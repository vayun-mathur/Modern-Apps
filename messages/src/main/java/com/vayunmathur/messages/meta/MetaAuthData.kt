package com.vayunmathur.messages.meta

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent auth data for Meta platforms (Messenger/Instagram).
 * Stores cookies and session information.
 */
@Serializable
data class MetaAuthData(
    val platform: Platform,
    val userId: String,
    val cookies: Map<String, String>,
    val sessionId: String? = null,
    val mqttEndpoint: String? = null,
) {
    enum class Platform {
        MESSENGER,
        INSTAGRAM
    }

    companion object {
        private const val PREFS_NAME = "meta_auth"
        private const val KEY_MESSENGER = "messenger_auth"
        private const val KEY_INSTAGRAM = "instagram_auth"

        fun load(context: Context, platform: Platform): MetaAuthData? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = when (platform) {
                Platform.MESSENGER -> KEY_MESSENGER
                Platform.INSTAGRAM -> KEY_INSTAGRAM
            }
            val json = prefs.getString(key, null) ?: return null
            return try {
                Json.decodeFromString<MetaAuthData>(json)
            } catch (e: Exception) {
                null
            }
        }

        fun save(context: Context, authData: MetaAuthData) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = when (authData.platform) {
                Platform.MESSENGER -> KEY_MESSENGER
                Platform.INSTAGRAM -> KEY_INSTAGRAM
            }
            val json = Json.encodeToString(authData)
            prefs.edit { putString(key, json) }
        }

        fun clear(context: Context, platform: Platform) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = when (platform) {
                Platform.MESSENGER -> KEY_MESSENGER
                Platform.INSTAGRAM -> KEY_INSTAGRAM
            }
            prefs.edit { remove(key) }
        }
    }

    /**
     * Validate that required cookies are present.
     */
    fun isValid(): Boolean {
        return when (platform) {
            Platform.MESSENGER -> {
                cookies.containsKey("c_user") &&
                cookies.containsKey("xs") &&
                cookies.containsKey("datr")
            }
            Platform.INSTAGRAM -> {
                cookies.containsKey("sessionid") &&
                cookies.containsKey("csrftoken") &&
                cookies.containsKey("ds_user_id")
            }
        }
    }

    /**
     * Get cookie header string for HTTP requests.
     */
    fun toCookieHeader(): String {
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
