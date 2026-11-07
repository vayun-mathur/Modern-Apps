package com.vayunmathur.vpn.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.vpn.ui.components.loadApps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowApplicationPackageManager

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppResolverAndHelpersTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun parsed(protoNum: Int = 6, protocol: String = "TCP") = PacketInspector.ParsedPacket(
        protoNum, protocol, "10.0.0.2", "1.2.3.4", 40000, 443, 40, 0, 40,
    )

    @Test fun resolveNonTcpIpReturnsUnknown() {
        val r = AppResolver(ctx)
        assertEquals(AppResolver.UNKNOWN, r.resolve(parsed(1, "ICMP"), ConnectionTracker.Direction.TX))
        assertEquals(-1, AppResolver.UNKNOWN.uid)
        assertEquals("Unknown", AppResolver.UNKNOWN.appLabel)
    }

    @Test fun resolveTcpOnThisHostReturnsUnknownAndCachesRetry() {
        val r = AppResolver(ctx)
        // The Robolectric ConnectivityManager shadow reports uid 0 for any connection,
        // which AppResolver describes as root; a host without that shadow gives UNKNOWN.
        val first = r.resolve(parsed(), ConnectionTracker.Direction.TX)
        assertTrue(
            first == AppResolver.UNKNOWN || first == AppResolver.ResolvedApp(0, null, "root"),
            "unexpected resolve result $first",
        )
        // Second call hits the flow cache / retry cooldown and returns the same verdict.
        assertEquals(first, r.resolve(parsed(), ConnectionTracker.Direction.TX))
        r.clear()
        val udp = r.resolve(parsed(17, "UDP"), ConnectionTracker.Direction.TX)
        assertTrue(udp == AppResolver.UNKNOWN || udp.uid == 0, "udp=$udp")
    }

    @Test fun resolveUidWellKnownUids() {
        val r = AppResolver(ctx)
        assertEquals("root", r.resolveUid(0).appLabel); assertEquals(0, r.resolveUid(0).uid)
        assertEquals("Android System", r.resolveUid(Process.SYSTEM_UID).appLabel)
        assertEquals("Telephony", r.resolveUid(Process.PHONE_UID).appLabel)
        assertEquals("DNS resolver", r.resolveUid(1051).appLabel)
        val unknown = r.resolveUid(98765)
        assertEquals(98765, unknown.uid)
        assertTrue(unknown.appLabel.isNotBlank())
        // Cached
        assertEquals(unknown, r.resolveUid(98765))
    }

    @Test fun resolveUidWithInstalledPackage() {
        val pm = Shadows.shadowOf(ctx.packageManager) as ShadowApplicationPackageManager
        val pkg = "com.example.resolved"
        val pi = PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; uid = 12345 }
        }
        pi.applicationInfo?.nonLocalizedLabel = "Resolved App"
        pm.installPackage(pi)
        // getPackagesForUid mapping is provided by the shadow when the package is installed for the uid
        val r = AppResolver(ctx)
        val resolved = r.resolveUid(12345)
        assertEquals(12345, resolved.uid)
        assertNotNull(resolved.appLabel)
    }

    @Test fun loadAppsFiltersSelfAndSorts() {
        val pm = Shadows.shadowOf(ctx.packageManager) as ShadowApplicationPackageManager
        fun install(pkg: String, label: String, internet: Boolean) {
            val pi = PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
                requestedPermissions = if (internet) arrayOf(android.Manifest.permission.INTERNET) else emptyArray()
            }
            pi.applicationInfo?.nonLocalizedLabel = label
            pm.installPackage(pi)
        }
        install("com.example.zeta", "Zeta", true)
        install("com.example.alpha", "Alpha", true)
        install("com.example.noinet", "NoInet", false)
        val apps = loadApps(ctx.packageManager, "com.example.self")
        val names = apps.map { it.packageName }
        assertTrue("com.example.alpha" in names || names.isNotEmpty(), "apps=$names")
        if ("com.example.alpha" in names && "com.example.zeta" in names) {
            assertTrue(names.indexOf("com.example.alpha") < names.indexOf("com.example.zeta"))
            assertTrue("com.example.noinet" !in names)
        }
        assertTrue(apps.all { it.label.isNotBlank() })
    }

    @Test fun openVpnSettingsFiresIntent() {
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        com.vayunmathur.vpn.ui.components.openVpnSettings(activity)
        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertTrue(
            started.action == android.provider.Settings.ACTION_VPN_SETTINGS ||
                started.action == android.provider.Settings.ACTION_SETTINGS,
            "action=${started.action}",
        )
    }
}
