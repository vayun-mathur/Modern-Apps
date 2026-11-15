@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.youpipe.util.sabr

import kotlin.uuid.Uuid
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.vayunmathur.library.log.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single headless WebView used by local JavaScript services.
 *
 * The runtime loads one blank first-party page, keeps it alive for the app process, and exposes
 * a stable JavaScript bridge. Callers keep their own JS namespaces and serialize their public entry
 * points on the caller side.
 */
class SharedWebViewRuntime private constructor(context: Context) {

    /** Contract for initialization failure callback. */
    fun interface InitializationFailureCallback {
        fun onInitializationFailure(throwable: Throwable)
    }

    /** Contract for sabr local dom callbacks. */
    interface SabrLocalDomCallbacks {
        fun onJsInitializationError(error: String)

        fun onRunBotguardResult(botguardResponse: String)

        fun onMinterReady()

        fun onObtainPoTokenResult(identifier: String, poTokenU8: String)

        fun onObtainPoTokenError(identifier: String, error: String)
    }

    private val appContext: Context = context.applicationContext
    private val debuggable: Boolean =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initLock = Any()
    private val sabrLocalDomCallbacks = ConcurrentHashMap<String, SabrLocalDomCallbacks>()

    private var initLatch: CountDownLatch? = null
    private var initError: AtomicReference<Throwable>? = null
    private var activeInitializationAttempt: InitializationAttempt? = null
    private var nextInitializationAttemptId: Long = 0
    private var initializationFailureCallback: InitializationFailureCallback? = null
    private var webView: WebView? = null

    @Volatile private var ready = false

    fun warmUp() {
        warmUp(null)
    }

    fun warmUp(failureCallback: InitializationFailureCallback?) {
        val existingFailure: Throwable?
        synchronized(initLock) {
            if (failureCallback != null) {
                initializationFailureCallback = failureCallback
            }
            if (ready || initLatch != null) {
                existingFailure = initError?.get()
                if (existingFailure == null) {
                    return
                }
            } else {
                startInitializationLocked()
                return
            }
        }
        existingFailure?.let { failureCallback?.onInitializationFailure(it) }
    }

    @Throws(Exception::class)
    fun ensureReady(timeoutMs: Long, operation: String) {
        if (isReady()) {
            return
        }
        val (latch, error) = awaitableInitState()
        if (latch == null || error == null) {
            throw IllegalStateException("$operation did not start WebView initialization")
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "$operation cannot wait on the main thread"
        }
        awaitInitCompletion(latch, error, timeoutMs, operation)
    }

    private fun isReady(): Boolean = synchronized(initLock) { ready }

    @Throws(IllegalStateException::class)
    private fun awaitInitCompletion(
        latch: CountDownLatch,
        error: AtomicReference<Throwable>,
        timeoutMs: Long,
        operation: String,
    ) {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("$operation timed out waiting for WebView runtime")
        }
        val failure = error.get()
        if (failure != null) {
            throw IllegalStateException(
                "$operation failed to initialize WebView runtime", failure
            )
        }
    }

    /**
     * Latch + error for the in-flight initialization. Starts initialization when needed.
     * Callers treat nulls as "did not start" and throw.
     */
    private fun awaitableInitState(): Pair<CountDownLatch?, AtomicReference<Throwable>?> {
        synchronized(initLock) {
            if (initLatch == null && !ready) {
                startInitializationLocked()
            }
            return initLatch to initError
        }
    }

    @Throws(Exception::class)
    fun evaluateJavascriptBlocking(
        script: String,
        timeoutMs: Long,
        operation: String
    ): String {
        ensureReady(timeoutMs, operation)
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "$operation cannot wait on the main thread"
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        val error = AtomicReference<Throwable>()
        val posted = mainHandler.post {
            try {
                val view = webView ?: throw IllegalStateException(
                    "WebView runtime is not initialized"
                )
                view.evaluateJavascript(script) { value ->
                    result.set(value)
                    latch.countDown()
                }
            } catch (throwable: IllegalStateException) {
                error.set(throwable)
                latch.countDown()
            }
        }
        if (!posted) {
            throw IllegalStateException("$operation could not post JavaScript evaluation")
        }
        return awaitJsResult(latch, result, error, timeoutMs, operation)
    }

    @Throws(IllegalStateException::class)
    private fun awaitJsResult(
        latch: CountDownLatch,
        result: AtomicReference<String>,
        error: AtomicReference<Throwable>,
        timeoutMs: Long,
        operation: String,
    ): String {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("$operation timed out")
        }
        val failure = error.get()
        if (failure != null) {
            throw IllegalStateException("$operation failed", failure)
        }
        return result.get()
    }

    fun evaluateJavascript(
        script: String,
        callback: ValueCallback<String>?,
        errorCallback: ValueCallback<Throwable>?
    ): Boolean {
        try {
            ensureReady(DEFAULT_TIMEOUT_MS, "async JavaScript evaluation")
        } catch (throwable: IllegalStateException) {
            errorCallback?.onReceiveValue(throwable)
            return false
        }
        return mainHandler.post {
            try {
                val view = webView ?: throw IllegalStateException(
                    "WebView runtime is not initialized"
                )
                view.evaluateJavascript(script, callback)
            } catch (throwable: IllegalStateException) {
                errorCallback?.onReceiveValue(throwable)
            }
        }
    }

    fun loadAsset(path: String): String {
        try {
            appContext.assets.open(path).use { input ->
                return readAssetFully(input)
            }
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Could not load asset $path", e)
        }
    }

    private fun readAssetFully(input: java.io.InputStream): String {
        ByteArrayOutputStream().use { out ->
            val buffer = ByteArray(ASSET_READ_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    fun registerSabrLocalDomCallbacks(callbacks: SabrLocalDomCallbacks): String {
        val id = Uuid.random().toString()
        sabrLocalDomCallbacks[id] = callbacks
        return id
    }

    fun unregisterSabrLocalDomCallbacks(id: String) {
        sabrLocalDomCallbacks.remove(id)
    }

    private fun startInitializationLocked() {
        val latch = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val attempt = InitializationAttempt(++nextInitializationAttemptId, 1, latch, error)
        initLatch = latch
        initError = error
        activeInitializationAttempt = attempt
        if (!mainHandler.post { createWebView(attempt) }) {
            val exception = IllegalStateException("Could not post WebView creation")
            activeInitializationAttempt = null
            error.set(exception)
            latch.countDown()
            notifyInitializationFailure(exception)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(attempt: InitializationAttempt) {
        if (!isActiveInitializationAttempt(attempt)) {
            return
        }
        try {
            Log.status(
                TAG,
                "creating WebView attempt=${attempt.number} elapsedMs=${attempt.elapsedMs()}"
            )
            val view = WebView(appContext)
            attempt.view = view
            Log.status(
                TAG,
                "created WebView attempt=${attempt.number} elapsedMs=${attempt.elapsedMs()}"
            )
            if (!isActiveInitializationAttempt(attempt)) {
                destroyWebView(view)
                return
            }
            if (debuggable) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            val settings = view.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.userAgentString = USER_AGENT
            settings.blockNetworkLoads = true
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(settings, false)
            }
            view.addJavascriptInterface(Bridge(), BRIDGE_NAME)
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (debuggable) {
                        Log.dev(
                            TAG,
                            "console ${message.messageLevel()} ${message.message()}" +
                                " @${message.sourceId()}:${message.lineNumber()}"
                        )
                    }
                    return true
                }
            }
            view.webViewClient = object : WebViewClient() {
                // Returning false here hands the dead renderer back to the framework, which
                // kills the whole app process. Tear the runtime down instead.
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    Log.status(
                        TAG,
                        "renderer gone crashed=${detail.didCrash()} attempt=${attempt.number}"
                    )
                    ready = false
                    if (webView === view) webView = null
                    retryOrFail(
                        attempt,
                        IllegalStateException(
                            "WebView renderer process gone (didCrash=${detail.didCrash()})"
                        )
                    )
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // WebView 44/83 occasionally misses this callback for a headless local page.
                    // Readiness is therefore determined only by the local document's bridge call.
                    Log.status(
                        TAG,
                        "page finished url=$url attempt=${attempt.number}" +
                            " elapsedMs=${attempt.elapsedMs()}"
                    )
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    webError: WebResourceError
                ) {
                    super.onReceivedError(view, request, webError)
                    if (request.isForMainFrame) {
                        retryOrFail(
                            attempt,
                            IllegalStateException(
                                "WebView runtime main frame error ${webError.errorCode}" +
                                    ": ${webError.description}"
                            )
                        )
                    }
                }
            }
            view.loadDataWithBaseURL(
                "https://www.youtube.com/",
                runtimeDocument(attempt.id),
                "text/html", "UTF-8", null
            )
            Log.status(
                TAG,
                "load dispatched attempt=${attempt.number} elapsedMs=${attempt.elapsedMs()}"
            )
            mainHandler.postDelayed({
                retryOrFail(
                    attempt,
                    IllegalStateException(
                        "WebView runtime ready callback timed out after " +
                            "$READY_CALLBACK_ATTEMPT_TIMEOUT_MS ms"
                    )
                )
            }, READY_CALLBACK_ATTEMPT_TIMEOUT_MS)
        } catch (throwable: IllegalStateException) {
            retryOrFail(attempt, throwable)
        }
    }

    private fun completeInitialization(attempt: InitializationAttempt) {
        if (!attempt.completed.compareAndSet(false, true)) {
            return
        }
        val stale: Boolean
        synchronized(initLock) {
            stale = activeInitializationAttempt !== attempt || ready
            if (!stale) {
                webView = attempt.view
                ready = true
                activeInitializationAttempt = null
            }
        }
        if (stale) {
            destroyWebView(attempt.view)
            return
        }
        Log.status(
            TAG,
            "ready source=bridge attempt=${attempt.number} elapsedMs=${attempt.elapsedMs()}" +
                " mainThread=${Looper.myLooper() == Looper.getMainLooper()}"
        )
        attempt.latch.countDown()
    }

    private fun retryOrFail(attempt: InitializationAttempt, throwable: Throwable) {
        if (!attempt.completed.compareAndSet(false, true)) {
            return
        }
        if (!isActiveInitializationAttempt(attempt)) {
            destroyWebView(attempt.view)
            return
        }
        destroyWebView(attempt.view)
        if (attempt.number < MAX_READY_CALLBACK_ATTEMPTS) {
            val retry: InitializationAttempt
            synchronized(initLock) {
                if (activeInitializationAttempt !== attempt || ready) {
                    return
                }
                retry = InitializationAttempt(
                    ++nextInitializationAttemptId, attempt.number + 1, attempt.latch, attempt.error
                )
                activeInitializationAttempt = retry
            }
            Log.status(
                TAG,
                "retrying WebView runtime ready callback after attempt ${attempt.number}" +
                    " elapsedMs=${attempt.elapsedMs()}",
                throwable
            )
            if (!mainHandler.post { createWebView(retry) }) {
                retryOrFail(retry, IllegalStateException("Could not post WebView retry"))
            }
            return
        }
        synchronized(initLock) {
            if (activeInitializationAttempt !== attempt || ready) {
                return
            }
            activeInitializationAttempt = null
            attempt.error.compareAndSet(null, throwable)
        }
        Log.error(
            TAG,
            "WebView runtime ready callback failed attempt=${attempt.number}" +
                " elapsedMs=${attempt.elapsedMs()}",
            throwable
        )
        attempt.latch.countDown()
        notifyInitializationFailure(throwable)
    }

    private fun isActiveInitializationAttempt(attempt: InitializationAttempt): Boolean =
        synchronized(initLock) {
            activeInitializationAttempt === attempt && initLatch === attempt.latch &&
                initError === attempt.error && !ready
        }

    private fun notifyInitializationFailure(throwable: Throwable) {
        val callback = synchronized(initLock) { initializationFailureCallback }
        callback?.onInitializationFailure(throwable)
    }

    private class InitializationAttempt(
        val id: Long,
        val number: Int,
        val latch: CountDownLatch,
        val error: AtomicReference<Throwable>
    ) {
        val completed = AtomicBoolean()
        private val startedAtMs = SystemClock.elapsedRealtime()

        @Volatile var view: WebView? = null

        fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAtMs
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onRuntimeDocumentReady(attemptId: String?) {
            mainHandler.post {
                val attempt = synchronized(initLock) {
                    val active = activeInitializationAttempt
                    if (active == null || active.id.toString() != attemptId) {
                        null
                    } else {
                        active
                    }
                }
                if (attempt != null) {
                    completeInitialization(attempt)
                }
            }
        }

        @JavascriptInterface
        fun onSabrLocalDomJsInitializationError(sessionId: String?, error: String?) {
            sabrLocalDomCallbacks[sessionId]?.onJsInitializationError(error ?: "")
        }

        @JavascriptInterface
        fun onSabrLocalDomRunBotguardResult(sessionId: String?, botguardResponse: String?) {
            sabrLocalDomCallbacks[sessionId]?.onRunBotguardResult(botguardResponse ?: "")
        }

        @JavascriptInterface
        fun onSabrLocalDomMinterReady(sessionId: String?) {
            sabrLocalDomCallbacks[sessionId]?.onMinterReady()
        }

        @JavascriptInterface
        fun onSabrLocalDomObtainPoTokenResult(
            sessionId: String?,
            identifier: String?,
            poTokenU8: String?
        ) {
            sabrLocalDomCallbacks[sessionId]
                ?.onObtainPoTokenResult(identifier ?: "", poTokenU8 ?: "")
        }

        @JavascriptInterface
        fun onSabrLocalDomObtainPoTokenError(
            sessionId: String?,
            identifier: String?,
            error: String?
        ) {
            sabrLocalDomCallbacks[sessionId]
                ?.onObtainPoTokenError(identifier ?: "", error ?: "")
        }
    }

    /** Shared constants and helpers for SharedWebViewRuntime. */
    companion object {
        const val BRIDGE_NAME = "PipePipeWebViewBridge"

        private const val TAG = "SharedWebViewRuntime"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val READY_CALLBACK_ATTEMPT_TIMEOUT_MS = 5_000L
        private const val MAX_READY_CALLBACK_ATTEMPTS = 2
        private const val ASSET_READ_BUFFER_SIZE = 8192
        internal const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

        @Volatile private var instance: SharedWebViewRuntime? = null

        @JvmStatic
        fun get(context: Context): SharedWebViewRuntime {
            var runtime = instance
            if (runtime == null) {
                synchronized(SharedWebViewRuntime::class.java) {
                    runtime = instance
                    if (runtime == null) {
                        runtime = SharedWebViewRuntime(context)
                        instance = runtime
                    }
                }
            }
            return runtime!!
        }

        @JvmStatic
        fun warmUp(context: Context) {
            get(context).warmUp()
        }

        private fun runtimeDocument(attemptId: Long): String =
            "<!doctype html><html><head><script>" +
                "PipePipeWebViewBridge.onRuntimeDocumentReady('$attemptId');" +
                "</script><title></title></head><body></body></html>"

        private fun destroyWebView(view: WebView?) {
            if (view == null) {
                return
            }
            try {
                view.stopLoading()
                view.destroy()
            } catch (throwable: IllegalStateException) {
                Log.status(TAG, "Could not destroy failed WebView initialization attempt", throwable)
            }
        }
    }
}
