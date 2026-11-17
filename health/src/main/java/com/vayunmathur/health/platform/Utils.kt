package com.vayunmathur.health.platform
import com.vayunmathur.library.ui.DateString
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.minutes

fun LocalDate.displayString() = DateString.monthDayYear(this)

fun formatDuration(minutes: Long): String =
    minutes.minutes.toComponents { h, m, _, _ ->
        if (h > 0) "${h}h ${m}m" else "${m}m"
    }
