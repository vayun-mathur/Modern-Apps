package org.schabi.newpipe.extractor.services.youtube.extractors

import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.DISABLE_PRETTY_PRINT_PARAMETER
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.YOUTUBEI_V1_URL
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.extractCookieValue
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.extractPlaylistTypeFromPlaylistId
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getValidJsonResponseBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getYouTubeHeaders
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.prepareDesktopJsonBuilder
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import org.schabi.newpipe.extractor.utils.ImageSuffix
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.Utils.getQueryValue
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.Utils.stringToURL
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getInt
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

/**
 * A [YoutubePlaylistExtractor] for a mix (auto-generated playlist).
 * It handles URLs in the format of
 * `youtube.com/watch?v=videoId&list=playlistId`
 */
class YoutubeMixPlaylistExtractor(
    service: StreamingService,
    linkHandler: ListLinkHandler
) : PlaylistExtractor(service, linkHandler) {

    private var initialData: JsonObject? = null
    private var playlistData: JsonObject? = null
    private var cookieValue: String = ""

    @Throws(IOException::class, ExtractionException::class)
    override fun onFetchPage(downloader: Downloader) {
        val localization = getExtractorLocalization()
        val url = stringToURL(getUrl())
        val mixPlaylistId = getId()
        val videoId = getQueryValue(url, "v")
        val playlistIndexString = getQueryValue(url, "index")

        val jsonBodyBuilder = prepareDesktopJsonBuilder(localization, getExtractorContentCountry())
            .value("playlistId", mixPlaylistId)
        if (videoId != null) {
            jsonBodyBuilder.value("videoId", videoId)
        }
        // A non-numeric index in a user-supplied mix URL is ignored, not
        // allowed to escape onFetchPage as NumberFormatException.
        playlistIndexString?.toIntOrNull()?.let { playlistIndex ->
            jsonBodyBuilder.value("playlistIndex", playlistIndex)
        }

        val body = jsonBodyBuilder.done().toString().toByteArray(Charsets.UTF_8)

        val headers = getYouTubeHeaders()

        val response = downloader.postWithContentTypeJson(
            "$YOUTUBEI_V1_URL${"next"}?$DISABLE_PRETTY_PRINT_PARAMETER", headers, body,
            localization
        )

        initialData = JsonUtils.toJsonObject(getValidJsonResponseBody(response))
        playlistData = initialData!!
            .getObject("contents").orEmptyObject()
            .getObject("twoColumnWatchNextResults").orEmptyObject()
            .getObject("playlist").orEmptyObject()
            .getObject("playlist")
        if (isNullOrEmpty(playlistData)) {
            val ex = ExtractionException("Could not get playlistData")
            if (!YoutubeParsingHelper.isConsentAccepted()) {
                throw ContentNotAvailableException(
                    "Consent is required in some countries to view Mix playlists",
                    ex
                )
            }
            throw ex
        }
        cookieValue = extractCookieValue(COOKIE_NAME, response)
    }

    override fun getName(): String {
        val name = YoutubeParsingHelper.getTextAtKey(playlistData!!, "title")
        if (isNullOrEmpty(name)) {
            throw ParsingException("Could not get playlist name")
        }
        return name
    }

    override fun getThumbnails(): List<Image> {
        try {
            return getThumbnailsFromPlaylistId(playlistData!!.getString("playlistId")!!)
        } catch (e: Exception) {
            try {
                return getThumbnailsFromVideoId(
                    initialData!!.getObject("currentVideoEndpoint").orEmptyObject()
                        .getObject("watchEndpoint").orEmptyObject().getString("videoId")!!
                )
            } catch (ignored: Exception) {
            }

            throw ParsingException("Could not get playlist thumbnails", e)
        }
    }

    override fun getUploaderUrl(): String? = ""

    override fun getUploaderName(): String? = "YouTube"

    override fun getUploaderAvatars(): List<Image> = emptyList()

    @Throws(ParsingException::class)
    override fun isUploaderVerified(): Boolean = false

    override fun getStreamCount(): Long = ListExtractor.ITEM_COUNT_INFINITE

    override fun getDescription(): Description = Description.EMPTY_DESCRIPTION

    @Throws(IOException::class, ExtractionException::class)
    @Suppress("UNCHECKED_CAST")
    override fun getInitialPage(): ListExtractor.InfoItemsPage<StreamInfoItem> {
        val collector = StreamInfoItemsCollector(getServiceId())
        collectStreamsFrom(collector, playlistData!!.getArray("contents"))

        val cookies = mutableMapOf<String, String>()
        cookies[COOKIE_NAME] = cookieValue

        return ListExtractor.InfoItemsPage(collector, getNextPageFrom(playlistData!!, cookies))
    }

    private fun getNextPageFrom(
        playlistJson: JsonObject,
        cookies: Map<String, String>
    ): Page {
        val lastStream = playlistJson.getArray("contents").orEmptyArray()
            .getOrNull(playlistJson.getArray("contents").orEmptyArray().size - 1) as? JsonObject
        if (lastStream == null || lastStream.getObject("playlistPanelVideoRenderer") == null) {
            throw ExtractionException("Could not extract next page url")
        }

        val watchEndpoint = lastStream.getObject("playlistPanelVideoRenderer").orEmptyObject()
            .getObject("navigationEndpoint").orEmptyObject().getObject("watchEndpoint").orEmptyObject()
        val playlistId = watchEndpoint.getString("playlistId")
        val videoId = watchEndpoint.getString("videoId")
        val index = watchEndpoint.getInt("index", 0)
        val params = watchEndpoint.getString("params")
        val body = prepareDesktopJsonBuilder(
            getExtractorLocalization(), getExtractorContentCountry()
        )
            .value("videoId", videoId)
            .value("playlistId", playlistId)
            .value("playlistIndex", index)
            .value("params", params)
            .done().toString()
            .toByteArray(Charsets.UTF_8)

        return Page(
            "$YOUTUBEI_V1_URL${"next"}?$DISABLE_PRETTY_PRINT_PARAMETER", null, null,
            cookies, body
        )
    }

    @Throws(IOException::class, ExtractionException::class)
    @Suppress("UNCHECKED_CAST")
    override fun getPage(page: Page): ListExtractor.InfoItemsPage<StreamInfoItem> {
        if (isNullOrEmpty(page.url)) {
            throw IllegalArgumentException("Page doesn't contain an URL")
        }
        if (page.cookies?.containsKey(COOKIE_NAME) != true) {
            throw IllegalArgumentException("Cookie '$COOKIE_NAME' is missing")
        }

        val collector = StreamInfoItemsCollector(getServiceId())
        val headers = getYouTubeHeaders()

        val response = downloader.postWithContentTypeJson(
            page.url, headers,
            page.body, getExtractorLocalization()
        )
        val ajaxJson = JsonUtils.toJsonObject(getValidJsonResponseBody(response))
        val playlistJson = ajaxJson.getObject("contents").orEmptyObject()
            .getObject("twoColumnWatchNextResults").orEmptyObject().getObject("playlist").orEmptyObject()
            .getObject("playlist").orEmptyObject()
        val allStreams = playlistJson.getArray("contents").orEmptyArray()
        val newStreams = allStreams.subList(
            (playlistJson.getInt("currentIndex") ?: 0) + 1, allStreams.size
        ).toList()

        collectStreamsFrom(collector, newStreams)
        return ListExtractor.InfoItemsPage(collector, getNextPageFrom(playlistJson, page.cookies))
    }

    private fun collectStreamsFrom(
        collector: StreamInfoItemsCollector,
        streams: List<JsonElement>?
    ) {
        if (streams == null) {
            return
        }

        val timeAgoParser = getTimeAgoParser()

        streams.filterIsInstance<JsonObject>()
            .map { stream ->
                val renderer = stream.getObject("playlistPanelVideoRenderer").orEmptyObject()
                YoutubeStreamInfoItemExtractor(renderer, timeAgoParser)
            }
            .forEach(collector::commit)
    }

    private fun getThumbnailsFromPlaylistId(playlistId: String): List<Image> {
        return getThumbnailsFromVideoId(YoutubeParsingHelper.extractVideoIdFromMixId(playlistId))
    }

    private fun getThumbnailsFromVideoId(videoId: String): List<Image> {
        val baseUrl = "https://i.ytimg.com/vi/$videoId/"
        return IMAGE_URL_SUFFIXES_AND_RESOLUTIONS.map { imageSuffix ->
            Image(
                baseUrl + imageSuffix.suffix,
                imageSuffix.height, imageSuffix.width,
                imageSuffix.resolutionLevel
            )
        }
    }

    @Throws(ParsingException::class)
    override fun getPlaylistType(): PlaylistInfo.PlaylistType {
        return extractPlaylistTypeFromPlaylistId(playlistData!!.getString("playlistId"))
    }

    companion object {
        private val IMAGE_URL_SUFFIXES_AND_RESOLUTIONS = listOf(
            ImageSuffix("default.jpg", 90, 120, Image.ResolutionLevel.LOW),
            ImageSuffix("mqdefault.jpg", 180, 320, Image.ResolutionLevel.MEDIUM),
            ImageSuffix("hqdefault.jpg", 360, 480, Image.ResolutionLevel.MEDIUM)
        )

        /**
         * YouTube identifies mixes based on this cookie. With this information it can generate
         * continuations without duplicates.
         */
        const val COOKIE_NAME: String = "VISITOR_INFO1_LIVE"
    }
}
