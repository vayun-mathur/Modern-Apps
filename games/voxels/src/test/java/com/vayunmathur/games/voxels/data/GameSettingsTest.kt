package com.vayunmathur.games.voxels.data

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.voxels.ui.SettingsSheet
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class GameSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `the debug readout is off by default`() {
        assertFalse(GameSettings().showDebug)
        assertFalse(GameSettingsStore.load(ctx).showDebug)
    }

    @Test fun `settings round-trip and are clamped`() {
        val s = GameSettings(lookSensitivity = 2f, invertY = true, musicVolume = 0.3f, sfxVolume = 0.6f, hudScale = 1.25f, showDebug = true)
        GameSettingsStore.save(ctx, s)
        assertEquals(s, GameSettingsStore.load(ctx))
        GameSettingsStore.save(ctx, GameSettings(lookSensitivity = 99f, hudScale = 0f, musicVolume = -1f))
        val clamped = GameSettingsStore.load(ctx)
        assertEquals(GameSettings.MAX_SENSITIVITY, clamped.lookSensitivity)
        assertEquals(GameSettings.MIN_HUD_SCALE, clamped.hudScale)
        assertEquals(0f, clamped.musicVolume)
    }

    @Test fun `the sheet toggles debug and invert-y and closes`() {
        var current = GameSettings()
        var closed = false
        rule.setContent { SettingsSheet(settings = current, onChange = { current = it }, onClose = { closed = true }) }
        rule.onNodeWithText("Show debug info").assertExists()
        rule.onNodeWithText("Done").performClick()
        assertTrue(closed)
    }
}
