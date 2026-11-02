package com.vayunmathur.globalesim.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.esimAccountStore by preferencesDataStore(name = "esim_account")

/**
 * Anonymous account id store. The id is a client-minted UUIDv4 — no email,
 * no PII — minted locally on first use. It is the account's only
 * credential; the app shows it as the "SIM ID" for claiming on another phone.
 */
class AnonymousIdStore(private val context: Context) {
    val accountId: Flow<String?> =
        context.esimAccountStore.data.map { it[KEY_ACCOUNT_ID] }

    /** Returns the existing id, or mints + persists a new UUIDv4. */
    suspend fun getOrCreate(): String {
        val existing = context.esimAccountStore.data.map { it[KEY_ACCOUNT_ID] }.first()
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        context.esimAccountStore.edit { it[KEY_ACCOUNT_ID] = fresh }
        return fresh
    }

    /** Replaces the id with a SIM ID claimed from another phone. */
    suspend fun replace(newId: String) {
        context.esimAccountStore.edit { it[KEY_ACCOUNT_ID] = newId }
    }

    companion object {
        private val KEY_ACCOUNT_ID = stringPreferencesKey("account_id")
    }
}
