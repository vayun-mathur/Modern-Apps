package com.vayunmathur.appstore.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The set of F-Droid builds that have been **reproduced**.
 *
 * F-Droid's verification server (<https://verification.f-droid.org>) rebuilds apps that
 * f-droid.org already built, on separate infrastructure, and records whether the two
 * outputs are bit-identical. Its `verified.json` is the only machine-readable statement
 * of that: `index-v2.json` carries no reproducibility field whatsoever, so the index
 * alone cannot tell you whether a version was independently reproduced.
 *
 * Shape (~24 MB, ~31k build records):
 * ```
 * { "packages": { "<packageName>": [ {
 *       "verified": true,
 *       "local":  { "packageName": ..., "versionCode": 110002, ... },
 *       "remote": { ... }, "diffoscope": ..., "url": ...
 * } ] } }
 * ```
 * `versionCode` is a JSON number for some records and a string for others, so it is read
 * leniently. The `local`/`remote` SHA-256s are of the *unsigned* build artefacts and
 * never match the published APK's hash — `verified` is the field that carries the
 * verdict, not a hash comparison.
 *
 * This drives a per-app **badge**, not a gate: [ReproducibleBuilds.fetch] is best-effort,
 * and a failure just means no app is badged reproducible this sync — the catalogue is still
 * imported in full. See [FDroidAppProvider].
 */
object ReproducibleBuilds {

    const val VERIFIED_JSON_URL = "https://verification.f-droid.org/verified.json"

    /** Keys are `"$packageName:$versionCode"`. */
    @JvmInline
    value class Verified(private val keys: Set<String>) {
        val size: Int get() = keys.size
        fun contains(packageName: String, versionCode: Long): Boolean =
            keys.contains("$packageName:$versionCode")
    }

    suspend fun fetch(context: Context): Verified = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "fdroid-verified.json")
        try {
            FDroidRepository.downloadToFile(VERIFIED_JSON_URL, file)
            parse(file)
        } finally {
            file.delete()
        }
    }

    internal fun parse(file: File): Verified {
        val keys = HashSet<String>(24_000)
        JsonReader(file.reader()).use { r ->
            r.isLenient = true
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "packages") {
                    r.skipValue()
                    continue
                }
                r.beginObject()
                while (r.hasNext()) {
                    val pkg = r.nextName()
                    try {
                        readBuilds(r, pkg, keys)
                    } catch (_: Exception) {
                        runCatching { r.skipValue() }
                    }
                }
                r.endObject()
            }
            r.endObject()
        }
        return Verified(keys)
    }

    private fun readBuilds(r: JsonReader, pkg: String, into: MutableSet<String>) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return
        }
        r.beginArray()
        while (r.hasNext()) {
            var verified = false
            var versionCode: Long? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "verified" -> verified = readBooleanLenient(r)
                    // The build's identity lives in the `local` block; `remote` repeats it.
                    "local" -> versionCode = readVersionCode(r) ?: versionCode
                    else -> r.skipValue()
                }
            }
            r.endObject()
            if (verified && versionCode != null) into.add("$pkg:$versionCode")
        }
        r.endArray()
    }

    private fun readVersionCode(r: JsonReader): Long? {
        if (r.peek() != JsonToken.BEGIN_OBJECT) {
            r.skipValue()
            return null
        }
        var code: Long? = null
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() == "versionCode") {
                code = when (r.peek()) {
                    JsonToken.NUMBER -> r.nextLong()
                    JsonToken.STRING -> r.nextString().toLongOrNull()
                    else -> { r.skipValue(); null }
                }
            } else {
                r.skipValue()
            }
        }
        r.endObject()
        return code
    }

    private fun readBooleanLenient(r: JsonReader): Boolean = when (r.peek()) {
        JsonToken.BOOLEAN -> r.nextBoolean()
        JsonToken.STRING -> r.nextString().equals("true", ignoreCase = true)
        else -> { r.skipValue(); false }
    }
}
