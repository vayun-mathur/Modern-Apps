package com.vayunmathur.library.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Pure camera/projection/gesture-state coverage the compose-driven suites
 * do not reach: CameraState animations and saver, Projection construction
 * and globe bounds, and the two-finger TiltTracker state machine.
 */
class MapDeepTest {

    // ---- CameraState animations ----

    private fun runWithFrameClock(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        kotlinx.coroutines.runBlocking {
            val clock = androidx.compose.runtime.BroadcastFrameClock()
            kotlinx.coroutines.withContext(clock) {
                kotlinx.coroutines.coroutineScope {
                    val pump = launch {
                        var t = 0L
                        while (isActive) {
                            clock.sendFrame(t)
                            t += 16_000_000L
                            kotlinx.coroutines.delay(5)
                        }
                    }
                    try {
                        block()
                    } finally {
                        pump.cancel()
                    }
                }
            }
        }
    }

    @Test fun animateToMovesCameraToTarget() {
        val camera = CameraState(CameraPosition(GeoPoint(0.0, 0.0), 5.0))
        camera.setViewport(Size(400f, 800f))
        runWithFrameClock {
            camera.animateTo(CameraPosition(GeoPoint(10.0, 20.0), 12.0, bearing = 45.0, pitch = 30.0), durationMs = 100)
        }
        val pos = camera.position
        assertEquals(10.0, pos.target.longitude, 1e-6)
        assertEquals(20.0, pos.target.latitude, 1e-6)
        assertEquals(12.0, pos.zoom, 1e-6)
    }

    @Test fun animateZoomByChangesZoomAroundAnchor() {
        val camera = CameraState(CameraPosition(GeoPoint(0.0, 0.0), 10.0))
        camera.setViewport(Size(400f, 800f))
        val before = camera.position.zoom
        runWithFrameClock {
            camera.animateZoomBy(1.0, Offset(200f, 400f), 2.0, 20.0, durationMs = 100)
        }
        assertTrue(camera.position.zoom > before, "zoom-in should raise zoom: ${camera.position.zoom} vs $before")
    }

    @Test fun cameraStateSaverRoundTripsPosition() {
        val saverClass = Class.forName("com.vayunmathur.library.map.CameraStateKt")
        val saverField = saverClass.getDeclaredField("CameraStateSaver").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val saver = saverField.get(null) as androidx.compose.runtime.saveable.Saver<CameraState, List<Double>>
        val original = CameraState(CameraPosition(GeoPoint(-122.4, 37.8), 14.0, bearing = 90.0, pitch = 45.0))
        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(saver) { scope.save(original) }
        assertNotNull(saved)
        assertEquals(5, saved.size)
        val restored = saver.restore(saved)
        assertNotNull(restored)
        assertEquals(14.0, restored!!.position.zoom, 1e-9)
        assertEquals(-122.4, restored.position.target.longitude, 1e-9)
        assertEquals(90.0, restored.position.bearing, 1e-9)
    }

    // ---- Projection ----

    @Test fun projectionRoundTripsAndGlobeBounds() {
        val proj = Projection(
            center = GeoPoint(0.0, 0.0), zoom = 8.0,
            widthDp = 400f, heightDp = 400f,
            pitchDeg = 0.0, bearingDeg = 0.0,
            labelQuery = null, markerPick = null,
            globe = true,
        )
        val screen = proj.screenLocationFromPosition(GeoPoint(0.0, 0.0))
        assertEquals(200f, screen.x.value, 1f)
        assertEquals(200f, screen.y.value, 1f)
        val boundsMethod = Projection::class.java.getDeclaredMethod("globeVisibleBounds")
            .apply { isAccessible = true }
        val bounds = boundsMethod.invoke(proj) as GeoBounds
        assertTrue(bounds.south < bounds.north, "bounds: $bounds")
        assertTrue(bounds.south >= -90.0 && bounds.north <= 90.0)
        val flat = Projection(
            center = GeoPoint(-122.4, 37.8), zoom = 12.0,
            widthDp = 800f, heightDp = 600f,
            pitchDeg = 45.0, bearingDeg = 30.0,
            labelQuery = null, markerPick = null,
            globe = false,
        )
        val centre = flat.positionFromScreenLocation(DpOffset(400.dp, 300.dp))
        assertEquals(-122.4, centre.longitude, 0.01)
        assertEquals(37.8, centre.latitude, 0.01)
        assertEquals(emptyList(), flat.queryRenderedLabels(
            androidx.compose.ui.unit.DpRect(0.dp, 0.dp, 10.dp, 10.dp), setOf("poi"),
        ))
        assertEquals(0L, flat.pickMarker(10f, 10f))
    }

    // ---- TiltTracker (private, via reflection with real pointer changes) ----

    private fun change(id: Long, x: Float, y: Float, prevX: Float, prevY: Float): PointerInputChange {
        // PointerInputChange's constructor is internal in Compose, and the
        // library's own PointerInputTestUtilKt helpers (down/moveTo) are
        // Kotlin-internal too, so neither is callable from this module; the
        // change is built reflectively against the exact current signature.
        //
        // Current signature (androidx.compose.ui:ui-android 1.12.1, the
        // version BOM 2026.09.00 resolves; verified with javap on ui.aar):
        //   private PointerInputChange(long id, long uptimeMillis,
        //       long position, boolean pressed, float pressure,
        //       long previousUptimeMillis, long previousPosition,
        //       boolean previousPressed, boolean downOrPositionChange,
        //       int type, List<HistoricalChange> historical,
        //       long scrollDelta, float scaleFactor, long panOffset,
        //       long originalEventPosition)
        // Offset/PointerId parameters are packed longs at the JVM level and
        // `type` is the packed int of the PointerType value class.
        //
        // Why the old code broke when foundation moved to BOM 1.12.1
        // (c47150ff7 dropped the foundation 1.13.0-beta01 override, so ui
        // fell back from 1.13.0-beta01 to 1.12.1): it unwrapped value
        // classes by taking their single primitive declared field. In
        // 1.13.0-beta01 PointerType has only its instance `value` int, so
        // that worked; in 1.12.1 PointerType also declares the static int
        // constants Unknown/Touch/Mouse/Stylus/Eraser on the class itself,
        // so there are six primitive fields, the "single field" lookup
        // returned null, and the boxed PointerType object was passed for
        // the constructor's `int type` parameter — Constructor.newInstance
        // then threw IllegalArgumentException "argument type mismatch".
        // Below, `type` is read from the named instance `value` field.
        val ctor = PointerInputChange::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            List::class.java, Long::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val typeValue = PointerType::class.java.getDeclaredField("value")
            .apply { isAccessible = true }
            .getInt(PointerType.Touch)
        return ctor.newInstance(
            id, 100L, Offset(x, y).packedValue, true, 1f,
            50L, Offset(prevX, prevY).packedValue, true, false,
            typeValue, emptyList<Any>(), Offset.Zero.packedValue, 1f,
            Offset.Zero.packedValue, Offset(x, y).packedValue,
        ) as PointerInputChange
    }

    @Test fun quickZoomDragSlopGate() {
        val dragClass = Class.forName("com.vayunmathur.library.map.QuickZoomDrag")
        val ctor = dragClass.getDeclaredConstructor(Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val drag = ctor.newInstance(24f)
        val advance = dragClass.getDeclaredMethod("advance", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val start = dragClass.getDeclaredMethod("start", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val startedField = dragClass.getDeclaredField("started").apply { isAccessible = true }
        val originField = dragClass.getDeclaredField("origin").apply { isAccessible = true }

        assertNull(advance.invoke(drag, 10f) as Float?)
        assertNull(advance.invoke(drag, -10f) as Float?)
        assertEquals(30f, advance.invoke(drag, 30f) as Float?)
        start.invoke(drag, 30f)
        assertEquals(true, startedField.getBoolean(drag))
        assertEquals(6f, originField.getFloat(drag))
        // Once started, every drag reports, however small.
        assertEquals(2f, advance.invoke(drag, 2f) as Float?)
        assertEquals(-40f, advance.invoke(drag, -40f) as Float?)
    }

    @Test fun tiltTrackerRequiresParallelVerticalMotionPastSlop() {
        val trackerClass = Class.forName("com.vayunmathur.library.map.TiltTracker")
        val ctor = trackerClass.getDeclaredConstructor(Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val tracker = ctor.newInstance(12f)
        val advance = trackerClass.getDeclaredMethod(
            "advance", PointerInputChange::class.java, PointerInputChange::class.java,
        ).apply { isAccessible = true }

        fun adv(f: PointerInputChange, s: PointerInputChange): Float? =
            advance.invoke(tracker, f, s) as Float?

        // First frame only registers the fingers.
        assertNull(adv(change(1, 100f, 100f, 100f, 100f), change(2, 200f, 100f, 200f, 100f)))
        // Small parallel move is still within slop.
        assertNull(adv(change(1, 100f, 105f, 100f, 100f), change(2, 200f, 105f, 200f, 100f)))
        // More parallel downward motion crosses the slop and reports the delta.
        val delta = adv(change(1, 100f, 120f, 100f, 105f), change(2, 200f, 118f, 200f, 105f))
        assertNotNull(delta)
        assertTrue(delta > 0f)
        // Opposing vertical motion (a pinch) claims nothing.
        assertNull(adv(change(1, 100f, 140f, 100f, 120f), change(2, 200f, 100f, 200f, 118f)))
        // Mostly-horizontal motion claims nothing either.
        assertNull(adv(change(1, 160f, 145f, 100f, 140f), change(2, 260f, 105f, 200f, 100f)))

        val reset = trackerClass.getDeclaredMethod("reset").apply { isAccessible = true }
        reset.invoke(tracker)
        assertNull(adv(change(1, 100f, 100f, 100f, 100f), change(2, 200f, 100f, 200f, 100f)))
    }
}
