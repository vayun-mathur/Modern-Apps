package com.vayunmathur.photos.util
import com.vayunmathur.photos.platform.SecureFolderViewModel
import com.vayunmathur.photos.platform.SecureFolderManager

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.data.VaultPhotoDao
import com.vayunmathur.photos.data.awaitPhotosCondition
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SecureFolderViewModel.restorePhotos] end-to-end: a vault file encrypted
 * with the production chunk format is decrypted into a stub MediaStore
 * insert, and its vault row is deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureFolderRestoreRealTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var sink: File

    private inner class MediaProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: arrayOf("_id"))
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri =
            Uri.parse("content://media/external/images/media/777")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 1
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ) = 1
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            sink.parentFile?.mkdirs()
            if (!sink.exists()) sink.createNewFile()
            return ParcelFileDescriptor.open(sink, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
    }

    @BeforeTest
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        sink = File(app.cacheDir, "restored.jpg")
        sink.delete()
        val provider = MediaProvider()
        provider.attachInfo(app, ProviderInfo().apply {
            authority = "media"
            packageName = app.packageName
        })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    private fun vaultDao(vault: Any): VaultPhotoDao {
        var type: Class<*>? = vault.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                if (field.type == Lazy::class.java || field.name.contains("db", ignoreCase = true)) {
                    val dao = runCatching {
                        field.isAccessible = true
                        val db = (field.get(vault) as Lazy<*>).value
                        db!!.javaClass.getMethod("vaultPhotoDao").invoke(db) as VaultPhotoDao
                    }.getOrNull()
                    if (dao != null) return dao
                }
            }
            type = type.superclass
        }
        error("no vault dao")
    }

    @Test fun thumbnailDecryptAndUnlockShortCircuit() = runBlocking {
        val vault = TestPhotosRepository.installVault(app, "pw123")
        val dao = vaultDao(vault)
        val manager = SecureFolderManager(app)
        val key = SecureFolderManager::class.java.getDeclaredMethod("getSecretKey", String::class.java)
            .apply { isAccessible = true }.invoke(manager, "pw123") as SecretKey
        val encrypt = SecureFolderManager::class.java.getDeclaredMethod(
            "encryptStreamChunked", java.io.InputStream::class.java, java.io.OutputStream::class.java, SecretKey::class.java,
        ).apply { isAccessible = true }
        // A real JPEG as the thumbnail plaintext.
        val jpegBytes = java.io.ByteArrayOutputStream().also { out ->
            android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.BLUE)
            }.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }.toByteArray()
        val thumbEnc = File(app.cacheDir, "thumb.enc")
        FileOutputStream(thumbEnc).use { encrypt.invoke(manager, ByteArrayInputStream(jpegBytes), it, key) }

        val secure = SecureFolderViewModel(app)
        secure.setVault(dao, "pw123")
        // Already unlocked: unlock short-circuits to success without biometrics.
        var unlockedWith: String? = null
        val activity = org.robolectric.Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java).setup().get()
        secure.unlock(activity, onSuccess = { _, pw -> unlockedWith = pw }, onFailure = { })
        assertEquals("pw123", unlockedWith)
        // Thumbnail decrypt publishes a bitmap into the per-path state.
        val state = secure.thumbnailState(thumbEnc.absolutePath)
        secure.requestThumbnail(thumbEnc.absolutePath, "pw123")
        assertTrue(awaitPhotosCondition { state.value != null })
        assertEquals(24, state.value!!.width)
        // Cached path: second request returns the same bitmap.
        secure.requestThumbnail(thumbEnc.absolutePath, "pw123")
        secure.setRelockOnExit(false)
        assertEquals(false, secure.relockOnExit.value)
        secure.setRelockOnExit(true)
        assertEquals(true, secure.relockOnExit.value)
        secure.lock()
        assertEquals(null, secure.thumbnailState(thumbEnc.absolutePath).value)
        Unit
    }

    @Test fun restoreDecryptsIntoMediaStoreAndDeletesRow() = runBlocking {
        val vault = TestPhotosRepository.installVault(app, "pw123")
        val dao = vaultDao(vault)
        // Encrypt a payload with the production chunk writer.
        val manager = SecureFolderManager(app)
        val key = SecureFolderManager::class.java.getDeclaredMethod("getSecretKey", String::class.java)
            .apply { isAccessible = true }.invoke(manager, "pw123") as SecretKey
        val encrypt = SecureFolderManager::class.java.getDeclaredMethod(
            "encryptStreamChunked", java.io.InputStream::class.java, java.io.OutputStream::class.java, SecretKey::class.java,
        ).apply { isAccessible = true }
        val plaintext = ByteArray(4096) { (it % 253).toByte() }
        val encFile = File(app.cacheDir, "vault-one.enc")
        FileOutputStream(encFile).use { encrypt.invoke(manager, ByteArrayInputStream(plaintext), it, key) }
        val thumbFile = File(app.cacheDir, "vault-one_thumb.enc")
        FileOutputStream(thumbFile).use { encrypt.invoke(manager, ByteArrayInputStream(byteArrayOf(1, 2)), it, key) }
        dao.upsert(VaultPhoto(
            name = "secret.jpg", path = encFile.absolutePath, thumbnailPath = thumbFile.absolutePath,
            date = 1L, width = 10, height = 10, dateModified = 1L,
        ))
        val row = dao.getAllFlow().first().single()
        val secure = SecureFolderViewModel(app)
        secure.setVault(dao, "pw123")
        secure.toggleSelection(row.id)
        secure.restorePhotos(listOf(row))
        assertTrue(awaitPhotosCondition { dao.getAllFlow().first().isEmpty() })
        assertTrue(sink.exists() && plaintext.contentEquals(sink.readBytes()))
        assertTrue(!encFile.exists())
        // Restoring nothing / while locked is a clean no-op.
        secure.restorePhotos(emptyList())
        secure.lock()
        secure.restorePhotos(listOf(row))
        assertEquals(null, secure.vaultPassword.value)
        Unit
    }
}
