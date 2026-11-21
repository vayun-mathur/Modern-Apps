package com.vayunmathur.camera.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.library.ui.IconCamera
import com.vayunmathur.library.ui.IconFlipCamera
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPhotoLibrary
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.animatedFloat

@Composable
internal fun ShutterRow(
    cameraMode: CameraMode,
    isRecording: Boolean,
    isCapturing: Boolean,
    recordingPaused: Boolean,
    videoSnapshotSupported: Boolean,
    galleryBitmap: android.graphics.Bitmap?,
    burstEnabled: Boolean,
    onBurstStart: () -> Unit,
    onBurstStop: () -> Unit,
    onCapture: () -> Unit,
    onPauseResume: () -> Unit,
    onSnapshot: () -> Unit,
    onFlipCamera: () -> Unit,
    onGallery: () -> Unit,
    iconRotation: Float,
    flipEnabled: Boolean = true
) {
    val shutterScale = animatedFloat(
        target = if (isCapturing) 0.8f else 1f,
        spec = spring(dampingRatio = 0.4f, stiffness = 800f)
    )
    val recordingCornerRadius = animatedFloat(
        target = if (isRecording) 8f else 38f,
        spec = spring(dampingRatio = 0.6f, stiffness = 500f)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left slot: pause/resume while recording, otherwise the gallery thumbnail.
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFF3C3C3C), CircleShape)
                    .clickable(onClick = onPauseResume),
                contentAlignment = Alignment.Center
            ) {
                if (recordingPaused) IconPlay(Modifier.size(24.dp).rotate(iconRotation), Color.White)
                else IconPause(Modifier.size(24.dp).rotate(iconRotation), Color.White)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF3C3C3C))
                    .clickable(onClick = onGallery),
                contentAlignment = Alignment.Center
            ) {
                if (galleryBitmap != null) {
                    Image(
                        bitmap = galleryBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.open_gallery),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    IconPhotoLibrary(Modifier.size(24.dp).rotate(iconRotation), Color.White)
                }
            }
        }

        val animatedSize = animatedFloat(
            target = if (isRecording) 36f else 66f,
            spec = spring(dampingRatio = 0.6f, stiffness = 500f)
        )
        Box(
            modifier = Modifier
                .size(76.dp)
                .border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = shutterScale
                        scaleY = shutterScale
                    }
                    .size(animatedSize.dp)
                    .clip(RoundedCornerShape(recordingCornerRadius.dp))
                    .background(
                        if (cameraMode in listOf(CameraMode.VIDEO, CameraMode.SLOW_MO, CameraMode.TIMELAPSE, CameraMode.CINEMATIC)) Color.Red
                        else Color.White
                    )
                    .then(
                        if (burstEnabled) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onCapture() },
                                    onLongPress = { onBurstStart() },
                                    onPress = {
                                        tryAwaitRelease()
                                        // No-op if a burst was never started (short tap).
                                        onBurstStop()
                                    }
                                )
                            }
                        } else {
                            Modifier.clickable(onClick = onCapture)
                        }
                    )
            )
        }

        // Right slot: video snapshot while recording (if supported), otherwise flip camera.
        // No flip in Slo-Mo — back camera only.
        if (isRecording) {
            if (videoSnapshotSupported) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White, CircleShape)
                        .clickable(onClick = onSnapshot),
                    contentAlignment = Alignment.Center
                ) {
                    IconCamera(Modifier.size(24.dp).rotate(iconRotation), Color.Black)
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        } else {
            if (flipEnabled) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFF3C3C3C), CircleShape)
                        .clickable(onClick = onFlipCamera),
                    contentAlignment = Alignment.Center
                ) {
                        IconFlipCamera(Modifier.size(24.dp).rotate(iconRotation), Color.White)
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}



