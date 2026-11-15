package com.vayunmathur.passwords.platform

import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.domain.PasskeyLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The UI contract between [PasswordsViewModel] and the screens that the store listing
 * images are rendered from.
 *
 * Those screens take a state value plus an actions interface rather than the ViewModel
 * itself, so a `@Preview` can render them from literal sample data — which matters more
 * here than elsewhere: the real screens only ever show credentials decrypted out of the
 * KDBX vault, and a screenshot generator must never need to open one.
 *
 * It lives in `util` rather than `ui` so the dependency runs one way: `ui` depends on
 * `util`, and the ViewModel implements [PasswordsActions].
 */

/** What the credential list draws. */
data class MenuUiState(
    val passwords: List<Password> = emptyList(),
    val passkeys: List<Passkey> = emptyList(),
    /**
     * True while the encrypted vault is still being opened. Without it an empty list is
     * indistinguishable from a vault that has not loaded, and opening a SQLCipher database costs
     * a key derivation - seconds on a cold start - which read as the app being broken.
     */
    val loading: Boolean = false,
    /**
     * Wall-clock millis, normally ticked once a second by [PasswordsViewModel.tickerFlow].
     * It is state rather than a `System.currentTimeMillis()` call inside the screen so a
     * preview can pin it and get the same TOTP code and countdown ring every render.
     *
     * A lambda, not a `Long`, so the tick is read inside the one row that draws a TOTP code
     * rather than in the screen's own scope. Reading it up here invalidated the whole list
     * every second, and `ListPage` is inline, so nothing below could skip.
     */
    val now: () -> Long = { 0L },
)

/** What the credential detail screen draws. */
data class PasswordUiState(
    val password: Password = Password(),
    /** Passkeys merged into this entry, resolved by `mergeCredentials`. */
    val passkeys: List<Passkey> = emptyList(),
    /** See [MenuUiState.now]. */
    val now: () -> Long = { 0L },
)

/** What the add/edit form draws. */
data class PasswordEditUiState(
    /** The persisted row, shown until the ViewModel's draft has been seeded from it. */
    val saved: Password = Password(),
    /** The in-progress edit, or null before [PasswordsActions] has a draft to save. */
    val draft: Password? = null,
)

/**
 * Callbacks for all three previewable screens. Every member has a default doing nothing,
 * so [Noop] is the whole implementation a preview needs.
 *
 * One interface rather than one per screen: there is a single ViewModel behind all of
 * them, and splitting it would mean redeclaring [copyToClipboard] — whose default
 * argument cannot be inherited twice — in two places.
 */
interface PasswordsActions {

    /** One-shot "copied" messages for the snackbar. A preview never emits. */
    val copyEvents: Flow<String> get() = emptyFlow()

    fun copyToClipboard(label: String, text: String, feedback: String? = null) {}

    fun delete(password: Password) {}

    fun setPasskeyLink(passkey: Passkey, link: PasskeyLink) {}

    fun updateDraft(transform: (Password) -> Password) {}

    fun saveDraft(onSaved: ((Long) -> Unit)? = null) {}

    /** Shared constants and helpers for PasswordsActions. */
    companion object {
        val Noop: PasswordsActions = object : PasswordsActions {}
    }
}
