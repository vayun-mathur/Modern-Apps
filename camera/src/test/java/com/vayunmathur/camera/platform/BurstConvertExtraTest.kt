package com.vayunmathur.camera.platform
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
/** Night burst via Camera2Session (no session -> empty) + Bokeh segmentLocked recycle. */
@GraphicsMode(GraphicsMode.Mode.NATIVE) @RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class BurstConvertExtraTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)
    @Test fun burstWithoutSessionFinishesEmpty() { val v=vm(); var done:List<Bitmap>?=null; v.captureNightBurst(NightExposure(1_000L,null),{}, {done=it}); assertEquals(emptyList(),done) }
    @Test fun startBurstWithoutSessionIsNoOp() { val v=vm(); v.startBurst(); assertFalse(v.burstActiveMutable.value) }
    @Test fun segmentLockedWithoutSegmenterRecyclesFrame() {
        var emitted=0; val a=BokehAnalyzer(context){emitted++}; val frame=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GREEN)}
        val m=BokehAnalyzer::class.java.getDeclaredMethod("segmentLocked",Bitmap::class.java).apply{isAccessible=true}
        assertEquals(null,m.invoke(a,frame)); assertTrue(frame.isRecycled); assertEquals(0,emitted); a.close()
    }
}
