package com.vayunmathur.screentime.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.screentime.FakeAndroidKeyStore
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * FrictionActivity as users reach it: unlike the other activities it does not
 * collect the rules-backed ViewModel state, so it fully composes on the host.
 * (Dashboard/WindDown/AppDetails compose from that state and are covered at
 * create() level in ActivitiesAndReceiversTest - their state flow cannot open
 * SQLCipher under Robolectric.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrictionActivityComposeTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun frictionActivityComposesNudge() {
        val scenario = ActivityScenario.launch<FrictionActivity>(
            FrictionActivity.intent(ctx, "com.a.app", nudge = true),
        )
        rule.onNodeWithText(ctx.getString(com.vayunmathur.screentime.R.string.friction_break)).assertExists()
        scenario.close()
    }
}
