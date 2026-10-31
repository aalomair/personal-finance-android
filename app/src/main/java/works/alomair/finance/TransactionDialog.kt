package works.alomair.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource as s
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class TransactionEditor(val transaction: FinanceTransaction? = null, val message: PendingMessage? = null)

@Composable fun ConfirmDialog(text: String, confirm: () -> Unit, cancel: () -> Unit) {
    AlertDialog(onDismissRequest = cancel, text = { Text(text) },
        confirmButton = { TextButton(confirm) { Text(s(R.string.confirm)) } },
        dismissButton = { TextButton(cancel) { Text(s(R.string.cancel)) } })
}

@Composable fun EditorField(value: String, change: (String) -> Unit, label: Int, tag: String = "", enabled: Boolean = true) {
    OutlinedTextField(value, change, label = { Text(s(label)) }, enabled = enabled, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable fun TransactionDialog(editor: TransactionEditor, categories: List<Category>, session: FinanceSession,
    onClose: () -> Unit, onChanged: () -> Unit) {
    val original = editor.transaction
    val sms = editor.message
    val json = remember(editor) { try { JSONObject(sms?.extractedJson ?: "{}") } catch (_: Exception) { JSONObject() } }
    fun draft(key: String, fallback: String = ""): String = if (json.isNull(key)) fallback else json.optString(key, fallback)
    val zone = remember(editor) { ZoneId.of(original?.zone ?: "Asia/Riyadh") }
    val received = remember(editor) { Instant.ofEpochMilli(original?.occurredAt ?: sms?.receivedAt ?: System.currentTimeMillis()).atZone(zone) }
    val initialTime = remember(editor) { try { OffsetDateTime.parse(draft("occurred_at")).atZoneSameInstant(zone) } catch (_: Exception) { received } }
    val id = remember(editor) { original?.id ?: UUID.randomUUID().toString() }
    var type by remember { mutableStateOf(original?.type ?: draft("type", "expense").takeIf { it in listOf("income", "expense", "transfer") } ?: "expense") }
    var direction by remember { mutableStateOf(original?.direction ?: draft("direction", "out")) }
    var amount by remember { mutableStateOf(original?.let { Money.decimal(it.amountMinor, it.currency).toPlainString() } ?: draft("amount")) }
    var currency by remember { mutableStateOf(original?.currency ?: draft("currency", "SAR")) }
    var merchant by remember { mutableStateOf(original?.merchant ?: draft("merchant")) }
    var bank by remember { mutableStateOf(original?.bank ?: sms?.sender ?: "") }
    var account by remember { mutableStateOf(original?.accountLabel ?: draft("account")) }
    var reference by remember { mutableStateOf(original?.reference ?: draft("reference")) }
    var category by remember { mutableStateOf(original?.categoryKey ?: draft("category", "other").takeIf { key -> categories.any { it.key == key } } ?: "other") }
    var date by remember { mutableStateOf(initialTime.toLocalDate().toString()) }
    var time by remember { mutableStateOf(initialTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))) }
    var error by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var confirmedFingerprint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f)) {
            Column(Modifier.padding(20.dp)) {
                Text(s(if (sms != null) R.string.accept else if (original == null) R.string.add_transaction else R.string.edit_transaction), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Choice(s(R.string.type), type, listOf("expense", "income", "transfer").map { it to s(typeLabel(it)) }, { type = it })
                    if (type == "transfer") Choice(s(R.string.direction), direction,
                        listOf("in" to s(R.string.incoming), "out" to s(R.string.outgoing)), { direction = it })
                    EditorField(amount, { amount = it }, R.string.amount, "edit_amount")
                    EditorField(currency, { currency = it.uppercase(Locale.ROOT) }, R.string.currency, "edit_currency")
                    EditorField(merchant, { merchant = it }, R.string.merchant, "edit_merchant")
                    Choice(s(R.string.category), category, categories.filter { !it.hidden || it.key == category }.map { it.key to categoryLabel(it) }, { category = it })
                    EditorField(bank, { bank = it }, R.string.bank, "edit_bank")
                    EditorField(account, { account = it }, R.string.account, "edit_account")
                    EditorField(date, { date = it }, R.string.date, "edit_date")
                    EditorField(time, { time = it }, R.string.time, "edit_time")
                    EditorField(reference, { reference = it }, R.string.reference, "edit_reference")
                }
                if (error != 0) Text(s(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("editor_error"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClose, enabled = !busy) { Text(s(R.string.cancel)) }
                    Button(enabled = !busy, modifier = Modifier.testTag("save_transaction"), onClick = {
                        val fingerprint = listOf(type, direction, amount, currency, merchant, category, bank, account, date, time, reference).joinToString("\u0000")
                        val row = try {
                            require(merchant.isNotBlank() && merchant.length <= 300 && bank.length <= 300 && account.length <= 300 && reference.length <= 300)
                            val occurred = LocalDateTime.of(LocalDate.parse(Money.normalize(date)), LocalTime.parse(Money.normalize(time))).atZone(zone).toInstant().toEpochMilli()
                            FinanceTransaction(id = id, type = type, direction = if (type == "income") "in" else if (type == "expense") "out" else direction,
                                amountMinor = Money.minor(amount, currency), currency = currency, occurredAt = occurred, zone = zone.id,
                                merchant = merchant.trim(), bank = bank.trim(), accountLabel = account.trim(), categoryKey = category,
                                reference = reference.trim().ifEmpty { null }, source = original?.source ?: "manual", sourceMessageId = original?.sourceMessageId,
                                createdAt = original?.createdAt ?: System.currentTimeMillis())
                        } catch (_: Exception) { error = R.string.check_fields; null }
                        if (row != null) {
                            busy = true
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        if (sms == null) session.ledger.saveManual(row, confirmedFingerprint == fingerprint)
                                        else session.engine.accept(sms.id, row, confirmedFingerprint == fingerprint)
                                    }
                                    when (result) {
                                        SaveResult.SAVED -> { onChanged(); onClose() }
                                        SaveResult.DUPLICATE_REFERENCE -> error = R.string.duplicate_reference
                                        SaveResult.PROBABLE_DUPLICATE -> { error = R.string.probable_duplicate; confirmedFingerprint = fingerprint }
                                    }
                                } catch (_: Exception) { error = R.string.error }
                                finally { busy = false }
                            }
                        }
                    }) { Text(s(if (sms == null) R.string.save else R.string.accept)) }
                }
                if (original != null) TextButton({ deleteConfirm = true }, enabled = !busy, modifier = Modifier.testTag("delete_transaction")) {
                    Text(s(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (deleteConfirm) ConfirmDialog(s(R.string.delete_confirm), confirm = {
        deleteConfirm = false; busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { session.db.dao().deleteTransaction(id) }; onChanged(); onClose() }
            catch (_: Exception) { error = R.string.error }
            finally { busy = false }
        }
    }, cancel = { deleteConfirm = false })
}
