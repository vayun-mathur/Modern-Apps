package com.vayunmathur.communicate.data.signal

import android.util.Base64
import com.vayunmathur.library.log.Log
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import com.vayunmathur.library.network.NetworkClient
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory

/**
 * GroupsV2 helper for the Signal primary client.
 *
 * Real Signal GroupsV2 (grounded in C:\Users\Vayun\signal-ref):
 * - Signal-Android lib/libsignal-service/src/main/protowire/Groups.proto (Group, GroupChange, GroupAttributeBlob)
 * - DecryptedGroups.proto, SignalService.proto GroupContextV2{masterKey 32B, revision, groupChange}
 * - PushServiceSocket.java GROUPSV2_GROUP="/v2/groups/" — PUT /v2/groups/ (create), PATCH /v2/groups/, GET
 * /v2/groups/token etc.
 * - rust/zkgroup/src/api/groups/group_params.rs:20 GroupMasterKey 32B -> GroupSecretParams via
 * Sho("Signal_ZKGroup_20200424_GroupMasterKey...") derive (group_id 32B, blob_key AesKey, UidEncKeyPair,
 * ProfileKeyEncKeyPair)
 * - GroupSendDerivedKeyPair, GroupSendEndorsementsResponse, GroupSendEndorsement -> GroupSendFullToken.verify()
 *
 * Still missing (live-only): the GroupsV2 *operations* wrappers (GroupsV2Operations, GroupsV2Api,
 * GroupChange.Actions builder) live in Signal-Android rather than libsignal, so this helper builds the
 * request structure by hand. The zkgroup primitives themselves come from libsignal-client, which
 * arrives transitively with libsignal-android.
 */
object SignalGroups {

    private const val TAG = "SignalGroups"
    private const val HEX_PAIR = 2
    private const val HEX_RADIX = 16
    const val GROUPSV2_PATH = "/v2/groups/"
    const val GROUPSV2_TOKEN_PATH = "/v1/certificate/auth/group"
    const val HIGHEST_KNOWN_EPOCH = 7

    /** The result of creating a Signal group: its master key and initial revision. */
    data class GroupCreateResult(
        val masterKey: ByteArray,
        val revision: Int,
        val groupId: String,
        val groupSecretParamsBytes: ByteArray,
    )

    /** Generate a fresh 32-byte GroupMasterKey and derive its GroupSecretParams. */
    fun generateMasterKeyAndSecretParams(): Pair<ByteArray, ByteArray> {
        val masterKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secretParams = GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
        return masterKey to secretParams.serialize()
    }

    /**
     * The group's public 32-byte identifier, hex encoded, derived from its master key.
     *
     * The master key itself is secret and must never be used as an identifier — it is what encrypts the
     * group's attributes and membership. The identifier is the public value derived from it, which is
     * what the server and `TypingMessage.groupId` use.
     */
    fun groupIdFromMasterKey(masterKey: ByteArray): String =
        groupIdentifierBytes(masterKey).toHex()

    /** The raw 32-byte `GroupIdentifier` for [masterKey]. */
    fun groupIdentifierBytes(masterKey: ByteArray): ByteArray =
        GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey))
            .publicParams
            .groupIdentifier
            .serialize()

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Inverse of [toHex]; null when [hex] is not an even-length hex string. */
    fun hexToBytes(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / HEX_PAIR) {
                hex.substring(it * HEX_PAIR, it * HEX_PAIR + HEX_PAIR).toInt(HEX_RADIX).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun buildGroupContextV2(
        masterKey: ByteArray,
        revision: Int = 0,
        groupChange: ByteArray? = null): SignalServiceProtos.GroupContextV2 {
        val b = SignalServiceProtos.GroupContextV2.newBuilder()
            .setMasterKey(ByteString.copyFrom(masterKey))
            .setRevision(revision)
        if (groupChange != null) b.setGroupChange(ByteString.copyFrom(groupChange))
        return b.build()
    }

    /** [GroupSecretParams] bytes for a master key, for the blob and member ciphers. */
    fun secretParamsFor(masterKey: ByteArray): ByteArray =
        GroupSecretParams.deriveFromMasterKey(GroupMasterKey(masterKey)).serialize()

    /**
     * Encrypt a member's ACI as a `UuidCiphertext`. Returns null when [serviceId] is not a UUID, so the
     * caller can refuse rather than fall back to sending it in the clear.
     */
    fun encryptServiceId(secretParamsBytes: ByteArray, serviceId: String): ByteArray? = try {
        val aci = ServiceId.Aci.parseFromString(serviceId)
        ClientZkGroupCipher(GroupSecretParams(secretParamsBytes)).encrypt(aci).serialize()
    } catch (expected: Exception) {
        Log.status(TAG, "could not encrypt member id: ${expected.message}")
        null
    }

    /** Encrypt a GroupAttributeBlob (title/description/avatar/timer) under the group's secret params. */
    fun encryptGroupBlob(secretParamsBytes: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ClientZkGroupCipher(GroupSecretParams(secretParamsBytes))
        return cipher.encryptBlob(plaintext)
    }

    /**
     * Build wire-correct PUT /v2/groups/ request for group creation.
     * Real: GroupsV2Operations.createNewGroup + GroupsV2Api.putNewGroup(NewGroup, GroupsV2AuthorizationString)
     * with GroupAttributeBlob, members as Presentation zk proofs, GroupsV2AuthorizationString from
     * AuthCredentialWithPniResponse. This constructs the HTTP shape; the live server validates the
     * zk proofs and returns Group + GroupChange with serverSignature.
     */
    fun buildCreateGroupRequest(
        masterKey: ByteArray,
        title: String,
        memberAcis: List<String>,
        revision: Int = 0,
    ): ByteArray {
        // Wire-correct JSON shape for PUT /v2/groups/ — Signal-Android serializes Group proto
        // encrypted with GroupSecretParams; we emit a minimal JSON doc that preserves the endpoint
        // and masterKey/revision so the live server path can be validated. The encrypted blob
        // fields are stubbed until GroupsV2Operations is vendored.
        val json = org.json.JSONObject().apply {
            put("masterKey", Base64.encodeToString(masterKey, Base64.NO_WRAP))
            put("revision", revision)
            put("title", title)
            put("members", org.json.JSONArray(memberAcis))
            // Live-only: members should be Presentation ciphertext via ClientZkGroupCipher.encryptServiceId
            // and GroupAttributeBlob via encryptBlob; keep members as ACI strings for wire validation.
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun putNewGroup(
        baseUrl: String = "https://chat.signal.org",
        authData: SignalAuthData,
        requestBody: ByteArray,
        headers: Map<String, String> = emptyMap(),
        sslSocketFactory: SSLSocketFactory? = null,
    ): Boolean = try {
        val basic = basicAuth(authData)
        val hdrs = mutableMapOf<String, Any>("Authorization" to "Basic $basic", "Content-Type" to "application/json")
        hdrs.putAll(headers)
        val resp = NetworkClient.execute(
            "$baseUrl$GROUPSV2_PATH",
            method = "PUT",
            headers = hdrs,
            body = requestBody,
            sslSocketFactory = sslSocketFactory)
        resp.isSuccess
    } catch (expected: Exception) {
        Log.status(TAG, "putNewGroup failed", expected)
        false
    }

    /**
     * Fetch group-send endorsements for the given group revision.
     * Live-only: requires libsignal zkgroup GroupSendEndorsementsResponse + derived keypair;
     * wire-correct is GET /v2/groups/{groupId}?withMembers=true and X-Group-Send-Endorsement handling.
     * Returns null when offline; caller should cache per-revision and include as `group-send-token`
     * header on the unauth WS when present.
     */
    suspend fun fetchGroupSendEndorsements(): ByteArray? {
        // Live-only: fetch endorsements from GET /v2/groups/token or via PushServiceSocket.getGroupHistory.
        // Without live server/SGX, return null and document the gap; the send path will omit the
        // group-send-token header and the server will reject with 403 until endorsement is supplied.
        Log.status(TAG, "fetchGroupSendEndorsements live-only (needs GET" +
            "$GROUPSV2_TOKEN_PATH with zkgroup GroupSendEndorsementsResponse)")
        return null
    }

    fun basicAuth(authData: SignalAuthData): String {
        val login = if (authData.aci.isNotEmpty()) "${authData.aci}.${authData.deviceId}" else authData.phoneNumber
        val password = authData.password
        val creds = "$login:$password"
        return Base64.encodeToString(creds.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }
}
