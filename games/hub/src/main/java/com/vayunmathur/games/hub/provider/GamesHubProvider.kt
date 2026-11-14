package com.vayunmathur.games.hub.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import com.vayunmathur.games.hub.data.GamesHubRepository
import com.vayunmathur.games.hub.data.entities.ActivityEventEntity
import com.vayunmathur.library.log.Log
import com.vayunmathur.sdk.games.GameHubContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Games Hub Provider: a content provider implementation for games hub provider. */
open class GamesHubProvider : ContentProvider() {

    /** Shared constants and helpers for GamesHubProvider. */
    companion object {
        private const val GAMES_PACKAGE_PREFIX = "com.vayunmathur.games"

        internal const val MILLIS_PER_SECOND = 1000L

        private const val CODE_GAMES = 1
        private const val CODE_GAME_ITEM = 2
        private const val CODE_ACH_DEFS = 10
        private const val CODE_ACH_PROGRESS = 11
        private const val CODE_ACH_PROGRESS_ITEM = 12
        private const val CODE_LEGACY_ACHIEVEMENTS = 20
        private const val CODE_LEGACY_ACHIEVEMENT_ITEM = 21
        private const val CODE_SESSIONS_BY_GAME = 50
        private const val CODE_SESSION_ITEM = 51
        private const val CODE_STREAK_BY_GAME = 60

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(GameHubContract.AUTHORITY, "games", CODE_GAMES)
            addURI(GameHubContract.AUTHORITY, "games/*", CODE_GAME_ITEM)
            addURI(GameHubContract.LEGACY_AUTHORITY, "games", CODE_GAMES)
            addURI(GameHubContract.LEGACY_AUTHORITY, "games/*", CODE_GAME_ITEM)

            addURI(GameHubContract.AUTHORITY, "achievements/*/defs", CODE_ACH_DEFS)
            addURI(GameHubContract.LEGACY_AUTHORITY, "achievements/*/defs", CODE_ACH_DEFS)
            addURI(GameHubContract.AUTHORITY, "achievements/*/progress", CODE_ACH_PROGRESS)
            addURI(GameHubContract.LEGACY_AUTHORITY, "achievements/*/progress", CODE_ACH_PROGRESS)
            addURI(GameHubContract.AUTHORITY, "achievements/*/progress/*", CODE_ACH_PROGRESS_ITEM)
            addURI(GameHubContract.LEGACY_AUTHORITY, "achievements/*/progress/*", CODE_ACH_PROGRESS_ITEM)

            addURI(GameHubContract.AUTHORITY, "achievements/*", CODE_LEGACY_ACHIEVEMENTS)
            addURI(GameHubContract.LEGACY_AUTHORITY, "achievements/*", CODE_LEGACY_ACHIEVEMENTS)
            addURI(GameHubContract.AUTHORITY, "achievements/*/*", CODE_LEGACY_ACHIEVEMENT_ITEM)
            addURI(GameHubContract.LEGACY_AUTHORITY, "achievements/*/*", CODE_LEGACY_ACHIEVEMENT_ITEM)

            addURI(GameHubContract.AUTHORITY, "sessions/*", CODE_SESSIONS_BY_GAME)
            addURI(GameHubContract.LEGACY_AUTHORITY, "sessions/*", CODE_SESSIONS_BY_GAME)
            addURI(GameHubContract.AUTHORITY, "sessions/*/*", CODE_SESSION_ITEM)
            addURI(GameHubContract.LEGACY_AUTHORITY, "sessions/*/*", CODE_SESSION_ITEM)

            addURI(GameHubContract.AUTHORITY, "streaks/*", CODE_STREAK_BY_GAME)
            addURI(GameHubContract.LEGACY_AUTHORITY, "streaks/*", CODE_STREAK_BY_GAME)
        }
    }

    private fun getRepo(): GamesHubRepository {
        val ctx = context ?: error("GamesHubProvider context null")
        return GamesHubRepository.get(ctx)
    }

    override fun onCreate(): Boolean = true

    // The signature permission on this provider only proves "signed by the Modern Apps key",
    // which every app in the repo is. Narrow that to the games namespace as well.
    private fun enforceGamesCaller() {
        if (Binder.getCallingUid() == Process.myUid()) return
        val caller = callingPackage
        if (caller != GAMES_PACKAGE_PREFIX && caller?.startsWith("$GAMES_PACKAGE_PREFIX.") != true) {
            throw SecurityException("GamesHubProvider: caller $caller is outside the games namespace")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceGamesCaller()
        val v = values ?: return null
        return runBlocking(Dispatchers.IO) {
            val repo = getRepo()
            val database = repo.database
            try {
                when (uriMatcher.match(uri)) {
                    CODE_GAME_ITEM, CODE_GAMES -> insertGame(uri, v, repo)
                    CODE_ACH_DEFS, CODE_LEGACY_ACHIEVEMENTS -> insertAchievementDef(uri, v, repo)
                    CODE_ACH_PROGRESS -> insertProgress(uri, v, repo)
                    CODE_ACH_PROGRESS_ITEM, CODE_LEGACY_ACHIEVEMENT_ITEM ->
                        insertProgressItem(
                            uri,
                            v,
                            repo,
                            ensureDef = uriMatcher.match(uri) == CODE_LEGACY_ACHIEVEMENT_ITEM,
                        )
                    CODE_SESSIONS_BY_GAME -> insertSession(uri, v, repo, database)
                    CODE_SESSION_ITEM -> insertSessionItem(uri, v, repo, database)
                    CODE_STREAK_BY_GAME -> insertStreak(uri, v, repo)
                    else -> null
                }
            } catch (e: IllegalArgumentException) {
                Log.error("GamesHubProvider", "insert failed $uri", e)
                null
            } catch (e: android.database.SQLException) {
                Log.error("GamesHubProvider", "insert failed $uri", e)
                null
            }
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceGamesCaller()
        val v = values ?: return 0
        return runBlocking(Dispatchers.IO) {
            val repo = getRepo()
            val database = repo.database
            try {
                when (uriMatcher.match(uri)) {
                    CODE_ACH_PROGRESS_ITEM, CODE_LEGACY_ACHIEVEMENT_ITEM ->
                        updateProgressItem(uri, v, repo)
                    CODE_SESSION_ITEM -> updateSession(uri, v, repo, database)
                    else -> 0
                }
            } catch (e: IllegalArgumentException) {
                Log.error("GamesHubProvider", "update failed $uri", e)
                0
            } catch (e: android.database.SQLException) {
                Log.error("GamesHubProvider", "update failed $uri", e)
                0
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        enforceGamesCaller()
        return runBlocking(Dispatchers.IO) {
            val repo = getRepo()
            val database = repo.database
            try {
                when (uriMatcher.match(uri)) {
                    CODE_GAMES -> cursorFromGames(database.gameDao().getAll())
                    CODE_GAME_ITEM -> queryGame(uri, database)
                    CODE_ACH_DEFS -> queryAchievementDefs(uri, repo)
                    CODE_LEGACY_ACHIEVEMENTS -> queryLegacyAchievements(uri, repo)
                    CODE_ACH_PROGRESS -> queryProgress(uri, repo)
                    CODE_ACH_PROGRESS_ITEM -> queryProgressItem(uri, repo)
                    CODE_LEGACY_ACHIEVEMENT_ITEM -> queryLegacyAchievementItem(uri, repo)
                    CODE_STREAK_BY_GAME -> queryStreak(uri, repo)
                    else -> null
                }
            } catch (e: IllegalArgumentException) {
                Log.error("GamesHubProvider", "query failed $uri", e)
                null
            } catch (e: android.database.SQLException) {
                Log.error("GamesHubProvider", "query failed $uri", e)
                null
            }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}

/** Games Hub Legacy Provider: a games hub provider implementation for games hub legacy provider. */
class GamesHubLegacyProvider : GamesHubProvider()
