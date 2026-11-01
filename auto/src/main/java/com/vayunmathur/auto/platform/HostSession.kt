package com.vayunmathur.auto.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.vayunmathur.library.log.Log
import android.view.MotionEvent
import android.view.Surface
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.HandshakeInfo
import androidx.car.app.IAppHost
import androidx.car.app.ICarApp
import androidx.car.app.ICarHost
import androidx.car.app.IOnDoneCallback
import androidx.car.app.ISurfaceCallback
import androidx.car.app.SessionInfo
import androidx.car.app.SessionInfoIntentEncoder
import androidx.car.app.SurfaceContainer
import androidx.car.app.constraints.IConstraintHost
import androidx.car.app.navigation.INavigationHost
import androidx.car.app.serialization.Bundleable
import com.vayunmathur.library.carhost.HostTemplate
import com.vayunmathur.library.carhost.HostTemplateParsers
import java.security.InvalidParameterException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * One hosted car-app session: binds a single `CarAppService` component and
 * implements the host binders so the launcher renders whatever it publishes.
 *
 * Extracted from the old Maps-only `CarAppHost` with no behavior change: the
 * handshake (`HOST_API_LEVEL` 1, onHandshakeCompleted -> onAppCreate/Start/
 * Resume, 5s latch), the `SurfaceContainer` push/clear, night via
 * `onConfigurationChanged`, and map touches via `onScroll`/`onClick` are all
 * identical. What changed is the shape: the component + category are
 * parameters (not Maps constants), and templates parse through
 * [HostTemplateParsers] into [HostTemplate] instead of the
 * NavigationTemplate-only `HostNavState`.
 *
 * Threading: `bind()` may be called from any thread; every blocking binder
 * round-trip runs on a background thread, never main. `setSurface` hops off
 * the caller (the `TextureView` callback is main, and the app's answer may
 * block); `clearSurface` stays synchronous so the service can release the
 * surface on return. Template callbacks post to main.
 */
class HostSession(
    context: Context,
    private val component: ComponentName,
    private val category: String,
    /** App name for car-facing error messages. */
    private val label: CharSequence,
    /** The app's host validator rejected us; called on the handshake thread. */
    private val onRefused: () -> Unit = {},
    private val onTemplate: (HostTemplate) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var carApp: ICarApp? = null
    @Volatile private var surfaceCallback: ISurfaceCallback? = null
    @Volatile private var bound = false
    @Volatile private var handshakeDone = false

    /** Whether the app declared API 9 voice-assistant capabilities. */
    private val voiceCapabilities = AtomicReference(false)

    /** Whether the app registered an API 8 media playback token. */
    private val mediaToken = AtomicReference(false)

    private var localSurface: Surface? = null
    private var localWidth = 0
    private var localHeight = 0
    private var localDpi = DEFAULT_DPI

    private var connection: ServiceConnection? = null

    /** Host-side mirror of the app's 5-template task stack (SPEC §2.2). */
    private val taskStack = HostTaskStack()

    /** The component this session hosts. */
    fun componentName(): ComponentName = component

    /**
     * Whether the car-app service resolves. Uses the runtime enabled state: apps such as
     * Google Messages ship the service `enabled=false` and turn it on via
     * `setComponentEnabledSetting`, which `ServiceInfo.enabled` never reflects.
     */
    fun hasService(): Boolean = runCatching {
        appContext.packageManager.resolveService(Intent().setComponent(component), 0) != null
    }.getOrDefault(false)

    /** Binds the service; no-op when already bound or when the service is missing. */
    fun bind() {
        if (bound) return
        if (!hasService()) {
            Log.status(TAG, "$component missing; stays unhosted")
            postError("$label has turned off its car support.")
            return
        }
        val intent = Intent(CarAppService.SERVICE_INTERFACE).apply {
            this.component = this@HostSession.component
            addCategory(category)
            SessionInfoIntentEncoder.encode(SessionInfo(SessionInfo.DISPLAY_TYPE_MAIN, SESSION_ID), this)
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Log.status(TAG, "$component connected; starting handshake")
                if (binder == null) {
                    Log.status(TAG, "$component connected with null binder")
                    postError("$label didn't respond.")
                    return
                }
                thread(name = "ma-auto-carhost", isDaemon = true) {
                    runHandshake(ICarApp.Stub.asInterface(binder))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.status(TAG, "$component disconnected")
                carApp = null
                bound = false
                handshakeDone = false
                mainHandler.post { onTemplate(HostTemplate.Pane(title = null)) }
            }

            override fun onBindingDied(name: ComponentName?) {
                Log.status(TAG, "$component binding died")
                postError("$label stopped unexpectedly.")
            }

            override fun onNullBinding(name: ComponentName?) {
                Log.status(TAG, "$component returned null binding")
                mainHandler.post { onTemplate(HostTemplate.Pane(title = null)) }
            }
        }
        connection = conn
        Log.status(TAG, "binding car service $component")
        val ok = runCatching {
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        bound = ok
        Log.status(TAG, "bindService returned $ok")
        if (!ok) postError("Couldn't connect to $label.")
    }

    /** Replaces the loading spinner with a terminal message; every dead-end bind path ends here. */
    private fun postError(message: String) {
        mainHandler.post { onTemplate(HostTemplate.Message(title = "Can't open $label", message = message)) }
    }

    /**
     * Hands a surface to the app.
     * Safe from any thread; the binder answer hops off the caller.
     */
    fun setSurface(surface: Surface, widthPx: Int, heightPx: Int) {
        localSurface = surface
        localWidth = widthPx
        localHeight = heightPx
        localDpi = appContext.resources.displayMetrics.densityDpi
        thread(name = "ma-auto-carhost-surface", isDaemon = true) { pushSurface() }
    }

    /**
     * The surface went away; tells the app synchronously so the service
     * can release the surface on return (same contract as `CarMapRenderer`:
     * the host releases the `Surface` as soon as this returns).
     */
    fun clearSurface() {
        val cb = surfaceCallback
        val last = localSurface
        localSurface = null
        if (cb != null && last != null) {
            runCatching {
                val container = SurfaceContainer(last, localWidth, localHeight, localDpi)
                bundleOf(container)?.let { cb.onSurfaceDestroyed(it, doneCallback()) }
            }.onFailure { Log.status(TAG, "onSurfaceDestroyed failed", it) }
        }
    }

    /** Pushes night as a configuration change; the app restyles its own map. */
    fun setNight(dark: Boolean) {
        val app = carApp ?: return
        if (!handshakeDone) return
        thread(name = "ma-auto-carhost-night", isDaemon = true) {
            runCatching {
                val config = Configuration(appContext.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                app.onConfigurationChanged(config, doneCallback())
            }.onFailure { Log.status(TAG, "onConfigurationChanged failed", it) }
        }
    }

    /**
     * Forwards one touch frame on the map surface to the app.
     * Main thread only (called from the nav renderer's touch listener).
     */
    fun injectMapTouch(action: Int, xPx: Float, yPx: Float): Boolean {
        val cb = surfaceCallback ?: return false
        return runCatching {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = xPx
                    lastTouchY = yPx
                    touchMoved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = xPx - lastTouchX
                    val dy = yPx - lastTouchY
                    if (!touchMoved && (dx * dx + dy * dy) < TOUCH_SLOP_PX_SQ) {
                        return true
                    }
                    touchMoved = true
                    lastTouchX = xPx
                    lastTouchY = yPx
                    cb.onScroll(dx, dy)
                }
                MotionEvent.ACTION_UP -> {
                    lastTouchX = xPx
                    lastTouchY = yPx
                    if (!touchMoved) cb.onClick(xPx, yPx)
                    touchMoved = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchMoved = false
                }
                else -> return false
            }
            true
        }.getOrDefault(false)
    }

    @Volatile private var lastTouchX = 0f
    @Volatile private var lastTouchY = 0f
    @Volatile private var touchMoved = false

    /** Tears the session down; the service still owns the `Surface`. */
    fun unbind() {
        clearSurface()
        val app = carApp
        carApp = null
        handshakeDone = false
        if (app != null) {
            runCatching { app.onAppPause(doneCallback()) }
            runCatching { app.onAppStop(doneCallback()) }
        }
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
        bound = false
        surfaceCallback = null
    }

    // ----------------------------------------------------------------
    // Handshake (background thread only)
    // ----------------------------------------------------------------

    private fun runHandshake(app: ICarApp) {
        carApp = app
        Log.status(TAG, "starting car-app handshake for $component")
        val handshake = bundleOf(HandshakeInfo(appContext.packageName, HOST_API_LEVEL)) ?: run {
            postError("$label couldn't start.")
            return
        }
        when (roundTrip("onHandshakeCompleted") { cb -> app.onHandshakeCompleted(handshake, cb) }) {
            CallResult.OK -> Unit
            // An explicit handshake rejection is the app's HostValidator: it only
            // accepts allowlisted hosts (Google Messages: Google's Android Auto).
            CallResult.REJECTED -> {
                Log.status(TAG, "$component refused this host")
                onRefused()
                postError("$label only works with car systems its developer has approved, like Android Auto.")
                return
            }
            CallResult.FAILED -> {
                postError("$label didn't respond.")
                return
            }
        }
        val config = Configuration(appContext.resources.configuration)
        val intent = Intent(Intent.ACTION_MAIN)
        val started =
            roundTrip("onAppCreate") { cb -> app.onAppCreate(carHostBinder, intent, config, cb) } == CallResult.OK &&
                roundTrip("onAppStart") { cb -> app.onAppStart(cb) } == CallResult.OK &&
                roundTrip("onAppResume") { cb -> app.onAppResume(cb) } == CallResult.OK
        if (!started) {
            postError("$label couldn't start.")
            return
        }
        handshakeDone = true
        mainHandler.post { onTemplate(HostTemplate.Pane(loading = true)) }
        fetchTemplate()
        pushSurface()
    }

    /** Serializes one host object; null when the Bundler refuses it. */
    private fun bundleOf(value: Any): Bundleable? = runCatching {
        Bundleable.create(value)
    }.getOrNull()

    private enum class CallResult { OK, REJECTED, FAILED }

    /** One blocking binder call with a 5s cap: REJECTED is the app saying no, FAILED is no answer. */
    private fun roundTrip(name: String, call: (IOnDoneCallback) -> Unit): CallResult {
        Log.status(TAG, "$name: calling for $component")
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val cb = object : IOnDoneCallback.Stub() {
            override fun onSuccess(response: Bundleable?) {
                Log.status(TAG, "$name: success")
                latch.countDown()
            }

            override fun onFailure(response: Bundleable?) {
                Log.status(TAG, "$name: failure $response")
                failure.set(RuntimeException("$name failed: $response"))
                latch.countDown()
            }

            override fun getInterfaceVersion(): Int = IOnDoneCallback.VERSION
        }
        runCatching { call(cb) }.onFailure {
            Log.status(TAG, "$name binder call threw", it)
            return CallResult.FAILED
        }
        val done = runCatching { latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!done) Log.status(TAG, "$name timed out")
        failure.get()?.let { Log.status(TAG, "$name rejected", it) }
        return when {
            failure.get() != null -> CallResult.REJECTED
            done -> CallResult.OK
            else -> CallResult.FAILED
        }
    }

    /** Pulls the current template and forwards it. Background thread. */
    fun fetchTemplate() {
        val app = carApp ?: return
        val managerRef = AtomicReference<androidx.car.app.IAppManager?>(null)
        val latch = CountDownLatch(1)
        val managerCb = object : IOnDoneCallback.Stub() {
            override fun onSuccess(response: Bundleable?) {
                managerRef.set(runCatching { response?.get() as? androidx.car.app.IAppManager }.getOrNull())
                latch.countDown()
            }

            override fun onFailure(response: Bundleable?) {
                Log.status(TAG, "fetchTemplate: getManager failure $response")
                latch.countDown()
            }

            override fun getInterfaceVersion(): Int = IOnDoneCallback.VERSION
        }
        runCatching { app.getManager(CarContext.APP_SERVICE, managerCb) }
            .onFailure {
                Log.status(TAG, "getManager threw", it)
                postError("$label didn't respond.")
                return
            }
        latch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val manager = managerRef.get() ?: run {
            Log.status(TAG, "getManager returned no manager")
            postError("$label didn't respond.")
            return
        }
        val templateRef = AtomicReference<Any?>(null)
        val templateLatch = CountDownLatch(1)
        val templateCb = object : IOnDoneCallback.Stub() {
            override fun onSuccess(response: Bundleable?) {
                templateRef.set(runCatching { response?.get() }.getOrNull())
                templateLatch.countDown()
            }

            override fun onFailure(response: Bundleable?) {
                Log.status(TAG, "fetchTemplate: getTemplate failure $response")
                templateLatch.countDown()
            }

            override fun getInterfaceVersion(): Int = IOnDoneCallback.VERSION
        }
        runCatching { manager.getTemplate(templateCb) }
            .onFailure {
                Log.status(TAG, "getTemplate threw", it)
                postError("$label didn't respond.")
                return
            }
        templateLatch.await(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val wrapper = templateRef.get() as? androidx.car.app.model.TemplateWrapper ?: run {
            Log.status(TAG, "getTemplate returned no template for $component")
            postError("$label didn't respond.")
            return
        }
        val parsed = HostTemplateParsers.parse(wrapper)
        taskStack.record(parsed)
        if (!taskStack.isAllowed(parsed)) {
            Log.status(TAG, "task quota exceeded for $component; showing error pane")
            mainHandler.post { onTemplate(HostTemplate.Pane(title = "Too many screens")) }
            return
        }
        mainHandler.post { onTemplate(parsed) }
    }

    private fun pushSurface() {
        val cb = surfaceCallback ?: return
        val surface = localSurface ?: return
        if (localWidth <= 0 || localHeight <= 0) return
        runCatching {
            bundleOf(SurfaceContainer(surface, localWidth, localHeight, localDpi))
                ?.let { cb.onSurfaceAvailable(it, doneCallback()) }
        }.onFailure { Log.status(TAG, "onSurfaceAvailable failed", it) }
    }

    private fun doneCallback(): IOnDoneCallback = object : IOnDoneCallback.Stub() {
        override fun onSuccess(response: Bundleable?) = Unit
        override fun onFailure(response: Bundleable?) {
            Log.status(TAG, "host call rejected: $response")
        }

        override fun getInterfaceVersion(): Int = IOnDoneCallback.VERSION
    }

    // ----------------------------------------------------------------
    // Host binders (these ARE the host the app talks to)
    // ----------------------------------------------------------------

    private val carHostBinder = object : ICarHost.Stub() {
        override fun startCarApp(intent: Intent?) {
            if (intent == null) return
            runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }.onFailure { Log.status(TAG, "startCarApp failed", it) }
        }

        override fun getHost(hostType: String?): IBinder {
            return when (hostType) {
                CarContext.APP_SERVICE -> appHostBinder.asBinder()
                CarContext.NAVIGATION_SERVICE -> navHostBinder.asBinder()
                CarContext.CONSTRAINT_SERVICE -> constraintHostBinder.asBinder()
                CarContext.MEDIA_PLAYBACK_SERVICE -> mediaHostBinder.asBinder()
                CarContext.SUGGESTION_SERVICE -> suggestionHostBinder.asBinder()
                else -> throw InvalidParameterException("Invalid host type: $hostType")
            }
        }

        override fun finish() = Unit

        override fun getInterfaceVersion(): Int = ICarHost.VERSION
    }

    private val appHostBinder = object : IAppHost.Stub() {
        override fun invalidate() {
            thread(name = "ma-auto-carhost-invalidate", isDaemon = true) { fetchTemplate() }
        }

        override fun showToast(text: CharSequence?, duration: Int) {
            Log.status(TAG, "car toast: $text")
        }

        override fun setSurfaceCallback(callback: ISurfaceCallback?) {
            surfaceCallback = callback
            thread(name = "ma-auto-carhost-surface", isDaemon = true) { pushSurface() }
        }

        override fun sendLocation(location: android.location.Location?) = Unit

        override fun showAlert(alert: Bundleable?) {
            Log.status(TAG, "car alert shown (not rendered)")
        }

        override fun dismissAlert(alertId: Int) = Unit

        override fun openMicrophone(request: Bundleable?): Bundleable {
            throw RemoteException("openMicrophone not supported")
        }

        override fun getInterfaceVersion(): Int = IAppHost.VERSION
    }

    private val navHostBinder = object : INavigationHost.Stub() {
        override fun navigationStarted() {
            Log.status(TAG, "$component navigation started")
            taskStack.reset() // NavigationTemplate resets the task quota
        }

        override fun navigationEnded() {
            Log.status(TAG, "$component navigation ended")
            mainHandler.post { onTemplate(HostTemplate.Pane(title = null)) }
        }

        override fun updateTrip(trip: Bundleable?) = Unit

        override fun setVoiceAssistantCapabilities(capabilities: Bundleable?) {
            // API 9: nav apps declare voice actions/disruptions/consent.
            // This host has no voice pipeline of its own — GAL carries mic
            // audio on ch6 (MicSourceChannel) and the phone resolves intents.
            // Record receipt so a future voice route can consume it.
            Log.status(TAG, "$component voice-assistant capabilities received")
            voiceCapabilities.set(capabilities != null)
        }

        override fun getInterfaceVersion(): Int =
            androidx.car.app.navigation.INavigationHost.VERSION
    }

    private val constraintHostBinder = object : IConstraintHost.Stub() {
        override fun getContentLimit(contentLimitType: Int): Int = CONTENT_LIMIT_DEFAULT

        override fun isAppDrivenRefreshEnabled(): Boolean = false

        override fun getInterfaceVersion(): Int = IConstraintHost.VERSION
    }

    /**
     * API 3: vehicle hardware is NOT dispatched through car-app IPC on this
     * host — and deliberately so (GAL-vs-car-app decision, recorded):
     * `SensorChannel` already streams GPS/sensors over the GAL sensor proto,
     * and car climate has no GAL counterpart at all. Apps calling
     * `CarHardwareManager` against this host get `UNIMPLEMENTED`/`UNAVAILABLE`
     * `CarValue`s (the library default when no hardware host answers), and
     * should read location through the platform `LocationManager` path they
     * already use. A `CarHardwareManager`→GAL bridge is a follow-up, not part
     * of this template rollout.
     */

    /**
     * API 8: receives the app's `MediaSessionCompat.Token` bundle.
     *
     * GAL-vs-car-app decision (recorded): the token is bridged, not forked.
     * `MusicCaptureService` keeps owning phone-side capture; this stub logs
     * receipt so a future host media UI can read playback state from the
     * token. No new GAL proto — media browse/playback stays on the car-app
     * channel, GAL ch5/ch6 carry the audio bytes as before.
     */
    private val mediaHostBinder = object : androidx.car.app.media.IMediaPlaybackHost.Stub() {
        override fun registerMediaSessionToken(token: Bundleable?) {
            Log.status(TAG, "$component media playback token registered")
            mediaToken.set(token != null)
        }

        override fun getInterfaceVersion(): Int =
            androidx.car.app.media.IMediaPlaybackHost.VERSION
    }

    /**
     * API 5: receives nav suggestion bundles. Same bridge policy as media:
     * logged, not re-transported — GAL `navigation.proto` stays a stub and
     * `NavStatusChannel` keeps carrying guidance bytes.
     */
    private val suggestionHostBinder = object : androidx.car.app.suggestion.ISuggestionHost.Stub() {
        override fun updateSuggestions(suggestions: Bundleable?) {
            Log.status(TAG, "$component suggestions updated")
        }

        override fun getInterfaceVersion(): Int =
            androidx.car.app.suggestion.ISuggestionHost.VERSION
    }

    private companion object {
        const val TAG = "MaAuto.HostSession"
        const val SESSION_ID = "main"
        const val DEFAULT_DPI = 160

        /**
         * Host API level 9 (car-app 1.9.0-alpha02): negotiate full surface.
         * Apps declaring an older `minCarApiLevel` still bind — the range gate
         * in `CarAppBinder` passes when our level is within the app's
         * [min, max] — and gate their own API 6/7/8/9 calls on the negotiated
         * level, so old apps keep working unchanged.
         */
        const val HOST_API_LEVEL = 9

        const val CALL_TIMEOUT_MS = 5_000L
        const val CONTENT_LIMIT_DEFAULT = 6
        const val TOUCH_SLOP_PX_SQ = 64f
    }
}

/**
 * Host-side mirror of the car-app 5-template task quota (SPEC §2.2).
 *
 * The host cannot see pushes/pops directly — only successive `getTemplate`
 * results. Heuristic: a Navigation template resets the count (quota-reset
 * view); an identical-kind repeat is a refresh (not counted); anything else
 * pushes. Over-counting only risks a false error pane, never a crash — the
 * app's own stack stays authoritative. `resetApp` (rebind) clears the mirror.
 */
internal class HostTaskStack {
    private var depth = 0
    private var lastKind: String? = null

    fun record(template: HostTemplate) {
        val kind = template.javaClass.simpleName
        if (template is HostTemplate.Navigation) {
            depth = 1
            lastKind = kind
            return
        }
        if (kind == lastKind) return // refresh: same type + same content shape
        lastKind = kind
        depth += 1
    }

    fun isAllowed(template: HostTemplate): Boolean {
        if (depth <= MAX_TASK_TEMPLATES) return true
        // Task enders are always allowed — the stack unwinds from here.
        return template is HostTemplate.Navigation ||
            template is HostTemplate.Pane ||
            template is HostTemplate.Message
    }

    fun reset() {
        depth = 0
        lastKind = null
    }

    private companion object {
        const val MAX_TASK_TEMPLATES = 5
    }
}
