package com.vayunmathur.library.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash-capture foundation: persists the last few uncaught exceptions to disk
 * so the next launch (or the user, via a future viewer) can see *why* the app
 * died, even with no network and no crash-reporting backend.
 *
 * [install] — called once from [MaInitProvider] for every app — wraps the
 * current default [Thread.UncaughtExceptionHandler]. On a crash the handler:
 *  1. writes the crash (time, thread, app version when known, stack trace) to
 *     `filesDir/crashes/last_crash.txt`, rotating the previous reports so at
 *     most [MAX_CRASH_FILES] files are kept, then
 *  2. chains to the previously installed handler — the crash is never
 *     swallowed; the process still dies the way it would have without us.
 *
 * Persistence must never throw (the [NativeLoader] lesson: a guard that throws
 * becomes the failure it was meant to contain). Every disk/version/logging call
 * on the crash path is wrapped; if writing fails the crash simply chains on.
 */
object CrashCapture {

    /** Directory under `filesDir` that holds crash reports. */
    const val CRASH_DIR_NAME = "crashes"

    /** Newest crash report file inside [CRASH_DIR_NAME]. */
    const val LAST_CRASH_FILE_NAME = "last_crash.txt"

    /** Total crash files kept: newest + this many - 1 rotated generations. */
    const val MAX_CRASH_FILES = 3

    private val installed = AtomicBoolean(false)

    /**
     * Install the crash handler for [context]'s app, resolving the app version
     * best-effort. Idempotent: a second call is a no-op, so the handler never
     * chains to itself. Never throws.
     */
    fun install(context: Context) {
        runCatching {
            val filesDir = context.filesDir ?: return
            install(filesDir, appVersion(context))
        }
    }

    /**
     * Install with an explicit storage root + version label (the [install]
     * seam, also what tests drive). Idempotent and never throws: any failure
     * leaves the previous default handler untouched.
     */
    internal fun install(filesDir: File, appVersion: String?) {
        runCatching {
            if (!installed.compareAndSet(false, true)) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(
                CrashUncaughtExceptionHandler(filesDir, appVersion, previous),
            )
        }.onFailure {
            // Roll back the claim so a later retry can still install.
            installed.set(false)
        }
    }

    /** Location of the crash-reports directory under an app's [filesDir]. */
    fun crashesDir(filesDir: File): File = File(filesDir, CRASH_DIR_NAME)

    /** Location of the newest crash report under an app's [filesDir]. */
    fun lastCrashFile(filesDir: File): File = File(crashesDir(filesDir), LAST_CRASH_FILE_NAME)

    /**
     * Rotated file name for [generation]: 0 → `last_crash.txt` (newest),
     * 1 → `last_crash.1.txt`, 2 → `last_crash.2.txt` (oldest kept).
     */
    internal fun rotatedFileName(generation: Int): String =
        if (generation == 0) LAST_CRASH_FILE_NAME else "last_crash.$generation.txt"

    /**
     * Render + persist one crash under [filesDir], rotating older reports.
     * Returns the written file, or null when persistence failed. Never throws.
     */
    internal fun writeCrash(
        filesDir: File,
        thread: Thread,
        throwable: Throwable,
        appVersion: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): File? = runCatching {
        val dir = crashesDir(filesDir)
        if (!dir.exists()) dir.mkdirs()
        rotate(dir)
        val target = File(dir, LAST_CRASH_FILE_NAME)
        target.writeText(renderCrash(thread, throwable, appVersion, nowMillis))
        prune(dir)
        target
    }.getOrNull()

    @Suppress("DEPRECATION") // Thread.id: threadId() is not available on all API levels
    internal fun renderCrash(
        thread: Thread,
        throwable: Throwable,
        appVersion: String?,
        nowMillis: Long,
    ): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
            .format(Date(nowMillis))
        return buildString {
            append("time: ").append(timestamp)
                .append(" (epochMillis=").append(nowMillis).append(")\n")
            append("thread: ").append(thread.name)
                .append(" (id=").append(thread.id).append(")\n")
            append("appVersion: ").append(appVersion ?: "unknown").append('\n')
            append("exception: ").append(throwable.javaClass.name)
            throwable.message?.let { append(": ").append(it) }
            append('\n')
            append("stack:\n")
            append(throwable.stackTraceToString())
        }
    }

    // Shift generations up by one (oldest falls off the end), freeing
    // last_crash.txt for the new report. Bounded at MAX_CRASH_FILES.
    private fun rotate(dir: File) {
        for (generation in MAX_CRASH_FILES - 1 downTo 0) {
            val source = File(dir, rotatedFileName(generation))
            if (!source.exists()) continue
            if (generation == MAX_CRASH_FILES - 1) {
                runCatching { source.delete() }
            } else {
                runCatching { source.renameTo(File(dir, rotatedFileName(generation + 1))) }
            }
        }
    }

    // Defensive bound: drop anything beyond the kept generations (e.g. files
    // left by an older scheme) so the directory can never grow unbounded.
    private fun prune(dir: File) {
        runCatching {
            val kept = (0 until MAX_CRASH_FILES).map { rotatedFileName(it) }.toSet()
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name !in kept) runCatching { file.delete() }
            }
        }
    }

    // "versionName (versionCode)" when the package manager can tell us, else
    // null (rendered as "unknown"). Never throws.
    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): String? = runCatching {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        "${packageInfo.versionName ?: "unknown"} ($code)"
    }.getOrNull()

    /** Test seam: uninstall the handler installed by [install], if any. */
    internal fun resetForTest() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is CrashUncaughtExceptionHandler) {
            Thread.setDefaultUncaughtExceptionHandler(current.previous)
        }
        installed.set(false)
    }
}

/**
 * The installed handler: persist, then chain. Persistence failure never
 * escapes and never blocks chaining; chaining failure is left alone (there is
 * nothing more useful we could do with it on a crashing thread).
 */
internal class CrashUncaughtExceptionHandler(
    private val filesDir: File,
    private val appVersion: String?,
    internal val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            CrashCapture.writeCrash(filesDir, thread, throwable, appVersion)
        }
        runCatching { Log.error(TAG, "uncaught exception on ${thread.name}", throwable) }

        val chained = previous
        if (chained != null) {
            chained.uncaughtException(thread, throwable)
        } else {
            // No previous handler to kill the process: preserve the default
            // "thread dies with its exception" contract instead of swallowing.
            throw throwable
        }
    }

    private companion object {
        const val TAG = "CrashCapture"
    }
}
