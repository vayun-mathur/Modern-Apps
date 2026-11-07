package com.vayunmathur.launcher.platform

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Flow tests for [WidgetBindFlow.add] and provider enumeration, driven by
 * Robolectric's app-widget shadow: bind-allowed vs consent-required,
 * configure vs no-configure, and cancellation releasing the allocated id.
 */
@RunWith(AndroidJUnit4::class)
class WidgetBindFlowTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeBridge(
        var bindResult: Boolean = true,
        var configureResult: Boolean = true,
    ) : ActivityBridge {
        val bindRequests = mutableListOf<Int>()
        val configureRequests = mutableListOf<Int>()

        override fun requestBindWidget(
            appWidgetId: Int,
            provider: ComponentName,
            profileSerial: Long,
            onResult: (Boolean) -> Unit,
        ) {
            bindRequests += appWidgetId
            onResult(bindResult)
        }

        override fun startWidgetConfigure(appWidgetId: Int, onResult: (Boolean) -> Unit) {
            configureRequests += appWidgetId
            onResult(configureResult)
        }

        override fun pickWallpaper() {}
        override fun requestHomeRole() {}
        override fun isDefaultHome(): Boolean = false
        override fun requestUninstall(packageName: String) {}
        override fun setWallpaperBlurRadius(radiusPx: Int) {}
        override fun launchAnimationOptions(left: Int, top: Int, right: Int, bottom: Int): Bundle? = null
    }

    private fun flow(): WidgetBindFlow {
        val host = LauncherWidgetHost(context)
        return WidgetBindFlow(context, host, AppWidgetManager.getInstance(context))
    }

    private fun info(configure: ComponentName? = null): AppWidgetProviderInfo {
        val info = AppWidgetProviderInfo()
        info.provider = ComponentName("com.example", "com.example.Widget")
        info.configure = configure
        info.minWidth = 100
        info.minHeight = 100
        return info
    }

    @Test
    fun `providers lists installed providers for the whole device and per profile`() {
        val widgetManager = AppWidgetManager.getInstance(context)
        val shadow = shadowOf(widgetManager)
        val providerInfo = info()
        shadow.addInstalledProvider(providerInfo)
        shadow.addInstalledProvidersForProfile(Process.myUserHandle(), providerInfo)

        val flow = flow()
        assertTrue(flow.providers(null).contains(providerInfo))
        assertTrue(flow.providers(Process.myUserHandle()).contains(providerInfo))
    }

    @Test
    fun `providerInfo returns what the manager holds and null for unknown ids`() {
        val widgetManager = AppWidgetManager.getInstance(context)
        val providerInfo = info()
        shadowOf(widgetManager).putWidgetInfo(777, providerInfo)
        val flow = flow()
        assertEquals(providerInfo, flow.providerInfo(777))
        assertNull(flow.providerInfo(778))
    }

    @Test
    fun `add binds directly when allowed and persists without configure`() {
        val widgetManager = AppWidgetManager.getInstance(context)
        shadowOf(widgetManager).setAllowedToBindAppWidgets(true)
        val bridge = FakeBridge()
        var boundId: Int? = null
        var cancelled = 0
        flow().add(info(), bridge, profileSerial = 0L, onBound = { boundId = it }, onCancelled = { cancelled++ })
        assertTrue(boundId != null)
        assertEquals(0, cancelled)
        assertTrue(bridge.bindRequests.isEmpty())
        assertTrue(bridge.configureRequests.isEmpty())
    }

    @Test
    fun `add runs configure when the provider declares one, then persists`() {
        val widgetManager = AppWidgetManager.getInstance(context)
        shadowOf(widgetManager).setAllowedToBindAppWidgets(true)
        val bridge = FakeBridge(configureResult = true)
        var boundId: Int? = null
        flow().add(
            info(configure = ComponentName("com.example", "com.example.Configure")),
            bridge, profileSerial = 0L, onBound = { boundId = it },
        )
        assertTrue(boundId != null)
        assertEquals(listOf(boundId), bridge.configureRequests)
    }

    @Test
    fun `add abandons when configure is cancelled`() {
        val widgetManager = AppWidgetManager.getInstance(context)
        shadowOf(widgetManager).setAllowedToBindAppWidgets(true)
        val bridge = FakeBridge(configureResult = false)
        var boundId: Int? = null
        var cancelled = 0
        flow().add(
            info(configure = ComponentName("com.example", "com.example.Configure")),
            bridge, profileSerial = 0L, onBound = { boundId = it }, onCancelled = { cancelled++ },
        )
        assertNull(boundId)
        assertEquals(1, cancelled)
    }

    @Test
    fun `add asks for consent when binding is not allowed, and honours the answer`() {
        val widgetManager = AppWidgetManager.getInstance(context)
        shadowOf(widgetManager).setAllowedToBindAppWidgets(false)

        val grantedBridge = FakeBridge(bindResult = true)
        var boundId: Int? = null
        flow().add(info(), grantedBridge, profileSerial = 0L, onBound = { boundId = it })
        assertTrue(boundId != null)
        assertEquals(listOf(boundId), grantedBridge.bindRequests)

        val refusedBridge = FakeBridge(bindResult = false)
        var boundId2: Int? = null
        var cancelled = 0
        flow().add(info(), refusedBridge, profileSerial = 0L, onBound = { boundId2 = it }, onCancelled = { cancelled++ })
        assertNull(boundId2)
        assertEquals(1, cancelled)
        assertEquals(1, refusedBridge.bindRequests.size)
    }

    @Test
    fun `host listening guards are idempotent and bound ids read back`() {
        val host = LauncherWidgetHost(context)
        host.startListeningSafely()
        host.startListeningSafely()
        assertEquals(emptySet(), host.boundIds())
        host.stopListeningSafely()
        host.stopListeningSafely()
        assertEquals(0x4C41, LauncherWidgetHost.HOST_ID)
    }
}
