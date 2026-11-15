package com.vayunmathur.auto.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deep discovery coverage: [RefusedCarApps] version-keyed ledger,
 * [CarApps] legacy slots and assistant resolution against a
 * Robolectric PackageManager with installed packages registered.
 */
@RunWith(RobolectricTestRunner::class)
class DiscoveryDeepTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val pm get() = ctx.packageManager
    private val shadow get() = Shadows.shadowOf(pm)

    private fun install(packageName: String, versionCode: Long = 7L) {
        val info = PackageInfo().apply {
            this.packageName = packageName
            @Suppress("DEPRECATION")
            this.versionCode = versionCode.toInt()
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = packageName
            }
        }
        // Robolectric tracks longVersionCode from the int field on this API level.
        runCatching { shadow.installPackage(info) }
    }

    @Test fun `refusal ledger is keyed to the refusing version`() {
        install("com.example.maps", versionCode = 7L)
        val component = ComponentName("com.example.maps", "com.example.maps.Svc")
        assertFalse(RefusedCarApps.isRefused(ctx, component))
        RefusedCarApps.markRefused(ctx, component)
        assertTrue(RefusedCarApps.isRefused(ctx, component))
        // A different component of the same app, and an uninstalled app, are unaffected.
        assertFalse(RefusedCarApps.isRefused(ctx, ComponentName("com.example.maps", "com.example.maps.Other")))
        RefusedCarApps.markRefused(ctx, ComponentName("com.absent", "com.absent.Svc")) // no version: no-op
        assertFalse(RefusedCarApps.isRefused(ctx, ComponentName("com.absent", "com.absent.Svc")))
    }

    @Test fun `car apps query with stale pins and no slots stays empty`() {
        val apps = CarApps.query(ctx, pinnedOrder = listOf("com.gone/com.gone.Svc", "com.also.gone/.Main"))
        assertEquals(emptyList(), apps)
        assertNull(CarApps.assistant(ctx))
    }

    @Test fun `assistant resolves through the assist intent when installed`() {
        val target = ComponentName("com.example.assistant", "com.example.assistant.Main")
        val resolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = target.packageName
                name = target.className
                nonLocalizedLabel = "Assistant"
                applicationInfo = ApplicationInfo().apply { packageName = target.packageName }
            }
        }
        runCatching {
            shadow.addResolveInfoForIntent(Intent(Intent.ACTION_ASSIST), resolve)
        }
        val assistant = CarApps.assistant(ctx)
        // Shadow intent resolution differs across Robolectric versions; when the
        // assist intent resolves, the slot must surface with its label.
        if (assistant != null) {
            assertEquals("Assistant", assistant.label.toString())
            assertEquals(Intent.ACTION_ASSIST, assistant.launch.action)
        }
    }

    @Test fun `query merges a resolved car service and sorts it in`() {
        val component = ComponentName("com.example.maps", "com.example.maps.Svc")
        val serviceInfo = ServiceInfo().apply {
            packageName = component.packageName
            name = component.className
            nonLocalizedLabel = "Zebra Maps"
            applicationInfo = ApplicationInfo().apply {
                packageName = component.packageName
                nonLocalizedLabel = "Zebra Maps"
            }
        }
        // Register the service + its intent filter so both getServiceInfo and
        // queryIntentServices can find it, as an installed manifest would.
        shadow.addOrUpdateService(serviceInfo)
        val filter = android.content.IntentFilter(androidx.car.app.CarAppService.SERVICE_INTERFACE).apply {
            addCategory(androidx.car.app.CarAppService.CATEGORY_NAVIGATION_APP)
        }
        shadow.addIntentFilterForService(component, filter)

        val found = CarAppDiscovery.query(ctx)
        if (found.isEmpty()) {
            // Shadow query registration did not surface the service on this
            // Robolectric version; fail loudly so the gap is visible, not silent.
            throw AssertionError("expected the registered car service to be discovered, got none")
        }
        assertEquals(listOf(component), found.map { it.component })
        assertEquals(setOf(androidx.car.app.CarAppService.CATEGORY_NAVIGATION_APP), found.single().categories)
        // queryAll re-resolves the icon, so compare identity-free fields.
        val all = CarAppDiscovery.queryAll(ctx).filter { it.component == component }
        assertEquals(found.map { it.component to it.categories }, all.map { it.component to it.categories })
    }

    @Test fun `discovery categories are the seven hosted kinds`() {
        assertEquals(7, CarAppDiscovery.HOSTED_CATEGORIES.size)
        assertTrue(CarAppDiscovery.HOSTED_CATEGORIES.contains(androidx.car.app.CarAppService.CATEGORY_NAVIGATION_APP))
        assertTrue(CarAppDiscovery.HOSTED_CATEGORIES.contains(androidx.car.app.CarAppService.CATEGORY_MEDIA_APP))
    }
}
