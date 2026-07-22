package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class LocalDomPoTokenGenerator private constructor(
    context: Context,
    private val initialization: InitWaiter,
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
        } catch (error: Throwable) {
            failInitialization(error)
        }
    }

    @Synchronized
    @Throws(SabrProtocolException::class)
    fun generateRawPoToken(identifier: String): ByteArray {
        if (closed) {
            throw SabrProtocolException("Local DOM PO token generator is closed")
        }
        val waiter = TokenWaiter()
        synchronized(tokenWaiters) {
            tokenWaiters[identifier] = waiter
        }
        val u8Identifier = stringToSabrU8(identifier)
        val posted = runtime.evaluateJavascript(
            "pipepipeSabrObtainPoToken(" + jsString(sessionId) + ", "
                + jsString(identifier) + ", " + u8Identifier + ");",
            null,
        ) { error -> onTokenError(identifier, error) }
        if (!posted) {
            synchronized(tokenWaiters) {
                tokenWaiters.remove(identifier)
            }
            throw SabrProtocolException("Could not post Local DOM PO token generation")
        }
        try {
            if (!waiter.latch.await(TOKEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                synchronized(tokenWaiters) {
                    tokenWaiters.remove(identifier)
                }
                throw SabrProtocolException("Local DOM PO token generation timed out")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            synchronized(tokenWaiters) {
                tokenWaiters.remove(identifier)
            }
            throw SabrProtocolException("Local DOM PO token generation interrupted", error)
        }
        waiter.error.get()?.let {
            throw SabrProtocolException("Local DOM PO token generation failed: ${it.message}", it)
        }
        val token = waiter.token.get()
        if (token == null || token.isEmpty()) {
            throw SabrProtocolException("Local DOM PO token generation returned no token")
        }
        return token
    }

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
                    ?: throw SabrProtocolException("Downloader is not initialized")
                val response = downloader.post(
                    url,
                    mapOf(
                        "User-Agent" to listOf(USER_AGENT),
                        "Accept" to listOf("application/json"),
                        "Content-Type" to listOf(contentType),
                        "x-goog-api-key" to listOf(GOOGLE_API_KEY),
                        "x-user-agent" to listOf("grpc-web-javascript/0.1"),
                    ),
                    data.toByteArray(),
                )
                if (response.responseCode() != 200) {
                    throw SabrProtocolException(
                        "Local DOM BotGuard request failed: ${response.responseCode()}",
                    )
                }
                onSuccess(response.responseBody())
            } catch (error: Throwable) {
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
                    ?: throw SabrProtocolException("Downloader is not initialized")
                val response = downloader.get(
                    url,
                    mapOf(
                        "User-Agent" to listOf(USER_AGENT),
                        "Accept" to listOf("*/*"),
                    ),
                )
                if (response.responseCode() != 200) {
                    throw SabrProtocolException(
                        "Local DOM BotGuard GET failed: ${response.responseCode()}",
                    )
                }
                onSuccess(response.responseBody())
            } catch (error: Throwable) {
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
        } catch (error: Throwable) {
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
        makeBotguardServiceRequest(
            "https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false",
            """{"context":{"client":{"clientName":"WEB","clientVersion":"$ATT_CLIENT_VERSION"}},"engagementType":"ENGAGEMENT_TYPE_UNBOUND"}""",
            contentType = "application/json",
            onSuccess = { body ->
                try {
                    val challenge = parseSabrAttChallengeData(body)
                    makeBotguardGetRequest(
                        challenge.interpreterUrl,
                        onSuccess = { interpreterJavascript ->
                            val challengeData = buildSabrAttChallengeData(
                                challenge,
                                interpreterJavascript,
                            )
                            runtime.evaluateJavascript(
                                "pipepipeSabrRunBotguard(" + jsString(sessionId)
                                    + ", " + challengeData + ");",
                                null,
                            ) { error -> failInitialization(error) }
                        },
                        onError = ::failInitialization,
                    )
                } catch (error: Throwable) {
                    failInitialization(error)
                }
            },
            onError = ::failInitialization,
        )
    }

    private fun onRunBotguardResult(botguardResponse: String) {
        makeBotguardServiceRequest(
            "https://jnn-pa.googleapis.com/\$rpc/google.internal.waa.v1.Waa/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
            onSuccess = { body ->
                try {
                    val (integrityToken, expirationSeconds) = parseSabrIntegrityTokenData(body)
                    expirationInstant = Instant.now().plusSeconds(expirationSeconds - 600)
                    runtime.evaluateJavascript(
                        "pipepipeSabrCreateMinter(" + jsString(sessionId) + ", "
                            + integrityToken + ");",
                        null,
                    ) { error -> failInitialization(error) }
                } catch (error: Throwable) {
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
        private const val ATT_CLIENT_VERSION = "2.20260227.01.00"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

        @Throws(SabrProtocolException::class)
        fun create(context: Context): LocalDomPoTokenGenerator {
            val init = InitWaiter()
            val generator = LocalDomPoTokenGenerator(context, init)
            generator.loadScriptAndInitialize()
            try {
                if (!init.latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    generator.close()
                    throw SabrProtocolException("Local DOM PO token initialization timed out")
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                generator.close()
                throw SabrProtocolException("Local DOM PO token initialization interrupted", error)
            }
            init.error.get()?.let {
                throw SabrProtocolException(
                    "Local DOM PO token initialization failed: ${it.message}",
                    it,
                )
            }
            return init.generator.get()
                ?: throw SabrProtocolException("Local DOM PO token initialization returned no result")
        }

        private fun jsString(value: String): String {
            return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\""
        }
    }
}
