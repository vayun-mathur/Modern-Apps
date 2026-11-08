package com.vayunmathur.appstore.data.installer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.security.InstallRequirement
import com.vayunmathur.appstore.data.security.VerificationResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The commit half of SessionInstaller: bytes that pass verification are
 * written into a PackageInstaller session and committed (Robolectric's
 * shadow PackageInstaller records the session), so the outcome is
 * started and the stage machinery can take it from there.
 */
@RunWith(RobolectricTestRunner::class)
class SessionInstallerCommitTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun signingInfo(): SigningInfo {
        val der = android.util.Base64.decode(CERT_A, android.util.Base64.DEFAULT)
        val info = SigningInfo()
        val setSignatures = Shadows.shadowOf(info).javaClass
            .getDeclaredMethod("setSignatures", Array<Signature>::class.java)
        setSignatures.isAccessible = true
        setSignatures.invoke(Shadows.shadowOf(info), arrayOf(Signature(der)))
        return info
    }

    /** A fake downloaded APK whose archive info the shadow PM serves for its path. */
    private fun registerApk(fileName: String, pkg: String = "com.example.commit"): File {
        val file = File(context.cacheDir, fileName).apply { writeText("apk-bytes") }
        val info = PackageInfo().apply {
            packageName = pkg
            @Suppress("DEPRECATION")
            versionCode = 7
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                sourceDir = file.absolutePath
                targetSdkVersion = 35
            }
            signingInfo = signingInfo()
        }
        Shadows.shadowOf(context.packageManager).addPackage(info)
        return file
    }

    @Test
    fun `verified bytes are committed to a session`() = runTest {
        val apk = registerApk("commit-base.apk")
        val installer = SessionInstaller(context)
        val outcome = installer.installSplits(
            "com.example.commit",
            listOf(apk),
            InstallRequirement(expectedPackage = "com.example.commit"),
            totalSize = apk.length(),
        )
        assertTrue(
            outcome.started,
            "expected the session to start, verification was ${outcome.verification}",
        )
        assertTrue(outcome.verification !is VerificationResult.Rejected)
    }

    @Test
    fun `the shadow package installer received a session for the package`() = runTest {
        val apk = registerApk("commit-two.apk", pkg = "com.example.two")
        SessionInstaller(context).installSplits(
            "com.example.two",
            listOf(apk),
            InstallRequirement(expectedPackage = "com.example.two"),
        )
        val sessions = context.packageManager.packageInstaller.mySessions
        assertTrue(
            sessions.any { it.appPackageName == "com.example.two" },
            "sessions were ${sessions.map { it.appPackageName }}",
        )
        assertEquals(
            "com.example.two",
            sessions.first { it.appPackageName == "com.example.two" }.appPackageName,
        )
    }

    private companion object {
        // Self-signed X.509 cert (DER, base64), as in InstallVerifierTest.
        const val CERT_A = "MIICvTCCAaWgAwIBAgIED3lFmDANBgkqhkiG9w0BAQsFADAPMQ0wCwYDVQQDEwRUZXN0MB4XDTI2MTEwNzAyNTAyNFoXDTM2MTEwNDAyNTAyNFowDzENMAsGA1UEAxMEVGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALS7Wkz9gUndEJscrbEZgQHPQh7aVHnWFIXpw7yMZpLlI7QUBg8x8jZPJeHbWJXws/IW66dod0/QmqrG4y21i1KIQW5xwIPo3s3Igrs9ZnO05kd9Um9sgrcagAd4CjnUI0Um10AIogP6Bz6rUFL8WGDNvACq22bJDRaKVVIno7FuwgGQY+7Jrn73B4rQjyOM+PJtAnxNGBcDUVXa6CCGhU9DT/eYCtnjKlOVF8Pmflwe4LlrfYRqq7wJm2d+I6cZHwMX2coH9BwymuRCT4s6NnYkQZVYAvy8BFVI3jvK+Q259oDKDDZBLp+fHs9cUHJQ2TwnKH5rFH7tYXIwvkHgG5ECAwEAAaMhMB8wHQYDVR0OBBYEFM8ej83GSYbEXAs4JDW/shyQY5x1MA0GCSqGSIb3DQEBCwUAA4IBAQCboZxi2u+lovXniEQCy31XZhU+vKXr+u1lyHKREgy88rcDrXquapNJX0XpaJBSLFEc180j3Fr6vWst0JynJxjq7dFHqf7wDSLJQDysXEDEUr2+H6Edkjbw8Lju88uXrD7v7BgHSxsn1ffZ02FfzFKF6zC4M7pp2FkJj/1CdtW4JbfcH2RWqht8k7YSAvWgPm2hzroQzMHe3BxGsSj0t9+9F5GdBtZdnG+/i8iBcOvmk+r7p0xkWuH7qaZmcGqNrX9m31FnZMp9OWkwkCqc8yncKDJ89P810JAcVL6Fp0cBrUMACkNAH5nIpDxAfV1mDp0FW8mDYuW1qnNxg0zdPfWX"
    }
}
