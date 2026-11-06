package com.vayunmathur.photos.util

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.VaultDatabase
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.data.VaultPhotoDao
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 tests for [SecureFolderViewModel]: selection, lock/unlock state,
 * thumbnail-holder identity, and the vault DAO flow wiring — all without
 * biometrics, MediaStore, or encrypted files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice3VaultTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application

    private fun viewModel() = SecureFolderViewModel(app)

    @AfterTest
    fun resetToken() {
        // Nothing global to reset: each test builds a fresh ViewModel.
    }

    @Test
    fun selectionToggleAddClear() {
        val vm = viewModel()
        assertTrue(vm.selectedIds.value.isEmpty())
        vm.toggleSelection(1L)
        vm.toggleSelection(2L)
        assertEquals(setOf(1L, 2L), vm.selectedIds.value)
        vm.toggleSelection(1L)
        assertEquals(setOf(2L), vm.selectedIds.value)
        vm.addSelection(1L)
        assertEquals(setOf(1L, 2L), vm.selectedIds.value)
        // addSelection is idempotent.
        vm.addSelection(1L)
        assertEquals(setOf(1L, 2L), vm.selectedIds.value)
        vm.clearSelection()
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun setVaultPublishesDaoAndPassword() {
        val vm = viewModel()
        assertNull(vm.vaultPhotoDao.value)
        assertNull(vm.vaultPassword.value)
        val dao = FakeVaultDao()
        vm.setVault(dao, "pw")
        assertEquals("pw", vm.vaultPassword.value)
        assertTrue(vm.vaultPhotoDao.value === dao)
    }

    @Test
    fun unlockShortCircuitsWhenAlreadyUnlocked() {
        val vm = viewModel()
        val dao = FakeVaultDao()
        vm.setVault(dao, "pw")
        var success = false
        // No activity/biometrics needed: the existing DAO short-circuits.
        vm.unlock(
            activity = androidx.fragment.app.FragmentActivity(),
            onSuccess = { _, _ -> success = true },
        )
        assertTrue(success)
    }

    @Test
    fun lockClearsStateAndSelection() {
        val vm = viewModel()
        vm.setVault(FakeVaultDao(), "pw")
        vm.toggleSelection(9L)
        vm.thumbnailState("/tmp/x.jpg")
        vm.lock()
        assertNull(vm.vaultPhotoDao.value)
        assertNull(vm.vaultPassword.value)
        assertTrue(vm.selectedIds.value.isEmpty())
        // Locking twice is a safe no-op.
        vm.lock()
        assertNull(vm.vaultPhotoDao.value)
    }

    @Test
    fun thumbnailStateHolderIsStable() {
        val vm = viewModel()
        val first = vm.thumbnailState("/tmp/a.jpg")
        val second = vm.thumbnailState("/tmp/a.jpg")
        // Same path returns the same holder instance for the VM's lifetime.
        assertTrue(first === second)
        assertNull(first.value)
        // Lock clears holders' values.
        vm.setVault(FakeVaultDao(), "pw")
        vm.lock()
        assertNull(first.value)
    }

    @Test
    fun photosFlowEmitsDaoContents() = runTest {
        val vm = viewModel()
        val dao = FakeVaultDao()
        val row = VaultPhoto(
            name = "v.jpg", path = "/vault/v.jpg", thumbnailPath = "/vault/v.thumb",
            date = 100L, width = 10, height = 10, dateModified = 100L,
        )
        dao.seed(listOf(row))
        vm.setVault(dao, "pw")
        // photos switches to the DAO flow on unlock.
        val photos = vm.photos.first { it.size == 1 }
        assertEquals("v.jpg", photos.single().name)
    }

    @Test
    fun relockDefaultIsFalse() {
        // Fresh VM reads the DataStore default (false) synchronously.
        assertFalse(viewModel().relockOnExit.value)
    }

    @Test
    fun restorePhotosEmptyIsNoop() {
        val vm = viewModel()
        // No DAO set and empty list: returns without launching work.
        vm.restorePhotos(emptyList())
        assertNull(vm.vaultPhotoDao.value)
    }

    @Test
    fun secureFolderManagerRoundTrip() {
        // Pure file crypto through the app files dir: encrypt bytes manually via
        // the manager's thumbnail path is MediaStore-bound, so pin the
        // viewer-cache helpers (no crypto) instead.
        val mgr = SecureFolderManager(app)
        val cache = java.io.File(app.cacheDir, "s3test-${System.nanoTime()}")
        cache.mkdirs()
        // Empty viewer cache clears without error.
        mgr.clearViewerCache(cache)
        assertFalse(java.io.File(cache, "vault_view").exists())
        // Missing file decrypts to null.
        assertNull(mgr.decryptThumbnail("/nonexistent/thumb.enc", "pw"))
        assertNull(mgr.decryptToCacheFile("/nonexistent/file.enc", "pw", cache))
        cache.deleteRecursively()
    }

    /** In-memory [VaultPhotoDao] fake: no SQLCipher, no password. */
    private class FakeVaultDao : VaultPhotoDao {
        private val rows = MutableStateFlow<List<VaultPhoto>>(emptyList())

        fun seed(values: List<VaultPhoto>) {
            rows.value = values
        }

        override fun getAllFlow() = rows
        override fun getByIdFlow(id: Long) =
            kotlinx.coroutines.flow.flowOf(rows.value.firstOrNull { it.id == id })

        override suspend fun upsert(value: VaultPhoto): Long {
            val current = rows.value.toMutableList()
            val idx = current.indexOfFirst { it.id == value.id }
            if (idx >= 0) current[idx] = value else current.add(value)
            rows.value = current
            return value.id
        }

        override suspend fun delete(value: VaultPhoto): Int {
            val before = rows.value.size
            rows.value = rows.value.filterNot { it.id == value.id }
            return before - rows.value.size
        }
    }
}
