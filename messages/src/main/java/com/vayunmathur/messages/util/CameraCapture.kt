package com.vayunmathur.messages.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Helpers for the "take a photo" composer action.
 *
 * Primary path: hand a FileProvider [Uri] to the system camera app via
 * `ActivityResultContracts.TakePicture`. Fallback (when no camera app
 * handles the intent — e.g. on a device with only a built-in sensor and
 * no camera package): the caller shows the in-app CameraX capture screen
 * and writes the JPEG to a file from [newPhotoFile].
 */
object CameraCapture {

    /** Matches the FileProvider authority declared in AndroidManifest.xml. */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** Create a fresh empty JPEG file under cacheDir/camera. */
    fun newPhotoFile(context: Context): File {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        return File(dir, "capture_${System.currentTimeMillis()}.jpg")
    }

    /** Wrap [file] as a shareable content:// URI for the camera app. */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /**
     * True iff at least one installed app can handle ACTION_IMAGE_CAPTURE.
     * When false, callers should use the built-in CameraX fallback.
     * Relies on the `<queries>` IMAGE_CAPTURE entry in the manifest for
     * package visibility on Android 11+.
     */
    fun hasCameraApp(context: Context): Boolean {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        return intent.resolveActivity(context.packageManager) != null
    }
}
