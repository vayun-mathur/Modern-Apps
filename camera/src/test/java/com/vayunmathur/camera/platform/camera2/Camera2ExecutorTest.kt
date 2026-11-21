package com.vayunmathur.camera.platform.camera2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Camera2ExecutorTest {

    @Test fun executesTasksOnItsOwnThread() {
        val executor = Camera2Executor("Camera2ExecutorTest-worker")
        try {
            val ran = CountDownLatch(1)
            val onWorker = AtomicBoolean(false)
            executor.execute {
                onWorker.set(executor.isOnCameraThread())
                ran.countDown()
            }
            assertTrue(ran.await(5, TimeUnit.SECONDS), "task did not run")
            assertTrue(onWorker.get(), "task did not run on the executor thread")
            assertFalse(executor.isOnCameraThread(), "test thread must not be the executor thread")
        } finally {
            executor.shutdown()
        }
    }

    @Test fun shutdownIsDeterministicAndIdempotent() {
        val executor = Camera2Executor("Camera2ExecutorTest-shutdown")
        val ran = CountDownLatch(1)
        executor.execute { ran.countDown() }
        assertTrue(ran.await(5, TimeUnit.SECONDS))

        assertTrue(executor.shutdown(), "thread did not terminate within the timeout")
        assertTrue(executor.isShutdown)
        // Second shutdown is a no-op and still reports terminated.
        assertTrue(executor.shutdown())
    }

    @Test fun workSubmittedAfterShutdownIsRejected() {
        val executor = Camera2Executor("Camera2ExecutorTest-reject")
        executor.shutdown()
        try {
            executor.execute { fail("must not run after shutdown") }
            fail("expected RejectedExecutionException")
        } catch (expected: RejectedExecutionException) {
            // expected
        }
        assertFalse(executor.postMain { fail("must not post after shutdown") })
    }

    @Test fun closeDelegatesToShutdown() {
        val executor = Camera2Executor("Camera2ExecutorTest-close")
        executor.use {
            val ran = CountDownLatch(1)
            it.execute { ran.countDown() }
            assertTrue(ran.await(5, TimeUnit.SECONDS))
        }
        assertTrue(executor.isShutdown)
    }
}
