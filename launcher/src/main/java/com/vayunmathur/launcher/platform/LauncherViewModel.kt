package com.vayunmathur.launcher.platform

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.launcher.data.LauncherItemEntity
import com.vayunmathur.launcher.data.LauncherRepository
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.PackageKey
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the workspace, the app list, the widget host and the preferences, and exposes
 * the action facades in the UI contract so the pages stay previewable.
 *
 * Action bodies live in LauncherLifecycleOps.kt, LauncherHomeOps.kt,
 * LauncherMenuOps.kt and LauncherActionOps.kt (TooManyFunctions split); behavior identical.
 *
 * Also the one [IconLoader]: icon rasterisation is cached per process, and the cache has to
 * outlive any single screen.
 */
class LauncherViewModel(app: Application) : AndroidViewModel(app),
    HomeActions by LauncherHomeOpsHolder,
    DrawerActions by LauncherDrawerOpsHolder,
    FolderActions by LauncherFolderOpsHolder,
    ItemMenuActions by LauncherItemMenuOpsHolder,
    WidgetPickerActions by LauncherWidgetOpsHolder,
    SettingsActions by LauncherSettingsOpsHolder,
    IconLoader by LauncherIconOpsHolder {

    internal val ds = DataStoreUtils.getInstance(app)
    internal val repository = LauncherRepository.get(app)
    internal val densityDpi = app.resources.displayMetrics.densityDpi

    val appsMonitor = LauncherAppsMonitor(app, viewModelScope)
    internal val iconCache = IconCache(iconSizePx = (densityDpi * ICON_CACHE_DP / 160f).toInt())

    /**
     * What this build is allowed to do beyond what any launcher can. False everywhere on an
     * ordinary device, which is what keeps the privileged features invisible there.
     */
    val privilege = LauncherPrivilege(app) { bridge }

    val widgetHost = LauncherWidgetHost(app)
    internal val widgets = WidgetBindFlow(app, widgetHost, AppWidgetManager.getInstance(app))

    /**
     * Set by `MainActivity` for the whole time it exists, and cleared when it goes.
     *
     * The bind and configure flows and the HOME-role prompt all need to start something for
     * a result, which only an Activity can do. With no DI framework the alternatives were
     * threading a callback through every action signature or keeping this one nullable
     * reference; the reference is clearer, and nulling it in `onDestroy` is what keeps it
     * from outliving the Activity.
     */
    var bridge: ActivityBridge? = null

    internal val homeState = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = homeState

    internal val drawerState = MutableStateFlow(DrawerUiState(loading = true))
    val drawer: StateFlow<DrawerUiState> = drawerState

    internal val itemMenuState = MutableStateFlow(ItemMenuUiState())
    val itemMenu: StateFlow<ItemMenuUiState> = itemMenuState

    internal val widgetPickerState = MutableStateFlow(WidgetPickerUiState())
    val widgetPicker: StateFlow<WidgetPickerUiState> = widgetPickerState

    internal val settingsState = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = settingsState

    /** Kept whole so the drawer can filter without re-reading the monitor. */
    internal var allApps: List<DrawerApp> = emptyList()

    /** Cache of the last committed layout, so a drop can look up its neighbours. */
    internal var savedItems: List<LauncherItemEntity> = emptyList()

    /** Launch counts by flattened component, which is what the predictions row is ordered by. */
    internal val launchCounts = mutableMapOf<String, Long>()

    /**
     * Whether each package is a system app, cached for the life of the process.
     *
     * `getApplicationInfo` is a binder call, and the answer is asked for once per row on every
     * workspace rebuild - which is every write. Uncached that is a round trip per icon per drag
     * commit, on the main thread, for something that cannot change without the package being
     * replaced (which takes the row with it through reconciliation anyway).
     */
    internal val systemApps = mutableMapOf<String, Boolean>()

    // Widget-picker loading, querying, adding and hosted views live in LauncherWidgets.kt.
    internal var allWidgetGroups: List<WidgetGroup> = emptyList()

    init {
        loadPreferencesImpl()
        loadLaunchCounts()
        observeWorkspaceImpl()
        LauncherHomeOpsHolder.bind(this)
        LauncherDrawerOpsHolder.bind(this)
        LauncherFolderOpsHolder.bind(this)
        LauncherItemMenuOpsHolder.bind(this)
        LauncherWidgetOpsHolder.bind(this)
        LauncherSettingsOpsHolder.bind(this)
        LauncherIconOpsHolder.bind(this)
    }

    override fun onCleared() {
        appsMonitor.stop()
        widgetHost.stopListeningSafely()
        bridge = null
    }

    /** Shared constants and helpers for LauncherViewModel. */
    companion object {
        /** Rasterisation size for cached icons, in dp. Generous so scaling up stays sharp. */
        private const val ICON_CACHE_DP = 72

        const val MIN_ICON_SCALE = 0.8f
        const val MAX_ICON_SCALE = 1.4f

        /** Grid sizes offered in settings. */
        val COLUMN_OPTIONS = listOf(3, 4, 5, 6)
        val ROW_OPTIONS = listOf(4, 5, 6, 7)
    }
}

/** Seed intents for first-run hotseat, in priority order. */
internal val SEED_INTENTS = listOf(
    Intent(Intent.ACTION_DIAL),
    Intent(Intent.ACTION_VIEW, "https://example.com".toUri()),
    Intent("android.media.action.IMAGE_CAPTURE"),
    Intent(Intent.ACTION_SENDTO, "mailto:".toUri()),
)

internal const val KEY_COLUMNS = "launcher_grid_columns"
internal const val KEY_ROWS = "launcher_grid_rows"
internal const val KEY_HOTSEAT = "launcher_hotseat_slots"
internal const val KEY_SHOW_LABELS = "launcher_show_labels"
internal const val KEY_ICON_SCALE = "launcher_icon_scale"
internal const val KEY_DRAWER_LIST_LAYOUT = "launcher_drawer_list_layout"

/** Launcher3's own all-apps blur, in pixels; density-independent enough not to be a dp. */
internal const val WALLPAPER_BLUR_PX = 60
