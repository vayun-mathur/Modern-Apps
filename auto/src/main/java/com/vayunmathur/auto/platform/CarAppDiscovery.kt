package com.vayunmathur.auto.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.vayunmathur.library.log.Log
import androidx.car.app.CarAppService

/**
 * One car-app-capable app: its service component, label, icon and the
 * categories it declared.
 */
data class DiscoveredApp(
    val component: ComponentName,
    val label: CharSequence,
    val icon: Drawable?,
    val categories: Set<String>,
) {
    /** Stable pin id: the flattened component string. */
    val id: String get() = component.flattenToString()
}

/**
 * Discovers third-party car apps through `PackageManager`, plus first-party
 * services by explicit component.
 *
 * Queries `CarAppService.SERVICE_INTERFACE` once per category the launcher
 * hosts (navigation, POI, IoT, weather): the manifest's `<queries>` block
 * keeps those lookups visible on Android 11+. Results merge by component so
 * an app declaring two categories appears once with both. First-party
 * services (music, communicate) publish categories the generic query does
 * not cover — `androidx.car.app.category.MEDIA` is not even a library
 * constant — so [queryAll] merges explicit components ahead of category
 * results, with real app icons resolved through `PackageManager`.
 */
object CarAppDiscovery {
    fun query(context: Context): List<DiscoveredApp> {
        val pm = context.packageManager
        val merged = linkedMapOf<ComponentName, MutableSet<String>>()
        for (category in HOSTED_CATEGORIES) {
            val intent = android.content.Intent(CarAppService.SERVICE_INTERFACE)
                .addCategory(category)
            val found = runCatching {
                pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
            }.getOrElse {
                Log.status(TAG, "could not query car apps for $category", it)
                emptyList()
            }
            for (info in found) {
                val component = ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
                merged.getOrPut(component) { linkedSetOf() } += category
            }
        }
        return merged.mapNotNull { (component, categories) ->
            if (RefusedCarApps.isRefused(context, component)) {
                Log.status(TAG, "hiding $component: it refused this host")
                return@mapNotNull null
            }
            runCatching {
                val info = pm.getServiceInfo(component, 0)
                DiscoveredApp(
                    component = component,
                    label = info.loadLabel(pm),
                    icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                    categories = categories,
                )
            }.getOrElse {
                Log.status(TAG, "could not load car app $component", it)
                null
            }
        }.sortedBy { it.label.toString().lowercase() }
    }

    /** Categories the launcher hosts templates for. */
    val HOSTED_CATEGORIES: List<String> = listOf(
        CarAppService.CATEGORY_NAVIGATION_APP,
        CarAppService.CATEGORY_POI_APP,
        CarAppService.CATEGORY_IOT_APP,
        CarAppService.CATEGORY_WEATHER_APP,
        CarAppService.CATEGORY_MESSAGING_APP,
        CarAppService.CATEGORY_CALLING_APP,
        CarAppService.CATEGORY_MEDIA_APP,
    )

    /**
     * Category query plus first-party services, by component id: what the
     * dock, the pin picker, and the host session all list. First-party
     * entries win on id collisions so their explicit category survives.
     */
    fun queryAll(context: Context): List<DiscoveredApp> {
        val byId = query(context).associateBy { it.id }.toMutableMap()
        for (party in firstParty(context)) {
            byId[party.id] = party
        }
        return byId.values.sortedBy { it.label.toString().lowercase() }
    }

    /**
     * First-party car services by explicit component, with labels and icons
     * resolved from the installed packages (missing packages resolve to
     * null and are skipped, so uninstalls never show dead tiles).
     */
    fun firstParty(context: Context): List<DiscoveredApp> {
        val pm = context.packageManager
        return FIRST_PARTY_COMPONENTS.mapNotNull { (packageName, className, label, categories) ->
            runCatching {
                val info = pm.getApplicationInfo(packageName, 0)
                DiscoveredApp(
                    component = ComponentName(packageName, className),
                    label = label,
                    icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                    categories = categories,
                )
            }.getOrElse {
                Log.status(TAG, "first-party car app not installed: $packageName", it)
                null
            }
        }
    }

    private data class FirstPartyComponent(
        val packageName: String,
        val className: String,
        val label: String,
        val categories: Set<String>,
    )

    private val FIRST_PARTY_COMPONENTS: List<FirstPartyComponent> = listOf(
        FirstPartyComponent(
            packageName = "com.vayunmathur.music",
            className = "com.vayunmathur.music.service.car.MusicCarAppService",
            label = "Music",
            categories = setOf("androidx.car.app.category.MEDIA"),
        ),
        FirstPartyComponent(
            packageName = "com.vayunmathur.communicate",
            className = "com.vayunmathur.communicate.service.car.CommunicateCarAppService",
            label = "Communicate",
            categories = setOf(CarAppService.CATEGORY_MESSAGING_APP),
        ),
    )

    private const val TAG = "MaAuto.CarDiscovery"
}
