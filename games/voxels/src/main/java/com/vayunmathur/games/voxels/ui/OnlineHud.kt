package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.voxels.R
import com.vayunmathur.games.voxels.domain.NetFailure
import com.vayunmathur.games.voxels.domain.NetStatus
import com.vayunmathur.games.voxels.domain.peerChanges
import com.vayunmathur.games.voxels.domain.playerSuffix
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.delay
import org.json.JSONArray

private data class Notice(val id: Long, val player: String, val joined: Boolean)

private const val NOTICE_MS = 4000L

/** Device ids in the engine's peer JSON (`[{"device":..}]`). */
internal fun peerIds(peersJson: String): Set<String> = runCatching {
    val arr = JSONArray(peersJson)
    (0 until arr.length()).map { arr.getJSONObject(it).optString("device") }.filter { it.isNotEmpty() }.toSet()
}.getOrDefault(emptySet())

/**
 * Online HUD: how many players are here, whether the host is (for joiners), the connection state
 * (Connecting / Reconnecting / why it failed) and short-lived "X joined" / "X left" notices.
 */
@Composable
fun OnlineHud(status: NetStatus, peersJson: String, ownerDevice: String, isHost: Boolean, modifier: Modifier = Modifier) {
    val peers = remember(peersJson) { peerIds(peersJson) }
    val notices = remember { mutableStateListOf<Notice>() }
    val previous = remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(peers) {
        val before = previous.value
        previous.value = peers
        if (before == null) return@LaunchedEffect
        val c = peerChanges(before, peers)
        val now = System.nanoTime()
        c.joined.forEachIndexed { i, id -> notices += Notice(now + i, playerSuffix(id), joined = true) }
        c.left.forEachIndexed { i, id -> notices += Notice(now + 1000 + i, playerSuffix(id), joined = false) }
    }
    Column(modifier.widthIn(max = 320.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Chip(stringResource(R.string.players_online, peers.size + 1), Color(0x99000000))
        if (!isHost && status == NetStatus.Connected) {
            val here = ownerDevice in peers
            Chip(stringResource(if (here) R.string.host_here else R.string.host_away), if (here) Color(0x992E7D32) else Color(0x99000000))
        }
        when (status) {
            NetStatus.Connected -> Unit
            NetStatus.Connecting -> Chip(stringResource(R.string.online_connecting), Color(0x99000000))
            NetStatus.Reconnecting -> Chip(stringResource(R.string.connection_lost), Color(0xCCB26A00))
            is NetStatus.Failed -> Chip(stringResource(failureText(status.reason)), Color(0xCCB00020))
        }
        for (n in notices) {
            val who = stringResource(R.string.player_name, n.player)
            Chip(stringResource(if (n.joined) R.string.player_joined else R.string.player_left, who), Color(0x99000000))
            LaunchedEffect(n.id) {
                delay(NOTICE_MS)
                notices.remove(n)
            }
        }
    }
}

private fun failureText(reason: NetFailure): Int = when (reason) {
    NetFailure.SERVER_UNREACHABLE -> R.string.online_failed_server
    NetFailure.BAD_INVITE -> R.string.online_failed_invite
    NetFailure.HOST_UNVERIFIED -> R.string.online_failed_host
}

@Composable
private fun Chip(text: String, background: Color) {
    Text(
        text, color = Color.White,
        modifier = Modifier.background(background, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
