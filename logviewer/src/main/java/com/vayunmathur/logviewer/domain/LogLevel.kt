package com.vayunmathur.logviewer.domain

/**
 * A logcat severity, and the two textual forms of it the app emits.
 *
 * [label] and [letter] are **wire format, not UI text**: [label] lowercased is the `level:` header
 * line, and [letter] is both the `*:V` argument passed to `logcat` and the ` | E+` suffix in the
 * title. They are deliberately not localised, so a report shared from a French phone reads the same
 * as one shared from an English one. The dialog shows a translated name alongside.
 */
enum class LogLevel(val priority: Int, val label: String) {
    Verbose(2, "Verbose"),
    Debug(3, "Debug"),
    Info(4, "Info"),
    Warn(5, "Warn"),
    Error(6, "Error"),
    Assert(7, "Assert"),
    ;

    val letter: Char get() = label[0]

    val headerValue: String get() = label.lowercase()

    /** Shared constants and helpers for LogLevel. */
    companion object {
        /** Clamped rather than rejected, matching the behaviour of an out-of-range intent extra. */
        fun ofPriority(priority: Int): LogLevel {
            val clamped = priority.coerceIn(Verbose.priority, Assert.priority)
            return entries.first { it.priority == clamped }
        }
    }
}
