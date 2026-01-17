package com.vayunmathur.library.util

fun <T> tryOrDefault(default: T, block: () -> T): T {
    return try {
        block()
    } catch (_: Exception) {
        default
    }
}