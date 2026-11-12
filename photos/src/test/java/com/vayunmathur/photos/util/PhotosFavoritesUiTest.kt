package com.vayunmathur.photos.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.ui.AlbumsScreen
import com.vayunmathur.photos.ui.FavoritesScreen
import com.vayunmathur.photos.ui.GalleryScreen
import com.vayunmathur.photos.ui.PhotoMetadataCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #912 Compose tests: the viewer's heart, the grid's multi-select heart and
 * the Favorites tile and page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosFavoritesUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private var nextId = 1L

    private fun photo(name: String, date: Long, favorite: Boolean = false, trashed: Boolean = false) = Photo(
        id = nextId++,
        name = name,
        uri = "content://media/external/images/media/$name",
        date = date,
        width = 100,
        height = 100,
        dateModified = date,
        exifSet = false,
        lat = null,
        long = null,
        videoData = null,
        panoData = null,
        isTrashed = trashed,
        isFavorite = favorite,
    )

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun stubThumbnail(): @Composable (Photo, Modifier) -> Unit =
        { photo, modifier -> Box(modifier.background(Color.Gray).testTag("thumb-" + photo.id)) }

    // ---- Viewer heart ----

    @Test
    fun viewerHeartShowsStateAndToggles() {
        val target = photo("p", 100L)
        val toggled = mutableListOf<Long>()
        var favorite by mutableStateOf(false)
        composeRule.setContent {
            PhotoMetadataCard(
                photo = target,
                context = context,
                countryName = null,
                fileSize = null,
                pageOffset = 0f,
                onEditPhoto = {},
                isFavorite = favorite,
                onToggleFavorite = { toggled += it.id },
            )
        }

        composeRule.onNodeWithContentDescription(str(R.string.action_favorite)).performClick()
        assertEquals(listOf(target.id), toggled)

        favorite = true
        composeRule.onNodeWithContentDescription(str(R.string.action_unfavorite)).assertIsDisplayed()
    }

    // ---- Grid multi-select heart ----

    @Test
    fun gallerySelectionHeartFavoritesAMixedSelection() {
        val fav = photo("fav", 100L, favorite = true)
        val plain = photo("plain", 200L)
        var toggles = 0
        val actions = object : GalleryActions {
            override fun toggleFavoriteSelection() {
                toggles++
            }
        }
        val columns = mutableFloatStateOf(3f)
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columns) {
                GalleryScreen(
                    backStack = NavBackStack(arrayOf<Route>(Route.Gallery)),
                    state = GalleryUiState(photos = listOf(fav, plain), selectedIds = setOf(fav.id, plain.id)),
                    actions = actions,
                    thumbnail = stubThumbnail(),
                )
            }
        }

        // Mixed: the outlined heart, i.e. "Favorite" (favourite them all).
        composeRule.onNodeWithContentDescription(str(R.string.action_favorite)).performClick()
        assertEquals(1, toggles)
    }

    // ---- Albums tile ----

    @Test
    fun favoritesTileShowsCountAndCoverAndOpensFavorites() {
        val cover = photo("cover", 100L, favorite = true)
        val stack = NavBackStack<Route>(arrayOf(Route.Albums))
        composeRule.setContent {
            AlbumsScreen(
                backStack = stack,
                state = AlbumsUiState(
                    defaultAlbums = listOf(
                        DefaultAlbum(DefaultAlbumKind.FAVORITES, count = 4, cover = cover),
                        DefaultAlbum(DefaultAlbumKind.TRASH, count = 1),
                    ),
                ),
                thumbnail = stubThumbnail(),
            )
        }

        composeRule.onNodeWithText(str(R.string.label_favorites)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.album_photo_count, 4, 4),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag("thumb-" + cover.id, useUnmergedTree = true).performClick()
        assertEquals(Route.Favorites, stack.backStack.last())
    }

    @Test
    fun emptyFavoritesTileShowsTheLabelWithoutACover() {
        composeRule.setContent {
            AlbumsScreen(
                backStack = NavBackStack(arrayOf<Route>(Route.Albums)),
                state = AlbumsUiState(defaultAlbums = listOf(DefaultAlbum(DefaultAlbumKind.FAVORITES, count = 0))),
                thumbnail = stubThumbnail(),
            )
        }
        composeRule.onNodeWithText(str(R.string.label_favorites)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.album_photo_count, 0, 0),
        ).assertIsDisplayed()
    }

    // ---- Favorites page ----

    private fun setFavorites(state: GalleryUiState, actions: GalleryActions, stack: NavBackStack<Route>) {
        val columns = mutableFloatStateOf(3f)
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columns) {
                FavoritesScreen(backStack = stack, state = state, actions = actions, thumbnail = stubThumbnail())
            }
        }
    }

    @Test
    fun favoritesPageEmptyState() {
        setFavorites(GalleryUiState(), GalleryActions.Noop, NavBackStack(arrayOf<Route>(Route.Favorites)))
        composeRule.onNodeWithText(str(R.string.favorites_empty)).assertIsDisplayed()
    }

    @Test
    fun favoritesPageOpensTheViewerOverTheFavorites() {
        val a = photo("a", 200L, favorite = true)
        val b = photo("b", 100L, favorite = true)
        val stack = NavBackStack<Route>(arrayOf(Route.Favorites))
        setFavorites(GalleryUiState(photos = listOf(a, b)), GalleryActions.Noop, stack)

        composeRule.onNodeWithTag("thumb-" + b.id, useUnmergedTree = true).performClick()

        val last = stack.backStack.last()
        assertTrue(last is Route.PhotoPage)
        last as Route.PhotoPage
        assertEquals(b.id, last.id)
        assertEquals(listOf(a.id, b.id), last.overridePhotosList?.map { it.id })
    }

    @Test
    fun favoritesPageSelectionUnfavorites() {
        val a = photo("a", 200L, favorite = true)
        var toggles = 0
        val actions = object : GalleryActions {
            override fun toggleFavoriteSelection() {
                toggles++
            }
        }
        setFavorites(
            GalleryUiState(photos = listOf(a), selectedIds = setOf(a.id)),
            actions,
            NavBackStack(arrayOf<Route>(Route.Favorites)),
        )

        // Every selected item is a favourite: the filled heart removes them.
        composeRule.onNodeWithContentDescription(str(R.string.action_unfavorite)).performClick()
        assertEquals(1, toggles)
    }
}
