package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.util.Album
import com.vayunmathur.photos.util.AlbumsUiState
import com.vayunmathur.photos.util.DefaultAlbum
import com.vayunmathur.photos.util.DefaultAlbumKind
import com.vayunmathur.photos.util.GalleryActions
import com.vayunmathur.photos.util.GalleryUiState
import com.vayunmathur.photos.util.PeopleUiState
import com.vayunmathur.photos.util.PersonCluster

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop window for the wide viewer+filmstrip layout. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=420"

/**
 * Store listing images for `:photos`, rendered from Compose previews instead of from an
 * instrumented test on a device.
 *
 * `./gradlew :photos:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/photos/`, where `release.sh` picks them up.
 *
 * Things to keep in mind when editing:
 *
 *  - Order matters, and it comes from the function names. The generated PNG filenames
 *    embed the function name, so `Preview1Gallery`/`Preview2Selection`/... sort into
 *    listing order. Renumber the functions if you reorder the listing.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in
 *    Studio but is not collected as a screenshot test, and the build fails with the
 *    unhelpful "did not discover any tests".
 *  - The previews must be members of a class, not top-level functions, or the screenshot
 *    engine (which discovers them as JUnit tests) silently skips them.
 *
 * This app is the awkward one for preview rendering: every screen it would want to show
 * off is a picture. Layoutlib has no MediaStore, cannot decode a content:// URI, and must
 * not run the ncnn face detector — so the tiles here are flat gradients passed in through
 * the `thumbnail`/`faceThumbnail` seams. That is not a regression: the previous on-device
 * generator also seeded synthetic gradient JPEGs rather than shipping real photographs.
 * The map screen is gone from the listing entirely; it is a MapLibre GL surface and there
 * is nothing for Layoutlib to draw.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-gallery", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Gallery() {
        GalleryPreview(GalleryUiState(photos = samplePhotos()))
    }

    @PreviewTest
    @Preview(name = "2-selection", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Selection() {
        GalleryPreview(
            GalleryUiState(
                photos = samplePhotos(),
                selectedIds = setOf(2L, 3L, 7L, 10L),
            )
        )
    }

    @PreviewTest
    @Preview(name = "3-people", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3People() {
        DynamicTheme(darkTheme = true) {
            PeopleScreen(
                backStack = NavBackStack(arrayOf<Route>(Route.People)),
                state = PeopleUiState(
                    people = samplePeople(),
                    faceScannedCount = 812,
                    faceTargetCount = 940,
                    indexing = true,
                ),
                faceThumbnail = { person, modifier -> PlaceholderFace(person, modifier) },
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview4Expanded() {
        DynamicTheme(darkTheme = true) {
            PhotoViewerWideLayout(
                viewer = { GalleryPreview(GalleryUiState(photos = samplePhotos())) },
                filmstrip = { GalleryPreview(GalleryUiState(photos = samplePhotos().take(4))) },
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-albums", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview5Albums() {
        val photos = samplePhotos()
        DynamicTheme(darkTheme = true) {
            AlbumsScreen(
                backStack = NavBackStack(arrayOf<Route>(Route.Albums)),
                state = AlbumsUiState(
                    albums = listOf(
                        Album("Camera", photos[0], photos.take(14)),
                        Album("Screenshots", photos[14], photos.drop(14)),
                        Album("Trip", photos[4], photos.slice(4..9)),
                        Album("WhatsApp Images", photos[18], photos.takeLast(3)),
                    ),
                    defaultAlbums = listOf(
                        // The Favorites tile takes its newest favorite as the cover.
                        DefaultAlbum(DefaultAlbumKind.FAVORITES, count = 6, cover = photos[2]),
                        DefaultAlbum(DefaultAlbumKind.TRASH, count = 3),
                        DefaultAlbum(DefaultAlbumKind.SECURE_FOLDER, count = null),
                    ),
                ),
                thumbnail = { photo, modifier -> PlaceholderTile(photo, modifier) },
            )
        }
    }
}

/**
 * The gallery grid under the app's real theme. `LocalColumnCount` is normally provided by
 * MainActivity from DataStore; three columns is its default.
 */
@Composable
private fun GalleryPreview(state: GalleryUiState) {
    DynamicTheme(darkTheme = true) {
        CompositionLocalProvider(LocalColumnCount provides remember { mutableFloatStateOf(3f) }) {
            GalleryScreen(
                backStack = NavBackStack(arrayOf<Route>(Route.Gallery)),
                state = state,
                actions = GalleryActions.Noop,
                thumbnail = { photo, modifier -> PlaceholderTile(photo, modifier) },
            )
        }
    }
}

/** Stand-in for a decoded thumbnail, picked from a fixed palette so runs are identical. */
@Composable
private fun PlaceholderTile(photo: Photo, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(gradientFor(photo.id))
    )
}

/** Stand-in for a cropped face. The caller's modifier already clips it to a circle. */
@Composable
private fun PlaceholderFace(person: PersonCluster, modifier: Modifier) {
    Box(modifier.background(gradientFor(person.coverPhoto.id)))
}

private val TILE_COLORS = listOf(
    Color(0xFFFF8A65) to Color(0xFFFFD180),
    Color(0xFF81D4FA) to Color(0xFFB39DDB),
    Color(0xFFA5D6A7) to Color(0xFFFFF59D),
    Color(0xFFF48FB1) to Color(0xFFCE93D8),
    Color(0xFF80DEEA) to Color(0xFF80CBC4),
    Color(0xFFFFAB91) to Color(0xFFBCAAA4),
    Color(0xFF9FA8DA) to Color(0xFF90CAF9),
    Color(0xFFFFCC80) to Color(0xFFFF8A65),
    Color(0xFFB2EBF2) to Color(0xFFAED581),
    Color(0xFFF06292) to Color(0xFF9575CD),
)

private fun gradientFor(id: Long): Brush {
    val (start, end) = TILE_COLORS[(id % TILE_COLORS.size).toInt()]
    return Brush.linearGradient(listOf(start, end))
}

// Noon UTC so the month a photo lands in doesn't depend on the render timezone.
private const val JULY_NOON = 1_752_580_800_000L // 2025-07-15T12:00Z
private const val JUNE_NOON = 1_750_420_800_000L // 2025-06-20T12:00Z
private const val DAY = 86_400_000L

private val JULY_NAMES = listOf(
    "IMG_20250715_121804.jpg", "IMG_20250714_093217.jpg", "IMG_20250713_174501.jpg",
    "IMG_20250712_201133.jpg", "VID_20250711_150244.mp4", "IMG_20250710_080356.jpg",
    "IMG_20250709_163019.jpg", "IMG_20250708_112247.jpg", "IMG_20250707_195802.jpg",
    "IMG_20250706_140135.jpg", "IMG_20250705_074528.jpg", "IMG_20250704_223011.jpg",
)

private val JUNE_NAMES = listOf(
    "IMG_20250620_101542.jpg", "IMG_20250619_184006.jpg", "VID_20250618_132719.mp4",
    "IMG_20250617_065833.jpg", "IMG_20250616_210427.jpg", "IMG_20250615_123350.jpg",
    "IMG_20250614_171905.jpg", "IMG_20250613_090214.jpg", "IMG_20250612_154641.jpg",
)

/** Two months of library, which is what makes the month headers show up in the grid. */
private fun samplePhotos(): List<Photo> =
    JULY_NAMES.mapIndexed { i, name -> samplePhoto(i + 1L, name, JULY_NOON - i * DAY) } +
        JUNE_NAMES.mapIndexed { i, name ->
            samplePhoto(JULY_NAMES.size + i + 1L, name, JUNE_NOON - i * DAY)
        }

private fun samplePhoto(id: Long, name: String, date: Long): Photo = Photo(
    id = id,
    name = name,
    uri = "content://media/external/images/media/$id",
    date = date,
    width = 4032,
    height = 3024,
    dateModified = date / 1000,
    exifSet = true,
    lat = 37.7749,
    long = -122.4194,
    videoData = if (name.startsWith("VID")) VideoData(duration = 14_000L) else null,
    panoData = null,
)

/**
 * Face clusters, ordered largest first the way the ViewModel emits them. The first
 * few are named and the rest are not, so the grid's name label renders in both
 * states.
 */
private fun samplePeople(): List<PersonCluster> {
    val photos = samplePhotos()
    val names = listOf("Ana Ruiz", "Priya Nair", "Tom Baker", null, null, null)
    return listOf(9, 7, 6, 5, 3, 2).mapIndexed { i, count ->
        PersonCluster(
            id = i + 1L,
            name = names[i],
            coverPhoto = photos[i * 3],
            faceLeft = 0.31f,
            faceTop = 0.18f,
            faceRight = 0.69f,
            faceBottom = 0.62f,
            photos = photos.drop(i).take(count),
        )
    }
}
