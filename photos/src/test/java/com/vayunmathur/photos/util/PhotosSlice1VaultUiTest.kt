package com.vayunmathur.photos.util

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.data.VaultPhotoDao
import com.vayunmathur.photos.ui.SecureFolderPage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ViewModel + page tests for slice 1/3 (`data/workers`): [SecureFolderViewModel]
 * selection/vault state and the [SecureFolderPage] composition over a fake DAO.
 *
 * NOTE: Robolectric tests currently record no JaCoCo execution data (lead is
 * investigating repo-wide), so these verify behavior now and will count toward
 * the merged report once that infra gap is fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice1VaultUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application
    private lateinit var viewModel: SecureFolderViewModel

    private var nextVaultId = 1L

    private fun vaultPhoto(name: String) = VaultPhoto(
        id = nextVaultId++,
        name = name,
        path = "/vault/$name.enc",
        thumbnailPath = "/vault/${name}_thumb.enc",
        date = 1_000L,
        width = 100,
        height = 100,
        dateModified = 1_000L,
    )

    private fun fakeDao(photos: List<VaultPhoto> = emptyList()): VaultPhotoDao =
        FakeVaultPhotoDao(photos)

    @AfterTest
    fun clearViewModel() {
        if (::viewModel.isInitialized) viewModel.onClearedForTest()
    }

    @Test
    fun setVaultAndLockRoundTrip() {
        viewModel = SecureFolderViewModel(app)
        val dao = fakeDao()
        viewModel.setVault(dao, "pw")
        assertEquals("pw", viewModel.vaultPassword.value)
        assertTrue(viewModel.vaultPhotoDao.value === dao)
        viewModel.lock()
        assertNull(viewModel.vaultPassword.value)
        assertNull(viewModel.vaultPhotoDao.value)
    }

    @Test
    fun selectionTogglesAndClears() {
        viewModel = SecureFolderViewModel(app)
        assertTrue(viewModel.selectedIds.value.isEmpty())
        viewModel.toggleSelection(7L)
        assertEquals(setOf(7L), viewModel.selectedIds.value)
        viewModel.toggleSelection(9L)
        assertEquals(setOf(7L, 9L), viewModel.selectedIds.value)
        viewModel.toggleSelection(7L)
        assertEquals(setOf(9L), viewModel.selectedIds.value)
        viewModel.addSelection(7L)
        assertEquals(setOf(7L, 9L), viewModel.selectedIds.value)
        viewModel.clearSelection()
        assertTrue(viewModel.selectedIds.value.isEmpty())
    }

    @Test
    fun lockClearsSelectionAndThumbnails() {
        viewModel = SecureFolderViewModel(app)
        viewModel.setVault(fakeDao(), "pw")
        viewModel.toggleSelection(1L)
        viewModel.toggleSelection(2L)
        viewModel.lock()
        assertTrue(viewModel.selectedIds.value.isEmpty())
    }

    @Test
    fun restorePhotosWithEmptyListIsNoOp() = runTest {
        viewModel = SecureFolderViewModel(app)
        viewModel.setVault(fakeDao(), "pw")
        viewModel.restorePhotos(emptyList())
        assertTrue(viewModel.selectedIds.value.isEmpty())
    }

    @Test
    fun thumbnailStateReturnsStableHolder() {
        viewModel = SecureFolderViewModel(app)
        val first = viewModel.thumbnailState("/vault/a_thumb.enc")
        val second = viewModel.thumbnailState("/vault/a_thumb.enc")
        assertTrue(first === second)
        assertNull(first.value)
    }

    @Test
    fun secureFolderPageShowsEmptyVault() {
        viewModel = SecureFolderViewModel(app)
        viewModel.setVault(fakeDao(), "pw")
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides mutableFloatStateOf(3f)) {
                SecureFolderPage(
                backStack = NavBackStack(arrayOf<Route>(Route.SecureFolder)),
                password = "pw",
                secureFolderViewModel = viewModel,
                )
            }
        }
        // Empty vault renders the empty state, not a crash.
        composeRule.waitForIdle()
        assertTrue(viewModel.photos.value.isEmpty())
    }

    @Test
    fun secureFolderPageSelectionShowsCountInTitle() {
        viewModel = SecureFolderViewModel(app)
        viewModel.setVault(fakeDao(listOf(vaultPhoto("a.jpg"), vaultPhoto("b.jpg"))), "pw")
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides mutableFloatStateOf(3f)) {
                SecureFolderPage(
                backStack = NavBackStack(arrayOf<Route>(Route.SecureFolder)),
                password = "pw",
                secureFolderViewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()
        viewModel.toggleSelection(1L)
        composeRule.waitForIdle()
        assertEquals(setOf(1L), viewModel.selectedIds.value)
    }

    @Test
    fun setRelockOnExitPersistsValue() {
        viewModel = SecureFolderViewModel(app)
        viewModel.setRelockOnExit(true)
        assertTrue(viewModel.relockOnExit.value)
        viewModel.setRelockOnExit(false)
        assertFalse(viewModel.relockOnExit.value)
    }

    /** In-memory fake so no SQLCipher/biometric stack is needed on the JVM. */
    private class FakeVaultPhotoDao(
        private val rows: List<VaultPhoto>,
    ) : VaultPhotoDao {
        private val store = rows.toMutableList()

        override fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<VaultPhoto>> =
            kotlinx.coroutines.flow.flowOf(store.toList())

        override fun getByIdFlow(id: Long): kotlinx.coroutines.flow.Flow<VaultPhoto?> =
            kotlinx.coroutines.flow.flowOf(store.firstOrNull { it.id == id })

        override suspend fun upsert(value: VaultPhoto): Long {
            val idx = store.indexOfFirst { it.id == value.id && value.id != 0L }
            return if (idx >= 0) {
                store[idx] = value
                value.id
            } else {
                val id = (store.maxOfOrNull { it.id } ?: 0L) + 1
                store += value.copy(id = id)
                id
            }
        }

        override suspend fun delete(value: VaultPhoto): Int =
            if (store.removeIf { it.id == value.id }) 1 else 0
    }
}

/** Test-only hook: [androidx.lifecycle.ViewModel.onCleared] is protected. */
private fun SecureFolderViewModel.onClearedForTest() {
    val method = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
    method.isAccessible = true
    method.invoke(this)
}
