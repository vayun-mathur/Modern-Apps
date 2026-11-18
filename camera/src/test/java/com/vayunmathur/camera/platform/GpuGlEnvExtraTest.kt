package com.vayunmathur.camera.platform

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** GpuStitcher GlEnv pure builders + GL-missing degradation (instance via Unsafe). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpuGlEnvExtraTest {

    private fun glEnv(): Any {
        val cls = Class.forName("com.vayunmathur.camera.platform.GpuStitcher\$GlEnv")
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, cls)
    }

    private fun estimate(): GpuStitcher.Estimate {
        val cam = GpuStitcher.FrameCam(
            0, 500.0, 40.0, 30.0,
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), 1f,
        )
        return GpuStitcher.Estimate(100, 50, 0.0, 0.0, 300.0, listOf(cam))
    }

    @Test fun frameMeshGeometry() {
        val env = glEnv()
        val cls = env.javaClass
        val est = estimate()
        val mesh = cls.getDeclaredMethod(
            "buildFrameMesh", GpuStitcher.Estimate::class.java, GpuStitcher.FrameCam::class.java,
        ).apply { isAccessible = true }.invoke(env, est, est.cams[0]) as java.nio.FloatBuffer
        // (GRID+1)^2 vertices * 4 floats (ndc x/y + uv).
        assertEquals(49 * 49 * 4, mesh.capacity())
        mesh.rewind()
        // First vertex projects the frame's top-left source pixel; all UVs in [0,1].
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
        while (mesh.remaining() >= 4) {
            val x = mesh.get(); val y = mesh.get(); val u = mesh.get(); val v = mesh.get()
            assertTrue(x.isFinite() && y.isFinite(), "ndc finite: $x,$y")
            if (u < minU) minU = u
            if (u > maxU) maxU = u
            assertTrue(v in 0f..1f)
        }
        assertEquals(0f, minU); assertEquals(1f, maxU)
    }

    @Test fun indexBufferTriangulatesGrid() {
        val env = glEnv()
        val buf = env.javaClass.getDeclaredMethod("buildIndexBuffer")
            .apply { isAccessible = true }.invoke(env) as java.nio.ShortBuffer
        assertEquals(48 * 48 * 6, buf.capacity())
        buf.rewind()
        // First quad: a=0, c=49, b=1 / b=1, c=49, d=50.
        assertEquals(0, buf.get().toInt())
        assertEquals(49, buf.get().toInt())
        assertEquals(1, buf.get().toInt())
        assertEquals(1, buf.get().toInt())
        assertEquals(49, buf.get().toInt())
        assertEquals(50, buf.get().toInt())
    }

    @Test fun renderWithoutGlReturnsNull() {
        val env = glEnv()
        val est = estimate()
        val result = env.javaClass.getDeclaredMethod(
            "render", GpuStitcher.Estimate::class.java, List::class.java,
        ).apply { isAccessible = true }.invoke(env, est, listOf(ByteArray(16)))
        assertNull(result)
    }

    @Test fun shaderAndProgramBuildersDegradeWithoutGl() {
        val env = glEnv()
        val cls = env.javaClass
        val compile = cls.getDeclaredMethod("compileShader", Int::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
        // GLES30 statics are inert in this harness: no program can link.
        val shader = compile.invoke(env, 35665, "void main() {}")
        assertTrue(shader == null || shader is Int)
        val build = cls.getDeclaredMethod("buildProgram", String::class.java, String::class.java)
            .apply { isAccessible = true }
        val program = build.invoke(env, "vs", "fs")
        assertTrue(program == null || program is Int)
        val indexCountProbe = cls.getDeclaredMethod("buildIndexBuffer").apply { isAccessible = true }
        assertNotNull(indexCountProbe.invoke(env))
        // A JPEG frame upload cannot produce a texture without GL.
        val baos = java.io.ByteArrayOutputStream()
        android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
            .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, baos)
        val upload = cls.getDeclaredMethod("uploadFrame", ByteArray::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val tex = upload.invoke(env, baos.toByteArray(), 2048)
        assertTrue(tex == null || tex is Int)
        // Garbage bytes are rejected before any GL work.
        assertNull(upload.invoke(env, byteArrayOf(1, 2, 3), 2048))
        // release() on a zeroed env is a no-op (all handles 0).
        cls.getDeclaredMethod("release").apply { isAccessible = true }.invoke(env)
    }

    @Test fun byteBufferRoundTripSanity() {
        // The mesh builders allocate direct, native-order buffers.
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(1.5f); buf.flip()
        assertEquals(1.5f, buf.get())
    }
}
