package com.vayunmathur.logviewer.platform

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.logviewer.R
import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.domain.LogLevel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@RunWith(AndroidJUnit4::class)
class LogcatReaderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val originalExecutor = LogcatReader.executeCommand

    @After
    fun restoreExecutor() {
        LogcatReader.executeCommand = originalExecutor
    }

    private fun loaded(result: LogLoadResult) = when (result) {
        is LogLoadResult.Loaded -> result.document
        is LogLoadResult.Unavailable -> fail("expected Loaded, got Unavailable(${result.messageRes})")
    }

    @Test
    fun `buffer constants are defaults plus radio`() {
        assertEquals(listOf("main", "system", "crash", "events", "kernel"), LogcatReader.DEFAULT_BUFFERS)
        assertEquals(LogcatReader.DEFAULT_BUFFERS + "radio", LogcatReader.ALL_BUFFERS)
    }

    @Test
    fun `system log builds command, strips utc offset, drops trailing blanks`() {
        var captured: List<String>? = null
        LogcatReader.executeCommand = { command ->
            captured = command
            listOf("first +0000 line", "second +0000 and +0000 again", "", "").asSequence()
        }
        val doc = loaded(
            LogcatReader.read(
                context,
                LogcatReader.Request(null, listOf("main", "radio"), LogLevel.Error, "Foo.*"),
            )
        )
        assertEquals(
            listOf(
                "logcat", "--buffer=main,radio", "-d", "--dividers",
                "--format=UTC,printable,uid,descriptive", "--regex=Foo.*", "*:E",
            ),
            captured,
        )
        assertEquals(LogKind.Logcat, doc.kind)
        assertNull(doc.sourcePackage)
        assertEquals(listOf("first line", "second and +0000 again"), doc.bodyLines)
        assertEquals("type: logcat", doc.headerLines.first())
        assertTrue(doc.headerLines.contains("osVersion: ${Build.FINGERPRINT}"))
        assertTrue(doc.headerLines.contains("buffers: main,radio"))
        assertTrue(doc.headerLines.contains("level: error"))
        assertTrue(doc.headerLines.contains("filterRegex: Foo.*"))
        assertEquals("System log | MR | E+ | Foo.*", doc.title)
    }

    @Test
    fun `default buffers and verbose level leave the plain system title`() {
        LogcatReader.executeCommand = { emptySequence() }
        val doc = loaded(
            LogcatReader.read(
                context,
                LogcatReader.Request(null, LogcatReader.DEFAULT_BUFFERS, LogLevel.Verbose, ""),
            )
        )
        assertEquals("System log", doc.title)
        assertTrue(doc.bodyLines.isEmpty())
        assertTrue(doc.headerLines.contains("level: verbose"))
        assertTrue(doc.headerLines.none { it.startsWith("filterRegex:") })
    }

    @Test
    fun `app log filters by uid, omits uid column and titles with the app label`() {
        var captured: List<String>? = null
        LogcatReader.executeCommand = { command ->
            captured = command
            listOf("app line").asSequence()
        }
        val pkg = context.packageName
        val doc = loaded(
            LogcatReader.read(
                context,
                LogcatReader.Request(pkg, LogcatReader.DEFAULT_BUFFERS, LogLevel.Verbose, ""),
            )
        )
        assertEquals(pkg, doc.sourcePackage)
        assertTrue(captured!!.contains("--format=UTC,printable,descriptive"))
        assertTrue(captured!!.any { it.startsWith("--uid=") })
        assertEquals("Log Viewer log", doc.title)
        assertTrue(doc.headerLines.any { it.startsWith("package: $pkg:") })
    }

    @Test
    fun `unknown target package is silently unavailable and never execs`() {
        var called = false
        LogcatReader.executeCommand = { called = true; emptySequence() }
        val result = LogcatReader.read(
            context,
            LogcatReader.Request(
                "com.example.does.not.exist",
                LogcatReader.DEFAULT_BUFFERS,
                LogLevel.Verbose,
                "",
            ),
        )
        assertTrue(result is LogLoadResult.Unavailable)
        assertNull((result as LogLoadResult.Unavailable).messageRes)
        assertEquals(false, called)
    }

    @Test
    fun `io failure from logcat is silently unavailable`() {
        LogcatReader.executeCommand = { throw IOException("no logcat") }
        val result = LogcatReader.read(
            context,
            LogcatReader.Request(null, LogcatReader.DEFAULT_BUFFERS, LogLevel.Verbose, ""),
        )
        assertTrue(result is LogLoadResult.Unavailable)
        assertNull((result as LogLoadResult.Unavailable).messageRes)
    }

    @Test
    fun `interrupted logcat is silently unavailable`() {
        LogcatReader.executeCommand = { throw InterruptedException("stop") }
        val result = LogcatReader.read(
            context,
            LogcatReader.Request(null, LogcatReader.DEFAULT_BUFFERS, LogLevel.Debug, ""),
        )
        assertTrue(result is LogLoadResult.Unavailable)
    }

    @Test
    fun `secondary user may not read the whole-device log but may read an app log`() {
        val userManager = context.getSystemService(android.os.UserManager::class.java)
        org.robolectric.Shadows.shadowOf(userManager).setIsSystemUser(false)

        val refused = LogcatReader.read(
            context,
            LogcatReader.Request(null, LogcatReader.DEFAULT_BUFFERS, LogLevel.Verbose, ""),
        )
        assertTrue(refused is LogLoadResult.Unavailable)
        assertEquals(
            R.string.error_system_log_secondary_user,
            (refused as LogLoadResult.Unavailable).messageRes,
        )

        LogcatReader.executeCommand = { listOf("own app line").asSequence() }
        val own = loaded(
            LogcatReader.read(
                context,
                LogcatReader.Request(
                    context.packageName,
                    LogcatReader.DEFAULT_BUFFERS,
                    LogLevel.Verbose,
                    "",
                ),
            )
        )
        assertEquals(listOf("own app line"), own.bodyLines)
    }

    @Test
    fun `the real logcat exec failing is silently unavailable`() {
        // No fake installed: on the unit-test host there is no logcat binary, so the
        // production ProcessBuilder path fails and must degrade the same way.
        LogcatReader.executeCommand = originalExecutor
        val result = LogcatReader.read(
            context,
            LogcatReader.Request(null, LogcatReader.DEFAULT_BUFFERS, LogLevel.Verbose, ""),
        )
        assertTrue(result is LogLoadResult.Unavailable)
        assertNull((result as LogLoadResult.Unavailable).messageRes)
    }

    @Test
    fun `read consumes a lazy one-shot sequence`() {
        // The production executor streams process output; it cannot hand back a
        // re-readable list. read() must pull the sequence exactly once, as it goes.
        var pulled = 0
        LogcatReader.executeCommand = {
            sequence {
                pulled++; yield("streamed +0000 one")
                pulled++; yield("streamed two")
            }.constrainOnce()
        }
        val doc = loaded(
            LogcatReader.read(
                context,
                LogcatReader.Request(null, LogcatReader.DEFAULT_BUFFERS, LogLevel.Verbose, ""),
            )
        )
        assertEquals(listOf("streamed one", "streamed two"), doc.bodyLines)
        assertEquals(2, pulled)
    }

    @Test
    fun `filter regex validation accepts empty and compilable patterns only`() {
        assertTrue(LogcatReader.isValidFilterRegex(""))
        assertTrue(LogcatReader.isValidFilterRegex("Foo.*"))
        assertTrue(LogcatReader.isValidFilterRegex("(?i)error|failure"))
        assertEquals(false, LogcatReader.isValidFilterRegex("("))
        assertEquals(false, LogcatReader.isValidFilterRegex("a{2,1}"))
        assertEquals(false, LogcatReader.isValidFilterRegex("[unclosed"))
    }

    @Test
    fun `request is a data holder`() {
        val request = LogcatReader.Request("pkg", listOf("main"), LogLevel.Info, "x")
        assertEquals("pkg", request.targetPackage)
        assertEquals(listOf("main"), request.buffers)
        assertEquals(LogLevel.Info, request.level)
        assertEquals("x", request.filterRegex)
        assertEquals(request, request.copy())
    }

    @Test
    fun `secondary user message resource is the documented string`() {
        // The branch itself needs a secondary user; pin the resource it reports.
        assertEquals(
            "The device log is only available to the primary user",
            context.getString(R.string.error_system_log_secondary_user),
        )
    }
}
