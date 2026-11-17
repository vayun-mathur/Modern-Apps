package com.vayunmathur.code.platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A line-based shell scoped to a project directory.
 *
 * Runs `/system/bin/sh -l` with its working directory set to the opened project and stderr folded
 * into stdout, streaming output back a line at a time via [onLine] (delivered on the main thread).
 * This is intentionally **not** a full PTY: ANSI colours and full-screen TUIs (vi/less) are out of
 * scope, and on modern Android only the system toybox/`sh` is reachable — non-system binaries are
 * blocked by W^X, and only the granted external-storage tree is accessible.
 */
class TerminalSession(
    dir: File,
    private val onLine: (String) -> Unit,
    private val onExit: () -> Unit,
) {
    private val process: Process = ProcessBuilder(listOf("/system/bin/sh", "-l"))
        .directory(dir)
        .redirectErrorStream(true)
        .start()

    private val writer = process.outputStream.bufferedWriter()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        withContext(Dispatchers.Main) { onLine(line) }
                    }
                }
            }
            withContext(Dispatchers.Main) { onExit() }
        }
    }

    /** Writes [command] followed by a newline to the shell's stdin. */
    fun send(command: String) {
        scope.launch {
            runCatching {
                writer.write(command)
                writer.newLine()
                writer.flush()
            }
        }
    }

    /** Kills the shell and stops the reader. */
    fun close() {
        runCatching { writer.close() }
        runCatching { process.destroy() }
        scope.cancel()
    }
}
