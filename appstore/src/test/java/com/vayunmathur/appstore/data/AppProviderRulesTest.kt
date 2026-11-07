package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Catalogue-wide listing rules: the minimum target-SDK gate and the
 * TV-compat filter. Both are pure functions over the listing rows, so
 * they pin here without Robolectric.
 */
class AppProviderRulesTest {

    private fun app(
        pkg: String = "com.example.app",
        targetSdk: Int? = null,
        tvCompatible: Boolean? = null,
        requiredFeatures: List<String> = emptyList(),
    ) = UnifiedApp(
        packageName = pkg,
        source = AppSource.FDROID,
        name = pkg,
        targetSdk = targetSdk,
        tvCompatible = tvCompatible,
        requiredFeatures = requiredFeatures,
    )

    @Test
    fun `the floor is enforced against the source-claimed target sdk`() {
        assertEquals(AppProvider.MIN_TARGET_SDK, 35)

        val kept = listOf(
            app("modern", targetSdk = 35),
            app("newer", targetSdk = 36),
            app("unknown", targetSdk = null),
        )
        val dropped = app("old", targetSdk = 34)

        assertEquals(kept.map { it.packageName }, AppProvider.filterTargetSdk(kept + dropped).map { it.packageName })
    }

    @Test
    fun `phones keep every listing`() {
        val apps = listOf(
            app("a", tvCompatible = false),
            app("b", requiredFeatures = listOf("android.hardware.telephony")),
        )

        assertEquals(apps, AppProvider.filterTvCompatible(false, apps))
    }

    @Test
    fun `tvs hide explicit negative verdicts`() {
        val apps = listOf(
            app("bad", tvCompatible = false),
            app("unknown", tvCompatible = null),
            app("good", tvCompatible = true),
        )

        assertEquals(
            listOf("unknown", "good"),
            AppProvider.filterTvCompatible(true, apps).map { it.packageName },
        )
    }

    @Test
    fun `tvs hide phone-hardware listings`() {
        val apps = listOf(
            app("dialer", requiredFeatures = listOf("android.hardware.telephony")),
            app("plain", requiredFeatures = emptyList()),
        )

        assertEquals(
            listOf("plain"),
            AppProvider.filterTvCompatible(true, apps).map { it.packageName },
        )
    }

    @Test
    fun `phone-hardware matching is case-insensitive substring`() {
        assertTrue(AppProvider.requiresPhoneHardware(listOf("ANDROID.HARDWARE.TELEPHONY")))
        assertTrue(AppProvider.requiresPhoneHardware(listOf("android.hardware.telephony")))
        assertTrue(AppProvider.requiresPhoneHardware(listOf("android.hardware.camera.flash")))
        assertTrue(AppProvider.requiresPhoneHardware(listOf("android.hardware.sensor.barometer")))
        assertFalse(AppProvider.requiresPhoneHardware(listOf("android.hardware.touchscreen")))
        assertFalse(AppProvider.requiresPhoneHardware(listOf("android.software.leanback")))
        assertFalse(AppProvider.requiresPhoneHardware(emptyList()))
    }

    @Test
    fun `leanback constant matches the platform feature name`() {
        assertEquals("android.software.leanback", AppProvider.FEATURE_LEANBACK)
    }
}
