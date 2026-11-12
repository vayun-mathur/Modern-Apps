package com.vayunmathur.photos.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.OcrLayout
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.PhotoFaceBoxes

@Composable
fun BoxScope.PhotoDetailSection(
    photo: Photo,
    context: Context,
    countryName: String?,
    fileSize: Long?,
    pageOffset: Float,
    peopleCount: Int,
    isSphere: Boolean,
    isMotionPhoto: Boolean,
    isPanorama: Boolean,
    isMetadataVisible: Boolean,
    showImmersive: Boolean,
    motionPlaying: Boolean,
    motionExtracting: Boolean,
    faceBoxes: PhotoFaceBoxes?,
    ocrLayout: OcrLayout?,
    ocrClearToken: Int,
    isSettled: Boolean,
    size: IntSize,
    currentZoom: State<ZoomState>,
    zoomModifier: Modifier,
    onOpenPerson: (Long) -> Unit,
    onSetWallpaper: (Photo) -> Unit,
    onEditPhoto: () -> Unit,
    onDelete: (Photo) -> Unit,
    onShowImmersive: (Boolean) -> Unit,
    onMotionClick: () -> Unit,
    /**
     * Reports the laid-out size of the metadata card so the viewer can keep
     * taps on it from toggling the chrome. Zero while the card is hidden, so
     * taps then toggle (restore) as usual.
     */
    onMetadataLayout: (IntSize) -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: (Photo) -> Unit = {},
) {
    ocrLayout?.takeIf { isSettled && size != IntSize.Zero }?.let { layout ->
        key(ocrClearToken) {
            OcrTextLayer(
                layout = layout,
                containerSize = size,
                showOutlines = isMetadataVisible,
                zoom = { currentZoom.value.scale },
                modifier = Modifier.fillMaxSize().then(zoomModifier)
            )
        }
    }

    faceBoxes?.takeIf { isSettled && size != IntSize.Zero }?.let { boxes ->
        FadeVisibility(
            visible = isMetadataVisible,
            modifier = Modifier.fillMaxSize().then(zoomModifier)
        ) {
            FaceBoxLayer(
                boxes = boxes,
                containerSize = size,
                zoom = { currentZoom.value.scale },
                onFaceClick = onOpenPerson,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    FadeVisibility(
        visible = isMetadataVisible,
        modifier = Modifier.align(Alignment.BottomStart)
            .onGloballyPositioned { onMetadataLayout(it.size) }
    ) {
        PhotoMetadataCard(
            photo = photo,
            context = context,
            countryName = countryName,
            fileSize = fileSize,
            pageOffset = pageOffset,
            peopleCount = peopleCount,
            isSphere = isSphere,
            isMotionPhoto = isMotionPhoto,
            onSetWallpaper = onSetWallpaper,
            onEditPhoto = onEditPhoto,
            onDelete = onDelete,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
        )
    }

    if (isPanorama || isMotionPhoto) {
        FadeVisibility(
            visible = isMetadataVisible,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isPanorama) {
                    FilledTonalButton(onClick = { onShowImmersive(true) }) {
                        Text(stringResource(if (isSphere) R.string.view_360 else R.string.view_panorama))
                    }
                }
                if (isMotionPhoto) {
                    FilledTonalButton(
                        onClick = onMotionClick,
                        enabled = !motionExtracting
                    ) {
                        Text(
                            stringResource(
                                if (motionPlaying) R.string.stop_motion_photo
                                else R.string.play_motion_photo
                            )
                        )
                    }
                }
            }
        }
    }

    if (showImmersive && photo.panoData != null) {
        Dialog(
            onDismissRequest = { onShowImmersive(false) },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (isSphere) {
                    PanoramaSphereView(photo = photo, modifier = Modifier.fillMaxSize())
                } else {
                    PanoramaFlatView(photo = photo, modifier = Modifier.fillMaxSize())
                }
                FilledTonalButton(
                    onClick = { onShowImmersive(false) },
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                ) {
                    Text(stringResource(UiR.string.close))
                }
            }
        }
    }
}
