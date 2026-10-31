package works.alomair.finance

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
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
import java.time.YearMonth
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: FinanceDatabase
    private lateinit var session: FinanceSession
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val date get() = YearMonth.now().atDay(15).atTime(12, 30).atZone(LedgerZone).toInstant().toEpochMilli()
    @Before fun setup() {
        db = FinanceDatabase.open(context, "ui-${UUID.randomUUID()}.db", test = true)
        val prefs = context.getSharedPreferences("ui-${UUID.randomUUID()}", 0)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        session = FinanceSession(db, ProviderStore(prefs, AesGcmCipher { key })) {}
    }
    @After fun cleanup() { db.close() }
    private fun start() { compose.setContent { FinanceTheme { FinanceApp(session) } }; compose.waitForIdle() }
    private fun seed() {
        val dao = db.dao()
        listOf(
            FinanceTransaction(type = "income", direction = "in", amountMinor = 850000, currency = "SAR", occurredAt = date, merchant = "Synthetic salary", bank = "Test Bank", accountLabel = "4321", categoryKey = "income"),
            FinanceTransaction(type = "expense", direction = "out", amountMinor = 23675, currency = "SAR", occurredAt = date - 60000, merchant = "Sample Market", bank = "Test Bank", accountLabel = "4321", categoryKey = "groceries"),
            FinanceTransaction(type = "expense", direction = "out", amountMinor = 1850, currency = "SAR", occurredAt = date - 120000, merchant = "مقهى تجريبي", bank = "Test Bank", accountLabel = "9876", categoryKey = "coffee"),
            FinanceTransaction(type = "transfer", direction = "out", amountMinor = 40000, currency = "SAR", occurredAt = date - 180000, merchant = "Savings transfer", bank = "Test Bank", accountLabel = "4321", categoryKey = "transfers"),
            FinanceTransaction(type = "expense", direction = "out", amountMinor = 2300, currency = "USD", occurredAt = date - 240000, merchant = "Sample Store", bank = "Demo Bank", accountLabel = "1111", categoryKey = "shopping"),
        ).forEach { dao.insertTransaction(it) }
        dao.enqueue(PendingMessage(sender = "TestBank", body = "Purchase SAR 18.50 at Sample Cafe card 4321", receivedAt = date,
            bodyHash = "ui-synthetic", queuedProviderId = "GO", status = QueueStatus.NEEDS_REVIEW, lastErrorCode = "LOW_CONFIDENCE",
            extractedJson = JSONObject().put("amount", "18.50").put("currency", "SAR").put("merchant", "Sample Cafe").put("type", "expense").put("category", "coffee").put("account", "4321").toString()))
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File(System.getProperty("finance.screens") ?: "build/screenshots").apply { mkdirs() }
        compose.activity.window.decorView.captureRoboImage(File(dir, "$name.png").absolutePath)
    }
    private fun waitForTag(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun dialogShot(name: String) {
        compose.waitForIdle()
        val global = org.robolectric.util.ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
        val views = org.robolectric.util.ReflectionHelpers.getField<List<android.view.View>>(global, "mViews")
        // A dialog is a separate window; Espresso's activity-root capture cannot select it.
        compose.runOnIdle {
            val view = views.last()
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val output = File(System.getProperty("finance.screens")!!, "$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }

    @Test fun englishNavigationAndRepresentativeScreens() {
        seed(); start()
        compose.onNodeWithTag("dashboard_screen").assertIsDisplayed()
        // An IO snapshot must have loaded, rather than screenshotting the empty initial state.
        waitForTag("net_SAR_850000")
        shot("dashboard-en")
        compose.onNodeWithTag("nav_1").performClick()
        compose.onNodeWithTag("transactions_screen").assertIsDisplayed(); shot("transactions-en")
        compose.onNodeWithTag("search").performTextInput("Sample Market")
        compose.onAllNodesWithText("Sample Market").assertCountEquals(2) // Search field and matching card.
        compose.onNodeWithText("Synthetic salary").assertDoesNotExist()
        compose.onNodeWithTag("nav_2").performClick()
        compose.onNodeWithTag("review_screen").assertIsDisplayed(); shot("review-en")
        compose.onNodeWithTag("nav_3").performClick()
        compose.onNodeWithTag("settings_screen").assertIsDisplayed(); shot("settings-en")
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("sms_status"))
        compose.onNodeWithTag("sms_status").assertIsDisplayed(); shot("settings-sms-en")
    }

    @Test @Config(qualifiers = "ar-rSA-ldrtl-w393dp-h852dp-mdpi")
    fun arabicRtlDashboardAndNavigation() {
        seed(); start()
        compose.onNodeWithTag("nav_0").assertTextContains("الرئيسية")
        waitForTag("net_SAR_850000")
        shot("dashboard-ar")
        compose.onNodeWithTag("nav_1").performClick(); shot("transactions-ar")
        compose.onNodeWithTag("nav_2").performClick(); shot("review-ar")
        compose.onNodeWithTag("nav_3").performClick(); shot("settings-ar")
    }

    @Test fun permissionDeniedManualCreateEditDeleteWorks() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS))
        start(); compose.onNodeWithTag("nav_1").performClick()
        compose.onNodeWithTag("add").performClick()
        compose.onNodeWithTag("edit_amount").performTextInput("42.50")
        compose.onNodeWithTag("edit_merchant").performTextInput("Synthetic manual purchase")
        dialogShot("manual-editor-en")
        compose.onNodeWithTag("save_transaction").performClick()
        compose.waitUntil(10_000) { db.dao().transactions().size == 1 }
        val row = db.dao().transactions().single()
        assertEquals(4250L, row.amountMinor)
        assertEquals("manual", row.source)
        waitForTag("transaction_${row.id}")
        compose.onNodeWithTag("transaction_${row.id}").performClick()
        compose.onNodeWithTag("edit_amount").performTextReplacement("45.00")
        compose.onNodeWithTag("save_transaction").performClick()
        compose.waitUntil(10_000) { db.dao().transactions().single().amountMinor == 4500L }
        waitForTag("transaction_${row.id}"); compose.onNodeWithTag("transaction_${row.id}").performClick()
        compose.onNodeWithTag("delete_transaction").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(10_000) { db.dao().transactions().isEmpty() }
    }

    @Test fun reviewDraftCanBeCorrectedAndAccepted() {
        seed(); start(); compose.onNodeWithTag("nav_2").performClick()
        val message = db.dao().messages().single()
        waitForTag("correct_${message.id}")
        compose.onNodeWithTag("correct_${message.id}").performClick()
        compose.onNodeWithTag("edit_amount").assertTextContains("18.50")
        compose.onNodeWithTag("edit_merchant").assertTextContains("Sample Cafe")
        compose.onNodeWithTag("save_transaction").performClick()
        compose.waitUntil(10_000) { db.dao().message(message.id)?.status == QueueStatus.DONE }
        assertEquals("sms", db.dao().transactions().first { it.sourceMessageId == message.id }.source)
    }

    @Test fun providerSaveAndCategoryManagementAreWired() {
        start(); compose.onNodeWithTag("nav_3").performClick()
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("provider_model"))
        compose.onNodeWithTag("provider_model").performTextReplacement("glm-5.3-flash")
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("save_provider"))
        compose.onNodeWithTag("save_provider").performClick()
        compose.waitUntil(10_000) { session.providers.preferences.contains("GO.model") }
        assertEquals("glm-5.3-flash", session.providers.active().model)
        compose.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("add_category"))
        compose.onNodeWithTag("add_category").performClick()

        compose.onNodeWithTag("category_key").performTextInput("sample")
        compose.onNodeWithTag("category_en").performTextInput("Sample")
        compose.onNodeWithTag("category_ar").performTextInput("تجريبي")
        dialogShot("category-editor-en")
        compose.onNodeWithTag("save_category").performClick()
        compose.waitUntil(10_000) { db.dao().categories().any { it.key == "sample" } }
    }

    @Test fun reviewRetryDismissAndProviderConfirmationAreWired() {
        seed()
        val queued = db.dao().messages().single()
        db.dao().updateMessage(queued.copy(status = QueueStatus.FAILED_CONFIG, queuedProviderId = "ROUTER"))
        start(); compose.onNodeWithTag("nav_2").performClick()
        compose.onNodeWithText("Change queued provider").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("ROUTER", db.dao().message(queued.id)!!.queuedProviderId)
        compose.onNodeWithText("Change queued provider").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(10_000) { db.dao().message(queued.id)!!.queuedProviderId == "GO" }
        compose.onNodeWithText("Retry", substring = false).performClick()
        compose.waitUntil(10_000) { db.dao().message(queued.id)!!.status == QueueStatus.PENDING }
        compose.onNodeWithText("Dismiss", substring = false).performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(QueueStatus.PENDING, db.dao().message(queued.id)!!.status)
        compose.onNodeWithText("Dismiss", substring = false).performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(10_000) { db.dao().message(queued.id)!!.status == QueueStatus.DISMISSED }
        assertEquals(queued.body, db.dao().message(queued.id)!!.body)
    }

    @Test @Config(qualifiers = "en-rUS-w393dp-h852dp-night-mdpi")
    fun darkThemeRenders() { seed(); start(); waitForTag("net_SAR_850000"); shot("dashboard-dark") }
}
