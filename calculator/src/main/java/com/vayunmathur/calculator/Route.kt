package com.vayunmathur.calculator

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for the calculator app. */
sealed interface Route : NavKey {
    @Serializable
    /** The main calculator keypad page. */
    data object Calculator : Route

    @Serializable
    /** The function-graphing page. */
    data object Graph : Route

    @Serializable
    /** The unit-converter page. */
    data object Units : Route
}
