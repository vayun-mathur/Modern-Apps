package com.vayunmathur.maps.util

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vayunmathur.library.downloadservice.ModelDownloadItem
import com.vayunmathur.library.downloadservice.downloadModels
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checks for and fetches incremental map updates (Phase 2: download + store).
 *
 * One run: fetch `planet4.manifest.json` (kilobytes, any network), compare
 * its overlay sequence against the pinned manifest, and — when newer —
 * download the missing segments sequentially through the shared
 * [downloadModels] path (`.part` + Range resume, per-segment SHA-256, atomic
 * rename). Segments land under `overlays/<seq>/`; the base
 * `basemap.mamaps` is never touched, so peak transient disk is one segment.
 * On success the manifest text is pinned; the atomic reader swap to
 * `(base, seq)` is Phase 3 work — until then new segments sit inert on disk
 * and the map keeps reading the base.
 *
 * Two entry points: [checkAuto] (UNMETERED periodic check, gated on the
 * auto-update toggle) and [checkManual] (user-tapped, any connectivity).
 * Both KEEP an in-flight run rather than doubling it.
 */
class MapOverlayWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manifestUrl = inputData.getString(KEY_MANIFEST_URL) ?: MapOverlay.MANIFEST_URL
        // WorkManager can cold-start the process without MainActivity's TLS
        // init; without it the pinned manifest fetch below has no trust store.
        NetworkClient.init(applicationContext, TrustBundle.FIRST_PARTY)
        val root = applicationContext.getExternalFilesDir(null) ?: return Result.failure()
        recoverRebaseCrash(root)
        // No base, no overlay: the startup gate owns the full download.
        if (!File(root, MapTileCache.BASEMAP_ARCHIVE_FILE).exists()) {
            Log.status(TAG, "no base archive; skipping overlay check")
            return Result.success()
        }
        val text = manifestFetcher(manifestUrl) ?: return Result.retry()
        val manifest = MapOverlay.parse(text)
        if (manifest == null) {
            Log.status(TAG, "manifest unparseable or too new; skipping")
            return Result.success()
        }
        val ds = DataStoreUtils.getInstance(applicationContext)
        ds.setString(KEY_LAST_MANIFEST, text.take(MANIFEST_TEXT_CAP))
        ds.setLong(KEY_LAST_CHECK, System.currentTimeMillis())
        return applyManifest(root, ds, manifest, text)
    }

    private suspend fun applyManifest(
        root: File,
        ds: DataStoreUtils,
        manifest: MapOverlay.Manifest,
        text: String,
    ): Result {
        val pinned = MapOverlay.pinned(root)
        // Sequence numbers only order overlays of ONE base: a manifest for a new
        // base (planet3 -> planet4 restarts its sequence) is never "already
        // applied" just because the old base's stack reached a higher seq.
        val sameBase = pinned != null && pinned.baseBuildId == manifest.baseBuildId
        if (pinned != null && sameBase && pinned.overlaySeq >= manifest.overlaySeq) {
            Log.status(TAG, "overlay seq ${manifest.overlaySeq} already applied")
            return Result.success()
        }
        // Base mismatch: either a foreign overlay (refuse) or a server
        // rebase (refresh the base once, then drop the old stack). An empty
        // segment list with a newer seq, or for a different base than the
        // pinned one, is the rebase signal.
        if (!baseMatches(root, manifest)) {
            if (isRebaseSignal(manifest, pinned)) {
                return refreshBase(root, ds, manifest, text)
            }
            Log.status(TAG, "overlay seq ${manifest.overlaySeq} is for another base; skipping")
            return Result.success()
        }
        val missing = MapOverlay.missing(root, manifest)
        if (missing.isNotEmpty() && !downloadSegments(root, ds, manifest, missing)) return Result.retry()
        if (!MapOverlay.isApplied(root, manifest)) {
            Log.status(TAG, "segments landed but failed verification")
            return Result.retry()
        }
        MapOverlay.pin(root, text)
        ds.setLong(KEY_APPLIED_SEQ, manifest.overlaySeq)
        ds.setLong(KEY_PENDING_BYTES, 0L)
        Log.status(TAG, "overlay seq ${manifest.overlaySeq} stored (${missing.size} segments)")
        return Result.success()
    }

    /**
     * An empty segment list is the rebase signal when nothing is pinned yet, when the pinned
     * manifest is for another base (planet3 → planet4 restarts the sequence), or when its
     * sequence moved past the pinned one.
     */
    private fun isRebaseSignal(manifest: MapOverlay.Manifest, pinned: MapOverlay.Manifest?): Boolean {
        if (manifest.segments.isNotEmpty()) return false
        if (pinned == null || pinned.baseBuildId != manifest.baseBuildId) return true
        return manifest.overlaySeq > pinned.overlaySeq
    }

    /** Download [missing] sequentially, one segment at a time: peak transient is one segment. */
    private suspend fun downloadSegments(
        root: File,
        ds: DataStoreUtils,
        manifest: MapOverlay.Manifest,
        missing: List<MapOverlay.SegmentRef>,
    ): Boolean {
        ds.setLong(KEY_PENDING_BYTES, missing.sumOf { it.len })
        return missing.all { seg ->
            val file = MapOverlay.segmentFile(root, manifest.overlaySeq, seg)
            val rel = root.toURI().relativize(file.toURI()).path
            val item = ModelDownloadItem(
                url = seg.url,
                fileName = rel,
                description = "Map update ${manifest.overlaySeq} part ${seg.seq + 1}/${manifest.segments.size}",
                sha256 = seg.sha256,
            )
            downloadModels(applicationContext, ds, listOf(item))
        }
    }

    private fun recoverRebaseCrash(root: File) {
        val before = File(root, MapTileCache.BASEMAP_ARCHIVE_FILE).exists()
        MapOverlay.recoverRebaseCrash(root, MapTileCache.BASEMAP_ARCHIVE_FILE)
        val after = File(root, MapTileCache.BASEMAP_ARCHIVE_FILE).exists()
        if (before != after) Log.status(TAG, "rebase crash recovery restored the base")
    }

    /**
     * Whether the local base is the build [manifest] was made for. The base
     * header's `build_id` lives at bytes 16..24 (little-endian u64); the
     * manifest pins it as hex. A missing/unreadable header refuses.
     */
    private fun baseMatches(root: File, manifest: MapOverlay.Manifest): Boolean {
        if (manifest.baseLen > 0) {
            val len = File(root, MapTileCache.BASEMAP_ARCHIVE_FILE).length()
            if (len != manifest.baseLen) {
                Log.status(TAG, "base len $len != manifest ${manifest.baseLen}")
                return false
            }
        }
        val id = readBaseBuildId(File(root, MapTileCache.BASEMAP_ARCHIVE_FILE)) ?: return false
        if (id != manifest.baseBuildId) {
            val radix = MapOverlay.BUILD_ID_RADIX
            Log.status(TAG, "base build ${id.toString(radix)} != manifest ${manifest.baseBuildId.toString(radix)}")
            return false
        }
        return true
    }

    /**
     * Server rebase: the manifest pins a NEW base with an empty overlay.
     * Download the base once (the documented quarterly exception to no-2x:
     * old + `.next` coexist transiently), swap it in, and drop the old
     * overlay stack + segments. Readers mmap by path and reopen per surface,
     * so the rename-then-reload is the same atomicity as the initial
     * download; a crash mid-download resumes via `.part`, a crash
     * mid-swap keeps the old base (rename is atomic).
     */
    private suspend fun refreshBase(
        root: File,
        ds: DataStoreUtils,
        manifest: MapOverlay.Manifest,
        text: String,
    ): Result {
        Log.status(TAG, "rebase to seq ${manifest.overlaySeq}: refreshing base")
        ds.setLong(KEY_PENDING_BYTES, manifest.baseLen)
        val item = ModelDownloadItem(
            url = manifest.baseUrl,
            fileName = "${MapTileCache.BASEMAP_ARCHIVE_FILE}.next",
            description = "Map data consolidation",
            sha256 = null,
        )
        if (!downloadModels(applicationContext, ds, listOf(item))) return Result.retry()
        val next = File(root, "${MapTileCache.BASEMAP_ARCHIVE_FILE}.next")
        if (!next.isFile || (manifest.baseLen > 0 && next.length() != manifest.baseLen)) {
            Log.status(TAG, "rebased base failed verification")
            return Result.retry()
        }
        if (readBaseBuildId(next) != manifest.baseBuildId) {
            Log.status(TAG, "rebased base build mismatch; refusing swap")
            next.delete()
            return Result.success()
        }
        val current = File(root, MapTileCache.BASEMAP_ARCHIVE_FILE)
        val backup = File(root, "${MapTileCache.BASEMAP_ARCHIVE_FILE}.prev")
        if (backup.exists()) backup.delete()
        // Two renames, crash-safe either way: a leftover `.prev` with no
        // `.next` work means the swap completed (drop it); a leftover
        // `.next` means it did not (resume it next run).
        if (!current.renameTo(backup)) return Result.retry()
        if (!next.renameTo(current)) {
            backup.renameTo(current)
            return Result.retry()
        }
        backup.delete()
        File(root, MapOverlay.OVERLAY_ROOT).deleteRecursively()
        MapOverlay.pin(root, text)
        ds.setLong(KEY_APPLIED_SEQ, manifest.overlaySeq)
        ds.setLong(KEY_PENDING_BYTES, 0L)
        Log.status(TAG, "rebase seq ${manifest.overlaySeq} complete")
        return Result.success()
    }

    companion object {
        private const val TAG = "MapOverlay"
        private const val KEY_MANIFEST_URL = "manifest_url"
        /** DataStore keys the Settings row reads (last check text is capped). */
        const val KEY_LAST_CHECK = "overlay_last_check"
        const val KEY_APPLIED_SEQ = "overlay_applied_seq"
        const val KEY_PENDING_BYTES = "overlay_pending_bytes"
        const val KEY_LAST_MANIFEST = "overlay_last_manifest"
        private const val MANIFEST_TEXT_CAP = 8 * 1024

        private const val MAX_RUN_ATTEMPTS = 6
        private const val BACKOFF_SECONDS = 30L

        /** Periodic auto check: UNMETERED only (multi-hundred-MB segments). */
        fun checkAuto(context: Context) =
            enqueue(context, MapOverlay.MANIFEST_URL, requireUnmetered = true)

        /** Manual check from Settings: any connectivity. */
        fun checkManual(context: Context) =
            enqueue(context, MapOverlay.MANIFEST_URL, requireUnmetered = false)

        /**
         * Daily auto-check chain (UNMETERED + battery-not-low). KEEP leaves an
         * already-scheduled chain alone. The worker self-skips when applied
         * (one small manifest GET), so the periodic slot costs nothing idle.
         * Call on app start when the auto-update toggle is on; [cancelAuto]
         * when it is off.
         */
        fun scheduleAuto(context: Context) {
            val request = PeriodicWorkRequestBuilder<MapOverlayWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Drop the periodic chain (auto-update toggled off). */
        fun cancelAuto(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
        }

        private fun enqueue(context: Context, manifestUrl: String, requireUnmetered: Boolean) {
            val request = OneTimeWorkRequestBuilder<MapOverlayWorker>()
                .setInputData(workDataOf(KEY_MANIFEST_URL to manifestUrl))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
                        )
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (requireUnmetered) UNIQUE_AUTO else UNIQUE_MANUAL,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private const val UNIQUE_AUTO = "map_overlay_auto"
        private const val UNIQUE_MANUAL = "map_overlay_manual"
        private const val UNIQUE_PERIODIC = "map_overlay_periodic"

        /** Attempt count guard lives in doWork's caller: WorkManager retries. */
        fun maxAttempts(): Int = MAX_RUN_ATTEMPTS

        /**
         * Manifest fetch seam (test-only override): production always uses the
         * pinned [fetchText]; tests substitute a local manifest so the apply/rebase
         * state machine can run without the live data host. Same pattern as the
         * HTTP seams other modules added for their fetchers.
         */
        internal var manifestFetcher: suspend (String) -> String? = { fetchText(it) }
    }
}

private const val HTTP_OK = 200
private const val BUILD_ID_OFFSET = 16L
private const val BUILD_ID_BYTES = 8

/**
 * GET [url] as text over the pinned FIRST_PARTY bundle, or null on any
 * failure (caller retries). Pinned, not plain HTTPS: the manifest names
 * segment URLs + SHA-256, so a MITM-swapped manifest must not verify —
 * and with pinning it cannot even be served.
 */
private suspend fun fetchText(url: String): String? = runCatching {
    val (status, body) = NetworkClient.performRequestBytes(url)
    if (status != HTTP_OK) return null
    body.toString(Charsets.UTF_8)
}.getOrNull()

/** The base archive's header `build_id` (bytes 16..24 LE), or null. */
private fun readBaseBuildId(file: File): Long? = runCatching {
    if (!file.isFile || file.length() < BUILD_ID_OFFSET + BUILD_ID_BYTES) return null
    java.io.RandomAccessFile(file, "r").use { raf ->
        raf.seek(BUILD_ID_OFFSET)
        val buf = ByteArray(BUILD_ID_BYTES)
        raf.readFully(buf)
        java.nio.ByteBuffer.wrap(buf).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
    }
}.getOrNull()
