package com.vayunmathur.appstore.data.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The verifier's stamp gate and its TV device-feature signal: an APK
 * with no stamp is refused when a stamp is required or was pinned,
 * and a declared feature the TV itself reports counts as a TV signal
 * even with no leanback-named activity.
 */
@RunWith(RobolectricTestRunner::class)
class InstallVerifierStampAndTvFeatureTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun signingInfo(cert: String): SigningInfo {
        val der = android.util.Base64.decode(CERT_A, android.util.Base64.DEFAULT)
        val info = SigningInfo()
        val shadow = Shadows.shadowOf(info)
        val setSignatures = shadow.javaClass
            .getDeclaredMethod("setSignatures", Array<Signature>::class.java)
        setSignatures.isAccessible = true
        setSignatures.invoke(shadow, arrayOf(Signature(der)))
        return info
    }

    private fun registerDownload(
        fileName: String,
        pkg: String,
        features: List<String> = emptyList(),
    ): File {
        val file = File(context.cacheDir, fileName).apply { writeText("apk-$fileName") }
        val info = PackageInfo().apply {
            packageName = pkg
            @Suppress("DEPRECATION")
            versionCode = 7
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                sourceDir = file.absolutePath
                targetSdkVersion = 35
            }
            signingInfo = signingInfo("a")
            if (features.isNotEmpty()) {
                reqFeatures = features.map { name ->
                    FeatureInfo().apply { this.name = name }
                }.toTypedArray()
            }
        }
        val shadow = Shadows.shadowOf(context.packageManager)
        val setter = shadow.javaClass.methods.first { it.name == "setPackageArchiveInfo" }
        setter.invoke(shadow, file.absolutePath, info)
        return file
    }

    @Test
    fun `an unstamped download is rejected when a stamp is required`() {
        val file = registerDownload("stamp-required.apk", "com.example.stampreq")
        val result = InstallVerifier.verify(
            context, listOf(file),
            InstallRequirement(expectedPackage = "com.example.stampreq", requireStamp = true),
        )
        val rejected = result as VerificationResult.Rejected
        assertTrue(rejected.reason.contains("no publisher stamp"), rejected.reason)
    }

    @Test
    fun `an unstamped download is rejected when a stamp was pinned before`() {
        val file = registerDownload("stamp-pinned.apk", "com.example.stamppin")
        val result = InstallVerifier.verify(
            context, listOf(file),
            InstallRequirement(
                expectedPackage = "com.example.stamppin",
                pinnedStamp = "ab".repeat(32),
            ),
        )
        val rejected = result as VerificationResult.Rejected
        assertTrue(rejected.reason.contains("publisher stamp is gone"), rejected.reason)
    }

    @Test
    fun `a declared feature the device also has counts as a tv signal`() {
        Shadows.shadowOf(context.packageManager).addSystemAvailableFeature(
            FeatureInfo().apply { name = "android.hardware.wifi" },
        )
        val file = registerDownload(
            "tv-feature.apk", "com.example.tvfeat",
            features = listOf("android.hardware.wifi"),
        )
        val result = InstallVerifier.verify(
            context, listOf(file),
            InstallRequirement(expectedPackage = "com.example.tvfeat", requireTvCompatible = true),
        )
        val verified = assertIs<VerificationResult.Verified>(result)
        assertTrue(verified.detail.contains("TV-compatible"), verified.detail)
    }

    @Test
    fun `a phone only feature is refused on tv even when the device lists other features`() {
        val file = registerDownload(
            "tv-phone.apk", "com.example.tvphone",
            features = listOf("android.hardware.telephony"),
        )
        val result = InstallVerifier.verify(
            context, listOf(file),
            InstallRequirement(expectedPackage = "com.example.tvphone", requireTvCompatible = true),
        )
        val rejected = result as VerificationResult.Rejected
        assertTrue(rejected.reason.contains("phone hardware"), rejected.reason)
    }

    private companion object {
        const val CERT_A =
            "MIICvTCCAaWgAwIBAgIED3lFmDANBgkqhkiG9w0BAQsFADAPMQ0wCwYDVQQDEwRUZXN0MB4XDTI2MTEwNzAyNTAyNFoXDTM2MTEwNDAyNTAyNFowDzENMAsGA1UEAxMEVGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALS7Wkz9gUndEJscrbEZgQHPQh7aVHnWFIXpw7yMZpLlI7QUBg8x8jZPJeHbWJXws/IW66dod0/QmqrG4y21i1KIQW5xwIPo3s3Igrs9ZnO05kd9Um9sgrcagAd4CjnUI0Um10AIogP6Bz6rUFL8WGDNvACq22bJDRaKVVIno7FuwgGQY+7Jrn73B4rQjyOM+PJtAnxNGBcDUVXa6CCGhU9DT/eYCtnjKlOVF8Pmflwe4LlrfYRqq7wJm2d+I6cZHwMX2coH9BwymuRCT4s6NnYkQZVYAvy8BFVI3jvK+Q259oDKDDZBLp+fHs9cUHJQ2TwnKH5rFH7tYXIwvkHgG5ECAwEAAaMhMB8wHQYDVR0OBBYEFM8ej83GSYbEXAs4JDW/shyQY5x1MA0GCSqGSIb3DQEBCwUAA4IBAQCboZxi2u+lovXniEQCy31XZhU+vKXr+u1lyHKREgy88rcDrXquapNJX0XpaJBSLFEc180j3Fr6vWst0JynJxjq7dFHqf7wDSLJQDysXEDEUr2+H6Edkjbw8Lju88uXrD7v7BgHSxsn1ffZ02FfzFKF6zC4M7pp2FkJj/1CdtW4JbfcH2RWqht8k7YSAvWgPm2hzroQzMHe3BxGsSj0t9+9F5GdBtZdnG+/i8iBcOvmk+r7p0xkWuH7qaZmcGqNrX9m31FnZMp9OWkwkCqc8yncKDJ89P810JAcVL6Fp0cBrUMACkNAH5nIpDxAfV1mDp0FW8mDYuW1qnNxg0zdPfWX"
    }
}
