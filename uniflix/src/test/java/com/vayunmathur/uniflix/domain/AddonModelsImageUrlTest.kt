package com.vayunmathur.uniflix.domain

import com.vayunmathur.uniflix.data.AddonStreamProvider
import com.vayunmathur.uniflix.data.TmdbImageUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure mapping helpers: addon stream ids, stream/subtitle conversion, image URLs, base-URL rules. */
class AddonModelsImageUrlTest {

    @Test fun streamIdsForMoviesAndTv() {
        assertEquals("tmdb:42", addonStreamId(42, MediaType.MOVIE))
        assertEquals("tmdb:42", addonStreamId(42, MediaType.MOVIE, season = 1, episode = 2))
        assertEquals("tmdb:7:2:5", addonStreamId(7, MediaType.TV, season = 2, episode = 5))
        assertEquals("tmdb:7:0:0", addonStreamId(7, MediaType.TV))
        assertEquals("movie", addonResourceType(MediaType.MOVIE))
        assertEquals("series", addonResourceType(MediaType.TV))
    }

    @Test fun streamToVideoSourceTitlesAndSubtitles() {
        val src = AddonStream(
            url = "https://cdn.example/v.m3u8", name = "  ",
            subtitles = listOf(
                AddonSubtitle(url = "https://cdn.example/en.vtt", lang = "en", id = "eng"),
                AddonSubtitle(url = "", lang = "fr"),
                AddonSubtitle(url = "https://cdn.example/x.vtt", lang = "", id = null)),
        ).toVideoSource("Fallback")
        assertEquals("https://cdn.example/v.m3u8", src!!.streamUrl)
        // Blank name falls back to the addon display name.
        assertEquals("Fallback", src.title)
        assertEquals(2, src.subtitles.size)
        assertEquals("en", src.subtitles[0].language)
        assertEquals("eng", src.subtitles[0].displayName)
        // No lang and no id defaults the language to en with blank display.
        assertEquals("en", src.subtitles[1].language)
        assertEquals("", src.subtitles[1].displayName)
        val named = AddonStream(url = "u", name = "HD").toVideoSource("F")
        assertEquals("HD", named!!.title)
    }

    @Test fun blankUrlsAreDropped() {
        assertNull(AddonStream(url = "  ").toVideoSource("F"))
        assertNull(AddonSubtitle(url = "").toSubtitleTrack())
        val track = AddonSubtitle(url = "u", lang = "", id = "spa").toSubtitleTrack()
        assertEquals("spa", track!!.language)
        assertEquals("spa", track.displayName)
    }

    @Test fun streamsResponseDefaultsToEmpty() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString(AddonStreamsResponse.serializer(), "{}").streams.isEmpty())
        val parsed = json.decodeFromString(
            AddonStreamsResponse.serializer(), """{"streams":[{"url":"u","name":"n"}]}""")
        assertEquals("u", parsed.streams.single().url)
        assertEquals(false, AddonBehaviorHints().notWebReady)
    }

    @Test fun mediaModelDefaults() {
        val item = MediaItem(tmdbId = 1, mediaType = MediaType.MOVIE, title = "T")
        assertEquals("", item.overview)
        assertEquals(0.0, item.voteAverage)
        assertNull(item.year)
        val detail = MediaDetail(tmdbId = 2, mediaType = MediaType.TV, title = "S")
        assertTrue(detail.seasons.isEmpty() && detail.recommendations.isEmpty())
        assertEquals(8, SeasonSummary(3, "Season 3", 8).episodeCount)
        assertEquals("Ep", Episode(1, 1, "Ep").name)
    }

    @Test fun imageUrlsUseProxyAndSizes() {
        assertNull(TmdbImageUrl.poster(null))
        assertNull(TmdbImageUrl.backdrop(null))
        assertNull(TmdbImageUrl.still(null))
        assertEquals(
            "https://api.maproj.org/api/tmdb/image/w342/p.jpg", TmdbImageUrl.poster("/p.jpg"))
        assertEquals(
            "https://api.maproj.org/api/tmdb/image/w500/p.jpg", TmdbImageUrl.poster("/p.jpg", "w500"))
        assertEquals(
            "https://api.maproj.org/api/tmdb/image/original/b.jpg", TmdbImageUrl.backdrop("/b.jpg"))
        assertEquals(
            "https://api.maproj.org/api/tmdb/image/w185/s.jpg", TmdbImageUrl.still("/s.jpg"))
    }

    @Test fun normalizeBaseUrlAcceptsBareAndManifestForms() {
        assertEquals("https://addons.example.com", AddonStreamProvider.normalizeBaseUrl("addons.example.com"))
        assertEquals(
            "https://addons.example.com", AddonStreamProvider.normalizeBaseUrl("https://addons.example.com/"))
        assertEquals(
            "https://addons.example.com/base",
            AddonStreamProvider.normalizeBaseUrl("https://addons.example.com/base/manifest.json"))
        assertEquals(
            "http://localhost:8080", AddonStreamProvider.normalizeBaseUrl("http://localhost:8080"))
        assertEquals(
            "http://127.0.0.1:9000/a", AddonStreamProvider.normalizeBaseUrl("http://127.0.0.1:9000/a/"))
        assertEquals(
            "http://192.168.1.5", AddonStreamProvider.normalizeBaseUrl("http://192.168.1.5"))
    }

    @Test fun normalizeBaseUrlRejectsInsecureAndInvalid() {
        assertFailsWith<ManifestError.InsecureUrl> {
            AddonStreamProvider.normalizeBaseUrl("http://addons.example.com")
        }
        assertFailsWith<ManifestError.InsecureUrl> {
            AddonStreamProvider.normalizeBaseUrl("http://8.8.8.8/addon")
        }
        assertFailsWith<ManifestError.Parse> {
            AddonStreamProvider.normalizeBaseUrl("ftp://addons.example.com")
        }
        assertFailsWith<ManifestError.Parse> {
            AddonStreamProvider.normalizeBaseUrl("https://exa mple.com")
        }
    }
}
