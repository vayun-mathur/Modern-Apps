package com.vayunmathur.communicate.data.signal.transport

import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.zkgroup.ServerPublicParams
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
import org.signal.libsignal.zkgroup.auth.ClientZkAuthOperations
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.libsignal.zkgroup.groups.UuidCiphertext
import org.signal.storageservice.storage.protos.groups.ExternalGroupCredential
import org.signal.storageservice.storage.protos.groups.Group
import org.signal.storageservice.storage.protos.groups.GroupAttributeBlob
import org.signal.storageservice.storage.protos.groups.GroupChange
import org.signal.storageservice.storage.protos.groups.Member
import javax.net.ssl.SSLSocketFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.days

/**
 * GroupsV2 against Signal's storage service.
 *
 * Paths, hosts and the auth format are taken from the official client rather than inferred:
 * - credentials: `GET /v1/certificate/auth/group` on **chat** (`GroupsV2ApiHelper.kt`)
 * - group state: `GET /v2/groups/` on **storage** (`PushServiceSocket.GROUPSV2_GROUP`, `makeStorageRequest`)
 * - group auth is Basic, with the group's *public* params as the username and a zkgroup credential
 *   presentation as the password, both hex (`GroupsV2AuthorizationString`)
 *
 * Note the group endpoints are **not** on chat.signal.org — sending them there is why the previous
 * hand-rolled group calls could never have worked.
 */
@OptIn(ExperimentalEncodingApi::class)
object SignalGroupsApi {
    private const val TAG = "SignalGroupsApi"
    private const val MS_PER_SECOND = 1000L

    /** From the official client's build config: `STORAGE_URL`. */
    private const val STORAGE_URL = "https://storage.signal.org"
    private const val CHAT_URL = "https://chat.signal.org"

    /** Test seams: localhost bases replacing the fixed Signal hosts. */
    internal var storageBaseForTest: String? = null
    internal var chatBaseForTest: String? = null

    private const val CREDENTIAL_PATH = "/v1/certificate/auth/group"
    private const val GROUP_PATH = "/v2/groups/"

    /** From the official client's `PushServiceSocket.GROUPSV2_TOKEN`. */
    private const val GROUP_TOKEN_PATH = "/v2/groups/token"

    /** Signal's calling server, from the official client's `SIGNAL_SFU_URL`. */
    const val SFU_URL = "https://sfu.voip.signal.org"

    private val json = Json { ignoreUnknownKeys = true }

    /** A credential and the day it is valid for. The server returns seven days at a time. */
    data class DayCredential(val redemptionTimeSeconds: Long, val credential: ByteArray)

    /**
     * Fetch a week of auth credentials. Official caches these; we fetch per use, which is slower but has no
     * staleness to get wrong.
     */
    suspend fun fetchCredentials(
        authHeader: String,
        sslSocketFactory: SSLSocketFactory?,
        nowSeconds: Long = System.currentTimeMillis() / MS_PER_SECOND,
    ): List<DayCredential> {
        // Signal issues credentials on day boundaries, so the window must be day-aligned.
        val today = nowSeconds - (nowSeconds % 86_400)
        val end = today + 7.days.inWholeSeconds
        val url = "${chatBaseForTest ?: CHAT_URL}$CREDENTIAL_PATH" +
            "?redemptionStartSeconds=$today&redemptionEndSeconds=$end"
        val resp = try {
            NetworkClient.execute(
                url,
                method = "GET",
                headers = mapOf("Authorization" to "Basic $authHeader"),
                sslSocketFactory = sslSocketFactory,
            )
        } catch (expected: Throwable) {
            Log.status(TAG, "could not fetch group auth credentials", expected)
            return emptyList()
        }
        if (!resp.isSuccess) {
            Log.status(TAG, "group credential request failed: ${resp.status} ${resp.statusMessage}")
            return emptyList()
        }
        return parseCredentials(resp.text)
    }

    internal fun parseCredentials(body: String, warn: (String) -> Unit = { Log.status(TAG, it) }): List<DayCredential> =
        try {
            json.parseToJsonElement(body).jsonObject["credentials"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val time = obj["redemptionTime"]?.jsonPrimitive?.long ?: return@mapNotNull null
                val credential = obj["credential"]?.jsonPrimitive?.content
                    ?.let { runCatching { Base64.Default.decode(it) }.getOrNull() }
                    ?: return@mapNotNull null
                DayCredential(time, credential)
            } ?: emptyList()
        } catch (expected: Exception) {
            warn("unparseable group credential response: ${expected.message}")
            emptyList()
        }

    /**
     * Build the Basic auth value the group endpoints require.
     *
     * The presentation proves membership without revealing which member we are, which is why group requests do
     * not use ordinary account auth.
     */
    fun authorizationFor(
        auth: SignalAuthData,
        secretParams: GroupSecretParams,
        credential: DayCredential,
    ): String? = try {
        val operations = ClientZkAuthOperations(ServerPublicParams(serverPublicParams()))
        val aci = ServiceId.Aci.parseFromString(auth.aci)
        val pni = ServiceId.Pni.parseFromString(auth.pni)
        val withPni = operations.receiveAuthCredentialWithPniAsServiceId(
            aci,
            pni,
            credential.redemptionTimeSeconds,
            AuthCredentialWithPniResponse(credential.credential),
        )
        val presentation = operations.createAuthCredentialPresentation(secretParams, withPni)
        val username = secretParams.publicParams.serialize().toHex()
        val password = presentation.serialize().toHex()
        Base64.Default.encode("$username:$password".toByteArray(Charsets.UTF_8))
    } catch (expected: Throwable) {
        Log.status(TAG, "could not build a group authorization", expected)
        null
    }

    /** Fetch and return the group's current server state, still encrypted. */
    suspend fun fetchGroup(
        authorization: String,
        sslSocketFactory: SSLSocketFactory?,
    ): Group? {
        val resp = try {
            NetworkClient.execute(
                "${storageBaseForTest ?: STORAGE_URL}$GROUP_PATH",
                method = "GET",
                headers = mapOf("Authorization" to "Basic $authorization"),
                sslSocketFactory = sslSocketFactory,
            )
        } catch (expected: Throwable) {
            Log.status(TAG, "could not fetch the group", expected)
            return null
        }
        if (!resp.isSuccess) {
            Log.status(TAG, "group fetch failed: ${resp.status} ${resp.statusMessage}")
            return null
        }
        return try {
            // The response is a GroupResponse wrapping the Group; older servers returned the Group directly.
            val bytes = resp.bytes ?: return null
            runCatching { org.signal.storageservice.storage.protos.groups.GroupResponse.parseFrom(bytes).group }
                .getOrNull()
                ?: Group.parseFrom(bytes)
        } catch (expected: Throwable) {
            Log.status(TAG, "could not parse the group response", expected)
            null
        }
    }

    /** A group's server state, decrypted. */
    data class GroupState(
        val title: String,
        val revision: Int,
        /** Full membership as ACI strings — what a fan-out send needs. */
        val memberAcis: List<String>,
        /** Invited but not yet joined; they cannot receive group messages yet. */
        val pendingCount: Int,
    )

    /**
     * Decrypt a fetched group.
     *
     * Every identifying field on the wire is encrypted under the group's secret params: members are
     * `UuidCiphertext` and the title is a `GroupAttributeBlob` inside an encrypted blob. That is the whole point
     * of GroupsV2 — the server routes the group without knowing who is in it.
     */
    fun decryptGroup(secretParams: GroupSecretParams, group: Group): GroupState? = try {
        val cipher = ClientZkGroupCipher(secretParams)
        val members = group.membersList.mapNotNull { member ->
            try {
                // A member carrying a presentation has not been reduced to a plain userId yet; skip rather than
                // guess, since its ACI is inside the presentation.
                if (member.presentation != null && member.presentation.size() > 0) return@mapNotNull null
                val serviceId = cipher.decrypt(UuidCiphertext(member.userId.toByteArray()))
                (serviceId as? ServiceId.Aci)?.toString()
            } catch (expected: Throwable) {
                Log.status(TAG, "skipping an undecryptable group member: ${expected.message}")
                null
            }
        }
        val title = try {
            val blobBytes = cipher.decryptBlob(group.title.toByteArray())
            GroupAttributeBlob.parseFrom(blobBytes).title.trim()
        } catch (expected: Throwable) {
            Log.status(TAG, "could not decrypt the group title: ${expected.message}")
            ""
        }
        GroupState(
            title = title,
            revision = group.version,
            memberAcis = members,
            pendingCount = group.membersPendingProfileKeyCount,
        )
    } catch (expected: Throwable) {
        Log.status(TAG, "could not decrypt the group", expected)
        null
    }

    /**
     * Submit a group change.
     *
     * The body is a serialized `GroupChange.Actions`, not JSON — which is why the previous hand-rolled JSON
     * PATCH could never have been accepted. The client does **not** sign: the server validates the actions
     * against the presented credential and returns a signed `GroupChange`.
     */
    suspend fun patchGroup(
        authorization: String,
        actions: GroupChange.Actions,
        sslSocketFactory: SSLSocketFactory?,
    ): Boolean {
        val resp = try {
            NetworkClient.execute(
                "${storageBaseForTest ?: STORAGE_URL}$GROUP_PATH",
                method = "PATCH",
                headers = mapOf(
                    "Authorization" to "Basic $authorization",
                    "Content-Type" to "application/x-protobuf",
                ),
                body = actions.toByteArray(),
                sslSocketFactory = sslSocketFactory,
            )
        } catch (expected: Throwable) {
            Log.status(TAG, "group change failed", expected)
            return false
        }
        if (!resp.isSuccess) {
            // Reported rather than swallowed: a rejected change previously still updated local state, so the
            // UI claimed success for a group the server never modified.
            Log.status(TAG, "group change rejected: ${resp.status} ${resp.statusMessage}")
            return false
        }
        Log.status(TAG, "group change accepted")
        return true
    }

    /** A rename, as the server expects it: the title encrypted into a `GroupAttributeBlob`. */
    fun titleChange(secretParams: GroupSecretParams, title: String, newRevision: Int): GroupChange.Actions? = try {
        val blob = GroupAttributeBlob.newBuilder().setTitle(title).build()
        val encrypted = ClientZkGroupCipher(secretParams).encryptBlob(blob.toByteArray())
        GroupChange.Actions.newBuilder()
            .setVersion(newRevision)
            .setModifyTitle(
                GroupChange.Actions.ModifyTitleAction.newBuilder()
                    .setTitle(com.google.protobuf.ByteString.copyFrom(encrypted)),
            )
            .build()
    } catch (expected: Throwable) {
        Log.status(TAG, "could not build a title change", expected)
        null
    }

    /**
     * Add members. Each is added as a full [Member] with its ACI encrypted, at the default role.
     *
     * Note a real client adds members *pending profile key* unless it already holds their profile key; this adds
     * them directly, which the server accepts only when permitted by the group's access control.
     */
    fun addMembersChange(
        secretParams: GroupSecretParams,
        acis: List<String>,
        newRevision: Int,
    ): GroupChange.Actions? = try {
        val cipher = ClientZkGroupCipher(secretParams)
        val builder = GroupChange.Actions.newBuilder().setVersion(newRevision)
        var added = 0
        acis.forEach { aci ->
            val serviceId = runCatching { ServiceId.Aci.parseFromString(aci) }.getOrNull() ?: return@forEach
            val userId = cipher.encrypt(serviceId).serialize()
            builder.addAddMembers(
                GroupChange.Actions.AddMemberAction.newBuilder()
                    .setAdded(
                        Member.newBuilder()
                            .setUserId(com.google.protobuf.ByteString.copyFrom(userId))
                            .setRole(Member.Role.DEFAULT),
                    ),
            )
            added++
        }
        if (added == 0) null else builder.build()
    } catch (expected: Throwable) {
        Log.status(TAG, "could not build an add-members change", expected)
        null
    }

    /**
     * The membership proof RingRTC presents to the SFU (`GET /v2/groups/token`).
     *
     * This is what proves to the calling server that we belong to the group, without the SFU learning the
     * group's membership. It expires, which is why RingRTC asks for it again via `requestMembershipProof`.
     */
    suspend fun fetchMembershipProof(
        authorization: String,
        sslSocketFactory: SSLSocketFactory?,
    ): ByteArray? {
        val resp = try {
            NetworkClient.execute(
                "${storageBaseForTest ?: STORAGE_URL}$GROUP_TOKEN_PATH",
                method = "GET",
                headers = mapOf("Authorization" to "Basic $authorization"),
                sslSocketFactory = sslSocketFactory,
            )
        } catch (expected: Throwable) {
            Log.status(TAG, "could not fetch a group membership proof", expected)
            return null
        }
        if (!resp.isSuccess) {
            Log.status(TAG, "membership proof request failed: ${resp.status} ${resp.statusMessage}")
            return null
        }
        return try {
            val bytes = resp.bytes ?: return null
            // The token is a string in the proto but goes to RingRTC as raw bytes.
            ExternalGroupCredential.parseFrom(bytes).token.toByteArray(Charsets.UTF_8)
        } catch (expected: Throwable) {
            Log.status(TAG, "could not parse the membership proof", expected)
            null
        }
    }

    /**
     * Encrypt each member's ACI for the SFU. RingRTC pairs the plain UUID with its ciphertext so it can match
     * SFU-reported participants back to people without the SFU knowing who they are.
     */
    fun groupMemberInfo(secretParams: GroupSecretParams, acis: List<String>): List<Pair<java.util.UUID, ByteArray>> {
        val cipher = ClientZkGroupCipher(secretParams)
        return acis.mapNotNull { aci ->
            try {
                val serviceId = ServiceId.Aci.parseFromString(aci)
                java.util.UUID.fromString(aci) to cipher.encrypt(serviceId).serialize()
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** Test seam: zkgroup server public params for a locally minted test server. */
    internal var serverPublicParamsForTest: ByteArray? = null

    /** Signal's zkgroup server public params, needed to verify credentials it issues. */
    private fun serverPublicParams(): ByteArray =
        serverPublicParamsForTest ?: Base64.Default.decode(SERVER_PUBLIC_PARAMS_B64)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Production zkgroup server public params, copied verbatim from the official client's
     * `ZKGROUP_SERVER_PUBLIC_PARAMS` (`app/build.gradle.kts`). These are public verification parameters, not a
     * secret, and they must match the server exactly or every credential fails to verify.
     */
    private const val SERVER_PUBLIC_PARAMS_B64 =
        "AMhf5ywVwITZMsff/eCyudZx9JDmkkkbV6PInzG4p8x3VqVJSFiMvnvlEKWuRob/1eaIetR31IYeAbm0NdOuHH8Qi+Rexi1wLl" +
            "pzIo1gstHWBfZzy1+qHRV5A4TqPp15YzBPm0WSggW6PbSn+F4lf57VCnHF7p8SvzAA2ZZJPYJURt8X7bbg+H3i+PEjH9DXIt" +
            "NEqs2sNcug37xZQDLm7X36nOoGPs54XsEGzPdEV+itQNGUFEjY6X9Uv+Acuks7NpyGvCoKxGwgKgE5XyJ+nNKlyHHOLb6N1N" +
            "uHyBrZrgtY/JYJHRooo5CEqYKBqdFnmbTVGEkCvJKxLnjwKWf+fEPoWeQFj5ObDjcKMZf2Jm2Ae69x+ikU5gBXsRmoF94GXT" +
            "LfN0/vLt98KDPnxwAQL9j5V1jGOY8jQl6MLxEs56cwXN0dqCnImzVH3TZT1cJ8SW1BRX6qIVxEzjsSGx3yxF3suAilPMqGRp" +
            "4ffyopjMD1JXiKR2RwLKzizUe5e8XyGOy9fplzhw3jVzTRyUZTRSZKkMLWcQ/gv0E4aONNqs4P+NameAZYOD12qRkxosQQP5" +
            "uux6B2nRyZ7sAV54DgFyLiRcq1FvwKw2EPQdk4HDoePrO/RNUbyNddnM/mMgj4FW65xCoT1LmjrIjsv/Ggdlx46ueczhMgtB" +
            "unx1/w8k8V+l8LVZ8gAT6wkU5J+DPQalQguMg12Jzug3q4TbdHiGCmD9EunCwOmsLuLJkz6EcSYXtrlDEnAM+hicw7iergYL" +
            "LlMXpfTdGxJCWJmP4zqUFeTTmsmhsjGBt7NiEB/9pFFEB3pSbf4iiUukw63Eo8Aqnf4iwob6X1QviCWuc8t0LUlT9vALgh/f" +
            "2DPVOOmR0RW6bgRvc7DSF20V/omg+YBw=="
}
