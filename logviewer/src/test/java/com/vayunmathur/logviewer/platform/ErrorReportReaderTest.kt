package com.vayunmathur.logviewer.platform

import android.app.ApplicationErrorReport
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.intents.LogViewerIntents
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@RunWith(AndroidJUnit4::class)
class ErrorReportReaderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun loaded(result: LogLoadResult) = when (result) {
        is LogLoadResult.Loaded -> result.document
        is LogLoadResult.Unavailable -> fail("expected Loaded, got Unavailable(${result.messageRes})")
    }

    private fun unavailable(result: LogLoadResult): LogLoadResult.Unavailable = when (result) {
        is LogLoadResult.Unavailable -> result
        is LogLoadResult.Loaded -> fail("expected Unavailable, got Loaded")
    }

    private fun errorReportIntent(message: String?): Intent {
        val intent = Intent(LogViewerIntents.ACTION_ERROR_REPORT)
        if (message != null) {
            intent.putExtra(LogViewerIntents.EXTRA_GZIPPED_MESSAGE, gzip(message))
        }
        return intent
    }

    // ------------------------------------------------------------ dispatch & flags

    @Test
    fun `unknown and null actions are silently unavailable`() {
        assertNull(unavailable(ErrorReportReader.read(context, Intent("com.example.NOPE"))).messageRes)
        assertNull(unavailable(ErrorReportReader.read(context, Intent())).messageRes)
    }

    @Test
    fun `showReportButton mirrors the extra defaulting to false`() {
        assertFalse(ErrorReportReader.showReportButton(Intent()))
        val intent = Intent().putExtra(LogViewerIntents.EXTRA_SHOW_REPORT_BUTTON, true)
        assertTrue(ErrorReportReader.showReportButton(intent))
    }

    // ------------------------------------------------------------ ACTION_ERROR_REPORT

    @Test
    fun `error report without extras is unavailable`() {
        val intent = Intent(LogViewerIntents.ACTION_ERROR_REPORT)
        assertNull(unavailable(ErrorReportReader.read(context, intent)).messageRes)
    }

    @Test
    fun `error report without a message is unavailable`() {
        val intent = Intent(LogViewerIntents.ACTION_ERROR_REPORT)
            .putExtra(LogViewerIntents.EXTRA_ERROR_TYPE, "crash")
        assertNull(unavailable(ErrorReportReader.read(context, intent)).messageRes)
    }

    @Test
    fun `corrupt gzipped message is unavailable`() {
        val intent = Intent(LogViewerIntents.ACTION_ERROR_REPORT)
            .putExtra(LogViewerIntents.EXTRA_GZIPPED_MESSAGE, byteArrayOf(1, 2, 3))
        assertNull(unavailable(ErrorReportReader.read(context, intent)).messageRes)
    }

    @Test
    fun `gzipped crash message becomes a titled error report`() {
        val intent = errorReportIntent("java.lang.RuntimeException: boom\n\tat Foo.bar(Foo.kt:1)")
            .putExtra(Intent.EXTRA_TITLE, "My crash title")
            .putExtra(LogViewerIntents.EXTRA_ERROR_TYPE, "crash")
        val doc = loaded(ErrorReportReader.read(context, intent))
        assertEquals(LogKind.ErrorReport, doc.kind)
        assertEquals("My crash title", doc.title)
        assertNull(doc.sourcePackage)
        // Header is folded into the body for this shape, separated by a blank line.
        assertEquals("type: crash", doc.bodyLines.first())
        assertTrue(doc.bodyLines.contains("osVersion: ${Build.FINGERPRINT}"))
        assertTrue(doc.bodyLines.contains("java.lang.RuntimeException: boom"))
        assertTrue(doc.bodyLines.contains("\tat Foo.bar(Foo.kt:1)"))
        val headerEnd = doc.bodyLines.indexOf("java.lang.RuntimeException: boom")
        assertEquals("", doc.bodyLines[headerEnd - 1])
    }

    @Test
    fun `default error type is crash and title falls back to app label`() {
        val appInfo = ApplicationInfo().apply { packageName = "com.example.crashed" }
        val intent = errorReportIntent("body text")
            .putExtra(LogViewerIntents.EXTRA_SOURCE_APP_INFO, appInfo)
        val doc = loaded(ErrorReportReader.read(context, intent))
        assertEquals("type: crash", doc.bodyLines.first())
        assertEquals("com.example.crashed", doc.sourcePackage)
        assertEquals("Error in com.example.crashed", doc.title)
    }

    @Test
    fun `message already carrying the fingerprint gets no duplicate osVersion`() {
        val message = "osVersion: ${Build.FINGERPRINT}\nthe body"
        val doc = loaded(ErrorReportReader.read(context, errorReportIntent(message)))
        assertEquals(1, doc.bodyLines.count { it.startsWith("osVersion:") })
        // A message opening with osVersion reads as its own separator: no blank line.
        assertEquals("type: crash", doc.bodyLines[0])
        assertEquals("osVersion: ${Build.FINGERPRINT}", doc.bodyLines[1])
    }

    @Test
    fun `indented newline-led and tombstone-led messages get no blank separator`() {
        val indented = loaded(ErrorReportReader.read(context, errorReportIntent("  indented body")))
        assertFalse(indented.bodyLines.contains(""))
        assertTrue(indented.bodyLines.last() == "  indented body")

        val newlineLed = loaded(ErrorReportReader.read(context, errorReportIntent("\nbody after blank")))
        assertTrue(newlineLed.bodyLines.contains("body after blank"))

        val tombstone = "*** *** *** ***\nBuild fingerprint: x"
        val fromTombstone = loaded(ErrorReportReader.read(context, errorReportIntent(tombstone)))
        assertEquals("*** *** *** ***", fromTombstone.bodyLines[fromTombstone.bodyLines.size - 2])
    }

    @Test
    fun `empty gzipped message still loads with only the header`() {
        val doc = loaded(ErrorReportReader.read(context, errorReportIntent("")))
        assertTrue(doc.bodyLines.first() == "type: crash")
        assertTrue(doc.bodyLines.none { it.isNotEmpty() && it != "type: crash" && !it.startsWith("osVersion") })
    }

    // ------------------------------------------------------------ text tombstones via extras

    private fun tombstoneFile(content: String): File {
        val file = Files.createTempFile("tombstone_", "").toFile()
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    private fun tombstoneIntent(file: File, modified: Long = file.lastModified()): Intent =
        Intent(LogViewerIntents.ACTION_ERROR_REPORT)
            .putExtra(LogViewerIntents.EXTRA_PREFER_TEXT_TOMBSTONE, true)
            .putExtra(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_FILE_PATH, file.absolutePath)
            .putExtra(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_LAST_MODIFIED_TIME, modified)

    @Test
    fun `preferring a readable text tombstone loads its contents`() {
        val file = tombstoneFile("*** tombstone ***\nfull native trace")
        val doc = loaded(ErrorReportReader.read(context, tombstoneIntent(file)))
        assertTrue(doc.bodyLines.contains("*** tombstone ***"))
        assertTrue(doc.bodyLines.contains("full native trace"))
    }

    @Test
    fun `preferring a tombstone with a stale timestamp explains the failure`() {
        val file = tombstoneFile("contents")
        val result = unavailable(ErrorReportReader.read(context, tombstoneIntent(file, modified = 1L)))
        assertEquals(R.string.toast_unable_to_show_more_info, result.messageRes)
    }

    @Test
    fun `preferring a tombstone without a timestamp is unavailable with message`() {
        val intent = Intent(LogViewerIntents.ACTION_ERROR_REPORT)
            .putExtra(LogViewerIntents.EXTRA_PREFER_TEXT_TOMBSTONE, true)
            .putExtra(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_FILE_PATH, "/tmp/whatever")
        assertEquals(
            R.string.toast_unable_to_show_more_info,
            unavailable(ErrorReportReader.read(context, intent)).messageRes,
        )
    }

    @Test
    fun `preferring a tombstone without any file is unavailable with message`() {
        val intent = Intent(LogViewerIntents.ACTION_ERROR_REPORT)
            .putExtra(LogViewerIntents.EXTRA_PREFER_TEXT_TOMBSTONE, true)
        assertEquals(
            R.string.toast_unable_to_show_more_info,
            unavailable(ErrorReportReader.read(context, intent)).messageRes,
        )
    }

    @Test
    fun `hasMoreInfo reflects tombstone availability and the prefer flag`() {
        val file = tombstoneFile("contents")
        val plain = Intent(LogViewerIntents.ACTION_ERROR_REPORT)
            .putExtra(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_FILE_PATH, file.absolutePath)
            .putExtra(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_LAST_MODIFIED_TIME, file.lastModified())
        assertTrue(ErrorReportReader.hasMoreInfo(plain))
        assertFalse(ErrorReportReader.hasMoreInfo(tombstoneIntent(file)))
        assertFalse(ErrorReportReader.hasMoreInfo(Intent(LogViewerIntents.ACTION_ERROR_REPORT)))
        val stale = Intent(LogViewerIntents.ACTION_ERROR_REPORT)
            .putExtra(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_FILE_PATH, file.absolutePath)
            .putExtra(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_LAST_MODIFIED_TIME, 1L)
        assertFalse(ErrorReportReader.hasMoreInfo(stale))
    }

    // ------------------------------------------------------------ ACTION_APP_ERROR

    private fun crashReport(stackTrace: String?): ApplicationErrorReport {
        val report = ApplicationErrorReport()
        report.type = ApplicationErrorReport.TYPE_CRASH
        report.packageName = "com.example.crashed"
        report.processName = "com.example.crashed"
        val crashInfo = ApplicationErrorReport.CrashInfo()
        crashInfo.stackTrace = stackTrace
        crashInfo.throwClassName = "java.lang.RuntimeException"
        crashInfo.throwFileName = "Foo.kt"
        crashInfo.throwLineNumber = 7
        crashInfo.throwMethodName = "bar"
        report.crashInfo = crashInfo
        return report
    }

    private fun appErrorIntent(report: ApplicationErrorReport?): Intent {
        val intent = Intent(Intent.ACTION_APP_ERROR)
        if (report != null) intent.putExtra(Intent.EXTRA_BUG_REPORT, report)
        return intent
    }

    @Test
    fun `app error without a report is unavailable`() {
        assertNull(unavailable(ErrorReportReader.read(context, appErrorIntent(null))).messageRes)
    }

    @Test
    fun `app error crash renders header and stack trace`() {
        val trace = "java.lang.RuntimeException: boom\n\tat com.example.Foo.bar(Foo.kt:7)"
        val doc = loaded(ErrorReportReader.read(context, appErrorIntent(crashReport(trace))))
        assertEquals(LogKind.ErrorReport, doc.kind)
        assertEquals("com.example.crashed", doc.sourcePackage)
        assertEquals("Error in com.example.crashed", doc.title)
        assertTrue(doc.headerLines.contains("type: crash"))
        assertTrue(doc.headerLines.contains("process: com.example.crashed"))
        assertTrue(doc.headerLines.any { it.startsWith("osVersion:") })
        assertEquals(listOf("java.lang.RuntimeException: boom", "\tat com.example.Foo.bar(Foo.kt:7)"), doc.bodyLines)
    }

    @Test
    fun `app error crash with extra text appends it to the header`() {
        val intent = appErrorIntent(crashReport("trace line"))
            .putExtra(Intent.EXTRA_TEXT, "a note from the sender")
        val doc = loaded(ErrorReportReader.read(context, intent))
        assertTrue(doc.headerLines.contains("a note from the sender"))
    }

    @Test
    fun `native crash body keeps signal abort and backtrace but drops register dumps`() {
        val trace = buildString {
            append("*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n")
            append("Build fingerprint: 'x'\n")
            append("Timestamp: 2026-01-01 00:00:00+0000\n")
            append("Process uptime: 3s\n")
            append("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0\n")
            append("Abort message: 'check failed'\n")
            append("    x0 0000000000000000 x1 1111111111111111\n")
            append("memory near x0:\n")
            append("backtrace:\n")
            append("      #00 pc 00001234  /lib/libc.so (strlen+52)\n")
            append("      #01 pc 00005678  /app/libfoo.so\n")
        }
        val doc = loaded(ErrorReportReader.read(context, appErrorIntent(crashReport(trace))))
        val body = doc.bodyLines.joinToString("\n")
        assertTrue(body.contains("signal 11 (SIGSEGV)"))
        assertTrue(body.contains("Abort message: 'check failed'"))
        assertTrue(body.contains("backtrace:"))
        assertTrue(body.contains("#00 pc 00001234"))
        assertFalse(body.contains("memory near x0"))
        assertFalse(body.contains("Build fingerprint"))
    }

    @Test
    fun `app error crash without stack trace is unavailable`() {
        assertNull(unavailable(ErrorReportReader.read(context, appErrorIntent(crashReport(null)))).messageRes)
        val noInfo = ApplicationErrorReport().apply {
            type = ApplicationErrorReport.TYPE_CRASH
            packageName = "com.example.crashed"
            processName = "proc"
        }
        assertNull(unavailable(ErrorReportReader.read(context, appErrorIntent(noInfo))).messageRes)
    }

    @Test
    fun `app error with missing sub-info or unknown type is unavailable`() {
        val anr = ApplicationErrorReport().apply {
            type = ApplicationErrorReport.TYPE_ANR
            packageName = "com.example.crashed"
            processName = "proc"
        }
        assertNull(unavailable(ErrorReportReader.read(context, appErrorIntent(anr))).messageRes)

        val battery = ApplicationErrorReport().apply {
            type = ApplicationErrorReport.TYPE_BATTERY
            packageName = "com.example.crashed"
            processName = "proc"
        }
        assertNull(unavailable(ErrorReportReader.read(context, appErrorIntent(battery))).messageRes)

        val service = ApplicationErrorReport().apply {
            type = ApplicationErrorReport.TYPE_RUNNING_SERVICE
            packageName = "com.example.crashed"
            processName = "proc"
        }
        assertNull(unavailable(ErrorReportReader.read(context, appErrorIntent(service))).messageRes)

        val unknown = ApplicationErrorReport().apply {
            type = 999
            packageName = "com.example.crashed"
            processName = "proc"
        }
        assertNull(unavailable(ErrorReportReader.read(context, appErrorIntent(unknown))).messageRes)
    }

    @Test
    fun `app error battery and running service dumps render`() {
        val battery = ApplicationErrorReport().apply {
            type = ApplicationErrorReport.TYPE_BATTERY
            packageName = "com.example.crashed"
            processName = "proc"
            batteryInfo = ApplicationErrorReport.BatteryInfo()
        }
        val batteryDoc = loaded(ErrorReportReader.read(context, appErrorIntent(battery)))
        assertTrue(batteryDoc.headerLines.contains("type: battery"))

        val service = ApplicationErrorReport().apply {
            type = ApplicationErrorReport.TYPE_RUNNING_SERVICE
            packageName = "com.example.crashed"
            processName = "proc"
            runningServiceInfo = ApplicationErrorReport.RunningServiceInfo().apply {
                durationMillis = 1234L
                serviceDetails = "details here"
            }
        }
        val serviceDoc = loaded(ErrorReportReader.read(context, appErrorIntent(service)))
        assertTrue(serviceDoc.headerLines.contains("type: running_service"))
        assertTrue(serviceDoc.bodyLines.joinToString("\n").contains("details here"))
    }

    @Test
    fun `app error anr dumps its info`() {
        val anr = ApplicationErrorReport().apply {
            type = ApplicationErrorReport.TYPE_ANR
            packageName = "com.example.crashed"
            processName = "proc"
            anrInfo = ApplicationErrorReport.AnrInfo().apply {
                activity = "com.example.MainActivity"
                cause = "input dispatch timed out"
                info = "extra anr info"
            }
        }
        val doc = loaded(ErrorReportReader.read(context, appErrorIntent(anr)))
        assertTrue(doc.headerLines.contains("type: ANR"))
        val body = doc.bodyLines.joinToString("\n")
        assertTrue(body.contains("AnrInfo dump:"))
        assertTrue(body.contains("com.example.MainActivity"))
    }

    @Test
    fun `app error preferring a readable tombstone swaps the body and drops osVersion`() {
        val file = tombstoneFile("full tombstone from disk\nsecond line")
        val intent = appErrorIntent(crashReport("short trace"))
            .putExtra(LogViewerIntents.EXTRA_PREFER_TEXT_TOMBSTONE, true)
            .putExtra(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_FILE_PATH, file.absolutePath)
            .putExtra(LogViewerIntents.EXTRA_TEXT_TOMBSTONE_LAST_MODIFIED_TIME, file.lastModified())
        val doc = loaded(ErrorReportReader.read(context, intent))
        assertEquals(listOf("full tombstone from disk", "second line"), doc.bodyLines)
        assertTrue(doc.headerLines.none { it.startsWith("osVersion:") })
        assertTrue(doc.headerLines.contains("type: crash"))
    }

    @Test
    fun `app error preferring a missing tombstone explains the failure`() {
        val intent = appErrorIntent(crashReport("short trace"))
            .putExtra(LogViewerIntents.EXTRA_PREFER_TEXT_TOMBSTONE, true)
        assertEquals(
            R.string.toast_unable_to_show_more_info,
            unavailable(ErrorReportReader.read(context, intent)).messageRes,
        )
    }

    @Test
    fun `tombstone-led crash has no more-info when the tombstone file cannot be found`() {
        val trace = "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n" +
            "Timestamp: 2026-01-01 00:00:00+0000\nrest"
        val intent = appErrorIntent(crashReport(trace))
        // /data/tombstones does not exist under Robolectric, so no fuller file can be offered.
        assertFalse(ErrorReportReader.hasMoreInfo(intent))
    }

    @Test
    fun `crash for an installed package falls back to its installed info`() {
        val report = crashReport("trace")
        report.packageName = context.packageName
        report.processName = context.packageName
        val doc = loaded(ErrorReportReader.read(context, appErrorIntent(report)))
        assertTrue(
            doc.headerLines.any { it.startsWith("package: ${context.packageName}:") },
            "header: ${doc.headerLines}",
        )
        assertEquals(context.packageName, doc.sourcePackage)
    }

    @Test
    fun `crash with no package has an empty title`() {
        val report = ApplicationErrorReport().apply {
            type = ApplicationErrorReport.TYPE_CRASH
            processName = "proc"
            crashInfo = ApplicationErrorReport.CrashInfo().apply { stackTrace = "trace" }
        }
        val doc = loaded(ErrorReportReader.read(context, appErrorIntent(report)))
        assertEquals("", doc.title)
        assertNull(doc.sourcePackage)
    }
}
