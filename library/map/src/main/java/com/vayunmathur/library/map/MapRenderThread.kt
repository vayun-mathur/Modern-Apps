package com.vayunmathur.library.map

import android.os.Handler
import android.os.Looper
import android.os.Process
import com.vayunmathur.library.log.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The one thread every [MapNative] call runs on.
 *
 * The native renderer is single-threaded by contract — `bridge::handle_mut` hands out a plain
 * `&mut` with no lock behind it — so every call into it must come from one thread. That thread
 * used to be the main thread: the whole frame (upload drain, tile selection, label placement,
 * command recording, the fence wait, the swapchain acquire and the present) ran on the
 * Choreographer callback, and so did `create` (Vulkan bring-up, pipeline compile, the glyph
 * atlas), `destroy` and `trimMemory` (`vkDeviceWaitIdle`), a swapchain rebuild
 * (`vkDeviceWaitIdle` plus a recompile of every pipeline), the 36 MB Moon upload and the tap
 * pick (a one-shot render plus `vkQueueWaitIdle`). Every millisecond of that was a millisecond
 * Compose could not lay out, draw or take input in, which is what "visibly laggy when moving
 * around" was made of, and any of the waits stretching was a "Maps isn't responding" dialog.
 *
 * Now the main thread only decides *when* to draw (the on-demand Choreographer loop in
 * [SurfaceMapRenderer] is unchanged) and *what* to draw (it reads the camera snapshot), and
 * hands the work here. Calls are serialised in submission order on one dedicated thread at
 * display priority, so the single-threaded contract holds exactly as before, only on a thread
 * that is allowed to block.
 *
 * One thread for the process rather than one per renderer: renderers come and go with
 * navigation, a shared queue needs no lifetime management, and a car surface and a phone
 * surface drawing at once simply take turns. Created lazily, so a process that never attaches
 * a surface — every JVM unit test — never starts it.
 */
internal object MapRenderThread {

    private const val TAG = "MapRenderThread"

    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(
                {
                    // Display priority, like the platform's own RenderThread: frame work must
                    // not queue behind background tile decode or image loading.
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
                    } catch (_: Throwable) {
                        // Not fatal: the thread still runs at default priority.
                    }
                    task.run()
                },
                "MapRender",
            ).apply { isDaemon = true }
        }
    }

    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** The executor's thread once it has run anything; used to make [call] re-entrant. */
    @Volatile
    private var thread: Thread? = null

    /** Run [task] on the render thread after everything already queued. Never blocks. */
    fun post(task: () -> Unit) {
        executor.execute {
            thread = Thread.currentThread()
            try {
                task()
            } catch (t: Throwable) {
                // A throwing task must not kill the only thread the renderer has.
                Log.error(TAG, "render-thread task failed", t)
            }
        }
    }

    /** Run [task] on the main thread, for results that feed main-thread state. */
    fun toMain(task: () -> Unit) {
        main.post(task)
    }

    /**
     * Run [task] on the render thread and wait for its result, at most [timeoutMillis].
     *
     * For the few calls whose callers need an answer synchronously (a tap pick, a host that
     * drives its own frame loop, a detach that must not return before the native renderer is
     * gone). The wait is bounded: past it the caller gets [fallback] and the task still runs
     * later, so the main thread can be delayed by at most the bound, never frozen. Called on
     * the render thread itself it runs [task] inline rather than deadlocking on its own queue.
     */
    fun <T> call(timeoutMillis: Long, fallback: T, task: () -> T): T {
        if (Thread.currentThread() === thread) return task()
        val result = AtomicReference<Any?>(Unset)
        val done = CountDownLatch(1)
        post {
            try {
                result.set(task())
            } finally {
                done.countDown()
            }
        }
        val finished = try {
            done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            Log.error(TAG, "render thread did not answer within ${timeoutMillis}ms; using the fallback")
            return fallback
        }
        val value = result.get()
        @Suppress("UNCHECKED_CAST")
        return if (value === Unset) fallback else value as T
    }

    /** Marker for "the task threw before producing a value". */
    private object Unset
}
