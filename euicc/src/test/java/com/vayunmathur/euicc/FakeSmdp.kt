package com.vayunmathur.euicc

import com.sun.net.httpserver.HttpServer
import com.vayunmathur.euicc.telephony.FakeEuiccTelephonyShadow
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Minimal DER TLV helpers for emulating eUICC / SM-DP+ payloads in tests. */
internal fun der(tag: ByteArray, value: ByteArray): ByteArray {
    val len = when {
        value.size < 0x80 -> byteArrayOf(value.size.toByte())
        value.size < 0x100 -> byteArrayOf(0x81.toByte(), value.size.toByte())
        else -> byteArrayOf(0x82.toByte(), (value.size shr 8).toByte(), value.size.toByte())
    }
    return tag + len + value
}

internal fun tlv1(tag: Int, value: ByteArray) = der(byteArrayOf(tag.toByte()), value)
internal fun tlv2(b1: Int, b2: Int, value: ByteArray) = der(byteArrayOf(b1.toByte(), b2.toByte()), value)
internal fun cat(vararg parts: ByteArray): ByteArray {
    var out = ByteArray(0)
    for (p in parts) out += p
    return out
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
internal fun hexBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** BCD-encodes decimal digits the way ICCIDs are stored (nibble-swapped, F-padded). */
internal fun iccidBcd(digits: String): ByteArray {
    val padded = if (digits.length % 2 == 1) digits + "F" else digits
    return ByteArray(padded.length / 2) { i ->
        val lo = padded[i * 2].digitToIntOrNull() ?: 0xF
        val hi = padded.getOrNull(i * 2 + 1)?.digitToIntOrNull() ?: 0xF
        ((hi shl 4) or lo).toByte()
    }
}

internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/**
 * A fake eUICC at the APDU layer plus a fake SM-DP+ at the HTTP layer, good
 * enough for the Rust SGP.22 core to run authenticate / finish / cancel and
 * the ES10 reads against. APDU replies are keyed by the DER tag of the
 * (reassembled) STORE DATA payload; BPP segments after the first are counted.
 */
class FakeEuicc {
    var profileSet: List<ByteArray> = defaultProfiles()
    private var bppSegmentsLeft = -1
    private var partial = StringBuilder()

    fun install() {
        FakeEuiccTelephonyShadow.transmitHandler = { call -> handle(call) }
    }

    private fun handle(call: FakeEuiccTelephonyShadow.TransmitCall): String {
        // STORE DATA chunking: P1 0x11 = more blocks follow, 0x91 = last block.
        if (call.p1 == 0x11) {
            partial.append(call.data)
            return "9000"
        }
        val full = partial.toString() + call.data
        partial = StringBuilder()
        return respond(full)
    }

    private fun respond(dataHex: String): String {
        when {
            dataHex.startsWith("BF36") -> {
                // Our BPP segments into 6 STORE DATA calls; this is the first.
                bppSegmentsLeft = 5
                return "9000"
            }
            bppSegmentsLeft > 0 -> {
                bppSegmentsLeft--
                if (bppSegmentsLeft == 0) {
                    bppSegmentsLeft = -1
                    return installSuccessHex()
                }
                return "9000"
            }
        }
        val body: ByteArray = when {
            dataHex.startsWith("BF2E") ->
                tlv2(0xBF, 0x2E, tlv1(0x80, ByteArray(16) { (it + 1).toByte() }))
            dataHex.startsWith("BF20") -> info1()
            dataHex.startsWith("BF3E") ->
                tlv2(0xBF, 0x3E, tlv1(0x5A, hexBytes("89044022444301000000000000012345".take(32))))
            dataHex.startsWith("BF2D") ->
                tlv2(0xBF, 0x2D, tlv1(0xA0, cat(*profileSet.toTypedArray())))
            dataHex.startsWith("BF28") -> notifications()
            dataHex.startsWith("BF30") -> tlv2(0xBF, 0x30, tlv1(0x80, byteArrayOf(0)))
            dataHex.startsWith("BF31") -> tlv2(0xBF, 0x31, tlv1(0x80, byteArrayOf(0)))
            dataHex.startsWith("BF32") -> tlv2(0xBF, 0x32, tlv1(0x80, byteArrayOf(0)))
            dataHex.startsWith("BF33") -> tlv2(0xBF, 0x33, tlv1(0x80, byteArrayOf(0)))
            dataHex.startsWith("BF29") -> tlv2(0xBF, 0x29, tlv1(0x80, byteArrayOf(0)))
            dataHex.startsWith("BF38") -> tlv2(0xBF, 0x38, ByteArray(0))
            dataHex.startsWith("BF21") -> tlv2(0xBF, 0x21, ByteArray(0))
            dataHex.startsWith("BF41") -> tlv2(0xBF, 0x41, ByteArray(0))
            dataHex.startsWith("BF2B") -> tlv2(0xBF, 0x2B, ByteArray(0))
            else -> ByteArray(0)
        }
        return body.toHex() + "9000"
    }

    private fun info1(): ByteArray = tlv2(
        0xBF, 0x20,
        cat(
            tlv1(0x82, byteArrayOf(2, 2, 0)),
            tlv1(0xA9, tlv1(0x04, hexBytes("1122"))),
            tlv1(0xAA, tlv1(0x04, hexBytes("3344"))),
        ),
    )

    private fun notifications(): ByteArray {
        val meta = tlv2(
            0xBF, 0x2F,
            cat(
                tlv1(0x80, byteArrayOf(3)),
                tlv1(0x81, byteArrayOf(0, 0x80.toByte())),
                tlv1(0x0C, "smdp.example.com".toByteArray()),
                tlv1(0x5A, iccidBcd("8901234567890000001")),
            ),
        )
        return tlv2(0xBF, 0x28, tlv1(0xA0, meta))
    }

    private fun installSuccessHex(): String {
        val meta = tlv2(
            0xBF, 0x2F,
            cat(
                tlv1(0x80, byteArrayOf(7)),
                tlv1(0x5A, iccidBcd("8901234567890000001")),
            ),
        )
        val data = tlv2(0xBF, 0x27, cat(meta, tlv1(0xA2, tlv1(0xA0, ByteArray(0)))))
        return tlv2(0xBF, 0x37, data).toHex() + "9000"
    }

    companion object {
        fun profile(
            iccidDigits: String, state: Int, profileClass: Int,
            nickname: String = "", spn: String = "", name: String = "",
        ): ByteArray {
            var body = cat(
                tlv1(0x5A, iccidBcd(iccidDigits)),
                tlv1(0x4F, hexBytes("A0000005591010")),
                der(byteArrayOf(0x9F.toByte(), 0x70), byteArrayOf(state.toByte())),
                tlv1(0x95, byteArrayOf(profileClass.toByte())),
            )
            if (nickname.isNotEmpty()) body += tlv1(0x90, nickname.toByteArray())
            if (spn.isNotEmpty()) body += tlv1(0x91, spn.toByteArray())
            if (name.isNotEmpty()) body += tlv1(0x92, name.toByteArray())
            return tlv1(0xE3, body)
        }

        fun defaultProfiles(): List<ByteArray> = listOf(
            profile("8901234567890000001", state = 1, profileClass = 2, nickname = "Work", spn = "Example Mobile", name = "Prepaid"),
            profile("8901234567890000002", state = 0, profileClass = 0, spn = "Travel eSIM", name = "Global Data"),
            profile("8901234567890000003", state = 0, profileClass = 1),
            profile("8901234567890000004", state = 0, profileClass = 9),
        )
    }
}

/** Local HTTP SM-DP+ speaking just enough ES9+ for the Rust core. */
class FakeSmdp {
    var ccRequired: Boolean = false
    var refuseBoundProfilePackage: Boolean = false
    val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val port: Int get() = server.address.port
    fun activationCode(): String = "LPA:1\$http://127.0.0.1:$port\$MATCH1"

    fun count(fn: String): Int = counts[fn]?.get() ?: 0

    fun start() {
        server.createContext("/gsma/rsp2/es9plus/") { exchange ->
            val fn = exchange.requestURI.path.substringAfterLast('/')
            counts.computeIfAbsent(fn) { AtomicInteger() }.incrementAndGet()
            exchange.requestBody.readAllBytes()
            val (status, body) = when (fn) {
                "initiateAuthentication" -> 200 to initiate()
                "authenticateClient" -> 200 to authenticateClient()
                "getBoundProfilePackage" ->
                    if (refuseBoundProfilePackage) 200 to failedStatus()
                    else 200 to boundProfilePackage()
                "cancelSession" -> 204 to ""
                "handleNotification" -> 204 to ""
                else -> 404 to ""
            }
            val bytes = body.toByteArray()
            if (status == 204) {
                exchange.sendResponseHeaders(204, -1)
            } else {
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
    }

    fun stop() = server.stop(0)

    private fun successHeader(): String =
        """"header":{"functionExecutionStatus":{"status":"Executed-Success"}}"""

    private fun initiate(): String {
        val txBin = "txbin123".toByteArray()
        val serverSigned1 = der(byteArrayOf(0x30), tlv1(0x80, txBin))
        return """{${successHeader()},"transactionId":"tx-123","serverSigned1":"${b64(serverSigned1)}","serverSignature1":"${b64(byteArrayOf(1, 2, 3))}","euiccCiPKIdToBeUsed":"${b64(hexBytes("1122"))}","serverCertificate":"${b64(byteArrayOf(0x30, 0))}"}"""
    }

    private fun authenticateClient(): String {
        val metadata = tlv2(
            0xBF, 0x25,
            cat(
                tlv1(0x5A, iccidBcd("8901234567890000001")),
                tlv1(0x91, "Fake Carrier".toByteArray()),
                tlv1(0x92, "Fake Plan".toByteArray()),
                tlv1(0x95, byteArrayOf(2)),
            ),
        )
        val signed2 = der(
            byteArrayOf(0x30),
            cat(
                tlv1(0x80, "txbin123".toByteArray()),
                tlv1(0x01, byteArrayOf(if (ccRequired) 1 else 0)),
            ),
        )
        return """{${successHeader()},"profileMetadata":"${b64(metadata)}","smdpSigned2":"${b64(signed2)}","smdpSignature2":"${b64(byteArrayOf(4, 5))}","smdpCertificate":"${b64(byteArrayOf(0x30, 0))}"}"""
    }

    private fun failedStatus(): String =
        """{"header":{"functionExecutionStatus":{"status":"Failed","statusCodeData":{"subjectCode":"8.2.7","reasonCode":"3.8"}}}}"""

    private fun boundProfilePackage(): String {
        val body = cat(
            tlv2(0xBF, 0x23, byteArrayOf(1)),
            tlv1(0xA0, tlv1(0x87, byteArrayOf(0x11))),
            tlv1(0xA1, tlv1(0x88, byteArrayOf(0x33))),
            tlv1(0xA3, tlv1(0x86, byteArrayOf(0x55))),
        )
        val bpp = tlv2(0xBF, 0x36, body)
        return """{${successHeader()},"boundProfilePackage":"${b64(bpp)}"}"""
    }
}
