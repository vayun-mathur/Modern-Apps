package com.vayunmathur.communicate.ui

import androidx.compose.runtime.mutableIntStateOf
import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SimManager
import com.vayunmathur.communicate.data.call.inAppCallPermissions
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCall
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMultiplePermissionRequest
import com.vayunmathur.library.ui.rememberPermissionRequest
import java.util.Date

@Composable
fun PermissionGate(
    permission: String,
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val context = LocalContext.current
    var grantRevision by remember { mutableIntStateOf(0) }
    val requestPermission = rememberPermissionRequest(permission) { grantRevision++ }
    val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    if (granted) {
        content(grantRevision)
    } else {
        EmptyState(
            title = stringResource(R.string.permission_title),
            message = message,
            icon = { IconCall() },
            action = {
                Button(onClick = requestPermission) {
                    Text(stringResource(R.string.grant_permission))
                }
            },
            modifier = modifier.fillMaxSize(),
        )
    }
}

/**
 * Contextual call-start permission gate, following [PermissionGate]'s rationale pattern without
 * gating a whole screen: the returned starter runs [start] immediately when the call's
 * permissions are already granted; otherwise it calls [onRationale] (the explanation the user
 * sees, via the messenger) and launches the shared request — voice needs RECORD_AUDIO, video
 * needs CAMERA + RECORD_AUDIO (see [inAppCallPermissions]). A granted result then runs the
 * pending [start]; a denial drops it and calls [onDenied], so the call never starts without
 * permission. Permanent denial is handled by the shared helpers, which open app settings.
 */
@Composable
internal fun rememberInAppCallPermissionStarter(
    onRationale: (video: Boolean) -> Unit,
    onDenied: (video: Boolean) -> Unit,
): (video: Boolean, start: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingVideo by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun handleResult(granted: Boolean) {
        val start = pendingStart
        val video = pendingVideo
        pendingStart = null
        if (granted) start?.invoke() else onDenied(video)
    }

    val requestAudio = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO) { granted ->
        handleResult(granted)
    }
    val requestVideo = rememberMultiplePermissionRequest(
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
    ) { allGranted -> handleResult(allGranted) }

    return { video, start ->
        val missing = inAppCallPermissions(video).any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (!missing) {
            start()
        } else {
            pendingVideo = video
            pendingStart = start
            onRationale(video)
            if (video) requestVideo() else requestAudio()
        }
    }
}

fun Context.hasCommunicatePermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.hasContactsPermission(): Boolean = hasCommunicatePermission(Manifest.permission.READ_CONTACTS)

fun formatDateTime(context: Context, timestampMillis: Long): String {
    val now = System.currentTimeMillis()
    return when {
        DateUtils.isToday(timestampMillis) -> DateFormat.getTimeFormat(context).format(Date(timestampMillis))
        now - timestampMillis < DateUtils.WEEK_IN_MILLIS -> DateUtils.formatDateTime(
            context,
            timestampMillis,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
        )
        else -> DateFormat.getMediumDateFormat(context).format(Date(timestampMillis))
    }
}

fun initialsFor(text: String): String = text
    .split(' ', '+', '-', '_')
    .filter { it.isNotBlank() }
    .take(2)
    .joinToString("") { it.first().uppercase() }
    .ifBlank { "?" }

