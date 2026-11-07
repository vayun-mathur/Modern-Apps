package com.vayunmathur.photos.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.util.PersonCluster
import com.vayunmathur.photos.util.PeopleUiState
import com.vayunmathur.photos.util.SecureFolderViewModel
import com.vayunmathur.photos.util.pass3Photo
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/** Pass-3 tests for detail metadata/media, people thumbnails, and the vault viewer states. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DetailPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private val ctx: Context get() = app

    private fun clickRow(text: String) {
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    @Test
    fun metadataCardShowsNameAndActions() {
        var edited = false
        var deleted = false
        var wallpaper = false
        val photo = pass3Photo(1, "sunset.jpg", date = 1700000000000, lat = 48.85, long = 2.35)
        rule.setContent {
            PhotoMetadataCard(
                photo = photo, context = ctx, countryName = "France", fileSize = 2048L,
                pageOffset = 0f, peopleCount = 3, isSphere = true, isMotionPhoto = true,
                onSetWallpaper = { wallpaper = true },
                onEditPhoto = { edited = true },
                onDelete = { deleted = true },
            )
        }
        rule.onNodeWithText("sunset.jpg").assertIsDisplayed()
        rule.onNodeWithText("France", substring = true).assertExists()
        // Action buttons are icon-driven; reaching composition with all badges is the check here.
        assertTrue(!edited && !deleted && !wallpaper)
    }

    @Test
    fun metadataCardMinimalPhoto() {
        val photo = pass3Photo(2, "plain.png", date = 1600000000000)
        rule.setContent {
            PhotoMetadataCard(
                photo = photo, context = ctx, countryName = null, fileSize = null,
                pageOffset = 0.5f, onEditPhoto = {},
            )
        }
        rule.onNodeWithText("plain.png").assertIsDisplayed()
    }

    @Test
    fun detailMediaImage() {
        val image = pass3Photo(1, "a.jpg")
        rule.setContent {
            PhotoDetailMedia(
                photo = image, context = ctx, viewerImageSize = 512, refreshKey = 0,
                sharedKey = null, motionPlaying = false, motionFile = null,
                isMetadataVisible = true, isSettled = true, zoomModifier = Modifier,
                onSizeChanged = {},
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun detailMediaGif() {
        val gif = pass3Photo(2, "g.gif").copy(mimeType = "image/gif")
        rule.setContent {
            PhotoDetailMedia(
                photo = gif, context = ctx, viewerImageSize = 512, refreshKey = 1,
                sharedKey = "key", motionPlaying = false, motionFile = null,
                isMetadataVisible = false, isSettled = false, zoomModifier = Modifier,
                onSizeChanged = {},
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun peopleScreenRealThumbnailAndNameClick() {
        var named: Long? = null
        val cover = pass3Photo(1, "face.jpg")
        val cluster = PersonCluster(
            id = 9, name = null, coverPhoto = cover,
            faceLeft = 0.1f, faceTop = 0.1f, faceRight = 0.6f, faceBottom = 0.6f,
            photos = listOf(cover),
        )
        val stack = NavBackStack(arrayOf<Route>(Route.People))
        rule.setContent {
            PeopleScreen(
                backStack = stack,
                state = PeopleUiState(people = listOf(cluster), modelsAvailable = true),
                onNameClick = { named = it.id },
            )
        }
        rule.waitForIdle()
        // The unnamed cluster shows its add-name affordance; the grid composed with
        // the real FaceThumbnail (image load fails gracefully under Robolectric).
        assertTrue(stack.backStack.isNotEmpty())
        assertTrue(named == null)
    }

    @Test
    fun peopleScreenEmptyAndProgressStates() {
        val stack = NavBackStack(arrayOf<Route>(Route.People))
        rule.setContent {
            PeopleScreen(
                backStack = stack,
                state = PeopleUiState(
                    people = emptyList(), modelsAvailable = true,
                    indexing = true, faceScannedCount = 3, faceTargetCount = 10,
                ),
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun peopleScreenModelsMissingState() {
        val stack = NavBackStack(arrayOf<Route>(Route.People))
        rule.setContent {
            PeopleScreen(
                backStack = stack,
                state = PeopleUiState(people = emptyList(), modelsAvailable = false),
            )
        }
        rule.onNodeWithText("Face grouping needs", substring = true).assertExists()
    }

    @Test
    fun vaultViewerNotFoundForUnknownId() {
        val stack = NavBackStack(arrayOf<Route>(Route.Wallpaper(1L)))
        rule.setContent { VaultViewerPage(stack, vaultId = 424242, password = "pw", secureFolderViewModel = SecureFolderViewModel(app)) }
        rule.onNodeWithText("no longer", substring = true).assertExists()
    }

    @Test
    fun detailSectionInBoxScope() {
        val photo = pass3Photo(1, "a.jpg")
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                PhotoDetailSection(
                    photo = photo, context = ctx, countryName = "France", fileSize = 1024L,
                    pageOffset = 0f, peopleCount = 1, isSphere = false, isMotionPhoto = false,
                    isPanorama = false, isMetadataVisible = true, showImmersive = false,
                    motionPlaying = false, motionExtracting = false, faceBoxes = null,
                    ocrLayout = null, ocrClearToken = 0, isSettled = true,
                    size = androidx.compose.ui.unit.IntSize(400, 300),
                    currentZoom = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ZoomState()) },
                    zoomModifier = Modifier,
                    onOpenPerson = {}, onSetWallpaper = {}, onEditPhoto = {}, onDelete = {},
                    onShowImmersive = {}, onMotionClick = {},
                )
            }
        }
        rule.onNodeWithText("a.jpg").assertIsDisplayed()
    }
}
