package com.vayunmathur.youpipe.util

import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.VideoStream

/**
 * The UI contract between [YouPipeViewModel] and the three screens the store listing is
 * rendered from: the home feed, the subscription feed, and the video page.
 *
 * Those screens take a state value plus an actions interface instead of the ViewModel, so a
 * `@Preview` can render them from literal data — no Room, no extractor, no network. It lives
 * in `util` next to the ViewModel so `ui` depends on `util` and not the other way round.
 * (The stream and comment types below are still declared in `ui`; the ViewModel already
 * imports them from there, and moving them would be a much bigger change than this one.)
 *
 * Anything the UI would otherwise derive from a Context or from the current clock — "1.2M
 * views", "3 days ago" — is pre-formatted into a plain string by the binder. That is what
 * makes a rendered preview byte-identical from one day to the next; a relative timestamp
 * computed at render time would churn the listing images every morning.
 */

/** One row of any of the video lists. */
data class VideoRowState(
    val videoID: Long,
    val title: String,
    /** Empty when there is nothing to fetch; the row then shows only its placeholder block. */
    val thumbnailURL: String = "",
    /** null on a list already scoped to one channel, which hides the line entirely. */
    val author: String? = null,
    /** Pre-formatted "1.2M views | 3 days ago". */
    val stats: String = "",
    /** Recommender explanation, shown under the stats. null outside the home feed. */
    val reason: String? = null,
    /** Lower-cased raw author name — the key channel preferences are stored under. */
    val channelKey: String = "",
    /** 0f..1f resume bar drawn across the bottom of the thumbnail. */
    val percentWatched: Float = 0f,
    /** Whether [thumbnailURL] came from DeArrow. Only affects the memory-cache key. */
    val deArrowThumbnail: Boolean = false,
)

// ===================== Home =====================

/** Everything the home screen draws. */
data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val results: List<SearchResultRow> = emptyList(),
    val recommendations: List<VideoRowState> = emptyList(),
    val recommendationsLoading: Boolean = false,
)

/** A row of the search overlay. The extractor returns videos and channels interleaved. */
sealed interface SearchResultRow {
    /** Immutable value describing video; carries video. */
    data class Video(val video: VideoRowState) : SearchResultRow

    /** Immutable value describing channel. */
    data class Channel(
        val channelID: String,
        val name: String,
        val avatarURL: String,
        /** Pre-formatted "1.2M subscribers". */
        val subscribers: String,
    ) : SearchResultRow
}

/**
 * Home-screen callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * The ViewModel does not implement this itself (unlike, say, `:calculator`) because every
 * one of these mixes a ViewModel call with a navigation side effect, which only the binder
 * can do.
 */
interface SearchActions {
    fun setSearchQuery(query: String) {}

    /**
     * Enter/Go on the search field: open a pasted watch URL if that is what it is, else run
     * the search. Returns true when it navigated away, which is the screen's cue to collapse
     * the search overlay.
     */
    fun submitSearch(): Boolean = false

    fun openVideo(videoID: Long) {}
    fun openChannel(channelID: String) {}
    fun notInterested(channelKey: String) {}
    fun moreLikeThis(channelKey: String) {}
    fun pinChannel(channelKey: String) {}
    fun blockChannel(channelKey: String) {}

    /** Shared constants and helpers for SearchActions. */
    companion object {
        val Noop: SearchActions = object : SearchActions {}
    }
}

// ===================== Subscription feed =====================

/** Everything the subscription-videos screen draws. */
data class SubscriptionFeedUiState(
    val videos: List<VideoRowState> = emptyList(),
    /** Refresh progress. Anything outside 0f..1f means "no refresh running". */
    val fetchProgress: Float = -1f,
    /** True until the first DB emission arrives; drives the loading skeleton. */
    val isLoading: Boolean = false,
)

/** Subscription-feed callbacks. Same no-op-default arrangement as [SearchActions]. */
interface SubscriptionFeedActions {
    fun openVideo(videoID: Long) {}

    /** Shared constants and helpers for SubscriptionFeedActions. */
    companion object {
        val Noop: SubscriptionFeedActions = object : SubscriptionFeedActions {}
    }
}

// ===================== Video page =====================

/** Everything the video screen draws around and below the player. */
data class VideoDetailUiState(
    /** False until the extractor has returned; the screen renders nothing at all until then. */
    val loaded: Boolean = false,
    val title: String = "",
    /** Pre-formatted "Channel | 1.2M views | 3 days ago". */
    val byline: String = "",
    val authorThumbnailURL: String = "",
    /** Only used as the avatar's memory-cache key; navigation goes through [VideoDetailActions.openChannel]. */
    val authorURL: String = "",
    val description: String = "",
    val comments: List<Comment> = emptyList(),
    val relatedVideos: List<VideoRowState> = emptyList(),
    /** Offered in the download dialog. */
    val videoStreams: List<VideoStream> = emptyList(),
    val audioStreams: List<AudioStream> = emptyList(),
    val downloaded: Boolean = false,
    /** 0f..1f while a download of this video is running, null otherwise. */
    val downloadProgress: Float? = null,
)

/** Video-screen callbacks. Same no-op-default arrangement as [SearchActions]. */
interface VideoDetailActions {
    fun openChannel() {}
    fun openVideo(videoID: Long) {}
    fun download(videoUrl: String, audioUrl: String?) {}
    fun cancelDownload() {}
    fun deleteDownload() {}
    fun addToPlaylist() {}
    fun share() {}

    /** Shared constants and helpers for VideoDetailActions. */
    companion object {
        val Noop: VideoDetailActions = object : VideoDetailActions {}
    }
}
