package works.alomair.finance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.concurrent.Executors

/** Live messages only. The platform protects this exported receiver with BROADCAST_SMS. */
class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val sender = parts.first().originatingAddress ?: ""
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val receivedAt = System.currentTimeMillis()
        val pending = goAsync()
        executor.execute {
            var db: FinanceDatabase? = null
            try {
                db = FinanceDatabase.open(context.applicationContext)
                SmsIngestor(Ledger(db), ProviderStore.open(context.applicationContext)) {
                    FinanceWork.enqueue(context.applicationContext)
                }.receive(sender, body, receivedAt)
            } finally {
                db?.close()
                pending.finish()
            }
        }
    }
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}
