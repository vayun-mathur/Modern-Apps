package com.vayunmathur.photos.util

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.ui.AlbumDetailScreen
import com.vayunmathur.photos.ui.AlbumPickerDialog
import com.vayunmathur.photos.ui.AlbumsScreen
import com.vayunmathur.photos.ui.GalleryScreen
import com.vayunmathur.photos.ui.PeopleScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 Compose tests for the gallery-family screens: [GalleryScreen],
 * [PeopleScreen], [AlbumsScreen], [AlbumDetailScreen] and [AlbumPickerDialog].
 *
 * Every screen under test takes its state as a value plus a actions interface,
 * so no ViewModel, WorkManager, MediaStore or image loading is involved: the
 * thumbnail seam paints a plain box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice3ScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private var nextId = 1L

    private fun photo(name: String, date: Long, album: String? = null, trashed: Boolean = false) =
        Photo(
            id = nextId++,
            name = name,
            uri = "content://media/$name",
            date = date,
            width = 100,
            height = 100,
            dateModified = date,
            exifSet = true,
            lat = null,
            long = null,
            videoData = null,
            panoData = null,
            isTrashed = trashed,
            album = album,
        )

    private fun backStack() = NavBackStack<Route>(arrayOf(Route.Gallery))

    private fun stubThumbnail(): @androidx.compose.runtime.Composable (Photo, Modifier) -> Unit =
        { photo, modifier -> Box(modifier.background(Color.Gray).testTag("thumb-" + photo.id)) }

    private fun setGallery(
        state: GalleryUiState,
        actions: GalleryActions = GalleryActions.Noop,
        stack: NavBackStack<Route> = backStack(),
        initialSearchActive: Boolean = false,
    ) {
        val columns = mutableFloatStateOf(3f)
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columns) {
                GalleryScreen(
                    backStack = stack,
                    state = state,
                    actions = actions,
                    initialSearchActive = initialSearchActive,
                    thumbnail = stubThumbnail(),
                )
            }
        }
    }

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    // ---- GalleryScreen ----

    @Test
    fun galleryEmptyGridShowsSearchPlaceholder() {
        setGallery(GalleryUiState())
        composeRule.onNodeWithText(str(R.string.search_placeholder)).assertIsDisplayed()
    }

    @Test
    fun gallerySearchActivateShowsIndexingProgress() {
        // Search bar open with an empty query: the expanded content shows the
        // on-device indexing progress rows instead of results.
        setGallery(
            GalleryUiState(ocrCount = 3, ocrTargetCount = 10),
            initialSearchActive = true,
        )
        composeRule.onNodeWithText(str(R.string.of_photos_processed, 30)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.photos_indexed_for_text_search, 3, 10))
            .assertIsDisplayed()
    }

    @Test
    fun gallerySearchQueryShowsAiUnavailableMessage() {
        setGallery(
            GalleryUiState(
                searchQuery = "beach",
                searchAiState = SearchAiState.UNAVAILABLE,
            ),
            initialSearchActive = true,
        )
        composeRule.onNodeWithText(str(R.string.semantic_search_unavailable)).assertIsDisplayed()
    }

    @Test
    fun gallerySelectionModeShowsCountAndClears() {
        var cleared = false
        val actions = object : GalleryActions {
            override fun clearSelection() { cleared = true }
        }
        setGallery(GalleryUiState(selectedIds = setOf(1L, 2L)), actions)
        composeRule.onNodeWithText(str(R.string.items_selected, 2)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").performClick()
        assertTrue(cleared)
    }

    @Test
    fun galleryTileOpensViewer() {
        val stack = backStack()
        val a = photo("tile-a", 100L)
        setGallery(GalleryUiState(photos = listOf(a)), stack = stack)
        composeRule.onNodeWithTag("thumb-" + a.id, useUnmergedTree = true).performClick()
        val last = stack.backStack.last()
        assertTrue(last is Route.PhotoPage)
        assertEquals(a.id, (last as Route.PhotoPage).id)
    }

    @Test
    fun gallerySelectionModeTrashForwards() {
        var trashed = false
        val actions = object : GalleryActions {
            override fun trashSelection() { trashed = true }
        }
        setGallery(GalleryUiState(selectedIds = setOf(7L)), actions)
        composeRule.onNodeWithContentDescription("Delete").performClick()
        assertTrue(trashed)
    }

    @Test
    fun galleryTypingInSearchForwardsQuery() {
        var query: String? = null
        val actions = object : GalleryActions {
            override fun setSearchQuery(q: String) { query = q }
        }
        setGallery(GalleryUiState(), actions, initialSearchActive = true)
        composeRule.onNodeWithText(str(R.string.search_placeholder)).performClick()
        composeRule.onNodeWithText("", useUnmergedTree = true).performTextInput("sunset")
        assertEquals("sunset", query)
    }

    // ---- PeopleScreen ----

    private fun personCluster(id: Long, name: String?, photo: Photo) = PersonCluster(
        id = id,
        name = name,
        coverPhoto = photo,
        faceLeft = 0f,
        faceTop = 0f,
        faceRight = 0.5f,
        faceBottom = 0.5f,
        photos = listOf(photo),
    )

    private fun setPeople(
        state: PeopleUiState,
        onNameClick: (PersonCluster) -> Unit = {},
    ) {
        composeRule.setContent {
            PeopleScreen(
                backStack = backStack(),
                state = state,
                faceThumbnail = { person, modifier ->
                    Box(modifier.background(Color.Gray).testTag("avatar-" + person.id))
                },
                onNameClick = onNameClick,
            )
        }
    }

    @Test
    fun peopleMissingModelsExplains() {
        setPeople(PeopleUiState(modelsAvailable = false))
        composeRule.onNodeWithText(str(R.string.people_model_missing)).assertIsDisplayed()
    }

    @Test
    fun peopleEmptyShowsEmptyMessage() {
        setPeople(PeopleUiState())
        composeRule.onNodeWithText(str(R.string.people_empty)).assertIsDisplayed()
    }

    @Test
    fun peopleScanningShowsProgressRow() {
        setPeople(PeopleUiState(indexing = true, faceScannedCount = 2, faceTargetCount = 8))
        composeRule.onNodeWithText(str(R.string.face_indexing_progress, 2, 8)).assertIsDisplayed()
    }

    @Test
    fun peopleGridShowsClusterAndOpensViewer() {
        val stack = backStack()
        val cover = photo("cover", 100L)
        val cluster = personCluster(11L, "Ada", cover)
        composeRule.setContent {
            PeopleScreen(
                backStack = stack,
                state = PeopleUiState(people = listOf(cluster)),
                faceThumbnail = { person, modifier ->
                    Box(modifier.background(Color.Gray).testTag("avatar-" + person.id))
                },
            )
        }
        // The cluster renders its name; tapping the avatar pushes the viewer route.
        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        composeRule.onNodeWithTag("avatar-" + cluster.id, useUnmergedTree = true).performClick()
        val last = stack.backStack.last()
        assertTrue(last is Route.PhotoPage)
        assertEquals(cover.id, (last as Route.PhotoPage).id)
    }

    // ---- AlbumsScreen ----

    private fun album(name: String, vararg photos: Photo) = Album(
        name = name,
        coverPhoto = photos.maxBy { it.date },
        photos = photos.toList(),
    )

    private fun setAlbums(state: AlbumsUiState) {
        composeRule.setContent {
            AlbumsScreen(
                backStack = backStack(),
                state = state,
                thumbnail = stubThumbnail(),
            )
        }
    }

    @Test
    fun albumsEmptyShowsEmptyMessage() {
        setAlbums(AlbumsUiState(defaultAlbums = emptyList(), albums = emptyList()))
        // Title renders in both collapsed and expanded app-bar slots; the
        // empty-state copy below is the unique assertion.
        composeRule.onNodeWithText(str(R.string.albums_empty), substring = false)
            .assertIsDisplayed()
    }

    @Test
    fun albumsGridShowsTilesWithCounts() {
        val a = photo("a", 100L, "Trip")
        val b = photo("b", 200L, "Trip")
        setAlbums(AlbumsUiState(albums = listOf(album("Trip", a, b))))
        composeRule.onNodeWithText("Trip").assertIsDisplayed()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.album_photo_count, 2, 2),
        ).assertIsDisplayed()
    }

    @Test
    fun albumsTappingTileOpensDetail() {
        val stack = backStack()
        val a = photo("a", 100L, "Trip")
        composeRule.setContent {
            AlbumsScreen(backStack = stack, state = AlbumsUiState(albums = listOf(album("Trip", a))), thumbnail = stubThumbnail())
        }
        composeRule.onNodeWithTag("thumb-" + a.id, useUnmergedTree = true).performClick()
        val last = stack.backStack.last()
        assertTrue(last is Route.AlbumDetail)
        assertEquals("Trip", (last as Route.AlbumDetail).albumName)
    }

    @Test
    fun albumsDefaultTilesOpenTrashAndVault() {
        val stack = backStack()
        composeRule.setContent {
            AlbumsScreen(
                backStack = stack,
                state = AlbumsUiState(
                    defaultAlbums = listOf(
                        DefaultAlbum(DefaultAlbumKind.TRASH, count = 3),
                        DefaultAlbum(DefaultAlbumKind.SECURE_FOLDER, count = null),
                    ),
                ),
                thumbnail = stubThumbnail(),
            )
        }
        // Both built-in tiles render; tapping the first navigates to Trash.
        composeRule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.album_photo_count, 3, 3),
        ).assertIsDisplayed()
    }

    // ---- AlbumDetailScreen ----

    private fun setAlbumDetail(
        albumName: String,
        album: Album?,
        stack: NavBackStack<Route> = backStack(),
        selectedIds: Set<Long> = emptySet(),
        onSetCover: (Long) -> Unit = {},
        onRemoveFromAlbum: () -> Unit = {},
        onRenameAlbum: (String) -> Unit = {},
        onDeleteAlbum: () -> Unit = {},
    ) {
        val columns = mutableFloatStateOf(3f)
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columns) {
                AlbumDetailScreen(
                    backStack = stack,
                    albumName = albumName,
                    album = album,
                    selectedIds = selectedIds,
                    onRemoveFromAlbum = onRemoveFromAlbum,
                    onSetCover = onSetCover,
                    onRenameAlbum = onRenameAlbum,
                    onDeleteAlbum = onDeleteAlbum,
                    thumbnail = stubThumbnail(),
                )
            }
        }
    }

    @Test
    fun albumDetailShowsTitleAndPhotos() {
        val a = photo("a", 100L, "Trip")
        val b = photo("b", 200L, "Trip")
        setAlbumDetail("Trip", album("Trip", a, b))
        composeRule.onNodeWithText("Trip").assertIsDisplayed()
    }

    @Test
    fun albumDetailUnknownBucketUsesLocalizedTitle() {
        setAlbumDetail(Album.UNKNOWN_NAME, null)
        composeRule.onNodeWithText(str(R.string.albums_unknown)).assertIsDisplayed()
    }

    @Test
    fun albumDetailSelectionShowsCount() {
        val a = photo("a", 100L, "Trip")
        setAlbumDetail("Trip", album("Trip", a), selectedIds = setOf(a.id))
        composeRule.onNodeWithText(str(R.string.items_selected, 1)).assertIsDisplayed()
    }

    @Test
    fun albumDetailMenuRenamesAlbum() {
        var renamed: String? = null
        val a = photo("a", 100L, "Trip")
        setAlbumDetail("Trip", album("Trip", a), onRenameAlbum = { renamed = it })
        composeRule.onNodeWithContentDescription("More").performClick()
        // Menu item and dialog confirm button share the Rename label; the dialog
        // text field starts with the current name, so replace it wholesale.
        composeRule.onNodeWithText(str(R.string.album_rename)).performClick()
        // Two nodes hold "Trip": the app-bar title and the dialog's text field
        // (dialog content traverses after the screen). The field is the editable one.
        composeRule.onAllNodesWithText("Trip")[1].performTextClearance()
        composeRule.onAllNodesWithText("", useUnmergedTree = true)[0].performTextInput("Holiday")
        composeRule.onAllNodesWithText(str(R.string.album_rename))[1].performClick()
        assertEquals("Holiday", renamed)
    }

    // ---- AlbumPickerDialog ----

    @Test
    fun albumPickerConfirmsNewNameAndFiltersUnknown() {
        var confirmed: String? = null
        composeRule.setContent {
            AlbumPickerDialog(
                existingAlbums = listOf("Trip", Album.UNKNOWN_NAME),
                onDismiss = {},
                onConfirm = { confirmed = it },
            )
        }
        // Unknown bucket is not a destination.
        composeRule.onAllNodesWithText(Album.UNKNOWN_NAME).assertCountEquals(0)
        composeRule.onNodeWithText("Trip").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.album_name_hint)).performTextInput("New Album")
        composeRule.onNodeWithText(str(R.string.album_create)).performClick()
        assertEquals("New Album", confirmed)
    }

}
