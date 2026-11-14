package com.vayunmathur.everysync.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists [AccountConfig]s in DataStore and keeps the system account entries
 * in sync. All EverySync accounts share the account type
 * `com.vayunmathur.everysync` (see [ACCOUNT_TYPE]) so synced contacts/calendar
 * rows are owned by a real system account — the system providers key rows by
 * (name, type) and some OEMs reject rows whose type has no registered
 * authenticator.
 */
class AccountStore private constructor(private val appContext: Application) {
    private val ds = DataStoreUtils.getInstance(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    fun accountsFlow(): Flow<List<AccountConfig>> =
        ds.stringFlow(KEY).map { decode(it) }

    fun getAll(): List<AccountConfig> = decode(ds.getString(KEY))

    fun get(accountName: String): AccountConfig? =
        getAll().firstOrNull { it.accountName == accountName }

    suspend fun upsert(config: AccountConfig) {
        val current = getAll().filterNot { it.accountName == config.accountName }
        ds.setString(KEY, json.encodeToString(current + config))
        ensureSystemAccount(config.accountName)
    }

    suspend fun remove(accountName: String) {
        val current = getAll().filterNot { it.accountName == accountName }
        ds.setString(KEY, json.encodeToString(current))
        TokenStore.getInstance(appContext).clear(accountName)
        removeSystemAccount(accountName)
    }

    private fun ensureSystemAccount(accountName: String) {
        try {
            val am = AccountManager.get(appContext)
            val exists = am.getAccountsByType(ACCOUNT_TYPE).any { it.name == accountName }
            if (!exists) {
                am.addAccountExplicitly(Account(accountName, ACCOUNT_TYPE), null, null)
            }
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to register system account", expected)
        }
    }

    private fun removeSystemAccount(accountName: String) {
        try {
            val am = AccountManager.get(appContext)
            am.getAccountsByType(ACCOUNT_TYPE)
                .firstOrNull { it.name == accountName }
                ?.let { am.removeAccountExplicitly(it) }
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to remove system account", expected)
        }
    }

    private fun decode(raw: String?): List<AccountConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<AccountConfig>>(raw)
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to decode accounts", expected)
            emptyList()
        }
    }

    /** Holds account-type and storage constants for AccountStore. */
    companion object {
        const val ACCOUNT_TYPE = "com.vayunmathur.everysync"
        private const val KEY = "everysync_accounts"
        private const val TAG = "AccountStore"

        @Volatile
        private var instance: AccountStore? = null

        fun getInstance(context: Context): AccountStore =
            instance ?: synchronized(this) {
                instance ?: AccountStore(context.applicationContext as Application).also { instance = it }
            }
    }
}
