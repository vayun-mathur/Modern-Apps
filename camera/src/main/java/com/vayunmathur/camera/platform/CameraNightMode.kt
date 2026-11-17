package com.vayunmathur.camera.platform

import com.vayunmathur.library.log.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageCapture
import androidx.camera.extensions.ExtensionSessionConfig
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.vayunmathur.camera.platform.lensSelector
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume

internal fun CameraViewModel.nightExtFailedRecently(): Boolean {
    val at = ds.getString("night_ext_failed_at")?.toLongOrNull() ?: return false
    return System.currentTimeMillis() - at < CameraViewModel.NIGHT_EXT_FAILURE_TTL_MS
}

internal fun CameraViewModel.recordNightExtensionFailure() {
    viewModelScope.launch { ds.setString("night_ext_failed_at", System.currentTimeMillis().toString()) }
    nightExtensionUsableMutable.value = false
}

/**
 * Re-evaluates whether night should be offered on the current lens/mode. Cheap checks first
 * (mode + the daily failure cache), then the isSessionConfigSupported() probe against the
 * selected lens' selector. Heavy — call off the main thread.
 */
suspend fun CameraViewModel.refreshNightExtensionUsable(cameraMode: CameraMode) {
    nightExtensionUsableMutable.value = when {
        cameraMode != CameraMode.PHOTO -> false
        nightExtFailedRecently() -> false
        else -> isNightExtensionAvailable()
    }
}

/** Observe getNightModeIndicator() on the freshly-bound camera (call on the main thread). */
internal fun CameraViewModel.observeNightModeIndicator(cameraInfo: androidx.camera.core.CameraInfo) {
    stopObservingNightModeIndicator()
    nightIndicatorSupported = try { cameraInfo.isNightModeIndicatorSupported } catch (_: Exception) { false }
    if (!nightIndicatorSupported) return
    val ld = try { cameraInfo.getNightModeIndicator() } catch (_: Exception) { null } ?: return
    nightIndicatorLiveData = ld
    ld.observeForever(nightIndicatorObserver)
}

internal fun CameraViewModel.stopObservingNightModeIndicator() {
    nightIndicatorLiveData?.removeObserver(nightIndicatorObserver)
    nightIndicatorLiveData = null
}

/** Observe the bound extension camera's processing strength (call on the main thread). */
internal fun CameraViewModel.observeExtensionStrength(cameraInfo: androidx.camera.core.CameraInfo) {
    stopObservingExtensionStrength()
    val mgr = extensionsManager ?: return
    val info = try { mgr.getCameraExtensionsInfo(cameraInfo) } catch (_: Exception) { return }
    if (!info.isExtensionStrengthAvailable) return
    val ld = try { info.getExtensionStrength() } catch (_: Exception) { null } ?: return
    extensionStrengthLiveData = ld
    ld.observeForever(extensionStrengthObserver)
}

internal fun CameraViewModel.stopObservingExtensionStrength() {
    extensionStrengthLiveData?.removeObserver(extensionStrengthObserver)
    extensionStrengthLiveData = null
    extensionStrengthMutable.value = null
}

internal fun CameraViewModel.observeExtensionCameraState(cameraInfo: androidx.camera.core.CameraInfo) {
    stopObservingExtensionCameraState()
    val ld = cameraInfo.cameraState
    extensionCameraStateLiveData = ld
    ld.observeForever(extensionCameraStateObserver)
}

internal fun CameraViewModel.stopObservingExtensionCameraState() {
    extensionCameraStateLiveData?.removeObserver(extensionCameraStateObserver)
    extensionCameraStateLiveData = null
}

/**
 * Feeds the PhotoAnalyzer's average scene luminance through a hysteresis + debounce filter so
 * night mode engages/disengages smoothly. When the scene brightens back up (true→false), the
 * user's per-scene override is reset so the next dark scene re-engages cleanly.
 */
fun CameraViewModel.onLuminance(avg: Float) {
    // getNightModeIndicator() is authoritative when the device supports it — skip the luminance
    // heuristic so the two don't fight. This luma path is only the fallback for devices without
    // the indicator.
    if (nightIndicatorSupported) return
    val beforeLow = lowLightDetectedMutable.value
    val beforeOff = nightModeOverriddenOffMutable.value
    Log.dev(
        "NightPreview",
        "onLuminance() avg=$avg lowLightBefore=$beforeLow overriddenOff=$beforeOff " +
            "lowFrames=$lowLumaFrames highFrames=$highLumaFrames " +
            "nightActive=${nightModeActive.value} photoActive=${photoSessionActiveMutable.value} " +
            "nightPreviewActive=${nightPreviewActiveMutable.value} thread=${Thread.currentThread().name}"
    )
    if (lowLightDetectedMutable.value) {
        trackBrightening(avg)
    } else {
        trackDarkening(avg)
    }
    if (beforeLow != lowLightDetectedMutable.value || beforeOff != nightModeOverriddenOffMutable.value) {
        Log.dev(
            "NightPreview",
            "onLuminance() STATE CHANGE low $beforeLow -> ${lowLightDetectedMutable.value} " +
                "off $beforeOff -> ${nightModeOverriddenOffMutable.value} nightActive ${nightModeActive.value}"
        )
    }
}

/** Bright-scene arm of the hysteresis filter: disengages night after enough bright frames. */
private fun CameraViewModel.trackBrightening(avg: Float) {
    if (avg <= CameraViewModel.NIGHT_DISENGAGE_LUMA) {
        if (highLumaFrames != 0) {
            Log.dev(
                "NightPreview",
                "onLuminance() resetting highFrames 0 (avg=$avg still below disengage)"
            )
        }
        highLumaFrames = 0
        return
    }
    highLumaFrames++
    lowLumaFrames = 0
    Log.dev(
        "NightPreview",
        "onLuminance() currently in low-light, avg $avg > disengage " +
            "${CameraViewModel.NIGHT_DISENGAGE_LUMA}, " +
            "highFrames=$highLumaFrames/${CameraViewModel.NIGHT_DEBOUNCE_FRAMES}"
    )
    if (highLumaFrames >= CameraViewModel.NIGHT_DEBOUNCE_FRAMES) {
        Log.dev(
            "NightPreview",
            "onLuminance() DISENGAGING night – high luma for " +
                "${CameraViewModel.NIGHT_DEBOUNCE_FRAMES} frames, lowLight=true->false"
        )
        lowLightDetectedMutable.value = false
        nightModeOverriddenOffMutable.value = false
        highLumaFrames = 0
        Log.dev(
            "NightPreview",
            "onLuminance() after DISENGAGE lowLight=${lowLightDetectedMutable.value} " +
                "nightActive=${nightModeActive.value} – triggers teardown->setupPhotoSession() rebind"
        )
    }
}

/** Dark-scene arm of the hysteresis filter: engages night after enough dark frames. */
private fun CameraViewModel.trackDarkening(avg: Float) {
    if (avg >= CameraViewModel.NIGHT_ENGAGE_LUMA) {
        if (lowLumaFrames != 0) {
            Log.dev("NightPreview", "onLuminance() resetting lowFrames 0 (avg=$avg above engage)")
        }
        lowLumaFrames = 0
        return
    }
    lowLumaFrames++
    highLumaFrames = 0
    Log.dev(
        "NightPreview",
        "onLuminance() avg $avg < engage ${CameraViewModel.NIGHT_ENGAGE_LUMA}, " +
            "lowFrames=$lowLumaFrames/${CameraViewModel.NIGHT_DEBOUNCE_FRAMES}"
    )
    if (lowLumaFrames >= CameraViewModel.NIGHT_DEBOUNCE_FRAMES) {
        Log.dev(
            "NightPreview",
            "onLuminance() ENGAGING night – low luma for " +
                "${CameraViewModel.NIGHT_DEBOUNCE_FRAMES} frames, lowLight=false->true"
        )
        lowLightDetectedMutable.value = true
        lowLumaFrames = 0
        Log.dev(
            "NightPreview",
            "onLuminance() after ENGAGE lowLight=${lowLightDetectedMutable.value} " +
                "nightActive=${nightModeActive.value} " +
                "overriddenOff=${nightModeOverriddenOffMutable.value} – if extension available, " +
                "useNightPreview should become true and rebind to setupNightPreviewSession()"
        )
    }
}

/** Toggles the user's override for the current dark scene (moon button handler). */
fun CameraViewModel.toggleNightModeOverride() {
    val before = nightModeOverriddenOffMutable.value
    nightModeOverriddenOffMutable.value = !nightModeOverriddenOffMutable.value
    Log.dev(
        "NightPreview",
        "toggleNightModeOverride() CLICK moon button beforeOff=$before " +
            "afterOff=${nightModeOverriddenOffMutable.value} lowLight=${lowLightDetectedMutable.value} " +
            "nightActiveBefore=${!before && lowLightDetectedMutable.value} " +
            "nightActiveAfter=${nightModeActive.value} thread=${Thread.currentThread().name} – " +
            "screen should transition? if extension binds, preview resolution changes " +
            "and zoom bar may collapse to 1x"
    )
}

internal fun CameraViewModel.resetNightModeDetection() {
    lowLightDetectedMutable.value = false
    nightModeOverriddenOffMutable.value = false
    lowLumaFrames = 0
    highLumaFrames = 0
}

/** Obtains (and caches) the ExtensionsManager bound to [provider]. Null if unavailable. */
internal suspend fun CameraViewModel.getExtensionsManager(provider: ProcessCameraProvider): ExtensionsManager? {
    Log.dev(
        "NightPreview",
        "getExtensionsManager() called providerHash=${provider.hashCode()} " +
            "cachedExists=${extensionsManager != null} thread=${Thread.currentThread().name} " +
            "startMs=${System.currentTimeMillis()}"
    )
    extensionsManager?.let {
        Log.dev(
            "NightPreview",
            "getExtensionsManager() returning CACHED manager providerHash=${provider.hashCode()} " +
                "manager=$it – NOTE: cached across provider instances, " +
                "may be tied to old provider if provider changed!"
        )
        return it
    }
    return try {
        val mgr = awaitExtensionsManager(provider)
        Log.dev(
            "NightPreview",
            "getExtensionsManager() obtained mgr=$mgr caching for providerHash=${provider.hashCode()}"
        )
        extensionsManager = mgr
        mgr
    } catch (e: IllegalStateException) {
        Log.error(
            "NightPreview",
            "getExtensionsManager() EXCEPTION – ExtensionsManager unavailable (was hidden as Warn), " +
                "root cause of black preview if NIGHT needed",
            e
        )
        null
    } catch (e: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "getExtensionsManager() EXCEPTION – ExtensionsManager unavailable (was hidden as Warn), " +
                "root cause of black preview if NIGHT needed",
            e
        )
        null
    }
}

/** Suspends until the ExtensionsManager instance for [provider] resolves. */
private suspend fun CameraViewModel.awaitExtensionsManager(
    provider: ProcessCameraProvider
): ExtensionsManager {
    val start = System.currentTimeMillis()
    return suspendCancellableCoroutine<ExtensionsManager> { cont ->
        Log.dev(
            "NightPreview",
            "getExtensionsManager() creating async instance for providerHash=${provider.hashCode()}"
        )
        val future = ExtensionsManager.getInstanceAsync(app, provider)
        future.addListener({
            resolveManagerFuture(future, start, cont)
        }, ContextCompat.getMainExecutor(app))
    }
}

/** Resumes [cont] with the manager future's result, logging failures. */
private fun CameraViewModel.resolveManagerFuture(
    future: com.google.common.util.concurrent.ListenableFuture<ExtensionsManager>,
    start: Long,
    cont: CancellableContinuation<ExtensionsManager>
) {
    try {
        val res = future.get()
        Log.dev(
            "NightPreview",
            "getExtensionsManager() future.get() SUCCESS res=$res " +
                "elapsed=${System.currentTimeMillis() - start}ms"
        )
        cont.resume(res)
    } catch (e: java.util.concurrent.ExecutionException) {
        Log.error(
            "NightPreview",
            "getExtensionsManager() future.get() FAILED " +
                "elapsed=${System.currentTimeMillis() - start}ms – " +
                "this was previously swallowed as Warn",
            e
        )
        cont.cancel(e)
    } catch (e: InterruptedException) {
        Log.error(
            "NightPreview",
            "getExtensionsManager() future.get() FAILED " +
                "elapsed=${System.currentTimeMillis() - start}ms – " +
                "this was previously swallowed as Warn",
            e
        )
        cont.cancel(e)
    }
}

/** Whether the CameraX NIGHT extension is available on the current lens. */
suspend fun CameraViewModel.isNightExtensionAvailable(): Boolean {
    val startMs = System.currentTimeMillis()
    Log.dev(
        "NightPreview",
        "isNightExtensionAvailable() START lensFacing=${lensFacingMutable.value} " +
            "thread=${Thread.currentThread().name}"
    )
    return try {
        probeNightExtension(startMs)
    } catch (e: IllegalStateException) {
        Log.error(
            "NightPreview",
            "isNightExtensionAvailable() OUTER EXCEPTION – returning false, " +
                "totalTook=${System.currentTimeMillis() - startMs}ms",
            e
        )
        false
    } catch (e: IllegalArgumentException) {
        Log.error(
            "NightPreview",
            "isNightExtensionAvailable() OUTER EXCEPTION – returning false, " +
                "totalTook=${System.currentTimeMillis() - startMs}ms",
            e
        )
        false
    }
}

/** Resolves the provider + manager and probes NIGHT support on the current lens. */
private suspend fun CameraViewModel.probeNightExtension(startMs: Long): Boolean {
    val provider = ProcessCameraProvider.awaitInstance(app)
    Log.dev(
        "NightPreview",
        "isNightExtensionAvailable() got providerHash=${provider.hashCode()} " +
            "elapsed=${System.currentTimeMillis() - startMs}ms"
    )
    val mgr = getExtensionsManager(provider)
    Log.dev(
        "NightPreview",
        "isNightExtensionAvailable() ExtensionsManager=${mgr != null} " +
            "elapsed=${System.currentTimeMillis() - startMs}ms"
    )
    if (mgr == null) {
        Log.status(
            "NightPreview",
            "isNightExtensionAvailable() manager NULL after ${System.currentTimeMillis() - startMs}ms, " +
                "returning false -> moon button may show but useNightPreview false, so no visible transition"
        )
        return false
    }
    val selector = lensSelector(lensFacingMutable.value, selectedLensMutable.value)
    if (!mgr.isExtensionAvailable(selector, ExtensionMode.NIGHT)) {
        Log.dev(
            "NightPreview",
            "isNightExtensionAvailable() isExtensionAvailable(NIGHT)=false " +
                "lens=${lensFacingMutable.value}"
        )
        return false
    }
    return querySessionConfigSupport(provider, selector, mgr, startMs)
}

/**
 * Optimistic support query. Neither this nor a getCameraInfo probe reliably predicts the
 * GrapheneOS/Pixel extender failure (it only surfaces at actual bind time), so we accept a
 * yes here and rely on the daily failure cache (recordNightExtensionFailure) to stop
 * offering night after the first real bind failure.
 */
private fun CameraViewModel.querySessionConfigSupport(
    provider: ProcessCameraProvider,
    selector: CameraSelector,
    mgr: ExtensionsManager,
    startMs: Long
): Boolean {
    val cameraInfo = provider.getCameraInfo(selector)
    val nightConfig = ExtensionSessionConfig.Builder(ExtensionMode.NIGHT, mgr)
        .addUseCase(Preview.Builder().build())
        .addUseCase(ImageCapture.Builder().build())
        .build()
    val supported = try {
        cameraInfo.isSessionConfigSupported(nightConfig)
    } catch (e: IllegalStateException) {
        Log.status("NightPreview", "isNightExtensionAvailable() isSessionConfigSupported threw", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.status("NightPreview", "isNightExtensionAvailable() isSessionConfigSupported threw", e)
        false
    }
    Log.dev(
        "NightPreview",
        "isNightExtensionAvailable() isSessionConfigSupported(NIGHT)=$supported " +
            "lens=${lensFacingMutable.value} total=${System.currentTimeMillis() - startMs}ms"
    )
    return supported
}
