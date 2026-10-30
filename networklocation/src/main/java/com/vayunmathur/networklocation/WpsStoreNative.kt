package com.vayunmathur.networklocation

/**
 * JNI bridge to the native offline WPSDB reader in the `networklocation` Rust library
 * (see networklocation/src/main/rust/src/wpsdb.rs). It answers exact key → coordinate
 * lookups over a `WPSDB2` store, and serves both offline stores the app ships:
 *   * `wifi-v2.wpsdb`  — 48-bit MAC (BSSID) key → coord + accuracy
 *   * `cells-v2.wpsdb` — 84-bit packed cell key → coord + accuracy
 *
 * A single generic reader handles both because the header carries the key universe width.
 * Keys are 128-bit, passed as a (hi, lo) pair of [Long]s: a MAC leaves `hi` zero, an 84-bit
 * cell key does not. The pair exists because an honest cell key — one that carries the radio
 * type and 5G's full 36-bit NCI — does not fit in 64 bits.
 *
 * The fully-qualified name MUST stay `com.vayunmathur.networklocation.WpsStoreNative` so the
 * JNI symbol mangling (`Java_com_vayunmathur_networklocation_WpsStoreNative_*`) matches.
 */
object WpsStoreNative {
    /** Whether the `.so` loaded. Guarded so host/unit contexts degrade gracefully. */
    val available: Boolean = runCatching { System.loadLibrary("networklocation") }.isSuccess

    /**
     * Open a WPSDB store from a file descriptor. [offset] is the store's start offset within
     * the file; [length] is reserved for future validation. Returns an opaque handle, or 0 on
     * failure — including when the file is truncated, which the reader checks for because a
     * partial download otherwise parses as a valid header. The native side dups [fd], so the
     * caller may close its own descriptor after this returns.
     */
    external fun open(fd: Int, offset: Long, length: Long): Long

    /**
     * `[lat, lon, accuracyMeters, altitudeMeters]` for the 128-bit key ([keyHi], [keyLo]), or
     * null if the store does not contain it. A negative accuracy means the store holds no
     * radius for that beacon; callers substitute their own conservative default. A NaN
     * altitude means the store holds no altitude for that beacon — always the case for kind-1
     * stores and for cell records, since the cell response has no altitude field.
     */
    external fun lookup(handle: Long, keyHi: Long, keyLo: Long): DoubleArray?

    /** Free the handle and its underlying descriptor. Safe to call with 0. */
    external fun close(handle: Long)
}
