package com.vayunmathur.library.map

import android.app.Application
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The render-thread contract every native call now relies on.
 *
 * The native renderer is single-threaded (`bridge::handle_mut` hands out an unlocked `&mut`),
 * and it no longer runs on the main thread, so [MapRenderThread] must give exactly three
 * guarantees: one thread, submission order, and a caller that is never blocked past its bound.
 * Plain JVM: the executor is java.util.concurrent, and `Process.setThreadPriority` is a stub
 * that returns (this module sets `isReturnDefaultValues`).
 */
class MapRenderThreadTest {

    @Test
    fun posts_run_in_order_on_one_thread_that_is_not_the_caller() {
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
        val done = CountDownLatch(1)
        repeat(200) { i ->
            MapRenderThread.post {
                order += i
                threads += Thread.currentThread()
            }
        }
        MapRenderThread.post { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS), "render thread never drained")
        assertEquals((0 until 200).toList(), order.toList(), "native calls must keep submission order")
        assertEquals(1, threads.size, "every native call must land on the same thread")
        assertNotSame(Thread.currentThread(), threads.single(), "native work must leave the calling thread")
    }

    @Test
    fun call_returns_the_answer_and_runs_inline_when_reentered() {
        assertEquals(42, MapRenderThread.call(5_000, -1) { 42 })
        // A synchronous query issued from a render-thread task must not wait on its own queue.
        val nested = MapRenderThread.call(5_000, -1) { MapRenderThread.call(5_000, -2) { 7 } }
        assertEquals(7, nested)
    }

    @Test
    fun a_slow_render_thread_costs_the_caller_its_bound_not_its_duration() {
        val release = CountDownLatch(1)
        MapRenderThread.post { release.await(5, TimeUnit.SECONDS) }
        val started = System.nanoTime()
        val answer = MapRenderThread.call(50, "fallback") { "late" }
        val waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        release.countDown()
        assertEquals("fallback", answer)
        assertTrue(waitedMillis < 1_000, "caller waited ${waitedMillis}ms past a 50 ms bound")
    }

    @Test
    fun a_throwing_task_does_not_kill_the_thread() {
        MapRenderThread.post { error("boom") }
        assertEquals("alive", MapRenderThread.call(5_000, "dead") { "alive" })
        assertEquals("fallback", MapRenderThread.call(5_000, "fallback") { error("boom") })
    }

    @Test
    fun a_renderer_without_a_surface_never_touches_the_render_thread() {
        // No surface: every native call is dropped (remembered for the attach replay), and
        // the synchronous queries answer their fallback without waiting on anything.
        val r = SurfaceMapRenderer(Application(), density = 3f)
        var ran = false
        r.onRenderThread { ran = true }
        assertEquals(-1L, r.queryRenderThread(5_000, -1L) { 1L })
        r.trimMemory(80)
        r.detachSurface()
        MapRenderThread.call(5_000, Unit) {}
        assertFalse(ran)
        assertEquals(MapRenderState.Initialising, r.renderState)
    }
}
