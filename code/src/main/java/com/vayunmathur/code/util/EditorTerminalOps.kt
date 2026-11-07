package com.vayunmathur.code.util

internal const val TERMINAL_SCROLLBACK = 2000

/** Starts a shell in the open project directory if one isn't already running. */
fun EditorViewModel.startTerminal() {
    if (terminal != null) return
    val dir = rootDir ?: return
    // Clear the session reference when its shell exits, otherwise startTerminal keeps
    // early-returning on the dead session and every later command is silently swallowed
    // by a process that no longer exists. The identity check keeps a stale exit from a
    // killed session (see terminalInterrupt) from clearing its replacement.
    var session: TerminalSession? = null
    session = TerminalSession(
        dir = dir,
        onLine = { line -> appendTerminalLine(line) },
        onExit = {
            if (terminal === session) {
                terminal = null
                terminalRunning = false
            }
        },
    )
    terminal = session
    terminalRunning = true
}

fun EditorViewModel.terminalSend(command: String) {
    if (terminal == null) startTerminal()
    appendTerminalLine("$ $command")
    terminal?.send(command)
}

/** Line-based shells can't deliver a real SIGINT, so "stop" kills and restarts the shell. */
fun EditorViewModel.terminalInterrupt() {
    terminal?.close()
    terminal = null
    terminalRunning = false
    appendTerminalLine("^C")
    startTerminal()
}

fun EditorViewModel.clearTerminal() {
    terminalLines.clear()
}

internal fun EditorViewModel.appendTerminalLine(line: String) {
    terminalLines.add(line)
    while (terminalLines.size > TERMINAL_SCROLLBACK) terminalLines.removeAt(0)
}
