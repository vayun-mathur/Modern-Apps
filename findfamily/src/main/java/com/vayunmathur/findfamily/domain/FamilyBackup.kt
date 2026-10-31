package com.vayunmathur.findfamily.domain

/**
 * Manual backup contract for Find Family.
 *
 * The on-disk format is owned by `:library` ([com.vayunmathur.library.util.BackupHelper]):
 * a zip containing one `<dbName>.db` entry per database plus one `<store>.preferences_pb`
 * entry per DataStore. Those entry names have been stable since the format was
 * introduced ("general: add backup / restore functionality", 9db56fb09), so the
 * literals below double as the compatibility pin: backups written by any older
 * app version restore with the current code, because the reader derives the
 * same names from [dbName]/[datastoreName].
 *
 * The manual backup covers the same payload as the OS-triggered one
 * ([com.vayunmathur.findfamily.util.AppBackupAgent]): the encrypted Room
 * database plus the default DataStore, which holds the stable device identity
 * and the E2EE key material. Pre-removal manual backups contained only the
 * database entry; such legacy files still restore — the missing DataStore
 * entry is simply skipped by the reader.
 */
object FamilyBackup {
    /** Room database name, shared with [com.vayunmathur.findfamily.data.FindFamilyRepository]. */
    const val DB_NAME = "passwords-db"

    /** DataStore holding the device identity and E2EE keys. */
    const val DATASTORE_NAME = "datastore_default"

    /** Zip entry for the (decrypted) database export. */
    fun dbEntryName(dbName: String = DB_NAME): String = "$dbName.db"

    /** Zip entry for a DataStore export. */
    fun datastoreEntryName(storeName: String = DATASTORE_NAME): String = "$storeName.preferences_pb"

    /** Database configs for the backup format: name to SQLCipher passphrase. */
    fun dbConfigs(passphrase: String): List<Pair<String, String>> = listOf(DB_NAME to passphrase)

    /** DataStore names included in the backup. */
    fun datastoreNames(): List<String> = listOf(DATASTORE_NAME)

    /** What a backup zip contains, derived from its entry file names. */
    data class BackupContents(
        val databasePresent: Boolean,
        val datastorePresent: Boolean,
    ) {
        /** A file is restorable if it carries the database; the DataStore is optional. */
        val isRestorable: Boolean get() = databasePresent

        /** Database-only files written before the DataStore was included. */
        val isLegacy: Boolean get() = databasePresent && !datastorePresent
    }

    /**
     * Classifies a backup zip by its entry file names (e.g. `passwords-db.db`,
     * `datastore_default.preferences_pb`). Unknown extra entries are ignored:
     * other apps' files fail [isRestorable] instead of throwing.
     */
    fun describeEntries(entryNames: Collection<String>): BackupContents {
        val names = entryNames.map { it.substringAfterLast('/') }.toSet()
        return BackupContents(
            databasePresent = dbEntryName() in names,
            datastorePresent = datastoreEntryName() in names,
        )
    }
}
