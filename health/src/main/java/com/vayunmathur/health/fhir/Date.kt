package com.vayunmathur.health.fhir

import kotlinx.datetime.LocalDate
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding

typealias Date = LocalDate

fun Date.displayString() = this.format(LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        chars(" ")
        day(Padding.NONE)
        chars(", ")
        year()
    })