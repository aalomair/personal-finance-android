package works.alomair.finance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS-w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FirstLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun freshActivityShowsTutorialBeforeLedger() {
        compose.waitForIdle()
        compose.onNodeWithTag("tutorial").assertIsDisplayed()
        compose.onNodeWithTag("dashboard_screen").assertDoesNotExist()
        org.junit.Assert.assertTrue(compose.activity.window.attributes.flags and
            android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
}
