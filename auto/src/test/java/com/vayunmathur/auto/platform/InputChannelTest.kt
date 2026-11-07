package com.vayunmathur.auto.platform

import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.InputCodec
import com.vayunmathur.auto.protocol.InputKey
import com.vayunmathur.auto.protocol.gal.InputSourceService
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.TouchConfig
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Auto module: [InputChannel] — the head-unit input source. Binding echoes
 * the discovery keycodes; reports inject only with input focus and a car
 * display; volume keys are consumed, never injected; traffic before binding
 * or for another channel is dropped, never misparsed.
 *
 * The channel only needs `GalConnection.send(channelId, type, payload)`; a
 * real connection closed before use drops sends silently (see
 * `GalConnection.close`), so these tests drive the event/sink paths without
 * a TLS handshake or a socket.
 */
@RunWith(RobolectricTestRunner::class)
class InputChannelTest {

    private class FakeTransport : GalTransport {
        val written = ByteArrayOutputStream()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            written.write(bytes, offset, length)
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    /** A real connection, closed: sends drop silently, events still fire. */
    private fun connection(): GalConnection {
        val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        return GalConnection(FakeTransport(), context, deviceModel = "Test").also { it.close() }
    }

    private fun service(
        keycodes: List<Int> = listOf(19, 20),
        feedback: List<Int> = emptyList(),
    ): Service = Service.newBuilder()
        .setId(8)
        .setInputSource(
            InputSourceService.newBuilder()
                .addAllKeycodesSupported(keycodes)
                .addAllFeedbackEventsSupported(feedback)
                .addTouchscreen(
                    TouchConfig.newBuilder().setWidth(800).setHeight(480).build(),
                ),
        )
        .build()

    @Test
    fun `channel id comes from discovery`() {
        val channel = InputChannel(service(), connection())
        assertEquals(8, channel.channelId)
    }

    @Test
    fun `traffic for another channel is ignored`() {
        val events = mutableListOf<InputEvent>()
        val channel = InputChannel(service(), connection(), onEvent = { events += it })
        channel.onMessage(99, GalMessage.Input.REPORT, InputCodec.buildReport())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `traffic before binding drops without focus`() {
        val events = mutableListOf<InputEvent>()
        val channel = InputChannel(service(), connection(), onEvent = { events += it })
        channel.onMessage(8, GalMessage.Input.REPORT, InputCodec.buildReport())
        assertEquals(listOf<InputEvent>(InputEvent.DroppedNoFocus), events)
    }

    @Test
    fun `binding response before binding drops without focus`() {
        val events = mutableListOf<InputEvent>()
        val channel = InputChannel(service(), connection(), onEvent = { events += it })
        channel.onMessage(8, GalMessage.Input.KEY_BINDING_RESPONSE, byteArrayOf(0x08, 0x00))
        assertEquals(listOf<InputEvent>(InputEvent.DroppedNoFocus), events)
    }

    @Test
    fun `key report without input focus drops`() {
        val events = mutableListOf<InputEvent>()
        val channel = InputChannel(
            service(), connection(),
            isInputAllowed = { false },
            displaySize = { 800 to 480 },
            onEvent = { events += it },
            keySink = { error("must not inject without focus") },
        )
        channel.requestBinding()
        assertEquals(
            listOf(InputEvent.BindingRequested(2)),
            events.filterIsInstance<InputEvent.BindingRequested>(),
        )
        channel.onMessage(8, GalMessage.Input.REPORT, InputCodec.buildKeyReport(0, 19 to true))
        assertTrue(events.contains(InputEvent.DroppedNoFocus))
    }

    @Test
    fun `key report without a display drops`() {
        val events = mutableListOf<InputEvent>()
        val channel = InputChannel(
            service(), connection(),
            isInputAllowed = { true },
            displaySize = { null },
            onEvent = { events += it },
            keySink = { error("must not inject without a display") },
        )
        channel.requestBinding()
        channel.onMessage(8, GalMessage.Input.REPORT, InputCodec.buildKeyReport(0, 19 to true))
        assertTrue(events.contains(InputEvent.DroppedNoFocus))
    }

    @Test
    fun `key press injects and volume keys are consumed`() {
        val events = mutableListOf<InputEvent>()
        val injected = mutableListOf<InputKey>()
        val channel = InputChannel(
            service(), connection(),
            isInputAllowed = { true },
            displaySize = { 800 to 480 },
            onEvent = { events += it },
            keySink = { injected += it; true },
        )
        channel.requestBinding()
        channel.onMessage(8, GalMessage.Input.REPORT, InputCodec.buildKeyReport(0, 19 to true))
        assertEquals(listOf(InputKey(19, true)), injected)
        assertTrue(events.filterIsInstance<InputEvent.Key>().isNotEmpty())
        // Volume up is consumed (observed), never forwarded to the sink.
        channel.onMessage(8, GalMessage.Input.REPORT, InputCodec.buildKeyReport(0, 24 to true))
        assertEquals(listOf(InputKey(19, true)), injected)
        assertTrue(events.filterIsInstance<InputEvent.VolumeKey>().isNotEmpty())
    }

    @Test
    fun `touch report scales to the car display and injects`() {
        val injected = mutableListOf<ScaledTouch>()
        val channel = InputChannel(
            service(), connection(),
            isInputAllowed = { true },
            displaySize = { 800 to 480 },
            touchSink = { injected += it; true },
        )
        channel.requestBinding()
        channel.onMessage(8, GalMessage.Input.REPORT, InputCodec.buildReport())
        assertEquals(1, injected.size)
        assertEquals(1, injected.single().pointers.size)
    }

    @Test
    fun `unknown message types are observed and ignored`() {
        val events = mutableListOf<InputEvent>()
        val channel = InputChannel(
            service(), connection(),
            isInputAllowed = { true },
            displaySize = { 800 to 480 },
            onEvent = { events += it },
        )
        channel.requestBinding()
        val before = events.size
        channel.onMessage(8, 0x1234, byteArrayOf(1, 2, 3))
        assertEquals(before, events.size)
    }

    @Test
    fun `scroll report injects its delta`() {
        val events = mutableListOf<InputEvent>()
        val deltas = mutableListOf<Int>()
        val channel = InputChannel(
            service(), connection(),
            isInputAllowed = { true },
            displaySize = { 800 to 480 },
            onEvent = { events += it },
            scrollSink = { deltas += it; true },
        )
        channel.requestBinding()
        channel.onMessage(8, GalMessage.Input.REPORT, InputCodec.buildScrollReport(delta = 3))
        assertEquals(listOf(3), deltas)
        assertEquals(listOf<InputEvent>(InputEvent.Scroll(3)), events.filterIsInstance<InputEvent.Scroll>())
    }

    @Test
    fun `tap-as-select presses dpad center`() {
        val injected = mutableListOf<InputKey>()
        val channel = InputChannel(
            service(), connection(),
            isInputAllowed = { true },
            displaySize = { 800 to 480 },
            keySink = { injected += it; true },
        )
        channel.requestBinding()
        channel.onMessage(
            8, GalMessage.Input.REPORT,
            InputCodec.buildAbsoluteReport(keycode = InputCodec.TAP_SELECT_KEYCODE, value = 1),
        )
        assertEquals(listOf(InputKey(InputCodec.KEYCODE_DPAD_CENTER, true)), injected)
    }

    @Test
    fun `key binding answer is observed with its status`() {
        val events = mutableListOf<InputEvent>()
        val channel = InputChannel(service(), connection(), onEvent = { events += it })
        channel.requestBinding()
        channel.onMessage(8, GalMessage.Input.KEY_BINDING_RESPONSE, byteArrayOf(0x08, 0x00))
        assertEquals(
            listOf(InputEvent.BindingAnswered(0)),
            events.filterIsInstance<InputEvent.BindingAnswered>(),
        )
    }

    @Test
    fun `focus flap parks and re-binds`() {
        val events = mutableListOf<InputEvent>()
        val channel = InputChannel(service(), connection(), onEvent = { events += it })
        // No binding yet: focus changes are no-ops, never sends.
        channel.onInputFocusChanged(false)
        channel.onInputFocusChanged(true)
        assertTrue(events.isEmpty())
        channel.requestBinding()
        val binds = events.filterIsInstance<InputEvent.BindingRequested>().size
        channel.onInputFocusChanged(false)
        channel.onInputFocusChanged(true)
        assertEquals(
            binds + 1,
            events.filterIsInstance<InputEvent.BindingRequested>().size,
        )
    }

    @Test
    fun `feedback before binding is a silent no-op`() {
        val events = mutableListOf<InputEvent>()
        val channel = InputChannel(service(), connection(), onEvent = { events += it })
        channel.sendFeedback(5)
        assertTrue(events.isEmpty())
    }
}
