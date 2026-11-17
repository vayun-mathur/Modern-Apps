package com.vayunmathur.office.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.office.R
import com.vayunmathur.office.Route
import com.vayunmathur.office.platform.OfficeDocMeta
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.createNewPresentation
import com.vayunmathur.office.platform.createNewSpreadsheet
import com.vayunmathur.office.platform.createNewTextDocument

/**
 * The home screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
private val homeScreenPadding = 24.dp
private val homeSectionGap = 32.dp
private val homeItemGap = 16.dp
private val homeSmallGap = 8.dp
private val homeTinyGap = 4.dp

@Composable
fun HomeScreen(
    onOpenDocument: () -> Unit = {},
    onNewTextDocument: () -> Unit = {},
    onNewSpreadsheet: () -> Unit = {},
    onNewPresentation: () -> Unit = {},
) {
    AppScaffold(title = stringResource(R.string.app_name), scrollBehavior = appBarScrollBehavior()) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background)
                .padding(homeScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(homeScreenPadding))
            Text(
                stringResource(R.string.open_document_format_viewer_editor),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(homeSectionGap))

            Button(
                onClick = onOpenDocument,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_document)) }
            Spacer(Modifier.height(homeTinyGap))
            Text(stringResource(R.string.opens_odf_word_excel_powerpoint_csv_tsv),
                style =
                    MaterialTheme.typography.bodySmall, color =
                        MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(homeItemGap))

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(homeSmallGap)) {
                OutlinedButton(
                    onClick = onNewTextDocument,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_doc)) }
                OutlinedButton(
                    onClick = onNewSpreadsheet,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_sheet)) }
                OutlinedButton(
                    onClick = onNewPresentation,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_slides)) }
            }
        }
    }
}
