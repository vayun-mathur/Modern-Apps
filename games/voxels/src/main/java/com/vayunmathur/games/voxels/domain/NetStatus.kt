package com.vayunmathur.games.voxels.domain

/** Why an online session could not start. */
enum class NetFailure {
    /** The sync identity couldn't be set up / registered (offline, or the relay is unreachable). */
    SERVER_UNREACHABLE,
    /** The world key from the invite is unreadable. */
    BAD_INVITE,
    /** The host's public key couldn't be fetched, so its updates can't be verified. */
    HOST_UNVERIFIED,
}

/** Connection state shown on the online HUD. */
sealed interface NetStatus {
    /** First connection attempt in progress. */
    data object Connecting : NetStatus
    /** Live connection to the world channel. */
    data object Connected : NetStatus
    /** Was connected, lost it, retrying with backoff. */
    data object Reconnecting : NetStatus
    /** Can't start at all; [reason] says why. */
    data class Failed(val reason: NetFailure) : NetStatus
}

/**
 * Live-socket state to HUD state. Before the first successful connection we are "connecting", not
 * "lost" - showing "Connection lost" on every join was the bug this replaces.
 */
fun netStatusOf(live: Boolean, everConnected: Boolean): NetStatus = when {
    live -> NetStatus.Connected
    everConnected -> NetStatus.Reconnecting
    else -> NetStatus.Connecting
}

/** Players who appeared ([joined]) and disappeared ([left]) between two peer snapshots. */
data class PeerChanges(val joined: Set<String>, val left: Set<String>)

/** Diff of two peer-id sets. */
fun peerChanges(before: Set<String>, after: Set<String>): PeerChanges =
    PeerChanges(joined = after - before, left = before - after)

/** Short display name for a device id, matching the join-request naming ("Player 1a2b"). */
fun playerLabel(deviceId: String): String = "Player " + deviceId.takeLast(PLAYER_SUFFIX)

private const val PLAYER_SUFFIX = 4
