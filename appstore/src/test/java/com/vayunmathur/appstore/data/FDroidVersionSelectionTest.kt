package com.vayunmathur.appstore.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * F-Droid publishes some apps as one APK per architecture within a single release, all
 * sharing the same `added` timestamp. Handing the wrong one to the installer produces a
 * failure only Android can detect, at the very end of the install, so this selection is
 * the only place the mistake can be caught.
 */
class FDroidVersionSelectionTest {

    private val arm64Device = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private fun version(
        added: Long,
        fileName: String,
        versionCode: Long = 1L,
        nativeCode: List<String> = emptyList(),
        requiredSplitTypes: List<String> = emptyList(),
        size: Long = 0L,
    ) = FDroidRepository.VersionCandidate(
        added = added,
        fileName = fileName,
        versionCode = versionCode,
        nativeCode = nativeCode,
        requiredSplitTypes = requiredSplitTypes,
        size = size,
    )

    @Test
    fun `picks the variant matching the device architecture`() {
        val release = listOf(
            version(200, "/app_x86_64.apk", versionCode = 14, nativeCode = listOf("x86_64")),
            version(200, "/app_arm64.apk", versionCode = 12, nativeCode = listOf("arm64-v8a")),
            version(200, "/app_armv7.apk", versionCode = 13, nativeCode = listOf("armeabi-v7a")),
        )

        assertEquals("/app_arm64.apk", FDroidRepository.selectVersion(release, arm64Device)?.fileName)
    }

    @Test
    fun `prefers the primary ABI over a secondary one the device also supports`() {
        val release = listOf(
            version(200, "/app_armv7.apk", nativeCode = listOf("armeabi-v7a")),
            version(200, "/app_arm64.apk", nativeCode = listOf("arm64-v8a")),
        )

        assertEquals("/app_arm64.apk", FDroidRepository.selectVersion(release, arm64Device)?.fileName)
    }

    @Test
    fun `prefers an architecture-specific build over a universal one`() {
        val release = listOf(
            version(200, "/app_universal.apk"),
            version(200, "/app_arm64.apk", nativeCode = listOf("arm64-v8a")),
        )

        assertEquals("/app_arm64.apk", FDroidRepository.selectVersion(release, arm64Device)?.fileName)
    }

    @Test
    fun `falls back to the universal build when no variant matches`() {
        val release = listOf(
            version(200, "/app_x86_64.apk", nativeCode = listOf("x86_64")),
            version(200, "/app_universal.apk"),
        )

        assertEquals("/app_universal.apk", FDroidRepository.selectVersion(release, arm64Device)?.fileName)
    }

    @Test
    fun `newest release still wins over an older one`() {
        val versions = listOf(
            version(100, "/app_old_arm64.apk", nativeCode = listOf("arm64-v8a")),
            version(200, "/app_new_arm64.apk", nativeCode = listOf("arm64-v8a")),
        )

        assertEquals("/app_new_arm64.apk", FDroidRepository.selectVersion(versions, arm64Device)?.fileName)
    }

    @Test
    fun `falls back to an older release when the newest has no build for this device`() {
        val versions = listOf(
            version(100, "/app_old_arm64.apk", nativeCode = listOf("arm64-v8a")),
            version(200, "/app_new_x86.apk", nativeCode = listOf("x86_64")),
        )

        assertEquals("/app_old_arm64.apk", FDroidRepository.selectVersion(versions, arm64Device)?.fileName)
    }

    @Test
    fun `drops a package with no runnable build at all`() {
        val versions = listOf(
            version(200, "/app_x86_64.apk", nativeCode = listOf("x86_64")),
            version(100, "/app_x86.apk", nativeCode = listOf("x86")),
        )

        assertNull(FDroidRepository.selectVersion(versions, arm64Device))
    }

    @Test
    fun `drops a package with no versions`() {
        assertNull(FDroidRepository.selectVersion(emptyList(), arm64Device))
    }

    @Test
    fun `treats a multi-ABI build as matching on its best ABI`() {
        val release = listOf(
            version(200, "/app_armv7_only.apk", nativeCode = listOf("armeabi-v7a")),
            version(200, "/app_arm.apk", nativeCode = listOf("armeabi-v7a", "arm64-v8a")),
        )

        assertEquals("/app_arm.apk", FDroidRepository.selectVersion(release, arm64Device)?.fileName)
    }

    @Test
    fun `prefers a split-set base over a same-version module with no split types`() {
        // GMS (issue: "not a valid signed app file"): the base and its Dynamite
        // modules share one versionCode and ABI rank, so everything ties and the
        // encounter order (index hash order) used to elect a module.
        val release = listOf(
            version(200, "/gms/module-CronetDynamite_installtime.apk", nativeCode = listOf("arm64-v8a")),
            version(200, "/gms/base.apk", nativeCode = listOf("arm64-v8a"), requiredSplitTypes = listOf("base__density")),
        )

        assertEquals("/gms/base.apk", FDroidRepository.selectVersion(release, arm64Device)?.fileName)
    }

    @Test
    fun `prefers the larger file when split types also tie`() {
        val release = listOf(
            version(200, "/app_small.apk", nativeCode = listOf("arm64-v8a")),
            version(200, "/app_big.apk", nativeCode = listOf("arm64-v8a")),
        ).map {
            // Sizes stand in for base (192MB) vs module (12MB) when an index
            // hasn't recorded split types on either side.
            if (it.fileName.contains("big")) it.copy(size = 192_000_000L) else it.copy(size = 12_000_000L)
        }

        assertEquals("/app_big.apk", FDroidRepository.selectVersion(release, arm64Device)?.fileName)
    }

    @Test
    fun `identifies Dynamite runtime modules by the installtime infix`() {
        assertEquals(true, FDroidRepository.isDynamiteModule("/gms/264230035/gms-264230035-CronetDynamite_installtime.apk"))
        assertEquals(true, FDroidRepository.isDynamiteModule("/gms/CronetDynamite_INSTALLTIME.apk"))
        assertEquals(false, FDroidRepository.isDynamiteModule("/gms/264230035/gms-264230035.apk"))
        assertEquals(false, FDroidRepository.isDynamiteModule("/gms/264230035/gms-264230035-config.en.apk"))
    }
}
