package com.vayunmathur.library.downloadservice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [ModelUrls], the mirror-only catalog behind supply-chain mitigation #1.
 * Pure Kotlin with no Android dependency, so it runs on the plain host JVM.
 */
class ModelUrlsTest {

    private val shaShape = Regex("[0-9a-f]{64}")

    private fun allUrls() = ModelUrls.GemmaMaml.FILES.map { it.url }

    private fun allShas() = ModelUrls.GemmaMaml.FILES.map { it.sha256.orEmpty() }

    @Test
    fun mirror_base_is_https_and_ends_with_a_slash() {
        assertTrue(ModelUrls.MIRROR_BASE.startsWith("https://"))
        assertTrue(ModelUrls.MIRROR_BASE.endsWith("/"))
    }

    @Test
    fun every_url_lives_under_the_mirror_base() {
        for (url in allUrls()) {
            assertTrue(url.startsWith(ModelUrls.MIRROR_BASE), "$url is outside the mirror")
            assertFalse("huggingface" in url, "$url must not fall back to a third party")
        }
    }

    @Test
    fun gemma_maml_set_lives_in_its_own_mirror_and_disk_subdir() {
        assertEquals("${ModelUrls.MIRROR_BASE}gemma4/", ModelUrls.GemmaMaml.BASE)
        assertEquals(
            listOf(
                "gemma4_text.maml", "gemma4_embed.maml", "gemma4_head.maml",
                "gemma4_vision.maml", "gemma4_audio.maml", "gemma4_tokenizer.spm1",
            ),
            ModelUrls.GemmaMaml.FILES.map { it.fileName.removePrefix("${ModelUrls.GemmaMaml.DIR}/") },
        )
        for (item in ModelUrls.GemmaMaml.FILES) {
            assertTrue(item.fileName.startsWith("gemma4/"), item.fileName)
            assertEquals(item.url.removePrefix(ModelUrls.GemmaMaml.BASE), item.fileName.removePrefix("gemma4/"))
        }
    }

    @Test
    fun initial_bundle_is_the_gemma_set_with_distinct_files() {
        assertEquals(ModelUrls.GemmaMaml.FILES, ModelUrls.INITIAL)
        val files = ModelUrls.INITIAL.map { it.fileName }
        assertEquals(files.size, files.toSet().size, "duplicate on-disk file names would overwrite each other")
    }

    @Test
    fun every_pinned_sha_is_a_64_char_lowercase_hex_digest() {
        for (sha in allShas()) {
            assertTrue(shaShape.matches(sha), "$sha is not a SHA-256 hex digest")
        }
    }

    @Test
    fun every_bundled_item_has_a_non_blank_description() {
        for (item in ModelUrls.INITIAL) {
            assertTrue(item.description.isNotBlank(), "${item.fileName} needs a display label")
            assertTrue(item.fileName.isNotBlank())
        }
    }

    @Test
    fun item_sha_defaults_to_null_for_unpinned_files() {
        val tokenizer = ModelUrls.GemmaMaml.FILES.last()
        assertNull(ModelDownloadItem(tokenizer.url, tokenizer.fileName, "Model").sha256)
    }

    @Test
    fun items_compare_by_value() {
        val tokenizer = ModelUrls.GemmaMaml.FILES.last()
        assertEquals(tokenizer, tokenizer.copy())
        assertFalse(tokenizer == tokenizer.copy(url = "${ModelUrls.MIRROR_BASE}other.bin"))
    }
}
