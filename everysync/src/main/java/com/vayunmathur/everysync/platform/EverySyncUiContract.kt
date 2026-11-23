package com.vayunmathur.everysync.platform

import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.sync.SnapshotStore.SnapshotMeta

/**
 * The UI contract between the screens and whatever drives them.
 *
 * Screens take a state value plus an actions interface rather than the ViewModel and the
 * nav back stack, so they can be rendered by a `@Preview` — which is what the store listing
 * images are generated from. It lives in `util` rather than `ui` so the dependency runs one
 * way: `ui` depends on `util`, and the `*Screen(backStack, viewModel)` binders are the only
 * things that know about either.
 *
 * Navigation is part of the actions here rather than a separate set of lambdas because
 * every screen but the accounts list needs at least a back arrow, and the ViewModel has
 * nothing to say about it — the binder implements the whole interface in one place.
 */

/** One row of the accounts list. */
data class AccountRow(
    val accountName: String,
    val providerId: String,
    val syncing: Boolean = false,
    val lastSyncError: String? = null,
    /**
     * Already formatted, because the date/time format follows the user's 12/24-hour
     * setting and so needs a Context, which a preview does not have. Null = never synced.
     */
    val lastSyncedAt: String? = null,
)

/** Everything the accounts list draws. */
data class AccountsUiState(
    val accounts: List<AccountRow> = emptyList(),
    /** False until the accounts store emits at least once, so an empty list can show a spinner. */
    val hasLoaded: Boolean = true,
)

/** Everything the per-account screen draws. */
data class AccountDetailUiState(
    val accountName: String = "",
    /** Null when the account is gone or not loaded yet, which shows the empty text. */
    val providerId: String? = null,
    val enabledTypes: Set<DataType> = emptySet(),
    /** Personal E2EE vaults (EverySync-only sharing). */
    val vaults: List<VaultUiState> = emptyList(),
    val deviceId: String = "",
    val safetyCode: String? = null,
    val computingCode: Boolean = false,
    /** Human-readable sync failure explanation, or null when healthy. */
    val syncErrorText: String? = null,
    /** Daily encrypted snapshots for this account's vaults, newest first. */
    val snapshots: List<SnapshotMeta> = emptyList(),
    /** True while a snapshot restore is running. */
    val restoring: Boolean = false,
)

/** Everything the settings screen draws. */
data class SettingsUiState(
    val intervalMinutes: Long = 60L,
    val wifiOnly: Boolean = false,
    val conflictPolicy: String = "",
)

/**
 * Accounts-list callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface AccountsActions {
    fun syncNow(accountName: String) {}
    fun openAccount(accountName: String) {}
    fun openAddAccount() {}
    fun openSettings() {}

    /** Provides a no-op AccountsActions for previews and defaults. */
    companion object {
        val Noop: AccountsActions = object : AccountsActions {}
    }
}

/** Provider-chooser callbacks. Same no-op-default arrangement as [AccountsActions]. */
interface AddAccountActions {
    fun addRelayAccount(providerId: String) {}
    fun back() {}

    /** Provides a no-op AddAccountActions for previews and defaults. */
    companion object {
        val Noop: AddAccountActions = object : AddAccountActions {}
    }
}

/** Per-account callbacks. Same no-op-default arrangement as [AccountsActions]. */
interface AccountDetailActions {
    fun toggleType(accountName: String, type: DataType, enabled: Boolean) {}
    fun syncNow(accountName: String) {}
    fun removeAccount(accountName: String) {}
    fun back() {}
    /** Restores a daily snapshot (replays its records through the sinks). */
    fun restoreSnapshot(snapshot: SnapshotMeta) {}
    /** Takes today's snapshots now (idempotent — same-day vaults are skipped). */
    fun snapshotNow() {}

    /** Creates a new owned personal vault for [dataType] (`contacts`/`calendar`) with [title]. */
    fun createVault(dataType: String, title: String) {}

    /** Reloads vault members + pending requests from the relay. */
    fun refreshVaults() {}

    /** Seals a vault invite to [recipientId]'s inbox with [role]. */
    fun sendInvite(vaultId: String, recipientId: String, role: String, onResult: (Boolean) -> Unit = {}) {}

    /** Approves [requesterId]'s join request on [vaultId], sealing them the invite. */
    fun approveRequest(vaultId: String, requesterId: String) {}

    /** Drops [requesterId]'s join request on [vaultId] without granting access. */
    fun denyRequest(vaultId: String, requesterId: String) {}

    /** Computes the out-of-band safety code for [recipientId]. */
    fun computeSafetyCode(recipientId: String) {}

    /** Roles offerable for [dataType] — passwords exclude viewer (read-only still discloses secrets). */
    fun offerableRoles(dataType: String): List<String> = emptyList()

    /** Provides a no-op AccountDetailActions for previews and defaults. */
    companion object {
        val Noop: AccountDetailActions = object : AccountDetailActions {}
    }
}

/** Settings callbacks. Same no-op-default arrangement as [AccountsActions]. */
interface SettingsActions {
    fun setInterval(minutes: Long) {}
    fun setWifiOnly(value: Boolean) {}
    fun setConflictPolicy(policy: String) {}
    fun back() {}

    /** Provides a no-op SettingsActions for previews and defaults. */
    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}
