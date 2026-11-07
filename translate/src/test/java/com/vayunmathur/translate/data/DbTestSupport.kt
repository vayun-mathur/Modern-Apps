package com.vayunmathur.translate.data

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs a suspending DB block off the Robolectric main thread.
 *
 * `runBlocking` on the (paused-looper) main thread deadlocks against Room:
 * the block's continuations and its virtual-clock timeouts are posted to the
 * Android main looper, which nobody pumps while the test thread is parked.
 * Here the work runs on [Dispatchers.IO] and the test thread pumps the main
 * looper until it completes.
 */
fun <T> runDb(block: suspend () -> T): T {
    val done = CountDownLatch(1)
    var failure: Throwable? = null
    var result: T? = null
    val worker = Thread {
        try {
            result = runBlocking(Dispatchers.IO) { block() }
        } catch (t: Throwable) {
            failure = t
        } finally {
            done.countDown()
        }
    }
    worker.start()
    var pumps = 0
    while (!done.await(25, TimeUnit.MILLISECONDS)) {
        shadowOf(Looper.getMainLooper()).idle()
        if (++pumps > 4000) throw AssertionError("DB work never finished after 100s of looper pumping")
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/**
 * Polls a one-shot DB read until it matches. Room Flows emit once per
 * invalidation, so snapshot polling is more robust here than holding a
 * `first { predicate }` collection open across writes.
 */
fun <T> pollDb(block: suspend () -> T, predicate: (T) -> Boolean): T = runDb {
    var value = block()
    var attempts = 0
    while (!predicate(value)) {
        if (++attempts > 400) throw AssertionError("DB state never matched; last=$value")
        kotlinx.coroutines.delay(25)
        value = block()
    }
    value
}
