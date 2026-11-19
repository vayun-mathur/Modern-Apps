package com.vayunmathur.office.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.OfflineBanner
import com.vayunmathur.library.util.rememberIsOnline
import com.vayunmathur.office.platform.OfficeDocMeta
import com.vayunmathur.office.platform.OfficeMember
import com.vayunmathur.office.platform.OfficeRoles
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.approveJoinRequest
import com.vayunmathur.office.platform.denyRequest
import com.vayunmathur.office.platform.enableOnlineSharing
import com.vayunmathur.office.platform.initSync
import com.vayunmathur.office.platform.shareLinkFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.vayunmathur.office.sync.OfficeSync
import com.vayunmathur.office.R
import com.vayunmathur.office.platform.refreshOnline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineTab(viewModel: OfficeViewModel, onOpenDoc: (OfficeDocMeta) -> Unit) {
    val onlineEnabled by viewModel.onlineEnabled.collectAsState()
    if (!onlineEnabled) {
        OnlineDisabledScreen(onEnable = { viewModel.enableOnlineSharing() })
        return
    }
    OnlineInit(viewModel)
    val docs by viewModel.onlineDocs.collectAsState()
    val requests by viewModel.pendingRequests.collectAsState()
    val deviceId = viewModel.syncDeviceId
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val online by rememberIsOnline()
    val context = LocalContext.current
    AppScaffold(
        title = stringResource(R.string.online),
        actions = {
            IconButton(onClick = { viewModel.refreshOnline() }) {
                IconRefresh()
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OfflineBanner(online)
            Column(Modifier.fillMaxSize().padding(horizontal = SpacingContent)) {
                OnlineDeviceHeader(deviceId, scope, clipboard)
                Spacer(Modifier.height(SpacingSm))
                OnlineDocLists(viewModel, docs, requests, context, onOpenDoc)
            }
        }
    }
}
