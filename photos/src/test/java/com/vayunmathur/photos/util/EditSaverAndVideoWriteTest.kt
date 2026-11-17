package com.vayunmathur.photos.util
import com.vayunmathur.photos.platform.PhotoEditSaver
import com.vayunmathur.photos.platform.PhotoEditViewModel
import com.vayunmathur.photos.platform.VideoEditViewModel
import com.vayunmathur.photos.platform.workInfosOverride

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.data.awaitPhotosCondition
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Editor save/export and video write paths against a stub media provider:
 * inserts land, output streams write to a real sink file, and the
 * permission-denied/granted bookkeeping behaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditSaverAndVideoWriteTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed
    private lateinit var sink: File

    private inner class MediaProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: arrayOf("_id"))
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri =
            Uri.parse("content://media/external/images/media/9001")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 1
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ) = 1
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            sink.parentFile?.mkdirs()
            if (!sink.exists()) sink.createNewFile()
            return ParcelFileDescriptor.open(
                sink,
                if ("w" in mode) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
                else ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
        sink = File(app.cacheDir, "media-sink.bin")
        sink.delete()
        val provider = MediaProvider()
        provider.attachInfo(app, ProviderInfo().apply {
            authority = "media"
            packageName = app.packageName
        })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    private fun photo(name: String, video: Boolean = false) = Photo(
        name = name, uri = "content://media/external/images/media/501", date = 1L,
        width = 64, height = 48, dateModified = 1L, exifSet = true, lat = null, long = null,
        videoData = if (video) VideoData(1_000L) else null, panoData = null,
    )

    @Test fun saverWritesCopyAndOverwriteThroughProvider() = runBlocking {
        val vm = PhotoEditViewModel(app, installed.photos)
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(200, 30, 30))
        }
        vm.updateDocument {
            EditDocument(
                canvasWidth = 64, canvasHeight = 48,
                layers = listOf(PixelLayer(BitmapReference(bmp), name = "base")),
            )
        }
        var completed = 0
        vm.saver.savePhoto(photo("base.jpg"), asCopy = true,
            strokes = emptyList(), texts = emptyList(),
            viewportWidth = 64f, viewportHeight = 48f) { completed++ }
        assertTrue(awaitPhotosCondition { completed == 1 })
        assertTrue(sink.exists() && sink.length() > 0, "copy written: ${sink.length()}")
        sink.delete()
        vm.saver.savePhoto(photo("base.jpg"), asCopy = false,
            strokes = emptyList(), texts = emptyList(),
            viewportWidth = 64f, viewportHeight = 48f) { completed++ }
        assertTrue(awaitPhotosCondition { completed == 2 })
        assertTrue(sink.exists() && sink.length() > 0, "overwrite written: ${sink.length()}")
        // Permission bookkeeping: denied clears, granted with nothing pending is a no-op.
        vm.saver.onWritePermissionDenied()
        assertEquals(null, vm.saver.writePermissionRequest.value)
        vm.saver.onWritePermissionGranted()
        Unit
    }

    @Test fun finishExportAndPermissionCompletion() = runBlocking {
        val vm = VideoEditViewModel(app, installed.photos)
        val finish = VideoEditViewModel::class.java.getDeclaredMethod(
            "finishExport", Context::class.java, Photo::class.java,
            Boolean::class.javaPrimitiveType, File::class.java, Function0::class.java,
        ).apply { isAccessible = true }
        val temp = File(app.cacheDir, "finish.mp4").apply {
            FileOutputStream(this).use { it.write(ByteArray(512) { 5 }) }
        }
        var completed = 0
        finish.invoke(vm, app, photo("clip.mp4", video = true), true, temp, { completed++ })
        assertTrue(awaitPhotosCondition { completed == 1 })
        assertFalse(vm.exporting.value)
        // Permission-granted completion: seed the pending state as the
        // NeedsPermission branch would, then grant.
        val temp2 = File(app.cacheDir, "pending.mp4").apply {
            FileOutputStream(this).use { it.write(ByteArray(256) { 6 }) }
        }
        fun setField(name: String, value: Any?) {
            VideoEditViewModel::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(vm, value)
            }
        }
        setField("pendingExportFile", temp2)
        setField("pendingExportUri", android.net.Uri.parse("content://media/external/video/media/88"))
        var granted = 0
        setField("pendingOnComplete", { granted++ } as kotlin.jvm.functions.Function0<kotlin.Unit>)
        vm.onWritePermissionGranted()
        assertTrue(awaitPhotosCondition { granted == 1 })
        assertTrue(sink.exists() && sink.length() == 256L)
        // Denied with nothing pending is a clean no-op.
        vm.onWritePermissionDenied()
        Unit
    }

    @Test fun saverPermissionGrantWritesPendingBytes() = runBlocking {
        val vm = PhotoEditViewModel(app, installed.photos)
        fun setSaverField(name: String, value: Any?) {
            PhotoEditSaver::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(vm.saver, value)
            }
        }
        sink.delete()
        setSaverField("pendingSaveBytes", byteArrayOf(4, 5, 6, 7))
        setSaverField("pendingSaveUri", android.net.Uri.parse("content://media/external/images/media/9002"))
        var completed = 0
        setSaverField("pendingSaveOnComplete", { completed++ } as kotlin.jvm.functions.Function0<kotlin.Unit>)
        vm.saver.onWritePermissionGranted()
        assertTrue(awaitPhotosCondition { completed == 1 })
        assertEquals(listOf(4.toByte(), 5.toByte(), 6.toByte(), 7.toByte()), sink.readBytes().toList())
        assertEquals(null, vm.saver.writePermissionRequest.value)
        Unit
    }

    @Test fun videoWriteCopyAndOverwriteThroughProvider() {
        val temp = File(app.cacheDir, "edited.mp4").apply {
            FileOutputStream(this).use { it.write(ByteArray(2048) { 7 }) }
        }
        val companion = VideoEditViewModel::class.java.getDeclaredField("Companion")
            .apply { isAccessible = true }.get(null)
        val writeVideo = companion.javaClass.getDeclaredMethod(
            "writeVideo", Context::class.java, Photo::class.java, File::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val copyResult = writeVideo.invoke(companion, app, photo("clip.mp4", video = true), temp, true)
        assertEquals("Success", copyResult.javaClass.simpleName)
        assertTrue(sink.exists() && sink.length() == 2048L, "video copy bytes: ${sink.length()}")
        // Overwrite consumes a fresh temp file (the copy path deletes its input).
        val temp2 = File(app.cacheDir, "edited2.mp4").apply {
            FileOutputStream(this).use { it.write(ByteArray(1024) { 3 }) }
        }
        val overwriteResult = writeVideo.invoke(companion, app, photo("clip.mp4", video = true), temp2, false)
        assertEquals("Success", overwriteResult.javaClass.simpleName)
        assertNotNull(sink)
    }
}
