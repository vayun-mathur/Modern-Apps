package com.vayunmathur.todo.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.SettingsDivider
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.todo.R
import com.vayunmathur.todo.sync.CalendarMirror

/** Calendar mirror toggle (asks for calendar access when turned on) and sync status. */
@Composable
fun SyncSettingsSection(
    calendarMirror: Boolean,
    onCalendarMirror: (Boolean) -> Unit,
    lastSyncAt: Long,
    pendingChanges: Int,
) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) onCalendarMirror(true)
    }
    SettingsSection(title = stringResource(R.string.settings_sync)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_calendar_mirror),
            supportingText = stringResource(R.string.settings_calendar_mirror_summary),
            checked = calendarMirror && CalendarMirror.hasPermission(context),
            onCheckedChange = { on ->
                when {
                    !on -> onCalendarMirror(false)
                    CalendarMirror.hasPermission(context) -> onCalendarMirror(true)
                    else -> permission.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                }
            },
        )
        val status = syncStatus(lastSyncAt)
        val launch = context.packageManager.getLaunchIntentForPackage(EVERYSYNC)
        SettingsRow(
            title = stringResource(R.string.settings_everysync),
            supportingText = if (status == null) {
                stringResource(R.string.settings_everysync_never)
            } else {
                listOfNotNull(
                    status,
                    pendingChanges.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.pending_changes, it, it) },
                ).joinToString(" · ")
            },
            enabled = launch != null,
            onClick = { launch?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        )
    }
    SettingsDivider()
}

private const val EVERYSYNC = "com.vayunmathur.everysync"
