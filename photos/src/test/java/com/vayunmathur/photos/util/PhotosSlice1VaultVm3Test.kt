package com.vayunmathur.photos.util

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.data.VaultPhotoDao
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Slice 1/3 second pass: SecureFolderViewModel restore/move coroutine bodies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DenyingApp::class)
class PhotosSlice1VaultVm3Test {

    private val app = ApplicationProvider.getApplicationContext<DenyingApp>()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val password = "correct horse battery staple"

    private class FakeDao(rows: List<VaultPhoto> = emptyList()) : VaultPhotoDao {
        val store = rows.toMutableList()
        override fun getAllFlow(): Flow<List<VaultPhoto>> = flowOf(store.toList())
        override fun getByIdFlow(id: Long): Flow<VaultPhoto?> = flowOf(store.firstOrNull { it.id == id })
        override suspend fun upsert(value: VaultPhoto): Long {
            store.removeIf { it.id == value.id }
            store += value
            return value.id
        }
        override suspend fun delete(value: VaultPhoto): Int =
            if (store.removeIf { it.id == value.id }) 1 else 0
    }

    private class FakePhotos(context: android.content.Context) : PhotosRepository(context) {
        val deleted = mutableListOf<Photo>()
        override suspend fun delete(value: Photo): Int {
            deleted += value
            return 1
        }
    }

    private fun pumpUntil(desc: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out: $desc")
    }

    private fun encryptChunked(plain: ByteArray): ByteArray {
        val key = SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8)), "AES",
        )
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x53, 0x45, 0x43, 0x32))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val enc = cipher.doFinal(plain)
        out.write(cipher.iv)
        out.write((enc.size ushr 24) and 0xFF)
        out.write((enc.size ushr 16) and 0xFF)
        out.write((enc.size ushr 8) and 0xFF)
        out.write(enc.size and 0xFF)
        out.write(enc)
        return out.toByteArray()
    }

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Test
    fun restorePhotosDeletesRestoredRowsAndClearsSelection() {
        val dir = File(context.filesDir, "secure_vault").also { it.mkdirs() }
        val enc = File(dir, "vm-restore.enc").also { it.writeBytes(encryptChunked(jpegBytes())) }
        val photo = VaultPhoto(
            id = 9L, name = "vm-restore.jpg", path = enc.absolutePath,
            thumbnailPath = enc.absolutePath + "_thumb", date = 1L,
            width = 10, height = 10, dateModified = 1L,
        )
        val dao = FakeDao(listOf(photo))
        val vm = SecureFolderViewModel(app)
        vm.setVault(dao, password)
        vm.addSelection(9L)
        vm.restorePhotos(listOf(photo))
        pumpUntil("row deleted") { dao.store.isEmpty() }
        assertTrue(vm.selectedIds.value.isEmpty())
        assertTrue(!enc.exists())
    }

    @Test
    fun restorePhotosWithoutPasswordKeepsRows() {
        val dir = File(context.filesDir, "secure_vault").also { it.mkdirs() }
        val enc = File(dir, "vm-keep.enc").also { it.writeBytes(encryptChunked(jpegBytes())) }
        val photo = VaultPhoto(
            id = 10L, name = "vm-keep.jpg", path = enc.absolutePath,
            thumbnailPath = enc.absolutePath + "_thumb", date = 1L,
            width = 10, height = 10, dateModified = 1L,
        )
        val dao = FakeDao(listOf(photo))
        val vm = SecureFolderViewModel(app)
        // DAO set but password cleared via lock-then-set pattern: setVault then
        // lock drops both; instead set dao with password, restore with a photo
        // whose file is missing - restore fails per-photo and row stays.
        vm.setVault(dao, password)
        val missing = photo.copy(id = 11L, path = "/vault/nope.enc")
        dao.store += missing
        vm.restorePhotos(listOf(missing))
        pumpUntil("selection cleared after pass") { vm.selectedIds.value.isEmpty() }
        assertEquals(listOf(10L, 11L), dao.store.map { it.id })
        assertTrue(enc.exists())
    }

    @Test
    fun moveToSecureFailureLeavesDaoUntouchedAndSkipsCallback() {
        val vm = SecureFolderViewModel(app)
        val dao = FakeDao()
        vm.setVault(dao, password)
        val source = Photo(
            name = "gone.jpg", uri = "content://media/external/images/media/424242",
            date = 1L, width = 10, height = 10, dateModified = 1L,
            exifSet = false, lat = null, long = null, videoData = null,
            panoData = null, isTrashed = false,
        )
        var called = false
        vm.moveToSecure(listOf(source), FakePhotos(context)) { called = true }
        // Give the IO coroutine a chance to run; the per-photo failure is
        // swallowed, nothing is upserted, and the success callback never fires.
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(dao.store.isEmpty())
        assertEquals(false, called)
    }

    @Test
    fun moveToSecureWithStreamDeniedSkipsPhotoWithoutCrashingOrOrphans() {
        // Simulate an expired read grant: the thumbnail still loads, but
        // opening the full stream throws SecurityException — the raw exception
        // a device delivers when a cloud document's grant is revoked after the
        // gallery indexed it. (Mockito's inline maker stubs the final
        // openInputStream; subclassing cannot — every stream API on
        // ContentResolver is final in the compile SDK.)
        val resolver = mock(ContentResolver::class.java)
        `when`(resolver.loadThumbnail(any(), any(), any()))
            .thenReturn(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        `when`(resolver.openInputStream(any()))
            .thenThrow(SecurityException("denied-open"))
        app.resolverOverride = resolver
        try {
            val vaultDir = File(context.filesDir, "secure_vault")
            val before = vaultDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            val vm = SecureFolderViewModel(app)
            val dao = FakeDao()
            vm.setVault(dao, password)
            val source = Photo(
                name = "grant-expired.jpg", uri = "content://denied/photo1",
                date = 1L, width = 10, height = 10, dateModified = 1L,
                exifSet = false, lat = null, long = null, videoData = null,
                panoData = null, isTrashed = false,
            )
            var called = false
            // An exception escaping viewModelScope lands on the thread's uncaught
            // handler (on a device: an app crash). Capture it to prove the scope
            // contains the denial instead of leaking it.
            val crashed = mutableListOf<Throwable>()
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                synchronized(crashed) { crashed += e }
            }
            try {
                vm.moveToSecure(listOf(source), FakePhotos(context)) { called = true }
                // Give the IO coroutine a chance to run. The per-photo denial must
                // be swallowed like any other per-photo failure: nothing is
                // upserted, the success callback never fires, no exception escapes
                // the scope, and no partial vault file is left behind.
                val deadline = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < deadline) {
                    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                }
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(prev)
            }
            assertTrue(crashed.isEmpty(), "exception escaped moveToSecure scope: $crashed")
            assertTrue(dao.store.isEmpty())
            assertFalse(called)
            val after = vaultDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
            assertEquals(before, after, "orphaned partial vault files")
        } finally {
            app.resolverOverride = null
        }
    }
}

/**
 * Application whose ContentResolver can be swapped for a stub. Properly
 * attached by Robolectric (unlike a manually-registered provider), and a
 * pass-through when [resolverOverride] is unset, so existing tests are
 * unaffected.
 */
class DenyingApp : Application() {
    var resolverOverride: ContentResolver? = null
    override fun getContentResolver(): ContentResolver =
        resolverOverride ?: super.getContentResolver()
}
