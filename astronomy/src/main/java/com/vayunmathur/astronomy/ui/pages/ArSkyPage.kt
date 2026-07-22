package com.vayunmathur.astronomy.ui.pages

import android.Manifest
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.domain.projection.ViewState
import com.vayunmathur.astronomy.ui.AstronomyViewModel
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun ArSkyPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel) {
    val context = LocalContext.current
    val visibleSky by viewModel.visibleSky.collectAsState()
    val fov by viewModel.fovDeg.collectAsState()
    val showConst by viewModel.showConstellations.collectAsState()
    val showDeep by viewModel.showDeepSky.collectAsState()
    val showPlanets by viewModel.showPlanets.collectAsState()
    val showGrid by viewModel.showGrid.collectAsState()
    val selectedId by viewModel.selectedObjectId.collectAsState()
    val trajectory by viewModel.trajectory.collectAsState()
    val deviceOrient by viewModel.deviceOrientation.collectAsState()

    var screenW by remember { mutableFloatStateOf(1080f) }
    var screenH by remember { mutableFloatStateOf(1920f) }
    val surfaceRequest = remember { MutableStateFlow<androidx.camera.core.SurfaceRequest?>(null) }
    val surfaceReqState by surfaceRequest.collectAsState()

    val centerPair = remember(viewModel.viewCenter.collectAsState().value, deviceOrient) {
        viewModel.resolveCenter()
    }
    val (centerAz, centerAlt) = centerPair
    val rotation = remember(viewModel.viewCenter.collectAsState().value, deviceOrient) {
        viewModel.resolveRotation()
    }
    val viewState = remember(centerAz, centerAlt, rotation, fov, screenW, screenH) {
        ViewState(centerAz, centerAlt, fov, screenW, screenH, rotation)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var provider: ProcessCameraProvider? = null
            var manualOwner: ManualLifecycleOwner? = null
            try {
                provider = ProcessCameraProvider.awaitInstance(context)
                provider.unbindAll()
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider { req -> surfaceRequest.value = req }
                val owner = ManualLifecycleOwner(); owner.start(); manualOwner = owner
                provider.bindToLifecycle(owner, selector, preview)
                awaitCancellation()
            } finally {
                manualOwner?.destroy()
                provider?.unbindAll()
                surfaceRequest.value = null
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.setDevicePointing(true) }

    PermissionsChecker(arrayOf(Manifest.permission.CAMERA), "Grant camera to use AR") {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AR Sky") },
                    navigationIcon = { IconNavigation(backStack) },
                    actions = {
                        IconButton(onClick = { viewModel.enableAr(false); backStack.pop() }) { IconClose() }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize().onSizeChanged { screenW = it.width.toFloat(); screenH = it.height.toFloat() }) {
                surfaceReqState?.let { req ->
                    CameraXViewfinder(surfaceRequest = req, modifier = Modifier.fillMaxSize())
                }

                SkyCanvas(
                    visibleSky = visibleSky,
                    viewState = viewState,
                    showConstellationLines = showConst,
                    showGrid = showGrid,
                    showDeepSky = showDeep,
                    showPlanets = showPlanets,
                    transparentBackground = true,
                    trajectory = trajectory,
                    selectedId = selectedId,
                    onPan = { dAz, dAlt -> viewModel.onPan(dAz, dAlt) },
                    onZoom = { viewModel.setFov(it) },
                    onTap = {},
                    onObjectTap = { id -> viewModel.selectObject(id); backStack.add(Route.ObjectDetail(id)) },
                    modifier = Modifier.fillMaxSize()
                )

                Text("AR: Az ${Math.toDegrees(centerAz).toInt()}° Alt ${Math.toDegrees(centerAlt).toInt()}°", modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
            }
        }
    }
}

private class ManualLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun start() { registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
