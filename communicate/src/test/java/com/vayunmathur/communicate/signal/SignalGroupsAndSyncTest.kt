package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.SignalContactSync
import com.vayunmathur.communicate.data.signal.SignalGroups
import com.vayunmathur.communicate.data.signal.transport.SignalGroupsApi
import com.vayunmathur.communicate.data.signal.transport.SignalKeysApi
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 1 (w6 slice 2/6): pure group + contact-sync + pre-key-parse coverage.
 *
 * - [SignalGroupsApi.parseCredentials] / [titleChange] / [addMembersChange] / [groupMemberInfo] /
 *   [decryptGroup] run fully offline against hand-built fixtures.
 * - [SignalContactSync] E.164 helpers + [SignalGroups] create-request JSON shape.
 * - [SignalKeysApi.parse] against hand-built pre-key responses.
 */
@RunWith(RobolectricTestRunner::class)
class SignalGroupsAndSyncTest {

    private val masterKey = ByteArray(32) { (it + 1).toByte() }
    private val secretParamsBytes = SignalGroups.secretParamsFor(masterKey)
    private val secretParams = GroupSecretParams(secretParamsBytes)

    private fun b64(bytes: ByteArray): String =
        kotlin.io.encoding.Base64.Default.encode(bytes).trimEnd('=')

    // ---- groups api: credentials parse ----

    @Test
    fun parseCredentials_acceptsValidList() {
        // Padded base64, as the decoder requires.
        val cred = kotlin.io.encoding.Base64.Default.encode(ByteArray(32) { 1 })
        val body = """{"credentials":[{"redemptionTime":1700000000,"credential":"$cred"}]}"""
        val parsed = SignalGroupsApi.parseCredentials(body)
        assertEquals(1, parsed.size)
        assertEquals(1700000000L, parsed[0].redemptionTimeSeconds)
        assertTrue(parsed[0].credential.contentEquals(ByteArray(32) { 1 }))
    }

    @Test
    fun parseCredentials_skipsBadEntriesAndRejectsJunk() {
        assertTrue(SignalGroupsApi.parseCredentials("""{"credentials":[]}""").isEmpty())
        assertTrue(SignalGroupsApi.parseCredentials("""{}""").isEmpty())
        assertTrue(SignalGroupsApi.parseCredentials("nope", warn = {}).isEmpty())
        // Bad base64 entry is skipped, good one kept.
        val goodCred = kotlin.io.encoding.Base64.Default.encode(byteArrayOf(1))
        val good = """{"credentials":[
          {"redemptionTime":1,"credential":"not base64!!!"},
          {"redemptionTime":2,"credential":"$goodCred"}
        ]}"""
        val parsed = SignalGroupsApi.parseCredentials(good, warn = {})
        assertEquals(1, parsed.size)
        assertEquals(2L, parsed[0].redemptionTimeSeconds)
    }

    @Test
    fun parseCredentials_rejectsUnpaddedBase64() {
        // Documents current behavior: unlike the pre-key parser (which restores padding),
        // the credential parser requires padded base64. An unpadded 43-char credential —
        // the wire-standard form — is skipped rather than decoded.
        val unpadded = kotlin.io.encoding.Base64.Default.encode(ByteArray(32) { 1 }).trimEnd('=')
        val parsed = SignalGroupsApi.parseCredentials(
            """{"credentials":[{"redemptionTime":1,"credential":"$unpadded"}]}""", warn = {},
        )
        assertTrue(parsed.isEmpty())
    }

    // ---- groups api: title / members change builders ----

    @Test
    fun titleChange_encryptsTitleAndSetsVersion() {
        val actions = SignalGroupsApi.titleChange(secretParams, "Weekend trip", 4)!!
        assertEquals(4, actions.version)
        assertTrue(actions.hasModifyTitle())
        // Title blob decrypts back.
        val cipher = org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher(secretParams)
        val blob = org.signal.storageservice.storage.protos.groups.GroupAttributeBlob.parseFrom(
            cipher.decryptBlob(actions.modifyTitle.title.toByteArray()),
        )
        assertEquals("Weekend trip", blob.title)
    }

    @Test
    fun addMembersChange_encryptsAcisAndSkipsJunk() {
        val alice = "3f0e5a2c-1111-2222-3333-444455556666"
        val actions = SignalGroupsApi.addMembersChange(secretParams, listOf(alice, "nope"), 2)!!
        assertEquals(2, actions.version)
        assertEquals(1, actions.addMembersCount)
        // Member decrypts back to Alice (ServiceId.toString renders as <ACI:...>).
        val cipher = org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher(secretParams)
        val decrypted = cipher.decrypt(
            org.signal.libsignal.zkgroup.groups.UuidCiphertext(actions.getAddMembers(0).added.userId.toByteArray()),
        )
        assertEquals("<ACI:$alice>", decrypted.toString())
    }

    @Test
    fun addMembersChange_returnsNullWhenNobodyValid() {
        assertNull(SignalGroupsApi.addMembersChange(secretParams, listOf("nope"), 1))
        assertNull(SignalGroupsApi.addMembersChange(secretParams, emptyList(), 1))
    }

    @Test
    fun groupMemberInfo_pairsUuidWithCiphertext() {
        val alice = "3f0e5a2c-1111-2222-3333-444455556666"
        val info = SignalGroupsApi.groupMemberInfo(secretParams, listOf(alice, "junk"))
        assertEquals(1, info.size)
        assertEquals(java.util.UUID.fromString(alice), info[0].first)
        assertTrue(info[0].second.isNotEmpty())
        assertTrue(SignalGroupsApi.groupMemberInfo(secretParams, emptyList()).isEmpty())
    }

    @Test
    fun decryptGroup_recoversTitleMembersRevision() {
        val alice = "3f0e5a2c-1111-2222-3333-444455556666"
        val cipher = org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher(secretParams)
        val titleBlob = org.signal.storageservice.storage.protos.groups.GroupAttributeBlob.newBuilder()
            .setTitle("Hiking").build().toByteArray()
        val group = org.signal.storageservice.storage.protos.groups.Group.newBuilder()
            .setTitle(com.google.protobuf.ByteString.copyFrom(cipher.encryptBlob(titleBlob)))
            .setVersion(9)
            .addMembers(
                org.signal.storageservice.storage.protos.groups.Member.newBuilder()
                    .setUserId(com.google.protobuf.ByteString.copyFrom(
                        cipher.encrypt(org.signal.libsignal.protocol.ServiceId.Aci.parseFromString(alice)).serialize(),
                    ))
                    .setRole(org.signal.storageservice.storage.protos.groups.Member.Role.DEFAULT),
            )
            .addMembersPendingProfileKey(
                org.signal.storageservice.storage.protos.groups.MemberPendingProfileKey.getDefaultInstance(),
            )
            .addMembersPendingProfileKey(
                org.signal.storageservice.storage.protos.groups.MemberPendingProfileKey.getDefaultInstance(),
            )
            .build()
        val state = SignalGroupsApi.decryptGroup(secretParams, group)!!
        assertEquals("Hiking", state.title)
        assertEquals(9, state.revision)
        assertEquals(listOf("<ACI:$alice>"), state.memberAcis)
        assertEquals(2, state.pendingCount)
    }

    @Test
    fun createGroupRequest_carriesMasterKeyTitleMembers() {
        val body = SignalGroups.buildCreateGroupRequest(masterKey, "T", listOf("a", "b"), revision = 1)
        val obj = org.json.JSONObject(body.toString(Charsets.UTF_8))
        assertEquals("T", obj.getString("title"))
        assertEquals(1, obj.getInt("revision"))
        assertEquals(2, obj.getJSONArray("members").length())
        assertTrue(obj.getString("masterKey").isNotEmpty())
    }

    @Test
    fun basicAuth_usesAciLoginWhenPresent() {
        val auth = com.vayunmathur.communicate.data.signal.SignalAuthData(
            phoneNumber = "+1", aci = "aci-1", deviceId = 2, password = "pw",
        )
        val decoded = String(
            android.util.Base64.decode(SignalGroups.basicAuth(auth), android.util.Base64.NO_WRAP),
            Charsets.UTF_8,
        )
        assertEquals("aci-1.2:pw", decoded)
        val phoneAuth = com.vayunmathur.communicate.data.signal.SignalAuthData(
            phoneNumber = "+1555", password = "pw2",
        )
        val decodedPhone = String(
            android.util.Base64.decode(SignalGroups.basicAuth(phoneAuth), android.util.Base64.NO_WRAP),
            Charsets.UTF_8,
        )
        assertEquals("+1555:pw2", decodedPhone)
    }

    @Test
    fun generateMasterKey_producesFreshThirtyTwoBytes() {
        val (k1, sp1) = SignalGroups.generateMasterKeyAndSecretParams()
        val (k2, _) = SignalGroups.generateMasterKeyAndSecretParams()
        assertEquals(32, k1.size)
        assertTrue(sp1.isNotEmpty())
        assertFalse(k1.contentEquals(k2))
        // Secret params derive deterministically.
        assertTrue(
            GroupSecretParams.deriveFromMasterKey(GroupMasterKey(k1)).serialize().contentEquals(sp1),
        )
    }

    @Test
    fun encryptServiceId_roundTripsAndRejectsJunk() {
        val alice = "3f0e5a2c-1111-2222-3333-444455556666"
        val ct = SignalGroups.encryptServiceId(secretParamsBytes, alice)
        assertNotNull(ct)
        val back = org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher(secretParams)
            .decrypt(org.signal.libsignal.zkgroup.groups.UuidCiphertext(ct))
        assertEquals("<ACI:$alice>", back.toString())
        assertNull(SignalGroups.encryptServiceId(secretParamsBytes, "junk"))
    }

    @Test
    fun encryptGroupBlob_roundTrips() {
        val blob = org.signal.storageservice.storage.protos.groups.GroupAttributeBlob.newBuilder()
            .setTitle("T").setDescriptionText("D").build().toByteArray()
        val enc = SignalGroups.encryptGroupBlob(secretParamsBytes, blob)
        val dec = org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher(secretParams).decryptBlob(enc)
        assertTrue(dec.contentEquals(blob))
    }

    // ---- contact sync pure helpers ----

    @Test
    fun normalizeE164_formatsUsNumbers() {
        assertEquals("+14155552671", SignalContactSync.normalizeE164("(415) 555-2671", "US"))
        assertEquals("+14155552671", SignalContactSync.normalizeE164("+14155552671", "US"))
        assertNull(SignalContactSync.normalizeE164("not a number", "US"))
        assertNull(SignalContactSync.normalizeE164("", "US"))
    }

    @Test
    fun e164Uint64_roundTrips() {
        val bytes = SignalContactSync.e164ToUint64Bytes("+14155552671")
        assertEquals(8, bytes.size)
        val num = java.nio.ByteBuffer.wrap(bytes).long
        assertEquals("+14155552671", SignalContactSync.uint64ToE164(num))
        // Non-numeric gives zero bytes.
        assertEquals(0L, java.nio.ByteBuffer.wrap(SignalContactSync.e164ToUint64Bytes("abc")).long)
    }

    // ---- keys api parse ----

    private fun bundleJson(
        deviceId: Int = 1, regId: Int = 5,
        withOneTime: Boolean = true, withKyber: Boolean = true, withSigned: Boolean = true,
    ): String {
        val key = { n: Int -> "\"publicKey\":\"${b64(ByteArray(33) { n.toByte() })}\",\"keyId\":$n,\"signature\":\"${b64(ByteArray(64) { 7 })}\"" }
        val parts = mutableListOf(
            "\"deviceId\":$deviceId,\"registrationId\":$regId",
            if (withSigned) "\"signedPreKey\":{${key(11)}}" else "",
            if (withOneTime) "\"preKey\":{\"publicKey\":\"${b64(ByteArray(33) { 3 })}\",\"keyId\":21}" else "",
            if (withKyber) "\"pqPreKey\":{${key(31)}}" else "",
        ).filter { it.isNotEmpty() }.joinToString(",")
        return "{$parts}"
    }

    @Test
    fun keysParse_acceptsFullBundle() {
        val body = """{"identityKey":"${b64(ByteArray(33) { 1 })}","devices":[${bundleJson()}]}"""
        val parsed = SignalKeysApi.parse(body, "aci")
        assertEquals(1, parsed.size)
        assertEquals(1, parsed[0].deviceId)
        assertEquals(5, parsed[0].bundle.registrationId)
        assertEquals(21, parsed[0].bundle.preKeyId)
        assertEquals(11, parsed[0].bundle.signedPreKeyId)
        assertEquals(31, parsed[0].bundle.kyberPreKeyId)
        assertEquals(33, parsed[0].bundle.identityKey.size)
    }

    @Test
    fun keysParse_skipsDevicesWithoutKyberOrSigned() {
        val body = """{"identityKey":"${b64(ByteArray(33) { 1 })}","devices":[
          ${bundleJson(deviceId = 1, withKyber = false)},
          ${bundleJson(deviceId = 2, withSigned = false)},
          ${bundleJson(deviceId = 3)}
        ]}"""
        val parsed = SignalKeysApi.parse(body, "aci", warn = {})
        assertEquals(listOf(3), parsed.map { it.deviceId })
    }

    @Test
    fun keysParse_oneTimeKeyIsOptional() {
        val body = """{"identityKey":"${b64(ByteArray(33) { 1 })}","devices":[${bundleJson(withOneTime = false)}]}"""
        val parsed = SignalKeysApi.parse(body, "aci")
        assertEquals(1, parsed.size)
        assertNull(parsed[0].bundle.preKeyId)
        assertNull(parsed[0].bundle.preKeyPublic)
    }

    @Test
    fun keysParse_rejectsJunkAndSkipsBadDevices() {
        assertTrue(SignalKeysApi.parse("junk", "aci", warn = {}).isEmpty())
        assertTrue(SignalKeysApi.parse("""{"devices":[]}""", "aci", warn = {}).isEmpty())
        assertTrue(SignalKeysApi.parse("""{"identityKey":"??"}""", "aci", warn = {}).isEmpty())
        // Device id 0 and non-object entries are skipped.
        val body = """{"identityKey":"${b64(ByteArray(33) { 1 })}","devices":[42,${bundleJson(deviceId = 0)}]}"""
        assertTrue(SignalKeysApi.parse(body, "aci", warn = {}).isEmpty())
    }
}
