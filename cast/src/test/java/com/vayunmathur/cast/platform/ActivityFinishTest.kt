package com.vayunmathur.cast.platform

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairActivityFinishTest {

    @get:Rule
    val rule = createAndroidComposeRule<CastPairActivity>()

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() = runBlocking { CastController.teardown() }

    @Test
    fun `pair activity finishes itself when the session is not awaiting`() {
        // The activity launches against an idle session (the prompt's dismiss
        // condition) and moving to Paired keeps it dismissed either way.
        CastController.sessionStateMutable.value = ClientState(phase = ClientPhase.Paired)
        rule.waitForIdle()
        assertTrue(rule.activity.isFinishing, "pair prompt must dismiss when nothing is awaiting")
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickerActivityFinishTest {

    @get:Rule
    val rule = createAndroidComposeRule<CastPickerActivity>()

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() = runBlocking { CastController.teardown() }

    @Test
    fun `picker finishes with ok once the controller reports connected`() {
        rule.waitForIdle()
        CastController.deviceMutable.value =
            CastDevice(id = "tv1", friendlyName = "TV", host = "127.0.0.1", port = 9)
        CastController.sessionStateMutable.value = ClientState(phase = ClientPhase.Paired)
        rule.waitForIdle()
        assertTrue(rule.activity.isFinishing, "picker should finish on Connected")
        assertEquals(
            android.app.Activity.RESULT_OK,
            Shadows.shadowOf(rule.activity).resultCode,
        )
    }

    @Test
    fun `picker starts cancelled`() {
        assertEquals(
            android.app.Activity.RESULT_CANCELED,
            Shadows.shadowOf(rule.activity).resultCode,
        )
    }
}
