package com.vayunmathur.library.avatar

import android.view.Surface
import com.vayunmathur.library.avatar.yuu.YUU_POSES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests run against the host build of `libyuu_avatar` (the test infra
 * puts it on `java.library.path`), so the JNI bridge is exercised for real:
 * most importantly, the preset gallery the engine reports must stay in sync
 * with the Kotlin mirror in [YUU_POSES] — Rust is authoritative.
 */
@RunWith(RobolectricTestRunner::class)
class YuuAvatarNativeTest {

    @Test
    fun `host native library loads for unit tests`() {
        assertTrue(
            YuuAvatarNative.isAvailable,
            "host libyuu_avatar should be on java.library.path in unit tests",
        )
    }

    @Test
    fun `engine poses match the kotlin gallery mirror`() {
        val ids = Json.parseToJsonElement(YuuAvatarNative.getPosesJson())
            .jsonArray.map { it.jsonPrimitive.content }
        assertEquals(YUU_POSES.map { it.id }, ids, "Rust preset_poses and YUU_POSES drifted")
    }

    @Test
    fun `config and lifecycle calls reach native without crashing`() {
        YuuAvatarNative.setConfig(1, "{}", "wave")
        YuuAvatarNative.setConfig(1, "{}", "{\"id\":\"idle\"}")
        YuuAvatarNative.setConfig(2, "not json", "bogus-pose")
        YuuAvatarNative.surfaceChanged(1, 640, 480)
        YuuAvatarNative.surfaceDestroyed(1)
        YuuAvatarNative.setPaused(1, true)
        YuuAvatarNative.setPaused(1, false)
        YuuAvatarNative.onRotateInput(1, 0.5f, -0.25f)
        YuuAvatarNative.setAutoRotate(1, true)
        YuuAvatarNative.setAutoRotate(1, false)
        YuuAvatarNative.onDestroy()
    }

    @Test
    fun `bundled mesh fd handoff tolerates a bad fd`() {
        // A detached fd the engine cannot parse must fail soft, not crash.
        YuuAvatarNative.setBundledMeshFd(-1, 0L, 0L)
    }

    @Test
    fun `surface created with a real surface does not crash`() {
        val surface = Surface(android.graphics.SurfaceTexture(0))
        YuuAvatarNative.surfaceCreated(surface, 7)
        YuuAvatarNative.surfaceDestroyed(7)
        surface.release()
    }
}
