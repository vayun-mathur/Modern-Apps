package com.vayunmathur.library.log

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Crash-capture: a synthetic exception must land in
 * `filesDir/crashes/last_crash.txt` (stack, thread, time, version), rotation
 * must keep at most [CrashCapture.MAX_CRASH_FILES] files, and the previous
 * default handler must always be chained to — even when persistence fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashCaptureTest {

    @BeforeTest
    fun setUp() {
        // MaInitProvider installs the handler too; start each test from a
        // known uninstalled state regardless of test ordering.
        CrashCapture.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        CrashCapture.resetForTest()
    }

    private fun filesDir(): File = RuntimeEnvironment.getApplication().filesDir

    @Test
    fun handlerWritesCrashFileWithStackThreadTimeAndVersion() {
        val chained = RecordingHandler()
        val handler = CrashUncaughtExceptionHandler(filesDir(), "9.9.9 (42)", chained)
        val boom = IllegalStateException("synthetic boom")

        handler.uncaughtException(Thread.currentThread(), boom)

        val crashFile = CrashCapture.lastCrashFile(filesDir())
        assertTrue(crashFile.isFile, "expected crash file at $crashFile")
        val text = crashFile.readText()
        assertTrue(text.contains("appVersion: 9.9.9 (42)"), text)
        assertTrue(text.contains("thread: ${Thread.currentThread().name}"), text)
        assertTrue(text.contains("time: "), text)
        assertTrue(text.contains("exception: java.lang.IllegalStateException: synthetic boom"), text)
        assertTrue(text.contains("synthetic boom"), text)
        assertTrue(text.contains("CrashCaptureTest"), text) // stack frames present
        assertEquals(1, chained.invocations)
        assertTrue(chained.lastThread === Thread.currentThread())
        assertTrue(chained.lastThrowable === boom)
    }

    @Test
    fun writeCrashReturnsNullInsteadOfThrowingWhenStorageIsUnusable() {
        // filesDir as a regular file: the crashes/ dir can never be created.
        val blocker = File(filesDir(), "not-a-dir-${System.nanoTime()}")
        blocker.writeText("occupied")
        try {
            assertNull(
                CrashCapture.writeCrash(blocker, Thread.currentThread(), RuntimeException("x"), null),
            )
        } finally {
            blocker.delete()
        }
    }

    @Test
    fun handlerStillChainsWhenPersistenceFails() {
        val blocker = File(filesDir(), "not-a-dir-chain-${System.nanoTime()}")
        blocker.writeText("occupied")
        val chained = RecordingHandler()
        val handler = CrashUncaughtExceptionHandler(blocker, null, chained)
        try {
            // Must not throw, and must still delegate.
            handler.uncaughtException(Thread.currentThread(), RuntimeException("chain me"))
            assertEquals(1, chained.invocations)
            assertEquals("chain me", chained.lastThrowable?.message)
        } finally {
            blocker.delete()
        }
    }

    @Test
    fun rotationKeepsAtMostMaxCrashFilesWithNewestFirst() {
        val dir = File(filesDir(), "rotation-${System.nanoTime()}").apply { mkdirs() }
        try {
            repeat(CrashCapture.MAX_CRASH_FILES + 2) { index ->
                assertNotNull(
                    CrashCapture.writeCrash(
                        dir,
                        Thread.currentThread(),
                        IllegalStateException("crash-$index"),
                        "1.0 ($index)",
                        nowMillis = 1_700_000_000_000L + index,
                    ),
                )
            }
            val crashesDir = CrashCapture.crashesDir(dir)
            val names = crashesDir.listFiles().orEmpty().map { it.name }.sorted()
            assertEquals(
                listOf("last_crash.1.txt", "last_crash.2.txt", "last_crash.txt"),
                names,
            )
            assertEquals(CrashCapture.MAX_CRASH_FILES, names.size)
            // Newest report is last_crash.txt; the oldest kept is N-1 crashes back.
            assertTrue(CrashCapture.lastCrashFile(dir).readText().contains("crash-4"))
            assertTrue(
                File(crashesDir, CrashCapture.rotatedFileName(2)).readText().contains("crash-2"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun installIsIdempotentAndChainsToOriginalHandlerExactlyOnce() {
        val original = RecordingHandler()
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(original)
        try {
            CrashCapture.install(filesDir(), "1.0 (1)")
            val installedHandler = Thread.getDefaultUncaughtExceptionHandler()
            assertTrue(installedHandler is CrashUncaughtExceptionHandler)

            // Second install must not wrap the handler in itself.
            CrashCapture.install(filesDir(), "1.0 (1)")
            assertTrue(Thread.getDefaultUncaughtExceptionHandler() === installedHandler)

            val boom = IllegalStateException("installed boom")
            installedHandler.uncaughtException(Thread.currentThread(), boom)
            assertEquals(1, original.invocations)
            assertTrue(original.lastThrowable === boom)
            assertTrue(CrashCapture.lastCrashFile(filesDir()).readText().contains("installed boom"))
        } finally {
            CrashCapture.resetForTest()
            Thread.setDefaultUncaughtExceptionHandler(prior)
        }
    }

    @Test
    fun installNeverThrows() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        try {
            // Install only registers the handler (the write happens at crash
            // time); reaching the assert without throwing is the contract.
            CrashCapture.install(filesDir(), null)
            assertTrue(
                Thread.getDefaultUncaughtExceptionHandler() is CrashUncaughtExceptionHandler,
            )
        } finally {
            CrashCapture.resetForTest()
            Thread.setDefaultUncaughtExceptionHandler(prior)
            assertTrue(Thread.getDefaultUncaughtExceptionHandler() === prior)
        }
    }

    private class RecordingHandler : Thread.UncaughtExceptionHandler {
        var invocations = 0
        var lastThread: Thread? = null
        var lastThrowable: Throwable? = null

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            invocations++
            lastThread = thread
            lastThrowable = throwable
        }
    }
}
