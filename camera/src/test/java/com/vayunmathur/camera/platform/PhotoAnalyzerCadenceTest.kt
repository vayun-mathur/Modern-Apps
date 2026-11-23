package com.vayunmathur.camera.platform
import android.graphics.Bitmap
import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
/** PhotoAnalyzer Camera2 cadence paths via onAnalysisFrame/onYPlane/copyYPlaneTight. */
@GraphicsMode(GraphicsMode.Mode.NATIVE) @RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class PhotoAnalyzerCadenceTest {

    class FakeFrame(
        val w: Int = 64, val h: Int = 48,
        val yBytes: ByteArray = ByteArray(64 * 48) { (it * 7 % 251).toByte() },
        override val timestampNs: Long = 42L,
        override val rotationDegrees: Int = 90,
    ) : com.vayunmathur.camera.platform.camera2.AnalysisFrame {
        var closed = false
        override val width get() = w; override val height get() = h
        override val cropRect = com.vayunmathur.camera.domain.FrameCrop.fullFrame(w, h)
        override val yPlane = com.vayunmathur.camera.domain.PlaneView(java.nio.ByteBuffer.wrap(yBytes), w, 1, w, h)
        override val planes: List<com.vayunmathur.camera.domain.PlaneView> = listOf(
            yPlane,
            com.vayunmathur.camera.domain.PlaneView(java.nio.ByteBuffer.wrap(ByteArray((w+1)/2*((h+1)/2)){128.toByte()}), (w+1)/2, 1, (w+1)/2, (h+1)/2),
            com.vayunmathur.camera.domain.PlaneView(java.nio.ByteBuffer.wrap(ByteArray((w+1)/2*((h+1)/2)){128.toByte()}), (w+1)/2, 1, (w+1)/2, (h+1)/2),
        )
        override fun close() { closed = true }
    }

    private fun age(a: PhotoAnalyzer) { for (n in listOf("lastMotionAtMs","lastDecodeAtMs")) PhotoAnalyzer::class.java.getDeclaredField(n).apply{isAccessible=true}.setLong(a,-10_000L) }
    @Test fun agedCadenceRunsMotionQrAndLuma() {
        var lum: Float? = null; var motion=0; var ts=-1L; var rot=-1
        val a = PhotoAnalyzer(onLuminance={lum=it}, onQrDetected={}, onMotionFrame={_:Bitmap,t:Long,r:Int->motion++;ts=t;rot=r})
        age(a); a.onAnalysisFrame(FakeFrame())
        assertTrue(lum!=null && lum!! in 0f..255f); assertTrue(motion<=1); if(motion==1){assertEquals(42L,ts);assertEquals(90,rot)}
    }
    @Test fun throwingCallbacksDoNotBreakFrame() {
        val a = PhotoAnalyzer(onLuminance={}, onQrDetected={throw IllegalStateException("gone")}, onMotionFrame={_,_,_->throw IllegalArgumentException("closed")})
        age(a); a.onAnalysisFrame(FakeFrame()) // must not throw
    }
    @Test fun freshCadenceSkipsExpensiveOpsButReportsLuma() {
        var motion=0; var lum:Float?=null
        val a = PhotoAnalyzer(onLuminance={lum=it}, onQrDetected={}, onMotionFrame={_,_,_->motion++})
        a.onAnalysisFrame(FakeFrame()); assertEquals(0,motion); assertNotNull(lum)
    }
    @Test fun onYPlaneReportsLumaAndCopyYPlaneTightHonoursStrides() {
        var lum:Float?=null; val a=PhotoAnalyzer(onLuminance={lum=it},onQrDetected={})
        val plane=com.vayunmathur.camera.domain.PlaneView(ByteBuffer.wrap(byteArrayOf(10,20,30,0,40,50,60,0)),4,1,3,2)
        a.onYPlane(plane); assertNotNull(lum)
        assertContentEquals(byteArrayOf(10,20,30,40,50,60), copyYPlaneTight(plane,3,2))
        assertNull(copyYPlaneTight(plane,0,2)); assertNull(copyYPlaneTight(com.vayunmathur.camera.domain.PlaneView(ByteBuffer.wrap(ByteArray(2)),1,1,4,4),4,4))
    }
}
