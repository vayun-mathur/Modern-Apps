package com.vayunmathur.appstore.data.security

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The pre-install gate. Robolectric cannot parse a real APK from disk, so
 * each downloaded file is backed by a PackageInfo registered with the
 * shadow PackageManager for that path (see [registerApk]) — the verifier
 * then runs its real package/hash/signer/TV/rollback checks against it.
 */
@RunWith(RobolectricTestRunner::class)
class InstallVerifierTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun signingInfo(cert: String): SigningInfo {
        val der = android.util.Base64.decode(CERTS.getValue(cert), android.util.Base64.DEFAULT)
        val info = SigningInfo()
        // Robolectric shadows SigningInfo; its signers live in the shadow.
        val setSignatures = Shadows.shadowOf(info).javaClass
            .getDeclaredMethod("setSignatures", Array<Signature>::class.java)
        setSignatures.isAccessible = true
        setSignatures.invoke(Shadows.shadowOf(info), arrayOf(Signature(der)))
        return info
    }

    /** A fake downloaded APK file whose archive info the shadow PM serves. */
    private fun registerApk(
        fileName: String,
        pkg: String = "com.example.fake",
        versionCode: Int = 7,
        targetSdk: Int = 35,
        cert: String = "a",
        features: List<String> = emptyList(),
        activities: List<String> = emptyList(),
        archiveOnly: Boolean = false,
    ): File {
        val file = File(context.cacheDir, fileName).apply { writeText("apk-bytes-$fileName") }
        val info = PackageInfo().apply {
            packageName = pkg
            @Suppress("DEPRECATION")
            this.versionCode = versionCode
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                sourceDir = file.absolutePath
                targetSdkVersion = targetSdk
            }
            signingInfo = signingInfo(cert)
            if (features.isNotEmpty()) {
                reqFeatures = features.map { FeatureInfo().apply { name = it } }.toTypedArray()
            }
            if (activities.isNotEmpty()) {
                this.activities = activities.map { ActivityInfo().apply { name = it } }.toTypedArray()
            }
        }
        val shadow = Shadows.shadowOf(context.packageManager)
        if (archiveOnly) {
            // A second file of the same package must not replace the first
            // file's archive info in the installed-package registry.
            val setter = shadow.javaClass.methods.first { it.name == "setPackageArchiveInfo" }
            setter.invoke(shadow, file.absolutePath, info)
        } else {
            shadow.addPackage(info)
        }
        return file
    }

    private companion object {
        // Real self-signed X.509 certificates (DER, base64): Signature()
        // parses the bytes, so arbitrary strings are not accepted.
        val CERTS: Map<String, String> = mapOf(
            "a" to "MIICvTCCAaWgAwIBAgIED3lFmDANBgkqhkiG9w0BAQsFADAPMQ0wCwYDVQQDEwRUZXN0MB4XDTI2MTEwNzAyNTAyNFoXDTM2MTEwNDAyNTAyNFowDzENMAsGA1UEAxMEVGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALS7Wkz9gUndEJscrbEZgQHPQh7aVHnWFIXpw7yMZpLlI7QUBg8x8jZPJeHbWJXws/IW66dod0/QmqrG4y21i1KIQW5xwIPo3s3Igrs9ZnO05kd9Um9sgrcagAd4CjnUI0Um10AIogP6Bz6rUFL8WGDNvACq22bJDRaKVVIno7FuwgGQY+7Jrn73B4rQjyOM+PJtAnxNGBcDUVXa6CCGhU9DT/eYCtnjKlOVF8Pmflwe4LlrfYRqq7wJm2d+I6cZHwMX2coH9BwymuRCT4s6NnYkQZVYAvy8BFVI3jvK+Q259oDKDDZBLp+fHs9cUHJQ2TwnKH5rFH7tYXIwvkHgG5ECAwEAAaMhMB8wHQYDVR0OBBYEFM8ej83GSYbEXAs4JDW/shyQY5x1MA0GCSqGSIb3DQEBCwUAA4IBAQCboZxi2u+lovXniEQCy31XZhU+vKXr+u1lyHKREgy88rcDrXquapNJX0XpaJBSLFEc180j3Fr6vWst0JynJxjq7dFHqf7wDSLJQDysXEDEUr2+H6Edkjbw8Lju88uXrD7v7BgHSxsn1ffZ02FfzFKF6zC4M7pp2FkJj/1CdtW4JbfcH2RWqht8k7YSAvWgPm2hzroQzMHe3BxGsSj0t9+9F5GdBtZdnG+/i8iBcOvmk+r7p0xkWuH7qaZmcGqNrX9m31FnZMp9OWkwkCqc8yncKDJ89P810JAcVL6Fp0cBrUMACkNAH5nIpDxAfV1mDp0FW8mDYuW1qnNxg0zdPfWX",
            "b" to "MIICvzCCAaegAwIBAgIEXCEH0jANBgkqhkiG9w0BAQsFADAQMQ4wDAYDVQQDEwVUZXN0MjAeFw0yNjExMDcwMjUwMjVaFw0zNjExMDQwMjUwMjVaMBAxDjAMBgNVBAMTBVRlc3QyMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAhcKTzafPJY3s/Lmn4oc6r0yyGIuZPHLKaodIdnhPIMZrlaUN5J24zkdF46touNZaZ/KOmVbLCJ7Oj5QznnozVuPyh05xbLk75FIgnbVjCwP+ZeFTo+JaJIS7zHmlZqJZi2bxMfdkGjpndNBMhpy5Stg8r8vJiTo2PpaAIhc1bjoAJIUY/hljYcsYrcTOkybG6QZDCkeiy1mnvSOOWcQs1xiKPohnt0Wh44mtUjP/uKsHKRwZOa4XTbrjrZt++P8PNxqjeGIm9/koYjCjkqdgjKj6ggH2acd5VjvN5boiYl7iCrUjdJvm7Azuv7lNgYu8ANnD0h/0kezImuWsvLstpwIDAQABoyEwHzAdBgNVHQ4EFgQUxo5BgVKbWu/yEMy7hm6EbhYszqMwDQYJKoZIhvcNAQELBQADggEBABVaqNtWrWB4FYVVEx3AqDGhQMssAtc9L/kC2XacQzFPXGMKusHRQWj6TuGbblrUPnhuZjDg5gAVrk1+ImxU2TemfZTF/KtWhdNdmCxD4K7QkfEMw3Ecs8Wt/i+dhVOTF4Cg5BeK/DHTzdyJH8fZKWJOSxSc4eim3Q3k74ltC2+8aUDUTgnomX0hI2Msj/aBLTVR2qHUs+SAyYtAZHSZmDwe7lFS49msiD2sOK9tB2kq/sesTFS7Xb1zGz3hnmKrP/gt9urJrLOaU+8aDbf2sTsj4YYjvIxKKcZW0NSbEy1KDlLKwLcKsbGoAuMsxGNWo6CnYw6egezV+plq+c1cFhU=",
        )
    }

    private fun requirement(
        pkg: String = "com.example.fake",
        sha: Map<String, String> = emptyMap(),
        signers: Set<String> = emptySet(),
        minVersionCode: Long? = null,
        requireStamp: Boolean = false,
        pinnedStamp: String? = null,
        requireTvCompatible: Boolean = false,
    ) = InstallRequirement(
        expectedPackage = pkg,
        expectedSha256 = sha,
        requiredSigners = signers,
        signerOrigin = "the test source",
        minVersionCode = minVersionCode,
        requireStamp = requireStamp,
        pinnedStamp = pinnedStamp,
        requireTvCompatible = requireTvCompatible,
    )

    @Test
    fun `no files is rejected`() {
        val result = InstallVerifier.verify(context, emptyList(), requirement())
        assertIs<VerificationResult.Rejected>(result)
        assertFalse(result.allowsInstall)
    }

    @Test
    fun `a file that is not a readable apk is rejected`() {
        val junk = File(context.cacheDir, "junk.apk").apply { writeText("not an apk") }
        val result = InstallVerifier.verify(context, listOf(junk), requirement())
        assertIs<VerificationResult.Rejected>(result)
        assertTrue(result.reason.contains("not a valid"))
    }

    @Test
    fun `an apk for a different package is rejected`() {
        val apk = registerApk("wrong-pkg.apk")
        val result = InstallVerifier.verify(context, listOf(apk), requirement(pkg = "com.example.other"))
        assertIs<VerificationResult.Rejected>(result)
        assertTrue(result.reason.contains("com.example.other"))
    }

    @Test
    fun `a signed apk with nothing published to check is not rejected`() {
        val apk = registerApk("plain.apk")
        val result = InstallVerifier.verify(context, listOf(apk), requirement())
        assertTrue(result.allowsInstall, "unexpected: $result")
    }

    @Test
    fun `matching published hash verifies`() {
        val apk = registerApk("hashed.apk")
        val hash = ApkCertificates.sha256(apk)
        val result = InstallVerifier.verify(context, listOf(apk), requirement(sha = mapOf(apk.name to hash)))
        assertIs<VerificationResult.Verified>(result)
        assertTrue(result.detail.contains("hash matches"))
    }

    @Test
    fun `mismatched published hash is rejected`() {
        val apk = registerApk("badhash.apk")
        val result = InstallVerifier.verify(
            context, listOf(apk), requirement(sha = mapOf(apk.name to "0".repeat(64))),
        )
        assertIs<VerificationResult.Rejected>(result)
        assertTrue(result.reason.contains("hash"))
    }

    @Test
    fun `hashes covering none of the downloaded files are rejected`() {
        val apk = registerApk("uncovered.apk")
        val result = InstallVerifier.verify(
            context, listOf(apk), requirement(sha = mapOf("other.apk" to "0".repeat(64))),
        )
        assertIs<VerificationResult.Rejected>(result)
        assertTrue(result.reason.contains("do not cover"))
    }

    @Test
    fun `the apk signer satisfies a matching requirement and not a foreign one`() {
        val apk = registerApk("signed.apk", cert = "a")
        val signers = ApkCertificates.apkSigners(context, apk)
        assertTrue(signers.isNotEmpty(), "registered APK must report a signer")
        val der = android.util.Base64.decode(CERTS.getValue("a"), android.util.Base64.DEFAULT)
        assertEquals(setOf(ApkCertificates.sha256(der)), signers)

        val ok = InstallVerifier.verify(context, listOf(apk), requirement(signers = signers))
        assertIs<VerificationResult.Verified>(ok)
        assertTrue(ok.detail.contains("test source"))

        val bad = InstallVerifier.verify(context, listOf(apk), requirement(signers = setOf("f".repeat(64))))
        assertIs<VerificationResult.Rejected>(bad)
        assertTrue(bad.reason.contains("test source"))
    }

    @Test
    fun `files signed by different keys are rejected`() {
        val base = registerApk("multi-base.apk", cert = "a")
        val split = registerApk("multi-split.apk", cert = "b", archiveOnly = true)
        val result = InstallVerifier.verify(context, listOf(base, split), requirement())
        assertIs<VerificationResult.Rejected>(result)
        assertTrue(result.reason.contains("same key"))
    }

    @Test
    fun `rollback floor above the apk version is rejected and at it passes`() {
        val apk = registerApk("rollback.apk", versionCode = 7)
        val bad = InstallVerifier.verify(context, listOf(apk), requirement(minVersionCode = 100L))
        assertIs<VerificationResult.Rejected>(bad)
        assertTrue(bad.reason.contains("minimum"))

        val ok = InstallVerifier.verify(context, listOf(apk), requirement(minVersionCode = 7L))
        assertIs<VerificationResult.Verified>(ok)
        assertTrue(ok.detail.contains("at or above the minimum"))
    }

    @Test
    fun `an apk targeting an old android version is rejected`() {
        val apk = registerApk("oldsdk.apk", targetSdk = 20)
        val result = InstallVerifier.verify(context, listOf(apk), requirement())
        assertIs<VerificationResult.Rejected>(result)
        assertTrue(result.reason.contains("API 20"))
    }

    @Test
    fun `requiring a stamp the apk does not carry is rejected`() {
        val apk = registerApk("nostamp.apk")
        val result = InstallVerifier.verify(context, listOf(apk), requirement(requireStamp = true))
        assertIs<VerificationResult.Rejected>(result)
        assertTrue(result.reason.contains("stamp"))
    }

    @Test
    fun `a pinned stamp that has disappeared is rejected`() {
        val apk = registerApk("stampgone.apk")
        val result = InstallVerifier.verify(
            context, listOf(apk), requirement(pinnedStamp = "e".repeat(64)),
        )
        assertIs<VerificationResult.Rejected>(result)
        assertTrue(result.reason.contains("stamp"))
    }

    @Test
    fun `tv gate refuses phone hardware and apps with no tv entry point`() {
        val phone = registerApk("tv-phone.apk", features = listOf("android.hardware.telephony"))
        val phoneResult = InstallVerifier.verify(context, listOf(phone), requirement(requireTvCompatible = true))
        assertIs<VerificationResult.Rejected>(phoneResult)
        assertTrue(phoneResult.reason.contains("phone hardware"))

        val noEntry = registerApk("tv-noentry.apk")
        val noEntryResult = InstallVerifier.verify(context, listOf(noEntry), requirement(requireTvCompatible = true))
        assertIs<VerificationResult.Rejected>(noEntryResult)
        assertTrue(noEntryResult.reason.contains("no TV entry point"))
    }

    @Test
    fun `tv gate accepts a leanback app`() {
        val apk = registerApk("tv-ok.apk", features = listOf("android.software.leanback"))
        val result = InstallVerifier.verify(context, listOf(apk), requirement(requireTvCompatible = true))
        assertIs<VerificationResult.Verified>(result)
        assertTrue(result.detail.contains("TV-compatible"))
    }

    @Test
    fun `verification result stamp and allowsInstall semantics`() {
        assertEquals("abc", VerificationResult.Verified("ok", stampToPin = "abc").stamp)
        assertEquals("def", VerificationResult.Unverified("nope", stampToPin = "def").stamp)
        assertEquals(null, VerificationResult.Rejected("bad").stamp)
        assertTrue(VerificationResult.Verified("ok").allowsInstall)
        assertTrue(VerificationResult.Unverified("nope").allowsInstall)
        assertFalse(VerificationResult.Rejected("bad").allowsInstall)
        assertEquals(
            InstallRequirement(expectedPackage = "com.example.x").requiredSigners,
            emptySet<String>(),
        )
    }
}
