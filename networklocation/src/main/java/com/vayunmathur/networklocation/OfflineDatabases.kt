package com.vayunmathur.networklocation

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The offline databases the provider reads, and where they live on disk.
 *
 * These used to ship as APK assets, but at ~3.5 GB they dominated the MAOS system image and
 * pushed the factory install zip past fastboot's 4 GiB zip limit. They are downloaded on
 * request instead, to device-protected storage so the always-on provider can reach them
 * before the user unlocks after a reboot.
 *
 * Absence is a supported state, not an error: every reader treats a zero handle as "no data",
 * so beacon lookups miss and the geocoder reports itself unavailable.
 *
 * ## Format versions
 *
 * The beacon stores carry their format version in the filename: a new format gets a new name
 * rather than a version field the readers negotiate. The geocoder keeps one name and carries its
 * version in the file header; [isPresent] reads that header, so a geocoder in an old format
 * counts as absent and the download replaces it. Either way the files are a cache of a
 * published artifact, not user data, so there is nothing to migrate — an old file is simply a
 * name nobody asks for, and [pruneStale] reclaims its multiple gigabytes.
 */
object OfflineDatabases {
    const val GEOCODER = "geocoder.db"
    const val WIFI = "wifi-v3.wpsdb"
    const val CELL = "cells-v3.wpsdb"

    /** Names shipped by earlier versions of the app, deleted on sight to reclaim space. */
    private val SUPERSEDED = listOf(
        "geocoder.geodb",
        "geocoder-v3.geodb",
        "wifi.wpsdb",
        "cells.wpsdb",
        "wifi-v2.wpsdb",
        "cells-v2.wpsdb",
    )

    private const val GEOCODER_URL = "https://data.maproj.org/geocoder/$GEOCODER"
    private const val WPS_BASE_URL = "https://data.maproj.org/wps/"

    /** `"MAGE"` and the format version `geocoder.rs` reads; must match `VERSION` there. */
    private const val GEOCODER_MAGIC = 0x4D414745
    private const val GEOCODER_VERSION = 5

    /**
     * Expected SHA-256 of each published database, verified by `:library:downloadservice`
     * after the transfer and refetched on mismatch.
     *
     * Presence alone is too weak a check: a store is several gigabytes fetched in resumable
     * chunks, and a truncated file is non-empty, so without a checksum it reads as installed
     * and then fails its magic check on every open, forever.
     *
     * A null hash means the download is accepted unverified. Each stays null until its database
     * has been built and published.
     */
    fun sha256For(name: String): String? = when (name) {
        GEOCODER -> "a9a3b21eba93fcf4549c077c8a2a74ae561570878c63988601505a5fe6a6f942"
        // A device that has a store counts it as installed and never refetches it, so new
        // content for a beacon store must ship under a new filename, not just a new hash.
        WIFI -> "c8aa83f577d90d250d76912df2adde8a8e0d0bc0dcb4c87fe9e9916a59e822db"
        CELL -> "3c7bf475e58f1c912aa45d47ac12d7cd7e76fc2c4d10c3b46859b1027e7660c3"
        else -> null
    }

    /** Download source for [name], matching the mirror the DBs are published to. */
    fun urlFor(name: String): String = when (name) {
        GEOCODER -> GEOCODER_URL
        else -> WPS_BASE_URL + name
    }

    /**
     * Device-protected storage, which is readable before first unlock. The provider is bound by
     * the framework and runs unattended, so it cannot wait for the user to unlock to find its
     * data. Note this only pays off once the services are also directBootAware.
     */
    fun dir(context: Context): File = context.createDeviceProtectedStorageContext().filesDir

    fun file(context: Context, name: String): File = File(dir(context), name)

    fun isPresent(context: Context, name: String): Boolean {
        val f = file(context, name)
        if (f.length() <= 0) return false
        return name != GEOCODER || hasCurrentGeocoderHeader(f)
    }

    /** Header is big-endian, which is what [RandomAccessFile.readInt] reads. */
    private fun hasCurrentGeocoderHeader(f: File): Boolean = try {
        RandomAccessFile(f, "r").use { it.readInt() == GEOCODER_MAGIC && it.readInt() == GEOCODER_VERSION }
    } catch (_: IOException) {
        false
    }

    fun allPresent(context: Context): Boolean =
        listOf(GEOCODER, WIFI, CELL).all { isPresent(context, it) }

    /** Delete databases in a format no reader understands any more. Safe to call repeatedly. */
    fun pruneStale(context: Context) {
        for (name in SUPERSEDED) {
            val f = file(context, name)
            if (f.isFile) f.delete()
        }
    }

    /**
     * Open [name] for the native readers, which take an fd plus a base offset. Assets lived at
     * an offset inside the APK; a standalone file starts at 0. Returns null when the database
     * has not been downloaded yet.
     */
    fun openReadOnly(context: Context, name: String): ParcelFileDescriptor? {
        val f = file(context, name)
        if (!f.isFile || f.length() == 0L) return null
        return try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: IOException) {
            null
        }
    }
}
