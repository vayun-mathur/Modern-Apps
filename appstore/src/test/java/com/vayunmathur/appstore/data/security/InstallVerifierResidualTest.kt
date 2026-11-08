package com.vayunmathur.appstore.data.security

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * InstallVerifier residuals the main verifier test does not reach:
 * continuity against the installed copy (matching and rotated keys),
 * the required-signer lineage check, and the TV gate's activity-name
 * heuristic. Same shadow-PackageManager registration as the main test.
 */
@RunWith(RobolectricTestRunner::class)
class InstallVerifierResidualTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun signingInfo(cert: String, pastCerts: List<String> = emptyList()): SigningInfo {
        fun der(c: String) =
            android.util.Base64.decode(CERTS.getValue(c), android.util.Base64.DEFAULT)
        val info = SigningInfo()
        val shadow = Shadows.shadowOf(info)
        val setSignatures = shadow.javaClass
            .getDeclaredMethod("setSignatures", Array<Signature>::class.java)
        setSignatures.isAccessible = true
        setSignatures.invoke(shadow, arrayOf(Signature(der(cert))))
        if (pastCerts.isNotEmpty()) {
            val setPast = shadow.javaClass
                .getDeclaredMethod("setPastSigningCertificates", Array<Signature>::class.java)
            setPast.isAccessible = true
            setPast.invoke(shadow, pastCerts.map { Signature(der(it)) }.toTypedArray())
        }
        return info
    }

    private fun packageInfo(
        pkg: String,
        cert: String,
        sourceDir: String,
        pastCerts: List<String> = emptyList(),
        activities: List<String> = emptyList(),
    ) = PackageInfo().apply {
        packageName = pkg
        @Suppress("DEPRECATION")
        versionCode = 7
        applicationInfo = ApplicationInfo().apply {
            packageName = pkg
            this.sourceDir = sourceDir
            targetSdkVersion = 35
        }
        signingInfo = signingInfo(cert, pastCerts)
        if (activities.isNotEmpty()) {
            this.activities = activities.map { ActivityInfo().apply { name = it } }.toTypedArray()
        }
    }

    /** An installed package (PackageManager registry) signed by [cert]. */
    private fun registerInstalled(pkg: String, cert: String) {
        val info = packageInfo(pkg, cert, "/installed/$pkg.apk")
        Shadows.shadowOf(context.packageManager).addPackage(info)
    }

    /** A downloaded file whose archive info the shadow PM serves for its path. */
    private fun registerDownload(
        fileName: String,
        pkg: String,
        cert: String,
        pastCerts: List<String> = emptyList(),
        activities: List<String> = emptyList(),
    ): File {
        val file = File(context.cacheDir, fileName).apply { writeText("apk-$fileName") }
        val info = packageInfo(pkg, cert, file.absolutePath, pastCerts, activities)
        val shadow = Shadows.shadowOf(context.packageManager)
        val setter = shadow.javaClass.methods.first { it.name == "setPackageArchiveInfo" }
        setter.invoke(shadow, file.absolutePath, info)
        return file
    }

    private fun fingerprint(cert: String): String =
        ApkCertificates.sha256(
            android.util.Base64.decode(CERTS.getValue(cert), android.util.Base64.DEFAULT),
        )

    @Test
    fun `a download signed differently from the installed copy is rejected`() {
        registerInstalled("com.example.cont", cert = "b")
        val file = registerDownload("cont-a.apk", "com.example.cont", cert = "a")
        val result = InstallVerifier.verify(
            context, listOf(file), InstallRequirement(expectedPackage = "com.example.cont"),
        )
        val rejected = result as VerificationResult.Rejected
        assertTrue(rejected.reason.contains("different key"), rejected.reason)
    }

    @Test
    fun `a download signed like the installed copy verifies with the continuity note`() {
        registerInstalled("com.example.same", cert = "a")
        val file = registerDownload("same-a.apk", "com.example.same", cert = "a")
        val result = InstallVerifier.verify(
            context, listOf(file), InstallRequirement(expectedPackage = "com.example.same"),
        )
        val verified = result as VerificationResult.Verified
        assertTrue(verified.detail.contains("same key as the installed copy"), verified.detail)
    }

    @Test
    fun `a rotated key verifies against the published lineage`() {
        // The source publishes the original cert; the bytes carry the
        // rotated one plus proof of lineage to the original.
        val file = registerDownload(
            "rotated.apk", "com.example.rotated", cert = "b", pastCerts = listOf("a"),
        )
        val ok = InstallVerifier.verify(
            context,
            listOf(file),
            InstallRequirement(
                expectedPackage = "com.example.rotated",
                requiredSigners = setOf(fingerprint("a")),
                signerOrigin = "the test source",
            ),
        )
        assertIs<VerificationResult.Verified>(ok)

        val foreign = InstallVerifier.verify(
            context,
            listOf(file),
            InstallRequirement(
                expectedPackage = "com.example.rotated",
                requiredSigners = setOf("0".repeat(64)),
                signerOrigin = "the test source",
            ),
        )
        assertIs<VerificationResult.Rejected>(foreign)
    }

    @Test
    fun `tv gate accepts a leanback activity name and refuses a phone only one`() {
        val tvFile = registerDownload(
            "tv-act.apk", "com.example.tvact", cert = "a",
            activities = listOf("com.example.tv.MainActivity"),
        )
        val tvResult = InstallVerifier.verify(
            context, listOf(tvFile),
            InstallRequirement(expectedPackage = "com.example.tvact", requireTvCompatible = true),
        )
        assertIs<VerificationResult.Verified>(tvResult)

        val phoneFile = registerDownload(
            "phone-act.apk", "com.example.phoneact", cert = "a",
            activities = listOf("com.example.MainActivity"),
        )
        val phoneResult = InstallVerifier.verify(
            context, listOf(phoneFile),
            InstallRequirement(expectedPackage = "com.example.phoneact", requireTvCompatible = true),
        )
        val rejected = phoneResult as VerificationResult.Rejected
        assertTrue(rejected.reason.contains("no TV entry point"), rejected.reason)
    }

    @Test
    fun `signer lineage includes rotated ancestors`() {
        val info = packageInfo("com.example.lin", "b", "/x.apk", pastCerts = listOf("a"))
        assertEquals(
            setOf(fingerprint("a"), fingerprint("b")),
            ApkCertificates.signerLineage(info),
        )
        assertEquals(setOf(fingerprint("b")), ApkCertificates.apkSigners(context, downloadFor(info)))
    }

    private fun downloadFor(info: PackageInfo): File {
        val file = File(context.cacheDir, "lineage.apk").apply { writeText("apk") }
        val shadow = Shadows.shadowOf(context.packageManager)
        val setter = shadow.javaClass.methods.first { it.name == "setPackageArchiveInfo" }
        // Re-key the registration to this file's path.
        info.applicationInfo!!.sourceDir = file.absolutePath
        setter.invoke(shadow, file.absolutePath, info)
        return file
    }

    private companion object {
        val CERTS: Map<String, String> = mapOf(
            "a" to "MIICvTCCAaWgAwIBAgIED3lFmDANBgkqhkiG9w0BAQsFADAPMQ0wCwYDVQQDEwRUZXN0MB4XDTI2MTEwNzAyNTAyNFoXDTM2MTEwNDAyNTAyNFowDzENMAsGA1UEAxMEVGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALS7Wkz9gUndEJscrbEZgQHPQh7aVHnWFIXpw7yMZpLlI7QUBg8x8jZPJeHbWJXws/IW66dod0/QmqrG4y21i1KIQW5xwIPo3s3Igrs9ZnO05kd9Um9sgrcagAd4CjnUI0Um10AIogP6Bz6rUFL8WGDNvACq22bJDRaKVVIno7FuwgGQY+7Jrn73B4rQjyOM+PJtAnxNGBcDUVXa6CCGhU9DT/eYCtnjKlOVF8Pmflwe4LlrfYRqq7wJm2d+I6cZHwMX2coH9BwymuRCT4s6NnYkQZVYAvy8BFVI3jvK+Q259oDKDDZBLp+fHs9cUHJQ2TwnKH5rFH7tYXIwvkHgG5ECAwEAAaMhMB8wHQYDVR0OBBYEFM8ej83GSYbEXAs4JDW/shyQY5x1MA0GCSqGSIb3DQEBCwUAA4IBAQCboZxi2u+lovXniEQCy31XZhU+vKXr+u1lyHKREgy88rcDrXquapNJX0XpaJBSLFEc180j3Fr6vWst0JynJxjq7dFHqf7wDSLJQDysXEDEUr2+H6Edkjbw8Lju88uXrD7v7BgHSxsn1ffZ02FfzFKF6zC4M7pp2FkJj/1CdtW4JbfcH2RWqht8k7YSAvWgPm2hzroQzMHe3BxGsSj0t9+9F5GdBtZdnG+/i8iBcOvmk+r7p0xkWuH7qaZmcGqNrX9m31FnZMp9OWkwkCqc8yncKDJ89P810JAcVL6Fp0cBrUMACkNAH5nIpDxAfV1mDp0FW8mDYuW1qnNxg0zdPfWX",
            "b" to "MIICvzCCAaegAwIBAgIEXCEH0jANBgkqhkiG9w0BAQsFADAQMQ4wDAYDVQQDEwVUZXN0MjAeFw0yNjExMDcwMjUwMjVaFw0zNjExMDQwMjUwMjVaMBAxDjAMBgNVBAMTBVRlc3QyMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAhcKTzafPJY3s/Lmn4oc6r0yyGIuZPHLKaodIdnhPIMZrlaUN5J24zkdF46touNZaZ/KOmVbLCJ7Oj5QznnozVuPyh05xbLk75FIgnbVjCwP+ZeFTo+JaJIS7zHmlZqJZi2bxMfdkGjpndNBMhpy5Stg8r8vJiTo2PpaAIhc1bjoAJIUY/hljYcsYrcTOkybG6QZDCkeiy1mnvSOOWcQs1xiKPohnt0Wh44mtUjP/uKsHKRwZOa4XTbrjrZt++P8PNxqjeGIm9/koYjCjkqdgjKj6ggH2acd5VjvN5boiYl7iCrUjdJvm7Azuv7lNgYu8ANnD0h/0kezImuWsvLstpwIDAQABoyEwHzAdBgNVHQ4EFgQUxo5BgVKbWu/yEMy7hm6EbhYszqMwDQYJKoZIhvcNAQELBQADggEBABVaqNtWrWB4FYVVEx3AqDGhQMssAtc9L/kC2XacQzFPXGMKusHRQWj6TuGbblrUPnhuZjDg5gAVrk1+ImxU2TemfZTF/KtWhdNdmCxD4K7QkfEMw3Ecs8Wt/i+dhVOTF4Cg5BeK/DHTzdyJH8fZKWJOSxSc4eim3Q3k74ltC2+8aUDUTgnomX0hI2Msj/aBLTVR2qHUs+SAyYtAZHSZmDwe7lFS49msiD2sOK9tB2kq/sesTFS7Xb1zGz3hnmKrP/gt9urJrLOaU+8aDbf2sTsj4YYjvIxKKcZW0NSbEy1KDlLKwLcKsbGoAuMsxGNWo6CnYw6egezV+plq+c1cFhU=",
        )
    }
}
