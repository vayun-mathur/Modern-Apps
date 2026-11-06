package com.vayunmathur.library.downloadservice

import android.app.DownloadManager
import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.downloadservice.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Internal unit of work: one on-disk [fileName] fetched from a single [url] with
 * an optional [sha256] verified after the download completes. Both the plain
 * `Triple(url, fileName, description)` API and the [ModelDownloadItem] API funnel
 * into this so the download loop only deals with one shape.
 *
 * [fileName] may contain a subdirectory (`small100/encoder.ncnn.bin`), which the
 * download loop creates on demand.
 */
internal data class DownloadSpec(
    val fileName: String,
    val description: String,
    val url: String,
    val sha256: String? = null,
    val expectedBytes: Long? = null,
)

private fun Triple<String, String, String>.toSpec() =
    DownloadSpec(fileName = second, description = third, url = first)

private fun ModelDownloadItem.toSpec() =
    DownloadSpec(
        fileName = fileName,
        description = description,
        url = url,
        sha256 = sha256,
        expectedBytes = expectedBytes,
    )

@Composable
fun InitialDownloadChecker(
    ds: DataStoreUtils,
    filesToDownload: List<Triple<String, String, String>>,
    mainPage: @Composable () -> Unit
) = InitialDownloadCheckerSpecs(ds, filesToDownload.map { it.toSpec() }, mainPage)

/**
 * Model variant of [InitialDownloadChecker]: each [ModelDownloadItem] is fetched
 * from the self-hosted mirror only (no third-party fallback), with optional
 * SHA-256 integrity verification. This is the supply-chain mitigation #1 entry
 * point (see [ModelUrls]).
 */
@Composable
fun InitialModelDownloadChecker(
    ds: DataStoreUtils,
    models: List<ModelDownloadItem>,
    mainPage: @Composable () -> Unit
) = InitialDownloadCheckerSpecs(ds, models.map { it.toSpec() }, mainPage)

@Composable
private fun InitialDownloadCheckerSpecs(
    ds: DataStoreUtils,
    specs: List<DownloadSpec>,
    mainPage: @Composable () -> Unit
) {
    val context = LocalContext.current
    // Gate on the actual presence of the requested files on disk rather than a
    // DataStore flag: if they are all already downloaded, go straight to the app;
    // otherwise show the download screen. This self-heals if a file is deleted or
    // the flag drifts out of sync with disk.
    var filesPresent by remember {
        mutableStateOf(allFilesPresent(context, specs))
    }
    if (filesPresent) {
        mainPage()
    } else {
        InitialDownloadScreen(ds, specs, onAllDownloaded = { filesPresent = true })
    }
}

private fun allFilesPresent(
    context: Context,
    specs: List<DownloadSpec>,
): Boolean {
    val dir = context.getExternalFilesDir(null)
    val ds = DataStoreUtils.getInstance(context)
    return manifestSatisfied(dir, ds, specsToManifest(specs))
}

/**
 * The on-disk gate state for one download set: the expected SHA-256 and size
 * per file, read from the current [DownloadSpec]s.
 *
 * Compared against the real files plus the `verified_*` markers the download
 * loop writes, so the gate never treats an unverified file as complete. Pure
 * and host-testable: [dir] stands in for the app's external files dir.
 */
internal data class VerifiedManifest(val entries: List<VerifiedEntry>)

internal data class VerifiedEntry(
    val fileName: String,
    val sha256: String?,
    val expectedBytes: Long?,
)

internal fun specsToManifest(specs: List<DownloadSpec>): VerifiedManifest =
    VerifiedManifest(
        specs.map { VerifiedEntry(it.fileName, it.sha256, it.expectedBytes) },
    )

/**
 * Cheap per-launch gate: every file exists at its expected size (when pinned),
 * and every hash-pinned file carries a `verified_<fileName>` marker equal to the
 * currently-expected SHA-256.
 *
 * The marker is what makes a model update re-download: bump the pinned hash and
 * the stored marker no longer matches, so the set reads as incomplete. The size
 * check catches a truncated file without rehashing gigabytes on every start.
 * Files with no pinned hash and no pinned size pass on existence, exactly as
 * before, so unpinned consumers (maps basemap, beacon stores without a hash)
 * see no behaviour change.
 */
internal fun manifestSatisfied(
    dir: File?,
    verifiedMarker: (String) -> String?,
    manifest: VerifiedManifest,
): Boolean {
    if (dir == null) return false
    return manifest.entries.all { entry ->
        val file = File(dir, entry.fileName)
        if (!file.isFile) return@all false
        if (entry.expectedBytes != null && file.length() != entry.expectedBytes) return@all false
        val want = entry.sha256 ?: return@all true
        verifiedMarker("verified_${entry.fileName}") == want
    }
}

internal fun manifestSatisfied(
    dir: File?,
    ds: DataStoreUtils,
    manifest: VerifiedManifest,
): Boolean = manifestSatisfied(dir, ds::getString, manifest)

/** Records that [spec]'s file on disk matches its currently-pinned SHA-256. */
internal suspend fun markVerified(ds: DataStoreUtils, spec: DownloadSpec) {
    val sha = spec.sha256 ?: return
    ds.setString("verified_${spec.fileName}", sha)
}

@Composable
@Suppress("LongMethod")
private fun InitialDownloadScreen(
    ds: DataStoreUtils,
    specs: List<DownloadSpec>,
    onAllDownloaded: () -> Unit = {},
) {
    val context = LocalContext.current

    // The download runs in a WorkManager job, not in this composition: it used to live in a
    // LaunchedEffect, so leaving the screen (or a configuration change) cancelled a multi-hundred-MB
    // transfer. The screen only enqueues and observes now; `.part` files let a stopped run resume.
    val workName = remember(specs) { ModelDownloadWorker.uniqueName(specs) }
    LaunchedEffect(specs) { ModelDownloadWorker.enqueue(context, specs) }

    val workInfo by remember(workName) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName)
    }.collectAsState(emptyList())
    val state = workInfo.firstOrNull()?.state

    LaunchedEffect(state) {
        // SUCCEEDED means runDownloadsCore verified every file, so the gate below
        // passes; still re-check disk rather than trusting the worker result, so a
        // file deleted between the last byte and this frame re-shows the screen.
        if (state == WorkInfo.State.SUCCEEDED && allFilesPresent(context, specs)) {
            onAllDownloaded()
        }
    }

    Scaffold { paddingValues ->
        Column(
            Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.initializing_system),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.downloading_required_components_for_this),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            // A silently-stuck screen was the old failure mode: every attempt threw, the loop gave
            // up, and nothing said so. Offer an explicit retry instead.
            if (state == WorkInfo.State.FAILED) {
                Text(
                    text = stringResource(R.string.download_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Button(onClick = { ModelDownloadWorker.retry(context, specs) }) {
                    Text(stringResource(R.string.retry))
                }
                Spacer(Modifier.height(24.dp))
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(specs, key = { it.fileName }) { spec ->
                    // Each item observes its own progress and speed from DataStore.
                    // "Done" needs both progress (1.0) and this run's verified
                    // marker — a stale 1.0 left by an older app version must not
                    // read as complete. Unpinned files keep the old threshold.
                    val progress by ds.doubleFlow("progress_${spec.fileName}").collectAsState(0.0)
                    val speedMbps by ds.doubleFlow("speed_${spec.fileName}").collectAsState(0.0)
                    val marker by ds.stringFlow("verified_${spec.fileName}")
                        .collectAsState(initial = null as String?)
                    val verified = spec.sha256?.let { marker == it } ?: true
                    val isDone = progress >= 0.999 && verified

                    FileProgressItem(
                        label = spec.description,
                        progress = progress,
                        speedMbps = speedMbps,
                        isDone = isDone
                    )
                }
            }
        }
    }
}

private const val SPEED_WINDOW_MS = 4000L
private const val BYTES_PER_MEGABIT = 1_000_000.0
private const val MIN_SPEED_SPAN_SEC = 0.5

/** How often to publish progress/speed to DataStore while streaming. */
private const val PUBLISH_INTERVAL_MS = 500L

/** Bounded retry for transient network failures per file. */
private const val MAX_ATTEMPTS = 4
private const val RETRY_DELAY_MS = 2000L

private const val DOWNLOAD_BUFFER_SIZE = 1 shl 16
private const val MILLIS_PER_SECOND = 1000.0
private const val BITS_PER_BYTE = 8.0
private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 30_000

private const val TAG = "ModelDownload"

/**
 * Mirror-only on-demand download: fetches each [ModelDownloadItem] from the
 * self-hosted mirror (no third-party fallback), with optional SHA-256
 * verification (supply-chain mitigation #1). Publishes the same `progress_*` /
 * `speed_*` DataStore keys as the initial screen and skips files already present
 * on disk. Returns whether every file ended up on disk and verified.
 *
 * This runs in the caller's scope, so it is for downloads the caller is already
 * waiting on; first-launch gating goes through [ModelDownloadWorker] instead.
 */
suspend fun downloadModels(
    context: Context,
    ds: DataStoreUtils,
    models: List<ModelDownloadItem>,
) = runDownloadsCore(context, ds, models.map { it.toSpec() })

/**
 * Self-managed streaming download loop. Each spec is streamed directly into the
 * app's [Context.getExternalFilesDir] with [java.net.HttpURLConnection], so the
 * files are ordinary app-owned files with no `DownloadManager` database row for
 * the system download provider to track or garbage-collect (the root cause of
 * the periodic model re-download).
 *
 * Files are processed sequentially; each publishes `progress_*` / `speed_*` for
 * the UI. On checksum mismatch the download is retried; on final failure any
 * `.part` file is left in place so the next run resumes it.
 *
 * Returns true only when every spec ended up on disk and verified, so the caller
 * ([ModelDownloadWorker]) can ask WorkManager to retry rather than reporting a
 * success that leaves the app gated forever.
 */
internal suspend fun runDownloadsCore(
    context: Context,
    ds: DataStoreUtils,
    specs: List<DownloadSpec>,
    targetDir: File? = null,
): Boolean = withContext(Dispatchers.IO) {
    val dir = targetDir ?: context.getExternalFilesDir(null)
    var allDownloaded = true
    for (spec in specs) {
        // Sever any legacy DownloadManager claim on an already-present file before
        // we treat it as a plain app file (one-time, guarded — see fn docs).
        migrateLegacyDownload(context, ds, dir, spec)

        val file = File(dir, spec.fileName)
        // A file already on disk still has to prove itself: size first (cheap),
        // then the pinned SHA when the verified marker does not match. A stale
        // bundle from an older app version (different pinned hash) or a
        // truncated one (wrong size) is deleted and re-downloaded below.
        if (file.exists()) {
            if (sizeOk(file, spec.expectedBytes) && checksumMatches(file, ds, spec)) {
                ds.setDouble("progress_${spec.fileName}", 1.0)
                continue
            } else {
                file.delete()
                ds.setDouble("progress_${spec.fileName}", 0.0)
            }
        }
        if (!downloadSpec(ds, dir, spec)) allDownloaded = false
    }
    allDownloaded
}

/**
 * True when [file] may be skipped without re-downloading: size matches when
 * pinned, and the SHA matches when pinned. The SHA is only recomputed when the
 * file's stored `verified_*` marker differs from the currently-pinned hash —
 * which covers a first install (no marker yet) and a model update (pinned hash
 * bumped, marker stale). A match writes the marker so the launch gate and
 * later runs skip the rehash.
 */
internal suspend fun checksumMatches(
    file: File,
    ds: DataStoreUtils,
    spec: DownloadSpec,
): Boolean {
    val want = spec.sha256 ?: return true
    if (ds.getString("verified_${spec.fileName}") == want) return true
    if (!checksumOk(file, want)) return false
    markVerified(ds, spec)
    return true
}

/** True when [file]'s size matches [expectedBytes], or when no size is pinned. */
internal fun sizeOk(file: File, expectedBytes: Long?): Boolean {
    if (expectedBytes == null) return true
    return file.isFile && file.length() == expectedBytes
}

private suspend fun downloadSpec(
    ds: DataStoreUtils,
    dir: File?,
    spec: DownloadSpec,
): Boolean {
    val finalFile = File(dir, spec.fileName)
    val partFile = File(dir, "${spec.fileName}.part")

    repeat(MAX_ATTEMPTS) { attempt ->
        try {
            streamToPart(ds, spec, partFile)
            if (sizeOk(partFile, spec.expectedBytes) && checksumOk(partFile, spec.sha256)) {
                finalizeDownload(ds, spec, partFile, finalFile)
                return true
            }
            // Corrupt/tampered/short copy — drop the partial and re-fetch from scratch.
            Log.status(TAG, "checksum mismatch for ${spec.fileName}; refetching")
            partFile.delete()
        } catch (e: CancellationException) {
            // Cooperative cancellation (worker stopped): keep the `.part` so the
            // next run resumes via the Range header.
            throw e
        } catch (expected: IOException) {
            // Transient network error — keep the `.part` for resume and back off.
            Log.status(TAG, "attempt ${attempt + 1}/$MAX_ATTEMPTS failed for ${spec.fileName}", expected)
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
    }
    Log.error(TAG, "giving up on ${spec.fileName} after $MAX_ATTEMPTS attempts")
    return false
}

/**
 * Streams [spec] from the network into [partFile]. Resumes an existing partial
 * with a `Range` request, appending on HTTP 206 and restarting (truncating) on
 * 200. Publishes throttled `progress_*` / `speed_*` while streaming.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
private suspend fun streamToPart(
    ds: DataStoreUtils,
    spec: DownloadSpec,
    partFile: File,
) {
    // fileName can name a subdirectory ("small100/encoder.ncnn.bin"). FileOutputStream does not
    // create missing parents, so without this every attempt died with ENOENT before a single byte
    // was written — which is exactly how the SMaLL-100 download hung at 0% with no error.
    partFile.parentFile?.mkdirs()
    var startOffset = if (partFile.exists()) partFile.length() else 0L
    val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        requestMethod = "GET"
        if (startOffset > 0) setRequestProperty("Range", "bytes=$startOffset-")
    }
    try {
        conn.connect()
        // Mirror MapTileCache.kt: treat 206 as a resume, 200 as a full restart
        // (the server ignored our Range).
        val append = when (val code = conn.responseCode) {
            HttpURLConnection.HTTP_PARTIAL -> true
            HttpURLConnection.HTTP_OK -> {
                startOffset = 0L
                false
            }
            else -> throw IOException("Unexpected HTTP $code for ${spec.url}")
        }
        // contentLengthLong is the *remaining* bytes; add the resume offset for the total.
        val remaining = conn.contentLengthLong
        val total = if (remaining >= 0) startOffset + remaining else -1L

        publishInitialProgress(ds, spec, total, startOffset)

        FileOutputStream(partFile, append).use { output ->
            conn.inputStream.use { input ->
                copyStreamed(input, output, ds, spec, total, startOffset)
            }
            output.fd.sync()
        }
    } finally {
        conn.disconnect()
    }
}

/** Publishes the resume offset as initial progress before streaming starts. */
private suspend fun publishInitialProgress(
    ds: DataStoreUtils,
    spec: DownloadSpec,
    total: Long,
    startOffset: Long
) {
    if (total > 0) {
        ds.setDouble(
            "progress_${spec.fileName}",
            (startOffset.toDouble() / total).coerceIn(0.0, 1.0)
        )
    }
}

/** Copies [input] to [output], publishing throttled progress/speed to [ds]. */
private suspend fun copyStreamed(
    input: java.io.InputStream,
    output: FileOutputStream,
    ds: DataStoreUtils,
    spec: DownloadSpec,
    total: Long,
    startOffset: Long
) {
    val tracker = ProgressTracker(ds, spec, total, startOffset)
    val buf = ByteArray(DOWNLOAD_BUFFER_SIZE)
    while (true) {
        coroutineContext.ensureActive()
        val n = input.read(buf)
        if (n < 0) break
        output.write(buf, 0, n)
        tracker.advance(n)
    }
}

/** Throttled progress/speed publisher for one streaming download. */
private class ProgressTracker(
    private val ds: DataStoreUtils,
    private val spec: DownloadSpec,
    private val total: Long,
    startOffset: Long
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var soFar = startOffset
    private var lastPublish = 0L

    suspend fun advance(bytes: Int) {
        soFar += bytes
        val now = System.currentTimeMillis()
        if (now - lastPublish < PUBLISH_INTERVAL_MS) return
        lastPublish = now
        publishProgress()
        publishSpeed(now)
    }

    private suspend fun publishProgress() {
        if (total > 0) {
            ds.setDouble(
                "progress_${spec.fileName}",
                (soFar.toDouble() / total).coerceIn(0.0, 1.0)
            )
        }
    }

    // Moving-average speed over the last SPEED_WINDOW_MS so the
    // reading stays stable across bursty reads.
    private suspend fun publishSpeed(now: Long) {
        samples.addLast(now to soFar)
        while (samples.size > 1 && now - samples.first().first > SPEED_WINDOW_MS) {
            samples.removeFirst()
        }
        val (oldestTime, oldestBytes) = samples.first()
        val spanSec = (now - oldestTime) / MILLIS_PER_SECOND
        if (spanSec >= MIN_SPEED_SPAN_SEC) {
            val bits = (soFar - oldestBytes) * BITS_PER_BYTE
            val speedMbps = bits / BYTES_PER_MEGABIT / spanSec
            ds.setDouble("speed_${spec.fileName}", speedMbps.coerceAtLeast(0.0))
        }
    }
}

/** Atomically promotes a verified [partFile] to [finalFile] and marks it done. */
private suspend fun finalizeDownload(
    ds: DataStoreUtils,
    spec: DownloadSpec,
    partFile: File,
    finalFile: File,
) {
    if (finalFile.exists()) finalFile.delete()
    if (!partFile.renameTo(finalFile)) {
        // Rename can fail across some filesystems; fall back to a copy.
        partFile.copyTo(finalFile, overwrite = true)
        partFile.delete()
    }
    // The file was hash- (and size-) verified before this rename, so record it:
    // the launch gate trusts this marker instead of rehashing gigabytes.
    markVerified(ds, spec)
    ds.setDouble("progress_${spec.fileName}", 1.0)
    ds.setDouble("speed_${spec.fileName}", 0.0)
}

/**
 * One-time, crash-guarded severing of a legacy `DownloadManager` claim on an
 * already-present file. The file currently on disk was created by
 * `DownloadManager` and still has a tracking row (`dlid_<fileName>`), so the
 * download provider could sweep it one last time. To drop that row without
 * re-downloading the (multi-GB) file, we move the real file aside first so
 * `DownloadManager.remove()` finds nothing to delete, then move it back.
 *
 * This is the only remaining reference to `DownloadManager`; once the id is
 * cleared it never runs again for that file.
 */
private suspend fun migrateLegacyDownload(
    context: Context,
    ds: DataStoreUtils,
    dir: File?,
    spec: DownloadSpec,
) {
    val finalFile = File(dir, spec.fileName)
    val migrating = File(dir, "${spec.fileName}.migrating")

    // Crash recovery: a leftover `.migrating` (with no final file) means a prior
    // run renamed away but didn't finish — restore it before doing anything else.
    if (migrating.exists() && !finalFile.exists()) {
        migrating.renameTo(finalFile)
    }

    val dlid = ds.getLong("dlid_${spec.fileName}") ?: 0L
    if (dlid <= 0L) return
    if (!finalFile.exists()) {
        // Nothing to protect; just drop the stale id.
        ds.setLong("dlid_${spec.fileName}", 0L)
        return
    }

    try {
        if (finalFile.renameTo(migrating)) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            // The tracked path is now empty, so this drops only the DB row.
            dm.remove(dlid)
            migrating.renameTo(finalFile)
        }
    } catch (_: Exception) {
        // Best effort: ensure the file is restored if anything went wrong.
        if (!finalFile.exists() && migrating.exists()) migrating.renameTo(finalFile)
    } finally {
        ds.setLong("dlid_${spec.fileName}", 0L)
    }
}

/**
 * True when [file] matches [expected] SHA-256. A null [expected] passes
 * unconditionally (integrity is opt-in until the mirror publishes checksums).
 */
private fun checksumOk(file: File, expected: String?): Boolean {
    val exp = expected ?: return true
    if (!file.exists()) return false
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    val actual = md.digest().joinToString("") { "%02x".format(it) }
    return actual.equals(exp, ignoreCase = true)
}

@Composable
fun FileProgressItem(
    label: String,
    progress: Double,
    speedMbps: Double,
    isDone: Boolean
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.toFloat(),
        label = "smooth_progress"
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1.0f),
                maxLines = 1
            )

            if (!isDone && progress > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        text = stringResource(R.string.mbps, speedMbps.round(1).toString()),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = if (isDone) stringResource(R.string.completed) else stringResource(R.string.downloading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "${(progress * 100).round(1)}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            strokeCap = StrokeCap.Round,
            color = if (isDone) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        )
    }
}
