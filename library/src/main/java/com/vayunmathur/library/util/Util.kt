package com.vayunmathur.library.util

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import okio.Source
import okio.buffer
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Clock

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

inline fun <reified T: ComponentActivity> Context.findActivity(): T? {
    var context = this
    while (context is ContextWrapper) {
        if (context is T) return context
        context = context.baseContext
    }
    return null
}

data class Tuple3<A, B, C>(val first: A, val second: B, val third: C)
data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun nowState() = produceState(Clock.System.now()) {
    while (true) {
        value = Clock.System.now()
        delay(100)
    }
}

fun String.firstLetterUppercase(): String {
    return replaceFirstChar { it.uppercase() }
}