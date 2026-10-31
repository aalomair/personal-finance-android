package works.alomair.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FinanceWorkTest {
    @Test fun workUsesNetworkConstraintAndExponentialBackoffAndRunsEngine() {
        val spec = FinanceWork.request().workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(30_000L, spec.backoffDelayDuration)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("finance.db")
        context.getSharedPreferences("providers", 0).edit().clear().commit()
        val db = FinanceDatabase.open(context, test = true)
        val id = SmsIngestor(Ledger(db), ProviderStore.open(context)) {}.receive("Bank", "Purchase SAR 10.00", 1000)!!
        db.close()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = TestWorkerBuilder.from(context, FinanceWorker::class.java, executor).build()
            // WorkManager executes synchronous Workers off the Android main thread.
            assertEquals(ListenableWorker.Result.success(), executor.submit<ListenableWorker.Result> { worker.doWork() }.get())
            val after = FinanceDatabase.open(context, test = true)
            assertEquals(QueueStatus.FAILED_CONFIG, after.dao().message(id)!!.status)
            assertEquals("MISSING_KEY", after.dao().message(id)!!.lastErrorCode)
            after.close()
        } finally { executor.shutdownNow() }
    }
}
