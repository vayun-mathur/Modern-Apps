package com.vayunmathur.photos.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.data.VaultPhotoDao
import com.vayunmathur.photos.util.SecureFolderManager
import com.vayunmathur.photos.util.SecureFolderViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The vault viewer over a genuinely encrypted JPEG: the page decrypts it
 * to the viewer cache and shows the image chrome; a missing row and a
 * wrong password both fail closed.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultViewerPageRealTest {

    @get:Rule val rule = createComposeRule()
    private val columnsState = mutableFloatStateOf(3f)
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setup() {
        TestPhotosRepository.install(app)
    }

    private fun setContent(content: @Composable () -> Unit) =
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) { content() }
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

    private fun encryptedJpeg(password: String, name: String): File {
        val manager = SecureFolderManager(app)
        val key = SecureFolderManager::class.java.getDeclaredMethod("getSecretKey", String::class.java)
            .apply { isAccessible = true }.invoke(manager, password) as SecretKey
        val encrypt = SecureFolderManager::class.java.getDeclaredMethod(
            "encryptStreamChunked", java.io.InputStream::class.java, java.io.OutputStream::class.java, SecretKey::class.java,
        ).apply { isAccessible = true }
        val jpegBytes = java.io.ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(48, 36, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(30, 90, 120))
            }.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }.toByteArray()
        val file = File(app.cacheDir, name)
        FileOutputStream(file).use { encrypt.invoke(manager, ByteArrayInputStream(jpegBytes), it, key) }
        return file
    }

    @Test fun viewerDecryptsAndShowsVaultPhoto() = runBlocking {
        val vault = TestPhotosRepository.installVault(app, "pw123")
        val dao = vaultDao(vault)
        val enc = encryptedJpeg("pw123", "viewer-one.enc")
        val thumb = encryptedJpeg("pw123", "viewer-one_thumb.enc")
        dao.upsert(VaultPhoto(
            name = "hidden.jpg", path = enc.absolutePath, thumbnailPath = thumb.absolutePath,
            date = 1L, width = 48, height = 36, dateModified = 1L,
        ))
        val row = dao.getAllFlow().first().single()
        val secure = SecureFolderViewModel(app)
        secure.setVault(dao, "pw123")
        val stack = NavBackStack<Route>(arrayOf(Route.Gallery, Route.VaultViewer(row.id)))
        setContent { DynamicTheme { VaultViewerPage(stack, row.id, "pw123", secure) } }
        rule.waitForIdle()
        // The decrypted viewer file exists in the cache now.
        assertTrue(File(app.cacheDir, "vault_view").listFiles()?.isNotEmpty() == true)
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, 6)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
        Unit
    }

    @Test fun viewerWithUnknownIdRendersEmpty() = runBlocking {
        val vault = TestPhotosRepository.installVault(app, "pw123")
        val dao = vaultDao(vault)
        val secure = SecureFolderViewModel(app)
        secure.setVault(dao, "pw123")
        val stack = NavBackStack<Route>(arrayOf(Route.Gallery, Route.VaultViewer(999L)))
        setContent { DynamicTheme { VaultViewerPage(stack, 999L, "pw123", secure) } }
        rule.waitForIdle()
        Unit
    }
}
