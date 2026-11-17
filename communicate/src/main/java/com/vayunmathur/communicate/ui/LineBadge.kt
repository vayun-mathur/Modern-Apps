package com.vayunmathur.communicate.ui

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
import com.vayunmathur.library.ui.rememberPermissionRequest
import java.util.Date

/** Convenience: tag for a stored line (SIM name / "Voice"). */
@Composable
fun LineBadge(line: CommunicateLine, modifier: Modifier = Modifier, subscriptionId: Int? = null) {
    LineTag(lineLabel(line, subscriptionId), modifier = modifier)
}
