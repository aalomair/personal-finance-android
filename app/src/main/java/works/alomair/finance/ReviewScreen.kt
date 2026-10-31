package works.alomair.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource as s
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable fun ReviewScreen(data: LedgerSnapshot, action: (() -> Unit) -> Unit, session: FinanceSession,
    correct: (PendingMessage) -> Unit, modifier: Modifier = Modifier) {
    var dismiss by remember { mutableStateOf<PendingMessage?>(null) }
    var reassign by remember { mutableStateOf(false) }
    val foreign = data.review.filter { it.queuedProviderId != data.provider.name && it.status != QueueStatus.PROCESSING }
    val locale = uiLocale()
    LazyColumn(modifier.testTag("review_screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(s(R.string.review_intro), style = MaterialTheme.typography.bodyLarge) }
        if (foreign.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(s(R.string.provider_mismatch, foreign.size, providerName(data.provider)))
                    TextButton({ reassign = true }) { Text(s(R.string.reassign)) }
                }
            }
        }
        if (data.review.isNotEmpty()) item { OutlinedButton({ action { session.engine.retryAll() } }) { Text(s(R.string.retry_all)) } }
        if (data.review.isEmpty()) item { EmptyState(s(R.string.review_empty), s(R.string.review_empty_detail)) }
        items(data.review, key = { it.id }) { row ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(row.sender, style = MaterialTheme.typography.titleMedium)
                    Text(s(statusLabel(row.status)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(Instant.ofEpochMilli(row.receivedAt).atZone(LedgerZone).format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", locale)), style = MaterialTheme.typography.bodySmall)
                    Text(s(R.string.queued_provider, providerName(ProviderId.valueOf(row.queuedProviderId))), style = MaterialTheme.typography.bodySmall)
                    row.lastErrorCode?.let { Text(s(R.string.review_code, it), style = MaterialTheme.typography.bodySmall) }
                    Text(row.body, style = MaterialTheme.typography.bodyMedium)
                    if (row.status != QueueStatus.PROCESSING) {
                        Button({ correct(row) }, Modifier.fillMaxWidth().testTag("correct_${row.id}")) { Text(s(R.string.correct)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton({ action { session.engine.retry(row.id) } }) { Text(s(R.string.retry)) }
                            TextButton({ dismiss = row }) { Text(s(R.string.dismiss)) }
                        }
                    }
                }
            }
        }
    }
    dismiss?.let { row -> ConfirmDialog(s(R.string.dismiss_confirm), { action { session.engine.dismiss(row.id, true) }; dismiss = null }, { dismiss = null }) }
    if (reassign) ConfirmDialog(s(R.string.provider_confirm), {
        action { session.engine.reassign(foreign.map { it.id }, data.provider, true) }; reassign = false
    }, { reassign = false })
}
