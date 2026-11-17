package com.vayunmathur.photos.ui

import android.app.Application
import android.app.WallpaperManager
import android.graphics.Rect
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.platform.WallpaperLoadState
import com.vayunmathur.photos.platform.WallpaperUtil
import com.vayunmathur.photos.platform.WallpaperViewModel
import com.vayunmathur.photos.platform.WallpaperViewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private val OffsetSaver = Saver<Offset, List<Float>>(
    save = { listOf(it.x, it.y) },
    restore = { Offset(it[0], it[1]) },
)

@Composable
fun WallpaperPage(
    backStack: NavBackStack<Route>,
    uri: String?,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val wallpaperSetSuccessMsg = stringResource(R.string.wallpaper_set_success)
    val app = context.applicationContext as Application
    val viewModel: WallpaperViewModel = viewModel(factory = WallpaperViewModelFactory(app))

    val bitmap by viewModel.bitmap.collectAsState()
    val loadState by viewModel.loadState.collectAsState()

    // Accurate window size on minSdk 31+ via WindowMetrics — recomputes on config change.
    val localConfig = LocalConfiguration.current
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) }
    val (screenW, screenH) = remember(localConfig, windowManager) {
        val bounds = windowManager.currentWindowMetrics.bounds
        bounds.width() to bounds.height()
    }

    val wm = remember(context) { WallpaperManager.getInstance(context) }
    // Unified scrollable width: max(desiredMinimumWidth, screenW*2) — single source of truth.
    val (scrollableW, scrollableH) = remember(wm, screenW, screenH) {
        val baseW = wm.desiredMinimumWidth
        val baseH = wm.desiredMinimumHeight
        val sW = maxOf(baseW.takeIf { it > 0 } ?: 0, screenW * 2).coerceAtLeast(screenW)
        val sH = (baseH.takeIf { it > 0 } ?: screenH).coerceAtLeast(screenH)
        sW to sH
    }

    var isScrollable by rememberSaveable { mutableStateOf(false) }
    var which by rememberSaveable { mutableIntStateOf(WallpaperManager.FLAG_SYSTEM) }
    var isSetting by rememberSaveable { mutableStateOf(false) }
    var zoomScale by rememberSaveable { mutableFloatStateOf(1f) }
    var zoomOffset by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }

    // Keep latest offset/scale fresh for gesture lambda without capturing stale values.
    val zoomScaleUpdated by rememberUpdatedState(zoomScale)
    val zoomOffsetUpdated by rememberUpdatedState(zoomOffset)

    val targetW = if (isScrollable) scrollableW else screenW
    val targetH = if (isScrollable) scrollableH else screenH

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Composition-measured snapshot — updated via SideEffect so Set button always reads current layout
    // without LaunchedEffect lag (fixes rotation stale bug). SideEffect runs after commit.
    var latestPreview by remember { mutableStateOf<PreviewSnapshot?>(null) }
    val latestPreviewRef by rememberUpdatedState(latestPreview)

    LaunchedEffect(uri) {
        val uriToLoad = uri ?: return@LaunchedEffect
        viewModel.load(uriToLoad)
    }

    val targetAspect = if (targetW > 0 && targetH > 0) {
        targetW.toFloat() / targetH.toFloat()
    } else 9f / 16f

    // RAW SCAFFOLD EXCEPTION: immersive black wallpaper preview with a transparent,
    // white-tinted TopAppBar (title + back). AppScaffold exposes no top-bar color slot, so
    // the transparent/white immersive bar cannot be preserved.
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.set_as_wallpaper), color = Color.White) },
                navigationIcon = { IconNavigation(backStack) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            WallpaperPreview(
                bitmap = bitmap,
                loadState = loadState,
                targetAspect = targetAspect,
                zoomScale = zoomScale,
                zoomOffset = zoomOffset,
                currentScale = rememberUpdatedState(zoomScaleUpdated),
                currentOffset = rememberUpdatedState(zoomOffsetUpdated),
                onZoomChange = { scale, offset ->
                    zoomScale = scale
                    zoomOffset = offset
                },
                onCoverMin = { coverMin ->
                    if (zoomScale < coverMin) {
                        zoomScale = coverMin
                        zoomOffset = Offset.Zero
                    }
                },
                onSnapshot = { latestPreview = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            // ----- bottom controls -----------------------------------------
            val currentBmp = bitmap
            WallpaperControls(
                which = which,
                onWhichChange = { which = it },
                isScrollable = isScrollable,
                onScrollableChange = { checked ->
                    isScrollable = checked
                    zoomOffset = Offset.Zero
                },
                canSet = currentBmp != null &&
                    loadState == WallpaperLoadState.Loaded &&
                    latestPreview != null,
                isSetting = isSetting,
                onSetWallpaper = {
                    val bmpForSet = currentBmp ?: return@WallpaperControls
                    if (isSetting) return@WallpaperControls
                    val snapshot = latestPreviewRef ?: return@WallpaperControls
                    if (snapshot.viewportW <= 0f) return@WallpaperControls
                    isSetting = true

                    val zoomAtClick = zoomScale
                    val offsetAtClick = zoomOffset

                    scope.launch {
                        try {
                            val cropRect: Rect = WallpaperUtil.computeCropRect(
                                srcW = bmpForSet.width,
                                srcH = bmpForSet.height,
                                baseDisplayW = snapshot.baseW,
                                baseDisplayH = snapshot.baseH,
                                zoom = zoomAtClick,
                                offsetX = offsetAtClick.x,
                                offsetY = offsetAtClick.y,
                                viewportW = snapshot.viewportW,
                                viewportH = snapshot.viewportH,
                                containerW = snapshot.containerW,
                                containerH = snapshot.containerH,
                            )

                            val result = WallpaperUtil.setWallpaper(
                                context = context,
                                source = bmpForSet,
                                viewport = cropRect,
                                targetWidth = targetW,
                                targetHeight = targetH,
                                which = which,
                                isScrollable = isScrollable,
                                screenW = screenW,
                                screenH = screenH,
                            )

                            when (result) {
                                is WallpaperUtil.SetResult.Success -> {
                                    snackbarHostState.showSnackbar(
                                        wallpaperSetSuccessMsg,
                                    )
                                    backStack.pop()
                                }
                                is WallpaperUtil.SetResult.Failure -> {
                                    val msg = resources.getString(
                                        R.string.wallpaper_set_failed,
                                        result.exception.message ?: "Unknown",
                                    )
                                    snackbarHostState.showSnackbar(msg)
                                }
                            }
                        } finally {
                            isSetting = false
                        }
                    }
                },
            )
        }
    }
}
