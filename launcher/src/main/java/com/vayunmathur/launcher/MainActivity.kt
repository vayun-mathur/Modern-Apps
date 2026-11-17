package com.vayunmathur.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.UiModeManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.launcher.platform.ActivityBridge
import com.vayunmathur.launcher.platform.LauncherPrivilege
import com.vayunmathur.launcher.platform.LauncherViewModel
import com.vayunmathur.launcher.platform.onStartImpl
import com.vayunmathur.launcher.platform.reconcileNowImpl
import com.vayunmathur.launcher.platform.onStopImpl
import com.vayunmathur.launcher.platform.refreshDefaultHomeImpl
import com.vayunmathur.launcher.platform.TvHomeViewModel
import com.vayunmathur.launcher.ui.TvNavigation
import com.vayunmathur.launcher.ui.TvRoute
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack

/**
 * The home screen activity, and the [ActivityBridge] the Compose tree talks to.
 *
 * `singleTask` plus `stateNotNeeded` in the manifest are what make it behave like a home
 * app: HOME delivers a new intent to the existing instance rather than stacking another
 * copy, and the system may kill it without saving state because the workspace is in the
 * database, not in a `Bundle`.
 */
class MainActivity : MaActivity(), ActivityBridge {

    private val viewModel: LauncherViewModel by viewModels()
    private val tvViewModel: TvHomeViewModel by viewModels()
    private var backStack: NavBackStack<Route>? = null
    private var tvBackStack: NavBackStack<TvRoute>? = null

    /**
     * Phone or TV content, decided once per resume from the current UI mode. A `var` rather
     * than a `val`: HDMI-CEC/dock changes can flip `uiMode` at runtime, and the next resume
     * re-routes (the merged-tvlauncher contract).
     */
    private var tvMode: Boolean = false

    /** Asked before anything a stock device cannot do is offered. See [LauncherPrivilege]. */
    private val privilege get() = viewModel.privilege

    /**
     * The bind consent dialog is a plain intent, so it goes through the Activity Result API.
     *
     * The configure step cannot: `AppWidgetHost.startAppWidgetConfigureActivityForResult` starts
     * the activity itself via the legacy call, so its result can only arrive through
     * [onActivityResult]. One pending callback per flow is enough - both are modal to the user.
     */
    private var pendingBindResult: ((Boolean) -> Unit)? = null
    private var pendingConfigureResult: ((Boolean) -> Unit)? = null

    private val bindWidgetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingBindResult
            pendingBindResult = null
            callback?.invoke(result.resultCode == Activity.RESULT_OK)
        }

    override val edgeToEdge: Boolean
        get() = !tvMode

    override fun onCreateBeforeContent() {
        tvMode = isTvMode()
        viewModel.bridge = this
    }

    @Composable
    override fun Content() {
        if (tvMode) {
            val stack = rememberNavBackStack<TvRoute>(TvRoute.Home)
            SideEffect { tvBackStack = stack }
            TvNavigation(tvViewModel, stack)
        } else {
            val stack = rememberNavBackStack<Route>(Route.Home)
            SideEffect { backStack = stack }
            Navigation(viewModel, this, stack)
        }
    }

    override fun onStart() {
        super.onStart()
        if (tvMode) return
        viewModel.onStartImpl()
        viewModel.refreshDefaultHomeImpl()
    }

    override fun onResume() {
        super.onResume()
        val nowTv = isTvMode()
        if (nowTv != tvMode) {
            // UI mode flipped underneath us (dock/HDMI-CEC): re-route on next composition.
            tvMode = nowTv
        }
        if (tvMode) {
            tvViewModel.refreshApps()
            tvViewModel.refreshDefaultHome(this)
            return
        }
        // Cheap enough to redo every time we come back: an app may have been installed or
        // uninstalled while we were away without us being alive to hear the callback.
        viewModel.reconcileNowImpl()
    }

    override fun onStop() {
        if (!tvMode) viewModel.onStopImpl()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isTvMode() != tvMode) {
            // Recreate to re-run onCreateBeforeContent + Content with the right mode rather
            // than trying to swap nav graphs mid-composition.
            recreate()
        }
    }

    /**
     * True when this device is a television. Single fact, mirrored from the appstore
     * DeviceAttributesProvider notion of TV — leanback present, no touchscreen.
     */
    private fun isTvMode(): Boolean {
        val uiMode = getSystemService(UiModeManager::class.java)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        if (uiMode) return true
        return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    override fun onDestroy() {
        viewModel.bridge = null
        super.onDestroy()
    }

    /**
     * Pressing HOME from inside another app, or from a folder we left open, comes back here.
     * Resetting to the first page is what makes HOME feel like HOME rather than a no-op.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (tvMode) tvBackStack?.reset(TvRoute.Home) else backStack?.reset(Route.Home)
    }

    // ------------------------------------------------------------------
    // ActivityBridge
    // ------------------------------------------------------------------

    override fun requestBindWidget(
        appWidgetId: Int,
        provider: ComponentName,
        profileSerial: Long,
        onResult: (Boolean) -> Unit,
    ) {
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,
                viewModel.appsMonitor.userFor(profileSerial),
            )
        }
        pendingBindResult = onResult
        val started = runCatching { bindWidgetLauncher.launch(intent) }.isSuccess
        if (!started) {
            pendingBindResult = null
            // Reporting failure rather than leaving the flow hanging is what triggers the
            // caller's deleteAppWidgetId; a silent drop here leaks the allocated id.
            onResult(false)
        }
    }

    override fun startWidgetConfigure(appWidgetId: Int, onResult: (Boolean) -> Unit) {
        pendingConfigureResult = onResult
        val started = runCatching {
            viewModel.widgetHost.startAppWidgetConfigureActivityForResult(
                this,
                appWidgetId,
                0,
                REQUEST_CONFIGURE_WIDGET,
                null,
            )
        }.isSuccess
        if (!started) {
            pendingConfigureResult = null
            onResult(false)
        }
    }

    @Deprecated("AppWidgetHost only offers startActivityForResult for the configure step.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE_WIDGET) return
        val callback = pendingConfigureResult ?: return
        pendingConfigureResult = null
        callback(resultCode == Activity.RESULT_OK)
    }

    override fun pickWallpaper() {
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_SET_WALLPAPER),
            getString(R.string.app_name),
        )
        if (!startSafely(chooser)) AppMessages.show("No wallpaper picker available")
    }

    override fun requestHomeRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        val intent = if (roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
            roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            // Without the role available there is no direct prompt, so the honest fallback is
            // the settings page where the choice actually lives.
            Intent(Settings.ACTION_HOME_SETTINGS)
        }
        if (!startSafely(intent)) AppMessages.show("Could not open home app settings")
    }

    override fun isDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    override fun requestUninstall(packageName: String) {
        // Silently when the ROM has granted DELETE_PACKAGES, which only a system build has; on an
        // ordinary device this is false and the user gets the system confirmation they expect.
        if (privilege.canUninstallSilently() && uninstallSilently(packageName)) return
        // ACTION_DELETE rather than the package installer session API: it needs no
        // permission and shows the system confirmation the user expects.
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
        if (!startSafely(intent)) AppMessages.show("Could not open the uninstaller")
    }

    private fun uninstallSilently(packageName: String): Boolean = runCatching {
        val intent = Intent(this, MainActivity::class.java)
        val sender = PendingIntent.getActivity(
            this,
            REQUEST_SILENT_UNINSTALL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        packageManager.packageInstaller.uninstall(packageName, sender.intentSender)
        true
    }.getOrDefault(false)

    override fun setWallpaperBlurRadius(radiusPx: Int) {
        // Off on some devices and in battery saver, where asking for a blur is silently ignored -
        // so the drawer's own translucent fill has to be enough on its own, and is.
        val manager = getSystemService(WindowManager::class.java)
        if (manager?.isCrossWindowBlurEnabled != true) return
        runCatching { window.setBackgroundBlurRadius(radiusPx) }
    }

    override fun launchAnimationOptions(left: Int, top: Int, right: Int, bottom: Int): Bundle? =
        runCatching {
            // Clip reveal from the icon's own rect, which is as close to the system launcher's app
            // open as an unprivileged app gets; the real shared-element handoff needs SystemUI.
            ActivityOptions
                .makeClipRevealAnimation(window.decorView, left, top, right - left, bottom - top)
                .toBundle()
        }.getOrNull()

    private fun startSafely(intent: Intent): Boolean = runCatching {
        startActivity(intent)
        true
    }.getOrDefault(false)

    private companion object {
        const val REQUEST_CONFIGURE_WIDGET = 1002
        const val REQUEST_SILENT_UNINSTALL = 1003
    }
}
