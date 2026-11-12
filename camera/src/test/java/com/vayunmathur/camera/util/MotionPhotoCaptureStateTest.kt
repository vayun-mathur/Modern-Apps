package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capture state machine for background Motion Photo saves (#914): the shutter is released when
 * the still is captured, not when its clip is saved; saves run in shot order; frames are freed
 * only after their save; and with the save queue full a press falls back to a plain still.
 * No CameraX binding: the capture callback's hand-off ([onMotionStillCaptured]) and the router
 * ([routeStillCapture]) are driven directly.
 */
@RunWith(AndroidJUnit4::class)
class MotionPhotoCaptureStateTest {

    private val scheduler = TestCoroutineScheduler()

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext()).apply {
        motionSaveDispatcher = StandardTestDispatcher(scheduler)
    }

    private fun request(name: String, frameCount: Int = 2) = MotionSaveRequest(
        jpegBytes = byteArrayOf(1, 2, 3),
        frames = List(frameCount) { i ->
            CameraViewModel.MotionFrame(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), i * 1_000L, 90)
        },
        rotationDegrees = 90,
        mirrored = false,
        displayName = name
    )

    private fun uri(name: String): Uri = Uri.parse("content://media/external/images/media/$name")

    @Test
    fun defaultPhotoShotRoutesToMotionPhotoAndReservesASlot() {
        val v = vm()
        assertEquals(StillRoute.MOTION_PHOTO, v.routeStillCapture())
        assertEquals(1, v.motionSaveQueue.pending)
    }

    @Test
    fun inFlightReleasedOnCaptureNotOnSave() {
        val v = vm()
        val gate = CompletableDeferred<Uri?>()
        v.motionPhotoSaver = { gate.await() }
        v.isCapturingMutable.value = true
        assertEquals(StillRoute.MOTION_PHOTO, v.routeStillCapture())
        val req = request("a")

        v.onMotionStillCaptured(req)

        // Captured: the shutter is free immediately, while the save hasn't even started.
        assertFalse(v.isCapturing.value)
        scheduler.advanceUntilIdle()
        // Saving (blocked on the gate): still free, slot still held, frames still alive.
        assertFalse(v.isCapturing.value)
        assertEquals(1, v.motionSaveQueue.pending)
        assertNull(v.lastCaptureUri.value)
        assertFalse(req.frames.any { it.bitmap.isRecycled })

        gate.complete(uri("a"))
        scheduler.advanceUntilIdle()
        // Saved: thumbnail source updated, slot returned, frames freed.
        assertEquals(uri("a"), v.lastCaptureUri.value)
        assertEquals(0, v.motionSaveQueue.pending)
        assertTrue(req.frames.all { it.bitmap.isRecycled })
    }

    @Test
    fun nextShotAllowedWhileEarlierSaveRuns() {
        val v = vm()
        val gate = CompletableDeferred<Uri?>()
        v.motionPhotoSaver = { gate.await() }
        assertEquals(StillRoute.MOTION_PHOTO, v.routeStillCapture())
        v.onMotionStillCaptured(request("a"))
        scheduler.advanceUntilIdle()

        // Second press while the first clip is still encoding: another Motion Photo.
        assertFalse(v.isCapturing.value)
        assertEquals(StillRoute.MOTION_PHOTO, v.routeStillCapture())
        assertEquals(2, v.motionSaveQueue.pending)
    }

    @Test
    fun queueFullFallsBackToPlainStill() {
        val v = vm()
        repeat(CameraViewModel.MAX_PENDING_MOTION_SAVES) {
            assertEquals(StillRoute.MOTION_PHOTO, v.routeStillCapture())
        }
        // Every slot is held by a shot still saving: this press must not block or queue more
        // frames, it takes a plain still and leaves the queue as it was.
        assertEquals(StillRoute.SINGLE, v.routeStillCapture())
        assertEquals(CameraViewModel.MAX_PENDING_MOTION_SAVES, v.motionSaveQueue.pending)

        // Once a save lands, the next press is a Motion Photo again.
        v.motionPhotoSaver = { uri("x") }
        v.onMotionStillCaptured(request("x"))
        scheduler.advanceUntilIdle()
        assertEquals(StillRoute.MOTION_PHOTO, v.routeStillCapture())
    }

    @Test
    fun savesLandInShotOrderEvenWhenTheFirstIsSlower() {
        val v = vm()
        val firstGate = CompletableDeferred<Unit>()
        val finished = mutableListOf<String>()
        v.motionPhotoSaver = { req ->
            if (req.displayName == "first") firstGate.await()
            finished += req.displayName
            uri(req.displayName)
        }
        repeat(2) { v.routeStillCapture() }
        v.onMotionStillCaptured(request("first"))
        v.onMotionStillCaptured(request("second"))
        scheduler.advanceUntilIdle()
        assertTrue(finished.isEmpty(), "second must wait for first, got $finished")
        assertNull(v.lastCaptureUri.value)

        firstGate.complete(Unit)
        scheduler.advanceUntilIdle()
        assertEquals(listOf("first", "second"), finished)
        assertEquals(uri("second"), v.lastCaptureUri.value)
        assertEquals(0, v.motionSaveQueue.pending)
    }

    @Test
    fun failedSaveStillReleasesSlotAndFrames() {
        val v = vm()
        v.motionPhotoSaver = { error("encoder blew up") }
        v.routeStillCapture()
        val req = request("boom")
        v.onMotionStillCaptured(req)
        scheduler.advanceUntilIdle()
        assertEquals(0, v.motionSaveQueue.pending)
        assertTrue(req.frames.all { it.bitmap.isRecycled })
        assertNull(v.lastCaptureUri.value)
        assertFalse(v.isCapturing.value)
    }

    @Test
    fun nonMotionModesDoNotTouchTheQueue() {
        val v = vm()
        v.aspectRatioMutable.value = AspectRatioOption.RATIO_16_9
        assertEquals(StillRoute.SINGLE, v.routeStillCapture())
        v.aspectRatioMutable.value = AspectRatioOption.RATIO_4_3
        v.warmthMutable.value = 0.5f
        assertEquals(StillRoute.SINGLE, v.routeStillCapture())
        assertEquals(0, v.motionSaveQueue.pending)
    }
}
