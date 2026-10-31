package works.alomair.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IngressConcurrencyTest {
    @Test fun smsPersistenceDoesNotWaitForAnInFlightRequest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = FinanceDatabase.open(context, "ingress-${UUID.randomUUID()}.db", test = true)
        val providers = ProviderStore.open(context)
        context.getSharedPreferences("providers", 0).edit().clear().commit()
        val executor = Executors.newFixedThreadPool(2)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val request = executor.submit {
                // This is the same lock held across the engine's bounded HTTP request.
                providers.withActive { held.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val ingress = executor.submit<String?> {
                SmsIngestor(Ledger(db), providers) {}.receive("SyntheticBank", "Purchase SAR 12.50 at Synthetic Cafe", 1000)
            }
            val id = ingress.get(2, TimeUnit.SECONDS)
            assertNotNull(id)
            assertEquals("GO", db.dao().message(id!!)!!.queuedProviderId)
            assertEquals(1, db.dao().messages().size)
            release.countDown()
            request.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            db.close()
        }
    }
}
