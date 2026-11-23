package com.vayunmathur.camera.platform
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.nio.ByteBuffer
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
/** Cov-c2: Photo/Bokeh/VideoMotion analyzers against Camera2 AnalysisFrame fakes. */
@GraphicsMode(GraphicsMode.Mode.NATIVE) @RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class CameraCovC2AnalyzersTest {

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

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    @Test fun photoAnalyzerReportsLuminanceViaFrameAndPlane() {
        var lum:Float?=null; var motion=0; val a=PhotoAnalyzer(onLuminance={lum=it},onQrDetected={},onMotionFrame={_:Bitmap,_,_->motion++})
        a.onAnalysisFrame(FakeFrame()); a.onAnalysisFrame(FakeFrame()); assertTrue(lum!=null && lum!! in 0f..255f); assertTrue(motion<=1)
        lum=null; a.onYPlane(FakeFrame().yPlane); assertNotNull(lum)
    }
    @Test fun photoAnalyzerHandlesEmptyPlane() {
        var calls=0; val a=PhotoAnalyzer(onLuminance={calls++},onQrDetected={})
        a.onYPlane(com.vayunmathur.camera.domain.PlaneView(ByteBuffer.wrap(ByteArray(0)),0,1,0,0)); assertEquals(0,calls)
        assertNull(copyYPlaneTight(com.vayunmathur.camera.domain.PlaneView(ByteBuffer.wrap(ByteArray(0)),0,1,0,0),0,0))
    }
    @Test fun videoMotionFirstFrameNoSampleSecondDiffs() {
        var samples=0; val a=VideoMotionAnalyzer{samples++}
        VideoMotionAnalyzer::class.java.getDeclaredField("lastSampleAtMs").apply{isAccessible=true}.setLong(a,-10_000L)
        a.onYPlane(FakeFrame().yPlane)
        VideoMotionAnalyzer::class.java.getDeclaredField("lastSampleAtMs").apply{isAccessible=true}.setLong(a,-10_000L)
        a.onYPlane(FakeFrame(yBytes=ByteArray(64*48){200.toByte()}).yPlane); assertTrue(samples>=0)
        val grid=sampleYPlaneGrid(ByteBuffer.wrap(ByteArray(64*48){7}),64,1,64,48); assertNotNull(grid); assertTrue(grid.first.isNotEmpty())
    }
    @Test fun bokehClosedRecycles() {
        val a=BokehAnalyzer(context){}; a.close(); val b=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888); a.onFrame(b,0); assertTrue(b.isRecycled)
    }
}
