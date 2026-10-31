package works.alomair.finance

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TutorialTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var session: FinanceSession
    private lateinit var store: TutorialStore
    private val generation = mutableIntStateOf(0)
    private var schedules = 0

    @Before fun setup() {
        context.getSharedPreferences("onboarding", 0).edit().clear().commit()
        store = TutorialStore(context)
        val db = FinanceDatabase.open(context, "tutorial-${UUID.randomUUID()}.db", test = true)
        val prefs = context.getSharedPreferences("tutorial-provider-${UUID.randomUUID()}", 0)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        session = FinanceSession(db, ProviderStore(prefs, AesGcmCipher { key })) { schedules++ }
    }
    @After fun cleanup() { session.db.close() }
    private fun start(fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FinanceTheme { key(generation.intValue) { TutorialGate { FinanceApp(session) } } }
            }
        }
    }
    private fun tag(name: String) = compose.onNodeWithTag(name)
    private fun await(name: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(name).fetchSemanticsNodes().isNotEmpty() }
        tag(name).assertIsDisplayed()
    }
    private fun relaunch() { compose.runOnIdle { generation.intValue++ } }
    private fun next() { tag("tutorial_next").performClick() }
    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File(System.getProperty("finance.screens"), "tutorial").apply { mkdirs() }
        compose.activity.window.decorView.captureRoboImage(File(dir, "$name.png").absolutePath)
    }

    @Test fun settingsReplayReturnsToSettingsWithoutResettingCompletion() {
        assertTrue(store.complete())
        start(); await("dashboard_screen")
        tag("nav_3").performClick()
        tag("show_tutorial").performClick()
        await("tutorial")
        assertTrue(store.completed())
        tag("tutorial_skip").performClick()
        await("settings_screen")
        assertTrue(store.completed())
        relaunch(); await("dashboard_screen")
        tag("tutorial").assertDoesNotExist()
    }

    @Test fun skipPersistsAcrossNewComposition() {
        start(); await("tutorial")
        assertFalse(store.completed())
        tag("tutorial_skip").performClick(); await("dashboard_screen")
        assertTrue(TutorialStore(context).completed())
        relaunch(); await("dashboard_screen")
        tag("tutorial").assertDoesNotExist()
    }

    @Test fun nextAndBackDoNotCompleteAndInterruptedTutorialRestarts() {
        start(); await("tutorial")
        tag("tutorial_back").assertIsNotEnabled()
        next(); tag("tutorial_page").assertTextEquals("Step 2 of 5")
        tag("tutorial_back").performClick()
        tag("tutorial_page").assertTextEquals("Step 1 of 5")
        repeat(3) { next() }
        assertFalse(store.completed())
        relaunch(); await("tutorial")
        tag("tutorial_page").assertTextEquals("Step 1 of 5")
        assertFalse(TutorialStore(context).completed())
    }

    @Test fun finishPersistsAndRelaunchSkipsTutorial() {
        start(); await("tutorial")
        repeat(4) { next(); assertFalse(store.completed()) }
        tag("tutorial_next").assertTextContains("Finish").performClick()
        await("dashboard_screen")
        assertTrue(TutorialStore(context).completed())
        relaunch(); await("dashboard_screen")
        tag("tutorial").assertDoesNotExist()
    }

    @Test fun everyPageOffersSkipWithoutPermissionOrLedgerSideEffects() {
        val providerBefore = session.providers.preferences.all.toMap()
        start(); await("tutorial")
        repeat(5) { page ->
            tag("tutorial_skip").assertIsDisplayed().assertIsEnabled()
            tag("tutorial_heading").assertIsDisplayed()
            if (page < 4) next()
        }
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECEIVE_SMS))
        assertEquals(0, schedules)
        assertTrue(session.db.dao().transactions().isEmpty())
        assertTrue(session.db.dao().messages().isEmpty())
        assertEquals(providerBefore, session.providers.preferences.all)
        assertFalse(store.completed())
        tag("tutorial_skip").performClick(); await("dashboard_screen")
        assertTrue(store.completed())
    }

    private fun renderPages(locale: String, fontScale: Float = 1f) {
        start(fontScale); await("tutorial")
        repeat(5) { page ->
            tag("tutorial_heading").assertIsDisplayed()
            tag("tutorial_heading").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading))
            tag("tutorial_heading").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { read ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(read(layouts))
                val layout = layouts.single()
                assertFalse("Heading must not clip: text=${layout.layoutInput.text}, size=${layout.size}, " +
                    "width=${layout.multiParagraph.width}, height=${layout.multiParagraph.height}, " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}", layout.hasVisualOverflow)
            }
            tag("tutorial_skip").assertIsDisplayed()
            tag("tutorial_next").assertIsDisplayed().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            shot("$locale-step-${page + 1}")
            if (page == 3) {
                // Long privacy/setup copy is intentionally scrollable; the
                // permission note and provider caveat must both be reachable.
                compose.onNodeWithText(context.getString(R.string.permission_help)).performScrollTo().assertIsDisplayed()
                shot("$locale-permission-note")
                compose.onNodeWithText(context.getString(R.string.go_warning)).performScrollTo().assertIsDisplayed()
                shot("$locale-provider-note")
            }
            if (page < 4) next()
        }
    }

    @Test fun englishPagesRender() { renderPages("en") }

    @Test @Config(qualifiers = "ar-rSA-ldrtl-w393dp-h852dp-mdpi")
    fun arabicPagesRenderWithMirroredControls() {
        renderPages("ar")
        tag("tutorial_next").assertTextContains("إنهاء")
        assertTrue(tag("tutorial_back").fetchSemanticsNode().boundsInRoot.left >
            tag("tutorial_next").fetchSemanticsNode().boundsInRoot.left)
    }

    @Test @Config(qualifiers = "en-rUS-w393dp-h852dp-night-mdpi")
    fun darkPagesRender() { renderPages("dark") }

    @Test @Config(qualifiers = "en-rUS-w320dp-h640dp-mdpi")
    fun smallPhoneLargeTextRenders() { renderPages("en-small-130", 1.3f) }

    @Test @Config(qualifiers = "ar-rSA-ldrtl-w320dp-h640dp-mdpi")
    fun arabicSmallPhoneLargeTextRenders() { renderPages("ar-small-130", 1.3f) }

    @Test @Config(qualifiers = "en-rUS-w800dp-h1280dp-mdpi")
    fun tabletRenders() { renderPages("tablet") }
}
