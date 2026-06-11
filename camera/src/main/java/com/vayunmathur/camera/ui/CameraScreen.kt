package com.vayunmathur.camera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import android.util.Patterns
import android.view.MotionEvent
import android.view.OrientationEventListener
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.camera.R
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.util.AspectRatioOption
import com.vayunmathur.camera.util.BokehAnalyzer
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.FlashMode
import com.vayunmathur.camera.util.QrAnalyzer
import com.vayunmathur.camera.util.TimerDuration
import com.vayunmathur.library.util.NavBackStack

private val ZOOM_LEVELS = listOf(".5" to 0.5f, "1x" to 1f, "2" to 2f, "5" to 5f)

private const val BOKEH_SHADER = """
    uniform shader cameraInput;
    uniform shader alphaMask;
    
    half4 main(float2 fragCoord) {
        float maskAlpha = alphaMask.eval(fragCoord).a;
        
        if (maskAlpha > 0.5) {
            return cameraInput.eval(fragCoord);
        }
        
        half4 blur = half4(0.0);
        float samples = 0.0;
        
        for (float x = -16.0; x <= 16.0; x += 2.0) {
            for (float y = -16.0; y <= 16.0; y += 2.0) {
                blur += cameraInput.eval(fragCoord + float2(x, y));
                samples += 1.0;
            }
        }
        
        half4 sharp = cameraInput.eval(fragCoord);
        half4 blurred = blur / samples;
        return mix(blurred, sharp, maskAlpha);
    }
"""

@Composable
fun CameraScreen(backStack: NavBackStack<Route>, viewModel: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraMode by viewModel.cameraMode.collectAsState()
    val lensFacing by viewModel.lensFacing.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val timerCountdown by viewModel.timerCountdown.collectAsState()
    val qrResult by viewModel.qrResult.collectAsState()
    val aspectRatio by viewModel.aspectRatio.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val timerDuration by viewModel.timerDuration.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val lastCaptureUri by viewModel.lastCaptureUri.collectAsState()
    val gridEnabled by viewModel.gridEnabled.collectAsState()
    val exposureComp by viewModel.exposureCompensation.collectAsState()
    val warmth by viewModel.warmth.collectAsState()
    val shadows by viewModel.shadows.collectAsState()

    var maskBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val isPhotoType = cameraMode in listOf(CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.PANORAMA)
    val bokehShader = remember { lazy { RuntimeShader(BOKEH_SHADER) } }

    val panoSweeping by viewModel.panoramaEngine.isSweeping.collectAsState()
    val panoStitching by viewModel.panoramaEngine.isStitching.collectAsState()
    val panoFrameCount by viewModel.panoramaEngine.frameCount.collectAsState()
    val panoAngle by viewModel.panoramaEngine.sweepAngle.collectAsState()

    val animatedZoom by animateFloatAsState(
        targetValue = zoomRatio,
        animationSpec = tween(durationMillis = 300)
    )

    // Orientation tracking
    var deviceRotation by remember { mutableIntStateOf(0) }
    val animatedRotation by animateFloatAsState(
        targetValue = deviceRotation.toFloat(),
        animationSpec = tween(durationMillis = 300)
    )

    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceRotation = when {
                    orientation in 45..134 -> 270
                    orientation in 135..224 -> 180
                    orientation in 225..314 -> 90
                    else -> 0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    val controller = remember { LifecycleCameraController(context) }

    // Gallery thumbnail
    val galleryBitmap = remember(lastCaptureUri) {
        lastCaptureUri?.let { uri ->
            try {
                val size = android.util.Size(96, 96)
                context.contentResolver.loadThumbnail(uri, size, null)
            } catch (_: Exception) { null }
        }
    }

    LaunchedEffect(lensFacing) {
        controller.cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }

    LaunchedEffect(Unit) {
        viewModel.updateLocation()
        controller.isPinchToZoomEnabled = false
    }

    LaunchedEffect(flashMode) {
        controller.imageCaptureFlashMode = viewModel.getImageCaptureFlashMode()
    }

    LaunchedEffect(animatedZoom) {
        val zoomState = controller.zoomState.value
        if (zoomState != null) {
            val clamped = animatedZoom.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
            controller.setZoomRatio(clamped)
        } else {
            controller.setZoomRatio(animatedZoom)
        }
    }

    // Exposure compensation
    LaunchedEffect(exposureComp) {
        val camera = controller.cameraInfo
        if (camera != null) {
            val range = camera.exposureState.exposureCompensationRange
            val index = (exposureComp * range.upper).toInt().coerceIn(range.lower, range.upper)
            controller.cameraControl?.setExposureCompensationIndex(index)
        }
    }

    LaunchedEffect(cameraMode) {
        if (cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT || cameraMode == CameraMode.PANORAMA) {
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            when (cameraMode) {
                CameraMode.PORTRAIT -> {
                    controller.setImageAnalysisAnalyzer(
                        ContextCompat.getMainExecutor(context),
                        BokehAnalyzer(context) { mask -> maskBitmap = mask }
                    )
                }
                CameraMode.PANORAMA -> {
                    maskBitmap = null
                    controller.setImageAnalysisAnalyzer(
                        ContextCompat.getMainExecutor(context)
                    ) @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class) { imageProxy ->
                        viewModel.panoramaEngine.latestFrame = imageProxy.toBitmap()
                        imageProxy.close()
                    }
                }
                else -> {
                    maskBitmap = null
                    controller.setImageAnalysisAnalyzer(
                        ContextCompat.getMainExecutor(context),
                        QrAnalyzer { text -> viewModel.setQrResult(text) }
                    )
                }
            }
        } else {
            maskBitmap = null
            controller.setEnabledUseCases(CameraController.VIDEO_CAPTURE)
            controller.clearImageAnalysisAnalyzer()
            viewModel.setQrResult(null)
        }
    }

    DisposableEffect(Unit) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { }
    }

    val previewAspectRatio = when (aspectRatio) {
        AspectRatioOption.RATIO_16_9 -> 9f / 16f
        AspectRatioOption.RATIO_4_3 -> 3f / 4f
        AspectRatioOption.RATIO_1_1 -> 1f
    }

    Scaffold(
        containerColor = Color.Black
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    flashMode = flashMode,
                    gridEnabled = gridEnabled,
                    onFlashToggle = {
                        val next = when (flashMode) {
                            FlashMode.OFF -> FlashMode.ON
                            FlashMode.ON -> FlashMode.AUTO
                            FlashMode.AUTO -> FlashMode.OFF
                        }
                        viewModel.setFlashMode(next)
                    },
                    onGridToggle = { viewModel.toggleGrid() },
                    iconRotation = animatedRotation
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    @OptIn(ExperimentalComposeUiApi::class)
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                this.controller = controller
                                scaleType = PreviewView.ScaleType.FIT_CENTER
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(previewAspectRatio)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                run {
                                    val hasColorAdj = warmth != 0f || shadows != 0f
                                    val hasBokeh = cameraMode == CameraMode.PORTRAIT && maskBitmap != null
                                    if (hasBokeh || hasColorAdj) {
                                        Modifier.graphicsLayer {
                                            var effect: RenderEffect? = null

                                            if (hasBokeh) {
                                                val mask = maskBitmap!!
                                                val shader = bokehShader.value
                                                val maskShader = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                                                val matrix = Matrix()
                                                matrix.setScale(size.width / mask.width, size.height / mask.height)
                                                maskShader.setLocalMatrix(matrix)
                                                shader.setInputShader("alphaMask", maskShader)
                                                effect = RenderEffect.createRuntimeShaderEffect(shader, "cameraInput")
                                            }

                                            if (hasColorAdj) {
                                                val cm = ColorMatrix()
                                                cm.set(floatArrayOf(
                                                    1f + warmth * 0.15f, 0f, 0f, 0f, shadows * 40f,
                                                    0f, 1f + warmth * 0.05f, 0f, 0f, shadows * 40f,
                                                    0f, 0f, 1f - warmth * 0.15f, 0f, shadows * 40f,
                                                    0f, 0f, 0f, 1f, 0f
                                                ))
                                                val colorEffect = RenderEffect.createColorFilterEffect(
                                                    ColorMatrixColorFilter(cm)
                                                )
                                                effect = if (effect != null) {
                                                    RenderEffect.createChainEffect(colorEffect, effect)
                                                } else {
                                                    colorEffect
                                                }
                                            }

                                            renderEffect = effect?.asComposeRenderEffect()
                                        }
                                    } else {
                                        Modifier.graphicsLayer { renderEffect = null }
                                    }
                                }
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    val newZoom = (viewModel.zoomRatio.value * zoom)
                                    val zoomState = controller.zoomState.value
                                    val clamped = if (zoomState != null) {
                                        newZoom.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                                    } else {
                                        newZoom.coerceIn(0.5f, 10f)
                                    }
                                    viewModel.setZoomRatio(clamped)
                                }
                            }
                            .pointerInteropFilter { event ->
                                if (event.action == MotionEvent.ACTION_UP) {
                                    val factory = SurfaceOrientedMeteringPointFactory(
                                        event.x, event.y
                                    )
                                    val point = factory.createPoint(event.x, event.y)
                                    val action = FocusMeteringAction.Builder(point).build()
                                    controller.cameraControl?.startFocusAndMetering(action)
                                }
                                false
                            }
                    )

                    if (gridEnabled) {
                        GridOverlay(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(previewAspectRatio)
                        )
                    }

                    if (timerCountdown > 0) {
                        Text(
                            text = "$timerCountdown",
                            fontSize = 96.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Adjustment sliders on the right edge
                    AdjustmentPanel(
                        exposure = exposureComp,
                        warmth = warmth,
                        shadows = shadows,
                        onExposureChange = { viewModel.setExposureCompensation(it) },
                        onWarmthChange = { viewModel.setWarmth(it) },
                        onShadowsChange = { viewModel.setShadows(it) },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .matchParentSize()
                    )

                    ZoomBar(
                        currentZoom = zoomRatio,
                        onZoomSelected = { viewModel.setZoomRatio(it) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                    )
                }

                ShutterRow(
                    cameraMode = cameraMode,
                    isRecording = isRecording,
                    isCapturing = isCapturing,
                    galleryBitmap = galleryBitmap,
                    onCapture = {
                        when {
                            cameraMode == CameraMode.PANORAMA -> {
                                if (panoSweeping) viewModel.stopPanorama()
                                else viewModel.startPanorama()
                            }
                            isPhotoType -> viewModel.takePhoto(controller)
                            else -> viewModel.toggleRecording(controller)
                        }
                    },
                    onFlipCamera = { viewModel.flipCamera() },
                    onGallery = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        )
                    },
                    iconRotation = animatedRotation
                )

                ModeSelector(
                    cameraMode = cameraMode,
                    isPhotoType = isPhotoType,
                    onModeSelected = { viewModel.setCameraMode(it) }
                )

                BottomBar(
                    cameraMode = cameraMode,
                    isPhotoType = isPhotoType,
                    timerDuration = timerDuration,
                    onPickerChanged = { photo ->
                        if (photo) {
                            viewModel.setCameraMode(CameraMode.PHOTO)
                        } else {
                            viewModel.setCameraMode(CameraMode.VIDEO)
                        }
                    },
                    onSettingsClick = { backStack.add(Route.Settings) },
                    onTimerCycle = {
                        val next = when (timerDuration) {
                            TimerDuration.NONE -> TimerDuration.THREE
                            TimerDuration.THREE -> TimerDuration.FIVE
                            TimerDuration.FIVE -> TimerDuration.TEN
                            TimerDuration.TEN -> TimerDuration.NONE
                        }
                        viewModel.setTimerDuration(next)
                    }
                )
            }

            if (qrResult != null) {
                QrResultOverlay(
                    text = qrResult!!,
                    onDismiss = { viewModel.setQrResult(null) },
                    context = context,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 280.dp)
                )
            }

            if (cameraMode == CameraMode.PANORAMA && (panoSweeping || panoStitching)) {
                PanoramaOverlay(
                    isSweeping = panoSweeping,
                    isStitching = panoStitching,
                    frameCount = panoFrameCount,
                    sweepAngle = panoAngle,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp)
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    flashMode: FlashMode,
    gridEnabled: Boolean,
    onFlashToggle: () -> Unit,
    onGridToggle: () -> Unit,
    iconRotation: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val flashBg = when (flashMode) {
            FlashMode.ON -> Color(0xFF3C3C3C)
            FlashMode.OFF -> Color.Transparent
            FlashMode.AUTO -> Color(0xFF3C3C3C)
        }
        IconButton(
            onClick = onFlashToggle,
            modifier = Modifier
                .size(40.dp)
                .background(flashBg, CircleShape)
        ) {
            Icon(
                painterResource(
                    when (flashMode) {
                        FlashMode.ON -> R.drawable.flash_on_24px
                        FlashMode.OFF -> R.drawable.flash_off_24px
                        FlashMode.AUTO -> R.drawable.flash_auto_24px
                    }
                ),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp).rotate(iconRotation)
            )
        }

        Spacer(Modifier.width(4.dp))

        val gridBg = if (gridEnabled) Color(0xFF3C3C3C) else Color.Transparent
        IconButton(
            onClick = onGridToggle,
            modifier = Modifier
                .size(40.dp)
                .background(gridBg, CircleShape)
        ) {
            Icon(
                painterResource(R.drawable.grid_on_24px),
                contentDescription = "Grid",
                tint = Color.White,
                modifier = Modifier.size(22.dp).rotate(iconRotation)
            )
        }
    }
}

@Composable
private fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.3f)
        val thirdW = size.width / 3f
        val thirdH = size.height / 3f

        for (i in 1..2) {
            drawLine(color, Offset(thirdW * i, 0f), Offset(thirdW * i, size.height), strokeWidth)
            drawLine(color, Offset(0f, thirdH * i), Offset(size.width, thirdH * i), strokeWidth)
        }
    }
}

@Composable
private fun AdjustmentPanel(
    exposure: Float,
    warmth: Float,
    shadows: Float,
    onExposureChange: (Float) -> Unit,
    onWarmthChange: (Float) -> Unit,
    onShadowsChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VerticalSlider(
            value = exposure,
            onValueChange = onExposureChange,
            iconRes = R.drawable.wb_sunny_24px,
            label = "Brightness",
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        VerticalSlider(
            value = shadows,
            onValueChange = onShadowsChange,
            iconRes = R.drawable.contrast_24px,
            label = "Shadows",
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        VerticalSlider(
            value = warmth,
            onValueChange = onWarmthChange,
            iconRes = R.drawable.warmth_24px,
            label = "Warmth",
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

@Composable
private fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    iconRes: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = label,
            tint = if (value != 0f) Color.White else Color(0xFF888888),
            modifier = Modifier.size(14.dp).padding(top = 2.dp)
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .width(36.dp)
                .padding(vertical = 8.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        val trackHeight = size.height.toFloat()
                        val fraction = (change.position.y / trackHeight).coerceIn(0f, 1f)
                        val newValue = 1f - fraction * 2f // top=1, middle=0, bottom=-1
                        onValueChange(newValue.coerceIn(-1f, 1f))
                    }
                }
        ) {
            val trackWidth = 4.dp.toPx()
            val thumbRadius = 8.dp.toPx()
            val trackX = size.width / 2f
            val trackTop = thumbRadius
            val trackBottom = size.height - thumbRadius
            val trackHeight = trackBottom - trackTop
            val midY = (trackTop + trackBottom) / 2f
            val thumbY = trackTop + trackHeight * (1f - (value + 1f) / 2f)

            // Inactive track
            drawLine(
                Color(0xFF666666),
                Offset(trackX, trackTop),
                Offset(trackX, trackBottom),
                strokeWidth = trackWidth
            )
            // Active track (from center to thumb)
            drawLine(
                Color.White,
                Offset(trackX, midY),
                Offset(trackX, thumbY),
                strokeWidth = trackWidth
            )
            // Center tick
            drawLine(
                Color(0xFFAAAAAA),
                Offset(trackX - 6.dp.toPx(), midY),
                Offset(trackX + 6.dp.toPx(), midY),
                strokeWidth = 1.5.dp.toPx()
            )
            // Thumb
            drawCircle(Color.White, thumbRadius, Offset(trackX, thumbY))
        }
    }
}

@Composable
private fun ZoomBar(currentZoom: Float, onZoomSelected: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ZOOM_LEVELS.forEach { (label, ratio) ->
            val isSelected = currentZoom == ratio
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .then(
                        if (isSelected) Modifier.background(Color(0xFF3C3C3C), CircleShape)
                        else Modifier
                    )
                    .clip(CircleShape)
                    .clickable { onZoomSelected(ratio) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (isSelected) Color.White else Color(0xFFBBBBBB),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun ShutterRow(
    cameraMode: CameraMode,
    isRecording: Boolean,
    isCapturing: Boolean,
    galleryBitmap: android.graphics.Bitmap?,
    onCapture: () -> Unit,
    onFlipCamera: () -> Unit,
    onGallery: () -> Unit,
    iconRotation: Float
) {
    val shutterScale by animateFloatAsState(
        targetValue = if (isCapturing) 0.8f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 800f)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                Icon(
                    painterResource(R.drawable.ic_photo_library),
                    contentDescription = stringResource(R.string.open_gallery),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .size((76 * shutterScale).dp)
                .border(3.dp, Color.White, CircleShape)
                .padding(5.dp)
                .clip(CircleShape)
                .background(
                    if (isRecording) Color.Red
                    else if (cameraMode in listOf(CameraMode.VIDEO, CameraMode.SLOW_MO, CameraMode.TIMELAPSE)) Color.Red
                    else Color.White
                )
                .clickable(onClick = onCapture)
        )

        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF3C3C3C), CircleShape)
                .clickable(onClick = onFlipCamera),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.flip_camera_android_24px),
                contentDescription = stringResource(R.string.flip_camera),
                tint = Color.White,
                modifier = Modifier.size(24.dp).rotate(iconRotation)
            )
        }
    }
}

@Composable
private fun ModeSelector(
    cameraMode: CameraMode,
    isPhotoType: Boolean,
    onModeSelected: (CameraMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val modes = if (isPhotoType) {
            listOf(
                CameraMode.PORTRAIT to "Portrait",
                CameraMode.PHOTO to "Photo",
                CameraMode.PANORAMA to "Pano"
            )
        } else {
            listOf(
                CameraMode.SLOW_MO to "Slo-Mo",
                CameraMode.VIDEO to "Video",
                CameraMode.TIMELAPSE to "Timelapse"
            )
        }
        modes.forEach { (mode, label) ->
            val isSelected = cameraMode == mode
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .then(
                        if (isSelected) Modifier.background(Color(0xFF3C3C3C), RoundedCornerShape(20.dp))
                        else Modifier
                    )
                    .clickable { if (!isSelected) onModeSelected(mode) }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun BottomBar(
    cameraMode: CameraMode,
    isPhotoType: Boolean,
    timerDuration: TimerDuration,
    onPickerChanged: (Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    onTimerCycle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color(0xFF3C3C3C), CircleShape)
                .clickable(onClick = onSettingsClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(com.vayunmathur.library.R.drawable.settings_24px),
                contentDescription = stringResource(R.string.settings),
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        Row(
            modifier = Modifier
                .background(Color(0xFF3C3C3C), RoundedCornerShape(20.dp))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .then(
                        if (isPhotoType) Modifier.background(Color(0xFF5C5C5C), CircleShape)
                        else Modifier
                    )
                    .clickable { if (!isPhotoType) onPickerChanged(true) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.photo_camera_24px),
                    contentDescription = stringResource(R.string.mode_photo),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .then(
                        if (!isPhotoType) Modifier.background(Color(0xFF5C5C5C), CircleShape)
                        else Modifier
                    )
                    .clickable { if (isPhotoType) onPickerChanged(false) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.video_camera_back_24px),
                    contentDescription = stringResource(R.string.mode_video),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (isPhotoType) {
            val timerLabel = when (timerDuration) {
                TimerDuration.NONE -> ""
                TimerDuration.THREE -> "3"
                TimerDuration.FIVE -> "5"
                TimerDuration.TEN -> "10"
            }
            val timerBg = if (timerDuration != TimerDuration.NONE) MaterialTheme.colorScheme.primary else Color(0xFF3C3C3C)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(timerBg, CircleShape)
                    .clickable(onClick = onTimerCycle),
                contentAlignment = Alignment.Center
            ) {
                if (timerDuration == TimerDuration.NONE) {
                    Icon(
                        painterResource(R.drawable.ic_timer),
                        contentDescription = stringResource(R.string.settings_timer),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painterResource(R.drawable.ic_timer),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(timerLabel, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
                    }
                }
            }
        } else {
            Spacer(Modifier.size(44.dp))
        }
    }
}

@Composable
private fun QrResultOverlay(text: String, onDismiss: () -> Unit, context: Context, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.qr_result),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (Patterns.WEB_URL.matcher(text).matches()) {
                Button(onClick = {
                    val url = if (!text.startsWith("http")) "https://$text" else text
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }) {
                    Text(stringResource(R.string.open_url))
                }
            }
            FilledTonalButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("QR", text))
            }) {
                Text(stringResource(R.string.copy_text))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    }
}

@Composable
private fun PanoramaOverlay(
    isSweeping: Boolean,
    isStitching: Boolean,
    frameCount: Int,
    sweepAngle: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isStitching) {
            Text(
                "Processing panorama…",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        } else if (isSweeping) {
            Text(
                "← Sweep slowly →",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.size(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$frameCount frames",
                    color = Color(0xFFBBBBBB),
                    fontSize = 13.sp
                )
                Text("•", color = Color(0xFF888888), fontSize = 13.sp)
                Text(
                    "${sweepAngle.toInt()}°",
                    color = Color(0xFFBBBBBB),
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .background(Color(0xFF444444), RoundedCornerShape(4.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((sweepAngle / 180f).coerceIn(0f, 1f))
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .size(4.dp)
                )
            }
        }
    }
}
