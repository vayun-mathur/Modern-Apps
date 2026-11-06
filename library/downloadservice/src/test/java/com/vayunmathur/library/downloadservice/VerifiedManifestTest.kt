package com.vayunmathur.library.downloadservice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the host-safe launch gate behind #901: [specsToManifest] plus
 * [manifestSatisfied].
 *
 * The gate must never treat an unverified file as complete — a truncated file
 * (wrong size), a stale one (pinned hash bumped, stored `verified_*` marker
 * now stale), or a never-verified one (file copied in by hand, marker absent)
 * all read as incomplete so the download screen re-fetches them. Pure Kotlin
 * with no Android dependency, so it runs on the plain host JVM.
 */
class VerifiedManifestTest {

    private fun spec(
        fileName: String,
        sha256: String? = "aa".repeat(32),
        expectedBytes: Long? = 8L,
    ) = DownloadSpec(
        fileName = fileName,
        description = fileName,
        url = "https://data.maproj.org/models/$fileName",
        sha256 = sha256,
        expectedBytes = expectedBytes,
    )

    private fun staged(vararg names: String): File {
        val dir = kotlin.io.path.createTempDirectory("manifest_gate").toFile()
        for (name in names) {
            val file = File(dir, name)
            file.parentFile?.mkdirs()
            // 8 bytes, matching the default expectedBytes above.
            file.writeBytes(ByteArray(8))
        }
        return dir
    }

    @Test
    fun specs_map_to_entries_verbatim() {
        val manifest = specsToManifest(listOf(spec("gemma4/a.bin"), spec("b.bin", null, null)))
        assertEquals(
            listOf(
                VerifiedEntry("gemma4/a.bin", "aa".repeat(32), 8L),
                VerifiedEntry("b.bin", null, null),
            ),
            manifest.entries,
        )
    }

    @Test
    fun all_verified_passes() {
        val dir = staged("gemma4/a.bin")
        val manifest = specsToManifest(listOf(spec("gemma4/a.bin")))
        val markers = mapOf("verified_gemma4/a.bin" to "aa".repeat(32))
        assertTrue(manifestSatisfied(dir, markers::getValue, manifest))
    }

    @Test
    fun missing_file_fails() {
        val dir = staged()
        val manifest = specsToManifest(listOf(spec("gemma4/a.bin")))
        assertFalse(manifestSatisfied(dir, { null }, manifest))
    }

    @Test
    fun null_dir_fails() {
        val manifest = specsToManifest(listOf(spec("a.bin")))
        assertFalse(manifestSatisfied(null, { null }, manifest))
    }

    @Test
    fun short_file_fails_even_when_previously_verified() {
        val dir = staged("a.bin")
        File(dir, "a.bin").writeBytes(ByteArray(4))
        val manifest = specsToManifest(listOf(spec("a.bin")))
        val markers = mapOf("verified_a.bin" to "aa".repeat(32))
        assertFalse(manifestSatisfied(dir, markers::getValue, manifest))
    }

    @Test
    fun stale_marker_after_a_hash_bump_fails() {
        val dir = staged("a.bin")
        val manifest = specsToManifest(listOf(spec("a.bin", sha256 = "bb".repeat(32))))
        val markers = mapOf("verified_a.bin" to "aa".repeat(32))
        assertFalse(manifestSatisfied(dir, markers::getValue, manifest))
    }

    @Test
    fun hand_placed_file_without_a_marker_fails() {
        val dir = staged("a.bin")
        val manifest = specsToManifest(listOf(spec("a.bin")))
        assertFalse(manifestSatisfied(dir, { null }, manifest))
    }

    @Test
    fun unpinned_file_passes_on_existence() {
        val dir = staged("archive.bin")
        val manifest = specsToManifest(listOf(spec("archive.bin", null, null)))
        assertTrue(manifestSatisfied(dir, { null }, manifest))
    }

    @Test
    fun size_only_pin_still_catches_truncation() {
        val dir = staged("archive.bin")
        File(dir, "archive.bin").writeBytes(ByteArray(4))
        val manifest = specsToManifest(listOf(spec("archive.bin", null, 8L)))
        assertFalse(manifestSatisfied(dir, { null }, manifest))
    }

    @Test
    fun size_ok_accepts_exact_and_unpinned() {
        val dir = staged("a.bin")
        val file = File(dir, "a.bin")
        assertTrue(sizeOk(file, 8L))
        assertFalse(sizeOk(file, 9L))
        assertTrue(sizeOk(file, null))
        assertFalse(sizeOk(File(dir, "missing.bin"), 8L))
    }
}
