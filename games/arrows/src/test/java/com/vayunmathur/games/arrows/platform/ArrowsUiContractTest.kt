package com.vayunmathur.games.arrows.platform

import com.vayunmathur.games.arrows.Route
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.GameMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArrowsUiContractTest {

    @Test
    fun noopGameActionsDoNothingAndDoNotThrow() {
        val noop = ArrowsGameActions.Noop
        noop.tapArrow(3)
        noop.finishMove(1L)
        noop.nextLevel()
        noop.restartLevel()
        noop.setGameMode(GameMode.DAILY)
        // A custom object with only defaults also works
        val custom = object : ArrowsGameActions {}
        custom.tapArrow(0); custom.nextLevel()
        assertEquals(noop, ArrowsGameActions.Noop)
    }

    @Test
    fun noopSettingsActionsDoNothing() {
        val noop = SettingsActions.Noop
        noop.setShowRoutes(true)
        noop.setReminderEnabled(false)
        noop.setReminderTime(9, 30)
        val custom = object : SettingsActions {}
        custom.setShowRoutes(false)
        assertEquals(noop, SettingsActions.Noop)
    }

    @Test
    fun uiStateDefaults() {
        val s = ArrowsUiState()
        assertNull(s.game)
        assertEquals(GameMode.CASUAL, s.mode)
        assertEquals(1, s.level)
        assertTrue(s.showRoutes)
        assertEquals(0L, s.dailyStreak)
        assertFalse(s.dailyDone)
        assertFalse(s.generating)
        assertFalse(s.generationFailed)
        assertTrue(s.moves.isEmpty())
    }

    @Test
    fun uiStateCopyRetainsUnchangedFields() {
        val game = ArrowsGameState(ArrowsPuzzle(3, 3, emptyList()), emptySet(), 3, 2, GameMode.DAILY)
        val move = ArrowMove(1, listOf(0, 1), 1, true)
        val s = ArrowsUiState(game = game, mode = GameMode.DAILY, level = 5, showRoutes = false, dailyStreak = 7, dailyDone = true, generating = true, moves = listOf(move))
        assertEquals(game, s.game)
        assertEquals(listOf(move), s.moves)
        val copy = s.copy(generating = false)
        assertFalse(copy.generating)
        assertEquals(5, copy.level)
        assertEquals(7L, copy.dailyStreak)
        assertEquals(s, s.copy())
        assertNotEquals(s, copy)
    }

    @Test
    fun arrowMoveFields() {
        val m = ArrowMove(pieceId = 4, route = listOf(1, 2, 3), advance = 2, clears = false)
        assertEquals(4, m.pieceId)
        assertEquals(listOf(1, 2, 3), m.route)
        assertEquals(2, m.advance)
        assertFalse(m.clears)
        assertEquals(m, m.copy())
        assertNotEquals(m, m.copy(clears = true))
    }

    @Test
    fun settingsUiStateDefaultsAndCopy() {
        val s = SettingsUiState()
        assertTrue(s.showRoutes)
        assertFalse(s.reminderEnabled)
        assertEquals(20, s.reminderHour)
        assertEquals(0, s.reminderMinute)
        val changed = s.copy(reminderEnabled = true, reminderHour = 7, reminderMinute = 45, showRoutes = false)
        assertTrue(changed.reminderEnabled)
        assertEquals(7, changed.reminderHour)
        assertEquals(45, changed.reminderMinute)
        assertFalse(changed.showRoutes)
    }

    @Test
    fun routesAreDistinct() {
        assertEquals(Route.Game, Route.Game)
        assertNotEquals(Route.Game as Route, Route.Settings)
        assertNotEquals(Route.Game as Route, Route.GameCenter)
        assertNotEquals(Route.Settings as Route, Route.GameCenter)
    }

    @Test
    fun routesSerializeRoundTrip() {
        val json = kotlinx.serialization.json.Json
        for (route in listOf<Route>(Route.Game, Route.GameCenter, Route.Settings)) {
            val encoded = json.encodeToString(Route.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.serializer(), encoded))
        }
    }

    @Test
    fun appBackupAgentListsBothDatastores() {
        assertEquals(listOf("settings", "datastore_default"), AppBackupAgent().datastoreNames)
    }
}
