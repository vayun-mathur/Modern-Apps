package com.vayunmathur.photos.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.core.net.toUri
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AnimatedImage
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.photos.data.Photo
import java.io.File

/**
 * Still / motion / video content of one viewer page.
 *
 * The still branch, the motion-photo playback branch and the plain video branch
 * used to live inline in [PhotoDetailView]; they are one decisions block, so
 * they move together. Zoom/pan arrives as [modifier] so a pinch updates the
 * GPU transform without recomposing this subtree.
 */
@Composable
fun PhotoDetailMedia(
    photo: Photo,
    context: Context,
    viewerImageSize: Int,
    refreshKey: Int,
    sharedKey: Any?,
    motionPlaying: Boolean,
    motionFile: File?,
    isMetadataVisible: Boolean,
    isSettled: Boolean,
    modifier: Modifier,
    onSizeChanged: (IntSize) -> Unit,
) {
    if (photo.videoData == null && !(motionPlaying && motionFile != null)) {
        val imageModifier =
            Modifier.fillMaxSize()
                .onGloballyPositioned { layoutCoordinates ->
                    onSizeChanged(layoutCoordinates.size)
                }
                .then(modifier)
                .then(
                    if (sharedKey == null) Modifier
                    else Modifier.sharedContainer("photo-image-$sharedKey")
                )
        if (photo.isGif) {
            AnimatedImage(
                uri = photo.uri.toUri(),
                contentDescription = null,
                modifier = imageModifier
            )
        } else {
            AsyncImage(
                model =
                    ImageRequest.Builder(context)
                        .data(photo.uri.toUri())
                        // refreshKey stays out of the disk key: it bumps on
                        // every ON_RESUME, so including it there made a
                        // return from the editor or a share sheet re-fetch
                        // and re-write the file. The memory key still
                        // carries it, which is the point — the pixels may
                        // have been edited.
                        .diskCacheKey("thumb_${photo.id}_${photo.dateModified}")
                        .memoryCacheKey("thumb_${photo.id}_${photo.dateModified}_$refreshKey")
                        // Without a size this decodes at full resolution: a
                        // 12 MP photo is ~48 MB as ARGB_8888, enough for one
                        // opened photo to evict every grid thumbnail from a
                        // memory cache measured in tens of MB.
                        .size(viewerImageSize)
                        .build(),
                contentDescription = null,
                modifier = imageModifier,
                contentScale = ContentScale.Fit
            )
        }
    } else if (motionPlaying && motionFile != null) {
        // Motion-photo playback. The seek bar is the frame inspector:
        // scrubbing pauses polling and seeks on release, like videos.
        // Autoplay is gated on the settled page, mirroring plain videos.
        VideoPlayer(
            modifier =
                Modifier.fillMaxSize()
                    .onGloballyPositioned { onSizeChanged(it.size) }
                    .then(modifier),
            uri = Uri.fromFile(motionFile),
            isMetadataVisible = isMetadataVisible,
            isSettledPage = isSettled && motionPlaying
        )
    } else {
        VideoPlayer(
            modifier =
                Modifier.fillMaxSize()
                    .onGloballyPositioned { onSizeChanged(it.size) }
                    .then(modifier),
            uri = photo.uri.toUri(),
            isMetadataVisible = isMetadataVisible,
            isSettledPage = isSettled
        )
    }
}
