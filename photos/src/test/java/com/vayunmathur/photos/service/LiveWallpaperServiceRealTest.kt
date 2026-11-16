package com.vayunmathur.photos.service

import android.app.Application
import android.view.SurfaceHolder
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * [LiveWallpaperService] engine lifecycle with a persisted GIF selection:
 * the surface callbacks run, the (undecodable on this host) drawable load
 * fails closed, and teardown releases everything without a player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveWallpaperServiceRealTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()


    private class FakeHolder : SurfaceHolder {
        private val surface = android.view.SurfaceView(ApplicationProvider.getApplicationContext<android.content.Context>()).holder.surface
        override fun addCallback(callback: SurfaceHolder.Callback) {}
        override fun removeCallback(callback: SurfaceHolder.Callback) {}
        override fun isCreating(): Boolean = false
        override fun setType(type: Int) {}
        override fun setFixedSize(width: Int, height: Int) {}
        override fun setSizeFromLayout() {}
        override fun setFormat(format: Int) {}
        override fun setKeepScreenOn(screenOn: Boolean) {}
        override fun lockCanvas(): android.graphics.Canvas? = null
        override fun lockCanvas(dirty: android.graphics.Rect?): android.graphics.Canvas? = null
        override fun lockHardwareCanvas(): android.graphics.Canvas? = null
        override fun unlockCanvasAndPost(canvas: android.graphics.Canvas) {}
        override fun getSurfaceFrame(): android.graphics.Rect = android.graphics.Rect(0, 0, 1080, 2400)
        override fun getSurface(): android.view.Surface = surface
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @Test fun engineLifecycleWithGifSelection() = runBlocking {
        val ds = DataStoreUtils.getInstance(app)
        ds.setString("wallpaper_uri", "content://media/animated.gif")
        ds.setBoolean("wallpaper_is_video", false)
        val controller = Robolectric.buildService(LiveWallpaperService::class.java)
        val service = controller.create().get()
        val engine = service.onCreateEngine()
        assertNotNull(engine)
        val holder = FakeHolder()
        engine.onSurfaceCreated(holder)
        engine.onSurfaceChanged(holder, 0, 1080, 2400)
        engine.onVisibilityChanged(true)
        engine.onVisibilityChanged(false)
        engine.onSurfaceDestroyed(holder)
        engine.onDestroy()
        controller.destroy()
        Unit
    }

    @Test fun engineLifecycleWithoutSelection() = runBlocking {
        val ds = DataStoreUtils.getInstance(app)
        ds.setString("wallpaper_uri", "")
        val controller = Robolectric.buildService(LiveWallpaperService::class.java)
        val service = controller.create().get()
        val engine = service.onCreateEngine()
        val holder = FakeHolder()
        engine.onSurfaceCreated(holder)
        engine.onVisibilityChanged(true)
        engine.onSurfaceDestroyed(holder)
        engine.onDestroy()
        controller.destroy()
        Unit
    }
}
