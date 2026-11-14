package com.vayunmathur.emergency.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import com.vayunmathur.library.log.Log

private const val TAG = "EmergencyGestureProvider"

/**
 * Gesture state for Settings, speaking the settingslib `EmergencyNumberUtils` protocol.
 *
 * Mirrors GrapheneOS's `EmergencyGestureContentProvider`: a `call()`-only provider (query,
 * insert, update and delete throw) whose methods get/set the SOS gesture toggles and the
 * emergency-number override. The string literals below are SettingsLib's
 * `EmergencyNumberUtils` constants, restated because a Gradle app cannot link SettingsLib.
 *
 * Writes land in `Settings.Secure`, which needs `WRITE_SECURE_SETTINGS` - a priv-app-only
 * grant. Every write is guarded: without the grant the call returns a denied bundle instead
 * of throwing into Settings. Reads work regardless; defaults come from local resources
 * (GrapheneOS reads `com.android.internal` bools we cannot see).
 *
 * Authority is ours (`com.vayunmathur.emergency.gesture`); the MAOS build must rewire
 * `EMERGENCY_NUMBER_OVERRIDE_AUTHORITY` if it wants Settings to call this provider.
 */
class GestureProvider : ContentProvider() {

    private fun prefs(): SharedPreferences =
        requireContext().getSharedPreferences(OVERRIDE_PREFS, Context.MODE_PRIVATE)

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        val out = Bundle()
        when (method) {
            METHOD_GET_NUMBER_OVERRIDE -> {
                out.putString(
                    EXTRA_CALL_NUMBER,
                    prefs().getString(EXTRA_CALL_NUMBER, null),
                )
            }
            METHOD_SET_NUMBER_OVERRIDE -> {
                prefs().edit()
                    .putString(EXTRA_CALL_NUMBER, extras?.getString(EXTRA_CALL_NUMBER))
                    .apply()
                // Stock notifies observers of the gesture authority URI here; Settings
                // registers a content observer on it, so without this the override row
                // never refreshes after a set. Both authorities served in the manifest,
                // so both get the notification.
                runCatching {
                    requireContext().contentResolver.notifyChange(STOCK_GESTURE_URI, null)
                    requireContext().contentResolver.notifyChange(GESTURE_URI, null)
                }
            }
            METHOD_SET_GESTURE -> {
                if (!writeSecure(SecureKeys.GESTURE_ENABLED, extras, EXTRA_VALUE)) {
                    return denied(out)
                }
            }
            METHOD_SET_GESTURE_UI_SHOWING -> {
                val showing = extras?.getInt(EXTRA_UI_SHOWING_VALUE, 0) ?: 0
                if (!writeSecureInt(SecureKeys.GESTURE_UI_SHOWING, showing.toLong())) {
                    return denied(out)
                }
                if (showing != 0) {
                    writeSecureInt(
                        SecureKeys.GESTURE_UI_LAST_STARTED_MILLIS,
                        SystemClock.elapsedRealtime(),
                    )
                }
            }
            METHOD_SET_SOUND -> {
                if (!writeSecure(SecureKeys.GESTURE_SOUND_ENABLED, extras, EXTRA_VALUE)) {
                    return denied(out)
                }
            }
            METHOD_GET_GESTURE_ENABLED -> {
                out.putInt(EXTRA_VALUE, readSecureInt(
                    SecureKeys.GESTURE_ENABLED,
                    defaultGestureEnabled(),
                ))
            }
            METHOD_GET_SOUND_ENABLED -> {
                out.putInt(EXTRA_VALUE, readSecureInt(
                    SecureKeys.GESTURE_SOUND_ENABLED,
                    defaultSoundEnabled(),
                ))
            }
            else -> {
                Log.status(TAG, "unknown gesture method $method from $authority")
                return null
            }
        }
        return out
    }

    /** Current number override, for the SOS screen; null when the user set none. */
    fun numberOverride(context: Context): String? = numberOverrideStatic(context)

    /** Whether the SOS gesture is on; local default when Settings.Secure is unreadable. */
    fun isGestureEnabled(context: Context): Boolean = isGestureEnabledStatic(context)

    /** Whether the SOS warning sound is on; local default when unreadable. */
    fun isSoundEnabled(context: Context): Boolean = isSoundEnabledStatic(context)

    private fun writeSecure(key: String, extras: Bundle?, valueKey: String): Boolean {
        val value = extras?.getInt(valueKey) ?: return false
        return writeSecureInt(key, value.toLong())
    }

    private fun writeSecureInt(key: String, value: Long): Boolean {
        // Long storage for the UI-showing timestamp; ints for the toggles.
        return runCatching {
            val resolver = requireContext().contentResolver
            if (key == SecureKeys.GESTURE_UI_LAST_STARTED_MILLIS) {
                Settings.Secure.putLong(resolver, key, value)
            } else {
                Settings.Secure.putInt(resolver, key, value.toInt())
            }
            true
        }.getOrElse {
            Log.status(TAG, "cannot write $key; is WRITE_SECURE_SETTINGS held?", it)
            false
        }
    }

    private fun readSecureInt(key: String, default: Int): Int =
        readSecureInt(requireContext(), key, default)

    private fun denied(out: Bundle): Bundle {
        // SettingsLib treats a null bundle as "provider absent, use compiled default"; a bundle
        // carrying the off value says "present but disabled", which is the honest answer when
        // the Secure write was refused.
        out.putInt(EXTRA_VALUE, SETTING_OFF)
        return out
    }

    private fun defaultGestureEnabled(): Int =
        if (
            requireContext().resources.getBoolean(
                com.vayunmathur.emergency.R.bool.default_emergency_gesture_enabled,
            )
        ) {
            SETTING_ON
        } else {
            SETTING_OFF
        }

    private fun defaultSoundEnabled(): Int =
        if (
            requireContext().resources.getBoolean(
                com.vayunmathur.emergency.R.bool.default_emergency_gesture_sound_enabled,
            )
        ) {
            SETTING_ON
        } else {
            SETTING_OFF
        }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor = throw UnsupportedOperationException()

    override fun getType(uri: Uri): String = throw UnsupportedOperationException()

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = throw UnsupportedOperationException()

    /** Wire-protocol constants and static reads for [GestureProvider]. */
    companion object {
        private const val OVERRIDE_PREFS = "local_emergency_number_override_shared_pref"

        /** Our gesture authority, for the notifyChange stock sends on override sets. */
        private val GESTURE_URI: Uri = Uri.Builder()
            .scheme("content")
            .authority("com.vayunmathur.emergency.gesture")
            .build()

        /** Stock gesture authority served by the same provider (see manifest). */
        private val STOCK_GESTURE_URI: Uri = Uri.Builder()
            .scheme("content")
            .authority("com.android.emergency.gesture")
            .build()

        /**
         * `Settings.Secure` gesture keys as literals: they are `@hide`/system-API in the
         * framework (`Settings.Secure.EMERGENCY_GESTURE_*`), so a Gradle app cannot reference
         * the constants. Values from AOSP `Settings.java`; the put/get calls themselves are
         * public API and fail gracefully without `WRITE_SECURE_SETTINGS`.
         */
        private object SecureKeys {
            const val GESTURE_ENABLED = "emergency_gesture_enabled"
            const val GESTURE_SOUND_ENABLED = "emergency_gesture_sound_enabled"
            const val GESTURE_UI_SHOWING = "emergency_gesture_ui_showing"
            const val GESTURE_UI_LAST_STARTED_MILLIS = "emergency_gesture_ui_last_started_millis"
        }

        /** Static read of the number override for non-provider callers (receiver, lookup). */
        fun numberOverrideStatic(context: Context): String? =
            runCatching {
                context.getSharedPreferences(OVERRIDE_PREFS, Context.MODE_PRIVATE)
                    .getString(EXTRA_CALL_NUMBER, null)
            }.getOrNull()

        /** Static gesture-enabled read (provider instance not required). */
        fun isGestureEnabledStatic(context: Context): Boolean =
            readSecureInt(
                context,
                SecureKeys.GESTURE_ENABLED,
                context.resources.getBoolean(
                    com.vayunmathur.emergency.R.bool.default_emergency_gesture_enabled,
                ).toSetting(),
            ) == SETTING_ON

        /** Static sound-enabled read (provider instance not required). */
        fun isSoundEnabledStatic(context: Context): Boolean =
            readSecureInt(
                context,
                SecureKeys.GESTURE_SOUND_ENABLED,
                context.resources.getBoolean(
                    com.vayunmathur.emergency.R.bool.default_emergency_gesture_sound_enabled,
                ).toSetting(),
            ) == SETTING_ON

        private fun Boolean.toSetting(): Int = if (this) SETTING_ON else SETTING_OFF

        // SettingsLib EmergencyNumberUtils literals (see plan). Do not "clean these up" into
        // nicer names: they are a wire protocol shared with Settings.
        const val METHOD_GET_NUMBER_OVERRIDE = "GET_EMERGENCY_NUMBER_OVERRIDE"
        const val METHOD_SET_NUMBER_OVERRIDE = "SET_EMERGENCY_NUMBER_OVERRIDE"
        const val METHOD_SET_GESTURE = "SET_EMERGENCY_GESTURE"
        const val METHOD_SET_GESTURE_UI_SHOWING = "SET_EMERGENCY_GESTURE_UI_SHOWING"
        const val METHOD_SET_SOUND = "SET_EMERGENCY_SOUND"
        const val METHOD_GET_GESTURE_ENABLED = "GET_EMERGENCY_GESTURE"
        const val METHOD_GET_SOUND_ENABLED = "GET_EMERGENCY_SOUND"
        const val EXTRA_CALL_NUMBER = "emergency_gesture_call_number"
        const val EXTRA_UI_SHOWING_VALUE = "emergency_gesture_ui_showing_value"
        const val EXTRA_VALUE = "emergency_setting_value"
        const val SETTING_ON = 1
        const val SETTING_OFF = 0

        private fun readSecureInt(context: Context, key: String, default: Int): Int =
            runCatching {
                Settings.Secure.getInt(context.contentResolver, key, default)
            }.getOrDefault(default)
    }
}
