package works.alomair.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource as s
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val LedgerZone: ZoneId = ZoneId.of("Asia/Riyadh")
@Composable fun uiLocale(): Locale = LocalConfiguration.current.locales[0]
@Composable fun categoryLabel(category: Category): String = if (uiLocale().language == "ar") category.labelAr else category.labelEn
@Composable fun categoryName(key: String, categories: List<Category>): String = categories.firstOrNull { it.key == key }?.let { categoryLabel(it) } ?: key
@Composable fun providerName(id: ProviderId): String = s(when (id) { ProviderId.GO -> R.string.go; ProviderId.ROUTER -> R.string.router; ProviderId.CUSTOM -> R.string.custom })
fun typeLabel(type: String): Int = when (type) { "income" -> R.string.income; "transfer" -> R.string.transfer; else -> R.string.expense }
fun statusLabel(status: String): Int = when (status) {
    QueueStatus.PENDING -> R.string.status_pending; QueueStatus.PROCESSING -> R.string.status_processing
    QueueStatus.NEEDS_REVIEW -> R.string.status_review; QueueStatus.FAILED_CONFIG -> R.string.status_config
    QueueStatus.FAILED_RETRYABLE -> R.string.status_retry; QueueStatus.DONE -> R.string.status_done
    else -> R.string.status_dismissed
}

@Composable fun MonthPicker(month: YearMonth, change: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { change(month.minusMonths(1)) }, Modifier.testTag("previous_month")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, s(R.string.previous_month))
        }
        Text(month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", uiLocale())), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { change(month.plusMonths(1)) }, Modifier.testTag("next_month")) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, s(R.string.next_month))
        }
    }
}

@Composable fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }

@Composable fun EmptyState(title: String, detail: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun DashboardScreen(data: LedgerSnapshot, month: YearMonth, changeMonth: (YearMonth) -> Unit,
    edit: (FinanceTransaction) -> Unit, modifier: Modifier = Modifier) {
    val summaries = remember(data.transactions, month) { FinanceSummary.month(data.transactions, month, LedgerZone) }
    val shown = summaries.ifEmpty { listOf(CurrencySummary("SAR", 0, 0, 0, 0, emptyMap())) }
    val recent = data.transactions.filter { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(LedgerZone)) == month }.take(5)
    val locale = uiLocale()
    LazyColumn(modifier.testTag("dashboard_screen"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(s(R.string.overview), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { MonthPicker(month, changeMonth) }
        shown.forEach { summary ->
            item(key = summary.currency) {
                Card(colors = CardDefaults.cardColors(containerColor = if (summary.currency == "SAR") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(if (summary.currency == "SAR") 20.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(summary.currency, style = MaterialTheme.typography.labelLarge)
                        Text(s(R.string.net), style = MaterialTheme.typography.titleSmall)
                        Text(Money.format(summary.net, summary.currency, locale), style = if (summary.currency == "SAR") MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("net_${summary.currency}_${summary.income}"))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(s(R.string.income), style = MaterialTheme.typography.labelLarge)
                                Text(Money.format(summary.income, summary.currency, locale), style = MaterialTheme.typography.titleMedium)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(s(R.string.expense), style = MaterialTheme.typography.labelLarge)
                                Text(Money.format(summary.expense, summary.currency, locale), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AmountLine(s(R.string.transfer_in), Money.format(summary.transferIn, summary.currency, locale))
                    AmountLine(s(R.string.transfer_out), Money.format(summary.transferOut, summary.currency, locale))
                    Text(s(R.string.transfer_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionTitle(s(R.string.spending) + " · " + summary.currency) }
            if (summary.byCategory.isEmpty()) item { Text(s(R.string.no_spending), style = MaterialTheme.typography.bodyMedium) }
            summary.byCategory.entries.sortedByDescending { it.value }.forEach { (key, value) ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val share = summary.categoryShare(key)
                        val percent = java.text.NumberFormat.getPercentInstance(locale).format(share)
                        AmountLine(categoryName(key, data.categories), Money.format(value, summary.currency, locale) + " · " + percent)
                        // Float is only the painted bar fraction, never monetary storage or calculation.
                        LinearProgressIndicator(progress = { share.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp))
                    }
                }
            }
        }
        item { SectionTitle(s(R.string.recent)) }
        if (recent.isEmpty()) item { EmptyState(s(R.string.no_transactions), s(R.string.empty_ledger)) }
        items(recent, key = { it.id }) { TransactionCard(it, data.categories) { edit(it) } }
        item { Text(s(R.string.local_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun AmountLine(label: String, amount: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable fun TransactionCard(row: FinanceTransaction, categories: List<Category>, click: () -> Unit) {
    val locale = uiLocale()
    OutlinedCard(onClick = click, modifier = Modifier.fillMaxWidth().testTag("transaction_${row.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.merchant, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(Money.format(row.amountMinor, row.currency, locale), style = MaterialTheme.typography.titleSmall)
            }
            Text(s(typeLabel(row.type)) + (if (row.type == "transfer") " · " + s(if (row.direction == "in") R.string.incoming else R.string.outgoing) else "") + " · " + categoryName(row.categoryKey, categories), style = MaterialTheme.typography.bodyMedium)
            Text(listOf(row.bank, row.accountLabel).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { s(R.string.unlabelled) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Instant.ofEpochMilli(row.occurredAt).atZone(ZoneId.of(row.zone)).format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", locale)) + " · " + s(if (row.source == "sms") R.string.sms else R.string.manual), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun Choice(label: String, value: String, options: List<Pair<String, String>>, change: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("$label: ${options.firstOrNull { it.first == value }?.second ?: value}", maxLines = 2)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { change(key); expanded = false }) }
        }
    }
}

@Composable fun TransactionsScreen(data: LedgerSnapshot, month: YearMonth, changeMonth: (YearMonth) -> Unit,
    edit: (FinanceTransaction) -> Unit, modifier: Modifier = Modifier) {
    var search by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
    var monthOnly by rememberSaveable { mutableStateOf(false) }
    val rows = data.transactions.filter {
        (search.isBlank() || listOf(it.merchant, it.reference.orEmpty()).any { text -> text.contains(search, true) }) &&
            (type.isEmpty() || it.type == type) && (category.isEmpty() || it.categoryKey == category) &&
            (account.isEmpty() || it.bank + " · " + it.accountLabel == account) &&
            (!monthOnly || YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(LedgerZone)) == month)
    }
    LazyColumn(modifier.testTag("transactions_screen"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(search, { search = it }, label = { Text(s(R.string.search)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("search")) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(monthOnly, { monthOnly = !monthOnly }, label = { Text(s(if (monthOnly) R.string.selected_month else R.string.all_months)) })
                TextButton({ search = ""; type = ""; category = ""; account = ""; monthOnly = false }) { Text(s(R.string.clear_filters)) }
            }
        }
        if (monthOnly) item { MonthPicker(month, changeMonth) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(s(R.string.type), type, listOf("" to s(R.string.all_types)) + listOf("income", "expense", "transfer").map { it to s(typeLabel(it)) }, { type = it }, Modifier.weight(1f))
                Choice(s(R.string.category), category, listOf("" to s(R.string.all_categories)) + data.categories.map { it.key to categoryLabel(it) }, { category = it }, Modifier.weight(1f))
            }
        }
        item { Choice(s(R.string.account), account, listOf("" to s(R.string.all_accounts)) + data.transactions.map { it.bank + " · " + it.accountLabel }.distinct().map { it to it }, { account = it }) }
        if (rows.isEmpty()) item { EmptyState(s(R.string.no_results), s(R.string.empty_ledger)) }
        items(rows, key = { it.id }) { TransactionCard(it, data.categories) { edit(it) } }
    }
}
