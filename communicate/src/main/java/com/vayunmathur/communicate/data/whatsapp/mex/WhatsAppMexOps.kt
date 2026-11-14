package com.vayunmathur.communicate.data.whatsapp.mex

import android.content.Context
import android.util.Base64 as AndroidBase64
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppE2EPreKey
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Typed, bounded catalog of `xwa2_*` MEX operations (w2.md §8). Each entry pairs a **GraphQL
 * operation name** (the persist-ids JSON key — documented in KDoc so a dev knows what to capture)
 * with a JVM-pure `variables` builder, then delegates to the [WhatsAppMex] router.
 *
 * `variables` shapes follow the SDL argument names from w2.md §8.0 ("single `variables` map keyed by
 * arg name"). JIDs and `XWA2Binary` are transmitted as Base64 (Argo BYTES over the JSON shim).
 *
 * Only the operations with a real committed doc_id (see `mex_persist_ids.json`, §5.5) will actually
 * round-trip today — `QueryGroupInfo`, `ContactUserInfoQuery`, `UsernameSetMutation`; the rest are
 * callable and return a typed `no_persisted_id:<op>` until a captured id is dropped into the JSON.
 *
 * Dev-only: reached via `CommunicateRepository`, gated on `WhatsAppFeature.enabled`.
 */
object WhatsAppMexOps {

    private const val TAG = "WAMex"

    // -- GraphQL operation names (persist-ids JSON keys) ---------------------------------------

    // Seeded id 27462649126753603. `query
    // QueryGroupInfo($group_input:XWA2GroupQueryInput!){xwa2_group_query_by_id…}`.
    const val OP_GROUP_QUERY = "QueryGroupInfo"
    /** Uncaptured. `xwa2_group_query_participating_groups`. */
    const val OP_GROUP_PARTICIPATING = "QueryParticipatingGroups"
    /** Uncaptured. `xwa2_group_batch_query_by_id`. */
    const val OP_GROUP_BATCH = "QueryGroupInfoBatch"
    /** Seeded id 27056625294008854 (nearest known contact op). `xwa2_contact_discovery`. */
    const val OP_CONTACT_DISCOVERY = "ContactUserInfoQuery"
    /** Uncaptured. `xwa2_primary_contacts_full_sync`. */
    const val OP_PRIMARY_CONTACTS_FULL_SYNC = "PrimaryContactsFullSync"
    /** Uncaptured. `xwa2_username_get`. */
    const val OP_USERNAME_GET = "UsernameGet"
    /** Uncaptured. `xwa2_username_check`. */
    const val OP_USERNAME_CHECK = "UsernameCheck"
    /** Seeded id 7225825540870559. `xwa2_username_set`. */
    const val OP_USERNAME_SET = "UsernameSetMutation"
    /** Uncaptured. `xwa2_blocklist_get`. */
    const val OP_BLOCKLIST_GET = "BlocklistGet"
    /** Uncaptured. `xwa2_update_blocklist_lid`. */
    const val OP_BLOCKLIST_UPDATE_LID = "UpdateBlocklistLid"
    /** Uncaptured. `xwa2_presence_data_platform_get_online_or_last_status`. */
    const val OP_PRESENCE_ONLINE_OR_LAST = "GetOnlineOrLastStatus"
    /** Uncaptured. `xwa2_newsletter`. */
    const val OP_NEWSLETTER = "NewsletterQuery"
    /** Uncaptured. `xwa2_newsletter_join_v2`. */
    const val OP_NEWSLETTER_JOIN_V2 = "NewsletterJoinV2"
    /** Uncaptured. `xwa2_newsletter_leave_v2`. */
    const val OP_NEWSLETTER_LEAVE_V2 = "NewsletterLeaveV2"
    /** Uncaptured. `xwa2_get_messaging_keys`. */
    const val OP_GET_MESSAGING_KEYS = "GetMessagingKeys"
    /** Uncaptured. `xwa2_set_messaging_keys`. */
    const val OP_SET_MESSAGING_KEYS = "SetMessagingKeys"

    private val json = Json { encodeDefaults = true }

    // ============================================================ Groups (§8.1)

    /** `xwa2_group_query_by_id` — read a group's metadata/participants over MEX. */
    suspend fun groupQueryById(context: Context, groupJid: String, queryContext: String = "INTERACTIVE"): MexResult =
        WhatsAppMex.call(
            context, OP_GROUP_QUERY, WhatsAppMexVariables.buildGroupQueryByIdVariables(groupJid, queryContext), "get")

    /** `xwa2_group_query_participating_groups` — groups the (optional) user participates in. */
    suspend fun groupParticipatingGroups(context: Context, userJid: String? = null): MexResult =
        WhatsAppMex.call(
            context, OP_GROUP_PARTICIPATING, WhatsAppMexVariables.buildParticipatingGroupsVariables(userJid), "get")

    /** `xwa2_group_batch_query_by_id` — batch group metadata read. */
    suspend fun groupBatchQueryById(
        context: Context,
        groupJids: List<String>,
        queryContext: String = "INTERACTIVE"): MexResult =
        WhatsAppMex.call(
            context, OP_GROUP_BATCH, WhatsAppMexVariables.buildGroupBatchQueryVariables(groupJids, queryContext), "get")

    // ============================================================ Contacts (§8.3)

    /** `xwa2_contact_discovery` — resolve raw phone numbers to LIDs. [context] = SEARCH|QR_SCAN. */
    suspend fun contactDiscovery(
        context: Context,
        rawPhoneNumbers: List<String>,
        discoveryContext: String = "SEARCH"): MexResult =
        WhatsAppMex.call(
            context,
            OP_CONTACT_DISCOVERY,
            WhatsAppMexVariables.buildContactDiscoveryVariables(rawPhoneNumbers, discoveryContext),
            "get")

    /** `xwa2_primary_contacts_full_sync` — single/multi-page primary-contact sync. */
    suspend fun primaryContactsFullSync(
        context: Context,
        rawPhoneNumbers: List<String>,
        sessionId: String,
        pageIndex: Int,
        last: Boolean,
        syncContext: String = "REGISTRATION",
    ): MexResult = WhatsAppMex.call(
        context,
        OP_PRIMARY_CONTACTS_FULL_SYNC,
        WhatsAppMexVariables.buildPrimaryContactsFullSyncVariables(
            rawPhoneNumbers, sessionId, pageIndex, last, syncContext),
        "set",
    )

    // ============================================================ Username (§8.11)

    /** `xwa2_username_get` — the account's current username (no args). */
    suspend fun usernameGet(context: Context): MexResult =
        WhatsAppMex.call(context, OP_USERNAME_GET, "{}", "get")

    /** `xwa2_username_check` — availability check with optional suggestions. */
    suspend fun usernameCheck(
        context: Context,
        username: String,
        includeSuggestions: Boolean = true,
        source: String = "USER_INPUT",
        sessionId: String? = null,
    ): MexResult = WhatsAppMex.call(
        context,
        OP_USERNAME_CHECK,
        WhatsAppMexVariables.buildUsernameCheckVariables(username, includeSuggestions, source, sessionId),
        "get",
    )

    /** `xwa2_username_set` — claim a username. [pin] is 6 uppercase alphanum (teen accounts require it). */
    suspend fun usernameSet(
        context: Context,
        username: String,
        pin: String? = null,
        source: String = "USER_INPUT",
        sessionId: String? = null,
    ): MexResult = WhatsAppMex.call(
        context,
        OP_USERNAME_SET,
        WhatsAppMexVariables.buildUsernameSetVariables(username, pin, source, sessionId),
        "set",
    )

    // ============================================================ Blocklist (§8.8)

    /** `xwa2_blocklist_get` — the account blocklist; [dhash] is the previously-returned delta hash. */
    suspend fun blocklistGet(context: Context, dhash: String? = null): MexResult =
        WhatsAppMex.call(context, OP_BLOCKLIST_GET, WhatsAppMexVariables.buildBlocklistGetVariables(dhash), "get")

    /**
     * `xwa2_update_blocklist_lid` — block/unblock a LID user. [identifierKey] is exactly one of
     * `pn_jid|username|display_name|guest_name` (per XWA2BlockedUserIdentifierLidInput). [action] =
     * BLOCK|UNBLOCK.
     */
    suspend fun updateBlocklistLid(
        context: Context,
        userLid: String,
        identifierKey: String,
        identifierValue: String,
        action: String,
        dhash: String? = null,
    ): MexResult = WhatsAppMex.call(
        context,
        OP_BLOCKLIST_UPDATE_LID,
        WhatsAppMexVariables.buildUpdateBlocklistLidVariables(userLid, identifierKey, identifierValue, action, dhash),
        "set",
    )

    // ============================================================ Presence (§8.6)

    /**
     * `xwa2_presence_data_platform_get_online_or_last_status` — online/last-seen for LID users.
     * [lastActiveFilter] = LAST_MINUTE|LAST_HOUR|LAST_DAY (optional).
     */
    suspend fun getOnlineOrLastStatus(
        context: Context,
        lidJids: List<String>,
        lastActiveFilter: String? = null): MexResult =
        WhatsAppMex.call(
            context,
            OP_PRESENCE_ONLINE_OR_LAST,
            WhatsAppMexVariables.buildOnlineOrLastStatusVariables(lidJids, lastActiveFilter),
            "get")

    // ============================================================ Newsletters (§8.4)

    /** `xwa2_newsletter` — read a channel. [keyType] = JID|INVITE; [viewRole] = SUBSCRIBER|GUEST|ADMIN. */
    suspend fun newsletter(
        context: Context,
        key: String,
        keyType: String = "JID",
        viewRole: String? = null): MexResult =
        WhatsAppMex.call(
            context, OP_NEWSLETTER, WhatsAppMexVariables.buildNewsletterVariables(key, keyType, viewRole), "get")

    /** `xwa2_newsletter_join_v2`. */
    suspend fun newsletterJoinV2(context: Context, newsletterId: String): MexResult =
        WhatsAppMex.call(
            context, OP_NEWSLETTER_JOIN_V2, WhatsAppMexVariables.buildNewsletterIdVariables(newsletterId), "set")

    /** `xwa2_newsletter_leave_v2`. */
    suspend fun newsletterLeaveV2(context: Context, newsletterId: String): MexResult =
        WhatsAppMex.call(
            context, OP_NEWSLETTER_LEAVE_V2, WhatsAppMexVariables.buildNewsletterIdVariables(newsletterId), "set")

    // ============================================================ Messaging keys (§8.2)

    /** `xwa2_get_messaging_keys` — fetch Signal prekey bundles for the given interop user JIDs. */
    suspend fun getMessagingKeys(context: Context, userJids: List<String>): MexResult =
        WhatsAppMex.call(
            context, OP_GET_MESSAGING_KEYS, WhatsAppMexVariables.buildGetMessagingKeysVariables(userJids), "get")

    /**
     * `xwa2_set_messaging_keys` — publish our identity + signed prekey + a batch of freshly-minted
     * one-time prekeys (§8.2). The one-time prekeys are minted via
     * [RustWhatsAppCrypto.generateKeyPairSplit] and persisted into the `whatsapp_e2e_pre_keys` Room
     * table (private||public, unuploaded) so what we upload matches our local store. All binary is
     * standard Base64 (Argo BYTES over the JSON shim).
     *
     * Requires persisted [WhatsAppAuthData]; returns a `no_auth` transport failure otherwise.
     */
    suspend fun setMessagingKeys(context: Context, oneTimePreKeyCount: Int = 10): MexResult {
        val auth = WhatsAppAuthData.load(context)
            ?: return MexResult.transport("no_auth")
        val db = WhatsAppDatabase.getDatabase(context)

        val identity = decodeStd(auth.identityPublicKey)
        val skeyValue = decodeStd(auth.signedPreKeyPublic)
        val skeySignature = decodeStd(auth.signedPreKeySignature)

        // Mint one-time prekeys, continuing ids after the current max, and persist them.
        val maxId = db.e2ePreKeyDao().getMaxId()
        val minted = ArrayList<Pair<Int, ByteArray>>(oneTimePreKeyCount)
        val entities = ArrayList<WhatsAppE2EPreKey>(oneTimePreKeyCount)
        for (i in 1..oneTimePreKeyCount) {
            val id = maxId + i
            val kp = RustWhatsAppCrypto.generateKeyPairSplit()
            entities.add(WhatsAppE2EPreKey(id, kp.privateKey + kp.publicKey, uploaded = false))
            minted.add(id to kp.publicKey)
        }
        db.e2ePreKeyDao().insertAll(entities)
        WhatsAppDiag.log(
            TAG,
            "setMessagingKeys: minted ${minted.size} one-time prekeys (ids ${maxId + 1}..${maxId + minted.size})")

        val variables = WhatsAppMexVariables.buildSetMessagingKeysVariables(
            identity = identity,
            skeyId = auth.signedPreKeyId,
            skeyValue = skeyValue,
            skeySignature = skeySignature,
            prekeys = minted,
        )
        return WhatsAppMex.call(context, OP_SET_MESSAGING_KEYS, variables, "set")
    }

    // -- encoding helpers ------------------------------------------------------------------------

    /** Decode a STANDARD-base64 field stored in [WhatsAppAuthData] (android.util.Base64.NO_WRAP). */
    private fun decodeStd(s: String): ByteArray = AndroidBase64.decode(s, AndroidBase64.NO_WRAP)
}
