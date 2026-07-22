package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrPoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeSessionPoToken
import org.schabi.newpipe.extractor.services.youtube.YoutubeSessionPoTokenProvider
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

internal fun youtubeCredentialIdentity(loggedIn: Boolean, tokens: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(if (loggedIn) 1.toByte() else 0.toByte())
    if (loggedIn) {
        digest.update(0.toByte())
        digest.update(tokens.orEmpty().toByteArray(StandardCharsets.UTF_8))
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
}

internal class CredentialIdentityTracker(private val onChanged: () -> Unit) {
    private var observedIdentity: String? = null

    @Synchronized
    fun observe(identity: String) {
        val previous = observedIdentity
        if (previous != null && previous != identity) {
            onChanged()
        }
        observedIdentity = identity
    }
}

class LocalDomPoTokenProvider(context: Context) :
    SabrPoTokenProvider,
    YoutubeSessionPoTokenProvider {
    private data class CachedToken(
        val token: ByteArray,
        val mintedAtMs: Long,
        val visitorData: String,
        val credentialIdentity: String,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, CachedToken>()
    private val mintLocks = ConcurrentHashMap<String, Any>()
    private val generatorLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generatorVisitorData: String? = null
    private var generatorCredentialIdentity: String? = null
    private var generator: LocalDomPoTokenGenerator? = null
    private var rawSessionPoToken: ByteArray? = null
    private val visitorDataLock = Any()
    private var fetchedVisitorData: String? = null
    private var fetchedVisitorDataLoggedIn: Boolean? = null
    private var fetchedVisitorDataCredentialIdentity: String? = null
    private var visitorDataFetchedAtMs: Long = 0
    private val credentialIdentityTracker = CredentialIdentityTracker(
        onChanged = ::invalidateCredentialBoundState,
    )
    private val prewarmLock = Any()
    private val prewarmExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "YoutubeSessionPoTokenPrewarm")
    }
    private var prewarmCredentialIdentity: String? = null
    private var prewarmTask: FutureTask<YoutubeSessionPoToken?>? = null

    override fun getSessionPoToken(
        clientName: String,
        localization: Localization,
        contentCountry: ContentCountry,
        loggedIn: Boolean,
    ): YoutubeSessionPoToken? {
        val credentialIdentity = currentCredentialIdentity(loggedIn)
        val inFlightPrewarm = synchronized(prewarmLock) {
            prewarmTask?.takeIf {
                prewarmCredentialIdentity == credentialIdentity && !it.isDone
            }
        }
        if (inFlightPrewarm != null) {
            try {
                return inFlightPrewarm.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SabrProtocolException(
                    "Interrupted waiting for session PO token prewarm",
                    error,
                )
            } catch (error: ExecutionException) {
                throw SabrProtocolException(
                    "Session PO token prewarm failed",
                    error.cause ?: error,
                )
            }
        }
        return getSessionPoTokenNow(
            clientName,
            localization,
            contentCountry,
            loggedIn,
            credentialIdentity,
        )
    }

    fun prewarmSessionPoToken(
        localization: Localization,
        contentCountry: ContentCountry,
        loggedIn: Boolean,
    ) {
        val credentialIdentity = currentCredentialIdentity(loggedIn)
        synchronized(prewarmLock) {
            val current = prewarmTask
            if (prewarmCredentialIdentity == credentialIdentity && current != null &&
                !current.isDone
            ) {
                return
            }
            val task = FutureTask {
                val startMs = android.os.SystemClock.elapsedRealtime()
                if (currentCredentialIdentity(currentlyLoggedIn()) !=
                    credentialIdentity
                ) {
                    Log.i(TAG, "session token prewarm skipped after credentials changed")
                    return@FutureTask null
                }
                try {
                    getSessionPoTokenNow(
                        PREWARM_CLIENT_NAME,
                        localization,
                        contentCountry,
                        loggedIn,
                        credentialIdentity,
                    ).also {
                        Log.i(
                            TAG,
                            "session token prewarm ready in " +
                                "${android.os.SystemClock.elapsedRealtime() - startMs}ms",
                        )
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "session token prewarm failed", error)
                    throw error
                }
            }
            prewarmCredentialIdentity = credentialIdentity
            prewarmTask = task
            prewarmExecutor.execute(task)
        }
    }

    private fun getSessionPoTokenNow(
        clientName: String,
        localization: Localization,
        contentCountry: ContentCountry,
        loggedIn: Boolean,
        credentialIdentity: String,
    ): YoutubeSessionPoToken? {
        credentialIdentityTracker.observe(credentialIdentity)
        val visitorData = getOrFetchVisitorData(
            localization,
            contentCountry,
            loggedIn,
            credentialIdentity,
        )
        val rawToken = getRawSessionPoToken(visitorData, credentialIdentity)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken)
        Log.i(
            TAG,
            "session token ready client=$clientName loggedIn=$loggedIn bytes=${rawToken.size}",
        )
        return YoutubeSessionPoToken(visitorData, encoded)
    }

    override fun getPoToken(
        info: YoutubeSabrInfo,
        streamState: YoutubeSabrStreamState,
    ): ByteArray? = getPoToken(info, streamState, false)

    override fun getPoToken(
        info: YoutubeSabrInfo,
        streamState: YoutubeSabrStreamState,
        forceRefresh: Boolean,
    ): ByteArray? {
        val credentialIdentity = currentCredentialIdentity(currentlyLoggedIn())
        credentialIdentityTracker.observe(credentialIdentity)
        val videoId = info.videoId
        val visitorData = info.visitorData ?: synchronized(visitorDataLock) {
            fetchedVisitorData
        } ?: throw SabrProtocolException("Missing visitorData for Local DOM PO token")
        if (forceRefresh) {
            cache.remove(videoId)
            prefs.edit().remove(videoId).apply()
        }
        synchronized(mintLocks.computeIfAbsent(videoId) { Any() }) {
            val now = System.currentTimeMillis()
            val cached = cache[videoId]
                ?: diskLoad(videoId)?.also { cache[videoId] = it }
            if (cached != null && cached.visitorData == visitorData &&
                cached.credentialIdentity == credentialIdentity &&
                now - cached.mintedAtMs < TOKEN_TTL_MS
            ) {
                Log.i(TAG, "cache hit video=$videoId bytes=${cached.token.size}")
                return cached.token
            }
            val token = ensureGenerator(visitorData, credentialIdentity)
                .generateRawPoToken(videoId)
            cache[videoId] = CachedToken(token, now, visitorData, credentialIdentity)
            diskSave(videoId, token, now, visitorData, credentialIdentity)
            Log.i(TAG, "mint complete video=$videoId bytes=${token.size}")
            return token
        }
    }

    fun hasCachedToken(videoId: String): Boolean {
        val credentialIdentity = currentCredentialIdentity(currentlyLoggedIn())
        credentialIdentityTracker.observe(credentialIdentity)
        val mem = cache[videoId]
        if (mem != null && mem.credentialIdentity == credentialIdentity &&
            System.currentTimeMillis() - mem.mintedAtMs < TOKEN_TTL_MS
        ) {
            return true
        }
        return diskLoad(videoId)?.credentialIdentity == credentialIdentity
    }

    fun clearCachedToken(videoId: String) {
        synchronized(mintLocks.computeIfAbsent(videoId) { Any() }) {
            cache.remove(videoId)
            prefs.edit().remove(videoId).commit()
        }
    }

    private fun ensureGenerator(
        visitorData: String,
        credentialIdentity: String,
    ): LocalDomPoTokenGenerator {
        synchronized(generatorLock) {
            val current = generator
            if (current != null && !current.isExpired() &&
                generatorVisitorData == visitorData &&
                generatorCredentialIdentity == credentialIdentity &&
                rawSessionPoToken != null
            ) {
                return current
            }
            if (!credentialsStillMatch(credentialIdentity)) {
                throw SabrProtocolException(
                    "YouTube credentials changed before PO token generator initialization",
                )
            }
            current?.let { mainHandler.post { it.close() } }
            val fresh = LocalDomPoTokenGenerator.create(appContext)
            val freshSessionPoToken = fresh.generateRawPoToken(visitorData)
            if (!credentialsStillMatch(credentialIdentity)) {
                mainHandler.post { fresh.close() }
                throw SabrProtocolException(
                    "YouTube credentials changed during PO token generator initialization",
                )
            }
            generator = fresh
            generatorVisitorData = visitorData
            generatorCredentialIdentity = credentialIdentity
            rawSessionPoToken = freshSessionPoToken
            return fresh
        }
    }

    private fun getRawSessionPoToken(
        visitorData: String,
        credentialIdentity: String,
    ): ByteArray {
        synchronized(generatorLock) {
            ensureGenerator(visitorData, credentialIdentity)
            return rawSessionPoToken?.clone()
                ?: throw SabrProtocolException("Local DOM session PO token is missing")
        }
    }

    private fun getOrFetchVisitorData(
        localization: Localization,
        contentCountry: ContentCountry,
        loggedIn: Boolean,
        credentialIdentity: String,
    ): String {
        synchronized(visitorDataLock) {
            val now = System.currentTimeMillis()
            val cached = fetchedVisitorData
            if (cached != null && fetchedVisitorDataLoggedIn == loggedIn &&
                fetchedVisitorDataCredentialIdentity == credentialIdentity &&
                now - visitorDataFetchedAtMs < VISITOR_DATA_TTL_MS
            ) {
                return cached
            }

            val headers = HashMap<String, List<String>>()
            headers.putAll(YoutubeParsingHelper.getYouTubeHeaders())
            headers["Content-Type"] = listOf("application/json")
            if (loggedIn) {
                YoutubeParsingHelper.addLoggedInHeaders(headers)
            }
            val fresh = YoutubeParsingHelper.getVisitorDataFromInnertube(
                InnertubeClientRequestInfo.ofWebClient(),
                localization,
                contentCountry,
                headers,
                YoutubeParsingHelper.YOUTUBEI_V1_URL,
                null,
                false,
            )
            if (!credentialsStillMatch(credentialIdentity)) {
                throw SabrProtocolException(
                    "YouTube credentials changed while fetching visitorData",
                )
            }
            fetchedVisitorData = fresh
            fetchedVisitorDataLoggedIn = loggedIn
            fetchedVisitorDataCredentialIdentity = credentialIdentity
            visitorDataFetchedAtMs = now
            return fresh
        }
    }

    // This fork does not support an authenticated YouTube session.
    private fun currentlyLoggedIn(): Boolean = false

    private fun currentCredentialIdentity(loggedIn: Boolean): String {
        return youtubeCredentialIdentity(loggedIn, null)
    }

    private fun credentialsStillMatch(credentialIdentity: String): Boolean {
        return currentCredentialIdentity(currentlyLoggedIn()) == credentialIdentity
    }

    private fun invalidateCredentialBoundState() {
        synchronized(visitorDataLock) {
            fetchedVisitorData = null
            fetchedVisitorDataLoggedIn = null
            fetchedVisitorDataCredentialIdentity = null
            visitorDataFetchedAtMs = 0
        }
        synchronized(generatorLock) {
            generator?.let { mainHandler.post { it.close() } }
            generator = null
            generatorVisitorData = null
            generatorCredentialIdentity = null
            rawSessionPoToken = null
        }
        cache.clear()
        prefs.edit().clear().commit()
        Log.i(TAG, "YouTube credentials changed; cleared credential-bound PO token state")
    }

    private fun diskLoad(videoId: String): CachedToken? {
        val value = prefs.getString(videoId, null) ?: return null
        val parts = value.split('|', limit = 4)
        if (parts.size != 4) {
            prefs.edit().remove(videoId).apply()
            return null
        }
        return try {
            val mintedAt = parts[0].toLong()
            if (System.currentTimeMillis() - mintedAt >= TOKEN_TTL_MS) {
                prefs.edit().remove(videoId).apply()
                null
            } else {
                val visitorData = String(
                    Base64.getUrlDecoder().decode(parts[2]),
                    StandardCharsets.UTF_8,
                )
                CachedToken(
                    Base64.getUrlDecoder().decode(parts[3]),
                    mintedAt,
                    visitorData,
                    parts[1],
                )
            }
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    private fun diskSave(
        videoId: String,
        token: ByteArray,
        mintedAt: Long,
        visitorData: String,
        credentialIdentity: String,
    ) {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedVisitorData = encoder.encodeToString(
            visitorData.toByteArray(StandardCharsets.UTF_8),
        )
        val encodedToken = encoder.encodeToString(token)
        prefs.edit().putString(
            videoId,
            "$mintedAt|$credentialIdentity|$encodedVisitorData|$encodedToken",
        ).commit()
    }

    companion object {
        private const val TAG = "SabrLocalDomPoToken"
        private const val PREFS = "sabr_local_dom_video_token_cache"
        private const val TOKEN_TTL_MS = 6L * 60L * 60L * 1000L
        private const val VISITOR_DATA_TTL_MS = 6L * 60L * 60L * 1000L
        private const val PREWARM_CLIENT_NAME = "WEB"

        @Volatile
        private var sharedInstance: LocalDomPoTokenProvider? = null

        @JvmStatic
        fun shared(context: Context): LocalDomPoTokenProvider {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: LocalDomPoTokenProvider(context.applicationContext).also {
                    sharedInstance = it
                }
            }
        }
    }
}
