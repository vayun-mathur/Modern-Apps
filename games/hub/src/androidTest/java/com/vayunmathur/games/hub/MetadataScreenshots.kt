package com.vayunmathur.games.hub

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.games.hub.data.DB_NAME
import com.vayunmathur.games.hub.data.GamesHubDatabase
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.games.hub.data.entities.PlaySessionEntity
import com.vayunmathur.games.hub.data.entities.PlayerProfileEntity
import com.vayunmathur.library.room.buildDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator → generated metadata path `metadata_data/photos/games-hub/`
 * (keeps `games-hub.md`). 3 distinct shots only — not 4 duplicates.
 *
 * Uses ActivityScenario + UiAutomation + decorView.draw() — no Compose test
 * rule / Espresso → avoids InputManager.getInstance crash on API 36 and
 * blank takeScreenshot() dupes.
 *
 * Shots:
 *  1. Dashboard (level, streak, Continue playing)
 *  2. Games list (Chess, Alchemist, etc)
 *  3. Profile (level table — distinct from 1 & 2)
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun findNode(root: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (pred(root)) return root
        for (i in 0 until root.childCount) {
            val f = findNode(root.getChild(i), pred)
            if (f != null) return f
        }
        return null
    }

    private fun findAll(root: AccessibilityNodeInfo?, pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun t(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (pred(n)) out += n
            for (i in 0 until n.childCount) t(n.getChild(i))
        }
        t(root)
        return out
    }

    /** Click bottom nav by contentDescription (Home/Games/Achievements/Profile) — avoids text collisions like StatCard "Achievements". */
    private fun clickBottomItem(label: String): Boolean {
        val ui = instrumentation.uiAutomation
        repeat(15) {
            val root = ui.rootInActiveWindow ?: return@repeat
            // Prefer nodes whose contentDescription matches AND are clickable, in lower half of screen (bottom bar)
            val candidates = findAll(root) { it.contentDescription?.toString()?.equals(label, ignoreCase = true) == true }
            // Filter to those near bottom half
            val sorted = candidates.mapNotNull { node ->
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) return@mapNotNull null
                // bottom nav is in bottom ~15% of screen
                val isBottom = bounds.centerY() > 1600 // rough for 2340px tall
                Pair(node, isBottom)
            }.sortedWith(compareByDescending<Pair<AccessibilityNodeInfo, Boolean>> { it.second }.thenBy { it.first.childCount })

            for ((node, _) in sorted) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.isClickable == true) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(800)
                    return true
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Thread.sleep(800)
                    return true
                }
            }
            // Fallback: any clickable ancestor of a node with text label in bottom area
            val textCandidates = findAll(root) { it.text?.toString()?.equals(label, ignoreCase = true) == true }
            for (node in textCandidates) {
                val b = android.graphics.Rect()
                node.getBoundsInScreen(b)
                if (b.centerY() < 1600) continue
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target != null) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(800)
                    return true
                }
            }
            Thread.sleep(400)
        }
        return false
    }

    private fun waitText(text: String, timeoutMs: Long = 10000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                if (findNode(root) { it.text?.toString()?.contains(text, true) == true } != null) return true
                if (findNode(root) { it.contentDescription?.toString()?.contains(text, true) == true } != null) return true
            }
            Thread.sleep(300)
        }
        return false
    }

    private fun <A : Activity> snap(scenario: ActivityScenario<A>, index: Int) {
        Thread.sleep(1000)
        var bmp: Bitmap? = null
        scenario.onActivity { act ->
            val d = act.window.decorView
            val w = d.width.takeIf { it > 0 } ?: 1080
            val h = d.height.takeIf { it > 0 } ?: 2340
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.draw(Canvas(b))
            bmp = b
        }
        val final = bmp ?: return
        File(outDir, "$index.png").outputStream().use { final.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun dismissDialog() {
        val ui = instrumentation.uiAutomation
        repeat(2) {
            val root = ui.rootInActiveWindow ?: return
            for (label in listOf("Close", "Got it", "OK")) {
                for (node in findAll(root) { it.text?.toString()?.equals(label, true) == true }) {
                    var t: AccessibilityNodeInfo? = node
                    while (t != null && !t.isClickable) t = t.parent
                    if (t != null) {
                        t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Thread.sleep(400); return
                    }
                }
            }
            Thread.sleep(300)
        }
    }

    private fun seedDatabase() {
        val db = ctx.buildDatabase<GamesHubDatabase>(dbName = DB_NAME)
        runBlocking {
            db.gameDao().clearAll()
            db.achievementDao().clearDefs()
            db.achievementDao().clearProgress()
            db.sessionDao().clearAll()
            db.activityDao().clearAll()

            val now = System.currentTimeMillis()
            val day = 24L * 3600 * 1000

            db.profileDao().upsert(PlayerProfileEntity(id = 0, displayName = "Alex Rivera", avatarSymbol = "stadia_controller", createdAt = now - 30 * day))

            val games = listOf(
                HubGameEntity("chess", "com.vayunmathur.games.chess", "Chess", "Classic chess with puzzles and Stockfish", "1.0", registeredAt = now - 28 * day, lastSeenAt = now - 3600_000, lastPlayedAt = now - 3600_000, totalPlaytimeMs = 5 * 3600_000L + 23 * 60_000L, totalSessions = 42),
                HubGameEntity("alchemist", "com.vayunmathur.games.alchemist", "Alchemist", "Combine elements", registeredAt = now - 25 * day, lastSeenAt = now - 3 * 3600_000, lastPlayedAt = now - 3 * 3600_000, totalPlaytimeMs = 3 * 3600_000L, totalSessions = 28),
                HubGameEntity("pipes", "com.vayunmathur.games.pipes", "Pipes", "Connect the pipes", registeredAt = now - 20 * day, lastSeenAt = now - 5 * 3600_000, lastPlayedAt = now - 5 * 3600_000, totalPlaytimeMs = 2 * 3600_000L + 45 * 60_000L, totalSessions = 35),
                HubGameEntity("solitaire", "com.vayunmathur.games.solitaire", "Solitaire", "Classic Klondike", registeredAt = now - 18 * day, lastSeenAt = now - 8 * 3600_000, lastPlayedAt = now - 8 * 3600_000, totalPlaytimeMs = 4 * 3600_000L, totalSessions = 51),
                HubGameEntity("wordmaker", "com.vayunmathur.games.wordmaker", "Word Maker", "Create words from letters", registeredAt = now - 15 * day, lastSeenAt = now - 12 * 3600_000, lastPlayedAt = now - 12 * 3600_000, totalPlaytimeMs = 1 * 3600_000L + 30 * 60_000L, totalSessions = 19),
                HubGameEntity("unblockjam", "com.vayunmathur.games.unblockjam", "Unblock Jam", "Slide blocks to clear the path", registeredAt = now - 10 * day, lastSeenAt = now - day, lastPlayedAt = now - day - 2 * 3600_000, totalPlaytimeMs = 55 * 60_000L, totalSessions = 12),
            )
            games.forEach { db.gameDao().upsert(it) }

            val defs = listOf(
                AchievementDefEntity("chess", "first_win", "First Victory", "Win your first game", 25, 1, false, "BRONZE"),
                AchievementDefEntity("chess", "win_10", "Checkmate Master", "Win 10 games", 50, 10, false, "SILVER"),
                AchievementDefEntity("chess", "puzzle_5", "Puzzle Solver", "Solve 5 puzzles", 100, 5, false, "GOLD"),
                AchievementDefEntity("alchemist", "discover_10", "Apprentice", "Discover 10 elements", 25, 10, false, "BRONZE"),
                AchievementDefEntity("pipes", "solve_20", "Plumber", "Solve 20 levels", 50, 20, false, "SILVER"),
                AchievementDefEntity("solitaire", "win_10", "Card Shark", "Win 10 games", 50, 10, false, "SILVER"),
                AchievementDefEntity("wordmaker", "word_5", "Word Smith", "Make a 5-letter word", 25, 1, false, "BRONZE"),
                AchievementDefEntity("unblockjam", "solve_5", "Getting Unstuck", "Solve 5 levels", 25, 5, false, "BRONZE"),
            )
            db.achievementDao().upsertDefs(defs)

            val progress = listOf(
                AchievementProgressEntity("chess", "first_win", 1, true, now - 5 * day),
                AchievementProgressEntity("chess", "puzzle_5", 5, true, now - 3 * day),
                AchievementProgressEntity("alchemist", "discover_10", 10, true, now - 6 * day),
                AchievementProgressEntity("pipes", "solve_20", 20, true, now - 4 * day),
                AchievementProgressEntity("solitaire", "win_10", 10, true, now - 2 * day),
                AchievementProgressEntity("wordmaker", "word_5", 1, true, now - 7 * day),
                AchievementProgressEntity("unblockjam", "solve_5", 5, true, now - 9 * day),
            )
            db.achievementDao().upsertProgresses(progress)

            val sessions = mutableListOf<PlaySessionEntity>()
            var sc = 0
            fun addS(gid: String, off: Long, dur: Long) { sc++; sessions.add(PlaySessionEntity(gameId = gid, sessionId = "sess_$sc", startTime = now - off, endTime = now - off + dur, durationMs = dur)) }
            addS("chess", 3600_000, 25 * 60_000); addS("pipes", 2 * 3600_000, 15 * 60_000)
            addS("solitaire", day + 2 * 3600_000, 18 * 60_000)
            sessions.forEach { db.sessionDao().upsert(it) }

            val activities = listOf(
                ActivityEventEntity(type = ActivityEventEntity.TYPE_ACHIEVEMENT_UNLOCKED, gameId = "solitaire", title = "Card Shark", description = "Won 10 games", timestamp = now - 2 * 3600_000),
                ActivityEventEntity(type = ActivityEventEntity.TYPE_SESSION_COMPLETED, gameId = "chess", title = "Played Chess", description = "Session 25m", timestamp = now - 3600_000),
            )
            activities.forEach { db.activityDao().upsert(it) }
        }
    }

    @Test
    fun generateStoreScreenshots() {
        outDir
        seedDatabase()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitText("GameHub", 15000)
            Thread.sleep(1200)
            dismissDialog()

            // 1: dashboard — distinct
            snap(scenario, 1)

            // 2: games — bottom nav exact CD match in bottom half
            clickBottomItem("Games")
            waitText("Chess", 10000)
            Thread.sleep(700)
            snap(scenario, 2)

            // 3: profile — level table visually distinct from games list (fixes previous duplicate where Achievements click hit StatCard)
            clickBottomItem("Profile")
            waitText("Alex Rivera", 10000)
            Thread.sleep(700)
            snap(scenario, 3)
        }
    }
}
