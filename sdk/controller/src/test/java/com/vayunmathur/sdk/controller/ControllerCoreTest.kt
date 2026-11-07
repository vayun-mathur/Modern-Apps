package com.vayunmathur.sdk.controller

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControllerCoreTest {

    // ---- ControllerKeys ----

    @Test
    fun secretMustBeExactly32Bytes() {
        assertFailsWith<IllegalArgumentException> { ControllerKeys.of(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { ControllerKeys.of(ByteArray(33)) }
        assertFailsWith<IllegalArgumentException> { ControllerKeys.of(ByteArray(0)) }
        assertEquals(32, ControllerKeys.of(ByteArray(32)).control.size)
    }

    @Test
    fun newSecretIs32RandomBytes() {
        val a = ControllerKeys.newSecret()
        val b = ControllerKeys.newSecret()
        assertEquals(ControllerKeys.SECRET_BYTES, a.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun controlKeyIsDeterministicAndSecretDependent() {
        val secret = ByteArray(32) { it.toByte() }
        val a = ControllerKeys.of(secret)
        val b = ControllerKeys.of(secret.copyOf())
        assertTrue(a.control.contentEquals(b.control))
        val other = ControllerKeys.of(ByteArray(32) { (it + 1).toByte() })
        assertFalse(a.control.contentEquals(other.control))
    }

    @Test
    fun pairProofBindsCodeTranscriptAndSecret() {
        val keys = ControllerKeys.of(ByteArray(32) { 7 })
        val transcript = byteArrayOf(1, 2, 3)
        val base = keys.pairProof("123456", transcript)
        assertEquals(32, base.size)
        // Same inputs -> same proof (HMAC is deterministic).
        assertTrue(base.contentEquals(keys.pairProof("123456", transcript)))
        // Any change to code, transcript or secret changes the proof.
        assertFalse(base.contentEquals(keys.pairProof("123457", transcript)))
        assertFalse(base.contentEquals(keys.pairProof("123456", byteArrayOf(1, 2, 4))))
        val otherKeys = ControllerKeys.of(ByteArray(32) { 8 })
        assertFalse(base.contentEquals(otherKeys.pairProof("123456", transcript)))
    }

    @Test
    fun deviceProofBindsDeviceKeyAndTranscript() {
        val keys = ControllerKeys.of(ByteArray(32) { 9 })
        val transcript = byteArrayOf(5, 6)
        val deviceKey = ByteArray(32) { 3 }
        val base = keys.deviceProof(deviceKey, transcript)
        assertTrue(base.contentEquals(keys.deviceProof(deviceKey.copyOf(), transcript)))
        assertFalse(base.contentEquals(keys.deviceProof(ByteArray(32) { 4 }, transcript)))
        assertFalse(base.contentEquals(keys.deviceProof(deviceKey, byteArrayOf(5, 7))))
        // Pair proof and device proof live in different HKDF domains.
        assertFalse(base.contentEquals(keys.pairProof("123456", transcript)))
    }

    @Test
    fun proofsMatchIsContentEquality() {
        assertTrue(proofsMatch(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(proofsMatch(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(proofsMatch(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
        assertTrue(proofsMatch(ByteArray(0), ByteArray(0)))
    }

    // ---- ControllerTranscript ----

    @Test
    fun transcriptOfNothingIsSha256OfEmpty() {
        val empty = ControllerTranscript().value()
        assertEquals(32, empty.size)
        // SHA-256("") is a well-known constant.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            empty.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun transcriptDependsOnBodiesAndTheirOrder() {
        val ab = ControllerTranscript().apply { add(byteArrayOf(1)); add(byteArrayOf(2)) }.value()
        val ba = ControllerTranscript().apply { add(byteArrayOf(2)); add(byteArrayOf(1)) }.value()
        val aOnly = ControllerTranscript().apply { add(byteArrayOf(1)) }.value()
        assertFalse(ab.contentEquals(ba))
        assertFalse(ab.contentEquals(aOnly))
        // Adding the same bodies again yields the same value.
        val ab2 = ControllerTranscript().apply { add(byteArrayOf(1)); add(byteArrayOf(2)) }.value()
        assertTrue(ab.contentEquals(ab2))
    }

    // ---- ControllerPairCode ----

    @Test
    fun pairCodeRandomIsAlwaysSixDigits() {
        repeat(200) {
            val code = ControllerPairCode.random()
            assertEquals(ControllerPairCode.DIGITS, code.length)
            assertTrue(ControllerPairCode.isWellFormed(code), "code $code must be well-formed")
        }
    }

    @Test
    fun pairCodeWellFormedEdges() {
        assertTrue(ControllerPairCode.isWellFormed("000000"))
        assertTrue(ControllerPairCode.isWellFormed("999999"))
        assertFalse(ControllerPairCode.isWellFormed(""))
        assertFalse(ControllerPairCode.isWellFormed("12345"))
        assertFalse(ControllerPairCode.isWellFormed("1234567"))
        assertFalse(ControllerPairCode.isWellFormed("12345a"))
        assertFalse(ControllerPairCode.isWellFormed(" 12345"))
        assertFalse(ControllerPairCode.isWellFormed("１２３４５６"))
    }

    // ---- ControllerPairingGate ----

    @Test
    fun gateStartsWithInjectedCodeAndFullAttempts() {
        val gate = ControllerPairingGate(newCode = { "424242" })
        assertEquals("424242", gate.code)
        assertEquals(ControllerPairCode.MAX_ATTEMPTS, gate.attemptsLeft)
    }

    @Test
    fun gateWrongAttemptsDecrementThenRotate() {
        val codes = ArrayDeque(listOf("111111", "222222"))
        val gate = ControllerPairingGate(newCode = { codes.removeFirst() })
        val keys = ControllerKeys.of(ControllerKeys.newSecret())
        val transcript = byteArrayOf(9, 9)
        val wrong = keys.pairProof("000000", transcript)

        val r1 = gate.verifyCode(keys, transcript, wrong)
        assertTrue(r1 is ControllerPairResult.Wrong)
        assertEquals(2, r1.attemptsLeft)
        assertFalse(r1.codeChanged)
        assertEquals("111111", gate.code)

        val r2 = gate.verifyCode(keys, transcript, wrong)
        assertTrue(r2 is ControllerPairResult.Wrong)
        assertEquals(1, r2.attemptsLeft)

        val r3 = gate.verifyCode(keys, transcript, wrong)
        assertTrue(r3 is ControllerPairResult.Wrong)
        assertTrue(r3.codeChanged)
        assertEquals(ControllerPairCode.MAX_ATTEMPTS, r3.attemptsLeft)
        assertEquals("222222", gate.code)
        assertEquals(ControllerPairCode.MAX_ATTEMPTS, gate.attemptsLeft)
    }

    @Test
    fun gateSuccessMintsFreshDeviceKeyAndResetsAttempts() {
        val codes = ArrayDeque(listOf("111111", "222222", "333333"))
        val gate = ControllerPairingGate(newCode = { codes.removeFirst() })
        val keys = ControllerKeys.of(ControllerKeys.newSecret())
        val transcript = byteArrayOf(1)
        // Burn one attempt first so the reset is observable.
        gate.verifyCode(keys, transcript, keys.pairProof("000000", transcript))
        assertEquals(2, gate.attemptsLeft)
        val ok = gate.verifyCode(keys, transcript, keys.pairProof("111111", transcript))
        assertTrue(ok is ControllerPairResult.Ok)
        val deviceKey = assertNotNull(ok.deviceKey)
        assertEquals(ControllerPairingGate.DEVICE_KEY_BYTES, deviceKey.size)
        assertEquals(ControllerPairCode.MAX_ATTEMPTS, gate.attemptsLeft)
        assertEquals("222222", gate.code)
        // A second success mints a different key.
        val ok2 = gate.verifyCode(keys, transcript, keys.pairProof("222222", transcript))
        assertTrue(ok2 is ControllerPairResult.Ok)
        assertFalse(deviceKey.contentEquals(assertNotNull(ok2.deviceKey)))
    }

    @Test
    fun gateResetRotatesCodeAndRestoresAttempts() {
        val codes = ArrayDeque(listOf("111111", "222222"))
        val gate = ControllerPairingGate(newCode = { codes.removeFirst() })
        val keys = ControllerKeys.of(ControllerKeys.newSecret())
        val transcript = byteArrayOf(2)
        gate.verifyCode(keys, transcript, keys.pairProof("000000", transcript))
        gate.reset()
        assertEquals("222222", gate.code)
        assertEquals(ControllerPairCode.MAX_ATTEMPTS, gate.attemptsLeft)
    }

    @Test
    fun gateDeviceVerificationHasNoLockout() {
        val gate = ControllerPairingGate(newCode = { "123456" })
        val keys = ControllerKeys.of(ControllerKeys.newSecret())
        val transcript = byteArrayOf(3)
        val deviceKey = ControllerPairingGate.newDeviceKey()
        val bad = keys.deviceProof(ByteArray(32) { 1 }, transcript)
        repeat(5) {
            val result = gate.verifyDevice(keys, transcript, deviceKey, bad)
            assertTrue(result is ControllerPairResult.Wrong)
            assertFalse(result.codeChanged)
        }
        // Failures never consume code attempts and the good proof still passes.
        assertEquals(ControllerPairCode.MAX_ATTEMPTS, gate.attemptsLeft)
        assertEquals("123456", gate.code)
        val ok = gate.verifyDevice(keys, transcript, deviceKey, keys.deviceProof(deviceKey, transcript))
        assertTrue(ok is ControllerPairResult.Ok)
        assertNull(ok.deviceKey)
    }

    @Test
    fun newDeviceKeyIs32RandomBytes() {
        val a = ControllerPairingGate.newDeviceKey()
        val b = ControllerPairingGate.newDeviceKey()
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun truncatedProofNeverVerifies() {
        val gate = ControllerPairingGate(newCode = { "123456" })
        val keys = ControllerKeys.of(ControllerKeys.newSecret())
        val transcript = byteArrayOf(4)
        val good = keys.pairProof("123456", transcript)
        val result = gate.verifyCode(keys, transcript, good.copyOf(16))
        assertTrue(result is ControllerPairResult.Wrong)
    }

    // ---- ControllerInputGate edges ----

    @Test
    fun gateRejectsNonIncreasingSeqFromTheStart() {
        val gate = ControllerInputGate()
        assertFalse(gate.offer(0, nowMs = 0))
        assertFalse(gate.offer(-5, nowMs = 0))
        assertTrue(gate.offer(1, nowMs = 0))
        // A backwards jump is a replay even with a fresh timestamp.
        assertFalse(gate.offer(1, nowMs = 5_000))
    }

    @Test
    fun gateAllowsSequenceGaps() {
        val gate = ControllerInputGate()
        assertTrue(gate.offer(1, nowMs = 0))
        assertTrue(gate.offer(100, nowMs = 10))
        assertFalse(gate.offer(99, nowMs = 20))
        assertTrue(gate.offer(101, nowMs = 30))
    }

    @Test
    fun rateWindowBoundaryIsStrictlyOverOneSecond() {
        val gate = ControllerInputGate(maxPerSecond = 2)
        assertTrue(gate.offer(1, nowMs = 0))
        assertTrue(gate.offer(2, nowMs = 500))
        // Exactly 1000 ms later the first sample still counts (eviction is > 1000).
        assertFalse(gate.offer(3, nowMs = 1_000))
        // At 1001 ms it has slid out.
        assertTrue(gate.offer(3, nowMs = 1_001))
    }

    @Test
    fun rejectedByRateLimitDoesNotAdvanceHighWaterMark() {
        val gate = ControllerInputGate(maxPerSecond = 1)
        assertTrue(gate.offer(1, nowMs = 0))
        assertFalse(gate.offer(2, nowMs = 10))
        // seq 2 was dropped, not applied: it may still arrive after the window slides.
        assertTrue(gate.offer(2, nowMs = 1_001))
    }

    // ---- Models / JSON ----

    @Test
    fun inputDefaultsAndButtonBits() {
        val input = ControllerInput(playerId = "p", seq = 7)
        assertEquals(0L, input.buttons)
        assertEquals(AXIS_COUNT, input.axes.size)
        assertTrue(input.axes.all { it == 0f })
        assertNull(input.touchX)
        assertNull(input.touchY)
        assertEquals(0L, input.clientTimestampMs)
        for (button in ControllerButton.entries) {
            assertFalse(input.isPressed(button))
            assertTrue(ControllerInput(playerId = "p", seq = 7, buttons = button.bit).isPressed(button))
        }
        // Button bits are unique powers of two.
        val bits = ControllerButton.entries.map { it.bit }
        assertEquals(bits.size, bits.distinct().size)
        assertTrue(bits.all { it > 0 && (it and (it - 1)) == 0L })
    }

    @Test
    fun everyMessageTypeRoundTripsThroughJson() {
        val messages: List<ControllerMessage> = listOf(
            ControllerHello(senderName = "Phone", senderId = "id-1", paired = true),
            ControllerHostHello(hostName = "TV", hostId = "tv-1", publicBundle = "AA==", gameId = "kart"),
            ControllerSealedSecret("c2VhbGVk"),
            ControllerPairRequired(code = true),
            ControllerPairRequired(code = false),
            ControllerPairProof("cHJvb2Y="),
            ControllerPairOk(),
            ControllerPairOk("ZGV2aWNl"),
            ControllerPairFailed(2),
            ControllerJoin("Alice"),
            ControllerLeave(),
            ControllerLeave("done"),
            ControllerInputFrame(
                ControllerInput(
                    playerId = "p1", seq = 42, buttons = 5L,
                    axes = listOf(0.1f, -0.2f, 0f, 0f, 1f, 0.5f),
                    hatX = -1, hatY = 1, touchX = 0.25f, touchY = -0.75f, clientTimestampMs = 123L,
                ),
            ),
            ControllerLobby(LobbyState(slots = listOf(PlayerSlot("p1", "Alice", remote = true)), gameId = "kart", racing = true)),
            ControllerRumble(RumbleFeedback(durationMs = 120, lowFreq = 0.5f, highFreq = 0.25f)),
            ControllerPing,
            ControllerBye(),
            ControllerBye("full"),
        )
        for (message in messages) {
            val encoded = ControllerJson.encodeToString(message)
            assertTrue(encoded.contains("\"type\""), "discriminator missing for $message: $encoded")
            assertEquals(message, ControllerJson.decodeFromString<ControllerMessage>(encoded))
            // The codec (plaintext) round-trips the same values.
            assertEquals(message, ControllerCodec().decode(ControllerCodec().encode(message)))
        }
    }

    @Test
    fun jsonIgnoresUnknownKeysAndOmitsNullDeviceKey() {
        val decoded = ControllerJson.decodeFromString<ControllerMessage>(
            """{"type":"PAIR_OK","futureField":42}""",
        )
        assertEquals(ControllerPairOk(), decoded)
        val encoded = ControllerJson.encodeToString(ControllerPairOk())
        assertFalse(encoded.contains("deviceKey"), "null deviceKey must stay absent: $encoded")
        // Defaults are encoded because the peer needs the version.
        val helloJson = ControllerJson.encodeToString(ControllerHello(senderName = "P", senderId = "i"))
        assertTrue(helloJson.contains("\"version\":1"), helloJson)
    }

    @Test
    fun modelDefaultsAreStable() {
        assertEquals(LobbyState(), LobbyState(slots = emptyList(), gameId = "", racing = false))
        assertEquals(RumbleFeedback(100), RumbleFeedback(100, lowFreq = 0f, highFreq = 1f))
        assertEquals(PlayerSlot("p", "A", remote = true), PlayerSlot("p", "A", remote = true, connected = true, lastSeq = 0L))
        assertEquals(ControllerLeave(), ControllerLeave(""))
        assertEquals(ControllerBye(), ControllerBye(""))
        assertNotEquals(ControllerPairRequired(true), ControllerPairRequired(false))
    }

    @Test
    fun contractConstantsArePinned() {
        assertEquals(1, ControllerContract.CONTROLLER_PROTOCOL_VERSION)
        assertEquals("_gamepad._tcp", ControllerContract.GAMEPAD_SERVICE_TYPE)
        assertEquals(30, ControllerContract.INPUT_HZ)
        assertEquals(40, ControllerContract.MAX_INPUT_PER_SECOND)
        assertEquals(8, ControllerContract.MAX_PLAYERS)
        assertEquals(20_000L, ControllerContract.PING_INTERVAL_MS)
        assertEquals(6, AXIS_COUNT)
        assertEquals(0, AXIS_LEFT_X)
        assertEquals(5, AXIS_BRAKE)
    }

    // ---- Framing edges ----

    @Test
    fun framingCleanEofReturnsNullAndZeroOrNegativeLengthsThrow() {
        assertNull(ControllerFraming.read(DataInputStream(ByteArrayInputStream(ByteArray(0)))))
        assertFailsWith<IllegalArgumentException> {
            ControllerFraming.read(DataInputStream(byteArrayOf(0, 0, 0, 0).inputStream()))
        }
        assertFailsWith<IllegalArgumentException> {
            ControllerFraming.read(DataInputStream(byteArrayOf(-1, -1, -1, -1).inputStream()))
        }
    }

    @Test
    fun framingTruncatedPayloadThrowsEof() {
        val frame = ControllerFraming.encode(byteArrayOf(1, 2, 3, 4)).copyOf(6)
        assertFailsWith<EOFException> { ControllerFraming.read(DataInputStream(frame.inputStream())) }
    }

    @Test
    fun framingReadsBackToBackFrames() {
        val a = ControllerFraming.encode(byteArrayOf(1))
        val b = ControllerFraming.encode(byteArrayOf(2, 3))
        val input = DataInputStream((a + b).inputStream())
        assertTrue(ControllerFraming.read(input)!!.contentEquals(byteArrayOf(1)))
        assertTrue(ControllerFraming.read(input)!!.contentEquals(byteArrayOf(2, 3)))
        assertNull(ControllerFraming.read(input))
    }

    // ---- Codec edges ----

    @Test
    fun codecRejectsGarbageAndTamperedCiphertext() {
        val plain = ControllerCodec()
        assertNull(plain.decode("not json".toByteArray()))
        assertNull(plain.decode(ByteArray(0)))
        assertFalse(plain.isEncrypting)

        val key = ControllerKeys.of(ControllerKeys.newSecret()).control
        val a = ControllerCodec().apply { useSessionKey(key) }
        val b = ControllerCodec().apply { useSessionKey(key) }
        assertTrue(a.isEncrypting)
        val body = a.encode(ControllerPing)
        val tampered = body.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        assertNull(b.decode(tampered))
        // A plaintext JSON body is not valid once the cipher is installed.
        assertNull(b.decode("""{"type":"PING"}""".toByteArray()))
    }
}
