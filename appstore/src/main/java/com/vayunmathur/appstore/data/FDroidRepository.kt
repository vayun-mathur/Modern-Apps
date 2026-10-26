package com.vayunmathur.appstore.data

import android.content.Context
import android.os.Build
import android.util.JsonReader
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.SignedJarIndex
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * F-Droid repo client. Avoids OOM on the ~100MB index by downloading to a file and
 * streaming-parsing with android.util.JsonReader rather than holding a whole object tree.
 * Filters targetSdk < AppProvider.MIN_TARGET_SDK.
 *
 * **The index is always authenticated through the repo's signed `entry.jar`**, and the
 * signing certificate is pinned per repo. The plain `index-v2.json` endpoint is deliberately
 * *not* used as a fallback: without a
 * signature the per-APK `sha256` and `signer` values this parser extracts would be
 * attacker-controlled, and pinning them would be security theatre.
 */
object FDroidRepository {

    /** Parsed index plus the repo signing certificate it was authenticated with. */
    data class IndexResult(val apps: List<UnifiedApp>, val signerSha256: String)

    /**
     * Fetch and verify [repoUrl]'s index.
     *
     * Every app's newest version is imported with [source]; [isReproducible]
     * only *tags* whether that version was independently reproduced (surfaced as a badge),
     * it never drops a version or a package.
     */
    suspend fun fetchRepoIndex(
        context: Context,
        repoUrl: String,
        pinnedFingerprint: String,
        source: AppSource,
        isReproducible: (packageName: String, versionCode: Long) -> Boolean,
    ): IndexResult = withContext(Dispatchers.IO) {
        val base = repoUrl.trimEnd('/')
        val work = File(context.cacheDir, "fdroid-index/${base.hashCode()}").apply { mkdirs() }
        try {
            fetchV2(base, pinnedFingerprint, source, work, isReproducible)
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * index-v2: `entry.jar` is the signed root. It names the real index file and pins its
     * SHA-256, so the large index itself needs no separate signature — the hash chains
     * back to the certificate we just verified.
     */
    private fun fetchV2(
        base: String,
        pinnedFingerprint: String,
        source: AppSource,
        work: File,
        isReproducible: (String, Long) -> Boolean,
    ): IndexResult {
        val entryJar = File(work, "entry.jar")
        downloadToFile("$base/entry.jar", entryJar)
        val verified = SignedJarIndex.readVerified(entryJar, "entry.json", pinnedFingerprint)

        val (name, expectedSha) = entryIndexRef(verified.content)

        val indexFile = File(work, "index-v2.json")
        downloadToFile(base + "/" + name.trimStart('/'), indexFile)
        val actualSha = ApkCertificates.sha256(indexFile)
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            throw java.io.IOException("index-v2.json hash does not match the signed entry.json")
        }

        return IndexResult(
            apps = AppProvider.filterTargetSdk(
                parseV2Streaming(indexFile, base, source, isReproducible)
            ),
            signerSha256 = verified.signerSha256,
        )
    }

    private data class IndexRef(val name: String, val sha256: String)

    private fun entryIndexRef(entryJson: ByteArray): IndexRef {
        val entry = JSONObject(String(entryJson, Charsets.UTF_8))
        val index = entry.optJSONObject("index")
            ?: throw java.io.IOException("entry.json has no index section")
        val name = index.optString("name").takeIf { it.isNotBlank() }
        val sha = index.optString("sha256").takeIf { it.isNotBlank() }
        if (name == null || sha == null) {
            throw java.io.IOException("entry.json index has no name/sha256")
        }
        return IndexRef(name, sha)
    }

    internal fun downloadToFile(url: String, outFile: File) {
        val rawConnection = URL(url).openConnection()
        val sslSocketFactory = NetworkClient.defaultSslSocketFactory
        if (sslSocketFactory != null && rawConnection is HttpsURLConnection) {
            rawConnection.sslSocketFactory = sslSocketFactory
        }
        val conn = (rawConnection as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in HTTP_OK_MIN..HTTP_OK_MAX) {
                throw java.io.IOException("HTTP ${conn.responseCode} for $url")
            }
            conn.inputStream.use { input ->
                outFile.outputStream().use { out ->
                    val buf = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---- Streaming V2 parser ----

    private fun parseV2Streaming(
        file: File,
        repoBase: String,
        source: AppSource,
        isReproducible: (String, Long) -> Boolean,
    ): List<UnifiedApp> {
        val result = mutableListOf<UnifiedApp>()
        JsonReader(file.reader()).use { r ->
            r.isLenient = true
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == PACKAGES_KEY) {
                    readPackagesV2(r, repoBase, source, isReproducible, result)
                } else {
                    r.skipValue()
                }
            }
            r.endObject()
        }
        return result
    }

    private fun readPackagesV2(
        r: JsonReader,
        repoBase: String,
        source: AppSource,
        isReproducible: (String, Long) -> Boolean,
        result: MutableList<UnifiedApp>,
    ) {
        r.beginObject()
        while (r.hasNext()) {
            val pkg = r.nextName()
            try {
                val app = parsePackageV2(r, pkg, repoBase, source, isReproducible)
                if (app != null) result.add(app)
            } catch (_: Exception) {
                try {
                    r.skipValue()
                } catch (_: Exception) {
                }
            }
        }
        r.endObject()
    }

    private const val PACKAGES_KEY = "packages"
    private const val CONNECT_TIMEOUT_MS = 30000
    private const val READ_TIMEOUT_MS = 120000
    private const val DOWNLOAD_BUFFER_SIZE = 32 * 1024
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    private const val USER_AGENT = "ModernAppStore/1.0"

    private class PackageBuilder {
        var metaName: String? = null
        var metaSummary: String? = null
        var metaDesc: String? = null
        var author: String? = null
        var categories: List<String> = emptyList()
        var website: String? = null
        var sourceCode: String? = null
        var license: String? = null
        var added: Long = 0L
        var lastUpdated: Long = 0L
        var iconUrl: String? = null
        var featureGraphic: String? = null
        var screenshots: List<String> = emptyList()
        var tvScreenshots: List<String> = emptyList()
        var antiFeatures: List<String> = emptyList()
        val versions = mutableListOf<VersionCandidate>()
    }

    private class VersionBuilder {
        var added: Long = 0L
        var fileName: String? = null
        var fileSize: Long = 0L
        var fileSha256: String? = null
        var signers: List<String> = emptyList()
        var versionName: String? = null
        var versionCode: Long = 0L
        var targetSdk: Int? = null
        var nativeCode: List<String> = emptyList()
        var whatsNew: String? = null
        var requiredFeatures: List<String> = emptyList()
        var featuresPublished: Boolean = false
        var requiredSplitTypes: List<String> = emptyList()
        fun build(): VersionCandidate? {
            val name = fileName ?: return null
            return VersionCandidate(
                added = added,
                fileName = name,
                size = fileSize,
                sha256 = fileSha256,
                signers = signers,
                versionName = versionName,
                versionCode = versionCode,
                targetSdk = targetSdk,
                nativeCode = nativeCode,
                whatsNew = whatsNew,
                requiredFeatures = requiredFeatures,
                featuresPublished = featuresPublished,
                requiredSplitTypes = requiredSplitTypes,
            )
        }
    }

    private fun parsePackageV2(
        reader: JsonReader,
        packageName: String,
        repoBase: String,
        source: AppSource,
        isReproducible: (String, Long) -> Boolean,
    ): UnifiedApp? {
        // reader at BEGIN_OBJECT of package
        val b = PackageBuilder()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "metadata" -> readMetadataV2(reader, repoBase, b)
                "versions" -> readVersionsV2(reader, b)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        // Play Dynamite runtime modules (`*_installtime.apk`, e.g. GMS's CronetDynamite)
        // ride along in Play delivery under the same versionCode but are not installable
        // APKs: `pm install` refuses them with INSTALL_FAILED_INVALID_APK, and
        // PackageManager cannot parse them, so InstallVerifier rejects the download as
        // "not a valid signed app file". Drop them here so they can be neither picked as
        // the advertised version nor swept in as install siblings. The server no longer
        // indexes them either; this covers indexes synced before that change.
        b.versions.removeAll { isDynamiteModule(it.fileName) }

        // Nothing this device can run — drop the package rather than advertising an entry
        // that has no installable APK behind it.
        val latest = selectVersion(b.versions, deviceAbis) ?: return null

        // Split-set versions install as a set: when the chosen entry requires
        // splits, every indexed file sharing its versionCode belongs in the
        // same installer session (base + config splits). Without this the
        // system refuses with INSTALL_FAILED_MISSING_SPLIT on Android 14+.
        // Dynamite runtime modules (`*_installtime.apk`) are excluded: they share
        // the versionCode but are not installable splits, and the system refuses
        // the whole session when one is included.
        // Sibling order is deterministic (base first: shortest file name tends
        // to be the base; ties broken alphabetically) — the session writer
        // doesn't care about order, but humans reading logs do.
        val splitFiles = if (latest.requiredSplitTypes.isEmpty()) {
            emptyList()
        } else {
            b.versions
                .filter {
                    it.versionCode == latest.versionCode &&
                        it.fileName != latest.fileName &&
                        !isDynamiteModule(it.fileName)
                }
                .sortedWith(compareBy({ it.fileName.length }, { it.fileName }))
                .map {
                    SplitFile(
                        fileName = it.fileName,
                        url = repoBase + "/" + it.fileName.trimStart('/'),
                        sha256 = it.sha256,
                        size = it.size,
                    )
                }
        }

        // index-v2 file names are repo-absolute ("/com.example_12.apk").
        val apkUrl = repoBase + "/" + latest.fileName.trimStart('/')
        val tvCompatible = deriveTvCompatible(
            b.categories,
            b.tvScreenshots,
            latest.requiredFeatures,
            authoritative = latest.featuresPublished && source in AUTHORITATIVE_TV_SOURCES,
        )
        return UnifiedApp(
            packageName = packageName,
            source = source,
            expectedSigners = latest.signers,
            apkSha256 = latest.sha256,
            reproducible = isReproducible(packageName, latest.versionCode),
            name = b.metaName ?: packageName.substringAfterLast('.'),
            summary = b.metaSummary ?: "",
            description = b.metaDesc ?: "",
            iconUrl = b.iconUrl,
            featureGraphic = b.featureGraphic,
            screenshots = b.screenshots,
            tvScreenshots = b.tvScreenshots,
            antiFeatures = b.antiFeatures,
            author = b.author,
            categories = b.categories,
            versionName = latest.versionName,
            versionCode = latest.versionCode,
            sizeBytes = latest.size,
            apkUrl = apkUrl,
            targetSdk = latest.targetSdk,
            license = b.license,
            website = b.website,
            sourceCode = b.sourceCode,
            whatsNew = latest.whatsNew,
            addedTimestamp = b.added,
            lastUpdated = if (b.lastUpdated != 0L) b.lastUpdated else b.added,
            repoUrl = repoBase,
            requiredFeatures = latest.requiredFeatures,
            tvCompatible = tvCompatible,
            containsSplit = latest.requiredSplitTypes.isNotEmpty() || splitFiles.isNotEmpty(),
            splitFiles = splitFiles,
        )
    }

    private fun readMetadataV2(reader: JsonReader, repoBase: String, b: PackageBuilder) {
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (!readMetadataScalar(reader, b, key)) {
                readMetadataNested(reader, repoBase, b, key)
            }
        }
        reader.endObject()
    }

    private fun readMetadataScalar(
        reader: JsonReader,
        b: PackageBuilder,
        key: String,
    ): Boolean {
        when (key) {
            "name" -> b.metaName = readLocalizedString(reader)
            "summary" -> b.metaSummary = readLocalizedString(reader)
            "description" -> b.metaDesc = readLocalizedString(reader)
            "authorName" -> b.author = nextStringOrNull(reader)
            "categories" -> b.categories = readStringArray(reader)
            "webSite" -> b.website = nextStringOrNull(reader)
            "sourceCode" -> b.sourceCode = nextStringOrNull(reader)
            "license" -> b.license = nextStringOrNull(reader)
            "added" -> b.added = nextLongOrNull(reader) ?: 0L
            "lastUpdated" -> b.lastUpdated = nextLongOrNull(reader) ?: 0L
            else -> return false
        }
        return true
    }

    private fun readMetadataNested(
        reader: JsonReader,
        repoBase: String,
        b: PackageBuilder,
        key: String,
    ) {
        when (key) {
            "icon" -> b.iconUrl = repoAssetUrl(reader, repoBase)
            "featureGraphic" -> b.featureGraphic = repoAssetUrl(reader, repoBase)
            "screenshots" -> {
                val sets = readScreenshotSetsV2(reader, repoBase)
                b.screenshots = sets.phone
                b.tvScreenshots = sets.tv
            }
            // v2 states anti-features as a map of id -> localised reason;
            // the ids are what the UI shows, so only the keys are kept.
            "antiFeatures" -> b.antiFeatures = readObjectKeys(reader)
            else -> reader.skipValue()
        }
    }

    private fun repoAssetUrl(reader: JsonReader, repoBase: String): String? {
        // index-v2 icon names are repo-absolute ("/icons/foo.png");
        // don't prepend /icons/ again as the v1 branch has to.
        val iconName = readIconName(reader) ?: return null
        return repoBase + "/" + iconName.trimStart('/')
    }

    private fun readVersionsV2(reader: JsonReader, b: PackageBuilder) {
        reader.beginObject()
        while (reader.hasNext()) {
            reader.nextName() // version key
            try {
                readOneVersionV2(reader, b)
            } catch (_: Exception) {
                try {
                    reader.endObject()
                } catch (_: Exception) {
                }
            }
        }
        reader.endObject()
    }

    private fun readOneVersionV2(reader: JsonReader, b: PackageBuilder) {
        reader.beginObject()
        val v = VersionBuilder()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "added" -> v.added = nextLongOrNull(reader) ?: 0L
                "file" -> readVersionFileV2(reader, v)
                "manifest" -> readVersionManifestV2(reader, v)
                "whatsNew" -> v.whatsNew = readLocalizedString(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        v.build()?.let { b.versions += it }
    }

    private fun readVersionFileV2(reader: JsonReader, v: VersionBuilder) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> v.fileName = nextStringOrNull(reader)
                "size" -> v.fileSize = nextLongOrNull(reader) ?: 0L
                "sha256" -> v.fileSha256 = nextStringOrNull(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun readVersionManifestV2(reader: JsonReader, v: VersionBuilder) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "versionName" -> v.versionName = nextStringOrNull(reader)
                "versionCode" -> v.versionCode = nextLongOrNull(reader) ?: 0L
                "usesSdk" -> v.targetSdk = readTargetSdkV2(reader)
                // The ABIs this APK carries native libraries for.
                // Absent means it has none and runs anywhere.
                "nativecode" -> v.nativeCode = readStringArray(reader)
                // Split types this APK requires at install time
                // (`requiredSplitTypes`, e.g. ["config", "density"]). Absent on
                // monolithic APKs. A non-empty list means the version installs
                // only as a set — see selectVersion's sibling gathering.
                "requiredSplitTypes" -> v.requiredSplitTypes = readStringArray(reader)
                // `<uses-feature>` entries, as `[{ "name": "android.hardware.camera" }, …]`.
                // Kept verbatim for TV-compat filtering — a leanback entry marks a TV build,
                // phone-hardware entries mark one a TV should not be offered.
                "features" -> {
                    v.requiredFeatures = readFeatureNamesV2(reader)
                    v.featuresPublished = true
                }
                // signer.sha256 is the list of signing-certificate
                // fingerprints this APK is expected to carry.
                "signer" -> v.signers = readSignerShaV2(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun readTargetSdkV2(reader: JsonReader): Int? {
        reader.beginObject()
        var target: Int? = null
        while (reader.hasNext()) {
            if (reader.nextName() == "targetSdkVersion") {
                target = nextIntOrNull(reader)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return target
    }

    private fun readSignerShaV2(reader: JsonReader): List<String> {
        reader.beginObject()
        var signers: List<String> = emptyList()
        while (reader.hasNext()) {
            if (reader.nextName() == "sha256") {
                signers = readStringArray(reader)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return signers
    }

    // ---- Version selection ----

    /**
     * One entry of a package's `versions` map.
     *
     * Held as a list rather than folded into a running maximum as it is read, because
     * F-Droid publishes some apps as one APK per architecture under a single release, and
     * those all share the same [added] timestamp: which of them is installable here can
     * only be decided once every variant has been seen.
     */
    internal data class VersionCandidate(
        val added: Long,
        val fileName: String,
        val size: Long = 0L,
        val sha256: String? = null,
        val signers: List<String> = emptyList(),
        val versionName: String? = null,
        val versionCode: Long = 0L,
        val targetSdk: Int? = null,
        val nativeCode: List<String> = emptyList(),
        val whatsNew: String? = null,
        val requiredFeatures: List<String> = emptyList(),
        /** True when the index carried a `features` field at all, even an empty one. */
        val featuresPublished: Boolean = false,
        val requiredSplitTypes: List<String> = emptyList(),
    )

    /**
     * Whether the catalogue can call this listing TV-compatible, from the signals
     * F-Droid publishes: leanback `<uses-feature>`, a TV category, or a TV screenshot
     * set. Null when nothing says either way - unknown, not incompatible.
     *
     * [authoritative] is for repos whose index is generated from the APK itself and
     * publishes the TV signal whenever it exists (this store's own repo and mirror):
     * there, the absence of a signal is a verdict, so phone-only apps are not offered
     * on a TV only to be refused by the install gate after the download.
     */
    internal fun deriveTvCompatible(
        categories: List<String>,
        tvScreenshots: List<String>,
        requiredFeatures: List<String>,
        authoritative: Boolean = false,
    ): Boolean? {
        val names = requiredFeatures.map { it.lowercase() }
        if (names.any { AppProvider.FEATURE_LEANBACK in it }) return true
        if (categories.any { it.equals(TV_CATEGORY, ignoreCase = true) }) return true
        if (tvScreenshots.isNotEmpty()) return true
        if (AppProvider.requiresPhoneHardware(requiredFeatures)) return false
        return if (authoritative) false else null
    }

    /** Repos whose index is built from the APK manifest by this project's own server. */
    private val AUTHORITATIVE_TV_SOURCES = setOf(AppSource.MODERN_APPS, AppSource.PROPRIETARY)

    private const val TV_CATEGORY = "TV"

    private val deviceAbis: List<String> by lazy { Build.SUPPORTED_ABIS?.toList().orEmpty() }

    /**
     * Whether this indexed file is a Play Dynamite runtime module rather than an
     * installable APK.
     *
     * GMS ships `*_installtime.apk` modules (Cronet, Maps, Ads, …) inside its Play delivery
     * under the same versionCode as the base. They carry the package name and signers, so
     * every identity check passes, but they are not installable: `pm install` refuses them
     * with `INSTALL_FAILED_INVALID_APK: Full install must include a base package`, and
     * PackageManager cannot parse them — which is what the user sees as
     * "the download is not a valid signed app file". Matched on the `_installtime` infix
     * Play uses for these module splits, file name only: a package legitimately carrying
     * that string elsewhere in the path is not filtered.
     */
    internal fun isDynamiteModule(fileName: String): Boolean =
        fileName.substringAfterLast('/').contains("_installtime", ignoreCase = true)

    /**
     * Pick the one APK to advertise for a package, newest release first.
     *
     * A release split per architecture used to resolve to whichever variant happened to be
     * enumerated last, so an arm64 phone could be handed the x86_64 build. Everything before
     * the install then passed — the hash and signer come from the same chosen entry — and
     * Android rejected it only at the very end, after the download and the system prompt,
     * which read as the install doing nothing at all.
     *
     * Variants this device cannot run are dropped outright, so a package whose newest release
     * has no build for this architecture falls back to an older one that does rather than
     * disappearing.
     *
     * A split-set base wins ties against its own siblings: GMS's mirror entry carries the
     * base plus config splits plus runtime Dynamite modules (`*_installtime.apk`) under one
     * versionCode, all ABI-matching. The non-empty `requiredSplitTypes` marks the base — the
     * only file installable standalone — so a module can never win on hash order again.
     */
    internal fun selectVersion(
        candidates: List<VersionCandidate>,
        deviceAbis: List<String>,
    ): VersionCandidate? =
        candidates
            .mapNotNull { candidate -> abiRank(candidate.nativeCode, deviceAbis)?.let { candidate to it } }
            .minWithOrNull(
                compareByDescending<Pair<VersionCandidate, Int>> { it.first.added }
                    .thenBy { it.second }
                    .thenByDescending { it.first.requiredSplitTypes.isNotEmpty() }
                    .thenByDescending { it.first.versionCode }
                    .thenByDescending { it.first.size }
            )
            ?.first

    /**
     * How well an APK's `nativecode` fits this device — lower is better, null means it cannot
     * run here at all. An APK with no native code is architecture-independent so it always
     * fits, but it ranks behind any variant built for one of the device's own ABIs, matching
     * how Android itself prefers the most specific available.
     */
    private fun abiRank(nativeCode: List<String>, deviceAbis: List<String>): Int? {
        if (nativeCode.isEmpty()) return deviceAbis.size
        return nativeCode.mapNotNull { abi -> deviceAbis.indexOf(abi).takeIf { it >= 0 } }.minOrNull()
    }

}
