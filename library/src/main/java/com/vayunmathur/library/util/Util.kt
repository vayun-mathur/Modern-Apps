package com.vayunmathur.library.util

import okio.Source
import okio.buffer
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

fun Source.readLines(): List<String> {
    val lines = mutableListOf<String>()
    val bufferedSource = buffer()

    // Read until there is no more data
    while (!bufferedSource.exhausted()) {
        val line = bufferedSource.readUtf8Line()
        if (line != null) {
            lines.add(line)
        }
    }
    return lines
}