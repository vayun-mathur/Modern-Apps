package com.vayunmathur.youpipe

import androidx.core.net.toUri
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.VideoInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItemExtractor
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.nio.ByteBuffer
import kotlin.io.encoding.Base64
import kotlin.time.toKotlinInstant

fun videoURLtoID(url: String): Long {
    return ByteBuffer.wrap(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(url.toUri().getQueryParameter("v")!!)).long
}

fun encodeVideoID(id: String): Long {
    return ByteBuffer.wrap(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(id)).long
}

fun decodeVideoID(id: Long): String {
    val buffer = ByteBuffer.allocate(java.lang.Long.BYTES)
    buffer.putLong(id)
    return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(buffer.array())
}

fun videoIDtoURL(id: Long): String {
    val buffer = ByteBuffer.allocate(java.lang.Long.BYTES)
    buffer.putLong(id)
    return "https://www.youtube.com/watch?v="+Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(buffer.array())
}

fun channelIDtoURL(id: String): String {
    return "https://www.youtube.com/channel/$id"
}

fun channelURLtoID(url: String): String {
    return url.toUri().lastPathSegment!!
}

fun getChannelVideos(channelId: String): Sequence<VideoInfo> = sequence {
    val ex = ServiceList.YouTube.getChannelTabExtractorFromId(channelId, "videos")
    ex.fetchPage()
    var page = ex.initialPage
    while(true) {
        page.items.filterIsInstance<StreamInfoItem>().forEach {
            yield(
            VideoInfo(
                it.name,
                videoURLtoID(it.url),
                it.duration,
                it.viewCount,
                it.uploadDate!!.instant.toKotlinInstant(),
                it.thumbnails.first().url,
                it.uploaderName
            ))
        }
        if(page.hasNextPage()) {
            page = ex.getPage(page.nextPage!!)
        } else {
            break
        }
    }
}

suspend fun getChannelInfo(channelId: String): ChannelInfo = coroutineScope {
    val ex = ServiceList.YouTube.getChannelExtractor(channelIDtoURL(channelId))
    ex.fetchPage()
    ChannelInfo(
        ex.name,
        channelId,
        ex.subscriberCount,
        0,
        ex.avatars.first().url,
    )
}