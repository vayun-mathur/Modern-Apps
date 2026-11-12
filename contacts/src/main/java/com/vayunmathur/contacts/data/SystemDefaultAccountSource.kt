package com.vayunmathur.contacts.data

import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import com.vayunmathur.library.log.Log

/**
 * [DefaultAccountSource] over the platform contacts APIs. Every call is SDK-guarded and
 * wrapped in runCatching: OEM contacts providers may not implement the Android 16
 * default-account methods, and a failed query must read as "no restriction" rather
 * than break the editor.
 */
class SystemDefaultAccountSource(context: Context) : DefaultAccountSource {

    private val appContext = context.applicationContext

    override fun defaultAccountForNewContacts(): DefaultAccountSnapshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        return runCatching { queryDefaultAccount() }
            .onFailure { Log.error(TAG, "Default account query failed", it) }
            .getOrNull()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun queryDefaultAccount(): DefaultAccountSnapshot {
        val state = ContactsContract.RawContacts.DefaultAccount
            .getDefaultAccountForNewContacts(appContext.contentResolver)
        val kind = when (state.state) {
            DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_CLOUD -> DefaultAccountKind.CLOUD
            DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_SIM -> DefaultAccountKind.SIM
            DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_LOCAL -> DefaultAccountKind.LOCAL
            else -> DefaultAccountKind.NOT_SET
        }
        val account = state.account?.let { ProviderAccount(it.name.orEmpty(), it.type.orEmpty()) }
        return DefaultAccountSnapshot(kind, account)
    }

    override fun customLocalAccount(): ProviderAccount? = runCatching {
        val name = ContactsContract.RawContacts.getLocalAccountName(appContext)
        val type = ContactsContract.RawContacts.getLocalAccountType(appContext)
        if (name.isNullOrBlank() || type.isNullOrBlank()) null else ProviderAccount(name, type)
    }.onFailure { Log.error(TAG, "Local account query failed", it) }.getOrNull()

    override fun providerSimAccounts(): List<ProviderAccount> = runCatching {
        ContactsContract.SimContacts.getSimAccounts(appContext.contentResolver)
            .map { ProviderAccount(it.accountName.orEmpty(), it.accountType.orEmpty()) }
    }.onFailure { Log.error(TAG, "SIM account query failed", it) }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "DefaultAccount"
    }
}

private typealias DefaultAccountAndState =
    ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState
