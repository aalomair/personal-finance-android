package works.alomair.finance

class SmsIngestor(
    private val ledger: Ledger,
    private val providers: ProviderStore,
    private val schedule: () -> Unit,
) {
    fun receive(sender: String, body: String, receivedAt: Long): String? {
        if (!SmsFilter.isCandidate(body)) { ledger.ignored(); return null }
        val row = PendingMessage(sender = sender, body = body, receivedAt = receivedAt,
            bodyHash = SmsFilter.hash(sender, body, receivedAt), queuedProviderId = providers.selected().name)
        val inserted = ledger.enqueue(row)
        // Queue insert is committed first. Even a scheduling failure cannot lose a candidate.
        schedule()
        return if (inserted) row.id else null
    }
}

class ProcessingEngine(
    private val ledger: Ledger,
    private val providers: ProviderStore,
    private val client: ChatCompletionsClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val schedule: () -> Unit = {},
) {
    private val dao get() = ledger.dao
    private fun activeWork(provider: String) = dao.messages().filter {
        it.queuedProviderId == provider && it.status in setOf(QueueStatus.PENDING, QueueStatus.PROCESSING)
    }

    /** True asks WorkManager for backoff. Offline deferral does not consume attempts. */
    fun processBatch(networkAvailable: Boolean = true): Boolean {
        if (!networkAvailable) return activeWork(providers.selected().name).isNotEmpty()
        try {
            providers.withActive { config ->
                for (queued in activeWork(config.id.name)) {
                    if (queued.attempts >= 5) {
                        if (queued.status != QueueStatus.PROCESSING || queued.leaseUntil <= now())
                            dao.updateMessage(queued.copy(status = QueueStatus.FAILED_RETRYABLE, leaseUntil = 0, lastErrorCode = "ATTEMPTS_EXHAUSTED"))
                        continue
                    }
                    if (dao.claim(queued.id, config.id.name, now(), now() + 120_000) != 1) continue
                    val sms = dao.message(queued.id)!!
                    try {
                        val categories = dao.categories().filter { !it.hidden }.map { it.key }
                        val text = client.extract(config, sms, categories)
                        val result = ExtractionValidator.validate(text, sms, categories)
                        ledger.db.runInTransaction {
                            if (result.transaction == null) {
                                dao.updateMessage(sms.copy(status = QueueStatus.NEEDS_REVIEW, leaseUntil = 0,
                                    extractedJson = result.draftJson, lastErrorCode = result.errorCode))
                            } else {
                                val duplicate = ledger.duplicate(result.transaction)
                                if (duplicate != null) dao.updateMessage(sms.copy(status = QueueStatus.NEEDS_REVIEW,
                                    leaseUntil = 0, extractedJson = result.draftJson, lastErrorCode = duplicate.name))
                                else {
                                    dao.insertTransaction(result.transaction)
                                    dao.updateMessage(sms.copy(status = QueueStatus.DONE, leaseUntil = 0,
                                        extractedJson = result.draftJson, lastErrorCode = null))
                                }
                            }
                            val stats = dao.stats() ?: ProcessingStats()
                            dao.saveStats(stats.copy(lastProcessedAt = now()))
                        }
                    } catch (failure: ApiFailure) {
                        val malformed = sms.malformedAttempts + if (failure.malformed) 1 else 0
                        val status = when {
                            !failure.retryable -> QueueStatus.FAILED_CONFIG
                            failure.malformed && malformed >= 2 -> QueueStatus.NEEDS_REVIEW
                            sms.attempts >= 5 -> QueueStatus.FAILED_RETRYABLE
                            else -> QueueStatus.PENDING
                        }
                        dao.updateMessage(sms.copy(status = status, leaseUntil = 0, lastErrorCode = failure.code,
                            malformedAttempts = malformed, nextAttemptAt = now() + backoff(sms.attempts)))
                    }
                }
            }
        } catch (_: KeyUnavailable) {
            // Keystore invalidation/cipher corruption: keep SMS and stop, no key or exception text persisted.
            for (row in activeWork(providers.selected().name)) {
                if (row.status == QueueStatus.PENDING || row.leaseUntil <= now())
                    dao.updateMessage(row.copy(status = QueueStatus.FAILED_CONFIG, leaseUntil = 0, lastErrorCode = "KEY_UNAVAILABLE"))
            }
        }
        return activeWork(providers.selected().name).isNotEmpty()
    }

    fun retry(id: String) {
        ledger.db.runInTransaction {
            val row = dao.message(id) ?: throw IllegalArgumentException("MESSAGE")
            require(row.status !in setOf(QueueStatus.DONE, QueueStatus.DISMISSED, QueueStatus.PROCESSING)) { "STATE" }
            dao.updateMessage(row.copy(status = QueueStatus.PENDING, attempts = 0, malformedAttempts = 0,
                nextAttemptAt = 0, leaseUntil = 0, lastErrorCode = null))
        }
        schedule()
    }
    fun retryAll() {
        for (row in dao.messages()) if (row.status in setOf(QueueStatus.FAILED_CONFIG, QueueStatus.FAILED_RETRYABLE, QueueStatus.NEEDS_REVIEW)) retry(row.id)
    }
    fun reassign(ids: List<String>, provider: ProviderId, confirmed: Boolean) {
        require(confirmed) { "PROVIDER_CONFIRMATION_REQUIRED" }
        ledger.db.runInTransaction {
            for (id in ids) {
                val row = dao.message(id) ?: continue
                if (row.status in setOf(QueueStatus.DONE, QueueStatus.DISMISSED, QueueStatus.PROCESSING)) continue
                dao.updateMessage(row.copy(queuedProviderId = provider.name, status = QueueStatus.PENDING,
                    attempts = 0, malformedAttempts = 0, nextAttemptAt = 0, lastErrorCode = null))
            }
        }
        schedule()
    }
    fun accept(id: String, correction: FinanceTransaction, confirmProbable: Boolean = false): SaveResult {
        var result = SaveResult.SAVED
        ledger.db.runInTransaction {
            val sms = dao.message(id) ?: throw IllegalArgumentException("MESSAGE")
            require(sms.status !in setOf(QueueStatus.DONE, QueueStatus.DISMISSED, QueueStatus.PROCESSING)) { "STATE" }
            require(dao.transaction(correction.id) == null) { "TRANSACTION_EXISTS" }
            val row = correction.copy(source = "sms", sourceMessageId = id)
            result = ledger.saveManual(row, confirmProbable)
            if (result == SaveResult.SAVED) dao.updateMessage(sms.copy(status = QueueStatus.DONE, lastErrorCode = null, leaseUntil = 0))
        }
        return result
    }
    fun dismiss(id: String, confirmed: Boolean) {
        require(confirmed) { "DISMISS_CONFIRMATION_REQUIRED" }
        ledger.db.runInTransaction {
            val row = dao.message(id) ?: throw IllegalArgumentException("MESSAGE")
            require(row.status !in setOf(QueueStatus.PROCESSING, QueueStatus.DONE)) { "STATE" }
            dao.updateMessage(row.copy(status = QueueStatus.DISMISSED, leaseUntil = 0))
        }
    }
    companion object {
        fun backoff(attempt: Int): Long = 30_000L * (1L shl (attempt - 1).coerceIn(0, 4))
    }
}
