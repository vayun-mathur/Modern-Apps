package com.vayunmathur.camera.platform
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ml.SegmentationMask
import kotlin.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
/** BokehAnalyzer Camera2 onFrame lifecycle + smoothing/helpers. */
@GraphicsMode(GraphicsMode.Mode.NATIVE) @RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class BokehAnalyzerMathTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    @Test fun closedAnalyzerRecyclesBitmapAndCloseIdempotent() {
        var emitted=0; val a=BokehAnalyzer(context,false){emitted++}; a.close(); a.close()
        val bmp=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888); a.onFrame(bmp,0); assertTrue(bmp.isRecycled); assertEquals(0,emitted)
    }
    @Test fun liveOnFrameNeverThrows() {
        var emitted=0; val a=BokehAnalyzer(context,true){emitted++}
        val bmp=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888); a.onFrame(bmp,90); assertEquals(0,emitted); a.close()
    }
    @Test fun smoothAndEmitBlendsTemporally() {
        var last:Bitmap?=null; val a=BokehAnalyzer(context){last=it}
        val m=BokehAnalyzer::class.java.getDeclaredMethod("smoothAndEmit",SegmentationMask::class.java).apply{isAccessible=true}
        m.invoke(a,SegmentationMask(4,3,FloatArray(12){1f})); assertNotNull(last); assertEquals(255,Color.alpha(last!!.getPixel(0,0)))
        m.invoke(a,SegmentationMask(4,3,FloatArray(12){0f})); assertEquals(89,Color.alpha(last!!.getPixel(1,1)))
        a.close(); val before=last; m.invoke(a,SegmentationMask(4,3,FloatArray(12){1f})); assertTrue(last===before)
    }
    @Test fun framePrepHelpersDownscaleAndOrient() {
        val a=BokehAnalyzer(context,true){}; val cls=BokehAnalyzer::class.java
        val down=cls.getDeclaredMethod("downscaleIfNeeded",Bitmap::class.java,Int::class.javaPrimitiveType).apply{isAccessible=true}
        val big=Bitmap.createBitmap(100,50,Bitmap.Config.ARGB_8888); val small=down.invoke(a,big,50) as Bitmap
        assertEquals(50,small.width); assertEquals(25,small.height); assertTrue(down.invoke(a,small,512)===small)
        val orient=cls.getDeclaredMethod("orientToDisplay",Bitmap::class.java,Int::class.javaPrimitiveType).apply{isAccessible=true}
        val rotated=orient.invoke(a,small,90) as Bitmap; assertEquals(25,rotated.width); assertEquals(50,rotated.height)
        val back=BokehAnalyzer(context,false){}; assertTrue(orient.invoke(back,small,0)===small); a.close(); back.close()
    }
}
