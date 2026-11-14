package com.vayunmathur.library.util

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.round
import kotlin.time.Clock

fun <T> tryOrDefault(default: T, block: () -> T): T = runCatching(block).getOrDefault(default)

fun Double.round(decimals: Int): Double {
    return round(this * 10.0.pow(decimals)) / (10.0.pow(decimals))
}

fun java.io.InputStream.readLines(): List<String> =
    bufferedReader().readLines()

inline fun <reified T : ComponentActivity> Context.findActivity(): T? {
    var context = this
    while (context is ContextWrapper) {
        if (context is T) return context
        context = context.baseContext
    }
    return null
}

typealias Tuple3<A, B, C> = Triple<A, B, C>
/** A four-element tuple; Kotlin's standard library stops at Triple. */
data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun nowState() = produceState(Clock.System.now()) {
    while (true) {
        value = Clock.System.now()
        delay(NOW_TICK_MS)
    }
}

fun String.firstLetterUppercase(): String {
    return replaceFirstChar { it.uppercase() }
}

private const val NOW_TICK_MS = 100L
