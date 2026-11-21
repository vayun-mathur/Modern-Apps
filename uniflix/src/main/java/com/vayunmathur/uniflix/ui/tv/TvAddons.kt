package com.vayunmathur.uniflix.ui.tv

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.tv.TvBadge
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvIconButton
import com.vayunmathur.library.tv.TvInfoCard
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvTextField
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.friendlyError
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconUpload
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.domain.AddonKind
import com.vayunmathur.uniflix.platform.AddonRow
import com.vayunmathur.uniflix.platform.AddonsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val JS_MIME_TYPES = arrayOf("text/javascript", "application/javascript", "text/plain")

/**
 * TV addon manager: a 2-column grid of full-information addon cards (name,
 * URL/version subtitle, last-error description, Default/Disabled/Error
 * badges), opening the addon detail on CENTER. Header actions add by URL and
 * import a .js plugin, mirroring the phone manager.
 */
@Composable
fun TvAddonsTab(onOpenDetail: (addonDbId: Long) -> Unit) {
    val vm: AddonsViewModel = viewModel()
    val rows by vm.addons.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val source = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                        ?: error("Could not read file")
                }
                vm.addJsPlugin(source)
            }.onFailure {
                AppMessages.show(context.friendlyError(it))
            }
        }
    }
    val importJs: () -> Unit = { importLauncher.launch(JS_MIME_TYPES) }

    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus, key = rows.firstOrNull()?.entity?.dbId ?: "add")

    TvScreenHeader(
        title = stringResource(R.string.stream_addons),
        subtitle = stringResource(R.string.addons_explainer),
        actions = {
            TvIconButton(onClick = { vm.refreshAll() }, icon = { IconRefresh() })
            TvIconButton(onClick = importJs, icon = { IconUpload() })
            TvIconButton(onClick = { showAddDialog = true }, icon = { IconAdd() })
        },
    )
    if (rows.isEmpty()) {
        TvEmptyState(
            title = stringResource(R.string.no_addons_yet),
            action = {
                TvButton(
                    text = stringResource(R.string.add_addon),
                    onClick = { showAddDialog = true },
                    modifier = Modifier.focusRequester(firstFocus),
                    icon = { IconAdd() },
                    primary = true,
                )
            },
        )
    } else {
        TvGrid(columns = 2) {
            items(rows, key = { "addon-${it.entity.dbId}" }) { row ->
                TvAddonCard(
                    row = row,
                    modifier = Modifier.focusIf(
                        "addon-${row.entity.dbId}",
                        rows.firstOrNull()?.let { "addon-${it.entity.dbId}" },
                        firstFocus,
                    ),
                    onOpenDetail = { onOpenDetail(row.entity.dbId) },
                )
            }
            tvFullWidth(key = "end-anchor") {}
        }
    }
    if (showAddDialog) {
        TvAddAddonDialog(
            adding = vm.adding,
            onDismiss = { showAddDialog = false },
            onConfirm = { url ->
                vm.addByUrl(url)
                showAddDialog = false
            },
            onImportJs = {
                showAddDialog = false
                importJs()
            },
        )
    }
}

/** One addon as a full-information card: name, URL/version, error, badges. */
@Composable
internal fun TvAddonCard(
    row: AddonRow,
    modifier: Modifier = Modifier,
    onOpenDetail: () -> Unit,
) {
    val entity = row.entity
    TvInfoCard(
        title = entity.name,
        onClick = onOpenDetail,
        modifier = modifier,
        subtitle = listOfNotNull(
            entity.baseUrl.ifBlank { entity.manifestUrl.ifBlank { null } },
            entity.version.ifBlank { null }?.let { "v$it" },
        ).joinToString("  •  ").ifBlank { null },
        description = entity.lastError,
        badges = {
            if (row.isDefault) TvBadge(text = stringResource(R.string.default_addon_short))
            if (!entity.enabled) TvBadge(text = stringResource(R.string.disabled))
            if (entity.lastError != null) TvBadge(text = stringResource(R.string.error_badge))
            if (entity.kind == AddonKind.JS) TvBadge(text = stringResource(R.string.js_plugin))
        },
    )
}

/** Add-by-URL dialog: a D-pad text field plus Import .js / Add / Cancel. */
@Composable
internal fun TvAddAddonDialog(
    adding: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onImportJs: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    TvDialog(
        title = stringResource(R.string.add_addon),
        onDismissRequest = onDismiss,
        body = stringResource(R.string.addon_url_example),
        content = {
            TvTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = stringResource(R.string.addon_url_hint),
                keyboardType = KeyboardType.Uri,
            )
            TvButton(
                text = stringResource(R.string.import_js),
                onClick = onImportJs,
                enabled = !adding,
            )
        },
        buttons = {
            TvButton(
                text = stringResource(UiR.string.cancel),
                onClick = onDismiss,
                enabled = !adding,
            )
            TvButton(
                text = stringResource(UiR.string.add),
                onClick = { onConfirm(url) },
                enabled = !adding && url.isNotBlank(),
                primary = true,
            )
        },
    )
}

