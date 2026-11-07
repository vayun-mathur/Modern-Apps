package com.vayunmathur.appstore.data.play

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.exceptions.GooglePlayException
import com.aurora.gplayapi.helpers.AuthHelper
import com.vayunmathur.appstore.data.AppProvider
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.DeviceAttributesProvider
import com.vayunmathur.appstore.data.searchCandidate
import com.vayunmathur.appstore.domain.SearchRanking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

private val Context.authDataStore by preferencesDataStore(name = "play_auth")
private val PLAY_AUTH_JSON_KEY = stringPreferencesKey("play_auth_json")
private val PLAY_AUTH_DISPENSED_AT_KEY = longPreferencesKey("play_auth_dispensed_at")

/**
 * How long a dispensed anonymous account is trusted before it is replaced.
 *
 * The dispenser hands out shared accounts that Google eventually invalidates,
 * and a dead one fails silently - Play calls just start erroring and the app
 * falls back to scraping. Cycling on age keeps that from being the way we find
 * out.
 */
private const val PLAY_AUTH_MAX_AGE_MS = 12L * 60 * 60 * 1000

sealed interface PlayAuthState {
    data object Idle : PlayAuthState
    data object Authenticating : PlayAuthState
    data class Authenticated(val authData: AuthData) : PlayAuthState
    data class Error(val message: String) : PlayAuthState
}

/**
 * Everything that needs a Play session, behind one object.
 *
 * This used to be spread across the ViewModel — the anonymous account, its persistence,
 * its expiry, the retry-once-on-a-dead-account dance, and every call site that had to
 * remember to use it. Keeping it here means a caller can ask for search results without
 * knowing that an account has to be dispensed first, and the "cycle a dead account and
 * try again" rule exists in exactly one place.
 */
class PlayRepository(private val context: Context) {

    private val anonAuthRepo = AnonymousAuthRepository()
    private val httpClient = PlayHttpClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Whether this device is a TV. Read once: the leanback feature set cannot change
     * without a reboot, and every Play call below consults it for TV-compat filtering.
     */
    private val isTv: Boolean by lazy { DeviceAttributesProvider(context).isTv() }

    private fun List<UnifiedApp>.filterTv(): List<UnifiedApp> =
        AppProvider.filterTvCompatible(isTv, this)

    private fun UnifiedApp.filterTv(): UnifiedApp? =
        AppProvider.filterTvCompatible(isTv, listOf(this)).firstOrNull()

    private val _authState = MutableStateFlow<PlayAuthState>(PlayAuthState.Idle)
    val authState: StateFlow<PlayAuthState> = _authState.asStateFlow()

    internal var cachedAuthData: AuthData? = null

    /** When [cachedAuthData] was dispensed, for age-based cycling. */
    internal var dispensedAt: Long = 0L

    /**
     * Serialises dispensing.
     *
     * Browse, the update check and an install can all discover a dead account within a
     * few milliseconds of each other; without this each would dispense its own, and the
     * last writer would win while the others' accounts leaked.
     */
    private val authMutex = Mutex()

    /** Reload a persisted account, if it is young enough and still alive. */
    suspend fun restore() {
        try {
            val prefs = context.authDataStore.data.first()
            val jsonStr = prefs[PLAY_AUTH_JSON_KEY]?.takeIf { it.isNotBlank() } ?: return
            val storedAt = prefs[PLAY_AUTH_DISPENSED_AT_KEY] ?: 0L
            if (System.currentTimeMillis() - storedAt > PLAY_AUTH_MAX_AGE_MS) {
                // Past its useful life; ensureAuth will dispense a new one rather than
                // spend a round-trip proving this is dead.
                invalidate()
                return
            }
            val authData = runCatching {
                json.decodeFromString(AuthData.serializer(), jsonStr)
            }.getOrNull() ?: return
            if (isValid(authData)) {
                cachedAuthData = authData
                dispensedAt = storedAt
                _authState.value = PlayAuthState.Authenticated(authData)
            } else {
                invalidate()
            }
        } catch (expected: java.io.IOException) {
            Log.status(TAG, "restore auth failed", expected)
        } catch (expected: IllegalStateException) {
            Log.status(TAG, "restore auth failed", expected)
        }
    }

    /**
     * Run a Play call with whatever account is already available, cycling it if it has
     * died.
     *
     * Returns null when there is no account to use or both attempts failed, so callers
     * keep their existing fallback. Deliberately does *not* dispense when nothing is
     * cached: being signed out is the caller's cue to use the scraping path, and
     * dispensing here would put a retrying network call in front of every keystroke of
     * search.
     */
    suspend fun <T> withApi(block: suspend (PlayStoreApi) -> T): T? {
        val cached = cachedAuthData ?: return null
        try {
            return block(PlayStoreApi(cached, httpClient)).also { clearPlayError() }
        } catch (e: CancellationException) {
            throw e
        } catch (expected: java.io.IOException) {
            Log.status(TAG, "Play call failed; cycling anonymous account", expected)
        } catch (expected: IllegalStateException) {
            Log.status(TAG, "Play call failed; cycling anonymous account", expected)
        } catch (expected: GooglePlayException) {
            // gplayapi surfaces HTTP failures (e.g. 429 Too Many Requests, issue #797)
            // as GooglePlayException, not IOException. Same cycle-and-retry as the rest.
            Log.status(TAG, "Play call failed (${expected.message}); cycling anonymous account", expected)
        }

        invalidate()
        val fresh = ensureAuth().getOrNull() ?: return null
        return try {
            block(PlayStoreApi(fresh, httpClient)).also { clearPlayError() }
        } catch (e: CancellationException) {
            throw e
        } catch (expected: java.io.IOException) {
            Log.status(TAG, "Play call failed again after cycling", expected)
            null
        } catch (expected: IllegalStateException) {
            Log.status(TAG, "Play call failed again after cycling", expected)
            null
        } catch (expected: GooglePlayException) {
            // Refused twice, so a retry loop would only hammer it further (issue #797).
            // Record it for the search screen instead of letting it kill the caller.
            Log.status(TAG, "Play call failed again after cycling", expected)
            _authState.value = PlayAuthState.Error(playFailureMessage(expected))
            null
        }
    }

    /** Like [withApi], but dispenses an account first if there isn't one. */
    suspend fun <T> withFreshApi(block: suspend (PlayStoreApi) -> T): T? {
        if (cachedAuthData == null && ensureAuth().isFailure) return null
        return withApi(block)
    }

    suspend fun ensureAuth(): Result<AuthData> = authMutex.withLock {
        cachedAuthData?.let { cached ->
            if (System.currentTimeMillis() - dispensedAt <= PLAY_AUTH_MAX_AGE_MS && isValid(cached)) {
                return@withLock Result.success(cached)
            }
            invalidate()
        }

        _authState.value = PlayAuthState.Authenticating

        // Use the same curated Pixel 9a profile Aurora + its dispenser are tuned to,
        // so the dispensed anonymous accounts and gplayapi's DFE headers stay consistent.
        val deviceProps = DeviceInfoProvider.auroraProfile(context)
        val result = anonAuthRepo.ensureAuthData(deviceProps)
        val authData = result.getOrElse { err ->
            _authState.value = PlayAuthState.Error(anonAuthRepo.errorMessage(err))
            return@withLock Result.failure(err)
        }

        cachedAuthData = authData
        dispensedAt = System.currentTimeMillis()
        _authState.value = PlayAuthState.Authenticated(authData)
        persist(authData)
        Result.success(authData)
    }

    // --- Convenience wrappers -----------------------------------------------------

    /**
     * Play's search: more pages while the answer is unsatisfying, then the first word.
     *
     * A phrase Play does not recognise comes back either as filler or as nothing at all —
     * the reason "Google Maps" returned Facebook and Netflix and "chase mobile" returned
     * nothing (issue #595). Neither is worth showing, so the search reads on, and failing
     * that asks the broader question. [SearchRanking] decides what any of it is worth, and
     * is also what decides whether an answer counts as one.
     */
    suspend fun search(query: String): List<UnifiedApp> {
        val answered: (List<UnifiedApp>) -> Boolean = { results ->
            results.any { SearchRanking.score(it.searchCandidate(), query) != null }
        }
        val direct = withApi { it.search(query, SEARCH_MAX_PAGES, answered) }.orEmpty()
        val tokens = SearchRanking.tokenize(query)
        if (tokens.size < 2 || answered(direct)) return direct.filterTv()
        val broader = withApi { it.search(tokens.first(), SEARCH_MAX_PAGES, answered) }.orEmpty()
        return (direct + broader).distinctBy { it.packageName }.filterTv()
    }

    suspend fun details(packageName: String): UnifiedApp? =
        withApi { it.getDetails(packageName) }?.filterTv()

    suspend fun details(packageNames: List<String>): List<UnifiedApp> =
        withFreshApi { it.getDetails(packageNames) }.orEmpty().filterTv()

    /**
     * Like [details], but null when the lookup itself failed (no account could be
     * dispensed, Play refused twice, no network) rather than empty.
     *
     * Lets the update check tell "Play has no updates for these packages" apart from
     * "Play didn't answer": the former clears the Play rows, the latter keeps the
     * previous ones rather than wiping valid updates on a transient failure.
     */
    suspend fun detailsOrNull(packageNames: List<String>): List<UnifiedApp>? {
        if (packageNames.isEmpty()) return emptyList()
        return withFreshApi { it.getDetails(packageNames) }?.filterTv()
    }

    suspend fun topChart(): List<UnifiedApp> =
        withFreshApi { it.topChart() }.orEmpty().filterTv()

    suspend fun homeClusters(): List<PlayCluster> =
        withFreshApi { it.homeClusters() }.orEmpty()
            .mapNotNull { cluster ->
                val apps = AppProvider.filterTvCompatible(isTv, cluster.apps)
                if (apps.isEmpty()) null else cluster.copy(apps = apps)
            }

    /** Throws, unlike the readers above: an install has no silent fallback. */
    suspend fun purchase(
        packageName: String,
        versionCode: Long,
        offerType: Int,
        certHash: String?,
    ): List<PlayFile> {
        val authData = ensureAuth().getOrThrow()
        return PlayStoreApi(authData, httpClient)
            .purchase(packageName, versionCode, offerType, certHash)
    }

    // --- Internals ----------------------------------------------------------------

    private suspend fun isValid(authData: AuthData): Boolean = withContext(Dispatchers.IO) {
        try {
            AuthHelper.using(httpClient).isValid(authData)
        } catch (_: java.io.IOException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: GooglePlayException) {
            // A validity probe is best-effort: a refusal means "not usable", not a crash.
            false
        }
    }

    /**
     * Forget the current anonymous account so the next Play call dispenses a
     * fresh one. Clears the persisted copy too, or a restart would restore the
     * dead credentials.
     */
    private suspend fun invalidate() {
        cachedAuthData = null
        dispensedAt = 0L
        _authState.value = PlayAuthState.Idle
        try {
            context.authDataStore.edit { prefs ->
                prefs.remove(PLAY_AUTH_JSON_KEY)
                prefs.remove(PLAY_AUTH_DISPENSED_AT_KEY)
            }
        } catch (expected: java.io.IOException) {
            Log.status(TAG, "invalidate auth failed", expected)
        }
    }

    private suspend fun persist(authData: AuthData) {
        try {
            val jsonStr = json.encodeToString(AuthData.serializer(), authData)
            context.authDataStore.edit { prefs ->
                prefs[PLAY_AUTH_JSON_KEY] = jsonStr
                prefs[PLAY_AUTH_DISPENSED_AT_KEY] = dispensedAt
            }
        } catch (expected: java.io.IOException) {
            Log.status(TAG, "persist auth failed", expected)
        }
    }

    /**
     * Clear a stale Play failure once a call succeeds, so the status line and the next
     * search stop blaming Play for a refusal that has since cleared.
     */
    private fun clearPlayError() {
        if (_authState.value is PlayAuthState.Error) {
            val cached = cachedAuthData
            _authState.value = if (cached != null) PlayAuthState.Authenticated(cached)
            else PlayAuthState.Idle
        }
    }

    /**
     * Short user-facing reason for a refused Play call (issue #797). The ViewModel wraps
     * this in R.string.play_unavailable, so this is the %1$s half, not a sentence.
     */
    private fun playFailureMessage(e: GooglePlayException): String {
        val code = (e as? GooglePlayException.Unknown)?.code
        return when (code) {
            TOO_MANY_REQUESTS -> "too many requests — try again shortly"
            SERVICE_UNAVAILABLE -> "temporarily unavailable — try again shortly"
            else -> e.message ?: "request failed"
        }
    }

    private companion object {
        const val TAG = "PlayRepository"

        /** gplayapi Unknown codes worth naming in user-facing failure text (issue #797). */
        const val TOO_MANY_REQUESTS = 429
        const val SERVICE_UNAVAILABLE = 503

        /**
         * How far into Play's search stream to read before giving up on a query. Only
         * reached when the pages so far hold nothing that answers it.
         */
        const val SEARCH_MAX_PAGES = 3
    }
}
