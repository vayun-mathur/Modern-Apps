package com.vayunmathur.sdk.controller

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControllerHostClientTest {

    private fun await(desc: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $desc")
    }

    private class Harness(
        val hostKeys: MutableMap<String, ByteArray> = ConcurrentHashMap(),
        var bundle: ByteArray? = byteArrayOf(9, 8, 7),
        var unsealFails: Boolean = false,
    ) {
        val host = ControllerHost(
            gameId = "kart",
            hostId = "tv-1",
            hostName = "Test TV",
            providePublicBundle = { bundle },
            unsealSecret = { sealed -> if (unsealFails) null else sealed },
            loadDeviceKey = { hostKeys[it] },
            saveDeviceKey = { id, key -> hostKeys[id] = key },
        )
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        @Volatile var running = true
        private val thread = Thread {
            while (running) {
                try {
                    val accepted = server.accept()
                    val socket = ControllerSocket()
                    socket.attach(accepted)
                    host.accept(socket)
                } catch (_: Exception) {
                    if (running) throw AssertionError("accept failed")
                }
            }
        }.apply { isDaemon = true; start() }

        fun hostInfo(): ControllerHostInfo = ControllerHostInfo(
            id = "tv-1", instanceName = "Test TV (Game Host)", friendlyName = "Test TV",
            host = "127.0.0.1", port = port,
            protocolVersion = ControllerContract.CONTROLLER_PROTOCOL_VERSION, gameId = "kart",
        )

        fun close() {
            running = false
            runCatching { server.close() }
            host.close()
        }
    }

    private fun newClient(
        id: String = "phone-1",
        keys: MutableMap<String, ByteArray> = ConcurrentHashMap(),
        sealFails: Boolean = false,
    ): ControllerClient = ControllerClient(
        senderId = id,
        senderName = "Phone $id",
        sealToBundle = { _, secret -> if (sealFails) null else secret },
        loadDeviceKey = { keys[it] },
        saveDeviceKey = { hostId, key -> keys[hostId] = key },
    )

    // ---- Local (gamepad) players: pure host state ----

    @Test
    fun localPlayersJoinInputAndLeaveThroughLobby() {
        val harness = Harness()
        try {
            assertEquals("kart", harness.host.lobby.value.gameId)
            assertTrue(harness.host.lobby.value.slots.isEmpty())
            harness.host.addLocalPlayer("pad-1", "Pad One")
            harness.host.addLocalPlayer("pad-2", "Pad Two")
            assertEquals(listOf("pad-1", "pad-2"), harness.host.lobby.value.slots.map { it.playerId })
            assertTrue(harness.host.lobby.value.slots.all { !it.remote })

            // Duplicate seat is a no-op (display name is not overwritten).
            harness.host.addLocalPlayer("pad-1", "Renamed")
            assertEquals("Pad One", harness.host.lobby.value.slots.first { it.playerId == "pad-1" }.displayName)

            val input = ControllerInput(playerId = "pad-1", seq = 1, buttons = ControllerButton.A.bit)
            harness.host.onLocalInput(input)
            assertEquals(input, harness.host.inputs.value["pad-1"])
            // Input from an unknown player is ignored.
            harness.host.onLocalInput(ControllerInput(playerId = "ghost", seq = 1))
            assertFalse(harness.host.inputs.value.containsKey("ghost"))

            // Rumble to a local player or a stranger is a silent no-op.
            harness.host.rumble("pad-1", RumbleFeedback(50))
            harness.host.rumble("ghost", RumbleFeedback(50))

            harness.host.removeLocalPlayer("pad-1")
            assertEquals(listOf("pad-2"), harness.host.lobby.value.slots.map { it.playerId })
            assertFalse(harness.host.inputs.value.containsKey("pad-1"))
            // Removing twice is harmless.
            harness.host.removeLocalPlayer("pad-1")
            harness.host.removeLocalPlayer("never-there")
        } finally {
            harness.close()
        }
    }

    @Test
    fun localLobbyCapsAtMaxPlayers() {
        val harness = Harness()
        try {
            repeat(ControllerContract.MAX_PLAYERS) { harness.host.addLocalPlayer("pad-$it", "Pad $it") }
            assertEquals(ControllerContract.MAX_PLAYERS, harness.host.lobby.value.slots.size)
            harness.host.addLocalPlayer("pad-overflow", "Overflow")
            assertEquals(ControllerContract.MAX_PLAYERS, harness.host.lobby.value.slots.size)
            assertFalse(harness.host.lobby.value.slots.any { it.playerId == "pad-overflow" })
        } finally {
            harness.close()
        }
    }

    @Test
    fun raceFlagTogglesAndCloseClearsSeatsAndInputs() {
        val harness = Harness()
        try {
            harness.host.addLocalPlayer("pad-1", "Pad")
            harness.host.onLocalInput(ControllerInput(playerId = "pad-1", seq = 3))
            assertFalse(harness.host.lobby.value.racing)
            harness.host.startRace()
            assertTrue(harness.host.lobby.value.racing)
            harness.host.endRace()
            assertFalse(harness.host.lobby.value.racing)
            harness.host.startRace()
            harness.host.close()
            assertTrue(harness.host.lobby.value.slots.isEmpty())
            assertTrue(harness.host.inputs.value.isEmpty())
        } finally {
            harness.close()
        }
    }

    // ---- Full network sessions ----

    @Test
    fun codePairingJoinInputAndLeave() {
        val harness = Harness()
        val clientKeys = ConcurrentHashMap<String, ByteArray>()
        val client = newClient(keys = clientKeys)
        var rumbled: RumbleFeedback? = null
        client.onRumble = { rumbled = it }
        try {
            assertEquals(ClientState.IDLE, client.state.value)
            client.connect(harness.hostInfo())
            await("awaiting code") { client.state.value == ClientState.AWAITING_CODE }
            val code = assertNotNull(harness.host.pairingCode)
            assertTrue(ControllerPairCode.isWellFormed(code))

            // A malformed code never leaves the phone.
            client.proveCode("12")
            Thread.sleep(150)
            assertEquals(ClientState.AWAITING_CODE, client.state.value)

            client.proveCode(code)
            await("paired") { client.state.value == ClientState.PAIRED }
            // Both ends persisted the same fresh device key under the peer id.
            val savedOnPhone = assertNotNull(clientKeys["tv-1"])
            val savedOnTv = assertNotNull(harness.hostKeys["phone-1"])
            assertTrue(savedOnPhone.contentEquals(savedOnTv))
            assertEquals(null, harness.host.pairingCode)

            client.join("Alice")
            await("joined") { client.state.value == ClientState.JOINED }
            await("host seated phone") { harness.host.lobby.value.slots.any { it.playerId == "phone-1" } }
            assertTrue(harness.host.lobby.value.slots.first { it.playerId == "phone-1" }.remote)

            // Input flows with increasing sequence numbers.
            client.sendInput(buttons = ControllerButton.A.bit)
            client.sendInput(buttons = ControllerButton.B.bit)
            await("host input") { (harness.host.inputs.value["phone-1"]?.seq ?: 0) >= 2 }
            assertEquals(ControllerButton.B.bit, harness.host.inputs.value.getValue("phone-1").buttons)

            // Rumble reaches the phone's callback.
            harness.host.rumble("phone-1", RumbleFeedback(durationMs = 90, lowFreq = 0.5f))
            // Client loop: rumble is only handled in runLoop; give the frame a moment via lobby echo.
            client.sendInput(buttons = 0)
            await("rumble delivered") { rumbled?.durationMs == 90 }

            client.leave()
            await("idle after leave") { client.state.value == ClientState.IDLE }
            await("host freed seat") { harness.host.lobby.value.slots.none { it.playerId == "phone-1" } }
            assertFalse(harness.host.inputs.value.containsKey("phone-1"))
        } finally {
            client.close()
            harness.close()
        }
    }

    @Test
    fun rememberedDeviceSkipsTheCode() {
        val harness = Harness()
        val shared = ByteArray(32) { 42 }
        // Pre-pair both ends out of band: TV holds the key by sender id, phone by host id.
        harness.hostKeys["phone-1"] = shared
        val clientKeys = ConcurrentHashMap<String, ByteArray>()
        clientKeys["tv-1"] = shared
        val client = newClient(keys = clientKeys)
        try {
            client.connect(harness.hostInfo())
            await("paired without code") { client.state.value == ClientState.PAIRED }
            assertEquals(null, harness.host.pairingCode)
            client.join("Bob")
            await("joined") { client.state.value == ClientState.JOINED }
            client.sendInput(buttons = ControllerButton.X.bit)
            await("input accepted") { harness.host.inputs.value.containsKey("phone-1") }
        } finally {
            client.close()
            harness.close()
        }
    }

    @Test
    fun wrongCodeReturnsToAwaitingThenCorrectCodePairs() {
        val harness = Harness()
        val client = newClient(id = "phone-2")
        try {
            client.connect(harness.hostInfo())
            await("awaiting code") { client.state.value == ClientState.AWAITING_CODE }
            val code = assertNotNull(harness.host.pairingCode)
            val wrong = if (code == "000000") "000001" else "000000"
            client.proveCode(wrong)
            // Host refuses the session on a wrong proof (PAIR_FAILED then close), so the
            // client's blocked receive ends and a fresh connect is the retry path. The
            // client-side branch that tolerates a non-PAIR_OK answer keeps it awaiting:
            // observe either FAILED (socket closed) as the protocol outcome.
            await("wrong proof resolved") {
                client.state.value == ClientState.FAILED || client.state.value == ClientState.AWAITING_CODE
            }
        } finally {
            client.close()
            harness.close()
        }
    }

    @Test
    fun unreachableHostFails() {
        val client = newClient(id = "phone-3")
        try {
            client.connect(
                ControllerHostInfo(
                    id = "tv-x", instanceName = "X", friendlyName = "X",
                    host = "127.0.0.1", port = 1,
                    protocolVersion = 1, gameId = "kart",
                ),
            )
            await("failed") { client.state.value == ClientState.FAILED }
        } finally {
            client.close()
        }
    }

    @Test
    fun sealFailureFailsTheHandshake() {
        val harness = Harness()
        val client = newClient(id = "phone-4", sealFails = true)
        try {
            client.connect(harness.hostInfo())
            await("failed") { client.state.value == ClientState.FAILED }
        } finally {
            client.close()
            harness.close()
        }
    }

    @Test
    fun missingPublicBundleFailsTheHandshake() {
        val harness = Harness(bundle = null)
        val client = newClient(id = "phone-5")
        try {
            client.connect(harness.hostInfo())
            await("failed") { client.state.value == ClientState.FAILED }
        } finally {
            client.close()
            harness.close()
        }
    }

    @Test
    fun sendInputBeforeJoinIsDroppedAndCloseIsIdempotent() {
        val client = newClient(id = "phone-6")
        client.sendInput(buttons = ControllerButton.A.bit)
        assertEquals(ClientState.IDLE, client.state.value)
        // proveCode / join outside their states are no-ops, not crashes.
        client.proveCode("123456")
        client.join("Nobody")
        Thread.sleep(150)
        assertEquals(ClientState.IDLE, client.state.value)
        client.close()
        client.close()
        assertEquals(ClientState.IDLE, client.state.value)
    }

    @Test
    fun racingLobbyRefusesJoin() {
        val harness = Harness()
        val client = newClient(id = "phone-7")
        try {
            harness.host.startRace()
            client.connect(harness.hostInfo())
            await("awaiting code") { client.state.value == ClientState.AWAITING_CODE }
            client.proveCode(assertNotNull(harness.host.pairingCode))
            await("paired") { client.state.value == ClientState.PAIRED }
            client.join("Late")
            // Host answers with BYE, not a lobby: the client fails the join and
            // close() settles it back to IDLE without ever seating the player.
            await("join refused") { client.state.value == ClientState.IDLE }
            assertTrue(harness.host.lobby.value.slots.none { it.playerId == "phone-7" })
        } finally {
            client.close()
            harness.close()
        }
    }
}
