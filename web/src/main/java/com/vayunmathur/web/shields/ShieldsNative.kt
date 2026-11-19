// PACKAGE STRUCTURE EXCEPTION (JNI): FQN frozen for native RegisterNatives/symbol mangling
package com.vayunmathur.web.shields

import com.vayunmathur.library.log.NativeLoader

/**
 * JNI bridge to Brave's adblock engine (`libweb_shields.so`, built from
 * `web/src/main/rust/`). [isAvailable] is false when the native lib is missing for the
 * current ABI, which makes [ShieldsEngine] fail open rather than break every page.
 *
 * Handles are opaque; 0 means "no engine". Every call is safe to make from any thread —
 * the Rust `Engine` is `Send + Sync` — but they are all blocking, so only [nativeCheck]
 * belongs on the WebView render thread.
 */
internal object ShieldsNative {

    val isAvailable: Boolean = NativeLoader.load("web_shields", "ShieldsNative")

    /** Parses `filters` (Adblock Plus syntax) and returns an engine handle, or 0. */
    external fun nativeCreate(filters: String, resourcesJson: String): Long

    /** Restores an engine from [nativeSerialize] output. Returns 0 if the format changed. */
    external fun nativeCreateFromCache(cache: ByteArray, resourcesJson: String): Long

    /** Snapshot of the parsed filter data, for [nativeCreateFromCache]. */
    external fun nativeSerialize(handle: Long): ByteArray?

    /** `{"blocked","important","exception","redirect","rewritten"}` — see [ShieldsCheck]. */
    external fun nativeCheck(handle: Long, url: String, sourceUrl: String, requestType: String): String?

    /** `{"hide","procedural","exceptions","script","generichide"}` — see [CosmeticResources]. */
    external fun nativeCosmeticResources(handle: Long, url: String): String?

    /** JSON array of extra hide selectors for classes/ids that appeared after load. */
    external fun nativeHiddenClassIdSelectors(
        handle: Long,
        classesJson: String,
        idsJson: String,
        exceptionsJson: String,
    ): String?

    external fun nativeDestroy(handle: Long)
}
