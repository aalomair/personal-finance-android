package works.alomair.finance

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppShellTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun manifestRestrictsSmsReceiverAndDisablesBackupAndCleartext() {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS)
        assertTrue(info.requestedPermissions.contains(Manifest.permission.RECEIVE_SMS))
        assertTrue(info.requestedPermissions.contains(Manifest.permission.INTERNET))
        assertFalse(info.requestedPermissions.contains(Manifest.permission.READ_SMS))
        assertEquals(0, info.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals(0, info.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        assertNotEquals(0, info.applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL)
        val receiver = info.receivers.single { it.name == IncomingSmsReceiver::class.java.name }
        assertTrue(receiver.exported)
        assertEquals(Manifest.permission.BROADCAST_SMS, receiver.permission)
    }

    @Test fun incomingMultipartBroadcastQueuesCombinedTextAndIgnoresOtp() {
        context.deleteDatabase("finance.db")
        context.getSharedPreferences("providers", 0).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val db = FinanceDatabase.open(context, test = true)
        try {
            fun deliver(parts: List<String>) {
                val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).setPackage(context.packageName)
                    .putExtra("format", "3gpp").putExtra("pdus", parts.map { pdu(it) }.toTypedArray())
                context.sendBroadcast(intent)
                shadowOf(Looper.getMainLooper()).idle()
            }
            deliver(listOf("Purchase SAR 12.50 ", "at Synthetic Cafe card 4321"))
            await { db.dao().messages().isNotEmpty() }
            val sms = db.dao().messages().single()
            assertEquals("Purchase SAR 12.50 at Synthetic Cafe card 4321", sms.body)
            assertEquals("GO", sms.queuedProviderId)
            assertEquals("+1234567890", sms.sender)
            deliver(listOf("OTP 123456 for purchase SAR 12.50"))
            await { db.dao().stats()?.ignored == 1L }
            assertEquals(1, db.dao().messages().size)
        } finally { db.close() }
    }

    private fun pdu(body: String): ByteArray {
        // Synthetic SMS-DELIVER, UCS-2, numeric test sender. No real SMS copied.
        val header = "00040A912143658709000862010321030021".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val text = body.toByteArray(Charsets.UTF_16BE)
        return header + byteArrayOf(text.size.toByte()) + text
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        assertTrue("Receiver did not persist expected result", condition())
    }
}
