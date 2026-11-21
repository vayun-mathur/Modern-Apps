package com.vayunmathur.email.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide preferences that belong to no particular account.
 *
 * Account settings live on the account row in Room, and the various workers
 * keep their own bookkeeping in private [android.content.SharedPreferences]
 * files. This is the third thing: a handful of user-facing choices that apply
 * to every account, read from composables that have a [Context] and nothing
 * else.
 *
 * A single instance per process so every reader observes the same
 * [StateFlow] - two instances would each hold their own copy of the value and
 * only one of them would update when the switch is flipped.
 */
class EmailSettings private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _loadRemoteImages = MutableStateFlow(prefs.getBoolean(KEY_LOAD_REMOTE_IMAGES, false))

    /**
     * Whether to fetch remote images without asking.
     *
     * Off by default: a remote image in a mail is a read receipt for the
     * sender, so turning them on is a choice the user has to make rather than
     * one made for them. When on, the "Remote images blocked" bar never
     * appears and messages render complete on open.
     */
    val loadRemoteImages: StateFlow<Boolean> = _loadRemoteImages.asStateFlow()

    fun setLoadRemoteImages(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LOAD_REMOTE_IMAGES, enabled) }
        _loadRemoteImages.value = enabled
    }

    private val _hasAskedNotifications =
        MutableStateFlow(prefs.getBoolean(KEY_HAS_ASKED_NOTIFICATIONS, false))

    /** Whether the one-time contextual notification ask has already fired. */
    val hasAskedNotifications: StateFlow<Boolean> = _hasAskedNotifications.asStateFlow()

    fun setHasAskedNotifications() {
        prefs.edit { putBoolean(KEY_HAS_ASKED_NOTIFICATIONS, true) }
        _hasAskedNotifications.value = true
    }

    /** Holds preference keys and defaults for email settings. */
    companion object {
        private const val PREFS = "email_settings"
        private const val KEY_LOAD_REMOTE_IMAGES = "load_remote_images"
        private const val KEY_HAS_ASKED_NOTIFICATIONS = "has_asked_notifications"

        @Volatile
        private var instance: EmailSettings? = null

        fun get(context: Context): EmailSettings =
            instance ?: synchronized(this) {
                instance ?: EmailSettings(context).also { instance = it }
            }
    }
}
