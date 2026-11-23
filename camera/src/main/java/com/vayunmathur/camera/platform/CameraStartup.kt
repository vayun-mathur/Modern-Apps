package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.vayunmathur.library.log.Log
import android.hardware.camera2.CameraManager
import android.util.Size
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.HighSpeedPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimum fps that counts as true high-frame-rate; gallery thumbnail size. */
private const val HFR_MIN_FPS = 60
private const val THUMBNAIL_SIDE_PX = 96

/**
 * Probe whether the BACK lens supports true high-speed video. Runs once at startup.
 * Does not affect other modes' quality.
 */
internal suspend fun CameraViewModel.probeSloMoSupport(): Boolean {
    // Camera2 probe: the BACK lens offers a constrained high-speed
    // operating point (planner selection over the characteristics
    // snapshot). Runs off the startup path; the first preview frame
    // never waits on it.
    return withContext(Dispatchers.Default) {
        try {
            ensureLensesEnumerated()
            val manager = app.getSystemService(CameraManager::class.java)
                ?: return@withContext false
            val lensId = CharacteristicsFacade.resolveCamera2Id(manager, LensFacing.BACK)
                ?: return@withContext false
            val facade = CharacteristicsFacade.tryFromCameraManager(manager, lensId)
                ?: return@withContext false
            val selection = HighSpeedPlanner.select(facade.snapshot)
            selection != null && selection.captureFps >= HFR_MIN_FPS
        } catch (e: IllegalStateException) {
            Log.status("SloMo", "Slo-Mo probe failed", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.status("SloMo", "Slo-Mo probe failed", e)
            false
        }
    }
}

internal suspend fun CameraViewModel.loadThumbnail(uri: Uri?): Bitmap? = uri?.let {
    withContext(Dispatchers.IO) {
        try {
            app.contentResolver.loadThumbnail(it, Size(THUMBNAIL_SIDE_PX, THUMBNAIL_SIDE_PX), null)
        } catch (e: java.io.IOException) {
            // SAF document URIs may not support loadThumbnail — decode directly.
            Log.status("CameraViewModel", "loadThumbnail failed; trying stream decode", e)
            try {
                app.contentResolver.openInputStream(it)?.use { stream ->
                    val bytes = stream.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, THUMBNAIL_SIDE_PX)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                }
            } catch (e2: java.io.IOException) {
                Log.status("CameraViewModel", "Failed to load gallery thumbnail", e2)
                null
            } catch (e2: SecurityException) {
                Log.status("CameraViewModel", "Failed to load gallery thumbnail", e2)
                null
            }
        } catch (e: SecurityException) {
            Log.status("CameraViewModel", "loadThumbnail failed", e)
            null
        }
    }
}

private fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while ((width / sample) > maxSide * 2 || (height / sample) > maxSide * 2) sample *= 2
    return sample
}
