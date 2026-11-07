package com.vayunmathur.photos.util

import android.app.Application
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.data.VaultPhotoDao
import com.vayunmathur.photos.data.VideoData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Slice 1/3 second pass: SecureFolderViewModel paths the first pass missed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1VaultVm2Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application

    private class FakeDao(rows: List<VaultPhoto> = emptyList()) : VaultPhotoDao {
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

    private fun vaultPhoto(id: Long = 1L) = VaultPhoto(
        id = id, name = "p$id.jpg", path = "/vault/p$id.enc",
        thumbnailPath = "/vault/p${id}_thumb.enc", date = 1000L,
        width = 10, height = 10, dateModified = 1000L,
    )

    private fun photo(name: String) = Photo(
        name = name, uri = "content://media/$name", date = 1000L, width = 10, height = 10,
        dateModified = 1000L, exifSet = false, lat = null, long = null,
        videoData = VideoData(500L), panoData = null, isTrashed = false,
    )

    @Test
    fun factoryCreatesViewModel() {
        val factory = SecureFolderViewModelFactory(app)
        val vm = factory.create(SecureFolderViewModel::class.java, CreationExtras.Empty)
        assertEquals(emptyList(), vm.photos.value)
        assertNull(vm.vaultPhotoDao.value)
        assertFalse(vm.relockOnExit.value)
    }

    @Test
    fun unlockWhenAlreadyUnlockedShortCircuits() {
        val vm = SecureFolderViewModel(app)
        val dao = FakeDao()
        vm.setVault(dao, "pw")
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).get()
        var successDao: VaultPhotoDao? = null
        var successPassword: String? = null
        var failed = false
        vm.unlock(
            activity,
            onSuccess = { d, p -> successDao = d; successPassword = p },
            onFailure = { failed = true },
        )
        assertTrue(successDao === dao)
        assertEquals("pw", successPassword)
        assertFalse(failed)
    }

    @Test
    fun photosFlowFollowsVaultDao() = runBlocking<Unit> {
        val vm = SecureFolderViewModel(app)
        vm.setVault(FakeDao(listOf(vaultPhoto(1), vaultPhoto(2))), "pw")
        // stateIn WhileSubscribed: collect via first with subscription.
        val seen = mutableListOf<List<VaultPhoto>>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            vm.photos.collect { seen += it }
        }
        // viewModelScope rides the (paused) Robolectric main looper: pump it.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && seen.none { it.size == 2 }) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        job.cancel()
        assertTrue(seen.any { it.map { p -> p.id } == listOf(1L, 2L) }, seen.toString())
        vm.lock()
        assertNull(vm.vaultPhotoDao.value)
    }

    @Test
    fun moveAndRestoreWithoutVaultOrItemsAreNoOps() {
        val vm = SecureFolderViewModel(app)
        var called = false
        // No vault set: both operations return before launching work.
        vm.moveToSecure(listOf(photo("a.jpg")), PhotosRepository.get(context)) { called = true }
        vm.restorePhotos(listOf(vaultPhoto()))
        assertFalse(called)
        // Vault set but empty input: still no-ops.
        vm.setVault(FakeDao(), "pw")
        vm.moveToSecure(emptyList(), PhotosRepository.get(context)) { called = true }
        vm.restorePhotos(emptyList())
        assertFalse(called)
    }

    @Test
    fun requestThumbnailForMissingFileStaysEmpty() {
        val vm = SecureFolderViewModel(app)
        val state = vm.thumbnailState("/vault/does-not-exist_thumb.enc")
        vm.requestThumbnail("/vault/does-not-exist_thumb.enc", "pw")
        assertNull(state.value)
        // Same path returns the same holder afterwards.
        assertTrue(state === vm.thumbnailState("/vault/does-not-exist_thumb.enc"))
    }

    @Test
    fun relockKeyConstantIsStable() {
        assertEquals("secure_folder_relock_on_exit", SecureFolderViewModel.RELOCK_ON_EXIT_KEY)
        val vm = SecureFolderViewModel(app)
        vm.setRelockOnExit(true)
        assertTrue(vm.relockOnExit.value)
    }
}
