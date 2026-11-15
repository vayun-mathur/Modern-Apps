package com.vayunmathur.games.hub.provider

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.hub.data.GamesHubDatabase
import com.vayunmathur.games.hub.data.GamesHubRepository
import com.vayunmathur.games.hub.data.entities.AchievementDefEntity
import com.vayunmathur.games.hub.data.entities.AchievementProgressEntity
import com.vayunmathur.games.hub.data.entities.HubGameEntity
import com.vayunmathur.library.util.databases
import com.vayunmathur.sdk.games.GameHubContract
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HubConvertersMoreTest {
    companion object { private var sharedDb: GamesHubDatabase? = null }
    private lateinit var repo: GamesHubRepository
    private lateinit var provider: GamesHubProvider

    @BeforeTest fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val mem = sharedDb ?: Room.inMemoryDatabaseBuilder(app, GamesHubDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build().also { sharedDb = it }
        synchronized(databases) { databases[GamesHubDatabase::class] = mem }
        repo = GamesHubRepository.get(app)
        provider = GamesHubProvider()
    }
    private fun v(block: ContentValues.() -> Unit) = ContentValues().apply(block)
    private fun uri(path: String) = Uri.parse("content://${GameHubContract.AUTHORITY}/$path")

    @Test fun gameConverterLegacyUriAndDefaults() {
        val legacy = v { put("game_id","legacy-game"); put("package_name","com.legacy"); put("display_name","Legacy"); put("version_code",7L); put("registered_at",111L); put("last_seen_at",222L) }
        val g = valuesToGame(legacy, uri("games"))
        assertEquals("legacy-game", g.gameId); assertEquals(7L, g.versionCode); assertEquals(111L, g.registeredAt)
        // URI segment fallback + defaults from gameId
        val fromUri = valuesToGame(v{}, uri("games/seg-game"))
        assertEquals("seg-game", fromUri.gameId); assertEquals("unknown", fromUri.packageName); assertEquals("seg-game", fromUri.displayName)
        assertTrue(fromUri.registeredAt > 0)
        // Contract and legacy keys are the same column name in this contract, so the last put wins.
        val both = v { put(GameHubContract.Games.GAME_ID,"contract-id"); put("game_id","legacy-id") }
        assertEquals("legacy-id", valuesToGame(both, uri("games/other")).gameId)
        assertFailsWith<IllegalStateException> { valuesToGame(v{}, uri("games")) }
        // typed helpers: contract first, legacy second, missing null
        val hv = v { put("legacy_s","L"); put("legacy_l",5L); put("legacy_i",3) }
        assertEquals("L", hv.gameString("contract_s","legacy_s")); assertEquals(5L, hv.gameLong("contract_l","legacy_l")); assertEquals(3, hv.gameInt("contract_i","legacy_i"))
        assertNull(hv.gameString("nope","also-nope"))
    }

    @Test fun gameMergedPreservesExistingFields() = runBlocking {
        repo.games.upsertGame(HubGameEntity("merge-g","com.old","Old Name","old desc","1.0",9L,registeredAt=10,lastSeenAt=20))
        val merged = valuesToGameMerged(v{ put(GameHubContract.Games.DISPLAY_NAME,"New Name") }, uri("games/merge-g"), repo)
        assertEquals("New Name", merged.displayName); assertEquals("com.old", merged.packageName); assertEquals("old desc", merged.description); assertEquals(9L, merged.versionCode); assertEquals(10L, merged.registeredAt)
        // New game path delegates to valuesToGame
        val fresh = valuesToGameMerged(v{ put(GameHubContract.Games.PACKAGE_NAME,"com.new") }, uri("games/fresh-g"), repo)
        assertEquals("fresh-g", fresh.gameId); assertEquals("com.new", fresh.packageName)
        ensureGameExists("ensure-g", repo); assertEquals("Ensure-g", repo.games.getGame("ensure-g")?.displayName)
        val before = repo.games.getGame("ensure-g"); ensureGameExists("ensure-g", repo); assertEquals(before, repo.games.getGame("ensure-g"))
    }

    @Test fun achievementDefAndProgressConvertersAllBranches() {
        val def = valuesToAchievementDef(v{
            put(GameHubContract.AchievementDefs.ACHIEVEMENT_ID,"a1"); put("name","Named"); put("xp_reward",99); put("is_secret",1); put("tier","GOLD")
        }, uri("achievements/def-game/defs"))
        assertEquals("def-game", def.gameId); assertEquals(99, def.xpReward); assertTrue(def.isSecret); assertEquals("GOLD", def.tier)
        val defDefaults = valuesToAchievementDef(v{ put("achievement_id","a2") }, uri("achievements/g2/defs"))
        assertEquals("a2", defDefaults.name); assertEquals("", defDefaults.description); assertEquals(DEFAULT_XP_REWARD, defDefaults.xpReward); assertEquals(1, defDefaults.targetProgress); assertFalse(defDefaults.isSecret); assertEquals("BRONZE", defDefaults.tier)
        assertFailsWith<IllegalStateException> { valuesToAchievementDef(v{}, uri("achievements")) }
        assertFailsWith<IllegalStateException> { valuesToAchievementDef(v{ put("game_id","g") }, uri("achievements/g/defs")) }

        // progress URI forms
        assertEquals("g" to "ach", progressUriIds(uri("achievements/g/progress/ach")))
        assertEquals("g" to "ach", progressUriIds(uri("achievements/g/ach")))
        assertEquals("g" to "defs", progressUriIds(uri("achievements/g/defs")))
        assertEquals("g" to "progress", progressUriIds(uri("achievements/g/progress")))
        assertEquals(null to null, progressUriIds(uri("games")))
        val unlocked = valuesToProgress(v{ put("unlocked",1); put("unlocked_at",777L) }, uri("achievements/g/progress/ach"))
        assertTrue(unlocked.isUnlocked); assertEquals(777L, unlocked.unlockedAt); assertEquals(1, unlocked.progress)
        val locked = valuesToProgress(v{ put(GameHubContract.AchievementProgressCols.PROGRESS,4) }, uri("achievements/g2/ach2"))
        assertFalse(locked.isUnlocked); assertNull(locked.unlockedAt); assertEquals(4, locked.progress)
        val contractUnlocked = v{ put(GameHubContract.AchievementProgressCols.IS_UNLOCKED,1) }
        assertTrue(progressUnlocked(contractUnlocked)); assertFalse(progressUnlocked(v{}))
        assertTrue(progressUnlockedAt(contractUnlocked, true)!! > 0); assertNull(progressUnlockedAt(v{}, false))
        assertFailsWith<IllegalStateException> { valuesToProgress(v{}, uri("games")) }
    }

    @Test fun sessionStreakConvertersAndErrors() {
        val s = valuesToSession(v{ put("start_time",100L); put("end_time",200L); put("duration_ms",100L) }, uri("sessions/game-x/sess-x"))
        assertEquals("game-x", s.gameId); assertEquals("sess-x", s.sessionId); assertEquals(100L, s.startTime)
        val s2 = valuesToSession(v{ put(GameHubContract.Sessions.SESSION_ID,"s9"); put(GameHubContract.Sessions.GAME_ID,"g9") }, uri("sessions"))
        assertEquals("g9", s2.gameId); assertTrue(s2.startTime > 0)
        assertFailsWith<IllegalStateException> { valuesToSession(v{}, uri("sessions")) }
        assertFailsWith<IllegalStateException> { valuesToSession(v{ put("game_id","g") }, uri("sessions/g")) }
        val streak = valuesToStreak(v{ put("current_streak",3); put("longest_streak",8); put("last_completed_day",42L) }, uri("streaks/sg"))
        assertEquals("sg", streak.gameId); assertEquals(8, streak.longestStreak)
        val streakDefaults = valuesToStreak(v{ put(GameHubContract.Streaks.GAME_ID,"sg2") }, uri("streaks"))
        assertEquals(0, streakDefaults.currentStreak); assertEquals(0L, streakDefaults.lastCompletedDay)
        assertFailsWith<IllegalStateException> { valuesToStreak(v{}, uri("streaks")) }
    }

    @Test fun cursorsExposeEveryColumnAndLegacyJoins() {
        val gameCursor = cursorFromGames(listOf(HubGameEntity("cg","com.cg","CG Game","desc","2.0",3L,1,2,3,4000,5)))
        gameCursor.use { c -> assertEquals(1,c.count); assertTrue(c.moveToFirst()); assertEquals("CG Game",c.getString(c.getColumnIndex("display_name"))); assertEquals(4000L,c.getLong(c.getColumnIndex("total_playtime_ms"))); assertEquals(5,c.getInt(c.getColumnIndex("total_sessions"))) }
        assertEquals(0, cursorFromGames(emptyList()).count)
        val streakCursor = cursorFromStreaks(listOf(com.vayunmathur.games.hub.data.entities.DailyStreakEntity("sg",2,9,11,12)))
        streakCursor.use { c -> assertTrue(c.moveToFirst()); assertEquals(9,c.getInt(c.getColumnIndex("longest_streak"))) }
        val defs = listOf(AchievementDefEntity("g","a1","First","desc",50,2,true,"SILVER","icon"))
        cursorFromAchievementDefs(defs).use { c -> assertTrue(c.moveToFirst()); assertEquals(1,c.getInt(c.getColumnIndex("is_secret"))); assertEquals("SILVER",c.getString(c.getColumnIndex("tier"))) }
        val progs = listOf(AchievementProgressEntity("g","a1",2,true,99,100))
        cursorFromProgress(progs).use { c -> assertTrue(c.moveToFirst()); assertEquals(1,c.getInt(c.getColumnIndex("is_unlocked"))); assertEquals(99L,c.getLong(c.getColumnIndex("unlocked_at"))) }
        cursorFromLegacyAchievements(defs, mapOf("a1" to progs[0])).use { c -> assertTrue(c.moveToFirst()); assertEquals(1,c.getInt(c.getColumnIndex("unlocked"))) }
        cursorFromLegacyAchievements(defs, emptyMap()).use { c -> assertTrue(c.moveToFirst()); assertEquals(0,c.getInt(c.getColumnIndex("unlocked"))); assertTrue(c.isNull(c.getColumnIndex("unlocked_at"))) }
        // null def + null prog yields blank identity row; prog-only yields prog ids
        cursorFromLegacyAchievementItem(null,null).use { c -> assertTrue(c.moveToFirst()); assertEquals("",c.getString(c.getColumnIndex("achievement_id"))) }
        cursorFromLegacyAchievementItem(null,progs[0]).use { c -> assertTrue(c.moveToFirst()); assertEquals("a1",c.getString(c.getColumnIndex("achievement_id"))); assertEquals("",c.getString(c.getColumnIndex("name"))) }
        cursorFromLegacyAchievementItem(defs[0],null).use { c -> assertTrue(c.moveToFirst()); assertEquals("First",c.getString(c.getColumnIndex("name"))); assertEquals(0,c.getInt(c.getColumnIndex("unlocked"))) }
    }

    @Test fun mergeProgressAndUpdateHelpersBranches() {
        val existingUnlocked = AchievementProgressEntity("g","a",5,true,111,100)
        val incomingLocked = AchievementProgressEntity("g","a",2,false,null,200)
        val merged1 = mergeProgress(existingUnlocked, incomingLocked)
        assertEquals(5, merged1.progress); assertTrue(merged1.isUnlocked); assertEquals(111L, merged1.unlockedAt)
        val existingLocked = AchievementProgressEntity("g","a",1,false,null,100)
        val merged2 = mergeProgress(existingLocked, AchievementProgressEntity("g","a",3,true,222,200))
        assertEquals(3, merged2.progress); assertTrue(merged2.isUnlocked); assertEquals(222L, merged2.unlockedAt)
        val merged3 = mergeProgress(existingLocked, incomingLocked.copy(achievementId="a"))
        assertFalse(merged3.isUnlocked); assertNull(merged3.unlockedAt)
        assertEquals(incomingLocked, mergeProgress(null, incomingLocked))

        // update helpers
        assertEquals("ug", progressGameId(uri("achievements/ug/progress/a"), v{ put("game_id","vg") }))
        assertEquals("vg", progressGameId(uri("x"), v{ put("game_id","vg") }))
        assertEquals("a", progressAchievementId(uri("achievements/g/progress/a"), v{}))
        assertEquals("va", progressAchievementId(uri("achievements/g/progress"), v{ put("achievement_id","va") }))
        assertEquals(7, progressValue(v{ put("progress",7) })); assertEquals(0, progressValue(v{}))
        val newProg = mergeUpdateProgress(null,"g","a",4,true,null); assertEquals(4,newProg.progress); assertTrue(newProg.isUnlocked); assertNotNull(newProg.unlockedAt)
        val newLocked = mergeUpdateProgress(null,"g","a",0,false,null); assertNull(newLocked.unlockedAt)
        val kept = mergeUpdateProgress(existingUnlocked,"g","a",1,false,null); assertEquals(5,kept.progress); assertTrue(kept.isUnlocked); assertEquals(111L,kept.unlockedAt)
        val newly = mergeUpdateProgress(existingLocked,"g","a",9,true,null); assertEquals(9,newly.progress); assertNotNull(newly.unlockedAt)
    }

    @Test fun insertHandlersSessionDedupStreakAndDefEnsuring() = runBlocking {
        // insertSessionItem twice: markPlayed only first time
        with(provider) {
            insertSessionItem(uri("sessions/dedup-game/sess-dup"), v{ put(GameHubContract.Sessions.START_TIME,1000L) }, repo, repo.database)
            insertSessionItem(uri("sessions/dedup-game/sess-dup"), v{ put(GameHubContract.Sessions.START_TIME,1000L) }, repo, repo.database)
        }
        assertEquals(1, repo.games.getGame("dedup-game")?.totalSessions)
        assertNotNull(repo.sessions.getSessionById("sess-dup"))
        // insertSession (by-game) always marks played and auto-creates game
        with(provider) { insertSession(uri("sessions/auto-game"), v{ put(GameHubContract.Sessions.SESSION_ID,"s-auto"); put(GameHubContract.Sessions.START_TIME,2000L) }, repo, repo.database) }
        assertEquals("Auto-game", repo.games.getGame("auto-game")?.displayName)
        // progress item without ensureDef creates no def; ensureAchievementDef creates default once
        val prog = AchievementProgressEntity("nod-game","nod-ach",1,true,5)
        with(provider) { insertProgressItem(uri("achievements/nod-game/progress/nod-ach"), v{ put("progress",1); put("unlocked",1) }, repo, ensureDef=false) }
        assertNull(repo.achievements.getAchievementDef("nod-game","nod-ach"))
        ensureAchievementDef(prog, repo)
        assertEquals(DEFAULT_XP_REWARD, repo.achievements.getAchievementDef("nod-game","nod-ach")?.xpReward)
        ensureAchievementDef(prog, repo) // no overwrite / no crash
        // updateProgressItem: unknown ids via empty uri return 0; session update idempotent end
        assertEquals(0, with(provider){ updateProgressItem(Uri.parse("content://x/"), v{}, repo) })
        val sessUri = uri("sessions/dedup-game/sess-dup")
        // end via handler directly
        val ended = with(provider){ updateSession(sessUri, v{ put(GameHubContract.Sessions.END_TIME,61_000L) }, repo, repo.database) }
        assertEquals(1, ended); assertEquals(60_000L, repo.games.getGame("dedup-game")?.totalPlaytimeMs)
        val endedAgain = with(provider){ updateSession(sessUri, v{ put(GameHubContract.Sessions.END_TIME,999_000L) }, repo, repo.database) }
        assertEquals(1, endedAgain); assertEquals(60_000L, repo.games.getGame("dedup-game")?.totalPlaytimeMs) // idempotent
        assertEquals(0, with(provider){ updateSession(uri("sessions/g/ghost-sess"), v{}, repo, repo.database) })
        // negative duration coerced to 0: create sess starting after end
        with(provider){ insertSessionItem(uri("sessions/dedup-game/sess-neg"), v{ put(GameHubContract.Sessions.START_TIME,5000L) }, repo, repo.database) }
        assertEquals(1, with(provider){ updateSession(uri("sessions/dedup-game/sess-neg"), v{ put(GameHubContract.Sessions.END_TIME,1000L) }, repo, repo.database) })
    }

    @Test fun queryHandlersMissingIdsAndStreak() = runBlocking {
        // query handlers missing ids
        assertNull(with(provider){ queryGame(uri("games"), repo.database) })
        assertNull(with(provider){ queryProgressItem(uri("achievements/g/progress"), repo) })
        assertNull(with(provider){ queryLegacyAchievementItem(uri("achievements/g"), repo) })
        val streakQ = with(provider){ queryStreak(uri("streaks/dedup-game"), repo) }; assertNotNull(streakQ); streakQ.use{ assertEquals(0,it.count) }
    }
}
