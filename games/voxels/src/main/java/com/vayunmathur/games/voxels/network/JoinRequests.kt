@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.vayunmathur.games.voxels.network

import android.content.Context
import com.vayunmathur.games.voxels.data.WorldManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

/**
 * Join requests for worlds this device owns: fetching the pending ones and approving or denying
 * them. Shared by the world menu (which polls while it's on screen) and the in-game host chip.
 */
object JoinRequests {
    /** How often the menu and the in-game host poll the inbox. */
    const val POLL_MS = 20_000L

    /** Persistent key marking a request as handled (the inbox is always pulled from 0). */
    fun handledKey(r: VoxelsSync.JoinRequest): String = "${r.worldId}|${r.requesterId}"

    /** Requests for [owned] worlds that haven't been approved/denied yet, one per requester+world. */
    fun pending(
        requests: List<VoxelsSync.JoinRequest>,
        owned: Set<String>,
        handled: Set<String>,
    ): List<VoxelsSync.JoinRequest> = requests
        .filter { it.worldId in owned && handledKey(it) !in handled }
        .distinctBy { handledKey(it) }

    /**
     * Pulls the inbox. Invites are folded into the shared-world list (when [acceptInvites]); returns
     * the pending requests for worlds this device owns. Empty on any failure.
     */
    suspend fun refresh(ctx: Context, acceptInvites: Boolean): List<VoxelsSync.JoinRequest> =
        withContext(Dispatchers.IO) {
        runCatching {
            if (!VoxelsSync.init(ctx)) return@runCatching emptyList()
            val res = VoxelsSync.pullInbox(0)
            if (acceptInvites) {
                for (inv in res.invites) {
                    WorldManager.upsertSharedWorld(
                        ctx, inv.name, inv.seed, inv.worldId,
                        inv.key, inv.ownerDeviceId, inv.role, System.currentTimeMillis(),
                    )
                }
            }
            val owned = WorldManager.onlineWorlds(ctx)
                .filter { it.meta.role == VoxelsRoles.OWNER }.map { it.meta.worldId }.toSet()
            pending(res.requests, owned, WorldManager.handledRequests(ctx))
        }.getOrDefault(emptyList())
    }

    /** Sends the requester a sealed invite and records them as an editor. True if the invite went out. */
    suspend fun approve(ctx: Context, req: VoxelsSync.JoinRequest): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            VoxelsSync.init(ctx)
            val world = WorldManager.onlineWorlds(ctx)
                .firstOrNull { it.meta.worldId == req.worldId && it.meta.role == VoxelsRoles.OWNER }
                ?: return@runCatching false
            val key = Base64.decode(world.meta.keyB64)
            val sent = VoxelsSync.sendInvite(
                req.requesterId, world.meta.worldId, key,
                world.meta.name, world.meta.seed, VoxelsRoles.EDITOR,
                Base64.encode(VoxelsSync.publicBundle), world.meta.ownerDeviceId,
            )
            if (sent) VoxelsSync.recordMembers(
                world.meta.worldId, key,
                listOf(
                    VoxelsSync.Member(world.meta.ownerDeviceId, "", VoxelsRoles.OWNER),
                    VoxelsSync.Member(req.requesterId, req.name, VoxelsRoles.EDITOR),
                ),
            )
            sent
        }.getOrDefault(false)
        if (ok) WorldManager.markRequestHandled(ctx, handledKey(req))
        ok
    }

    /** Declines a request; it won't be shown again. */
    fun deny(ctx: Context, req: VoxelsSync.JoinRequest) = WorldManager.markRequestHandled(ctx, handledKey(req))
}
