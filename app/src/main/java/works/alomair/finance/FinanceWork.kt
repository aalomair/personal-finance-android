package works.alomair.finance

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object FinanceWork {
    const val NAME = "finance-pending-messages"
    fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<FinanceWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    fun enqueue(context: Context) {
        // APPEND_OR_REPLACE preserves arrivals during the current worker's final snapshot.
        // KEEP could lose that wake-up. Each successor reads the durable queue, never SMS in WorkData.
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
    }
}

class FinanceWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        var db: FinanceDatabase? = null
        return try {
            db = FinanceDatabase.open(applicationContext)
            val providers = ProviderStore.open(applicationContext)
            val engine = ProcessingEngine(Ledger(db), providers, ChatCompletionsClient(sessionId = providers.sessionId()))
            if (engine.processBatch()) Result.retry() else Result.success()
        } catch (_: Exception) {
            // Never log provider responses, key material, database rows or exception messages.
            // A claimed row has a lease; process death/DB failure is recovered on a later attempt.
            Result.retry()
        } finally { db?.close() }
    }
}
