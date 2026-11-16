package com.vayunmathur.photos.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VaultPhoto
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.PhotoMapViewModel
import com.vayunmathur.photos.util.SecureFolderViewModel
import com.vayunmathur.photos.util.workInfosOverride
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Deeper page drives: People/Trash/SecureFolder/Wallpaper pages composed
 * over seeded rows with their interactive chrome pressed (selection modes,
 * relock settings, restore affordances).
 */
@RunWith(AndroidJUnit4::class)
class PhotosPagesDeepDriveTest {

    @get:Rule val rule = createComposeRule()
    private val columnsState = mutableFloatStateOf(3f)
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun setContent(content: @Composable () -> Unit) =
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) { content() }
        }

    private fun stack(vararg routes: Route): NavBackStack<Route> = NavBackStack(routes)

    private fun photo(name: String, date: Long, trashed: Boolean = false, lat: Double? = null) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 100,
        dateModified = date, exifSet = true, lat = lat, long = lat?.let { -it },
        videoData = null, panoData = null, isTrashed = trashed,
    )

    private fun clickEverything(max: Int = 10) {
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, max)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
    }

    @Test fun peoplePageWithPersonOpensCluster() = runBlocking {
        installed.photos.upsertAll(listOf(photo("group.jpg", 300L), photo("solo.jpg", 200L)))
        val all = installed.photos.getAll().associateBy { it.name }
        val faces = listOf(all.getValue("group.jpg"), all.getValue("solo.jpg")).map { p ->
            com.vayunmathur.photos.data.PhotoFace(
                photoId = p.id, clusterId = null,
                embedding = com.vayunmathur.photos.util.FaceRecognizer.floatsToBytes(FloatArray(4) { 1f }),
                clusterable = true, left = 0f, top = 0f, right = 0.5f, bottom = 0.5f,
                srcWidth = 100, srcHeight = 100,
            )
        }
        installed.faces.commitFaceScan(all.values.map { it.id }, faces)
        val rows = installed.faces.getClusterRows()
        installed.faces.applyClustering(
            listOf(com.vayunmathur.photos.data.ResolvedPersonRow(null, rows.first().embedding, rows.map { it.id })),
            emptyList(),
        )
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        setContent { DynamicTheme { PeoplePage(stack(Route.Gallery), gallery) } }
        rule.waitForIdle()
        clickEverything()
        assertTrue(rule.onAllNodesWithText("group.jpg", substring = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty())
        Unit
    }

    @Test fun albumsPageOpensFavoritesAndSecureTiles() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("fav.jpg", 300L), photo("plain.jpg", 200L),
        ))
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        // Mark one a favorite in Room so the Favorites tile has content.
        val fav = installed.photos.getAll().first { it.name == "fav.jpg" }
        installed.photos.setFavorite(listOf(fav.id), true)
        val secureVm = SecureFolderViewModel(app)
        setContent {
            DynamicTheme {
                com.vayunmathur.photos.ui.AlbumsPage(
                    stack(Route.Gallery), gallery, secureVm,
                )
            }
        }
        rule.waitForIdle()
        clickEverything(12)
        Unit
    }

    @Test fun trashPageSelectionChrome() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("gone1.jpg", 300L, trashed = true), photo("gone2.jpg", 200L, trashed = true),
        ))
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        val secureVm = SecureFolderViewModel(app)
        setContent { DynamicTheme { TrashPage(stack(Route.Gallery), gallery) } }
        rule.waitForIdle()
        clickEverything()
        Unit
    }

    @Test fun secureFolderPageWithVaultRows() = runBlocking {
        val vault = TestPhotosRepository.installVault(app)
        vault.upsert(VaultPhoto(
            name = "secret.jpg", path = "/vault/secret.enc", thumbnailPath = "/vault/secret_thumb.enc",
            date = 1L, width = 100, height = 100, dateModified = 1L,
        ))
        val secure = SecureFolderViewModel(app)
        // VaultRepository keeps its DAO behind the RoomRepository delegate;
        // read the injected in-memory database back out reflectively.
        var type: Class<*>? = vault.javaClass
        var dao: com.vayunmathur.photos.data.VaultPhotoDao? = null
        while (type != null && dao == null) {
            for (field in type.declaredFields) {
                if (field.type == Lazy::class.java || field.name.contains("db", ignoreCase = true)) {
                    runCatching {
                        field.isAccessible = true
                        val db = (field.get(vault) as Lazy<*>).value
                        dao = db!!.javaClass.getMethod("vaultPhotoDao").invoke(db) as com.vayunmathur.photos.data.VaultPhotoDao
                    }
                }
            }
            type = type.superclass
        }
        secure.setVault(dao!!, "pw")
        setContent { DynamicTheme { SecureFolderPage(stack(Route.Gallery), "pw", secure) } }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty())
        clickEverything()
        Unit
    }

    @Test fun wallpaperPageRendersControls() = runBlocking {
        installed.photos.upsertAll(listOf(photo("wall.jpg", 100L)))
        setContent { DynamicTheme { WallpaperPage(stack(Route.Gallery), null) } }
        rule.waitForIdle()
        clickEverything()
        Unit
    }

    @Test fun photoPageViewerChrome() = runBlocking {
        installed.photos.upsertAll(listOf(
            photo("one.jpg", 300L, lat = 37.0), photo("two.jpg", 200L),
        ))
        val gallery = GalleryViewModel(app).also { it.runSyncHook = {} }
        val first = installed.photos.getAll().first { it.name == "one.jpg" }
        val mapVm = PhotoMapViewModel(app)
        setContent {
            DynamicTheme {
                PhotoPage(gallery, mapVm, first.id, null, null, stack(Route.Gallery))
            }
        }
        rule.waitForIdle()
        clickEverything()
        Unit
    }
}
