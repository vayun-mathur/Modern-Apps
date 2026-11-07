package com.vayunmathur.sdk.games

import android.content.ContentProvider
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Serves fixed achievement-status rows on the legacy authority. */
class LegacyStatusProvider : ContentProvider() {
    companion object {
        var throwOnQuery = false
        val columns = arrayOf("achievement_id", "name", "description", "icon_res_name", "unlocked", "unlocked_at")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        if (throwOnQuery) throw IllegalStateException("legacy provider blew up")
        return MatrixCursor(columns).apply {
            addRow(arrayOf<Any>("a1", "First", "win", "ic_a", 1, 1_700_000_000_000L))
            addRow(arrayOf<Any>("a2", "Second", "win twice", "ic_b", 0, 0L))
        }
    }

    override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = uri
    override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 1
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class GameCenterLegacyTest {

    private lateinit var context: Context

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LegacyStatusProvider.throwOnQuery = false
        Robolectric.setupContentProvider(LegacyStatusProvider::class.java, GameHubContract.LEGACY_AUTHORITY)
        Robolectric.setupContentProvider(FakeHubProvider::class.java, GameHubContract.AUTHORITY)
        FakeHubProvider.reset()
    }

    @Test
    fun legacyReadsStatusesWhenHubInstalled() = runTest {
        installHubPackage(context.packageManager, GameHubContract.HUB_PACKAGE)
        val center: GameCenter = GameCenterLegacy(context, "chess")
        assertTrue(center.isHubInstalled())
        val statuses = center.getAchievements()
        assertEquals(2, statuses.size)
        val first = statuses[0]
        assertEquals("a1", first.achievement.id)
        assertEquals("First", first.achievement.name)
        assertEquals("ic_a", first.achievement.iconResName)
        assertTrue(first.unlocked)
        assertEquals(1_700_000_000_000L, first.unlockedAt)
        val second = statuses[1]
        assertFalse(second.unlocked)
        assertNull(second.unlockedAt) // locked rows report no timestamp
    }

    @Test
    fun legacyReadsEmptyWhenHubMissingOrProviderFails() = runTest {
        val center: GameCenter = GameCenterLegacy(context, "chess")
        assertFalse(center.isHubInstalled())
        assertTrue(center.getAchievements().isEmpty())
        installHubPackage(context.packageManager, GameHubContract.HUB_PACKAGE)
        LegacyStatusProvider.throwOnQuery = true
        assertTrue(center.getAchievements().isEmpty())
    }

    @Test
    fun legacyDelegatesWritesAndScoresAreNoOps() = runTest {
        installHubPackage(context.packageManager, GameHubContract.HUB_PACKAGE)
        val center: GameCenter = GameCenterLegacy(context, "chess")
        center.registerAchievements(listOf(Achievement("a1", "First", "win", iconResName = "ic")))
        assertEquals(1, FakeHubProvider.inserts.size)
        assertEquals("a1", FakeHubProvider.inserts[0].values[GameHubContract.AchievementDefs.ACHIEVEMENT_ID])
        assertEquals("ic", FakeHubProvider.inserts[0].values[GameHubContract.AchievementDefs.ICON_RES_NAME])
        assertTrue(center.unlockAchievement("a1"))
        center.reportScore("blitz", 42L)
        assertTrue(center.getScores("blitz").isEmpty())
        assertEquals(ScoreEntry("g", "c", 1L, 2L).copy(score = 3L).score, 3L)
    }
}
