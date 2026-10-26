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

    private fun allUrls() = ModelUrls.GemmaMaml.FILES.map { it.url } + listOf(
        ModelUrls.SIGLIP_VISION_URL,
        ModelUrls.SIGLIP_TEXT_URL,
        ModelUrls.SIGLIP_TOKENIZER_URL,
    )

    private fun allShas() = ModelUrls.GemmaMaml.FILES.map { it.sha256.orEmpty() } + listOf(
        ModelUrls.SIGLIP_VISION_SHA256,
        ModelUrls.SIGLIP_TEXT_SHA256,
        ModelUrls.SIGLIP_TOKENIZER_SHA256,
    )

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
    fun siglip_mirror_paths_match_their_on_disk_file_names() {
        assertEquals(ModelUrls.SIGLIP_VISION_FILE, ModelUrls.SIGLIP_VISION_URL.removePrefix(ModelUrls.MIRROR_BASE))
        assertEquals(ModelUrls.SIGLIP_TEXT_FILE, ModelUrls.SIGLIP_TEXT_URL.removePrefix(ModelUrls.MIRROR_BASE))
        assertEquals(
            ModelUrls.SIGLIP_TOKENIZER_FILE,
            ModelUrls.SIGLIP_TOKENIZER_URL.removePrefix(ModelUrls.MIRROR_BASE),
        )
    }

    @Test
    fun siglip_bundle_holds_the_three_search_models() {
        assertEquals(3, ModelUrls.SIGLIP.size)
        assertEquals(
            listOf(ModelUrls.SIGLIP_VISION, ModelUrls.SIGLIP_TEXT, ModelUrls.SIGLIP_TOKENIZER),
            ModelUrls.SIGLIP,
        )
    }

    @Test
    fun initial_bundle_is_gemma_first_then_siglip_with_distinct_files() {
        assertEquals(9, ModelUrls.INITIAL.size)
        assertEquals(ModelUrls.GemmaMaml.FILES, ModelUrls.INITIAL.take(6))
        assertEquals(ModelUrls.SIGLIP, ModelUrls.INITIAL.drop(6))
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
        assertNull(ModelDownloadItem(ModelUrls.SIGLIP_TEXT_URL, ModelUrls.SIGLIP_TEXT_FILE, "Model").sha256)
        assertEquals(ModelUrls.SIGLIP_TEXT_SHA256, ModelUrls.SIGLIP_TEXT.sha256)
    }

    @Test
    fun items_compare_by_value() {
        assertEquals(ModelUrls.SIGLIP_TEXT, ModelUrls.SIGLIP_TEXT.copy())
        assertFalse(ModelUrls.SIGLIP_TEXT == ModelUrls.SIGLIP_TEXT.copy(url = "${ModelUrls.MIRROR_BASE}other.bin"))
    }
}
