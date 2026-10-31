package works.alomair.finance

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource as s
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only Skip / Finish write the app-private flag; navigation and replay never reset it. */
class TutorialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    fun completed() = preferences.getBoolean("tutorial_completed_v1", false)
    fun complete(): Boolean = preferences.edit().putBoolean("tutorial_completed_v1", true).commit()
}

@Composable fun TutorialGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { TutorialStore(context) }
    var completed by remember { mutableStateOf<Boolean?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { completed = withContext(Dispatchers.IO) { store.completed() } }
    when (completed) {
        null -> Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        true -> content()
        false -> TutorialScreen(busy = saving, saveFailed = saveFailed, onComplete = {
            if (!saving) {
                saving = true
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { store.complete() }
                    saving = false; saveFailed = !saved
                    if (saved) completed = true
                }
            }
        })
    }
}

@Composable fun TutorialScreen(onComplete: () -> Unit, busy: Boolean = false, saveFailed: Boolean = false) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf(R.string.tutorial_welcome, R.string.tutorial_flow, R.string.tutorial_control,
        R.string.tutorial_privacy, R.string.tutorial_ready)
    val bodies = listOf(R.string.tutorial_welcome_body, R.string.tutorial_flow_body, R.string.tutorial_control_body,
        R.string.tutorial_setup_body, R.string.tutorial_ready_body)
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    BackHandler(enabled = page > 0 && !busy) { page-- }
    Surface(Modifier.fillMaxSize().testTag("tutorial")) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 600.dp).heightIn(max = 960.dp).fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(s(R.string.tutorial_label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onComplete, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("tutorial_skip")) { Text(s(R.string.tutorial_skip)) }
                }
                LinearProgressIndicator(progress = { (page + 1) / titles.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(s(R.string.tutorial_page, page + 1, titles.size), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 16.dp).testTag("tutorial_page").semantics { liveRegion = LiveRegionMode.Polite })
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (page == 0 || page == titles.lastIndex) {
                        Image(painterResource(R.drawable.tutorial_icon), contentDescription = null, modifier = Modifier.size(112.dp))
                    }
                    Text(s(titles[page]), style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.fillMaxWidth().testTag("tutorial_heading").semantics { heading() })
                    Text(s(bodies[page]), style = MaterialTheme.typography.bodyLarge)
                    if (page == 3) {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Text(s(R.string.disclosure), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(s(R.string.permission_help), style = MaterialTheme.typography.bodyMedium)
                        Text(s(R.string.go_warning), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (saveFailed) Text(s(R.string.error), color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ page-- }, enabled = page > 0 && !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("tutorial_back")) { Text(s(R.string.tutorial_back)) }
                    Button({ if (page == titles.lastIndex) onComplete() else page++ }, enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("tutorial_next")) {
                        Text(s(if (page == titles.lastIndex) R.string.tutorial_finish else R.string.tutorial_next))
                    }
                }
            }
        }
    }
}
