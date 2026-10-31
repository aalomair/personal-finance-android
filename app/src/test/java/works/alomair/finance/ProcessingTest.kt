package works.alomair.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProcessingTest {
    private lateinit var db: FinanceDatabase
    private lateinit var ledger: Ledger
    private lateinit var providers: ProviderStore
    private lateinit var server: MockWebServer
    private lateinit var engine: ProcessingEngine
    private var time = Instant.parse("2026-10-30T09:30:00Z").toEpochMilli()
    private val body = "Purchase SAR 12.50 at Test Cafe card 4321 ref SYN12345"
    private var scheduled = 0

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("processing-test")
        db = FinanceDatabase.open(context, "processing-test", test = true)
        ledger = Ledger(db)
        val prefs = context.getSharedPreferences("processing-providers", 0).apply { edit().clear().commit() }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        providers = ProviderStore(prefs, AesGcmCipher { key })
        server = MockWebServer().apply { start() }
        providers.save(ProviderConfig(ProviderId.CUSTOM, server.url("/chat").toString(), "synthetic-model", "dummy-test-key"))
        providers.select(ProviderId.CUSTOM)
        engine = ProcessingEngine(ledger, providers, ChatCompletionsClient(sessionId = providers.sessionId()), { time }, { scheduled++ })
    }
    @After fun cleanup() { db.close(); server.close() }
    private fun ingest(text: String = body, timestamp: Long = time): PendingMessage {
        val id = SmsIngestor(ledger, providers, {
            assertTrue(db.dao().messages().isNotEmpty()) // Queue commit precedes scheduling/network.
            scheduled++
        }).receive("SyntheticBank", text, timestamp)!!
        return db.dao().message(id)!!
    }
    private fun answer(reference: String? = "SYN12345", confidence: String = "0.95", amount: String = "12.50"): String = JSONObject()
        .put("is_transaction", true).put("type", "expense").put("direction", "out").put("amount", amount)
        .put("currency", "SAR").put("occurred_at", JSONObject.NULL).put("merchant", "Test Cafe")
        .put("bank", "SyntheticBank").put("account", "4321").put("category", "coffee")
        .put("reference", reference ?: JSONObject.NULL).put("confidence", confidence).toString()
    private fun respond(content: String = answer()) {
        server.enqueue(MockResponse().setBody(JSONObject().put("choices", org.json.JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", content)))).toString()))
    }

    @Test fun queueIsDurableBeforeNetworkOtpIgnoredAndOfflineDoesNotConsumeAttempts() {
        val sms = ingest()
        val ingestor = SmsIngestor(ledger, providers) {}
        assertNull(ingestor.receive("Bank", "OTP 1234 Purchase SAR 12.50", time))
        assertNull(ingestor.receive("News", "A general update", time))
        assertEquals(2, db.dao().stats()!!.ignored.toInt())
        assertTrue(engine.processBatch(networkAvailable = false))
        assertEquals(0, db.dao().message(sms.id)!!.attempts)
        assertEquals(0, server.requestCount)
        respond()
        assertFalse(engine.processBatch())
        assertEquals(QueueStatus.DONE, db.dao().message(sms.id)!!.status)
        assertEquals(1250L, db.dao().transactions().single().amountMinor)
        val request = JSONObject(server.takeRequest().body.readUtf8())
        val user = JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(setOf("sender", "body", "received_at"), user.keys().asSequence().toSet())
        assertEquals(body, user.getString("body"))
        assertFalse(user.toString().contains(sms.id))
    }

    @Test fun duplicateDeliveryReferenceAndProbableDuplicateAreNotInserted() {
        val first = ingest()
        assertNull(SmsIngestor(ledger, providers) {}.receive("SyntheticBank", body, time))
        assertEquals(1, db.dao().messages().size)
        respond(); engine.processBatch()
        val repeat = ingest(body + " copy", time + 60_000)
        respond(); engine.processBatch()
        assertEquals("DUPLICATE_REFERENCE", db.dao().message(repeat.id)!!.lastErrorCode)
        assertEquals(QueueStatus.NEEDS_REVIEW, db.dao().message(repeat.id)!!.status)
        val probable = ingest(body.replace(" ref SYN12345", ""), time + 120_000)
        respond(answer(reference = null)); engine.processBatch()
        assertEquals("PROBABLE_DUPLICATE", db.dao().message(probable.id)!!.lastErrorCode)
        assertEquals(1, db.dao().transactions().size)
        assertEquals(QueueStatus.DONE, db.dao().message(first.id)!!.status)
    }

    @Test fun transientFailuresStopAtFiveAndManualRetryKeepsProvider() {
        val sms = ingest()
        repeat(5) { attempt ->
            server.enqueue(MockResponse().setResponseCode(429))
            val again = engine.processBatch()
            assertEquals(attempt < 4, again)
            assertEquals(attempt + 1, db.dao().message(sms.id)!!.attempts)
            time += 1_000_000
        }
        assertEquals(QueueStatus.FAILED_RETRYABLE, db.dao().message(sms.id)!!.status)
        assertEquals("HTTP_429", db.dao().message(sms.id)!!.lastErrorCode)
        engine.processBatch(); assertEquals(5, server.requestCount)
        engine.retry(sms.id)
        assertEquals(ProviderId.CUSTOM.name, db.dao().message(sms.id)!!.queuedProviderId)
        assertEquals(0, db.dao().message(sms.id)!!.attempts)
        respond(); engine.processBatch()
        assertEquals(QueueStatus.DONE, db.dao().message(sms.id)!!.status)
    }

    @Test fun authErrorsStopImmediatelyAndProviderChangesRequireConfirmation() {
        val sms = ingest()
        providers.select(ProviderId.GO)
        assertFalse(engine.processBatch())
        assertEquals(0, server.requestCount)
        assertEquals(0, db.dao().message(sms.id)!!.attempts)
        assertThrows(IllegalArgumentException::class.java) { engine.reassign(listOf(sms.id), ProviderId.GO, confirmed = false) }
        assertEquals("CUSTOM", db.dao().message(sms.id)!!.queuedProviderId)
        engine.reassign(listOf(sms.id), ProviderId.GO, confirmed = true)
        assertFalse(engine.processBatch())
        assertEquals(QueueStatus.FAILED_CONFIG, db.dao().message(sms.id)!!.status)
        assertEquals("MISSING_KEY", db.dao().message(sms.id)!!.lastErrorCode)
        providers.select(ProviderId.CUSTOM)
        engine.reassign(listOf(sms.id), ProviderId.CUSTOM, confirmed = true)
        engine.retry(sms.id)
        server.enqueue(MockResponse().setResponseCode(401))
        assertFalse(engine.processBatch())
        assertEquals("HTTP_401", db.dao().message(sms.id)!!.lastErrorCode)
        assertEquals(QueueStatus.FAILED_CONFIG, db.dao().message(sms.id)!!.status)
        assertFalse(engine.processBatch()); assertEquals(1, server.requestCount)
    }

    @Test fun malformedIsRetriedOnceLowConfidenceDraftCanBeCorrectedAndDismissalIsRecorded() {
        val sms = ingest()
        respond("{bad")
        assertTrue(engine.processBatch())
        time += 1_000_000
        respond("{still bad")
        assertFalse(engine.processBatch())
        assertEquals(QueueStatus.NEEDS_REVIEW, db.dao().message(sms.id)!!.status)
        assertEquals(2, db.dao().message(sms.id)!!.attempts)
        engine.retry(sms.id)
        respond(answer(confidence = "0.5")); engine.processBatch()
        assertEquals("LOW_CONFIDENCE", db.dao().message(sms.id)!!.lastErrorCode)
        assertEquals("12.50", JSONObject(db.dao().message(sms.id)!!.extractedJson!!).getString("amount"))
        val corrected = FinanceTransaction(type = "expense", direction = "out", amountMinor = 1250, currency = "SAR",
            occurredAt = sms.receivedAt, bank = sms.sender, merchant = "Test Cafe", categoryKey = "coffee")
        assertEquals(SaveResult.SAVED, engine.accept(sms.id, corrected))
        assertEquals(sms.id, db.dao().transactions().single().sourceMessageId)
        assertThrows(IllegalArgumentException::class.java) { engine.accept(sms.id, corrected) }
        val dismissed = ingest(body + " second", time + 60_000)
        assertThrows(IllegalArgumentException::class.java) { engine.dismiss(dismissed.id, confirmed = false) }
        engine.dismiss(dismissed.id, confirmed = true)
        assertEquals(QueueStatus.DISMISSED, db.dao().message(dismissed.id)!!.status)
        assertEquals(body + " second", db.dao().message(dismissed.id)!!.body)
    }

    @Test fun staleProcessingLeaseIsRecoveredWithoutConcurrentDuplicateWork() {
        val sms = ingest()
        db.dao().updateMessage(sms.copy(status = QueueStatus.PROCESSING, attempts = 1, leaseUntil = time + 60_000))
        assertTrue(engine.processBatch())
        assertEquals(0, server.requestCount)
        time += 61_000
        respond(); assertFalse(engine.processBatch())
        assertEquals(QueueStatus.DONE, db.dao().message(sms.id)!!.status)
        assertEquals(2, db.dao().message(sms.id)!!.attempts)
    }

    @Test fun lostCredentialStopsExpiredLeasesRatherThanRetryingForever() {
        val sms = ingest()
        db.dao().updateMessage(sms.copy(status = QueueStatus.PROCESSING, attempts = 1, leaseUntil = time - 1))
        providers.preferences.edit().putString("CUSTOM.key", "invalid-ciphertext").commit()
        assertFalse(engine.processBatch())
        assertEquals(QueueStatus.FAILED_CONFIG, db.dao().message(sms.id)!!.status)
        assertEquals("KEY_UNAVAILABLE", db.dao().message(sms.id)!!.lastErrorCode)
        assertEquals(0, server.requestCount)
    }

    @Test fun failedFinalLeaseStopsAtCapAndRetryAllPreservesQueueOwnership() {
        val sms = ingest()
        db.dao().updateMessage(sms.copy(status = QueueStatus.PROCESSING, attempts = 5, leaseUntil = time - 1))
        assertFalse(engine.processBatch())
        assertEquals(QueueStatus.FAILED_RETRYABLE, db.dao().message(sms.id)!!.status)
        engine.retryAll()
        providers.select(ProviderId.GO)
        assertFalse(engine.processBatch())
        assertEquals("CUSTOM", db.dao().message(sms.id)!!.queuedProviderId)
        assertEquals(QueueStatus.PENDING, db.dao().message(sms.id)!!.status)
        assertEquals(0, server.requestCount)
    }
}
