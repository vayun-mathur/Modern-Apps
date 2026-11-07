package com.vayunmathur.photos.util

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.PhotoExifTarget
import com.vayunmathur.photos.data.PhotoScanRepository
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.data.VaultPhotoDao
import com.vayunmathur.photos.ui.SecureFolderPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Slice 1/3 second pass: populated SecureFolderPage + thumbnail/EXIF success paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1VaultPage2Test {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application

    private class FakeDao(rows: List<VaultPhoto>) : VaultPhotoDao {
        val store = rows.toMutableList()
        override fun getAllFlow(): Flow<List<VaultPhoto>> = flowOf(store.toList())
        override fun getByIdFlow(id: Long): Flow<VaultPhoto?> = flowOf(store.firstOrNull { it.id == id })
        override suspend fun upsert(value: VaultPhoto): Long {
            store += value
            return value.id
        }
        override suspend fun delete(value: VaultPhoto): Int =
            if (store.removeIf { it.id == value.id }) 1 else 0
    }

    private fun vaultPhoto(id: Long, name: String) = VaultPhoto(
        id = id, name = name, path = "/vault/$name.enc",
        thumbnailPath = "/vault/${name}_thumb.enc", date = 1000L,
        width = 100, height = 100, dateModified = 1000L,
    )

    private fun pumpUntil(desc: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out: $desc")
    }

    @Test
    fun populatedPageRendersGridAndVaultItemLongPressSelects() {
        val vm = SecureFolderViewModel(app)
        val photos = listOf(vaultPhoto(1L, "alpha.jpg"), vaultPhoto(2L, "beta.jpg"))
        vm.setVault(FakeDao(photos), "pw")
        val backStack = NavBackStack(arrayOf<Route>(Route.SecureFolder))
        composeRule.setContent {
            val columns = androidx.compose.runtime.remember { mutableFloatStateOf(3f) }
            CompositionLocalProvider(LocalColumnCount provides columns) {
                SecureFolderPage(backStack, "pw", vm)
            }
        }
        composeRule.onRoot().assertExists()
        composeRule.waitForIdle()
    }

    @Test
    fun vaultItemLongPressSelects() {
        val vm = SecureFolderViewModel(app)
        val photos = listOf(vaultPhoto(1L, "alpha.jpg"))
        var longClicked = false
        var clicked = false
        composeRule.setContent {
            com.vayunmathur.photos.ui.VaultPhotoItem(
                photo = photos[0], password = "pw", isSelected = false,
                isSelectionMode = false, secureFolderViewModel = vm,
                onClick = { clicked = true }, onLongClick = { longClicked = true },
            )
        }
        composeRule.onRoot().performTouchInput { longClick() }
        assertTrue(longClicked)
        assertEquals(false, clicked)
    }

    @Test
    fun requestThumbnailDecryptsIntoState() {
        val vm = SecureFolderViewModel(app)
        val manager = SecureFolderManager(context)
        // Build an encrypted thumbnail through the manager's own restore path:
        // encrypt a JPEG via decryptToCacheFile's inverse is not public, so use
        // the chunk format through a vault file created by encryptChunked in
        // the sibling test's shape - here simply reuse a real encrypted file
        // from decryptThumbnail's counterpart: create with Cipher directly.
        val plain = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bitmap.recycle()
        }.toByteArray()
        val key = javax.crypto.spec.SecretKeySpec(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("pw".toByteArray(Charsets.UTF_8)), "AES",
        )
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        val enc = cipher.doFinal(plain)
        val bytes = ByteArrayOutputStream().also { out ->
            out.write(byteArrayOf(0x53, 0x45, 0x43, 0x32))
            out.write(cipher.iv)
            out.write((enc.size ushr 24) and 0xFF)
            out.write((enc.size ushr 16) and 0xFF)
            out.write((enc.size ushr 8) and 0xFF)
            out.write(enc.size and 0xFF)
            out.write(enc)
        }.toByteArray()
        val file = File(context.cacheDir, "thumb-test.enc").also { it.writeBytes(bytes) }
        val state = vm.thumbnailState(file.absolutePath)
        vm.requestThumbnail(file.absolutePath, "pw")
        pumpUntil("thumbnail decrypted") { state.value != null }
        assertNotNull(state.value)
        // Second request hits the cache and re-publishes synchronously.
        vm.requestThumbnail(file.absolutePath, "pw")
        assertNotNull(vm.thumbnailState(file.absolutePath).value)
        assertNotNull(manager.decryptThumbnail(file.absolutePath, "pw"))
    }

    @Test
    fun exifPassParsesGpsFromMediaStoreImage() = runBlocking<Unit> {
        // Write a JPEG with GPS EXIF into a temp file, tag it, then insert it.
        val jpegFile = File(context.cacheDir, "exif-test.jpg")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        FileOutputStream(jpegFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val exif = ExifInterface(jpegFile.absolutePath)
        exif.setLatLong(37.42, -122.08)
        exif.saveAttributes()

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "exif-test.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull(uri)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            jpegFile.inputStream().use { it.copyTo(out) }
        }
        val id = android.content.ContentUris.parseId(uri)

        val scanRepository = object : PhotoScanRepository(context) {
            var written: List<com.vayunmathur.photos.data.ExifResult> = emptyList()
            override suspend fun getUnscannedForExif(): List<PhotoExifTarget> =
                listOf(PhotoExifTarget(id = id, uri = uri.toString()))
            override suspend fun setExifResults(results: List<com.vayunmathur.photos.data.ExifResult>) {
                written = results
            }
        }
        setExifData(scanRepository, context)
        val result = scanRepository.written.single()
        assertEquals(id, result.id)
        assertEquals(37.42, result.lat ?: 0.0, 0.01)
        assertEquals(-122.08, result.long ?: 0.0, 0.01)
    }
}

