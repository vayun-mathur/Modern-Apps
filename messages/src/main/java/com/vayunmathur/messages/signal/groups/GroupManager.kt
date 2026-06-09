package com.vayunmathur.messages.signal.groups

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.Group
import com.vayunmathur.messages.signal.store.SignalGroupEntity
import com.vayunmathur.messages.signal.store.SignalGroupStore
import com.vayunmathur.messages.signal.web.SignalWebSocket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class GroupManager(
    private val ws: SignalWebSocket,
    private val groupStore: SignalGroupStore,
    private val aci: String,
    private val password: String,
) {
    data class SignalGroup(
        val groupId: String,
        val title: String,
        val memberAcis: List<String>,
        val avatarUrl: String?,
        val revision: Int,
    )

    private val cache = ConcurrentHashMap<String, SignalGroup>()

    suspend fun fetchGroup(groupId: String, masterKey: ByteArray): SignalGroup? {
        return try {
            val authHeader = GroupAuth.authHeader(aci, password)
            val response = ws.sendRequest(
                "GET", "/v1/groups",
                headers = mapOf("Authorization" to authHeader),
            )
            if (response.status !in 200..299) return null

            val groupProto = Group.parseFrom(response.body)
            val group = SignalGroup(
                groupId = groupId,
                title = groupProto.title.toStringUtf8(),
                memberAcis = groupProto.membersList.map {
                    val bb = ByteBuffer.wrap(it.userId.toByteArray())
                    UUID(bb.getLong(), bb.getLong()).toString()
                },
                avatarUrl = groupProto.avatarUrl.ifEmpty { null },
                revision = groupProto.version,
            )

            cache[groupId] = group
            groupStore.storeGroup(
                SignalGroupEntity(
                    groupId = groupId,
                    masterKey = masterKey,
                    title = group.title,
                    avatarUrl = group.avatarUrl,
                    revision = group.revision,
                )
            )
            group
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch group $groupId", e)
            null
        }
    }

    suspend fun getOrFetchGroup(groupId: String, masterKey: ByteArray): SignalGroup? {
        cache[groupId]?.let { return it }
        return fetchGroup(groupId, masterKey)
    }

    fun getCachedGroup(groupId: String): SignalGroup? = cache[groupId]

    fun deriveGroupId(masterKey: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey, "HmacSHA256"))
        val hash = mac.doFinal("GV2 Derived".toByteArray())
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "GroupManager"
    }
}
