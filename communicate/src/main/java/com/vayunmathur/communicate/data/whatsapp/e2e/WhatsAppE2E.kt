package com.vayunmathur.communicate.data.whatsapp.e2e

import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppE2EPreKey
import com.vayunmathur.communicate.data.whatsapp.WhatsAppE2ESenderKey
import com.vayunmathur.communicate.data.whatsapp.WhatsAppE2ESession
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Rust-backed E2E crypto for WhatsApp (X3DH + Double Ratchet + Sender Keys).
 * Kotlin owns persistence (Room) and passes opaque session/sender-key blobs to Rust.
 *
 * Record format is Rust's own versioned encoding (RECORD_VERSION=1), not Java's
 * SignalRecord. Migration 6->7 clears the E2E tables, forcing a re-link.
 */
@OptIn(ExperimentalEncodingApi::class)
class WhatsAppE2E(
    private val db: WhatsAppDatabase,
    private val auth: WhatsAppAuthData,
) {

    /** Raw 32-byte identity public key for upload. */
    val ownIdentityPublicKey: ByteArray = b64(auth.identityPublicKey)

    private val ownIdentityPrivate: ByteArray = b64(auth.identityPrivateKey)
    private val ownSignedPreKeyPrivate: ByteArray = b64(auth.signedPreKeyPrivate)
    private val ownSignedPreKeyPublic: ByteArray = b64(auth.signedPreKeyPublic)

    private val ownUser: String = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
    private val ownDeviceId: Int = auth.deviceId

    // -----------------------------------------------------------------------
    // JID parsing — matches whatsmeow JID.SignalAddress: name=user, deviceId=device
    // -----------------------------------------------------------------------

    private fun parseJid(jid: String): Pair<String, Int> {
        val local = jid.substringBefore("@")
        val user = local.substringBefore(":").substringBefore(".")
        val device = local.substringAfter(":", "0").toIntOrNull() ?: 0
        return user to device
    }

    fun signalAddress(jid: String): Pair<String, Int> = parseJid(jid)

    fun hasSession(jid: String): Boolean {
        val (name, dev) = parseJid(jid)
        return runBlocking { db.e2eSessionDao().exists(name, dev) }
    }

    fun deleteSession(jid: String) {
        val (name, dev) = parseJid(jid)
        runBlocking { db.e2eSessionDao().delete(name, dev) }
    }

    // -- 1:1 encrypt / decrypt ------------------------------------------------

    /** An encrypted 1:1 message: wire type (pkmsg/msg) and ciphertext. */
    data class EncResult(val type: String, val data: ByteArray)

    fun encryptDM(jid: String, paddedPlaintext: ByteArray): EncResult {
        val (name, dev) = parseJid(jid)
        val entity = runBlocking { db.e2eSessionDao().get(name, dev) }
            ?: throw IllegalStateException("No session for $jid")
        val result = RustWhatsAppCrypto.encryptSplit(entity.record, paddedPlaintext)
        runBlocking {
            db.e2eSessionDao().insert(WhatsAppE2ESession(name, dev, result.newSession))
        }
        return EncResult(if (result.isPreKey) "pkmsg" else "msg", result.body)
    }

    fun decryptDM(jid: String, isPreKey: Boolean, ciphertext: ByteArray): ByteArray {
        val (name, dev) = parseJid(jid)
        return if (isPreKey) {
            // Inbound pkmsg: X3DH bob side + first message.
            val preKeyId = parsePreKeyIdFromMessage(ciphertext)
            val oneTimePriv: ByteArray? = if (preKeyId != null) {
                val entity = runBlocking { db.e2ePreKeyDao().get(preKeyId) }
                entity?.let { rec ->
                    // record = private(32) || public(32)
                    if (rec.record.size >= IDENTITY_KEY_SIZE) {
                        rec.record.copyOfRange(0, IDENTITY_KEY_SIZE)
                    } else {
                        null
                    }
                }
            } else null

            val decrypted = RustWhatsAppCrypto.decryptPreKeySplit(
                localIdentityPrivate = ownIdentityPrivate,
                localIdentityPublic = ownIdentityPublicKey,
                signedPreKeyPrivate = ownSignedPreKeyPrivate,
                oneTimePrivate = oneTimePriv,
                preKeyMessageBytes = ciphertext,
            )
            runBlocking {
                db.e2eSessionDao().insert(WhatsAppE2ESession(name, dev, decrypted.newSession))
                if (preKeyId != null) {
                    try { db.e2ePreKeyDao().delete(preKeyId) } catch (_: Exception) {}
                }
            }
            decrypted.plaintext
        } else {
            val entity = runBlocking { db.e2eSessionDao().get(name, dev) }
                ?: throw IllegalStateException("No session for $jid (msg)")
            val decrypted = RustWhatsAppCrypto.decryptMessageSplit(entity.record, ciphertext)
            runBlocking {
                db.e2eSessionDao().insert(WhatsAppE2ESession(name, dev, decrypted.newSession))
            }
            decrypted.plaintext
        }
    }

    // -- Parsed bundle --------------------------------------------------------

    /** A peers pre-key bundle parsed off the wire: registration id, pre-keys and identity key. */
    data class ParsedPreKeyBundle(
        val registrationId: Int,
        val preKeyId: Int?, // null if absent
        val preKeyPublic: ByteArray?, // 32 bytes or null
        val signedPreKeyId: Int,
        val signedPreKeyPublic: ByteArray, // 32
        val signedPreKeySignature: ByteArray, // 64
        val identityKey: ByteArray, // 32
    )

    fun processPreKeyBundle(jid: String, bundle: ParsedPreKeyBundle) {
        val (name, dev) = parseJid(jid)
        val sessionBytes = RustWhatsAppCrypto.processPreKeyBundle(
            localIdentityPrivate = ownIdentityPrivate,
            localIdentityPublic = ownIdentityPublicKey,
            localRegistrationId = auth.registrationId,
            registrationId = bundle.registrationId,
            preKeyId = bundle.preKeyId ?: -1,
            preKeyPublic = bundle.preKeyPublic,
            signedPreKeyId = bundle.signedPreKeyId,
            signedPreKeyPublic = bundle.signedPreKeyPublic,
            signedPreKeySignature = bundle.signedPreKeySignature,
            identityKey = bundle.identityKey,
        ) ?: throw IllegalStateException("Rust processPreKeyBundle returned null")
        runBlocking {
            db.e2eSessionDao().insert(WhatsAppE2ESession(name, dev, sessionBytes))
        }
    }

    // -- Group (sender key) ---------------------------------------------------

    fun createSenderKeyDistribution(groupJid: String): ByteArray {
        // Always mint a new sender key (matches GroupSessionBuilder.create overwriting).
        val created = RustWhatsAppCrypto.createSenderKeySplit()
        runBlocking {
            db.e2eSenderKeyDao().insert(
                WhatsAppE2ESenderKey(ownUser, ownDeviceId, groupJid, created.state)
            )
        }
        return created.skdm
    }

    fun processSenderKeyDistribution(groupJid: String, senderJid: String, skdmBytes: ByteArray) {
        val (sName, sDev) = parseJid(senderJid)
        val stateBytes = RustWhatsAppCrypto.processSenderKey(skdmBytes)
            ?: throw IllegalStateException("processSenderKey returned null")
        runBlocking {
            db.e2eSenderKeyDao().insert(
                WhatsAppE2ESenderKey(sName, sDev, groupJid, stateBytes)
            )
        }
    }

    fun encryptGroup(groupJid: String, paddedPlaintext: ByteArray): ByteArray {
        var stateEntity = runBlocking { db.e2eSenderKeyDao().get(ownUser, ownDeviceId, groupJid) }
        if (stateEntity == null) {
            // No sender key yet — create one (first group message).
            val created = RustWhatsAppCrypto.createSenderKeySplit()
            runBlocking {
                db.e2eSenderKeyDao().insert(
                    WhatsAppE2ESenderKey(ownUser, ownDeviceId, groupJid, created.state)
                )
            }
            stateEntity = runBlocking { db.e2eSenderKeyDao().get(ownUser, ownDeviceId, groupJid) }
                ?: throw IllegalStateException("Failed to create sender key")
        }
        val encrypted = RustWhatsAppCrypto.encryptGroupSplit(stateEntity.record, paddedPlaintext)
        runBlocking {
            db.e2eSenderKeyDao().insert(
                WhatsAppE2ESenderKey(ownUser, ownDeviceId, groupJid, encrypted.newState)
            )
        }
        return encrypted.data
    }

    fun decryptGroup(groupJid: String, senderJid: String, ciphertext: ByteArray): ByteArray {
        val (sName, sDev) = parseJid(senderJid)
        val entity = runBlocking { db.e2eSenderKeyDao().get(sName, sDev, groupJid) }
            ?: throw IllegalStateException("No sender key for $senderJid in $groupJid")
        val decrypted = RustWhatsAppCrypto.decryptGroupSplit(entity.record, ciphertext)
        runBlocking {
            db.e2eSenderKeyDao().insert(
                WhatsAppE2ESenderKey(sName, sDev, groupJid, decrypted.newState)
            )
        }
        return decrypted.data
    }

    // -- Prekey seeding & upload ---------------------------------------------

    /**
     * Previously seeded the signed prekey into the Java store for inbound pkmsg.
     * With Rust we use the raw keys from auth directly, so this is now a no-op
     * kept for call-site compatibility.
     */
    fun ensureSignedPreKeyStored() {
        // no-op
    }

    private data class LocalPreKey(val id: Int, val publicKey: ByteArray)

    private fun generatePreKeys(count: Int): List<LocalPreKey> {
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        val entities = ArrayList<WhatsAppE2EPreKey>(count)
        val locals = ArrayList<LocalPreKey>(count)
        for (i in 1..count) {
            val id = maxId + i
            val kp = RustWhatsAppCrypto.generateKeyPairSplit()
            // Store as private||public (64 bytes) — private needed for inbound pkmsg.
            val record = kp.privateKey + kp.publicKey
            entities.add(WhatsAppE2EPreKey(id, record, uploaded = false))
            locals.add(LocalPreKey(id, kp.publicKey))
        }
        runBlocking { db.e2ePreKeyDao().insertAll(entities) }
        return locals
    }

    fun buildPreKeyUploadContent(initialUpload: Boolean): List<WhatsAppProtocol.Node> {
        val wanted = if (initialUpload) INITIAL_PREKEY_UPLOAD else REFILL_PREKEY_COUNT
        val records = generatePreKeys(wanted)

        val regBytes = ByteArray(REGISTRATION_ID_SIZE)
        regBytes[0] = (auth.registrationId ushr SHIFT_BYTE3).toByte()
        regBytes[1] = (auth.registrationId ushr SHIFT_BYTE2).toByte()
        regBytes[2] = (auth.registrationId ushr SHIFT_BYTE1).toByte()
        regBytes[REGISTRATION_ID_SIZE - 1] = auth.registrationId.toByte()

        val listNode = WhatsAppProtocol.Node(
            tag = "list",
            content = records.map { preKeyToNode(it.id, it.publicKey, null) },
        )
        val signedNode = preKeyToNode(
            auth.signedPreKeyId,
            b64(auth.signedPreKeyPublic),
            b64(auth.signedPreKeySignature),
        )

        return listOf(
            WhatsAppProtocol.Node(tag = "registration", data = regBytes),
            WhatsAppProtocol.Node(tag = "type", data = byteArrayOf(PREKEY_VERSION_BYTE)),
            WhatsAppProtocol.Node(tag = "identity", data = ownIdentityPublicKey),
            listNode,
            signedNode,
        )
    }

    fun markPreKeysUploaded() {
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        runBlocking { db.e2ePreKeyDao().markUploadedUpTo(maxId) }
    }

    fun buildRetryReceiptKeysNode(accountDeviceIdentity: ByteArray?): WhatsAppProtocol.Node {
        val oneTime = generatePreKeys(1).first()
        val children = mutableListOf(
            WhatsAppProtocol.Node(tag = "type", data = byteArrayOf(PREKEY_VERSION_BYTE)),
            WhatsAppProtocol.Node(tag = "identity", data = ownIdentityPublicKey),
            preKeyToNode(oneTime.id, oneTime.publicKey, null),
            preKeyToNode(auth.signedPreKeyId, b64(auth.signedPreKeyPublic), b64(auth.signedPreKeySignature)),
        )
        if (accountDeviceIdentity != null) {
            children.add(WhatsAppProtocol.Node(tag = "device-identity", data = accountDeviceIdentity))
        }
        return WhatsAppProtocol.Node(tag = "keys", content = children)
    }

    private fun preKeyToNode(id: Int, pub32: ByteArray, signature: ByteArray?): WhatsAppProtocol.Node {
        val idBytes = byteArrayOf(
            (id ushr SHIFT_BYTE2).toByte(),
            (id ushr SHIFT_BYTE1).toByte(),
            id.toByte(),
        )
        val children = mutableListOf(
            WhatsAppProtocol.Node(tag = "id", data = idBytes),
            WhatsAppProtocol.Node(tag = "value", data = pub32),
        )
        return if (signature != null) {
            children.add(WhatsAppProtocol.Node(tag = "signature", data = signature))
            WhatsAppProtocol.Node(tag = "skey", content = children)
        } else {
            WhatsAppProtocol.Node(tag = "key", content = children)
        }
    }

    fun parsePreKeyBundleNode(deviceId: Int, userNode: WhatsAppProtocol.Node): ParsedPreKeyBundle? {
        return try {
            if (userNode.getChildByTag("error") != null) {
                Log.status(TAG, "prekey response error for device $deviceId")
                return null
            }
            val registrationId = readRegistrationId(userNode) ?: return null
            val keysNode = userNode.getChildByTag("keys") ?: userNode
            val identityRaw = keysNode.getChildByTag("identity")?.data ?: return null
            if (identityRaw.size != IDENTITY_KEY_SIZE) return null
            val (preKeyId, preKeyPublic) = readPreKey(keysNode) ?: (null to null)
            val signed = readSignedPreKey(keysNode) ?: return null
            ParsedPreKeyBundle(
                registrationId = registrationId,
                preKeyId = preKeyId,
                preKeyPublic = preKeyPublic,
                signedPreKeyId = signed.first,
                signedPreKeyPublic = signed.second,
                signedPreKeySignature = signed.third,
                identityKey = identityRaw,
            )
        } catch (expected: Exception) {
            Log.status(TAG, "Failed to parse prekey bundle", expected)
            null
        }
    }

    private fun readRegistrationId(userNode: WhatsAppProtocol.Node): Int? {
        val regBytes = userNode.getChildByTag("registration")?.data ?: return null
        if (regBytes.size != REGISTRATION_ID_SIZE) return null
        return ((regBytes[0].toInt() and BYTE_MASK) shl SHIFT_BYTE3) or
            ((regBytes[1].toInt() and BYTE_MASK) shl SHIFT_BYTE2) or
            ((regBytes[2].toInt() and BYTE_MASK) shl SHIFT_BYTE1) or
            (regBytes[REGISTRATION_ID_SIZE - 1].toInt() and BYTE_MASK)
    }

    /** Optional one-time prekey (id + public key), or null pair when absent. */
    private fun readPreKey(keysNode: WhatsAppProtocol.Node): Pair<Int?, ByteArray?>? {
        val keyNode = keysNode.getChildByTag("key") ?: return null to null
        val preKeyId = readKeyId(keyNode) ?: return null
        val pub = keyNode.getChildByTag("value")?.data ?: return null
        if (pub.size != IDENTITY_KEY_SIZE) return null
        return preKeyId to pub
    }

    /** Signed prekey triple (id, public, signature), or null when malformed. */
    private fun readSignedPreKey(
        keysNode: WhatsAppProtocol.Node,
    ): Triple<Int, ByteArray, ByteArray>? {
        val skey = keysNode.getChildByTag("skey") ?: return null
        val signedPreKeyId = readKeyId(skey) ?: return null
        val signedPub = skey.getChildByTag("value")?.data
            ?.takeIf { it.size == IDENTITY_KEY_SIZE } ?: return null
        val signedSig = skey.getChildByTag("signature")?.data
            ?.takeIf { it.size == SIGNATURE_SIZE } ?: return null
        return Triple(signedPreKeyId, signedPub, signedSig)
    }

    private fun readKeyId(node: WhatsAppProtocol.Node): Int? {
        val idBytes = node.getChildByTag("id")?.data ?: return null
        if (idBytes.size != KEY_ID_SIZE) return null
        return ((idBytes[0].toInt() and BYTE_MASK) shl SHIFT_BYTE2) or
            ((idBytes[1].toInt() and BYTE_MASK) shl SHIFT_BYTE1) or
            (idBytes[2].toInt() and BYTE_MASK)
    }

    /** Crypto size constants and logging tag for [WhatsAppE2E]. */
    companion object {
        private const val TAG = "WhatsAppE2E"
        private const val IDENTITY_KEY_SIZE = 32
        private const val SIGNATURE_SIZE = 64
        private const val SIGNED_MESSAGE_SIZE = 33
        private const val INITIAL_PREKEY_UPLOAD = 812
        private const val REFILL_PREKEY_COUNT = 50
        private const val REGISTRATION_ID_SIZE = 4
        private const val KEY_ID_SIZE = 3
        private const val BYTE_MASK = 0xFF
        private const val VARINT_MASK = 0x7F
        private const val VARINT_CONT = 0x80
        private const val VARINT_BITS = 7
        private const val FIELD_SHIFT = 3
        private const val WIRE_TYPE_MASK = 7
        private const val SHIFT_BYTE1 = 8
        private const val SHIFT_BYTE2 = 16
        private const val SHIFT_BYTE3 = 24
        private const val PREKEY_VERSION_BYTE: Byte = 0x05

        private fun b64(s: String): ByteArray = Base64.Default.decode(s)

        /**
         * Parse the optional preKeyId from a PreKeySignalMessage (version || protobuf).
         * Minimal protobuf scan for field 1 varint.
         */
        fun parsePreKeyIdFromMessage(data: ByteArray): Int? {
            if (data.isEmpty()) return null
            // Skip version byte (high nibble version)
            var pos = 1
            while (pos < data.size) {
                val field = readFieldKey(data, pos) ?: return null
                pos = field.nextPos
                if (field.wireType == 0) {
                    // varint
                    val (value, after) = readVarint(data, pos)
                    if (after == pos) return null // truncated
                    pos = after
                    if (field.number == 1) return value?.toInt()
                } else {
                    pos = skipField(data, pos, field.wireType) ?: return null
                }
            }
            return null
        }

        private data class FieldKey(val number: Int, val wireType: Int, val nextPos: Int)

        /** Read a varint field key; null when truncated. */
        private fun readFieldKey(data: ByteArray, pos: Int): FieldKey? {
            var keyVal = 0L
            var shift = 0
            var idx = pos
            var b: Int
            do {
                if (idx >= data.size) return null
                b = data[idx].toInt() and BYTE_MASK
                keyVal = keyVal or ((b and VARINT_MASK).toLong() shl shift)
                shift += VARINT_BITS
                idx++
            } while (b and VARINT_CONT != 0)
            return FieldKey((keyVal shr FIELD_SHIFT).toInt(), (keyVal and WIRE_TYPE_MASK.toLong()).toInt(), idx)
        }

        /** Read a varint value; null value when truncated. Returns (value, nextPos). */
        private fun readVarint(data: ByteArray, pos: Int): Pair<Long?, Int> {
            var v = 0L
            var shift = 0
            var p = pos
            while (p < data.size) {
                val b = data[p].toInt() and BYTE_MASK
                v = v or ((b and VARINT_MASK).toLong() shl shift)
                p++
                if (b and VARINT_CONT == 0) break
                shift += VARINT_BITS
            }
            return (if (p > pos) v else null) to p
        }

        /** Skip one field payload; null when truncated/unsupported. */
        private fun skipField(data: ByteArray, pos: Int, wireType: Int): Int? {
            if (wireType != 2) return null // only length-delimited expected here
            val (len, after) = readVarint(data, pos)
            val length = len ?: return null
            val end = after + length.toInt()
            if (end > data.size) return null
            return end
        }

        /**
         * XEdDSA signature over 0x05||signedPreKeyPub using raw 32-byte identity private.
         * Uses Rust for constant-time signing.
         */
        fun signSignedPreKey(identityPrivate32: ByteArray, signedPreKeyPublic32: ByteArray): ByteArray {
            val message = ByteArray(SIGNED_MESSAGE_SIZE)
            message[0] = PREKEY_VERSION_BYTE
            System.arraycopy(signedPreKeyPublic32, 0, message, 1, IDENTITY_KEY_SIZE)
            return RustWhatsAppCrypto.sign(identityPrivate32, message)
                ?: throw IllegalStateException("Rust sign returned null")
        }
    }
}
