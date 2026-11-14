package com.vayunmathur.backup.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Which storage destination a backup is written to. */
enum class BackendType { NONE, SAF, WEBDAV }

/**
 * An immutable snapshot of the backup configuration. Credentials for a WebDAV remote
 * are held here for simplicity; on a real deployment these should be wrapped by the
 * AndroidKeyStore (see [com.vayunmathur.backup.crypto.KeyManager]).
 */
data class BackupSettings(
    val backendType: BackendType = BackendType.NONE,
    val safTreeUri: String? = null,
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val webdavPassword: String = "",
    val appBackupEnabled: Boolean = false,
    val fileBackupEnabled: Boolean = false,
    val lastRun: Long = 0L,
) {
    val isConfigured: Boolean
        get() = when (backendType) {
            BackendType.NONE -> false
            BackendType.SAF -> !safTreeUri.isNullOrBlank()
            BackendType.WEBDAV -> webdavUrl.isNotBlank()
        }
}

private val Context.backupDataStore by preferencesDataStore(name = "backup_config")

/** DataStore-backed persistence for [BackupSettings]. */
class BackupConfig(private val context: Context) {
    val settings: Flow<BackupSettings> = context.backupDataStore.data.map { p ->
        BackupSettings(
            backendType = p[KEY_BACKEND]?.let { runCatching { BackendType.valueOf(it) }.getOrNull() }
                ?: BackendType.NONE,
            safTreeUri = p[KEY_SAF_URI],
            webdavUrl = p[KEY_WEBDAV_URL] ?: "",
            webdavUser = p[KEY_WEBDAV_USER] ?: "",
            webdavPassword = p[KEY_WEBDAV_PASS] ?: "",
            appBackupEnabled = (p[KEY_APP_ENABLED] ?: 0L) == 1L,
            fileBackupEnabled = (p[KEY_FILE_ENABLED] ?: 0L) == 1L,
            lastRun = p[KEY_LAST_RUN] ?: 0L,
        )
    }

    suspend fun setSafBackend(treeUri: String) {
        context.backupDataStore.edit {
            it[KEY_BACKEND] = BackendType.SAF.name
            it[KEY_SAF_URI] = treeUri
        }
    }

    suspend fun setWebDavBackend(url: String, user: String, password: String) {
        context.backupDataStore.edit {
            it[KEY_BACKEND] = BackendType.WEBDAV.name
            it[KEY_WEBDAV_URL] = url
            it[KEY_WEBDAV_USER] = user
            it[KEY_WEBDAV_PASS] = password
        }
    }

    suspend fun setAppBackupEnabled(enabled: Boolean) {
        context.backupDataStore.edit { it[KEY_APP_ENABLED] = if (enabled) 1L else 0L }
    }

    suspend fun setFileBackupEnabled(enabled: Boolean) {
        context.backupDataStore.edit { it[KEY_FILE_ENABLED] = if (enabled) 1L else 0L }
    }

    suspend fun setLastRun(epochMillis: Long) {
        context.backupDataStore.edit { it[KEY_LAST_RUN] = epochMillis }
    }

    suspend fun clear() {
        context.backupDataStore.edit { it.clear() }
    }

    /** DataStore preference keys for [BackupConfig]. */
    companion object {
        private val KEY_BACKEND = stringPreferencesKey("backend_type")
        private val KEY_SAF_URI = stringPreferencesKey("saf_tree_uri")
        private val KEY_WEBDAV_URL = stringPreferencesKey("webdav_url")
        private val KEY_WEBDAV_USER = stringPreferencesKey("webdav_user")
        private val KEY_WEBDAV_PASS = stringPreferencesKey("webdav_password")
        private val KEY_APP_ENABLED = longPreferencesKey("app_backup_enabled")
        private val KEY_FILE_ENABLED = longPreferencesKey("file_backup_enabled")
        private val KEY_LAST_RUN = longPreferencesKey("last_run")
    }
}
