package works.alomair.finance

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** All Room / Keystore / HTTP work is called from Dispatchers.IO, never composition. */
class FinanceSession(val db: FinanceDatabase, val providers: ProviderStore, val schedule: () -> Unit) {
    val ledger = Ledger(db)
    val client = ChatCompletionsClient(sessionId = providers.sessionId())
    val engine = ProcessingEngine(ledger, providers, client, schedule = schedule)
    fun snapshot() = LedgerSnapshot(db.dao().transactions(), db.dao().categories(), db.dao().messages(),
        db.dao().stats() ?: ProcessingStats(), providers.selected())
    companion object {
        suspend fun open(context: Context): FinanceSession = withContext(Dispatchers.IO) {
            FinanceSession(FinanceDatabase.open(context), ProviderStore.open(context)) { FinanceWork.enqueue(context) }
                .also { it.schedule() } // Reconcile persisted work after app/process restart.
        }
    }
}

data class LedgerSnapshot(
    val transactions: List<FinanceTransaction> = emptyList(),
    val categories: List<Category> = SeedCategories.all,
    val messages: List<PendingMessage> = emptyList(),
    val stats: ProcessingStats = ProcessingStats(),
    val provider: ProviderId = ProviderId.GO,
) {
    val review get() = messages.filter { it.status !in setOf(QueueStatus.DONE, QueueStatus.DISMISSED) }
}
