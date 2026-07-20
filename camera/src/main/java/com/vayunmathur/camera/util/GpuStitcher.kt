package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.atan2
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * GPU panorama compositor (OpenGL ES 3.x, headless EGL pbuffer + FBO). Replaces
 * the CPU per-pixel warp + feather blend with a textured-mesh remap + additive
 * blend on an RGBA16F accumulation buffer, then a normalization pass.
 *
 * Registration (features/match/estimate/BA/wave) stays in Rust; this consumes
 * its [Estimate] blob (see [parseEstimate]) plus the captured JPEG frames and
 * produces a cropped [Bitmap]. Everything here returns null on any failure so
 * the caller can fall back to the Rust CPU stitcher.
 */
object GpuStitcher {

    private const val TAG = "GpuStitcher"

    // Warp mesh resolution (grid cells per axis over each source image).
    private const val GRID = 48

    // Feather width in source-UV units (distance from edge over which weight ramps).
    private const val FEATHER = 0.15f

    // Coverage threshold: accumulated weight above this counts as "has data".
    private const val COVERAGE_EPS = 1e-2f

    private const val EGL_OPENGL_ES3_BIT = 0x0040

    /** One kept frame's camera solution. [r] is the 3×3 rotation, row-major. */
    data class FrameCam(
        val originalIndex: Int,
        val focal: Double,
        val ppx: Double,
        val ppy: Double,
        val r: DoubleArray,
        val gain: Float,
    )

    /** Compose canvas geometry + per-frame cameras from Rust registration. */
    data class Estimate(
        val canvasW: Int,
        val canvasH: Int,
        val u0: Double,
        val v0: Double,
        val scale: Double,
        val cams: List<FrameCam>,
    )

    /** The composited panorama, cropped to content, plus the full-canvas geometry. */
    data class CompositeResult(
        val bitmap: Bitmap,
        val fullWidth: Int,
        val fullHeight: Int,
        val croppedLeft: Int,
        val croppedTop: Int,
    )

    /**
     * Decode the little-endian blob produced by the Rust `estimate` JNI call.
     * Layout: canvas_w u32, canvas_h u32, u0 f64, v0 f64, scale f64, count u32,
     * then per cam: original_index u32, focal f64, ppx f64, ppy f64, R[9] f64
     * (row-major), gain f32.
     */
    fun parseEstimate(blob: ByteArray): Estimate? {
        return try {
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val canvasW = buf.int
            val canvasH = buf.int
            val u0 = buf.double
            val v0 = buf.double
            val scale = buf.double
            val count = buf.int
            if (count <= 0 || canvasW <= 0 || canvasH <= 0) return null
            val cams = ArrayList<FrameCam>(count)
            repeat(count) {
                val idx = buf.int
                val focal = buf.double
                val ppx = buf.double
                val ppy = buf.double
                val r = DoubleArray(9) { buf.double }
                val gain = buf.float
                cams.add(FrameCam(idx, focal, ppx, ppy, r, gain))
            }
            Estimate(canvasW, canvasH, u0, v0, scale, cams)
        } catch (t: Throwable) {
            Log.e(TAG, "parseEstimate failed", t)
            null
        }
    }

    /**
     * Composite the kept frames on the GPU. [frames] is indexed by the frame's
     * original capture index (matching [FrameCam.originalIndex]). Returns null
     * on any GL/EGL failure or unsupported capability so the caller falls back
     * to the CPU stitcher.
     */
    fun composite(estimate: Estimate, frames: List<ByteArray>): CompositeResult? {
        var gl: GlEnv? = null
        return try {
            gl = GlEnv.create()
            if (gl == null) {
                Log.w(TAG, "EGL/GLES init failed or float FBO unsupported; cannot composite")
                return null
            }
            gl.render(estimate, frames)
        } catch (t: Throwable) {
            Log.e(TAG, "composite failed", t)
            null
        } finally {
            gl?.release()
        }
    }

    /** Forward spherical map for one source pixel (mirrors Rust `Proj::forward`). */
    private fun forward(cam: FrameCam, scale: Double, x: Double, y: Double): DoubleArray {
        // K^-1 * [x,y,1] with K = [[f,0,ppx],[0,f,ppy],[0,0,1]], aspect = 1.
        val a = (x - cam.ppx) / cam.focal
        val b = (y - cam.ppy) / cam.focal
        val c = 1.0
        val r = cam.r
        val px = r[0] * a + r[1] * b + r[2] * c
        val py = r[3] * a + r[4] * b + r[5] * c
        val pz = r[6] * a + r[7] * b + r[8] * c
        val u = scale * atan2(px, pz)
        val denom = sqrt(px * px + py * py + pz * pz)
        val ww = if (denom > 1e-12) (py / denom).coerceIn(-1.0, 1.0) else 0.0
        val v = scale * (Math.PI - acos(ww))
        return doubleArrayOf(u, v)
    }

    /** Encapsulates the EGL context + GL resources for one composite pass. */
    private class GlEnv private constructor(
        private val display: EGLDisplay,
        private val context: EGLContext,
        private val surface: EGLSurface,
    ) {
        private var warpProgram = 0
        private var normProgram = 0
        private var accumTex = 0
        private var accumFbo = 0
        private var outTex = 0
        private var outFbo = 0

        fun render(est: Estimate, frames: List<ByteArray>): CompositeResult? {
            val maxTex = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTex, 0)
            val cap = maxTex[0].coerceAtLeast(2048)
            if (est.canvasW > cap || est.canvasH > cap) {
                Log.w(TAG, "canvas ${est.canvasW}x${est.canvasH} exceeds GL_MAX_TEXTURE_SIZE $cap")
                return null
            }

            // RGBA16F accumulation FBO — needs GL_EXT_color_buffer_float.
            accumTex = genTexture(GLES30.GL_NEAREST)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F,
                est.canvasW, est.canvasH, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null
            )
            accumFbo = genFbo(accumTex)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "RGBA16F FBO incomplete (no color-buffer-float support)")
                return null
            }

            warpProgram = buildProgram(WARP_VS, WARP_FS) ?: return null
            normProgram = buildProgram(NORM_VS, NORM_FS) ?: return null

            // --- accumulate all frames additively ---
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, accumFbo)
            GLES30.glViewport(0, 0, est.canvasW, est.canvasH)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)

            val indices = buildIndexBuffer()
            val indexCount = GRID * GRID * 6
            GLES30.glUseProgram(warpProgram)
            val uGain = GLES30.glGetUniformLocation(warpProgram, "uGain")
            val uFeather = GLES30.glGetUniformLocation(warpProgram, "uFeather")
            val uTex = GLES30.glGetUniformLocation(warpProgram, "uTex")
            val uXOffset = GLES30.glGetUniformLocation(warpProgram, "uXOffset")

            // Seam handling: one full circle in u is 2π·scale px → this much in NDC
            // x. Drawing each frame at {0, +circle, -circle} lets a frame crossing
            // the ±180° meridian appear on both canvas edges; copies that fall
            // outside the viewport are clipped, so a sub-360° pano is unaffected.
            val circleNdc = (2.0 * Math.PI * est.scale / est.canvasW * 2.0).toFloat()
            val xOffsets = floatArrayOf(0f, circleNdc, -circleNdc)

            var drawn = 0
            for (cam in est.cams) {
                val jpeg = frames.getOrNull(cam.originalIndex) ?: continue
                val tex = uploadFrame(jpeg, cap) ?: continue
                try {
                    val verts = buildFrameMesh(est, cam)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                    GLES30.glUniform1i(uTex, 0)
                    GLES30.glUniform1f(uGain, cam.gain)
                    GLES30.glUniform1f(uFeather, FEATHER)

                    verts.position(0)
                    GLES30.glEnableVertexAttribArray(0)
                    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 4 * 4, verts)
                    verts.position(2)
                    GLES30.glEnableVertexAttribArray(1)
                    GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 4 * 4, verts)

                    for (off in xOffsets) {
                        GLES30.glUniform1f(uXOffset, off)
                        indices.position(0)
                        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, indices)
                    }
                    GLES30.glDisableVertexAttribArray(0)
                    GLES30.glDisableVertexAttribArray(1)
                    drawn++
                } finally {
                    val t = intArrayOf(tex)
                    GLES30.glDeleteTextures(1, t, 0)
                }
            }
            GLES20.glDisable(GLES20.GL_BLEND)
            if (drawn == 0) return null

            // --- normalization pass into an RGBA8 output FBO ---
            outTex = genTexture(GLES30.GL_NEAREST)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                est.canvasW, est.canvasH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            outFbo = genFbo(outTex)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "output FBO incomplete")
                return null
            }
            GLES30.glViewport(0, 0, est.canvasW, est.canvasH)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(normProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, accumTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(normProgram, "uAccum"), 0)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(normProgram, "uEps"), COVERAGE_EPS)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

            // --- read back + crop to content ---
            val pixels = ByteBuffer.allocateDirect(est.canvasW * est.canvasH * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, est.canvasW, est.canvasH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)

            val full = Bitmap.createBitmap(est.canvasW, est.canvasH, Bitmap.Config.ARGB_8888)
            pixels.rewind()
            full.copyPixelsFromBuffer(pixels)

            pixels.rewind()
            val rect = contentRect(pixels, est.canvasW, est.canvasH)
            if (rect == null) {
                return CompositeResult(full, est.canvasW, est.canvasH, 0, 0)
            }
            val (rx, ry, rw, rh) = rect
            val cropped = Bitmap.createBitmap(full, rx, ry, rw, rh)
            if (cropped != full) full.recycle()
            return CompositeResult(cropped, est.canvasW, est.canvasH, rx, ry)
        }

        private fun uploadFrame(jpeg: ByteArray, cap: Int): Int? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > cap || bounds.outHeight / sample > cap) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
            // UVs are normalized, so a downsampled texture samples the same content;
            // the mesh's source-pixel coords use full-res intrinsics (ppx=fullW/2).
            val tex = genTexture(GLES30.GL_LINEAR)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            return tex
        }

        /**
         * Build a GRID×GRID warp mesh: interleaved [ndcX, ndcY, u, v] per vertex.
         * Each vertex's `u` is unwrapped relative to the frame center so a frame
         * that straddles the ±180° seam stays horizontally continuous (no torn
         * triangle). The seam wrap itself is handled by drawing the mesh at ±one
         * full-circle X offset (see the draw loop).
         */
        private fun buildFrameMesh(est: Estimate, cam: FrameCam): FloatBuffer {
            val cols = GRID + 1
            val data = FloatArray(cols * cols * 4)
            val period = 2.0 * Math.PI * est.scale // u span of a full 360° circle
            // Reference u at the frame center — vertices unwrap toward this.
            val refU = forward(cam, est.scale, cam.ppx, cam.ppy)[0]
            var p = 0
            for (j in 0..GRID) {
                val b = j.toFloat() / GRID
                for (i in 0..GRID) {
                    val a = i.toFloat() / GRID
                    // Source pixel coords (ppx/ppy assume full-res width; UVs are
                    // normalized so a downsampled texture samples the same content).
                    val sx = a.toDouble() * (cam.ppx * 2.0)
                    val sy = b.toDouble() * (cam.ppy * 2.0)
                    val uv = forward(cam, est.scale, sx, sy)
                    var u = uv[0]
                    if (period > 0.0) u -= period * Math.round((u - refU) / period)
                    val outX = u - est.u0
                    val outY = uv[1] - est.v0
                    val ndcX = (outX / est.canvasW * 2.0 - 1.0).toFloat()
                    val ndcY = (outY / est.canvasH * 2.0 - 1.0).toFloat()
                    data[p++] = ndcX
                    data[p++] = ndcY
                    data[p++] = a
                    data[p++] = b
                }
            }
            return ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(data); position(0) }
        }

        private fun buildIndexBuffer(): ShortBuffer {
            val cols = GRID + 1
            val idx = ShortArray(GRID * GRID * 6)
            var p = 0
            for (j in 0 until GRID) {
                for (i in 0 until GRID) {
                    val a = (j * cols + i).toShort()
                    val bb = (j * cols + i + 1).toShort()
                    val c = ((j + 1) * cols + i).toShort()
                    val d = ((j + 1) * cols + i + 1).toShort()
                    idx[p++] = a; idx[p++] = c; idx[p++] = bb
                    idx[p++] = bb; idx[p++] = c; idx[p++] = d
                }
            }
            return ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder())
                .asShortBuffer().apply { put(idx); position(0) }
        }

        private fun genTexture(filter: Int): Int {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        private fun genFbo(tex: Int): Int {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0
            )
            return ids[0]
        }

        private fun buildProgram(vs: String, fs: String): Int? {
            val v = compileShader(GLES30.GL_VERTEX_SHADER, vs) ?: return null
            val f = compileShader(GLES30.GL_FRAGMENT_SHADER, fs) ?: return null
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, v)
            GLES30.glAttachShader(p, f)
            GLES30.glLinkProgram(p)
            val status = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
            GLES30.glDeleteShader(v)
            GLES30.glDeleteShader(f)
            if (status[0] == 0) {
                Log.e(TAG, "program link failed: ${GLES30.glGetProgramInfoLog(p)}")
                GLES30.glDeleteProgram(p)
                return null
            }
            return p
        }

        private fun compileShader(type: Int, src: String): Int? {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val status = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "shader compile failed: ${GLES30.glGetShaderInfoLog(s)}")
                GLES30.glDeleteShader(s)
                return null
            }
            return s
        }

        fun release() {
            if (accumFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(accumFbo), 0)
            if (outFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(outFbo), 0)
            if (accumTex != 0) GLES30.glDeleteTextures(1, intArrayOf(accumTex), 0)
            if (outTex != 0) GLES30.glDeleteTextures(1, intArrayOf(outTex), 0)
            if (warpProgram != 0) GLES30.glDeleteProgram(warpProgram)
            if (normProgram != 0) GLES30.glDeleteProgram(normProgram)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        companion object {
            /** Bring up a headless ES3 context on a 1×1 pbuffer, or null on failure. */
            fun create(): GlEnv? {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (display == EGL14.EGL_NO_DISPLAY) {
                    Log.w(TAG, "eglGetDisplay: no display")
                    return null
                }
                val ver = IntArray(2)
                if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
                    Log.w(TAG, "eglInitialize failed")
                    return null
                }

                val cfgAttr = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfig = IntArray(1)
                if (!EGL14.eglChooseConfig(display, cfgAttr, 0, configs, 0, 1, numConfig, 0) ||
                    numConfig[0] == 0 || configs[0] == null
                ) {
                    Log.w(TAG, "eglChooseConfig failed (no ES3 pbuffer config)")
                    EGL14.eglTerminate(display)
                    return null
                }
                val config = configs[0]!!

                val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
                val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
                if (context == EGL14.EGL_NO_CONTEXT) {
                    Log.w(TAG, "eglCreateContext failed")
                    EGL14.eglTerminate(display)
                    return null
                }
                val surfAttr = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
                val surface = EGL14.eglCreatePbufferSurface(display, config, surfAttr, 0)
                if (surface == EGL14.EGL_NO_SURFACE) {
                    Log.w(TAG, "eglCreatePbufferSurface failed")
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                    return null
                }
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    Log.w(TAG, "eglMakeCurrent failed")
                    EGL14.eglDestroySurface(display, surface)
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                    return null
                }
                // Require float color-buffer support up front (FBO completeness
                // is re-checked at render time as a backstop).
                if (!hasColorBufferFloat()) {
                    Log.w(TAG, "no GL_EXT_color_buffer_float; falling back to CPU")
                    val env = GlEnv(display, context, surface)
                    env.release()
                    return null
                }
                return GlEnv(display, context, surface)
            }

            /** ES3-safe extension query (the monolithic GL_EXTENSIONS string may be empty). */
            private fun hasColorBufferFloat(): Boolean {
                val legacy = GLES30.glGetString(GLES30.GL_EXTENSIONS)
                if (legacy != null && (legacy.contains("GL_EXT_color_buffer_float") ||
                        legacy.contains("GL_EXT_color_buffer_half_float"))
                ) {
                    return true
                }
                val count = IntArray(1)
                GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, count, 0)
                for (i in 0 until count[0]) {
                    val e = GLES30.glGetStringi(GLES30.GL_EXTENSIONS, i) ?: continue
                    if (e == "GL_EXT_color_buffer_float" || e == "GL_EXT_color_buffer_half_float") {
                        return true
                    }
                }
                return false
            }
        }
    }

    /**
     * Maximal all-opaque (alpha == 255) rectangle in the RGBA byte buffer — a
     * Kotlin port of the Rust `content_rect` histogram/stack max-rectangle. The
     * buffer is top-down (matching the readback), so the rect is in bitmap
     * coordinates directly. Returns (x, y, w, h) or null if nothing is covered.
     */
    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun contentRect(pixels: ByteBuffer, w: Int, h: Int): Rect? {
        val heights = IntArray(w)
        var best: Rect? = null
        var bestArea = 0
        val stack = IntArray(w + 1)
        for (y in 0 until h) {
            val rowBase = y * w * 4
            for (x in 0 until w) {
                val alpha = pixels.get(rowBase + x * 4 + 3).toInt() and 0xFF
                heights[x] = if (alpha == 255) heights[x] + 1 else 0
            }
            var sp = 0
            var x = 0
            while (x <= w) {
                val cur = if (x == w) 0 else heights[x]
                if (sp == 0 || cur >= heights[stack[sp - 1]]) {
                    stack[sp++] = x
                    x++
                } else {
                    val top = stack[--sp]
                    val height = heights[top]
                    val left = if (sp == 0) 0 else stack[sp - 1] + 1
                    val width = x - left
                    val area = height * width
                    if (area > bestArea) {
                        bestArea = area
                        best = Rect(left, y + 1 - height, width, height)
                    }
                }
            }
        }
        return if (best != null && best.w > 0 && best.h > 0) best else null
    }

    // --- shaders (ES 3.10) ---

    private const val WARP_VS = """#version 310 es
        layout(location = 0) in vec2 aPos;
        layout(location = 1) in vec2 aUv;
        uniform float uXOffset;
        out vec2 vUv;
        void main() {
            vUv = aUv;
            gl_Position = vec4(aPos.x + uXOffset, aPos.y, 0.0, 1.0);
        }
    """

    private const val WARP_FS = """#version 310 es
        precision highp float;
        uniform sampler2D uTex;
        uniform float uGain;
        uniform float uFeather;
        in vec2 vUv;
        out vec4 frag;
        void main() {
            vec4 c = texture(uTex, vUv);
            float dx = min(vUv.x, 1.0 - vUv.x);
            float dy = min(vUv.y, 1.0 - vUv.y);
            float d = min(dx, dy);
            float w = smoothstep(0.0, uFeather, d);
            frag = vec4(c.rgb * w * uGain, w);
        }
    """

    private const val NORM_VS = """#version 310 es
        out vec2 vUv;
        void main() {
            vec2 p = vec2(float((gl_VertexID & 1) << 2) - 1.0,
                          float((gl_VertexID & 2) << 1) - 1.0);
            vUv = (p + 1.0) * 0.5;
            gl_Position = vec4(p, 0.0, 1.0);
        }
    """

    private const val NORM_FS = """#version 310 es
        precision highp float;
        uniform sampler2D uAccum;
        uniform float uEps;
        in vec2 vUv;
        out vec4 frag;
        void main() {
            vec4 a = texture(uAccum, vUv);
            float cov = a.a > uEps ? 1.0 : 0.0;
            float wsum = max(a.a, 1e-4);
            frag = vec4(a.rgb / wsum, cov);
        }
    """
}
