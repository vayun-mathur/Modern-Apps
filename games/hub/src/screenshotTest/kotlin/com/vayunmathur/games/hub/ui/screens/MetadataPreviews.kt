package com.vayunmathur.games.hub.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.games.hub.data.YuuUiModel
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig
import com.vayunmathur.games.hub.ui.components.AvatarIcon
import com.vayunmathur.games.hub.ui.yuu.YuuEditorActions
import com.vayunmathur.games.hub.ui.yuu.YuuEditorUiState
import com.vayunmathur.games.hub.ui.yuu.YuuListActions
import com.vayunmathur.games.hub.ui.yuu.YuuListUiState
import com.vayunmathur.games.hub.util.DashboardActions
import com.vayunmathur.games.hub.util.DashboardUiState
import com.vayunmathur.games.hub.util.GamesListActions
import com.vayunmathur.games.hub.util.GamesListUiState
import com.vayunmathur.games.hub.util.ProfileActions
import com.vayunmathur.games.hub.util.ProfileUiState
import com.vayunmathur.games.hub.viewmodel.CrossGameStats
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconSportsEsports
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop/tablet window — comfortably above the 840dp expanded-width threshold. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/**
 * Store listing images for `:games:hub`, rendered from Compose previews instead of from an
 * instrumented test on a device. See `common-conventions-preview-metadata`.
 *
 * The three shots match what the old on-device generator produced — dashboard, games list,
 * profile — but the sample data is literal here rather than seeded into Room.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * Timestamps are offsets from a fixed instant ([NOW]) so the "2h ago" style
 * labels stay in their intended bucket and the rendered text is identical on
 * every run (no relative-to-now fixtures).
 *
 * Coverage: `Dashboard`, `Games`, `Profile`, `Yuu` (list/empty/editor) and
 * `PlayOnTv` (controller-pad discovery) on phone, plus the dashboard on
 * large-tablet.
 * Exceptions (no stateless slice; GameHubViewModel over Room): `Achievements`,
 * `AchievementsForGame`, `GameDetail`, `Activity` and `Settings` are not
 * preview-rendered. The Yuu editor's 3D avatar surface is the screen's own
 * preview seam as already rendered by `Preview7YuuEditor`.
 */
class MetadataPreviews {

    /** 2026-01-17T12:00Z — fixed, never the wall clock. */
    private val now = 1_768_651_200_000L

    private val games = listOf(
        HubGameEntity("chess", "com.vayunmathur.games.chess", "Chess", "Classic chess with puzzles and a human-like AI", "1.0", registeredAt = now - 28 * DAY, lastSeenAt = now - HOUR, lastPlayedAt = now - HOUR, totalPlaytimeMs = 5 * HOUR + 23 * 60_000L, totalSessions = 42),
        HubGameEntity("pipes", "com.vayunmathur.games.pipes", "Pipes", "Connect the pipes", registeredAt = now - 20 * DAY, lastSeenAt = now - 5 * HOUR, lastPlayedAt = now - 5 * HOUR, totalPlaytimeMs = 2 * HOUR + 45 * 60_000L, totalSessions = 35),
        HubGameEntity("solitaire", "com.vayunmathur.games.solitaire", "Solitaire", "Classic Klondike", registeredAt = now - 18 * DAY, lastSeenAt = now - 8 * HOUR, lastPlayedAt = now - 8 * HOUR, totalPlaytimeMs = 4 * HOUR, totalSessions = 51),
        HubGameEntity("wordmaker", "com.vayunmathur.games.wordmaker", "Word Maker", "Create words from letters", registeredAt = now - 15 * DAY, lastSeenAt = now - 12 * HOUR, lastPlayedAt = now - 12 * HOUR, totalPlaytimeMs = HOUR + 30 * 60_000L, totalSessions = 19),
        HubGameEntity("unblockjam", "com.vayunmathur.games.unblockjam", "Unblock Jam", "Slide blocks to clear the path", registeredAt = now - 10 * DAY, lastSeenAt = now - DAY, lastPlayedAt = now - DAY - 2 * HOUR, totalPlaytimeMs = 55 * 60_000L, totalSessions = 12),
    )

    private val achievementProgress = mapOf(
        "chess" to (2 to 3),
        "pipes" to (1 to 1),
        "solitaire" to (1 to 1),
        "wordmaker" to (1 to 1),
        "unblockjam" to (1 to 1),
    )

    /** 1225 XP is level 4 ("Casual Gamer") under [com.vayunmathur.games.hub.util.XpLevelCalculator]. */
    private val stats = CrossGameStats(
        totalPlaytimeMs = 14 * HOUR + 38 * 60_000L,
        totalSessions = 159,
        totalGames = 5,
        totalAchievementsUnlocked = 6,
        totalAchievements = 7,
        totalXp = 1225,
        level = 4,
        currentStreak = 5,
        longestStreak = 12,
    )

    @PreviewTest
    @Preview(name = "1-dashboard", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Dashboard() {
        ChromePreview(selectedTab = 0)
    }

    @PreviewTest
    @Preview(name = "2-games", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Games() {
        ChromePreview(selectedTab = 1)
    }

    @PreviewTest
    @Preview(name = "3-profile", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Profile() {
        ChromePreview(selectedTab = 3)
    }

    @Composable
    private fun DashboardContent() {
        DashboardScreen(
            state = DashboardUiState(
                playerName = "Alex Rivera",
                level = 4,
                title = "Casual Gamer",
                totalXp = 1225,
                stats = stats,
                recentlyPlayed = games.take(3),
                recentActivity = listOf(
                    ActivityEventEntity(id = 1, type = ActivityEventEntity.TYPE_SESSION_COMPLETED, gameId = "chess", title = "Played Chess", description = "Session 25m", timestamp = now - HOUR),
                    ActivityEventEntity(id = 2, type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED, gameId = "solitaire", title = "Card Shark", description = "Won 10 games", timestamp = now - 2 * HOUR),
                    ActivityEventEntity(id = 3, type = ActivityEventEntity.TYPE_LEVEL_UP, title = "Reached level 4", description = "Casual Gamer", timestamp = now - 6 * HOUR),
                ),
                achievementProgressByGame = achievementProgress,
                installedGameIds = games.mapTo(mutableSetOf()) { it.gameId },
            ),
            actions = DashboardActions.Noop,
        )
    }

    @Composable
    private fun GamesContent() {
        GamesListScreen(
            state = GamesListUiState(
                games = games,
                achievementProgressByGame = achievementProgress,
                installedGameIds = games.mapTo(mutableSetOf()) { it.gameId },
            ),
            actions = GamesListActions.Noop,
        )
    }

    @Composable
    private fun ProfileContent() {
        ProfileScreen(
            state = ProfileUiState(
                playerName = "Alex Rivera",
                avatarSymbol = "stadia_controller",
                level = 4,
                title = "Casual Gamer",
                totalXp = 1225,
                stats = stats,
            ),
            actions = ProfileActions.Noop,
        )
    }

    /**
     * The app's real tab chrome around preview screen content: the same
     * [TabbedPagerScaffold] with the same Home/Games/Achievements/Profile tabs
     * the activity declares (release build — no dev-only Yuu tab). Achievements
     * needs its ViewModel, so it shows an empty page (never visible — only the
     * selected tab renders); the rest are the real screens with literal state.
     */
    @Composable
    private fun ChromePreview(selectedTab: Int) {
        DynamicTheme(darkTheme = true) {
            val tabs = listOf(
                PagerTab("Home", { IconDashboard() }) { DashboardContent() },
                PagerTab("Games", { IconSportsEsports() }) { GamesContent() },
                PagerTab("Achievements", { IconEmojiEvents() }) { Box(Modifier.fillMaxSize()) },
                PagerTab("Profile", { AvatarIcon(symbol = "stadia_controller") }) { ProfileContent() },
            )
            TabbedPagerScaffold(
                tabs = tabs,
                pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { tabs.size }),
                tabStyle = TabStyle.BottomNav,
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    // CHROME EXEMPT: custom wide dashboard+games two-pane, not a tab destination.
    fun Preview4Expanded() {
        DynamicTheme(darkTheme = true) {
            // Expanded windows keep the dashboard visible while the games list
            // opens beside it instead of covering it. GameDetailScreen reads
            // its ViewModel internally, so the previewable GamesListScreen
            // stands in as the detail pane.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    DashboardScreen(
                        state = DashboardUiState(
                            playerName = "Alex Rivera",
                            level = 4,
                            title = "Casual Gamer",
                            totalXp = 1225,
                            stats = stats,
                            recentlyPlayed = games.take(3),
                            recentActivity = listOf(
                                ActivityEventEntity(id = 1, type = ActivityEventEntity.TYPE_SESSION_COMPLETED, gameId = "chess", title = "Played Chess", description = "Session 25m", timestamp = now - HOUR),
                                ActivityEventEntity(id = 2, type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED, gameId = "solitaire", title = "Card Shark", description = "Won 10 games", timestamp = now - 2 * HOUR),
                                ActivityEventEntity(id = 3, type = ActivityEventEntity.TYPE_LEVEL_UP, title = "Reached level 4", description = "Casual Gamer", timestamp = now - 6 * HOUR),
                            ),
                            achievementProgressByGame = achievementProgress,
                            installedGameIds = games.mapTo(mutableSetOf()) { it.gameId },
                        ),
                        actions = DashboardActions.Noop,
                    )
                }
                Box(Modifier.weight(0.6f).fillMaxHeight()) {
                    GamesListScreen(
                        state = GamesListUiState(
                            games = games,
                            achievementProgressByGame = achievementProgress,
                            installedGameIds = games.mapTo(mutableSetOf()) { it.gameId },
                        ),
                        actions = GamesListActions.Noop,
                    )
                }
            }
        }
    }

    @PreviewTest
    @Preview(name = "5-yuu", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview5Yuu() {
        DynamicTheme(darkTheme = true) {
            YuuScreen(
                state = YuuListUiState(
                    yuus = listOf(
                        YuuUiModel(1, "Alex", YuuFaceConfig.DEFAULT, isProfile = true, updatedAt = now),
                        YuuUiModel(2, "Sam", YuuFaceConfig(faceShape = 2, hairStyle = 2, eyeStyle = 3), isProfile = false, updatedAt = now),
                        YuuUiModel(3, "Jo", YuuFaceConfig(faceShape = 5, hairStyle = 4, glassesStyle = 1), isProfile = false, updatedAt = now),
                    ),
                    profileYuuId = 1,
                ),
                actions = YuuListActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "6-yuu-empty", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview6YuuEmpty() {
        DynamicTheme(darkTheme = true) {
            YuuScreen(
                state = YuuListUiState(),
                actions = YuuListActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "8-play-on-tv", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview8PlayOnTv() {
        DynamicTheme(darkTheme = true) {
            ControllerPad(
                state = ControllerPadState(
                    hosts = listOf(
                        com.vayunmathur.sdk.controller.ControllerHostInfo(
                            id = "tv-1",
                            instanceName = "Living Room TV",
                            friendlyName = "Living Room TV",
                            host = "192.168.1.20",
                            port = 5277,
                            protocolVersion = 1,
                            gameId = "kart",
                        ),
                    ),
                ),
                onConnect = {},
                onProveCode = {},
                onJoin = {},
                onInput = { _, _, _, _ -> },
                onLeave = {},
                onRescan = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "7-yuu-editor", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview7YuuEditor() {
        DynamicTheme(darkTheme = true) {
            YuuEditorScreen(
                state = YuuEditorUiState(
                    initialName = "Alex",
                    initialConfig = YuuFaceConfig(faceShape = 1, hairStyle = 2, eyeStyle = 1),
                ),
                actions = YuuEditorActions.Noop,
            )
        }
    }
}
