package com.vayunmathur.photos.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.vayunmathur.photos.data.PanoData
import com.vayunmathur.photos.data.Photo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Interactive 360 viewer: renders an equirectangular photo onto the inside of a
 * UV sphere (OpenGL ES 2.0) and lets the user look around by dragging, pinching
 * to zoom, and tilting the phone (gyroscope). Partial panoramas map only their
 * covered band using [PanoData]; the rest of the sphere stays black.
 */
private class PanoramaSphereGLView(
    context: Context,
    uri: Uri,
    panoData: PanoData,
    private val onTap: () -> Unit,
) : GLSurfaceView(context), SensorEventListener {

    private val renderer = SphereRenderer(context, uri, panoData)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val scaleDetector: ScaleGestureDetector

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Pinch out (scaleFactor > 1) zooms in → narrower FOV.
                renderer.fov = (renderer.fov / detector.scaleFactor).coerceIn(MIN_FOV, MAX_FOV)
                return true
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onDetachedFromWindow() {
        sensorManager.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    // Scale drag by FOV so the feel is consistent across zoom levels.
                    val speed = DRAG_SPEED * (renderer.fov / DEFAULT_FOV)
                    renderer.touchYaw -= dx * speed
                    renderer.touchPitch = (renderer.touchPitch - dy * speed).coerceIn(-90f, 90f)
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                val moved = kotlin.math.hypot(event.x - downX, event.y - downY)
                if (moved < TAP_SLOP && !scaleDetector.isInProgress) onTap()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            renderer.updateDeviceRotation(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val DRAG_SPEED = 0.15f
        private const val DEFAULT_FOV = 75f
        private const val MIN_FOV = 30f
        private const val MAX_FOV = 100f
        private const val TAP_SLOP = 20f
    }
}

private class SphereRenderer(
    private val context: Context,
    private val uri: Uri,
    private val pano: PanoData,
) : GLSurfaceView.Renderer {

    @Volatile var touchYaw = 0f
    @Volatile var touchPitch = 0f
    @Volatile var fov = 75f

    private val deviceRotation = FloatArray(16)
    @Volatile private var haveDeviceRotation = false

    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texBuffer: FloatBuffer
    private lateinit var indexBuffer: ShortBuffer
    private var indexCount = 0

    private var program = 0
    private var textureId = 0
    private var aPos = 0
    private var aTex = 0
    private var uMvp = 0
    private var uTex = 0

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private var aspect = 1f

    fun updateDeviceRotation(rotationVector: FloatArray) {
        val r = FloatArray(16)
        SensorManager.getRotationMatrixFromVector(r, rotationVector)
        // Remap so holding the phone up in portrait points the camera into the
        // scene, then invert (transpose of an orthonormal matrix) to get a
        // world→camera view matrix.
        val remapped = FloatArray(16)
        SensorManager.remapCoordinateSystem(
            r, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped
        )
        Matrix.transposeM(deviceRotation, 0, remapped, 0)
        haveDeviceRotation = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE) // viewed from inside the sphere
        buildMesh()
        program = buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        textureId = loadTexture()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        Matrix.perspectiveM(projection, 0, fov, aspect, 0.1f, 10f)

        Matrix.setIdentityM(view, 0)
        Matrix.rotateM(view, 0, touchPitch, 1f, 0f, 0f)
        Matrix.rotateM(view, 0, touchYaw, 0f, 1f, 0f)
        if (haveDeviceRotation) {
            Matrix.multiplyMM(view, 0, view, 0, deviceRotation, 0)
        }

        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTex, 0)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun buildMesh() {
        val positions = ArrayList<Float>()
        val texCoords = ArrayList<Float>()
        val indices = ArrayList<Short>()

        val fullW = pano.fullWidth.toFloat().coerceAtLeast(1f)
        val fullH = pano.fullHeight.toFloat().coerceAtLeast(1f)
        val cropW = pano.croppedWidth.toFloat().coerceAtLeast(1f)
        val cropH = pano.croppedHeight.toFloat().coerceAtLeast(1f)

        for (stack in 0..STACKS) {
            val vFull = stack.toFloat() / STACKS // 0 at top (+90 lat)
            val phi = Math.PI / 2.0 - vFull * Math.PI
            val cosPhi = cos(phi)
            val sinPhi = sin(phi)
            for (slice in 0..SLICES) {
                val uFull = slice.toFloat() / SLICES
                val theta = uFull * 2.0 * Math.PI
                val x = (cosPhi * sin(theta)).toFloat()
                val y = sinPhi.toFloat()
                val z = (cosPhi * cos(theta)).toFloat()
                positions.add(x); positions.add(y); positions.add(z)

                // Map full-sphere UV into the stored (cropped) texture. Values
                // outside [0,1] fall outside the covered band → black in shader.
                val texU = (uFull * fullW - pano.croppedLeft) / cropW
                val texV = (vFull * fullH - pano.croppedTop) / cropH
                texCoords.add(texU); texCoords.add(texV)
            }
        }

        val cols = SLICES + 1
        for (stack in 0 until STACKS) {
            for (slice in 0 until SLICES) {
                val a = (stack * cols + slice).toShort()
                val b = (stack * cols + slice + 1).toShort()
                val c = ((stack + 1) * cols + slice).toShort()
                val d = ((stack + 1) * cols + slice + 1).toShort()
                indices.add(a); indices.add(c); indices.add(b)
                indices.add(b); indices.add(c); indices.add(d)
            }
        }

        vertexBuffer = positions.toFloatBuffer()
        texBuffer = texCoords.toFloatBuffer()
        indexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
                indices.forEach { put(it) }
                position(0)
            }
        indexCount = indices.size
    }

    private fun loadTexture(): Int {
        val maxTex = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
        val cap = maxTex[0].coerceAtLeast(2048)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > cap || bounds.outHeight / sample > cap) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return 0

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return id
    }

    private fun buildProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return p
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun List<Float>.toFloatBuffer(): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            forEach { put(it) }
            position(0)
        }

    companion object {
        private const val STACKS = 64
        private const val SLICES = 64

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = uMvp * aPos;
                vTex = aTex;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vTex;
            void main() {
                if (vTex.x < 0.0 || vTex.x > 1.0 || vTex.y < 0.0 || vTex.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTex, vTex);
                }
            }
        """
    }
}

/**
 * Compose wrapper around the 360 sphere [GLSurfaceView]. Forwards resume/pause
 * to the GL surface via [DisposableEffect]. [onTap] fires on a tap (used to
 * toggle the metadata overlay).
 */
@Composable
fun PanoramaSphereView(
    photo: Photo,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val pano = photo.panoData ?: return
    val uri = photo.uri.toUri()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val glView = androidx.compose.runtime.remember(uri) {
        PanoramaSphereGLView(context, uri, pano, onTap)
    }

    DisposableEffect(lifecycleOwner, glView) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> glView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> glView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glView.onPause()
        }
    }

    AndroidView(modifier = modifier, factory = { glView })
}
