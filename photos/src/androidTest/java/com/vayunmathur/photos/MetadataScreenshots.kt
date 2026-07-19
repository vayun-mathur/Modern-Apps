package com.vayunmathur.photos

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.util.setExifData
import com.vayunmathur.photos.util.syncPhotos
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:photos:metadata`. Seeds a set of colourful geotagged
 * JPEGs into MediaStore, indexes them into the app DB, then captures the gallery grid and
 * the map (clustered markers).
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snap(index: Int) {
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** Full-device screenshot (needed for the GL/SurfaceView MapLibre map). */
    private fun snapDevice(index: Int) {
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        File(outDir, "$index.png").outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun gradientBitmap(top: Int, bottom: Int): Bitmap {
        val size = 900
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        // A soft circle accent so thumbnails look photographic rather than flat.
        canvas.drawCircle(size * 0.7f, size * 0.3f, size * 0.18f, Paint().apply { color = Color.argb(70, 255, 255, 255) })
        return bmp
    }

    /** Remove images seeded by a previous run so reruns don't accumulate duplicates. */
    private fun deleteSeeded() {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ctx.contentResolver.delete(
            collection,
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$NAME_PREFIX%"),
        )
    }

    private fun seedImages() {
        val palettes = listOf(
            Color.rgb(255, 138, 101) to Color.rgb(255, 209, 128),
            Color.rgb(129, 212, 250) to Color.rgb(179, 157, 219),
            Color.rgb(165, 214, 167) to Color.rgb(255, 245, 157),
            Color.rgb(244, 143, 177) to Color.rgb(206, 147, 216),
            Color.rgb(128, 222, 234) to Color.rgb(128, 203, 196),
            Color.rgb(255, 171, 145) to Color.rgb(188, 170, 164),
            Color.rgb(159, 168, 218) to Color.rgb(144, 202, 249),
            Color.rgb(255, 204, 128) to Color.rgb(255, 138, 101),
            Color.rgb(178, 235, 242) to Color.rgb(174, 213, 129),
            Color.rgb(240, 98, 146) to Color.rgb(149, 117, 205),
            Color.rgb(255, 224, 130) to Color.rgb(129, 199, 132),
            Color.rgb(179, 229, 252) to Color.rgb(244, 143, 177),
        )
        val resolver = ctx.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val now = System.currentTimeMillis()
        palettes.forEachIndexed { i, (top, bottom) ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$NAME_PREFIX${i + 1}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.DATE_TAKEN, now - i * 3_600_000L)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return@forEachIndexed
            resolver.openOutputStream(uri)!!.use { out ->
                gradientBitmap(top, bottom).compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            // Geotag: cluster all photos near one city with small offsets. MediaStore
            // redacts the LATITUDE/LONGITUDE columns on API 29+, so the coordinates must
            // live in the file's EXIF (the app reads them via setRequireOriginal +
            // ACCESS_MEDIA_LOCATION).
            val lat = BASE_LAT + (i % 4) * 0.008
            val lon = BASE_LON + (i / 4) * 0.008
            resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setLatLong(lat, lon)
                exif.saveAttributes()
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    /** Click a bottom-nav item by its label through the accessibility tree (the map's
     * continuous re-projection loop would otherwise stall Compose's waitForIdle). */
    private fun clickByText(label: String): Boolean {
        val ui = instrumentation.uiAutomation
        repeat(10) {
            val root: AccessibilityNodeInfo? = ui.rootInActiveWindow
            if (root != null) {
                val nodes = root.findAccessibilityNodeInfosByText(label)
                for (node in nodes) {
                    var target: AccessibilityNodeInfo? = node
                    while (target != null && !target.isClickable) target = target.parent
                    if (target != null) {
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                }
            }
            Thread.sleep(300)
        }
        return false
    }

    /** Dismiss the system "built for a previous version / 16 KB" warning dialog if shown. */
    private fun dismissSystemDialog() {
        val ui = instrumentation.uiAutomation
        val labels = listOf("Close", "Got it", "OK", "Dismiss", "Cancel")
        repeat(3) {
            val root = ui.rootInActiveWindow ?: return
            for (label in labels) {
                for (node in root.findAccessibilityNodeInfosByText(label)) {
                    var target: AccessibilityNodeInfo? = node
                    while (target != null && !target.isClickable) target = target.parent
                    if (target != null) {
                        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Thread.sleep(500)
                        return
                    }
                }
            }
            Thread.sleep(300)
        }
    }

    @Test
    fun generateStoreScreenshots() {
        deleteSeeded()
        seedImages()

        // Index MediaStore into the app's shared Room DB and resolve EXIF GPS directly so
        // the gallery and map populate deterministically before capture.
        val db = ctx.buildDatabase<PhotoDatabase>()
        runBlocking {
            syncPhotos(ctx, db)
            setExifData(db.photoDao().getAll(), db, ctx)
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            // Let the gallery index MediaStore and load thumbnails.
            Thread.sleep(6000)
            // The 16 KB-incompatibility warning can pop over the app on launch; clear it
            // before capturing or navigating so it neither pollutes the shot nor blocks
            // the bottom-nav taps.
            dismissSystemDialog()
            snap(1)

            // Map page: GL-backed MapLibre view. Navigate via accessibility, wait for
            // tiles + clustered markers to render, then capture the whole screen.
            dismissSystemDialog()
            clickByText("Map")
            Thread.sleep(9000)
            dismissSystemDialog()
            snapDevice(2)
        }
    }

    companion object {
        private const val NAME_PREFIX = "metadata_sample_"
        private const val BASE_LAT = 37.7749
        private const val BASE_LON = -122.4194
    }
}
