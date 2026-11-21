package com.vayunmathur.camera.platform.camera2

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Camera2 migration Phase 0: single-threaded background executor for Camera2
 * callbacks, plus a main-thread poster.
 *
 * Ownership: whoever constructs this owns it and must call [shutdown] exactly
 * when the owning session/screen is destroyed. The executor is single-use —
 * after [shutdown] it never restarts; construct a new one for a new session.
 * Work submitted after shutdown is rejected, never silently queued.
 */
class Camera2Executor(
    threadName: String = DEFAULT_THREAD_NAME,
) : Executor, AutoCloseable {

    private val thread = HandlerThread(threadName).apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isShutdown: Boolean = false
        private set

    val looper: Looper get() = thread.looper

    fun isOnCameraThread(): Boolean = Looper.myLooper() == thread.looper

    override fun execute(command: Runnable) {
        if (isShutdown || !handler.post(command)) {
            throw RejectedExecutionException("Camera2Executor is shut down")
        }
    }

    /** Posts to the main thread; returns false (and drops the task) after shutdown. */
    fun postMain(task: Runnable): Boolean = !isShutdown && mainHandler.post(task)

    /**
     * Deterministic shutdown: stops accepting work, quits safely (already-queued
     * tasks run to completion), then joins the thread for up to [timeoutMs].
     * Idempotent. Returns true when the thread actually terminated.
     */
    fun shutdown(timeoutMs: Long = DEFAULT_SHUTDOWN_TIMEOUT_MS): Boolean {
        if (isShutdown) return !thread.isAlive
        isShutdown = true
        thread.quitSafely()
        if (timeoutMs > 0 && Thread.currentThread() !== thread) {
            runCatching { thread.join(timeoutMs) }
        }
        return !thread.isAlive
    }

    override fun close() {
        shutdown()
    }

    companion object {
        const val DEFAULT_THREAD_NAME = "Camera2Executor"
        const val DEFAULT_SHUTDOWN_TIMEOUT_MS = 2_000L
    }
}
