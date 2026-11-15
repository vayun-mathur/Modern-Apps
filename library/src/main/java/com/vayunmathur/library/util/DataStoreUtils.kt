package com.vayunmathur.library.util

import android.content.Context
import android.os.UserManager
import com.vayunmathur.library.log.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// DataStore accessor overloads for each stored type (byteArray/long/boolean/double/string/
// stringSet × sync/suspend/flow/default variants) plus the Direct Boot seed helpers. Split
// across files would scatter one type's read/write pair; the count is inherent to the API
// surface. FileLength (fatal lint) still caps file size.
@Suppress("TooManyFunctions")
/** Typed synchronous, suspending and Flow accessors over the shared preferences DataStore. */
class DataStoreUtils internal constructor(private val dataStore: DataStore<Preferences>) {

    private constructor(context: Context) : this(createDataStore(context))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Eagerly mirror the persisted preferences so the synchronous getters below
    // can read the latest snapshot without blocking the calling thread.
    private val state: StateFlow<Preferences> =
        dataStore.data.stateIn(scope, SharingStarted.Eagerly, emptyPreferences())

    private fun <T> getWithFallback(key: Preferences.Key<T>): T? {
        return state.value[key]
    }

    fun getByteArray(name: String): ByteArray? {
        return getWithFallback(byteArrayPreferencesKey(name))
    }

    /** Suspend variant that awaits DataStore hydration (safe on cold start / service start). */
    suspend fun getByteArrayAwait(name: String): ByteArray? {
        return dataStore.data.first()[byteArrayPreferencesKey(name)]
    }

    suspend fun setByteArray(name: String, value: ByteArray, onlyIfAbsent: Boolean = false) {
        dataStore.edit {
            if (onlyIfAbsent && it.contains(byteArrayPreferencesKey(name))) return@edit
            it[byteArrayPreferencesKey(name)] = value
        }
    }

    fun getLong(name: String): Long? {
        return getWithFallback(longPreferencesKey(name))
    }

    /** Suspend variant that awaits DataStore hydration. */
    suspend fun getLongAwait(name: String): Long? {
        return dataStore.data.first()[longPreferencesKey(name)]
    }

    // `dataStore.data` re-emits the whole preference set on every write, so without this a
    // collector of one key wakes up for every change to any other. Anything watching a
    // handful of keys at once (the keyboard watches twelve) otherwise does that much
    // redundant work on each save.
    fun booleanFlow(name: String): Flow<Boolean> {
        return dataStore.data.mapNotNull { it[booleanPreferencesKey(name)] }.distinctUntilChanged()
    }

    /** Suspend variant that awaits DataStore hydration. */
    suspend fun getBooleanAwait(name: String, default: Boolean = false): Boolean {
        return dataStore.data.first()[booleanPreferencesKey(name)] ?: default
    }

    /** Defaulted variant: unlike [booleanFlow], emits before the key is first written. */
    fun booleanFlow(name: String, default: Boolean): Flow<Boolean> {
        return dataStore.data.map { it[booleanPreferencesKey(name)] ?: default }.distinctUntilChanged()
    }

    suspend fun setBoolean(name: String, value: Boolean) {
        dataStore.edit {
            it[booleanPreferencesKey(name)] = value
        }
    }

    fun longFlow(key: String): Flow<Long> {
        return dataStore.data.mapNotNull { it[longPreferencesKey(key)] }.distinctUntilChanged()
    }

    fun longFlow(name: String, default: Long): Flow<Long> {
        return dataStore.data.map { it[longPreferencesKey(name)] ?: default }.distinctUntilChanged()
    }

    suspend fun setLong(key: String, value: Long, onlyIfAbsent: Boolean = false) {
        dataStore.edit {
            if (onlyIfAbsent && it.contains(longPreferencesKey(key))) return@edit
            it[longPreferencesKey(key)] = value
        }
    }

    /** Atomically raises the stored value to [value] when it is larger. Returns true if it changed. */
    suspend fun setLongIfGreater(name: String, value: Long): Boolean {
        var updated = false
        dataStore.edit {
            val current = it[longPreferencesKey(name)] ?: 0L
            if (value > current) {
                it[longPreferencesKey(name)] = value
                updated = true
            }
        }
        return updated
    }

    fun doubleFlow(key: String): Flow<Double> {
        return dataStore.data.mapNotNull { it[doublePreferencesKey(key)] }.distinctUntilChanged()
    }

    fun getDouble(name: String): Double? {
        return getWithFallback(doublePreferencesKey(name))
    }

    suspend fun setDouble(key: String, value: Double) {
        dataStore.edit {
            it[doublePreferencesKey(key)] = value
        }
    }

    fun getString(key: String): String? {
        return getWithFallback(stringPreferencesKey(key))
    }

    /** Suspend variant that awaits DataStore hydration. */
    suspend fun getStringAwait(name: String): String? {
        return dataStore.data.first()[stringPreferencesKey(name)]
    }

    suspend fun setString(key: String, value: String, onlyIfAbsent: Boolean = false) {
        dataStore.edit {
            if (onlyIfAbsent && it.contains(stringPreferencesKey(key))) return@edit
            it[stringPreferencesKey(key)] = value
        }
    }

    /** Read-modify-write [name] in one edit, so concurrent callers can't drop each other's change. */
    suspend fun updateString(name: String, transform: (String?) -> String) {
        dataStore.edit {
            it[stringPreferencesKey(name)] = transform(it[stringPreferencesKey(name)])
        }
    }

    fun stringFlow(key: String): Flow<String> {
        return dataStore.data.mapNotNull { it[stringPreferencesKey(key)] }.distinctUntilChanged()
    }

    fun stringSetFlow(key: String): Flow<Set<String>> {
        return dataStore.data.map { it[stringSetPreferencesKey(key)] ?: emptySet() }.distinctUntilChanged()
    }

    /**
     * Suspend variant that awaits DataStore hydration. Use this rather than a snapshot read
     * when the caller may run before the store has loaded — e.g. a service started at boot.
     */
    suspend fun getStringSetAwait(name: String): Set<String> {
        return dataStore.data.first()[stringSetPreferencesKey(name)] ?: emptySet()
    }

    fun addStringToSet(key: String, id: String) {
        scope.launch {
            dataStore.edit {
                val set = it[stringSetPreferencesKey(key)] ?: setOf()
                it[stringSetPreferencesKey(key)] = set + id
            }
        }
    }

    /** Atomically adds [id] to the set, returning true only if it was not already present. */
    suspend fun addStringToSetIfAbsent(key: String, id: String): Boolean {
        var added = false
        dataStore.edit {
            val set = it[stringSetPreferencesKey(key)] ?: setOf()
            if (id !in set) {
                it[stringSetPreferencesKey(key)] = set + id
                added = true
            }
        }
        return added
    }

    fun removeStringFromSet(key: String, id: String) {
        scope.launch {
            dataStore.edit {
                val set = it[stringSetPreferencesKey(key)] ?: setOf()
                it[stringSetPreferencesKey(key)] = set - id
            }
        }
    }

    /**
     * Drop [names] entirely, whatever type they hold. Deleting the thing a namespaced key belongs to
     * has to delete the key too, or the store grows a tail of state nothing can ever reach again.
     */
    suspend fun removeKeys(names: Collection<String>) {
        dataStore.edit { prefs ->
            for (name in names) {
                // Every stored type, as documented above: callers
                // remove string session keys and double calibration
                // offsets, neither of which the old two-type removal
                // touched, so "cleared" values resurrected on reload.
                prefs.remove(stringSetPreferencesKey(name))
                prefs.remove(longPreferencesKey(name))
                prefs.remove(booleanPreferencesKey(name))
                prefs.remove(doublePreferencesKey(name))
                prefs.remove(stringPreferencesKey(name))
                prefs.remove(byteArrayPreferencesKey(name))
            }
        }
    }

    fun getBoolean(key: String, default: Boolean): Boolean {
        return getWithFallback(booleanPreferencesKey(key)) ?: default
    }

    /**
     * Copy the values [source] holds for [keys] into this store, and nothing else. Returns how
     * many keys were written.
     */
    internal suspend fun copyFrom(
        source: DataStoreUtils,
        keys: Collection<String>,
        overwrite: Boolean,
    ): Int {
        if (keys.isEmpty()) return 0
        val wanted = keys.toSet()
        val sourcePrefs = source.dataStore.data.first()
        // Skip the edit entirely when the source holds none of them, so an unseeded source is
        // a true no-op rather than a rewrite of the target file on every call.
        if (sourcePrefs.asMap().keys.none { it.name in wanted }) return 0

        var copied = 0
        dataStore.edit { copied = it.copyEntriesFrom(sourcePrefs, wanted, overwrite) }
        return copied
    }

    /** Singleton access and Direct Boot seeding for [DataStoreUtils]. */
    companion object {
        private const val TAG = "DataStoreUtils"

        @Volatile
        private var instance: DataStoreUtils? = null
        @Volatile
        private var deviceProtectedInstance: DataStoreUtils? = null
        /**
         * @param deviceProtected when true, the store is backed by device-protected storage
         *   (available in Direct Boot, i.e. before the first unlock after a reboot). Use this
         *   for components that must run on the lock screen — e.g. an IME used to type the
         *   unlock password. Data in device-protected storage is not credential-encrypted, so
         *   only put non-sensitive settings there. The two modes are separate singletons/files.
         *
         *   Separate files with no migration between them: a key written through the default
         *   store is *not* visible here, so a caller that switches to `deviceProtected = true`
         *   reads an empty store rather than its existing data. Use
         *   [seedDeviceProtectedStorage] to mirror the specific keys that are needed before
         *   unlock.
         */
        fun getInstance(context: Context, deviceProtected: Boolean = false): DataStoreUtils {
            if (deviceProtected) {
                return deviceProtectedInstance ?: synchronized(this) {
                    deviceProtectedInstance ?: DataStoreUtils(
                        context.applicationContext.createDeviceProtectedStorageContext()
                    ).also { deviceProtectedInstance = it }
                }
            }
            // First check (no locking for performance)
            return instance ?: synchronized(this) {
                // Second check (inside lock to ensure only one thread initializes)
                instance ?: DataStoreUtils(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * Mirror [keys] from the default (credential-encrypted) store into the
         * device-protected one, so a Direct Boot component can read them before the first
         * unlock. Returns how many keys were written.
         *
         * This copies; it does not move. The credential-encrypted original stays in place and
         * stays authoritative. Device-protected storage is readable without the user's
         * credential, so a value mirrored here has been deliberately taken out from behind the
         * passcode — which is why [keys] is an explicit allowlist and not "everything". Name
         * only the keys the pre-unlock path actually reads. (Contrast `SqlCipher`'s
         * `moveDatabaseFrom` for Room: a single database has one home, so moving it is right
         * there and wrong here.)
         *
         * By default a key is written only when it is absent from the device-protected store,
         * which makes repeat calls cheap and non-destructive. That matters because
         * `LOCKED_BOOT_COMPLETED` fires when an app leaves the stopped state as well as at a
         * real boot, so nothing in this area gets to assume "once per boot". Pass
         * [overwrite] for values that must track later changes — a user-facing toggle, say,
         * where pinning the first value seen would be a bug.
         *
         * **Must be called while the user is unlocked.** It reads credential-encrypted
         * storage, which is not merely empty before first unlock but unreadable, and the
         * failure would be cached in the singleton for the rest of the process. Call it from
         * an unlock-time path (`ACTION_USER_UNLOCKED`, or ordinary foreground use), never from
         * a Direct Boot receiver; doing so throws rather than silently seeding nothing.
         *
         * @throws IllegalStateException if invoked before the user has unlocked the device.
         */
        suspend fun seedDeviceProtectedStorage(
            context: Context,
            keys: Collection<String>,
            overwrite: Boolean = false,
        ): Int {
            val userManager = context.getSystemService(UserManager::class.java)
            // Checked before getInstance, so a locked caller cannot leave a broken
            // credential-encrypted singleton behind for everyone else.
            check(userManager == null || userManager.isUserUnlocked) {
                "seedDeviceProtectedStorage reads credential-encrypted storage and cannot run " +
                    "before the user unlocks the device. Call it from an unlock-time path, not " +
                    "from a LOCKED_BOOT_COMPLETED receiver."
            }
            val copied = getInstance(context, deviceProtected = true)
                .copyFrom(getInstance(context), keys, overwrite)
            if (copied > 0) Log.status(TAG, "seeded $copied key(s) into device-protected storage")
            return copied
        }
    }
}

private fun createDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { context.filesDir.resolve("datastore_default.preferences_pb") }

/**
 * Copy the entries [source] holds under [keys] into these preferences, leaving a key that is
 * already present alone unless [overwrite]. Returns how many were written.
 *
 * Entries are taken from [source] whole rather than rebuilt, so each value keeps the type it
 * was stored with and the caller does not have to declare it.
 */
internal fun MutablePreferences.copyEntriesFrom(
    source: Preferences,
    keys: Set<String>,
    overwrite: Boolean,
): Int {
    val wanted = source.asMap().filterKeys { it.name in keys }
    if (wanted.isEmpty()) return 0
    return copyWantedEntries(wanted, overwrite)
}

private fun MutablePreferences.copyWantedEntries(
    wanted: Map<Preferences.Key<*>, Any?>,
    overwrite: Boolean
): Int {
    var copied = 0
    for ((key, value) in wanted) {
        // The value came out of this very key, so it already has the type the key was stored
        // with; the cast only re-attaches what the star projection erased.
        @Suppress("UNCHECKED_CAST")
        val typed = key as Preferences.Key<Any>
        if (!overwrite && contains(typed)) continue
        @Suppress("UNCHECKED_CAST")
        this[typed] = value as Any
        copied++
    }
    return copied
}
