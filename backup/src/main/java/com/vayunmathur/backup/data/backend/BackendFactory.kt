package com.vayunmathur.backup.data.backend

import androidx.core.net.toUri
import android.content.Context
import com.vayunmathur.backup.data.BackendType
import com.vayunmathur.backup.data.BackupSettings

/** Builds the active [BackupBackend] from persisted [BackupSettings]. */
object BackendFactory {
    fun create(context: Context, settings: BackupSettings): BackupBackend? =
        when (settings.backendType) {
            BackendType.NONE -> null
            BackendType.SAF -> settings.safTreeUri
                ?.takeIf { it.isNotBlank() }
                ?.let { SafBackend(context.applicationContext, it.toUri()) }
            BackendType.WEBDAV -> settings.webdavUrl
                .takeIf { it.isNotBlank() }
                ?.let {
                    WebDavBackend(it, settings.webdavUser, settings.webdavPassword)
                }
        }
}
