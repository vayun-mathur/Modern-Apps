package com.vayunmathur.games.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.vayunmathur.library.util.buildDatabase
import kotlinx.coroutines.runBlocking

class GameHubProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY = "com.vayunmathur.games.provider"
        private const val ACHIEVEMENTS_BY_GAME = 1
        private const val ACHIEVEMENT_BY_ID = 2
        private const val SCORES_BY_GAME = 3

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "achievements/*", ACHIEVEMENTS_BY_GAME)
            addURI(AUTHORITY, "achievements/*/*", ACHIEVEMENT_BY_ID)
            addURI(AUTHORITY, "scores/*", SCORES_BY_GAME)
        }
    }

    private val db by lazy { buildDatabase<GameHubDatabase>(context!!) }
    private val achievementDao by lazy { db.achievementDao() }
    private val scoreDao by lazy { db.scoreDao() }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        return when (uriMatcher.match(uri)) {
            ACHIEVEMENTS_BY_GAME -> {
                val gameId = uri.pathSegments[1]
                val entities = runBlocking { achievementDao.getByGame(gameId) }
                val cursor = MatrixCursor(arrayOf("achievement_id", "game_id", "name", "description", "icon_res_name", "unlocked", "unlocked_at"))
                for (e in entities) {
                    cursor.addRow(arrayOf(e.achievementId, e.gameId, e.name, e.description, e.iconResName, if (e.unlocked) 1 else 0, e.unlockedAt ?: 0L))
                }
                cursor
            }
            SCORES_BY_GAME -> {
                val gameId = uri.pathSegments[1]
                val category = selectionArgs?.firstOrNull()
                val entities = runBlocking {
                    if (category != null && selection == "category = ?") {
                        scoreDao.getByGameAndCategory(gameId, category)
                    } else {
                        scoreDao.getByGame(gameId)
                    }
                }
                val cursor = MatrixCursor(arrayOf("game_id", "category", "score", "timestamp"))
                for (e in entities) {
                    cursor.addRow(arrayOf(e.gameId, e.category, e.score, e.timestamp))
                }
                cursor
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        values ?: return null
        return when (uriMatcher.match(uri)) {
            ACHIEVEMENTS_BY_GAME -> {
                val entity = AchievementEntity(
                    gameId = values.getAsString("game_id"),
                    achievementId = values.getAsString("achievement_id"),
                    name = values.getAsString("name"),
                    description = values.getAsString("description"),
                    iconResName = values.getAsString("icon_res_name"),
                    unlocked = (values.getAsInteger("unlocked") ?: 0) == 1
                )
                runBlocking { achievementDao.insert(entity) }
                uri
            }
            SCORES_BY_GAME -> {
                val entity = ScoreEntity(
                    gameId = values.getAsString("game_id"),
                    category = values.getAsString("category"),
                    score = values.getAsLong("score"),
                    timestamp = values.getAsLong("timestamp")
                )
                runBlocking { scoreDao.insert(entity) }
                uri
            }
            else -> null
        }
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        values ?: return 0
        return when (uriMatcher.match(uri)) {
            ACHIEVEMENT_BY_ID -> {
                val gameId = uri.pathSegments[1]
                val achievementId = uri.pathSegments[2]
                val unlocked = (values.getAsInteger("unlocked") ?: 0) == 1
                val unlockedAt = values.getAsLong("unlocked_at") ?: System.currentTimeMillis()
                runBlocking { achievementDao.unlock(gameId, achievementId, unlocked, unlockedAt) }
            }
            else -> 0
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return when (uriMatcher.match(uri)) {
            ACHIEVEMENT_BY_ID -> {
                val gameId = uri.pathSegments[1]
                val achievementId = uri.pathSegments[2]
                runBlocking { achievementDao.delete(gameId, achievementId) }
            }
            ACHIEVEMENTS_BY_GAME -> {
                val gameId = uri.pathSegments[1]
                runBlocking { achievementDao.deleteByGame(gameId) }
            }
            SCORES_BY_GAME -> {
                val gameId = uri.pathSegments[1]
                runBlocking { scoreDao.deleteByGame(gameId) }
            }
            else -> 0
        }
    }

    override fun getType(uri: Uri): String? = null
}
