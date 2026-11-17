package com.vayunmathur.youpipe.network.sabr

import android.content.Context
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Mints PO tokens inside the shared WebView runtime from a home-page attestation bootstrap.
 *
 * Port of PipePipe's `PersistentMintSession`: the BotGuard challenge comes from the YouTube home
 * page (`window.ytAtN`, parsed by [parseYoutubePageAttestationBootstrap]) and the run is bound
 * to the page's EVENT_ID. The previous `youtubei/v1/att/get` UNBOUND flow minted tokens the
 * server leaves at attestation-pending forever (#565).
 */
internal class LocalDomPoTokenGenerator private constructor(
    context: Context,
    private val initialization: InitWaiter,
    private val bootstrap: YoutubePageAttestationBootstrap,
) : Closeable {
    private val appContext = context.applicationContext
    private val runtime = SharedWebViewRuntime.get(appContext)
    private val sessionId = runtime.registerSabrLocalDomCallbacks(Callbacks())
    private val tokenWaiters = mutableMapOf<String, TokenWaiter>()
    private lateinit var expirationInstant: Instant
    @Volatile
    private var closed = false

    private fun loadScriptAndInitialize() {
        try {
            runtime.ensureReady(INIT_TIMEOUT_MS, "Local DOM PO token initialization")
            runtime.evaluateJavascriptBlocking(
                runtime.loadAsset(ASSET) + "\ntrue",
                INIT_TIMEOUT_MS,
                "Local DOM BotGuard helper injection",
            )
            downloadAndRunBotguard()
        } catch (error: IllegalStateException) {
            failInitialization(error)
        } catch (error: java.io.IOException) {
            failInitialization(error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failInitialization(error)
        }
    }

    @Synchronized
    @Throws(SabrProtocolException::class)
    fun mint(identifier: String): ByteArray {
        if (closed) {
            throw closedGeneratorException()
        }
        val waiter = TokenWaiter()
        synchronized(tokenWaiters) {
            tokenWaiters[identifier] = waiter
        }
        postTokenRequest(identifier)
        awaitToken(identifier, waiter)
        return validatedToken(waiter)
    }

    @Throws(SabrProtocolException::class)
    private fun validatedToken(waiter: TokenWaiter): ByteArray {
        waiter.error.get()?.let {
            throw tokenFailedException(it)
        }
        return waiter.token.get()?.takeIf { it.isNotEmpty() }
            ?: throw emptyTokenException()
    }

    @Throws(SabrProtocolException::class)
    private fun postTokenRequest(identifier: String) {
        val u8Identifier = stringToSabrU8(identifier)
        val posted = runtime.evaluateJavascript(
            "pipepipeSabrObtainPoToken(" + jsString(sessionId) + ", "
                + jsString(identifier) + ", " + u8Identifier + ");",
            null,
        ) { error -> onTokenError(identifier, error) }
        if (!posted) {
            discardWaiter(identifier)
            throw postFailedException()
        }
    }

    @Throws(SabrProtocolException::class)
    private fun awaitToken(identifier: String, waiter: TokenWaiter) {
        try {
            if (!waiter.latch.await(TOKEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                discardWaiter(identifier)
                throw tokenTimeoutException()
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            discardWaiter(identifier)
            throw SabrProtocolException("Local DOM PO token generation interrupted", error)
        }
    }

    private fun discardWaiter(identifier: String) {
        synchronized(tokenWaiters) {
            tokenWaiters.remove(identifier)
        }
    }

    private fun closedGeneratorException() =
        SabrProtocolException("Local DOM PO token generator is closed")

    private fun postFailedException() =
        SabrProtocolException("Could not post Local DOM PO token generation")

    private fun tokenTimeoutException() =
        SabrProtocolException("Local DOM PO token generation timed out")

    private fun tokenFailedException(cause: Throwable) =
        SabrProtocolException("Local DOM PO token generation failed: ${cause.message}", cause)

    private fun emptyTokenException() =
        SabrProtocolException("Local DOM PO token generation returned no token")

    fun isExpired(): Boolean {
        return !::expirationInstant.isInitialized || Instant.now().isAfter(expirationInstant)
    }

    override fun close() {
        closed = true
        runtime.unregisterSabrLocalDomCallbacks(sessionId)
        synchronized(tokenWaiters) {
            tokenWaiters.values.forEach {
                it.error.set(SabrProtocolException("Local DOM PO token generator closed"))
                it.latch.countDown()
            }
            tokenWaiters.clear()
        }
        runtime.evaluateJavascript(
            "pipepipeSabrDeleteSession(" + jsString(sessionId) + ");",
            null,
            null,
        )
    }

    private fun makeBotguardServiceRequest(
        url: String,
        data: String,
        contentType: String = "application/json+protobuf",
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        Thread({
            try {
                val downloader = NewPipe.getDownloader()
                val response = downloader.post(
                    url,
                    mapOf(
                        "User-Agent" to listOf(SharedWebViewRuntime.USER_AGENT),
                        "Accept" to listOf("application/json"),
                        "Content-Type" to listOf(contentType),
                        "x-goog-api-key" to listOf(GOOGLE_API_KEY),
                        "x-user-agent" to listOf("grpc-web-javascript/0.1"),
                    ),
                    data.toByteArray(),
                )
                if (response.responseCode() != HTTP_OK) {
                    throw SabrProtocolException(
                        "Local DOM BotGuard request failed: ${response.responseCode()}",
                    )
                }
                onSuccess(response.responseBody())
            } catch (error: java.io.IOException) {
                onError(error)
            } catch (error: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
                onError(error)
            }
        }, "SabrLocalDomPoTokenJnn").start()
    }

    private fun makeBotguardGetRequest(
        url: String,
        onSuccess: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        Thread({
            try {
                val downloader = NewPipe.getDownloader()
                val response = downloader.get(
                    url,
                    mapOf(
                        "User-Agent" to listOf(SharedWebViewRuntime.USER_AGENT),
                        "Accept" to listOf("*/*"),
                    ),
                )
                if (response.responseCode() != HTTP_OK) {
                    throw SabrProtocolException(
                        "Local DOM BotGuard GET failed: ${response.responseCode()}",
                    )
                }
                onSuccess(response.responseBody())
            } catch (error: java.io.IOException) {
                onError(error)
            } catch (error: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
                onError(error)
            }
        }, "SabrLocalDomPoTokenJnnGet").start()
    }

    private fun failInitialization(error: Throwable) {
        initialization.error.compareAndSet(null, error)
        initialization.latch.countDown()
        close()
    }

    private fun completeInitialization() {
        initialization.generator.compareAndSet(null, this)
        initialization.latch.countDown()
    }

    private fun onTokenResult(identifier: String, poTokenU8: String) {
        val waiter = synchronized(tokenWaiters) {
            tokenWaiters.remove(identifier)
        } ?: return
        try {
            waiter.token.set(csvU8ToByteArray(poTokenU8))
        } catch (error: IllegalArgumentException) {
            waiter.error.set(error)
        } finally {
            waiter.latch.countDown()
        }
    }

    private fun onTokenError(identifier: String, error: Throwable) {
        val waiter = synchronized(tokenWaiters) {
            tokenWaiters.remove(identifier)
        } ?: return
        waiter.error.set(error)
        waiter.latch.countDown()
    }

    private fun downloadAndRunBotguard() {
        val challenge = bootstrap.challenge
        val inlineInterpreter = challenge.interpreterJavascript
        if (inlineInterpreter != null) {
            runBotguard(challenge, inlineInterpreter)
        } else {
            val url = challenge.interpreterUrl
                ?: return failInitialization(
                    SabrProtocolException("Attestation challenge has no interpreter script or URL")
                )
            makeBotguardGetRequest(
                url,
                onSuccess = { runBotguard(challenge, it) },
                onError = ::failInitialization,
            )
        }
    }

    private fun runBotguard(
        challenge: SabrAttChallengeData,
        interpreterJavascript: String,
    ) {
        val challengeData = buildSabrAttChallengeData(challenge, interpreterJavascript)
        runtime.evaluateJavascript(
            "pipepipeSabrRunBotguard(" + jsString(sessionId) + ", "
                + jsString(bootstrap.eventId) + ", " + challengeData + ");",
            null,
        ) { error -> failInitialization(error) }
    }

    private fun onRunBotguardResult(botguardResponse: String) {
        makeBotguardServiceRequest(
            "https://jnn-pa.googleapis.com/\$rpc/google.internal.waa.v1.Waa/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
            onSuccess = { body ->
                try {
                    val (integrityToken, expirationSeconds) = parseSabrIntegrityTokenData(body)
                    expirationInstant = Instant.now().plusSeconds(expirationSeconds - EXPIRATION_SKEW_SECONDS)
                    runtime.evaluateJavascript(
                        "pipepipeSabrCreateMinter(" + jsString(sessionId) + ", "
                            + integrityToken + ");",
                        null,
                    ) { error -> failInitialization(error) }
                } catch (error: IllegalArgumentException) {
                    failInitialization(error)
                } catch (error: IllegalStateException) {
                    failInitialization(error)
                }
            },
            onError = ::failInitialization,
        )
    }

    private inner class Callbacks : SharedWebViewRuntime.SabrLocalDomCallbacks {
        override fun onJsInitializationError(error: String) {
            failInitialization(SabrProtocolException(error))
        }

        override fun onRunBotguardResult(botguardResponse: String) {
            this@LocalDomPoTokenGenerator.onRunBotguardResult(botguardResponse)
        }

        override fun onMinterReady() {
            completeInitialization()
        }

        override fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
            onTokenResult(identifier, poTokenU8)
        }

        override fun onObtainPoTokenError(identifier: String, error: String) {
            onTokenError(identifier, SabrProtocolException(error))
        }
    }

    private class TokenWaiter {
        val latch = CountDownLatch(1)
        val token = AtomicReference<ByteArray>()
        val error = AtomicReference<Throwable>()
    }

    private class InitWaiter {
        val latch = CountDownLatch(1)
        val generator = AtomicReference<LocalDomPoTokenGenerator>()
        val error = AtomicReference<Throwable>()
    }

    companion object {
        private const val ASSET = "sabr_po_token.js"
        private const val TOKEN_TIMEOUT_MS = 30_000L
        private const val INIT_TIMEOUT_MS = 60_000L
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val EXPIRATION_SKEW_SECONDS = 600L
        private const val HTTP_OK = 200

        @Throws(SabrProtocolException::class)
        fun create(
            context: Context,
            bootstrap: YoutubePageAttestationBootstrap,
        ): LocalDomPoTokenGenerator {
            val init = InitWaiter()
            val generator = LocalDomPoTokenGenerator(context, init, bootstrap)
            generator.loadScriptAndInitialize()
            awaitInitialization(init, generator)
            init.error.get()?.let {
                throw SabrProtocolException(
                    "Local DOM PO token initialization failed: ${it.message}",
                    it,
                )
            }
            return init.generator.get()
                ?: throw initEmptyException()
        }

        @Throws(SabrProtocolException::class)
        private fun awaitInitialization(init: InitWaiter, generator: LocalDomPoTokenGenerator) {
            try {
                if (!init.latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    generator.close()
                    throw initTimeoutException()
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                generator.close()
                throw SabrProtocolException("Local DOM PO token initialization interrupted", error)
            }
        }

        private fun initTimeoutException() =
            SabrProtocolException("Local DOM PO token initialization timed out")

        private fun initEmptyException() =
            SabrProtocolException("Local DOM PO token initialization returned no result")

        private fun jsString(value: String): String {
            return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\""
        }
    }
}
