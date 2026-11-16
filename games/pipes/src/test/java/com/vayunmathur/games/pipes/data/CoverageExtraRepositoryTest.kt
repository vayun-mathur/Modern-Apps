package com.vayunmathur.games.pipes.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.pipes.platform.PipesActions
import com.vayunmathur.games.pipes.platform.SettingsActions
import com.vayunmathur.games.pipes.platform.SettingsUiState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CoverageExtraRepositoryTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun progressRepositoryPrunesOldestAndClears() {
        val repo = ProgressRepository(ctx)
        for (i in 0 until 35) repo.save("lvl_$i", "state_$i")
        // Only the 30 most recent levels survive.
        assertNull(repo.load("lvl_0"))
        assertNull(repo.load("lvl_4"))
        assertEquals("state_5", repo.load("lvl_5"))
        assertEquals("state_34", repo.load("lvl_34"))
        // Re-saving an existing level makes it the most recent without duplicating it.
        repo.save("lvl_5", "updated")
        assertEquals("updated", repo.load("lvl_5"))
        repo.save("lvl_35", "state_35")
        assertEquals("updated", repo.load("lvl_5"))
        // Clearing removes the entry; clearing again is a no-op.
        repo.clear("lvl_5")
        assertNull(repo.load("lvl_5"))
        repo.clear("lvl_5")
        repo.clear("never_saved")
        assertEquals("state_35", repo.load("lvl_35"))
    }

    @Test fun progressRepositorySurvivesCorruptOrder() {
        val repo = ProgressRepository(ctx)
        repo.save("keep", "value")
        ctx.getSharedPreferences(ProgressRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("order", "not json").commit()
        // Corrupt recency data falls back to an empty order; the save still works.
        repo.save("after", "value2")
        assertEquals("value2", repo.load("after"))
        assertEquals("value", repo.load("keep"))
        assertEquals("pipes_progress", ProgressRepository.PREFS_NAME)
    }

    @Test fun contractDefaultsAndNoopActions() {
        val settings = SettingsUiState()
        assertEquals(false, settings.colorblind)
        assertEquals(false, settings.reminderEnabled)
        assertEquals(20, settings.reminderHour)
        assertEquals(0, settings.reminderMinute)
        // Preview no-op actions accept every callback without doing anything.
        PipesActions.Noop.startDraw(CellPos(0, 0))
        PipesActions.Noop.extendPath(CellPos(0, 1))
        PipesActions.Noop.commitDraw()
        PipesActions.Noop.onUndo()
        PipesActions.Noop.onRestart()
        SettingsActions.Noop.setColorblind(true)
        SettingsActions.Noop.setReminderEnabled(true)
        SettingsActions.Noop.setReminderTime(9, 30)
        val custom = SettingsUiState(colorblind = true, reminderEnabled = true, reminderHour = 9, reminderMinute = 30)
        assertEquals(true, custom.colorblind)
        assertEquals(30, custom.reminderMinute)
    }
}
