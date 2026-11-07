package com.vayunmathur.updater.platform

import android.content.Context
import android.os.storage.StorageManager
import com.vayunmathur.library.log.Log
import androidx.core.content.getSystemService
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.updater.domain.OtaDownloadPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

private const val TAG = "OtaDownloader"

private const val CONNECT_TIMEOUT_MS = 30_000
private const val READ_TIMEOUT_MS = 30_000
private const val BUFFER_SIZE = 1 shl 16

/** How often to report progress upward. A 1-2 GB read would otherwise report thousands of times. */
private const val PROGRESS_INTERVAL_MS = 500L

/** 416 Range Not Satisfiable. `HttpURLConnection` has no constant for it. */
private const val HTTP_RANGE_NOT_SATISFIABLE = 416

/**
 * Fetches an OTA package to `/data/ota_package/update.zip`.
 *
 * **That path is not a choice.** update_engine opens the file itself, as its own uid and under
 * its own SELinux domain, so it has to live somewhere update_engine is allowed to read.
 * `/data/ota_package` is the directory AOSP labels for exactly this, and the app's
 * `ACCESS_CACHE_FILESYSTEM` permission is what grants write access to it — that permission is in
 * the manifest for this one reason. App-private storage would not work.
 *
 * One fixed filename means the artifact's own name has to be remembered separately, in
 * [UpdaterPreferences.DOWNLOAD_FILE]: a partial file is only resumable if it is a prefix of the
 * artifact we are about to request.
 *
 * The download is resumable, which at this size is not a nicety — a one to two gigabyte transfer
 * will be interrupted. Nothing here inspects what it downloaded; the file is untrusted until
 * [OtaInstaller] has run `RecoverySystem.verifyPackage` over it.
 */
object OtaDownloader {

    /** Where update_engine can reach it. See the class doc. */
    val UPDATE_PATH = File("/data/ota_package/update.zip")

    /** Test seam: replaces the staged-package file (unwritable off-device). Null means [UPDATE_PATH]. */
    internal var updateFileOverride: File? = null

    /** Test seam: replaces opening the HTTPS connection. Null means the real [URL] connection. */
    internal var connectionOverride: ((url: String, offset: Long) -> HttpsURLConnection)? = null

    internal fun updateFile(): File = updateFileOverride ?: UPDATE_PATH

    sealed interface Result {
        data class Downloaded(val incremental: Boolean) : Result

        /** Neither artifact is published for this build. Ordinary, not an error. */
        data object NotFound : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Download the best available artifact for [targetBuild].
     *
     * Tries the incremental, falling back to the full package on a 404 - the normal outcome
     * after skipping a release. An incremental update_engine has already refused to initialise
     * from is skipped outright; see [UpdaterPreferences.FAILED_INCREMENTAL].
     *
     * Broad catch is deliberate: the download path throws undocumented RuntimeExceptions
     * (not just IOException), which read as "download failed" rather than a worker crash.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun download(
        context: Context,
        device: String,
        currentBuild: String,
        targetBuild: String,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val store = DataStoreUtils.getInstance(context)
        val artifacts = OtaDownloadPlan.artifacts(device, currentBuild, targetBuild)
        val failedIncremental = store.getStringAwait(UpdaterPreferences.FAILED_INCREMENTAL)
        val incremental = artifacts.incremental.takeIf { it != null && it != failedIncremental }

        try {
            updateFile().parentFile?.mkdirs()
            val remembered = store.getStringAwait(UpdaterPreferences.DOWNLOAD_FILE)
            // Only a partial file whose artifact we can still name is worth resuming.
            val target = resolveTarget(remembered, incremental, artifacts.full)
            if (!target.resumable) updateFile().delete()

            return@withContext when (val request = requestArtifact(artifacts, target)) {
                is ArtifactRequest.Complete -> Result.Downloaded(request.wanted == artifacts.incremental)
                ArtifactRequest.NotFound -> Result.NotFound
                is ArtifactRequest.Stream -> streamResult(context, store, request, artifacts, onProgress)
            }
        } catch (e: CancellationException) {
            // The partial file and the remembered name both stay, so the next run resumes.
            throw e
        } catch (e: Exception) {
            Log.status(TAG, "download failed", e)
            Result.Failed("the update could not be downloaded")
        }
    }

    /** Stream an open artifact body and map the outcome onto [Result]. */
    private suspend fun streamResult(
        context: Context,
        store: DataStoreUtils,
        request: ArtifactRequest.Stream,
        artifacts: OtaDownloadPlan.Artifacts,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Result {
        val outcome = stream(
            context, store, request.connection, request.code,
            request.wanted, request.offset, onProgress,
        )
        return when (outcome) {
            is Stream.Error -> Result.Failed(outcome.reason)
            Stream.Ok -> Result.Downloaded(request.wanted == artifacts.incremental)
        }
    }

    /** Which artifact to request and from what byte offset. */
    private data class DownloadTarget(val wanted: String, val offset: Long, val resumable: Boolean)

    private fun resolveTarget(
        remembered: String?,
        incremental: String?,
        full: String,
    ): DownloadTarget {
        val resumable = remembered != null && (remembered == incremental || remembered == full)
        val wanted = if (resumable) remembered!! else incremental ?: full
        val offset = if (resumable) updateFile().length() else 0L
        return DownloadTarget(wanted, offset, resumable)
    }

    /** An open connection plus the request it answers, after 416/404 handling. */
    private sealed interface ArtifactRequest {
        /** The server says the resumed file is already complete — no body to stream. */
        data class Complete(val wanted: String) : ArtifactRequest

        /** Neither artifact is published for this build. Ordinary, not an error. */
        data object NotFound : ArtifactRequest

        data class Stream(
            val connection: HttpsURLConnection,
            val code: Int,
            val wanted: String,
            val offset: Long,
        ) : ArtifactRequest
    }

    /**
     * Open [target] and handle the two non-body answers.
     */
    private fun requestArtifact(
        artifacts: OtaDownloadPlan.Artifacts,
        target: DownloadTarget,
    ): ArtifactRequest {
        // A resumed request the server says is already complete. verifyPackage is next and
        // will catch the file if it is not in fact whole, so trusting this costs nothing.
        val first = open(target.wanted, target.offset)
        val firstCode = first.responseCode
        if (firstCode == HTTP_RANGE_NOT_SATISFIABLE) {
            first.disconnect()
            Log.status(TAG, "${target.wanted} was already downloaded")
            return ArtifactRequest.Complete(target.wanted)
        }
        val fallback = maybeFallbackToFull(first, firstCode, artifacts, target)
        if (fallback != null) return fallback
        if (firstCode == HttpURLConnection.HTTP_NOT_FOUND) {
            first.errorStream?.close()
            first.disconnect()
            return ArtifactRequest.NotFound
        }
        return ArtifactRequest.Stream(first, firstCode, target.wanted, target.offset)
    }

    /**
     * No incremental for this exact source build — the usual case after skipping a
     * release. Start the full package from scratch; the bytes we have are not a prefix
     * of it. Null when no fallback applies (the caller handles other codes).
     */
    private fun maybeFallbackToFull(
        connection: HttpsURLConnection,
        code: Int,
        artifacts: OtaDownloadPlan.Artifacts,
        target: DownloadTarget,
    ): ArtifactRequest? {
        if (code != HttpURLConnection.HTTP_NOT_FOUND || target.wanted == artifacts.full) return null
        connection.errorStream?.close()
        connection.disconnect()
        Log.status(TAG, "${target.wanted} is not published; falling back to ${artifacts.full}")
        updateFile().delete()
        val retry = open(artifacts.full, 0L)
        val retryCode = retry.responseCode
        if (retryCode == HttpURLConnection.HTTP_NOT_FOUND) {
            retry.errorStream?.close()
            retry.disconnect()
            return ArtifactRequest.NotFound
        }
        return ArtifactRequest.Stream(retry, retryCode, artifacts.full, 0L)
    }

    /** Forget the staged package entirely, so the next run starts clean rather than resuming. */
    suspend fun discard(context: Context) {
        updateFile().delete()
        DataStoreUtils.getInstance(context).setString(UpdaterPreferences.DOWNLOAD_FILE, "")
    }

    private fun open(fileName: String, offset: Long): HttpsURLConnection {
        val url = "${SystemBuild.otaServer()}/$fileName"
        connectionOverride?.let { return it(url, offset) }
        // HTTPS only. The signature check is the real boundary, but there is no reason to let a
        // gigabyte of anything arrive over a connection nobody authenticated.
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.useCaches = false
        if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
        return connection
    }

    private sealed interface Stream {
        data object Ok : Stream
        data class Error(val reason: String) : Stream
    }

    private suspend fun stream(
        context: Context,
        store: DataStoreUtils,
        connection: HttpsURLConnection,
        code: Int,
        fileName: String,
        requestedOffset: Long,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Stream {
        try {
            val append = appendModeFor(code) ?: return Stream.Error("the OTA server returned HTTP $code")
            val offset = if (append) requestedOffset else 0L

            // contentLengthLong is the REMAINING bytes on a 206, not the file size.
            val remaining = connection.contentLengthLong
            val total = if (remaining >= 0) offset + remaining else -1L
            if (total > 0) allocate(context, total - offset)

            // Recorded before the first byte lands: a crash mid-download must leave the name of
            // whatever is actually in the file, not the name of the last completed one.
            store.setString(UpdaterPreferences.DOWNLOAD_FILE, fileName)

            val soFar = copyStream(connection, append, offset, total, onProgress)
            if (total > 0 && soFar != total) {
                return Stream.Error("the download ended early ($soFar of $total bytes)")
            }
            onProgress(soFar, total)
            return Stream.Ok
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Whether the body appends to the partial file. Null when [code] is not a body at all.
     * The server ignoring the Range and sending the whole file means what we have is not
     * a prefix of what is arriving, so start over.
     */
    private fun appendModeFor(code: Int): Boolean? = when (code) {
        HttpURLConnection.HTTP_PARTIAL -> true
        HttpURLConnection.HTTP_OK -> false
        else -> null
    }

    /** Stream the body to [UPDATE_PATH], reporting progress; returns the total bytes on disk. */
    private suspend fun copyStream(
        connection: HttpsURLConnection,
        append: Boolean,
        offset: Long,
        total: Long,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Long {
        var soFar = offset
        var lastReport = 0L
        onProgress(soFar, total)

        FileOutputStream(updateFile(), append).use { output ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    soFar += read
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                        lastReport = now
                        onProgress(soFar, total)
                    }
                }
            }
            // Force the bytes out before the file is handed to verifyPackage.
            output.fd.sync()
        }
        return soFar
    }

    /**
     * Ask the system to make room, aggressively.
     *
     * Best-effort: failing here is not fatal, because space is often freed later and the write
     * itself will say so if it is not.
     *
     * Reached by reflection because both the three-argument `allocateBytes` and
     * `FLAG_ALLOCATE_AGGRESSIVE` are `@hide`. The GrapheneOS updater calls them directly
     * (`Service.java:396`) because it is built against the platform APIs; this app is built
     * against the public SDK, so the same call will not compile. It resolves at runtime because
     * we are a privileged system app.
     *
     * The aggressive flag is the point of the exercise — it is what lets the allocation evict
     * cached data to make room for a 1-2 GB package — so falling back to the public two-argument
     * overload would quietly defeat it. If reflection fails we simply do not pre-allocate, and
     * the write reports the real problem if there is one.
     */
    private fun allocate(context: Context, bytes: Long) {
        runCatching {
            val storage = context.getSystemService<StorageManager>() ?: return
            val uuid = storage.getUuidForPath(updateFile())
            val flag = StorageManager::class.java
                .getField("FLAG_ALLOCATE_AGGRESSIVE")
                .getInt(null)
            StorageManager::class.java
                .getMethod(
                    "allocateBytes",
                    java.util.UUID::class.java,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .invoke(storage, uuid, bytes, flag)
        }.onFailure { Log.status(TAG, "could not reserve $bytes bytes; continuing anyway", it) }
    }
}
