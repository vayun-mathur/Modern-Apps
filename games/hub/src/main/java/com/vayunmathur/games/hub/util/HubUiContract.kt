package com.vayunmathur.games.hub.util

import com.vayunmathur.games.hub.data.YuuUiModel
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.viewmodel.CrossGameStats

/**
 * The UI contract between [com.vayunmathur.games.hub.viewmodel.GameHubViewModel] and the
 * three screens that the store listing is captured from.
 *
 * Those screens take a state value plus an actions interface rather than the ViewModel
 * itself, so they can be rendered by a `@Preview` — which is what the listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, never the reverse.
 *
 * The ViewModel exposes Room flows, not a single state object, so the `…Page` binders are
 * what collect the flows and assemble these values. Anything that needs a [android.content.Context]
 * — installed-package lookups, app icons, launching a game — is resolved there too, which
 * is what leaves the screens renderable with no device.
 */

/** What the dashboard draws. `playerName` is null until the profile row loads. */
data class DashboardUiState(
    val playerName: String? = null,
    val avatarSymbol: String? = null,
    val profileYuu: YuuUiModel? = null,
    val level: Int = 1,
    val title: String = "Beginner",
    val totalXp: Int = 0,
    val stats: CrossGameStats = CrossGameStats(),
    val recentlyPlayed: List<HubGameEntity> = emptyList(),
    val recentActivity: List<ActivityEventEntity> = emptyList(),
    /** gameId -> (unlocked, total). */
    val achievementProgressByGame: Map<String, Pair<Int, Int>> = emptyMap(),
    /** gameId -> (current, longest) daily-puzzle streak. */
    val dailyStreakByGame: Map<String, Pair<Int, Int>> = emptyMap(),
    /** Games whose package is actually present on the device. */
    val installedGameIds: Set<String> = emptySet(),
    /** gameId -> registered display name, so the activity feed never shows a raw id. */
    val gameNames: Map<String, String> = emptyMap(),
)

/** What the games list draws; the search/sort of it is the screen's own state. */
data class GamesListUiState(
    val games: List<HubGameEntity> = emptyList(),
    /** gameId -> (unlocked, total). */
    val achievementProgressByGame: Map<String, Pair<Int, Int>> = emptyMap(),
    /** gameId -> (current, longest) daily-puzzle streak. */
    val dailyStreakByGame: Map<String, Pair<Int, Int>> = emptyMap(),
    /** Games whose package is actually present on the device. */
    val installedGameIds: Set<String> = emptySet(),
)

/** What the profile screen draws. */
data class ProfileUiState(
    val playerName: String? = null,
    val avatarSymbol: String? = null,
    val profileYuu: YuuUiModel? = null,
    val level: Int = 1,
    val title: String = "Beginner",
    val totalXp: Int = 0,
    val stats: CrossGameStats = CrossGameStats(),
)

/**
 * Dashboard callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs. The
 * other two actions interfaces below follow the same arrangement.
 */
interface DashboardActions {
    fun openGame(gameId: String) {}
    fun openProfile() {}
    fun openActivity() {}
    fun openGamesList() {}
    fun playGame(game: HubGameEntity) {}
    fun openController() {}

    companion object {
        val Noop: DashboardActions = object : DashboardActions {}
    }
}

/** Games list callbacks. */
interface GamesListActions {
    fun openGame(gameId: String) {}
    fun playGame(game: HubGameEntity) {}

    companion object {
        val Noop: GamesListActions = object : GamesListActions {}
    }
}

/** Profile callbacks — implemented directly by the ViewModel, whose names already match. */
interface ProfileActions {
    fun updateDisplayName(name: String) {}
    fun updateAvatarSymbol(symbol: String?) {}
    fun updateProfilePose(poseId: String) {}

    companion object {
        val Noop: ProfileActions = object : ProfileActions {}
    }
}
