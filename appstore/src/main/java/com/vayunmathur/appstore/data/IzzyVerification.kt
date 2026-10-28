package com.vayunmathur.appstore.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The set of IzzyOnDroid builds independently reproduced bit-for-bit.
 *
 * IzzyOnDroid ships the developers' own signed binaries rather than building from
 * source, so the store cannot trust an Izzy listing the way it trusts an F-Droid one.
 * What it can check is whether an independent verification builder rebuilt that exact
 * version from its published source tag and got a bit-identical APK — which is what
 * [Izzy's rbtlog transparency log](https://codeberg.org/IzzyOnDroid/rbtlog) records.
 *
 * Shape: `{ "<64-hex-key>": [ { "appid": ..., "version_code": 132,
 * "reproducible": true, ... } ] }`. The per-builder `index.json` files are merged:
 * a version counts as reproduced when *any* trusted builder says so. Records are
 * keyed here by `"$packageName:$versionCode"` — the top-level keys are opaque
 * content hashes, not join keys.
 *
 * Unlike [ReproducibleBuilds] (F-Droid) this is a **gate**, not a badge: the Izzy
 * descriptor imports nothing whose advertised version has no `reproducible == true`
 * record, because without one the listing is an unchecked third-party binary.
 * Best-effort in the same way, though — a failed fetch means nothing from Izzy is
 * listed this sync, rather than listing everything unfiltered.
 */
object IzzyReproducibleBuilds {

    /**
     * Per-builder transparency-log indexes consulted for Izzy verdicts: IzzyOnDroid's
     * own builder first, then the independent builder Izzy points clients at
     * (see the builder list at the end of `rbtlog.dev`).
     */
    const val IZZY_INDEX_URL = "https://codeberg.org/IzzyOnDroid/rbtlog/raw/branch/izzy/log/index.json"
    const val INDEPENDENT_INDEX_URL = "https://codeberg.org/bg443/rbtlog/raw/branch/master/index.json"

    /** Keys are `"$packageName:$versionCode"`. */
    @JvmInline
    value class Verified(private val keys: Set<String>) {
        val size: Int get() = keys.size
        fun contains(packageName: String, versionCode: Long): Boolean =
            keys.contains("$packageName:$versionCode")
    }

    /**
     * Whether the advertised version may be listed: only with an explicit reproduced
     * verdict. A missing feed (null) admits nothing — the Izzy catalogue then imports
     * no rows rather than unfiltered ones.
     */
    fun admits(verified: Verified?, packageName: String, versionCode: Long): Boolean =
        verified?.contains(packageName, versionCode) == true

    suspend fun fetch(context: Context): Verified = withContext(Dispatchers.IO) {
        val keys = HashSet<String>(16_000)
        listOf(IZZY_INDEX_URL, INDEPENDENT_INDEX_URL).forEachIndexed { index, url ->
            val file = File(context.cacheDir, "izzy-verified-$index.json")
            try {
                FDroidRepository.downloadToFile(url, file)
                parseInto(file, keys)
            } catch (_: Exception) {
                // One builder's log failing must not veto the other's verdicts.
            } finally {
                file.delete()
            }
        }
        Verified(keys)
    }

    private fun parseInto(file: File, into: MutableSet<String>) {
        JsonReader(file.reader()).use { r ->
            r.isLenient = true
            r.beginObject()
            while (r.hasNext()) {
                r.nextName() // opaque content-hash key, not a join key
                if (r.peek() != JsonToken.BEGIN_ARRAY) {
                    r.skipValue()
                    continue
                }
                r.beginArray()
                while (r.hasNext()) {
                    readRecord(r, into)
                }
                r.endArray()
            }
            r.endObject()
        }
    }

    private fun readRecord(r: JsonReader, into: MutableSet<String>) {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return
        }
        var appid: String? = null
        var versionCode: Long? = null
        var reproducible = false
        try {
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "appid" -> appid = nextStringOrNull(r)
                    "version_code" -> versionCode = nextLongOrNull(r)
                    // The schema allows null (not yet attempted): only an explicit
                    // true counts, so a missing or failed rebuild never admits.
                    // A non-boolean must still be consumed, or the reader desyncs
                    // and every record after this one misparses.
                    "reproducible" -> reproducible = if (r.peek() == JsonToken.BOOLEAN) {
                        r.nextBoolean()
                    } else {
                        r.skipValue()
                        false
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        } catch (_: Exception) {
            // Best-effort resync: consume to the end of this record so one corrupt
            // entry cannot take down every record after it in the array.
            runCatching {
                while (r.peek() != JsonToken.END_OBJECT && r.peek() != JsonToken.END_DOCUMENT) {
                    r.skipValue()
                }
                if (r.peek() == JsonToken.END_OBJECT) r.endObject()
            }
            return
        }
        if (reproducible && appid != null && versionCode != null) {
            into.add("$appid:$versionCode")
        }
    }
}
