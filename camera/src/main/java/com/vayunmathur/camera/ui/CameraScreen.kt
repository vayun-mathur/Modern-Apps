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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.vayunmathur.camera.util.GuideDot
import com.vayunmathur.camera.util.GuideDotState
import com.vayunmathur.camera.util.FlashMode
import com.vayunmathur.camera.util.QrAnalyzer
import com.vayunmathur.camera.util.TimerDuration
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


private const val BOKEH_SHADER = """
    uniform shader cameraInput;
    uniform shader alphaMask;

    half4 main(float2 fragCoord) {
        float maskAlpha = alphaMask.eval(fragCoord).a;
        half4 sharp = cameraInput.eval(fragCoord);

        // 3-pass separable bokeh: blur along 0°, 120°, 240° axes then average.
        // Each pass samples 13 taps along its direction (radius ~36px).
        float2 dir0 = float2(1.0, 0.0);
        float2 dir1 = float2(-0.5, 0.866);
        float2 dir2 = float2(-0.5, -0.866);

        // 1D Gaussian-ish weights for 13 taps (symmetric, sum ≈ 1)
        float w0 = 0.14;
        float w1 = 0.13;
        float w2 = 0.11;
        float w3 = 0.09;
        float w4 = 0.06;
        float w5 = 0.04;
        float w6 = 0.02;

        half4 pass0 = cameraInput.eval(fragCoord) * w0;
        half4 pass1 = cameraInput.eval(fragCoord) * w0;
        half4 pass2 = cameraInput.eval(fragCoord) * w0;

        for (float i = 1.0; i <= 6.0; i += 1.0) {
            float w;
            if (i < 1.5) w = w1;
            else if (i < 2.5) w = w2;
            else if (i < 3.5) w = w3;
            else if (i < 4.5) w = w4;
            else if (i < 5.5) w = w5;
            else w = w6;

            float2 off0 = dir0 * i * 6.0;
            float2 off1 = dir1 * i * 6.0;
            float2 off2 = dir2 * i * 6.0;

            pass0 += cameraInput.eval(fragCoord + off0) * w;
            pass0 += cameraInput.eval(fragCoord - off0) * w;
            pass1 += cameraInput.eval(fragCoord + off1) * w;
            pass1 += cameraInput.eval(fragCoord - off1) * w;
            pass2 += cameraInput.eval(fragCoord + off2) * w;
            pass2 += cameraInput.eval(fragCoord - off2) * w;
        }

        half4 blur = (pass0 + pass1 + pass2) / 3.0;
        return mix(blur, sharp, maskAlpha);
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
    val recordingDuration by viewModel.recordingDurationSec.collectAsState()
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
    val isPhotoType = cameraMode in listOf(CameraMode.PHOTO, CameraMode.PORTRAIT, CameraMode.PANORAMA, CameraMode.PHOTOSPHERE)
    val isSloMo = cameraMode == CameraMode.SLOW_MO
    val highSpeedActive by viewModel.highSpeedActive.collectAsState()
    val availableZoomLevels by viewModel.availableZoomLevels.collectAsState()
    val bokehShader = remember { lazy { RuntimeShader(BOKEH_SHADER) } }

    val panoSweeping by viewModel.panoramaEngine.isSweeping.collectAsState()
    val panoStitching by viewModel.panoramaEngine.isStitching.collectAsState()
    val panoFrameCount by viewModel.panoramaEngine.frameCount.collectAsState()
    val panoAngle by viewModel.panoramaEngine.sweepAngle.collectAsState()
    val panoDots by viewModel.panoramaEngine.guideDots.collectAsState()
    val panoCurrentAngle by viewModel.panoramaEngine.currentAngle.collectAsState()
    val panoDirection by viewModel.panoramaEngine.sweepDirection.collectAsState()
    val panoPitch by viewModel.panoramaEngine.currentPitch.collectAsState()

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

    LaunchedEffect(lensFacing, isSloMo) {
        if (isSloMo) return@LaunchedEffect
        delay(500)
        viewModel.updateZoomLevels(lensFacing)
    }

    LaunchedEffect(Unit) {
        viewModel.updateLocation()
        controller.isPinchToZoomEnabled = true
    }

    // Sync controller's zoom state back to ViewModel (for pinch-to-zoom)
    DisposableEffect(controller, lifecycleOwner) {
        val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { state ->
            if (state != null) {
                viewModel.setZoomRatio(state.zoomRatio)
            }
        }
        controller.zoomState.observe(lifecycleOwner, observer)
        onDispose { controller.zoomState.removeObserver(observer) }
    }

    LaunchedEffect(flashMode) {
        controller.imageCaptureFlashMode = viewModel.getImageCaptureFlashMode()
    }

    LaunchedEffect(zoomRatio) {
        val zoomState = controller.zoomState.value
        val currentZoom = zoomState?.zoomRatio ?: 0f
        if (kotlin.math.abs(currentZoom - zoomRatio) > 0.05f) {
            if (zoomState != null) {
                val clamped = zoomRatio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                controller.setZoomRatio(clamped)
            } else {
                controller.setZoomRatio(zoomRatio)
            }
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
        if (cameraMode == CameraMode.SLOW_MO) {
            maskBitmap = null
            controller.setEnabledUseCases(0)
            controller.clearImageAnalysisAnalyzer()
            viewModel.setQrResult(null)
    } else if (cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT || cameraMode == CameraMode.PANORAMA || cameraMode == CameraMode.PHOTOSPHERE) {
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            when (cameraMode) {
                CameraMode.PORTRAIT -> {
                    controller.setImageAnalysisAnalyzer(
                        ContextCompat.getMainExecutor(context),
                        BokehAnalyzer(context) { mask -> maskBitmap = mask }
                    )
                }
                CameraMode.PANORAMA, CameraMode.PHOTOSPHERE -> {
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

    // Manage camera binding: controller for normal modes, high-speed session for slo-mo
    val highSpeedPreviewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    val regularPreviewView = remember {
        PreviewView(context).apply {
            this.controller = controller
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(lensFacing, isSloMo) {
        if (isSloMo) {
            controller.unbind()
            viewModel.teardownHighSpeedSession()
            delay(250)
            viewModel.setupHighSpeedSession(
                highSpeedPreviewView.surfaceProvider
            )
        } else {
            viewModel.teardownHighSpeedSession()
            controller.bindToLifecycle(lifecycleOwner)
        }
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
                    val previewModifier = Modifier
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

                    if (isSloMo) {
                        AndroidView(
                            factory = { highSpeedPreviewView },
                            modifier = previewModifier
                        )
                    } else {
                        AndroidView(
                            factory = { regularPreviewView },
                            modifier = previewModifier
                        )
                    }

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
                        zoomLevels = availableZoomLevels,
                        onZoomSelected = { viewModel.setZoomRatio(it) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                    )

                    if ((cameraMode == CameraMode.PANORAMA || cameraMode == CameraMode.PHOTOSPHERE) && (panoSweeping || panoStitching)) {
                        PanoramaOverlay(
                            isSweeping = panoSweeping,
                            isStitching = panoStitching,
                            guideDots = panoDots,
                            currentAngle = panoCurrentAngle,
                            sweepDirection = panoDirection,
                            currentPitch = panoPitch,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(previewAspectRatio)
                        )
                    }
                }

                ShutterRow(
                    cameraMode = cameraMode,
                    isRecording = isRecording,
                    isCapturing = isCapturing,
                    galleryBitmap = galleryBitmap,
                    onCapture = {
                        when {
                            cameraMode == CameraMode.PHOTOSPHERE -> {
                                if (panoSweeping) viewModel.stopPhotosphere()
                                else viewModel.startPhotosphere()
                            }
                            cameraMode == CameraMode.PANORAMA -> {
                                if (panoSweeping) viewModel.stopPanorama()
                                else viewModel.startPanorama()
                            }
                            isSloMo && highSpeedActive -> viewModel.toggleHighSpeedRecording()
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
                    onModeSelected = { viewModel.switchCameraMode(it) }
                )

                BottomBar(
                    cameraMode = cameraMode,
                    isPhotoType = isPhotoType,
                    timerDuration = timerDuration,
                    iconRotation = animatedRotation,
                    onPickerChanged = { photo ->
                        if (photo) {
                            viewModel.switchCameraMode(CameraMode.PHOTO)
                        } else {
                            viewModel.switchCameraMode(CameraMode.VIDEO)
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

            if (isRecording) {
                RecordingIndicator(
                    durationSec = recordingDuration,
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
private fun ZoomBar(
    currentZoom: Float,
    zoomLevels: List<Pair<String, Float>>,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        zoomLevels.forEach { (label, ratio) ->
            val isSelected = kotlin.math.abs(currentZoom - ratio) < 0.05f
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
    val recordingCornerRadius by animateFloatAsState(
        targetValue = if (isRecording) 8f else 38f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f)
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

        val animatedSize by animateFloatAsState(
            targetValue = if (isRecording) 36f else 66f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f)
        )
        Box(
            modifier = Modifier
                .size(76.dp)
                .border(3.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(animatedSize.dp)
                    .clip(RoundedCornerShape(recordingCornerRadius.dp))
                    .background(
                        if (cameraMode in listOf(CameraMode.VIDEO, CameraMode.SLOW_MO, CameraMode.TIMELAPSE)) Color.Red
                        else Color.White
                    )
                    .clickable(onClick = onCapture)
            )
        }

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
                CameraMode.PANORAMA to "Pano",
                CameraMode.PHOTOSPHERE to "Sphere"
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
    iconRotation: Float,
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
                modifier = Modifier.size(22.dp).rotate(iconRotation)
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
                    modifier = Modifier.size(20.dp).rotate(iconRotation)
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
                    modifier = Modifier.size(20.dp).rotate(iconRotation)
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
                        modifier = Modifier.size(22.dp).rotate(iconRotation)
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painterResource(R.drawable.ic_timer),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp).rotate(iconRotation)
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
private fun RecordingIndicator(durationSec: Long, modifier: Modifier = Modifier) {
    val dotAlpha by animateFloatAsState(
        targetValue = if ((durationSec % 2) == 0L) 1f else 0.3f,
        animationSpec = tween(500)
    )

    val minutes = durationSec / 60
    val seconds = durationSec % 60
    val timeText = "%d:%02d".format(minutes, seconds)

    Row(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .graphicsLayer { alpha = dotAlpha }
                .background(Color.Red, CircleShape)
        )
        Text(
            text = timeText,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PanoramaOverlay(
    isSweeping: Boolean,
    isStitching: Boolean,
    guideDots: List<GuideDot>,
    currentAngle: Float,
    sweepDirection: Int,
    currentPitch: Float,
    modifier: Modifier = Modifier
) {
    if (isStitching) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
                Text("Processing panorama\u2026", color = Color.White, fontSize = 16.sp)
            }
        }
        return
    }

    if (!isSweeping) return

    val capturedCount = guideDots.count { it.state == GuideDotState.CAPTURED }
    val totalDots = guideDots.size
    val halfFOV = 30f

    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(capturedCount) {
        if (capturedCount > 1) {
            flashAlpha.snapTo(0.3f)
            flashAlpha.animateTo(0f, tween(250))
        }
    }

    Box(modifier.fillMaxSize()) {
        if (flashAlpha.value > 0f) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha.value)))
        }

        Canvas(Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val halfVertFOV = 40f

            guideDots.forEach { dot ->
                val angleOffset = dot.targetAngle - currentAngle
                val pitchOffset = dot.targetPitch - currentPitch
                if (Math.abs(angleOffset) <= halfFOV * 1.2f && Math.abs(pitchOffset) <= halfVertFOV * 1.2f) {
                    val dotX = centerX + (angleOffset / halfFOV) * (size.width / 2)
                    val dotY = centerY - (pitchOffset / halfVertFOV) * (size.height / 2)
                    val baseRadius = 14.dp.toPx()

                    when (dot.state) {
                        GuideDotState.PENDING -> {
                            drawCircle(Color.White.copy(alpha = 0.7f), baseRadius, Offset(dotX, dotY), style = Stroke(2.dp.toPx()))
                            drawCircle(Color.White.copy(alpha = 0.3f), 3.dp.toPx(), Offset(dotX, dotY))
                        }
                        GuideDotState.ALIGNING, GuideDotState.CAPTURING -> {
                            drawCircle(Color(0xFF4FC3F7), baseRadius * 1.2f, Offset(dotX, dotY))
                            drawCircle(Color.White, baseRadius * 1.2f, Offset(dotX, dotY), style = Stroke(2.dp.toPx()))
                        }
                        GuideDotState.CAPTURED -> {
                            drawCircle(Color(0xFF4CAF50), baseRadius, Offset(dotX, dotY))
                            val checkPath = Path().apply {
                                moveTo(dotX - baseRadius * 0.35f, dotY)
                                lineTo(dotX - baseRadius * 0.05f, dotY + baseRadius * 0.3f)
                                lineTo(dotX + baseRadius * 0.4f, dotY - baseRadius * 0.3f)
                            }
                            drawPath(checkPath, Color.White, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                }
            }

            val crossSize = 20.dp.toPx()
            val crossGap = 6.dp.toPx()
            val crossStroke = 2.dp.toPx()
            val crossColor = Color.White
            drawLine(crossColor, Offset(centerX - crossSize, centerY), Offset(centerX - crossGap, centerY), crossStroke)
            drawLine(crossColor, Offset(centerX + crossGap, centerY), Offset(centerX + crossSize, centerY), crossStroke)
            drawLine(crossColor, Offset(centerX, centerY - crossSize), Offset(centerX, centerY - crossGap), crossStroke)
            drawLine(crossColor, Offset(centerX, centerY + crossGap), Offset(centerX, centerY + crossSize), crossStroke)
            drawCircle(crossColor, 2.dp.toPx(), Offset(centerX, centerY))
        }

        Text(
            "$capturedCount / $totalDots",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}
