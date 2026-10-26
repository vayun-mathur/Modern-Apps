package com.vayunmathur.shop.network

import com.vayunmathur.library.network.NetworkDataStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.charset.CharacterCodingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UcpSseTest {
    @Test
    fun splitUtf8AndAllLineEndingsPreserveMultilineData() = runBlocking {
        val text = "caf\u00e9 \u96ea \ud83d\ude80"
        for (ending in listOf("\n", "\r\n", "\r")) {
            for (chunk in listOf(1, 2, 3, 7, 4096)) {
                val reader = reader(
                    ": keepalive${ending}event: catalog${ending}id: ignored${ending}" +
                        "retry: 10${ending}data: $text${ending}data: second${ending}${ending}" +
                        "data: next${ending}${ending}",
                    chunk,
                )
                assertEquals(UcpSseEvent("catalog", "$text\nsecond"), reader.next(), "ending=$ending chunk=$chunk")
                assertEquals(UcpSseEvent("message", "next"), reader.next())
                assertNull(reader.next())
            }
        }
    }

    @Test
    fun commentsUnknownFieldsAndEmptyFramesDoNotDispatch() = runBlocking {
        val reader = reader(
            ": comment\r\n\r\nevent: ignored\nunknown: ignored\n\n" +
                "data\ndata:  two spaces\ndata:\n\n",
            1,
        )
        assertEquals(UcpSseEvent("message", "\n two spaces\n"), reader.next())
        assertNull(reader.next())
        assertNull(reader(": heartbeat\n\n").next())
    }

    @Test
    fun mixedTerminatorsAndBomAreDecodedAcrossSingleByteReads() = runBlocking {
        val reader = reader("\uFEFFdata: one\r\ndata: two\rdata: three\n\r\ndata: four\r\r", 1)
        assertEquals(UcpSseEvent("message", "one\ntwo\nthree"), reader.next())
        assertEquals(UcpSseEvent("message", "four"), reader.next())
        assertNull(reader.next())
    }

    @Test
    fun malformedAndIncompleteUtf8AreRejected() = runBlocking {
        for (invalid in listOf(byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(0xE2.toByte(), 0x82.toByte()))) {
            val bytes = "data: ".toByteArray() + invalid + "\n\n".toByteArray()
            assertFailsWith<CharacterCodingException> { UcpSse(SplitStream(bytes, 1)).next() }
            assertFailsWith<CharacterCodingException> { UcpWire.readBody(SplitStream(invalid, 1)) }
        }
    }

    @Test
    fun unterminatedLinesAndEventsFailInsteadOfReturningPartialData() = runBlocking {
        for (body in listOf("data: partial", "data: partial\n", "event: message\ndata: partial\r\n", ": partial")) {
            assertFailsWith<IllegalArgumentException>(body) { reader(body, 1).next() }
        }
        assertNull(reader("").next())
        assertNull(reader(": complete comment\n").next())
        assertEquals(UcpSseEvent("message", "complete"), reader("data: complete\n\n").next())
    }

    @Test
    fun frameLimitIncludesAllLinesAndDelimiters() = runBlocking<Unit> {
        val exact = "data: " + "x".repeat(UcpWire.MAX_EVENT_BYTES - 8) + "\n\n"
        assertEquals("x".repeat(UcpWire.MAX_EVENT_BYTES - 8), reader(exact, 4096).next()?.data)
        assertFailsWith<IllegalArgumentException> { reader(exact.replace("data: ", "data: x")).next() }
        val multiline = "data: " + "x".repeat(32_760) + "\ndata: " + "y".repeat(32_762) + "\n\n"
        assertFailsWith<IllegalArgumentException> { reader(multiline).next() }
        assertFailsWith<IllegalArgumentException> { reader(":" + "x".repeat(UcpWire.MAX_EVENT_BYTES) + "\n\n").next() }
        val multibyte = "data: " + "\u00e9".repeat(UcpWire.MAX_EVENT_BYTES / 2) + "\n\n"
        assertFailsWith<IllegalArgumentException> { reader(multibyte, 3).next() }
    }

    @Test
    fun eventCountIsBoundedAcrossCallsAndIncludesHeartbeats() = runBlocking<Unit> {
        val reader = reader("data: x\n\n".repeat(UcpWire.MAX_EVENTS + 1))
        repeat(UcpWire.MAX_EVENTS) { assertEquals(UcpSseEvent("message", "x"), reader.next()) }
        assertFailsWith<IllegalArgumentException> { reader.next() }
        assertNull(reader(": heartbeat\n\n".repeat(UcpWire.MAX_EVENTS)).next())
        assertFailsWith<IllegalArgumentException> {
            reader(": heartbeat\n\n".repeat(UcpWire.MAX_EVENTS + 1)).next()
        }
    }

    @Test
    fun aggregateBodyLimitCannotBeBypassedWithSmallFrames() = runBlocking<Unit> {
        // 128 individually valid frames exactly fill the body budget, below the event cap.
        val frame = ":" + "x".repeat(8192 - 3) + "\n\n"
        assertNull(reader(frame.repeat(UcpWire.MAX_BODY_BYTES / frame.length), 4096).next())
        assertFailsWith<IllegalArgumentException> {
            reader(frame.repeat(UcpWire.MAX_BODY_BYTES / frame.length) + "\n", 4096).next()
        }
    }

    @Test
    fun bodyReaderCapsBytesAndPreservesSplitUtf8() = runBlocking<Unit> {
        val body = "\uFEFF{\"name\":\"caf\u00e9 \u96ea\"}"
        assertEquals(body.removePrefix("\uFEFF"), UcpWire.readBody(SplitStream(body.toByteArray(), 1)))
        assertEquals("x".repeat(UcpWire.MAX_BODY_BYTES), UcpWire.readBody(SplitStream(ByteArray(UcpWire.MAX_BODY_BYTES) { 120 }, 4096)))
        assertFailsWith<IllegalArgumentException> {
            UcpWire.readBody(SplitStream(ByteArray(UcpWire.MAX_BODY_BYTES + 1) { 120 }, 4096))
        }
    }

    @Test
    fun emptyEventTypeDefaultsToMessage() = runBlocking {
        assertEquals(UcpSseEvent("message", "reply"), reader("event:\ndata: reply\n\n", 1).next())
    }

    @Test
    fun zeroProgressStreamIsRejectedInsteadOfSpinning() = runBlocking<Unit> {
        val stream = object : NetworkDataStream {
            override val isClosedForRead = false
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        }
        assertFailsWith<IllegalArgumentException> { UcpSse(stream).next() }
    }

    @Test
    fun cancellationEscapesBothReadersAndClosesTheirOwner() = runBlocking {
        for (sse in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>()
            var closed = false
            val stream = object : NetworkDataStream {
                override val isClosedForRead get() = closed
                override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val job = launch {
                try {
                    if (sse) UcpSse(stream).next() else UcpWire.readBody(stream)
                } finally {
                    closed = true
                }
            }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(closed)
        }
    }

    private fun reader(text: String, chunk: Int = 4096) = UcpSse(SplitStream(text.toByteArray(Charsets.UTF_8), chunk))

    private class SplitStream(private val bytes: ByteArray, private val chunk: Int) : NetworkDataStream {
        private var position = 0
        override val isClosedForRead get() = position == bytes.size
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (isClosedForRead) return -1
            val count = minOf(chunk, length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }
}
