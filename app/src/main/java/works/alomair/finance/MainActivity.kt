package works.alomair.finance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource as s
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.time.YearMonth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FinanceTheme {
                var session by remember { mutableStateOf<FinanceSession?>(null) }
                var failed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    try { session = FinanceSession.open(applicationContext) }
                    catch (_: Exception) { failed = true }
                }
                val current = session
                TutorialGate {
                    if (current == null) Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
                            Text(s(if (failed) R.string.error else R.string.loading))
                        }
                    } else FinanceApp(current)
                }
                DisposableEffect(current) {
                    onDispose { current?.let { CoroutineScope(Dispatchers.IO).launch { it.db.close() } } }
                }
            }
        }
        // Prevent sensitive financial screens / key entry appearing in recents or screen captures.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }
}

@Composable fun FinanceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF91D5BC), onPrimary = Color(0xFF00382A),
        primaryContainer = Color(0xFF18513F), onPrimaryContainer = Color(0xFFADF1D7),
        surface = Color(0xFF101B18), background = Color(0xFF101B18),
    ) else lightColorScheme(
        primary = Color(0xFF176B52), onPrimary = Color.White,
        primaryContainer = Color(0xFFD6F1E4), onPrimaryContainer = Color(0xFF103D2E),
        surface = Color(0xFFF7FAF8), background = Color(0xFFF7FAF8),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FinanceApp(session: FinanceSession) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(LedgerSnapshot()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthText)
    val snackbar = remember { SnackbarHostState() }
    var permission by remember { mutableIntStateOf(permissionState(context as? ComponentActivity)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = permissionState(context as? ComponentActivity)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = permissionState(context as? ComponentActivity)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val refresh: suspend () -> Unit = { snapshot = withContext(Dispatchers.IO) { session.snapshot() } }
    LaunchedEffect(session) {
        while (isActive) {
            try { refresh() } catch (_: Exception) { snackbar.showSnackbar(context.getString(R.string.error)) }
            delay(2_000)
        }
    }
    // Exceptions never expose API responses, SMS, credentials, or raw database errors.
    val action: (() -> Unit) -> Unit = { block ->
        scope.launch {
            try { withContext(Dispatchers.IO) { block() }; refresh() }
            catch (_: Exception) { snackbar.showSnackbar(context.getString(R.string.error)) }
        }
    }
    var editor by remember { mutableStateOf<TransactionEditor?>(null) }
    var showTutorial by rememberSaveable { mutableStateOf(false) }
    if (showTutorial) {
        TutorialScreen(onComplete = { showTutorial = false })
        return
    }
    val labels = listOf(R.string.dashboard, R.string.transactions, R.string.review, R.string.settings)
    val icons = listOf(Icons.Default.Home, Icons.AutoMirrored.Filled.List, Icons.Default.Warning, Icons.Default.Settings)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text(s(labels[tab]), style = MaterialTheme.typography.headlineSmall) }) },
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        modifier = Modifier.testTag("nav_$index"),
                        icon = {
                            BadgedBox(badge = {
                                if (index == 2 && snapshot.review.isNotEmpty()) Badge { Text(snapshot.review.size.toString()) }
                            }) { Icon(icons[index], contentDescription = null) }
                        }, label = { Text(s(label), maxLines = 1) })
                }
            }
        },
        floatingActionButton = {
            if (tab in 0..1) FloatingActionButton(onClick = { editor = TransactionEditor() }, modifier = Modifier.testTag("add")) {
                Icon(Icons.Default.Add, s(R.string.add_transaction))
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        when (tab) {
            0 -> DashboardScreen(snapshot, month, { monthText = it.toString() }, { editor = TransactionEditor(it) }, modifier)
            1 -> TransactionsScreen(snapshot, month, { monthText = it.toString() }, { editor = TransactionEditor(it) }, modifier)
            2 -> ReviewScreen(snapshot, action, session, { editor = TransactionEditor(message = it) }, modifier)
            3 -> SettingsScreen(snapshot, session, action, permission,
                requestPermission = {
                    (context as? ComponentActivity)?.getPreferences(0)?.edit()?.putBoolean("askedSms", true)?.apply()
                    launcher.launch(Manifest.permission.RECEIVE_SMS)
                }, openSettings = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }, showTutorial = { showTutorial = true }, modifier = modifier)
        }
    }
    editor?.let { draft ->
        TransactionDialog(draft, snapshot.categories, session, onClose = { editor = null }, onChanged = { action {} })
    }
}

private fun permissionState(activity: ComponentActivity?): Int {
    if (activity == null) return R.string.permission_denied
    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)
        return R.string.permission_granted
    val fixed = activity.packageManager.isPermissionRevokedByPolicy(Manifest.permission.RECEIVE_SMS, activity.packageName)
    val asked = activity.getPreferences(0).getBoolean("askedSms", false)
    return if (fixed || (asked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)))
        R.string.permission_restricted else R.string.permission_denied
}
