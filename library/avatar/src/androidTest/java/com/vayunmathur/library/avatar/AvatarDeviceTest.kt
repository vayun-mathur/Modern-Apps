package com.vayunmathur.library.avatar

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.library.avatar.components.YuuAvatarSurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device surface lifecycle. The emulator is x86_64 and the native engine
 * ships arm64-only, so this exercises the shipped fallback contract: views
 * attach, surfaces come and go, and nothing crashes without the library.
 */
class AvatarDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeIsUnavailableOnX86Emulator() {
        assertFalse(YuuAvatarNative.isAvailable)
        assertEquals("[]", YuuAvatarNative.getPosesJson())
    }

    @Test
    fun adoptBundledMeshIsNoOpWithoutNative() {
        YuuAvatarAssets.adoptBundledMesh(context.assets)
    }

    @Test
    fun surfaceViewLifecycleCallbacksFire() {
        val created = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var view: YuuAvatarSurfaceView
            scenario.onActivity { activity ->
                view = YuuAvatarSurfaceView(activity, surfaceId = 5)
                view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: android.view.View) = Unit
                    override fun onViewDetachedFromWindow(v: android.view.View) = Unit
                })
                val holder = view.holder
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(h: android.view.SurfaceHolder) = created.countDown()
                    override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) = Unit
                    override fun surfaceDestroyed(h: android.view.SurfaceHolder) = destroyed.countDown()
                })
                activity.setContentView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            assertEquals(true, created.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                (view.parent as? ViewGroup)?.removeView(view)
            }
            assertEquals(true, destroyed.await(10, TimeUnit.SECONDS))
            assertEquals(5, view.surfaceId)
        }
    }
}
