package com.vayunmathur.launcher.platform

import android.content.ComponentName
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.domain.PackageKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [LauncherAppsMonitor] against Robolectric's LauncherApps shadow,
 * which has no launchable activities installed: refresh publishes an empty
 * list, change notifications fire, and lookups degrade to null/false rather
 * than throwing.
 */
@RunWith(AndroidJUnit4::class)
class LauncherAppsMonitorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun awaitApps(monitor: LauncherAppsMonitor, changes: MutableList<PackageKey?>) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (changes.isNotEmpty()) return
            Thread.sleep(5)
        }
        throw AssertionError("refresh never notified; apps=${monitor.apps.value}")
    }

    @Test
    fun `start refreshes to an empty list and notifies with no stale package`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val monitor = LauncherAppsMonitor(context, scope)
            val changes = mutableListOf<PackageKey?>()
            monitor.start { stale -> synchronized(changes) { changes += stale } }
            awaitApps(monitor, changes)
            assertTrue(monitor.apps.value.isEmpty())
            assertEquals(listOf<PackageKey?>(null), synchronized(changes) { changes.toList() })
            assertTrue(monitor.unavailable.value.isEmpty())
            assertTrue(monitor.installedKeys().isEmpty())
            monitor.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `refresh with a stale key reports it to the listener`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val monitor = LauncherAppsMonitor(context, scope)
            val changes = mutableListOf<PackageKey?>()
            monitor.start { stale -> synchronized(changes) { changes += stale } }
            awaitApps(monitor, changes)
            val stale = PackageKey("com.example.changed", 0L)
            monitor.refresh(stale)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                synchronized(changes) { if (changes.contains(stale)) break }
                Thread.sleep(5)
            }
            assertTrue(synchronized(changes) { changes.contains(stale) })
            monitor.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `lookups for unknown apps degrade safely`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val monitor = LauncherAppsMonitor(context, scope)
            val component = ComponentName("com.example", "com.example.Main")
            assertNull(monitor.entryFor(component, 0L))
            assertNull(monitor.icon(component, 0L, 320))
            assertEquals(Process.myUserHandle(), monitor.userFor(0L))
            assertEquals(emptyList(), monitor.shortcuts("com.example", Process.myUserHandle()))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `shortcut operations on an unknown shortcut degrade safely`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val monitor = LauncherAppsMonitor(context, scope)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setComponent(ComponentName("com.example", "com.example.Main"))
            val shortcut = android.content.pm.ShortcutInfo.Builder(context, "shortcut-1")
                .setShortLabel("Shortcut")
                .setIntent(intent)
                .build()
            // Not the default home app, so the role-gated calls must answer
            // with null/false/empty rather than throwing.
            kotlin.test.assertNull(monitor.shortcutIcon(shortcut, 320))
            kotlin.test.assertEquals(false, monitor.startShortcut(shortcut, null))
            monitor.pinShortcut(shortcut)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `app entry keys derive from component and serial`() {
        val component = ComponentName("com.example.app", "com.example.app.Main")
        val entry = AppEntry(
            componentName = component,
            label = "Example",
            user = Process.myUserHandle(),
            profileSerial = 0L,
            isWorkProfile = false,
        )
        assertEquals(ComponentKey(component, 0L), entry.key)
        assertEquals(PackageKey("com.example.app", 0L), entry.packageKey)
        val work = entry.copy(user = Process.myUserHandle(), profileSerial = 10L, isWorkProfile = true)
        assertEquals(PackageKey("com.example.app", 10L), work.packageKey)
        assertTrue(work.isWorkProfile)
    }
}
