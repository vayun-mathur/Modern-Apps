// PACKAGE STRUCTURE EXCEPTION (JNI)
package com.vayunmathur.youpipe.nativeext

/**
 * JNI surface of the Rust extractor (`youpipe/src/main/rust`).
 *
 * The Rust side owns the whole request: it builds the InnerTube payloads, performs the HTTP over
 * its own rustls stack, and parses the responses. Nothing crosses back into Kotlin mid-request.
 *
 * Because it does not use `library:network`, it does not share the app's cookie jar or proxy
 * settings, and it trusts the bundled Mozilla roots rather than Android's store.
 *
 * Every entry point returns a JSON envelope rather than throwing across the boundary:
 * `{"ok":true,"data":{...}}` or `{"ok":false,"error":"..."}`. Use [NativeExtractor] instead of
 * calling these directly.
 *
 * `hl` is a BCP-47 UI language (`en-GB`); `gl` an ISO-3166 content country (`GB`).
 */
internal object YouPipeNative {

    init {
        System.loadLibrary("youpipe_extractor")
    }

    /** @param filter one of `videos`, `channels`, `playlists`, or null for everything. */
    external fun search(
        query: String,
        filter: String?,
        hl: String,
        gl: String,
    ): String

    external fun searchPage(token: String, hl: String, gl: String): String

    external fun suggestions(query: String, hl: String, gl: String): String

    /** Full metadata and streams for a video id. */
    external fun streamInfo(videoId: String, hl: String, gl: String): String

    /** @param id a `UC…` channel id or an `@handle`. */
    external fun channelInfo(id: String, hl: String, gl: String): String

    external fun playlistInfo(id: String, hl: String, gl: String): String

    external fun trending(hl: String, gl: String): String

    /** Next page for any browse-backed list (channel, playlist, trending). */
    external fun browseContinuation(
        token: String,
        hl: String,
        gl: String,
    ): String

    external fun comments(videoId: String, hl: String, gl: String): String

    external fun commentsPage(token: String, hl: String, gl: String): String
}
