package com.vayunmathur.passwords.ui
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.passwords.Route
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.platform.MenuUiState
import com.vayunmathur.passwords.platform.PasswordsViewModel

/** Credential Item: a long implementation for credential item. */
sealed class CredentialItem(override val id: Long) : DatabaseItem {
    /** Password Item: a password implementation for password item. */
    class PasswordItem(val password: Password, val passkeys: List<Passkey>) :
        CredentialItem(password.id)
    /** Passkey Item: a passkey implementation for passkey item. */
    class PasskeyItem(val passkey: Passkey) : CredentialItem(Long.MAX_VALUE - passkey.id)
}

@Composable
fun MenuPage(
    backStack: NavBackStack<Route>,
    viewModel: PasswordsViewModel,
) {
    // Deliberately not `by`: unwrapping here would read the tick in this scope and invalidate
    // the whole list once a second. MenuScreen reads it inside the TOTP row instead.
    val ticker = viewModel.tickerFlow.collectAsState()
    val passwords by viewModel.passwords.collectAsState()
    val passkeys by viewModel.passkeys.collectAsState()
    val loaded by viewModel.loaded.collectAsState()
    val now = remember(ticker) { { ticker.value } }
    MenuScreen(
        backStack,
        MenuUiState(passwords = passwords, passkeys = passkeys, loading = !loaded, now = now),
        viewModel,
    )
}
