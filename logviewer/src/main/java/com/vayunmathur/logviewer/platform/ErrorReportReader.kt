package com.vayunmathur.logviewer.platform

import android.app.ApplicationErrorReport
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.vayunmathur.library.log.Log
import android.util.StringBuilderPrinter
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.data.TimestampedFile
import com.vayunmathur.logviewer.data.TombstoneFiles
import com.vayunmathur.logviewer.domain.LogDocument
import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.intents.LogViewerIntents
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Turns an error-report intent into a [LogDocument].
 *
 * Two shapes arrive here. `ACTION_ERROR_REPORT` is the platform's own, built by
 * `LogViewerApp.createBaseErrorReportIntent()` and carrying an already-rendered message, gzipped
 * because an uncompressed one would not survive the binder transaction. `ACTION_APP_ERROR` is the
 * standard AOSP report object, which has to be rendered here.
 */
internal object ErrorReportReader {

    private const val TAG = "ErrorReportReader"

    /** The first line every text tombstone starts with. Used to recognise one, not to parse it. */
    private const val TOMBSTONE_MARKER =
        "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***"

    private const val OS_VERSION_PREFIX = "osVersion: "

    /**
     * Largest report body accepted from an intent extra or a tombstone file. Real reports
     * are stack traces (kilobytes to a few megabytes with memory maps); the cap exists so a
     * crafted extra - a binder-sized gzip expands ~1000x - cannot OOM this process while
     * decompressing. Oversized input degrades to Unavailable like corrupt input does.
     */
    private const val MAX_REPORT_BYTES = 16 shl 20

    fun read(context: Context, intent: Intent): LogLoadResult = when (intent.action) {
        LogViewerIntents.ACTION_ERROR_REPORT -> readErrorReport(context, intent)
        Intent.ACTION_APP_ERROR -> readAppError(context, intent)
        else -> LogLoadResult.Unavailable()
    }

    fun showReportButton(intent: Intent): Boolean =
        intent.getBooleanExtra(LogViewerIntents.EXTRA_SHOW_REPORT_BUTTON, false)

    /**
     * Whether there is a fuller text tombstone to offer.
     *
     * Only when the report on screen is not already that tombstone, or "More info" would reload
     * what is already there.
     */
    fun hasMoreInfo(intent: Intent): Boolean =
        !intent.getBooleanExtra(LogViewerIntents.EXTRA_PREFER_TEXT_TOMBSTONE, false) &&
            textTombstoneFile(intent) != null

    // ----------------------------------------------------------------- ACTION_ERROR_REPORT

    private fun readErrorReport(context: Context, intent: Intent): LogLoadResult {
        val extras = intent.extras ?: return LogLoadResult.Unavailable()

        val messageBytes = readReportMessageBytes(intent, extras)
            ?: return LogLoadResult.Unavailable(reportMessageUnavailableRes(extras))
        val sourceAppInfo = BundleCompat.getParcelable(
            extras,
            LogViewerIntents.EXTRA_SOURCE_APP_INFO,
            ApplicationInfo::class.java,
        )
        val message = String(messageBytes, Charsets.UTF_8)

        val header = buildReportHeader(context, extras, message, sourceAppInfo)
        val body = buildReportBody(header, message)

        val sourcePackage = sourceAppInfo?.packageName
        val title = extras.getString(Intent.EXTRA_TITLE) ?: errorTitle(context, sourcePackage)

        return LogLoadResult.Loaded(
            LogDocument(
                kind = LogKind.ErrorReport,
                sourcePackage = sourcePackage,
                title = title,
                // Everything is in the body: the header the platform sent is part of the message
                // it rendered, and splitting it back out would be guesswork.
                header = "",
                body = body,
            )
        )
    }

    private fun reportMessageUnavailableRes(extras: Bundle): Int? {
        if (extras.getBoolean(LogViewerIntents.EXTRA_PREFER_TEXT_TOMBSTONE)) {
            return R.string.toast_unable_to_show_more_info
        }
        return null
    }

    private fun readReportMessageBytes(intent: Intent, extras: Bundle): ByteArray? {
        if (extras.getBoolean(LogViewerIntents.EXTRA_PREFER_TEXT_TOMBSTONE)) {
            return textTombstoneBytes(intent)
        }
        val gzipped = extras.getByteArray(LogViewerIntents.EXTRA_GZIPPED_MESSAGE)
            ?: return null
        return try {
            GZIPInputStream(ByteArrayInputStream(gzipped)).use { readBounded(it) }
        } catch (e: IOException) {
            Log.dev(TAG, "corrupt gzipped message", e)
            null
        }
    }

    /** Reads [input] fully, or returns null once it passes [MAX_REPORT_BYTES]. */
    private fun readBounded(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_REPORT_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun buildReportHeader(
        context: Context,
        extras: Bundle,
        message: String,
        sourceAppInfo: ApplicationInfo?,
    ): List<String> {
        val header = mutableListOf(
            "type: " + extras.getString(LogViewerIntents.EXTRA_ERROR_TYPE, "crash"),
        )
        // A text tombstone already names the build; a second copy of it would just be noise.
        if (!message.contains(Build.FINGERPRINT)) header += "osVersion: ${Build.FINGERPRINT}"
        ReportHeaders.addDeviceLines(context, header)
        if (sourceAppInfo != null) ReportHeaders.addPackageLines(context, sourceAppInfo, header)
        return header
    }

    private fun buildReportBody(header: List<String>, message: String): String {
        val body = StringBuilder(message.length + 1000)
        body.append(header.joinToString("\n"))
        body.append('\n')
        // A blank line, unless the message already opens with something that reads as its own
        // separator - indented text, an empty line, or a tombstone's asterisk banner.
        if (needsBlankSeparator(message)) {
            body.append('\n')
        }
        body.append(message)
        return body.toString()
    }

    private fun needsBlankSeparator(message: String): Boolean {
        val first = message.firstOrNull() ?: return false
        if (first == ' ' || first == '\n' || first == '*') return false
        return !message.startsWith(OS_VERSION_PREFIX)
    }

    // -------------------------------------------------------------------- ACTION_APP_ERROR

    private fun readAppError(context: Context, intent: Intent): LogLoadResult {
        val report = IntentCompat.getParcelableExtra(
            intent,
            Intent.EXTRA_BUG_REPORT,
            ApplicationErrorReport::class.java,
        ) ?: return LogLoadResult.Unavailable()

        val useTextTombstone =
            intent.getBooleanExtra(LogViewerIntents.EXTRA_PREFER_TEXT_TOMBSTONE, false)

        val body = if (useTextTombstone) {
            val bytes = textTombstoneBytes(intent)
                ?: return LogLoadResult.Unavailable(R.string.toast_unable_to_show_more_info)
            String(bytes, Charsets.UTF_8)
        } else {
            createBody(report) ?: run {
                Log.error(TAG, "invalid ApplicationErrorReport")
                return LogLoadResult.Unavailable()
            }
        }

        var header = createHeader(
            context,
            report,
            // The text tombstone includes the OS version string already.
            includeOsVersion = !useTextTombstone,
        )
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { header += "\n$it" }

        return LogLoadResult.Loaded(
            LogDocument(
                kind = LogKind.ErrorReport,
                sourcePackage = report.packageName,
                title = errorTitle(context, report.packageName),
                header = header,
                body = body,
            )
        )
    }

    private fun createHeader(
        context: Context,
        report: ApplicationErrorReport,
        includeOsVersion: Boolean,
    ): String {
        val lines = mutableListOf("type: " + typeName(report.type))
        if (includeOsVersion) lines += "osVersion: ${Build.FINGERPRINT}"
        ReportHeaders.addDeviceLines(context, lines)

        applicationInfo(context, report)?.let { ReportHeaders.addPackageLines(context, it, lines) }

        lines += "process: ${report.processName}"

        if (report.type == ApplicationErrorReport.TYPE_CRASH) {
            val crashInfo = report.crashInfo
            val uptime = crashInfo?.let { HiddenFrameworkApi.longField(it, "processUptimeMs") }
            if (uptime != null && uptime > 0) {
                val latency = HiddenFrameworkApi.longField(crashInfo, "processStartupLatencyMs") ?: 0L
                lines += "processUptime: $uptime + $latency ms"
            }
        }

        report.packageName?.let { packageName ->
            ReportHeaders.installingPackage(context, packageName)?.let {
                lines += "installer: $it"
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * `ApplicationErrorReport.applicationInfo` is not public API and is not present on every build,
     * so the installed package is the fallback. It describes the app as it is now rather than as it
     * was when it crashed, which is worth a slightly stale version number over no `package:` line.
     */
    private fun applicationInfo(
        context: Context,
        report: ApplicationErrorReport,
    ): ApplicationInfo? {
        (HiddenFrameworkApi.objectField(report, "applicationInfo") as? ApplicationInfo)?.let {
            return it
        }
        val packageName = report.packageName ?: return null
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun createBody(report: ApplicationErrorReport): String? {
        if (report.type == ApplicationErrorReport.TYPE_CRASH) {
            val stackTrace = report.crashInfo?.stackTrace ?: return null
            return crashBody(stackTrace)
        }
        return nonCrashBody(report)
    }

    private fun nonCrashBody(report: ApplicationErrorReport): String? {
        val sb = StringBuilder()
        val printer = StringBuilderPrinter(sb)

        when (report.type) {
            ApplicationErrorReport.TYPE_ANR -> {
                val info = report.anrInfo ?: return null
                // tracesFilePath is @hide; without it the AnrInfo dump is all there is.
                HiddenFrameworkApi.stringField(info, "tracesFilePath")?.let { path ->
                    ReportHeaders.readFileAsString(path)?.let { printer.println(it) }
                }
                printer.println("\nAnrInfo dump:")
                info.dump(printer, "")
            }

            ApplicationErrorReport.TYPE_BATTERY -> {
                val info = report.batteryInfo ?: return null
                info.dump(printer, "")
            }

            ApplicationErrorReport.TYPE_RUNNING_SERVICE -> {
                val info = report.runningServiceInfo ?: return null
                info.dump(printer, "")
            }

            else -> return null
        }
        return sb.toString()
    }

    /**
     * A native crash arrives as a whole tombstone in `stackTrace`, most of which is register dumps
     * and memory maps. Everything before the backtrace is dropped except the two lines that
     * actually say what happened, because a report nobody scrolls to the bottom of is not read.
     */
    private fun crashBody(stackTrace: String): String {
        val nativeCrashMarker = stackTrace.indexOf("\nProcess uptime: ")
        if (nativeCrashMarker <= 0) return stackTrace

        val sb = StringBuilder()
        val prefixes = listOf("signal ", "Abort message: ")
        var backtraceStarted = false
        for (line in stackTrace.substring(nativeCrashMarker).split("\n")) {
            if (backtraceStarted) {
                sb.append(line)
                sb.append('\n')
            }
            for (prefix in prefixes) {
                if (line.startsWith(prefix)) {
                    sb.append(line)
                    sb.append('\n')
                }
            }
            if (line.startsWith("backtrace:")) {
                sb.append('\n')
                sb.append(line)
                sb.append('\n')
                backtraceStarted = true
            }
        }
        return sb.toString()
    }

    private fun typeName(type: Int): String = when (type) {
        ApplicationErrorReport.TYPE_CRASH -> "crash"
        ApplicationErrorReport.TYPE_ANR -> "ANR"
        ApplicationErrorReport.TYPE_BATTERY -> "battery"
        ApplicationErrorReport.TYPE_RUNNING_SERVICE -> "running_service"
        else -> "unknown ($type)"
    }

    private fun errorTitle(context: Context, sourcePackage: String?): String =
        if (sourcePackage != null) {
            context.getString(
                R.string.error_report_title,
                ReportHeaders.loadAppLabel(context, sourcePackage),
            )
        } else {
            ""
        }

    // ------------------------------------------------------------------- text tombstones

    /**
     * The tombstone this report refers to, either matched from the crash header or named outright
     * by the sender.
     *
     * The sender's path is only trusted together with the modification time it saw: the path alone
     * would let a caller point this at whatever is at that path now.
     */
    private fun textTombstoneFile(intent: Intent): TimestampedFile? {
        val extras: Bundle = intent.extras ?: return null

        BundleCompat.getParcelable(
            extras,
            Intent.EXTRA_BUG_REPORT,
            ApplicationErrorReport::class.java,
        )?.let { report ->
            tombstoneFromReport(report)?.let { return it }
        }

        val path = extras.getString(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_FILE_PATH) ?: return null
        if (!extras.containsKey(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_LAST_MODIFIED_TIME)) {
            return null
        }
        val expected = extras.getLong(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_LAST_MODIFIED_TIME)

        val file = File(path)
        val lastModified = file.lastModified()
        if (expected != lastModified) {
            Log.error(TAG, "lastModified mismatch: expected $expected, got $lastModified")
            return null
        }
        return TimestampedFile(file, lastModified)
    }

    private fun tombstoneFromReport(report: ApplicationErrorReport): TimestampedFile? {
        if (report.type != ApplicationErrorReport.TYPE_CRASH) return null
        val stackTrace = report.crashInfo?.stackTrace ?: return null
        if (!stackTrace.startsWith(TOMBSTONE_MARKER)) return null

        val timestampPrefix = "\nTimestamp: "
        val timestampIndex = stackTrace.indexOf(timestampPrefix)
        if (timestampIndex < 0) return null
        val timestampEnd = stackTrace.indexOf('\n', timestampIndex + timestampPrefix.length)
        if (timestampEnd < 0) return null

        return TombstoneFiles.findByHeader(
            stackTrace.substring(0, timestampEnd).toByteArray(Charsets.UTF_8)
        )
    }

    private fun textTombstoneBytes(intent: Intent): ByteArray? {
        val timestamped = textTombstoneFile(intent) ?: return null
        val file = timestamped.file
        val bytes = try {
            FileInputStream(file).use { readBounded(it) }
        } catch (e: IOException) {
            Log.error(TAG, "unable to read tombstone $file", e)
            return null
        } ?: return null
        // Rechecked after the read: the ring may have recycled the file mid-read, and half of one
        // crash followed by half of another is worse than nothing.
        if (file.lastModified() != timestamped.lastModified) return null
        return bytes
    }
}
