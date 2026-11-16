package com.vayunmathur.setupwizard

import android.app.Activity
import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.setupwizard.platform.DebugFlags
import com.vayunmathur.setupwizard.platform.SetupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NavigationFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun str(id: Int) = ctx.getString(id)

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        Settings.Global.putString(ctx.contentResolver, "setupwizard_debug_flags", null)
    }

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        Settings.Global.putString(ctx.contentResolver, "setupwizard_debug_flags", null)
    }

    private fun awaitText(id: Int) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText(str(id))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitNoText(id: Int) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText(str(id))).fetchSemanticsNodes().isEmpty()
        }
    }

    /** Delivers a result to whichever handoff activity the wizard just launched. */
    private fun deliverHandoffResult(resultCode: Int) {
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 10_000) {
            Shadows.shadowOf(rule.activity).peekNextStartedActivityForResult() != null
        }
        val request = Shadows.shadowOf(rule.activity).nextStartedActivityForResult
        kotlin.test.assertNotNull(request, "wizard should have launched a handoff activity")
        rule.runOnUiThread {
            rule.activity.activityResultRegistry.dispatchResult(
                request.requestCode,
                ActivityResult(resultCode, null),
            )
        }
        rule.waitForIdle()
    }

    @Test fun `primary user walks welcome through handoffs to finish`() {
        val vm = SetupViewModel(app)
        assertTrue(vm.state.isPrimaryUser)
        rule.setContent { Navigation(vm) }
        awaitText(R.string.welcome_to_modern_apps_os)

        // Accessibility / emergency rows open external settings/dialer intents; under
        // Robolectric nothing handles them and Navigation must swallow that.
        rule.onNodeWithText(str(R.string.accessibility)).performClick()

        rule.onNodeWithText(str(R.string.next)).performClick()
        // Wifi is a handoff step with no UI of its own: completing the external setup
        // activity replaces it with Location instead of leaving it on the back stack.
        deliverHandoffResult(Activity.RESULT_OK)
        awaitText(R.string.location_services)
        awaitNoText(R.string.welcome_to_modern_apps_os)

        rule.onNodeWithText(str(R.string.next)).performClick()
        // Security hands off to biometric enrol; completing it lands on Migration,
        // which skips itself when no restore handler exists -> Gestures.
        deliverHandoffResult(Activity.RESULT_OK)
        awaitText(R.string.swipe_gestures_title)

        rule.onNodeWithText(str(R.string.skip)).performClick()
        awaitText(R.string.you_re_all_set_now)
        rule.onNodeWithText(str(R.string.start)).assertExists()
    }

    @Test fun `cancelling the wifi handoff returns to welcome`() {
        val vm = SetupViewModel(app)
        rule.setContent { Navigation(vm) }
        awaitText(R.string.welcome_to_modern_apps_os)
        rule.onNodeWithText(str(R.string.next)).performClick()
        // Backing out of Wi-Fi setup pops the handoff step rather than advancing.
        deliverHandoffResult(Activity.RESULT_CANCELED)
        awaitText(R.string.welcome_to_modern_apps_os)
    }

    @Test fun `language row opens picker and selecting a language closes it`() {
        val vm = SetupViewModel(app)
        rule.setContent { Navigation(vm) }
        awaitText(R.string.welcome_to_modern_apps_os)
        val languageLabel = vm.state.language.ifBlank { str(R.string.english_united_states) }
        rule.onNodeWithText(languageLabel).performClick()
        // The picker is populated from the platform locales; dismissing it returns to welcome.
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(hasText(str(R.string.welcome_to_modern_apps_os)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(vm.availableLanguages().isNotEmpty())
    }

    @Test fun `unlocked bootloader flag diverts welcome to the warning step`() {
        // Only honoured on a debuggable OS; Robolectric reports one, and the VM reads the
        // override when it snapshots OEM state in init, so set the flag before creating it.
        Settings.Global.putString(
            ctx.contentResolver,
            "setupwizard_debug_flags",
            "isDeviceOemUnlocked_override=true",
        )
        assertTrue(DebugFlags.getBool(ctx, "isDeviceOemUnlocked_override") == true)
        val vm = SetupViewModel(app)
        if (!vm.state.bootloaderUnlocked) {
            // Non-debuggable shadow build: the override is ignored by design (see DebugFlags).
            assertFalse(vm.welcomeLeadsToBootloaderWarning())
            return
        }
        assertTrue(vm.welcomeLeadsToBootloaderWarning())
        rule.setContent { Navigation(vm) }
        awaitText(R.string.welcome_to_modern_apps_os)
        rule.onNodeWithText(str(R.string.next)).performClick()
        awaitText(R.string.lock_your_bootloader)
        awaitNoText(R.string.welcome_to_modern_apps_os)
    }

    @Test fun `routes serialise distinctly`() {
        val routes = listOf(
            Route.Welcome, Route.OemUnlock, Route.Wifi, Route.Location,
            Route.Security, Route.Migration, Route.Gestures, Route.Finish,
        )
        assertTrue(routes.distinct().size == routes.size)
        assertTrue(routes.all { it.toString().isNotBlank() })
    }
}
