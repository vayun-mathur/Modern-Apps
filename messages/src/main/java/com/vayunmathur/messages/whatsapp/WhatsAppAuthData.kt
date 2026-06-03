package com.vayunmathur.messages.whatsapp

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.vayunmathur.messages.util.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json as KotlinJson

/**
 * Persistent auth data for WhatsApp Web companion device.
 * Stores device identity, encryption keys, and session data.
 */
@Serializable
data class WhatsAppAuthData(
    val deviceId: String,
    val phoneNumber: String,
    val pushName: String,
    val clientId: String,
    val serverToken: String,
    val clientToken: String,
    val encKey: String, // Base64 encoded
    val macKey: String, // Base64 encoded
    val wid: String, // WhatsApp ID (e.g., "1234567890@s.whatsapp.net")
) {
    companion object {
        private const val PREFS_NAME = "whatsapp_auth"
        private const val KEY_AUTH_DATA = "auth_data"

        fun load(context: Context): WhatsAppAuthData? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_AUTH_DATA, null) ?: return null
            return try {
                KotlinJson.decodeFromString<WhatsAppAuthData>(json)
            } catch (e: Exception) {
                null
            }
        }

        fun save(context: Context, authData: WhatsAppAuthData) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = KotlinJson.encodeToString(authData)
            prefs.edit { putString(KEY_AUTH_DATA, json) }
        }

        fun clear(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit { remove(KEY_AUTH_DATA) }
        }
    }
}

/**
 * QR code data for pairing a new WhatsApp Web device.
 */
@Serializable
data class WhatsAppQrData(
    val ref: String,
    val publicKey: String,
    val clientId: String,
)
