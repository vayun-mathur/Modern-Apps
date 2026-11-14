package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ZoomState
import com.vayunmathur.library.ui.zoomGestures
import java.io.File

/**
 * Full-screen black viewer for one decrypted vault item, mirroring PhotoPage's
 * immersive pattern. Stateless: the caller supplies the decrypted [file] (or
 * null while decryption is still running).
 *
 * Vault rows carry no mime column and GIFs are indistinguishable from stills at
 * rest, so every non-video item renders through [AsyncImage] regardless of
 * format; videos (videoDuration != null) play via [VideoPlayer].
 */
@Composable
fun VaultViewerScreen(
    fileName: String,
    file: File?,
    isVideo: Boolean,
    onBack: () -> Unit,
) {
    var isChromeVisible by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(ZoomState()) }
    // Live reader, not a snapshot: gesture handlers read the latest zoom without
    // recomposing this screen on every pinch frame.
    val zoomState = rememberUpdatedState(zoom)
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // RAW SCAFFOLD EXCEPTION: bar-less full-screen black media viewer (no top/bottom
    // bar); chrome is a floating overlay inside the viewer. AppScaffold always renders a
    // top app bar, which would break the immersive viewer.
    Scaffold(containerColor = Color.Black) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
                .onGloballyPositioned { containerSize = it.size }
                .then(
                    zoomGestures(
                        zoom = zoomState,
                        containerSize = { containerSize },
                        onZoomChange = { zoom = it },
                        onTap = { isChromeVisible = !isChromeVisible },
                    )
                )
        ) {
            if (file != null) {
                val zoomModifier = Modifier.graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offset.x
                    translationY = zoom.offset.y
                }
                if (isVideo) {
                    VideoPlayer(
                        modifier = Modifier.fillMaxSize().then(zoomModifier),
                        uri = file.toUri(),
                        isMetadataVisible = isChromeVisible,
                        isSettledPage = true,
                    )
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder().data(file).build(),
                        contentDescription = fileName,
                        modifier = Modifier.fillMaxSize().then(zoomModifier),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            FadeVisibility(
                visible = isChromeVisible,
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    IconButton(onClick = onBack) {
                        IconBack(tint = Color.White)
                    }
                    Text(
                        text = fileName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}
