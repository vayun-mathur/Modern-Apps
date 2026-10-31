package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Installs read the app back from the Room catalogue, so split siblings dropped on the
 * way in leave a split-set install with only its base (INSTALL_FAILED_MISSING_SPLIT).
 */
class CachedAppSplitFilesTest {

    private val base = UnifiedApp(
        packageName = "com.google.android.gms",
        source = AppSource.PROPRIETARY,
        name = "Google Play services",
        apkUrl = "https://maproj.org/proprietary/repo/com.google.android.gms-264261035.apk",
    )

    @Test
    fun splitFilesSurviveRoundTrip() {
        val splits = listOf(
            SplitFile(
                fileName = "/com.google.android.gms/264261035/com.google.android.gms-264261035-config.en.apk",
                url = "https://maproj.org/proprietary/repo/com.google.android.gms-264261035-config.en.apk",
                sha256 = "f003c399fa6d671bcdf773de21910bd3ead73513f3df968f7255aba86f878369",
                size = 1425618L,
            ),
            SplitFile(
                fileName = "config.xxhdpi.apk",
                url = "https://example.org/config.xxhdpi.apk",
                sha256 = null,
                size = 0L,
            ),
        )

        val restored = base.copy(splitFiles = splits).toEntity().toUnifiedApp()

        assertEquals(splits, restored.splitFiles)
    }

    @Test
    fun monolithicAppStoresNoSplits() {
        val entity = base.toEntity()

        assertNull(entity.splitFiles)
        assertEquals(emptyList(), entity.toUnifiedApp().splitFiles)
    }
}
