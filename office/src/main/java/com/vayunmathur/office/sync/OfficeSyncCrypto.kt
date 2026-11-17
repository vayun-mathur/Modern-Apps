package com.vayunmathur.office.sync

import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.e2ee.Pqc
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * Crypto facades for [OfficeSync] (split for file length).
 * Extension functions on [OfficeSync]; behavior identical, call sites unchanged.
 */

suspend fun OfficeSync.securityCode(peerBundle: ByteArray): String? =
    runCatching { Pqc.securityCode(identity.publicBundle, peerBundle) }.getOrNull()

suspend fun OfficeSync.sign(data: ByteArray): ByteArray = identity.sign(data)

suspend fun OfficeSync.verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
    Pqc.verify(publicBundle, data, signature)

suspend fun OfficeSync.seal(bundle: ByteArray, data: ByteArray): ByteArray = Pqc.encryptTo(bundle, data)

suspend fun OfficeSync.unseal(data: ByteArray): ByteArray = identity.decrypt(data)

suspend fun OfficeSync.appendRaw(channel: String, items: List<String>): Int? = append(channel, items)

suspend fun OfficeSync.pullRaw(channel: String, since: Int): List<String> =
    pull(channel, since)?.actions ?: emptyList()

fun OfficeSync.decrypt(key: ByteArray, b64: String): String? =
    runCatching { E2ee.aesDecrypt(key, Base64.decode(b64)).decodeToString() }.getOrNull()
