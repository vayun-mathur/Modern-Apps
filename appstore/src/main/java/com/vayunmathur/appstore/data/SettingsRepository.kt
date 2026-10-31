package com.vayunmathur.appstore.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "appstore-settings")

/**
 * User-adjustable preferences for the store.
 *
 * The persisted value is mirrored into a [StateFlow] so the settings UI can render the
 * stored choice without a suspending read. The default is applied on read
 * ([DEFAULT_AUTO_INSTALL_UPDATES]) rather than written at first launch, so an existing user
 * with nothing stored still gets the documented default.
 */
class SettingsRepository(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val _autoInstallUpdates = MutableStateFlow(DEFAULT_AUTO_INSTALL_UPDATES)
    private val _enabledSources = MutableStateFlow(AppSource.DEFAULT_ENABLED)

    /**
     * Whether the periodic update check may also download and install updates on its own,
     * with no tap. Off by default: this installs apps without the user present, so it is
     * strictly opt-in. With it off the same updates wait for the user to ask for them.
     * Either way the install itself is silent, so this only decides who starts it. Only
     * ever acts on updates that install silently (see the worker).
     */
    val autoInstallUpdates: StateFlow<Boolean> = _autoInstallUpdates.asStateFlow()

    /**
     * The sources the store may talk to. First-party repos (MODERN_APPS,
     * PROPRIETARY) are on for a fresh install; FDROID, PLAYSTORE and
     * ACCRESCENT are third-party and start off until the user opts in.
     *
     * What is persisted is the *disabled* set once the user edits anything;
     * until then the read returns [AppSource.DEFAULT_ENABLED]. A source added
     * in a later version starts out off for everyone who already had the app.
     */
    val enabledSources: StateFlow<Set<AppSource>> = _enabledSources.asStateFlow()

    init {
        scope.launch {
            context.settingsDataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .collect { prefs ->
                    _autoInstallUpdates.value = prefs.autoInstallUpdates()
                    _enabledSources.value = prefs.enabledSources()
                }
        }
    }

    suspend fun setAutoInstallUpdates(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_INSTALL_UPDATES] = enabled }
    }

    suspend fun setSourceEnabled(source: AppSource, enabled: Boolean) {
        if (source !in AppSource.TOGGLEABLE) return
        context.settingsDataStore.edit { prefs ->
            // Resolve against the effective set (fresh installs get DEFAULT_ENABLED),
            // then store the full disabled complement so the read path stays explicit
            // from the first user edit onward.
            val current = prefs.enabledSources()
            val next = if (enabled) current + source else current - source
            prefs[KEY_DISABLED_SOURCES] =
                AppSource.entries.map { it.name }.toSet() - next.map { it.name }.toSet()
        }
    }

    /**
     * One-shot read of the persisted value, for callers with no long-lived scope to
     * collect a flow — the [UpdateCheckWorker] is cold-started by WorkManager and needs the
     * committed choice, not the not-yet-populated flow default.
     */
    suspend fun readAutoInstallUpdates(): Boolean = read().autoInstallUpdates()

    /** One-shot read of [enabledSources], for the same cold-started callers. */
    suspend fun readEnabledSources(): Set<AppSource> = read().enabledSources()

    private suspend fun read(): Preferences =
        context.settingsDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()

    private fun Preferences.autoInstallUpdates(): Boolean =
        this[KEY_AUTO_INSTALL_UPDATES] ?: DEFAULT_AUTO_INSTALL_UPDATES

    private fun Preferences.enabledSources(): Set<AppSource> {
        // An unrecognised name is ignored rather than treated as a disabled source, so a
        // downgrade cannot leave the store permanently unable to re-enable something.
        // Sources added by later versions start out off for existing users (opt-in);
        // first-party repos stay on. Only DEFAULT_ENABLED is on for a fresh install.
        return if (this[KEY_DISABLED_SOURCES] == null) {
            AppSource.DEFAULT_ENABLED
        } else {
            val disabled = this[KEY_DISABLED_SOURCES].orEmpty()
                .mapNotNullTo(mutableSetOf()) { runCatching { AppSource.valueOf(it) }.getOrNull() }
            AppSource.entries.toSet() - disabled
        }
    }

    companion object {
        const val DEFAULT_AUTO_INSTALL_UPDATES = false
        private val KEY_AUTO_INSTALL_UPDATES =
            booleanPreferencesKey("auto_install_updates")
        private val KEY_DISABLED_SOURCES =
            stringSetPreferencesKey("disabled_sources")
    }
}
