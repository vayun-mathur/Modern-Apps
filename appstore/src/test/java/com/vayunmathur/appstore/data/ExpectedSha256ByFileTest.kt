package com.vayunmathur.appstore.data

import com.vayunmathur.appstore.data.installer.expectedSha256ByFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression: only the proprietary mirror's branch keyed split hashes into
 * the install requirement, so F-Droid/Izzy/Modern split siblings installed
 * with no hash check even though the signed index published one per file.
 */
class ExpectedSha256ByFileTest {

    private fun app(source: AppSource) = UnifiedApp(
        packageName = "com.example.app",
        source = source,
        name = "Example",
        apkSha256 = "basehash",
        splitFiles = listOf(
            SplitFile(fileName = "config.arm64.apk", url = "https://x/a", sha256 = "splithash0", size = 1),
            SplitFile(fileName = "config.en.apk", url = "https://x/b", sha256 = null, size = 1),
        ),
    )

    @Test
    fun `every url source hashes base and splits by staged name`() {
        val expected = mapOf(
            "com.example.app.apk" to "basehash",
            "com.example.app.split0.apk" to "splithash0",
        )
        for (source in listOf(AppSource.FDROID, AppSource.IZZY, AppSource.MODERN_APPS, AppSource.PROPRIETARY)) {
            assertEquals(expected, expectedSha256ByFile(app(source)), "source $source")
        }
    }

    @Test
    fun `no published hashes means no hash check`() {
        val bare = UnifiedApp(packageName = "com.example.app", source = AppSource.FDROID, name = "E")
        assertEquals(emptyMap(), expectedSha256ByFile(bare))
    }
}
