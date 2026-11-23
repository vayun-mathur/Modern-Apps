package com.vayunmathur.camera.platform
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
/** Single capture: Camera2 session no-op, night exposure, downscaledThumbnail(Bitmap,...). */
@GraphicsMode(GraphicsMode.Mode.NATIVE) @RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class SingleCaptureNightTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)
    private fun jpeg(): ByteArray { val b=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888); b.eraseColor(Color.BLUE); val o=ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG,95,o); b.recycle(); return o.toByteArray() }
    @Test fun captureSinglePhotoWithoutSessionIsNoOp() { val v=vm(); v.captureSinglePhoto(); assertEquals(false, v.isCapturingMutable.value) }
    @Test fun capturePhotoForResultWithoutSessionErrors() { val v=vm(); var err=false; v.capturePhotoForResult({},{err=true}); assertTrue(err) }
    @Test fun nightExposureClampsToSessionOrFallback() {
        val v=vm(); val e=v.computeNightExposure(33_000_000L); assertTrue(e.nanos>0)
        assertEquals("Capturing 1/6", nightCaptureProgressLabel(1,6)); assertEquals(0.5f, nightCaptureProgressFraction(3,6))
    }
    @Test fun downscaledThumbnailRotatesMirrorsAndScales() {
        val src=Bitmap.createBitmap(1024,512,Bitmap.Config.ARGB_8888); src.eraseColor(Color.RED)
        val thumb=downscaledThumbnail(src,90,true); assertTrue(maxOf(thumb.width,thumb.height)<=512); assertTrue(src.isRecycled)
        val small=Bitmap.createBitmap(100,50,Bitmap.Config.ARGB_8888); val t2=downscaledThumbnail(small,0,false); assertEquals(100,t2.width)
    }
    @Test fun decodeNightFrameViaCaptureNightBurstNoSession() {
        val v=vm(); var done:List<Bitmap>?=null; v.captureNightBurst(NightExposure(1_000L,null),{}, {done=it}); assertEquals(emptyList(),done)
    }
}
