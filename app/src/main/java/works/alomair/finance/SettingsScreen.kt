package works.alomair.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource as s
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable fun SettingsScreen(data: LedgerSnapshot, session: FinanceSession, action: (() -> Unit) -> Unit,
    permission: Int, requestPermission: () -> Unit, openSettings: () -> Unit, showTutorial: () -> Unit, modifier: Modifier = Modifier) {
    var categoryEditor by remember { mutableStateOf<Category?>(null) }
    val locale = uiLocale()
    LazyColumn(modifier.testTag("settings_screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            OutlinedButton(showTutorial, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("show_tutorial")) {
                Text(s(R.string.show_tutorial))
            }
        }
        item { ProviderSettings(session, data.provider, action) }
        item {
            OutlinedCard(Modifier.fillMaxWidth().testTag("sms_status")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(s(R.string.sms_status))
                    Text(s(permission), style = MaterialTheme.typography.titleSmall)
                    Button(requestPermission, enabled = permission != R.string.permission_granted, modifier = Modifier.fillMaxWidth()) { Text(s(R.string.enable_sms)) }
                    OutlinedButton(openSettings, Modifier.fillMaxWidth()) { Text(s(R.string.app_settings)) }
                    Text(s(R.string.permission_help), style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    Text(s(R.string.ignored_count, data.stats.ignored))
                    listOf(QueueStatus.PENDING, QueueStatus.PROCESSING, QueueStatus.NEEDS_REVIEW, QueueStatus.FAILED_CONFIG, QueueStatus.FAILED_RETRYABLE, QueueStatus.DONE, QueueStatus.DISMISSED).forEach { status ->
                        Text(s(R.string.status_count, s(statusLabel(status)), data.messages.count { it.status == status }), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(s(R.string.last_processed, data.stats.lastProcessedAt?.let {
                        Instant.ofEpochMilli(it).atZone(LedgerZone).format(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", locale))
                    } ?: s(R.string.never)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s(R.string.categories), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton({ categoryEditor = Category("", "", "") }, Modifier.testTag("add_category")) { Text(s(R.string.add_category)) }
            }
        }
        items(data.categories, key = { it.key }) { category ->
            OutlinedCard(onClick = { categoryEditor = category }, modifier = Modifier.fillMaxWidth().testTag("category_${category.key}")) {
                Column(Modifier.padding(16.dp)) {
                    Text(categoryLabel(category), style = MaterialTheme.typography.titleMedium)
                    Text(category.key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (category.hidden) Text(s(R.string.hidden), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    categoryEditor?.let { category -> CategoryDialog(category, data.categories, session, { categoryEditor = null }, { action {} }) }
}

@Composable private fun ProviderSettings(session: FinanceSession, active: ProviderId, action: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(active) }
    var model by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") } // Not saveable; never serialized in saved-instance-state.
    var keyPresent by remember { mutableStateOf(false) }
    var removeKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(provider) {
        loaded = false; notice = ""; key = ""; removeKey = false
        try {
            val config = withContext(Dispatchers.IO) { session.providers.load(provider) }
            model = config.model; endpoint = config.endpoint; keyPresent = config.apiKey.isNotBlank()
        } catch (_: KeyUnavailable) {
            model = provider.defaultModel; endpoint = provider.endpoint; keyPresent = false; removeKey = true
            notice = context.getString(R.string.connection_failed, "KEY_UNAVAILABLE")
        }
        loaded = true
    }
    suspend fun formConfig(): ProviderConfig = withContext(Dispatchers.IO) {
        val savedKey = if (removeKey || key.isNotBlank()) "" else session.providers.load(provider).apiKey
        ProviderConfig(provider, endpoint.trim(), model.trim(), if (removeKey) "" else key.ifBlank { savedKey })
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s(R.string.active_provider, providerName(active)), style = MaterialTheme.typography.titleMedium)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Text(s(R.string.disclosure), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Choice(s(R.string.provider), provider.name, ProviderId.values().map { it.name to providerName(it) }, { if (!busy) provider = ProviderId.valueOf(it) })
        if (provider == ProviderId.GO) Text(s(R.string.go_warning), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text(s(R.string.endpoint)) }, readOnly = provider != ProviderId.CUSTOM,
            enabled = loaded && !busy, modifier = Modifier.fillMaxWidth().testTag("provider_endpoint"))
        OutlinedTextField(model, { model = it }, label = { Text(s(R.string.model)) }, singleLine = true,
            enabled = loaded && !busy, modifier = Modifier.fillMaxWidth().testTag("provider_model"))
        OutlinedTextField(key, { key = it; removeKey = false }, label = { Text(s(R.string.api_key)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(), supportingText = { Text(s(R.string.key_hint)) },
            enabled = loaded && !busy, modifier = Modifier.fillMaxWidth().testTag("provider_key"))
        Text(s(if (keyPresent) R.string.key_saved else R.string.key_missing), style = MaterialTheme.typography.bodySmall)
        if (keyPresent) LabeledCheckbox(s(R.string.remove_key), removeKey, !busy) { removeKey = it }
        Text(s(R.string.provider_save_hint), style = MaterialTheme.typography.bodySmall)
        if (notice.isNotEmpty()) Text(notice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("provider_notice"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = loaded && !busy, modifier = Modifier.weight(1f).testTag("test_connection"), onClick = {
                busy = true
                scope.launch {
                    try {
                        val config = formConfig()
                        withContext(Dispatchers.IO) { session.client.testConnection(config) }
                        notice = context.getString(R.string.connection_ok)
                    } catch (failure: ApiFailure) { notice = context.getString(R.string.connection_failed, failure.code) }
                    catch (_: Exception) { notice = context.getString(R.string.error) }
                    finally { busy = false }
                }
            }) { Text(s(if (busy) R.string.testing else R.string.test_connection)) }
            Button(enabled = loaded && !busy, modifier = Modifier.weight(1f).testTag("save_provider"), onClick = {
                busy = true
                scope.launch {
                    try {
                        val config = formConfig()
                        withContext(Dispatchers.IO) { session.providers.save(config); session.providers.select(config.id); session.schedule() }
                        keyPresent = config.apiKey.isNotBlank(); key = ""; removeKey = false
                        notice = context.getString(R.string.saved); action {}
                    } catch (_: Exception) { notice = context.getString(R.string.error) }
                    finally { busy = false }
                }
            }) { Text(s(R.string.save)) }
        }
    }
}

@Composable private fun CategoryDialog(original: Category, categories: List<Category>, session: FinanceSession,
    close: () -> Unit, changed: () -> Unit) {
    val isNew = original.key.isEmpty()
    var key by remember { mutableStateOf(original.key) }
    var en by remember { mutableStateOf(original.labelEn) }
    var ar by remember { mutableStateOf(original.labelAr) }
    var hidden by remember { mutableStateOf(original.hidden) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.85f)) {
            Column(Modifier.padding(20.dp)) {
                Text(s(if (isNew) R.string.add_category else R.string.edit_category), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorField(key, { key = it }, R.string.category_key, "category_key", isNew && !busy)
                if (isNew) Text(s(R.string.category_key_hint), style = MaterialTheme.typography.bodySmall)
                EditorField(en, { en = it }, R.string.label_en, "category_en", !busy)
                EditorField(ar, { ar = it }, R.string.label_ar, "category_ar", !busy)
                LabeledCheckbox(s(R.string.hidden), hidden, key != "other" && !busy) { hidden = it }
                if (error) Text(s(R.string.category_invalid), color = MaterialTheme.colorScheme.error)
                if (!isNew && key != "other") TextButton({ confirmDelete = true }, enabled = !busy) { Text(s(R.string.delete), color = MaterialTheme.colorScheme.error) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(close, enabled = !busy) { Text(s(R.string.cancel)) }
            TextButton(enabled = !busy, modifier = Modifier.testTag("save_category"), onClick = {
                if ((isNew && categories.any { it.key == key }) || (key == "other" && hidden)) error = true
                else {
                    busy = true
                    scope.launch {
                        try { withContext(Dispatchers.IO) { session.ledger.saveCategory(Category(key, en.trim(), ar.trim(), hidden)) }; changed(); close() }
                        catch (_: Exception) { error = true }
                        finally { busy = false }
                    }
                }
            }) { Text(s(R.string.save)) }
                }
            }
        }
    }
    if (confirmDelete) ConfirmDialog(s(R.string.category_delete_confirm), {
        confirmDelete = false; busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { session.ledger.deleteCategory(key) }; changed(); close() }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }, { confirmDelete = false })
}

@Composable private fun LabeledCheckbox(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.padding(start = 12.dp))
    }
}
