package com.vayunmathur.euicc

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.euicc.platform.AddEsimActivity
import com.vayunmathur.euicc.platform.EuiccViewModel
import com.vayunmathur.euicc.platform.LuiActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import android.os.Looper
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NavigationAndActivitiesTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = EuiccViewModel(ApplicationProvider.getApplicationContext<Application>())
    private fun str(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test fun `navigation home shows app title`() {
        val vm = vm()
        rule.setContent { Navigation(vm) }
        rule.onNodeWithText(str(R.string.app_name)).assertExists()
    }

    @Test fun `navigation seeded with AddSim shows add sim screen`() {
        val vm = vm()
        rule.setContent { Navigation(vm, Route.AddSim) }
        rule.onNodeWithText(str(R.string.add_sim_body)).assertExists()
        rule.onNodeWithText(str(R.string.scan_qr_button)).assertExists()
    }

    @Test fun `navigation seeded with DeviceInfo shows device info`() {
        val vm = vm()
        rule.setContent { Navigation(vm, Route.DeviceInfo) }
        rule.onNodeWithText(str(R.string.device_info_title)).assertExists()
    }

    @Test fun `navigation seeded with ActivationCode shows code entry`() {
        val vm = vm()
        rule.setContent { Navigation(vm, Route.ActivationCode) }
        rule.onNodeWithText(str(R.string.activation_code_title)).assertExists()
    }

    @Test fun `navigation seeded with Home explicitly shows home`() {
        val vm = vm()
        rule.setContent { Navigation(vm, Route.Home) }
        rule.onNodeWithText(str(R.string.app_name)).assertExists()
    }

    @Test fun `lui activity creates and shows navigation`() {
        val controller = Robolectric.buildActivity(LuiActivity::class.java)
        controller.create()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing.not())
        controller.destroy()
    }

    @Test fun `add esim activity with valid universal link creates`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
            "https://esimsetup.android.com/esim_qrcode_provisioning?carddata=LPA:1\$smdp.example.com\$MATCH1",
        )).setClass(
            ApplicationProvider.getApplicationContext(),
            AddEsimActivity::class.java,
        )
        val controller = Robolectric.buildActivity(AddEsimActivity::class.java, intent)
        controller.create()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing.not())
        controller.destroy()
    }

    @Test fun `add esim activity with missing carddata creates add sim flow`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
            "https://esimsetup.android.com/esim_qrcode_provisioning",
        )).setClass(ApplicationProvider.getApplicationContext(), AddEsimActivity::class.java)
        val controller = Robolectric.buildActivity(AddEsimActivity::class.java, intent)
        controller.create()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing.not())
        controller.destroy()
    }

    @Test fun `add esim activity with lpa scheme creates`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("lpa:1\$smdp.example.com\$MATCH1"))
            .setClass(ApplicationProvider.getApplicationContext(), AddEsimActivity::class.java)
        val controller = Robolectric.buildActivity(AddEsimActivity::class.java, intent)
        controller.create()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing.not())
        controller.destroy()
    }

    @Test fun `add esim activity with malformed carddata creates`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
            "https://esimsetup.android.com/esim_qrcode_provisioning?carddata=not-a-code",
        )).setClass(ApplicationProvider.getApplicationContext(), AddEsimActivity::class.java)
        val controller = Robolectric.buildActivity(AddEsimActivity::class.java, intent)
        controller.create()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing.not())
        controller.destroy()
    }

    @Test fun `add esim activity with no intent data creates`() {
        val controller = Robolectric.buildActivity(AddEsimActivity::class.java, Intent())
        controller.create()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing.not())
        controller.destroy()
    }
}
