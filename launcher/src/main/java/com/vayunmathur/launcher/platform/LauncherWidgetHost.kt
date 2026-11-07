package com.vayunmathur.launcher.platform

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.UserHandle
import android.util.SizeF
import kotlin.math.ceil

/**
 * The `AppWidgetHost` for the launcher.
 *
 * The host id is frozen forever. It is the key the system files every allocated
 * `appWidgetId` under, so changing it orphans every widget the user has ever placed —
 * they would silently stop updating and could never be recovered. There is no migration
 * for this; it just has to never change.
 *
 * `startListening` / `stopListening` are bound to the Activity lifecycle rather than the
 * process: listening keeps `RemoteViews` flowing for every hosted widget, which is wasted
 * work and wasted battery while the home screen is not on screen. Both can throw on some
 * OEM builds when a provider misbehaves during the initial update, so both are guarded —
 * a widget that fails to start updating is much better than a launcher that cannot start.
 */
class LauncherWidgetHost(context: Context) : AppWidgetHost(context, HOST_ID) {

    private var listening = false

    fun startListeningSafely() {
        if (listening) return
        listening = runCatching { startListening() }.isSuccess
    }

    fun stopListeningSafely() {
        if (!listening) return
        runCatching { stopListening() }
        listening = false
    }

    /** Ids the host still recognises. An id missing from here can never be rendered again. */
    fun boundIds(): Set<Int> = runCatching { appWidgetIds.toSet() }.getOrDefault(emptySet())

    companion object {
        /** Frozen. See the class doc — never change this value. */
        const val HOST_ID = 0x4C41
    }
}

/**
 * Allocate, bind, configure, persist — in that order, with the id released on every exit
 * that is not a successful persist.
 *
 * The order is not rearrangeable. An id must exist before it can be bound; binding must
 * succeed before the provider will accept configuration; and the row must not be written
 * until both have, or a restart renders a widget that was never really added. Every
 * failure path calls `deleteAppWidgetId`, because a leaked id is a widget the system keeps
 * updating on our behalf that the user can neither see nor remove.
 */
class WidgetBindFlow(
    private val context: Context,
    private val host: LauncherWidgetHost,
    private val appWidgetManager: AppWidgetManager,
) {

    /** Widget providers across every profile, grouped for the picker. */
    fun providers(user: UserHandle?): List<AppWidgetProviderInfo> = runCatching {
        if (user == null) {
            appWidgetManager.installedProviders
        } else {
            appWidgetManager.getInstalledProvidersForProfile(user)
        }
    }.getOrDefault(emptyList())

    fun providerInfo(appWidgetId: Int): AppWidgetProviderInfo? =
        runCatching { appWidgetManager.getAppWidgetInfo(appWidgetId) }.getOrNull()

    /**
     * Runs the whole flow and calls [onBound] with the id only once the widget is really
     * ours. [onBound] is where the row is persisted; anything it does is guaranteed to be
     * about a live, configured widget.
     */
    fun add(
        provider: AppWidgetProviderInfo,
        bridge: ActivityBridge,
        profileSerial: Long,
        onBound: (appWidgetId: Int) -> Unit,
        onCancelled: () -> Unit = {},
    ) {
        val appWidgetId = host.allocateAppWidgetId()

        fun abandon() {
            host.deleteAppWidgetId(appWidgetId)
            onCancelled()
        }

        fun configureThenPersist() {
            if (provider.configure == null) {
                onBound(appWidgetId)
                return
            }
            bridge.startWidgetConfigure(appWidgetId) { configured ->
                // A provider that returns RESULT_CANCELED after actually configuring loses
                // its widget here. Launcher3 carries per-provider leniency for the known
                // offenders; taking the result at its word is the honest reading.
                if (configured) onBound(appWidgetId) else abandon()
            }
        }

        val allowed = runCatching {
            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider.provider)
        }.getOrDefault(false)

        if (allowed) {
            configureThenPersist()
        } else {
            bridge.requestBindWidget(appWidgetId, provider.provider, profileSerial) { granted ->
                if (granted) configureThenPersist() else abandon()
            }
        }
    }

    /** Releases an id. Called on removal, and on the ids reconciliation orphaned. */
    fun release(appWidgetId: Int) {
        runCatching { host.deleteAppWidgetId(appWidgetId) }
    }

    /**
     * Frees ids the host holds that no row references.
     *
     * These accumulate from crashes and from cancellations that never got to run their
     * cleanup, and each one is a provider still being updated for nothing.
     */
    fun releaseOrphans(usedIds: Set<Int>) {
        (host.boundIds() - usedIds).forEach(::release)
    }

    fun createView(appWidgetId: Int, provider: AppWidgetProviderInfo): AppWidgetHostView =
        host.createView(context, appWidgetId, provider)

    /**
     * Tells the provider the size it has actually been given.
     *
     * Without this a widget lays itself out for its declared minimum rather than the cell
     * span it was dropped into, which is why unsized widgets look cramped.
     */
    fun updateSize(view: AppWidgetHostView, widthDp: Int, heightDp: Int) {
        // The SizeF list overload rather than the four-int one: the latter is deprecated, and
        // minSdk is 31, which is exactly where this was introduced.
        runCatching {
            view.updateAppWidgetSize(
                Bundle.EMPTY,
                listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())),
            )
        }
    }

    /** Default span for a provider, in cells, clamped by the caller to the current grid. */
    fun spanFor(provider: AppWidgetProviderInfo, cellWidthDp: Int, cellHeightDp: Int): Pair<Int, Int> {
        // AppWidgetProviderInfo.minWidth/minHeight are already in dp, not
        // px. Dividing by density shrank every widget on a high-density
        // screen (at density 3 a 210dp-wide widget got one 72dp cell
        // instead of three), so widgets were placed far smaller than
        // the size they declare they need.
        val widthDp = provider.minWidth.coerceAtLeast(1).toFloat()
        val heightDp = provider.minHeight.coerceAtLeast(1).toFloat()
        val spanX = ceil(widthDp / cellWidthDp).toInt().coerceAtLeast(1)
        val spanY = ceil(heightDp / cellHeightDp).toInt().coerceAtLeast(1)
        return spanX to spanY
    }

    fun flatten(provider: ComponentName): String = provider.flattenToString()

    fun unflatten(flat: String?): ComponentName? =
        flat?.let { ComponentName.unflattenFromString(it) }
}
