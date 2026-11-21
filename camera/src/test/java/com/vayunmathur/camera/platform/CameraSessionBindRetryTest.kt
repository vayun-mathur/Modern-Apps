package com.vayunmathur.camera.platform

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bounded session-bind retry: fake setups that fail, then succeed (or don't). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraSessionBindRetryTest {

    @Test fun failingThenSucceedingSetupIsRetriedUntilSuccess() = runBlocking<Unit> {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val bound = runSessionBindWithRetry(
            maxRetries = 2,
            retryDelayMs = 7L,
            delayFn = { delays += it },
        ) {
            attempts++
            attempts >= 3 // fake setup fails twice, succeeds on the third try
        }
        assertTrue(bound)
        assertEquals(3, attempts)
        assertEquals(listOf(7L, 7L), delays)
    }

    @Test fun alwaysFailingSetupStopsAfterBoundedRetries() = runBlocking<Unit> {
        var attempts = 0
        val bound = runSessionBindWithRetry(
            maxRetries = SESSION_BIND_MAX_RETRIES,
            retryDelayMs = 1L,
            delayFn = {},
        ) {
            attempts++
            false
        }
        assertFalse(bound)
        // First attempt + SESSION_BIND_MAX_RETRIES retries, never a fourth bind.
        assertEquals(SESSION_BIND_MAX_RETRIES + 1, attempts)
    }

    @Test fun firstTrySuccessDoesNotRetryOrDelay() = runBlocking<Unit> {
        var attempts = 0
        var delays = 0
        val bound = runSessionBindWithRetry(delayFn = { delays++ }) {
            attempts++
            true
        }
        assertTrue(bound)
        assertEquals(1, attempts)
        assertEquals(0, delays)
    }

    @Test fun cancellationSupersedesRetryLadder() = runBlocking<Unit> {
        var attempts = 0
        var cancelled = false
        try {
            runSessionBindWithRetry(delayFn = { throw CancellationException("key changed") }) {
                attempts++
                false
            }
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(1, attempts)
    }

    @Test fun manualRetryClearsFailureAndBumpsRetryCounter() {
        val viewModel = CameraViewModel(ApplicationProvider.getApplicationContext())
        viewModel.sessionBindFailedMutable.value = true
        val before = viewModel.sessionBindRetryMutable.value
        viewModel.retrySessionBind()
        assertFalse(viewModel.sessionBindFailedMutable.value)
        assertEquals(before + 1, viewModel.sessionBindRetryMutable.value)
        viewModel.retrySessionBind()
        assertEquals(before + 2, viewModel.sessionBindRetryMutable.value)
    }
}
