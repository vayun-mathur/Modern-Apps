package com.vayunmathur.logviewer.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import com.vayunmathur.library.log.Log
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.domain.LogDocument
import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.domain.LogLevel
import java.io.IOException

/**
 * Runs `logcat` and turns its output into a [LogDocument].
 *
 * The log is read by executing the binary rather than through an API because there is no public one
 * that returns the same thing: `--format=descriptive` decoding of event-log tags, the `--dividers`
 * markers and per-buffer selection all live in the tool. READ_LOGS is what makes the exec return
 * anything but this process's own lines.
 *
 * `-d` means dump and exit, so this is a snapshot, not a stream - the same as the app it replaces.
 * Re-reading for new filters is another call to this, in the same window.
 */
internal object LogcatReader {

    private const val TAG = "LogcatReader"
    private const val INITIAL_LINE_CAPACITY = 16_384

    val DEFAULT_BUFFERS: List<String> = listOf("main", "system", "crash", "events", "kernel")

    /** Everything selectable in the buffers dialog: the defaults plus radio. */
    val ALL_BUFFERS: List<String> = DEFAULT_BUFFERS + "radio"

    data class Request(
        val targetPackage: String?,
        val buffers: List<String>,
        val level: LogLevel,
        val filterRegex: String,
    )

    /**
     * [LogLoadResult.Unavailable] when the log must not or cannot be shown.
     *
     * Only the secondary-user refusal carries a message. The others - an unknown target package, a
     * `logcat` that would not run - are malformed input or a broken device rather than something
     * the user did, and the app being replaced closed its window without comment for both.
     */
    fun read(context: Context, request: Request): LogLoadResult {
        val isSystemUser = context.getSystemService(UserManager::class.java)?.isSystemUser == true
        if (request.targetPackage == null && !isSystemUser) {
            // A secondary user must not see other users' logs, and the whole-device log is exactly
            // that. Their own apps' logs are still fine.
            return LogLoadResult.Unavailable(R.string.error_system_log_secondary_user)
        }

        val buffersArg = request.buffers.joinToString(",")
        val command = mutableListOf("logcat", "--buffer=$buffersArg", "-d", "--dividers")

        val format = mutableListOf("UTC", "printable")
        // The uid column is noise once the output is already filtered to one app.
        if (request.targetPackage == null) format += "uid"
        format += "descriptive"
        command += "--format=" + format.joinToString(",")

        if (request.filterRegex.isNotEmpty()) command += "--regex=" + request.filterRegex
        command += "*:${request.level.letter}"

        var targetAppInfo: ApplicationInfo? = null
        if (request.targetPackage != null) {
            targetAppInfo = try {
                context.packageManager.getApplicationInfo(request.targetPackage, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.dev(TAG, "unknown package ${request.targetPackage}", e)
                return LogLoadResult.Unavailable()
            }
            command += "--uid=${targetAppInfo.uid}"
        }

        Log.debug(TAG, "command: " + command.joinToString(" "))

        // Parsed as it streams in, so the raw dump is never held whole alongside its lines.
        val body = ArrayList<String>(INITIAL_LINE_CAPACITY)
        try {
            val process = ProcessBuilder(command).start()
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) body += stripUtcOffset(line)
            }
            Log.debug(TAG, "logcat return code: " + process.waitFor())
        } catch (e: IOException) {
            Log.error(TAG, "logcat failed", e)
            return LogLoadResult.Unavailable()
        } catch (e: InterruptedException) {
            Log.error(TAG, "logcat interrupted", e)
            return LogLoadResult.Unavailable()
        }
        while (body.isNotEmpty() && body[body.lastIndex].isEmpty()) body.removeAt(body.lastIndex)
        body.trimToSize()

        val header = mutableListOf("type: logcat", "osVersion: ${Build.FINGERPRINT}")
        ReportHeaders.addDeviceLines(context, header)
        if (targetAppInfo != null) ReportHeaders.addPackageLines(context, targetAppInfo, header)
        header += "buffers: $buffersArg"
        header += "level: ${request.level.headerValue}"
        if (request.filterRegex.isNotEmpty()) header += "filterRegex: ${request.filterRegex}"

        return LogLoadResult.Loaded(
            LogDocument(
                kind = LogKind.Logcat,
                sourcePackage = request.targetPackage,
                title = buildTitle(context, request),
                headerLines = header,
                bodyLines = body,
            )
        )
    }

    /**
     * The title carries the active filters, because the log itself gives no hint that it is
     * filtered and a truncated log that looks complete is worse than no log.
     */
    private fun buildTitle(context: Context, request: Request): String {
        val title = StringBuilder(
            if (request.targetPackage != null) {
                context.getString(
                    R.string.app_log_title,
                    ReportHeaders.loadAppLabel(context, request.targetPackage),
                )
            } else {
                context.getString(R.string.system_log_title)
            }
        )
        if (request.buffers != DEFAULT_BUFFERS) {
            title.append(" | ")
            for (buffer in request.buffers) title.append(buffer[0].uppercaseChar())
        }
        if (request.level != LogLevel.Verbose) {
            title.append(" | ").append(request.level.letter).append('+')
        }
        if (request.filterRegex.isNotEmpty()) {
            title.append(" | ").append(request.filterRegex)
        }
        return title.toString()
    }

    /**
     * `--format=UTC` stamps every single line with `+0000`, which is the same on all of them and
     * costs six columns of a screen that is already too narrow.
     */
    private fun stripUtcOffset(line: String): String = line.replaceFirst(" +0000", "")
}
