package com.vayunmathur.library.util

import kotlin.math.pow
import kotlin.math.round

fun <T> tryOrDefault(default: T, block: () -> T): T {
    return try {
        block()
    } catch (_: Exception) {
        default
    }
}

fun Double.round(decimals: Int): Double {
    return round(this * 10.0.pow(decimals)) / (10.0.pow(decimals))
}