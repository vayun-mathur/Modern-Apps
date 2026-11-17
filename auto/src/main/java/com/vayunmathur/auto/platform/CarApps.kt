package com.vayunmathur.auto.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.vayunmathur.library.log.Log

/** One tile on the car launcher: an icon, a label, and how to open it. */
data class CarApp(
    val label: CharSequence,
    val icon: Drawable?,
    val launch: Intent,
)

/**
 * Which apps the car launcher shows.
 *
 * Discovery-first: every `CarAppService` the phone reports (navigation, POI,
 * IoT, weather) becomes a tile whose tap opens the hosted template screen;
 * the legacy phone slots (Maps launch intent, media browser, dialer) remain
 * as open-on-phone fallbacks for apps with no car service. Whatever is not
 * installed is skipped rather than shown dead, and an empty grid gets an
 * explicit empty state.
 */
object CarApps {
    /**
     * Queries all car-capable apps, pinned first.
     *
     * [pinnedOrder] is flattened component strings (see [DiscoveredApp.id])
     * in dock order; entries that no longer resolve are filtered as stale.
     * Unpinned apps follow in label order, then the legacy phone slots.
     */
    @androidx.car.app.annotations.ExperimentalCarApi
    fun query(context: Context, pinnedOrder: List<String> = emptyList()): List<CarApp> {
        val discovered = runCatching { CarAppDiscovery.query(context) }.getOrElse {
            Log.status(TAG, "car discovery failed; legacy slots only", it)
            emptyList()
        }
        val byId = discovered.associateBy { it.id }
        val found = mutableListOf<CarApp>()
        for (id in pinnedOrder) {
            val app = byId[id] ?: continue
            found += app.toCarApp()
        }
        val pinnedIds = pinnedOrder.toSet()
        for (app in discovered) {
            if (app.id !in pinnedIds) found += app.toCarApp()
        }
        for (slot in SLOTS) {
            runCatching { slot.resolve(context, context.packageManager) }
                .onSuccess { app -> if (app != null) found += app }
                .onFailure { Log.status(TAG, "could not resolve car slot ${slot.label}", it) }
        }
        return found
    }

    /**
     * Resolves the assistant slot for the launcher's assistant affordance, or
     * null when the phone has no assistant to open. Skip-if-missing like every
     * [SLOTS] entry.
     */
    fun assistant(context: Context): CarApp? = runCatching {
        val pm = context.packageManager
        val assist = Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val voice = Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launch = if (assist.resolveActivity(pm) != null) assist
        else if (voice.resolveActivity(pm) != null) voice
        else return null
        val target = launch.resolveActivity(pm) ?: return null
        val info = pm.getActivityInfo(target, 0)
        CarApp(
            label = info.loadLabel(pm),
            icon = info.loadIcon(pm),
            launch = launch,
        )
    }.getOrNull()

    private fun DiscoveredApp.toCarApp(): CarApp {
        // Tapping a discovered tile opens its hosted template screen (Phase C
        // routes this through the launcher selection); the launch intent opens
        // the phone app as the pre-host fallback.
        val component = component
        val launch = Intent(Intent.ACTION_MAIN)
            .setClassName(component.packageName, component.className)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return CarApp(label = label, icon = icon, launch = launch)
    }

    private interface Slot {
        val label: String
        fun resolve(context: Context, pm: PackageManager): CarApp?
    }

    private fun launcherApp(
        pm: PackageManager,
        packageName: String,
    ): CarApp? {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val info = pm.getApplicationInfo(packageName, 0)
        return CarApp(
            label = pm.getApplicationLabel(info),
            icon = pm.getApplicationIcon(info),
            launch = intent,
        )
    }

    private val SLOTS: List<Slot> = listOf(
        object : Slot {
            override val label = "maps"
            override fun resolve(context: Context, pm: PackageManager): CarApp? =
                launcherApp(pm, "com.vayunmathur.maps")
        },
        object : Slot {
            override val label = "music"
            override fun resolve(context: Context, pm: PackageManager): CarApp? {
                val direct = runCatching {
                    launcherApp(pm, "com.vayunmathur.music")
                }.getOrNull()
                if (direct != null) return direct
                // Fallback: anything declaring a browsable media surface.
                val browse = Intent("android.media.browse.MediaBrowserService")
                val services = pm.queryIntentServices(browse, 0)
                val service = services.firstOrNull() ?: return null
                val info = service.serviceInfo
                return CarApp(
                    label = info.loadLabel(pm),
                    icon = info.loadIcon(pm),
                    launch = pm.getLaunchIntentForPackage(info.packageName)
                        ?: Intent(Intent.ACTION_VIEW).setPackage(info.packageName),
                )
            }
        },
        object : Slot {
            override val label = "phone"
            override fun resolve(context: Context, pm: PackageManager): CarApp? {
                val dial = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val activity = dial.resolveActivity(pm) ?: return null
                val info = pm.getActivityInfo(activity, 0)
                return CarApp(
                    label = info.loadLabel(pm),
                    icon = info.loadIcon(pm),
                    launch = dial,
                )
            }
        },
    )

    private const val TAG = "MaAuto.CarApps"
}
