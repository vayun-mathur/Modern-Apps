package com.vayunmathur.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.web.R

/**
 * Diameter of the circular tab-count indicator, and therefore the
 * height of the omnibox beside it: the two chrome controls share one
 * size so the bar reads as one row of equal-height controls. This is
 * the height the indicator already had (labelLarge line height plus
 * its old 8.dp vertical padding), so only the width stops varying
 * with the digit count. No shared repo dimension exists for this —
 * `library.ui.Spacing` is spacing, not control sizes.
 */
internal val ChromeControlHeight = 36.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserChrome(
    omniboxText: String,
    tabCount: Int,
    progress: Float = 0f,
    atBottom: Boolean = false,
    onOmniboxClick: () -> Unit = {},
    onTabSwitcherClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    menu: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    // RAW SCAFFOLD EXCEPTION: bespoke browser toolbar chrome. The bar is a
    // Column of a custom TopAppBar (a tappable read-only address pill as the
    // title, with tab-count + overflow-menu actions on a surface-colored bar)
    // with a page LinearProgressIndicator drawn alongside it, and it can sit
    // in either the top or the bottom slot. That composite bar has no
    // equivalent in the shared scaffolds, and the content is the full-bleed
    // WebView. Back/forward/shields live in the overflow menu instead.
    val bar: @Composable () -> Unit = {
        // At the bottom, TopAppBar's hardcoded top inset would be a status bar's
        // height of dead space, and nothing else applies the navigation bar inset.
        // The bar's own container color stops at the padding, so the surface is
        // painted here instead to reach the screen edge. The IME inset is left alone
        // on purpose: MainNavigation already owns it, and navigationBarsPadding
        // resolves to zero once the keyboard has consumed more than a navigation
        // bar's height.
        val barModifier = if (atBottom) {
            Modifier
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .consumeWindowInsets(WindowInsets.statusBars)
        } else {
            Modifier
        }
        Column(barModifier) {
            // The progress bar belongs against the web content, so it moves to the
            // far side of the toolbar when the toolbar moves to the bottom.
            if (atBottom) PageProgress(progress)
            TopAppBar(
                title = {
                    DisplayOnlyAddressPill(
                        fullUrl = omniboxText,
                        onClick = onOmniboxClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                actions = {
                    // A true circle of ChromeControlHeight diameter, not a
                    // pill whose width grows with the digit count. The
                    // clickable area is exactly the circle it was before
                    // (same height) inside the TopAppBar row.
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .size(ChromeControlHeight)
                            .clip(CircleShape)
                            .clickable(onClick = onTabSwitcherClick)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = tabCount.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onMenuClick) { IconMoreVert() }
                    menu()
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
            if (!atBottom) PageProgress(progress)
        }
    }
    Scaffold(
        topBar = { if (!atBottom) bar() },
        bottomBar = { if (atBottom) bar() },
        content = content,
    )
}

/** The page load indicator, drawn only while a load is actually in flight. */
@Composable
private fun PageProgress(progress: Float) {
    if (progress in 0.01f..0.99f) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
    }
}

@Composable
private fun DisplayOnlyAddressPill(
    fullUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Exactly as tall as the circular tab-count indicator beside it
    // (ChromeControlHeight): the field's container shrinks and its
    // internal vertical padding with it, while the text stays bodySmall
    // at the same size — only padding was spent on the extra height.
    Box(modifier = modifier) {
        OutlinedTextField(
            value = fullUrl,
            onValueChange = {},
            readOnly = true,
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = { Text(stringResource(R.string.search_or_enter_address), style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().height(ChromeControlHeight)
        )
        // Overlay to handle tap without focusing the field
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(28.dp))
                .clickable(onClick = onClick)
        )
    }
}
