package com.vayunmathur.networklocation

/**
 * JNI bridge to the native offline geocoder search in the `networklocation` Rust library
 * (see networklocation/src/main/rust/src/geocoder.rs). The database itself is generated
 * offline by `scripts/networklocation/geodb_build` and downloaded to device-protected storage
 * (see [OfflineDatabases]); this object opens it by file descriptor and answers reverse and
 * forward queries entirely in Rust.
 *
 * Results are flat String arrays with [FIELDS_PER_RESULT] entries per result:
 * `[lat, lon, name, house, street, city, state, country, postcode, kind]`, lat/lon to 5 dp.
 *
 * The fully-qualified name MUST stay `com.vayunmathur.networklocation.GeocoderNative` so the
 * JNI symbol mangling (`Java_com_vayunmathur_networklocation_GeocoderNative_*`) matches.
 */
object GeocoderNative {
    /** Strings per result in the flat arrays returned by the three query functions. */
    const val FIELDS_PER_RESULT = 10

    /** Offsets within one result. */
    const val F_LAT = 0

    /** Longitude. */
    const val F_LON = 1

    /** Feature name; empty for a plain address. */
    const val F_NAME = 2

    /** House number. */
    const val F_HOUSE = 3

    /** Street, or the place a building is addressed against. */
    const val F_STREET = 4

    /** City or locality. */
    const val F_CITY = 5

    /** State or province. */
    const val F_STATE = 6

    /** ISO country code. */
    const val F_COUNTRY = 7

    /** Postcode. */
    const val F_POSTCODE = 8

    /** One of [KIND_ADDRESS], [KIND_STREET], [KIND_POI], [KIND_PLACE], as a decimal string. */
    const val F_KIND = 9

    /** A postal address. */
    const val KIND_ADDRESS = 1

    /** A named road. */
    const val KIND_STREET = 2

    /** A named point of interest. */
    const val KIND_POI = 3

    /** A populated place: city, town, village, suburb, neighbourhood. */
    const val KIND_PLACE = 4

    /** Whether the `.so` loaded. Guarded so host/unit contexts degrade gracefully. */
    val available: Boolean = runCatching { System.loadLibrary("networklocation") }.isSuccess

    /**
     * Open the geocoder DB from a file descriptor. [offset] is the database's start offset
     * within the file; [length] is reserved for future validation. Returns an opaque handle,
     * or 0 on failure. The native side dups [fd], so the caller may close its own descriptor
     * after this returns.
     */
    external fun open(fd: Int, offset: Long, length: Long): Long

    /** Nearest stored record to (lat, lon): [FIELDS_PER_RESULT] strings, or null. */
    external fun reverse(handle: Long, lat: Double, lon: Double): Array<String>?

    /**
     * Addresses matching the components exactly. Returns a flat array of
     * `FIELDS_PER_RESULT * k` strings (k results, possibly empty), or null on error.
     */
    external fun forward(
        handle: Long,
        country: String,
        state: String,
        city: String,
        street: String,
        limit: Int,
    ): Array<String>?

    /**
     * Named features — points of interest, places and streets — whose name starts with
     * [prefix]. Matching is case-sensitive, because the name dictionary is ordered by the
     * names as OpenStreetMap spells them and a case-folded run would not be contiguous in
     * that ordering.
     */
    external fun searchName(handle: Long, prefix: String, limit: Int): Array<String>?

    /** Free the handle and its underlying descriptor. Safe to call with 0. */
    external fun close(handle: Long)
}
