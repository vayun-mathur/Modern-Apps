package com.vayunmathur.testsupport

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows

/**
 * Shared deterministic waits for JVM/Robolectric tests.
 *
 * This is the repo's one shared test-await helper. It lives in
 * `build-logic/shared-test/java`, which both convention plugins
 * (`common-conventions-app` and `common-conventions-library`) add to every
 * module's unit-test source set — the same mechanism that already shares
 * `build-logic/shared-test-resources/robolectric.properties`. There is no
 * shared test-util Gradle module in this repo, and none is invented here.
 *
 * Every wait below is a wait *on a signal*, never on a wall clock:
 * - [awaitValue] suspends until a [Flow] emits a value matching a predicate
 *   (a StateFlow terminal state, a Room/DataStore flow, a completion flow).
 * - [awaitWithMainIdle] pumps Robolectric's paused main looper until a
 *   terminal condition holds. Idling the looper is what lets a coroutine
 *   resumed on Main actually run; sleeping would only bet on load.
 * - [awaitSemantics] is a Compose semantics wait on a terminal node state,
 *   idling the main looper on every check for the same reason.
 *
 * None of these sleep, burn CPU, or read `System.currentTimeMillis`.
 */

/** Runs every task currently queued on Robolectric's paused main looper. */
fun idleMainLooper() {
    Shadows.shadowOf(Looper.getMainLooper()).idle()
}

/**
 * Suspends until [this] flow emits a value matching [predicate], and returns
 * that value. For a [kotlinx.coroutines.flow.StateFlow] this is a wait for a
 * terminal state: the caller is resumed by the emission itself, not by a poll.
 */
suspend fun <T> Flow<T>.awaitValue(predicate: (T) -> Boolean): T = first(predicate)

/**
 * Blocking form of [awaitValue] for plain (non-coroutine) JUnit tests.
 * Safe when the flow's producers run on IO/Default dispatchers or on an
 * unconfined Main dispatcher; do not use it for a value whose only producer
 * is queued on Robolectric's paused main looper — use [awaitWithMainIdle].
 */
fun <T> awaitValueBlocking(flow: Flow<T>, predicate: (T) -> Boolean): T =
    runBlocking { flow.awaitValue(predicate) }

/**
 * Idles the main looper until [condition] holds, then returns.
 *
 * Step-counted as a failure bound only: each step is a full looper idle,
 * which is the work that makes progress possible, and the thread yields
 * between steps so IO/Default producers on other threads can run. This
 * replaces `Thread.sleep` + `System.currentTimeMillis` deadline loops,
 * which under Robolectric measured a substituted clock and gave up before
 * real IO had landed.
 */
fun awaitWithMainIdle(description: String, maxSteps: Int = 10_000, condition: () -> Boolean) {
    var step = 0
    while (step++ < maxSteps) {
        idleMainLooper()
        if (condition()) return
        Thread.yield()
    }
    // One last idle + check so the failure reports the settled state.
    idleMainLooper()
    check(condition()) { "timed out (step-bounded, no wall clock): $description after $maxSteps looper idles" }
}

/**
 * Compose semantics wait on a terminal state: [ComposeContentTestRule.waitUntil]
 * re-checks [condition] after every idle, and each check first pumps the
 * main looper so a parse/load resumed on Main can land and compose.
 * The timeout is the framework's failure bound, not the mechanism.
 */
fun ComposeContentTestRule.awaitSemantics(description: String, condition: () -> Boolean) {
    waitUntil(15_000) {
        idleMainLooper()
        condition()
    }
    idleMainLooper()
    check(condition()) { "terminal semantics state not reached: $description" }
}
