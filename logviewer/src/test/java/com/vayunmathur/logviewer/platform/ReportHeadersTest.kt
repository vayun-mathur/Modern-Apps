package com.vayunmathur.logviewer.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReportHeadersTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `readFileAsString returns contents and null for missing or directory`() {
        val file = File.createTempFile("report-header", ".txt")
        try {
            file.writeText("hello\nworld", Charsets.UTF_8)
            assertEquals("hello\nworld", ReportHeaders.readFileAsString(file.absolutePath))
        } finally {
            file.delete()
        }
        assertNull(ReportHeaders.readFileAsString("/nonexistent/definitely/missing.txt"))
        assertNull(ReportHeaders.readFileAsString(System.getProperty("java.io.tmpdir")!!))
    }

    @Test
    fun `loadAppLabel falls back to package name for unknown package`() {
        assertEquals(
            "com.example.does.not.exist",
            ReportHeaders.loadAppLabel(context, "com.example.does.not.exist").toString(),
        )
    }

    @Test
    fun `loadAppLabel resolves an installed package label`() {
        val label = ReportHeaders.loadAppLabel(context, context.packageName).toString()
        assertTrue(label.isNotEmpty())
    }

    @Test
    fun `installingPackage is null for unknown package`() {
        assertNull(ReportHeaders.installingPackage(context, "com.example.does.not.exist"))
    }

    @Test
    fun `addDeviceLines adds nothing for the system user with dev options off`() {
        val dst = mutableListOf<String>()
        ReportHeaders.addDeviceLines(context, dst)
        // Robolectric's default user is the system user and DEVELOPMENT_SETTINGS_ENABLED is 0.
        assertTrue(dst.isEmpty(), "unexpected device lines: $dst")
    }

    @Test
    fun `addDeviceLines reports user type and dev options for a secondary user`() {
        val userManager = context.getSystemService(android.os.UserManager::class.java)
        org.robolectric.Shadows.shadowOf(userManager).setIsSystemUser(false)
        android.provider.Settings.Global.putInt(
            context.contentResolver,
            android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            1,
        )
        val dst = mutableListOf<String>()
        ReportHeaders.addDeviceLines(context, dst)
        assertTrue(dst.contains("userType: full.secondary"), "lines: $dst")
        assertTrue(dst.contains("flags: dev options enabled"), "lines: $dst")
    }

    @Test
    fun `addPackageLines prints package and target sdk, falling back to version zero`() {
        val appInfo = ApplicationInfo().apply {
            packageName = "com.example.does.not.exist"
            targetSdkVersion = 34
            sourceDir = "/data/app/com.example/base.apk"
        }
        val dst = mutableListOf<String>()
        ReportHeaders.addPackageLines(context, appInfo, dst)
        assertEquals(
            listOf("package: com.example.does.not.exist:0, targetSdk 34"),
            dst,
        )
    }

    @Test
    fun `addPackageLines for installed package omits sharedUid when none`() {
        val installed = context.packageManager.getApplicationInfo(context.packageName, 0)
        val dst = mutableListOf<String>()
        ReportHeaders.addPackageLines(context, installed, dst)
        assertTrue(dst.first().startsWith("package: ${context.packageName}:"))
        assertTrue(dst.first().contains("targetSdk ${installed.targetSdkVersion}"))
        assertTrue(dst.none { it.startsWith("sharedUid:") })
    }
}
