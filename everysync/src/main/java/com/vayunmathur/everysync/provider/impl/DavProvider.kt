package com.vayunmathur.everysync.provider.impl

import android.content.Context
import androidx.compose.runtime.Composable
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.TokenStore
import com.vayunmathur.everysync.provider.AuthType
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.SyncDirection
import com.vayunmathur.everysync.provider.SyncProvider
import com.vayunmathur.everysync.remote.DavClient
import com.vayunmathur.everysync.remote.DavSync

/**
 * Base CalDAV/CardDAV provider. Resource hrefs are used as the local SOURCE_ID /
 * _SYNC_ID so ETag comparison is direct; server deletions are detected by set
 * difference. Two-way: local edits are pushed back via PUT/DELETE with If-Match.
 */
abstract class DavProvider(
    override val id: String,
    override val displayName: String,
    override val icon: @Composable () -> Unit,
    override val capabilities: Set<DataType>,
    override val davPresetUrl: String? = null,
) : SyncProvider {
    override val authType = AuthType.DAV

    /** Base URL to discover addressbook collections from. */
    protected open fun contactsBaseUrl(config: AccountConfig, creds: com.vayunmathur.everysync.auth.DavCredentials): String =
        config.davBaseUrl ?: creds.baseUrl

    /** Base URL to discover calendar collections from. */
    protected open fun calendarBaseUrl(config: AccountConfig, creds: com.vayunmathur.everysync.auth.DavCredentials): String =
        config.davBaseUrl ?: creds.baseUrl

    override suspend fun sync(context: Context, config: AccountConfig, direction: SyncDirection) {
        val creds = TokenStore.getInstance(context).getDav(config.accountName) ?: return
        val client = DavClient(creds)
        val account = config.accountName

        if (DataType.CONTACTS in capabilities && DataType.CONTACTS in config.enabledTypes) {
            DavSync.syncContacts(context, account, client, contactsBaseUrl(config, creds), direction)
        }
        if (DataType.CALENDAR in capabilities && DataType.CALENDAR in config.enabledTypes) {
            DavSync.syncCalendars(context, account, client, calendarBaseUrl(config, creds), direction)
        }
    }
}
