package com.vayunmathur.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.platform.DrawerActions
import com.vayunmathur.launcher.platform.DrawerUiState
import com.vayunmathur.launcher.ui.components.FastScrollStrip
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.PrimaryTabRow
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.launch

/**
 * The app drawer, as an overlay over the home screen rather than a separate destination.
 *
 * That is the whole reason it is not a nav route: an app has to be draggable straight out of here
 * and onto the grid, and a drag cannot survive crossing two destinations. Drawn inside the home
 * screen's gesture-owning root, every icon here registers with the same
 * [com.vayunmathur.launcher.ui.components.LauncherDragController] as a workspace item does — with
 * a null `itemId`, which is what marks it as something to insert rather than move. Launcher3
 * places AllApps in its `DragLayer` for exactly the same reason.
 *
 * A [LazyVerticalGrid] here, unlike the home screen: the drawer really is a flowing list of
 * uniform 1x1 items, which is what a lazy grid is for. The row spans and absolute cell
 * coordinates that rule it out for the workspace simply do not arise.
 *
 * [gridState] is hoisted because the home screen's gesture owner needs it: a downward swipe closes
 * the drawer, but only from the top of this list — anywhere else that swipe is this list's own
 * scrolling. [draggable] is false while the drawer is only part-way up, so a long press cannot pick
 * an app out of a drawer that is barely on screen. [strip] is hoisted for the same reason and is the
 * more delicate of the two: see [FastScrollStrip].
 */
@Composable
fun DrawerContent(
    state: DrawerUiState,
    actions: DrawerActions,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    draggable: Boolean = true,
    strip: FastScrollStrip? = null,
    onDismiss: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var showWork by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { onDismiss() }

    // Work apps are a separate tab rather than mixed in, as Launcher3 does: they are a different
    // profile with different rules, and a personal and a work copy of one app are otherwise two
    // identical rows next to each other.
    val hasWork = state.apps.any { it.isWorkProfile }
    val work = hasWork && showWork
    val apps = if (hasWork) state.apps.filter { it.isWorkProfile == work } else state.apps

    Box(
        modifier = modifier
            .fillMaxSize()
            // A sheet with rounded top corners, not a full-bleed fill: the wallpaper stays visible
            // at the corners, which is what says this is something pulled up over the workspace
            // rather than a different screen. The blur behind it is the window's own, since a sheet
            // in the same window cannot blur what is behind that window by itself.
            .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = SHEET_ALPHA)),
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            // The handle Launcher3 draws at the top of the sheet (`bottom_sheet_handle`). It says
            // the surface is draggable, which is the only affordance the swipe-to-close gesture has.
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = HANDLE_WIDTH, height = HANDLE_HEIGHT)
                        .clip(RoundedCornerShape(HANDLE_HEIGHT / 2))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }

            CommonSearchBar(
                value = state.query,
                onValueChange = actions::setQuery,
                // The shared default placeholder is a hardcoded "Search", which says nothing on a
                // screen that is only ever a list of apps.
                placeholder = "Search apps",
                padding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            )

            // Predictions are about reaching for something without looking; a filtered list is
            // already the user telling us what they want, so the row would be in the way.
            if (state.query.isBlank() && !work && state.predictions.isNotEmpty()) {
                PredictionsRow(state, actions, draggable)
                // Launcher3's `apps_divider_view`: the predictions are a different kind of list from
                // the alphabet below them, and without a rule between them they read as its first row.
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }

            if (hasWork) {
                PrimaryTabRow(selectedTabIndex = if (work) 1 else 0) {
                    Tab(
                        selected = !work,
                        onClick = { showWork = false },
                        text = { Text("Personal") },
                    )
                    Tab(
                        selected = work,
                        onClick = { showWork = true },
                        text = { Text("Work") },
                    )
                }
            }

            // Only on a build that can actually pause the profile, which is a privileged one. See
            // com.vayunmathur.launcher.platform.LauncherPrivilege.
            if (work) {
                state.workPaused?.let { paused ->
                    SettingsSwitchRow(
                        title = "Pause work apps",
                        supportingText = "Work apps and notifications stop until you turn this off",
                        checked = paused,
                        onCheckedChange = actions::setWorkPaused,
                    )
                }
            }

            if (apps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.loading) {
                        Text("Loading…")
                    } else {
                        Text(
                            if (state.query.isBlank()) "No apps found"
                            else "No apps match \"${state.query}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@Column
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    // One column is what makes this a list: the same lazy grid, the same hoisted
                    // state, so the swipe-to-close gesture and the fast scroller carry over
                    // untouched rather than needing a second code path for a LazyColumn.
                    columns =
                        if (state.listLayout) GridCells.Fixed(1)
                        else GridCells.Adaptive(DRAWER_CELL_MIN),
                    state = gridState,
                    contentPadding = PaddingValues(Spacing.sm),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(apps, key = { it.key.componentName.flattenToShortString() + it.key.profileSerial }) { app ->
                        DrawerCell(
                            app = app,
                            state = state,
                            actions = actions,
                            draggable = draggable,
                            listLayout = state.listLayout,
                        )
                    }
                }

                // Overlaid on the grid's right edge rather than beside it, which is what Launcher3's
                // negative `fastscroll_end_margin` achieves: the strip is a wide touch region, and
                // letting it take layout space costs the grid a whole column.
                //
                // Only useful on an unfiltered list; once a query has cut it down, scrolling is
                // short and the strip is noise.
                if (state.query.isBlank()) {
                    FastScrollThumb(
                        apps = apps,
                        strip = strip,
                        gridState = gridState,
                        onJump = { index -> scope.launch { gridState.scrollToItem(index) } },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }

                strip?.active?.let { fraction ->
                    LetterBubble(apps = apps, fraction = fraction)
                }
            }
        }
    }
}

/** Launcher3's `bottom_sheet_handle`. */
private val HANDLE_WIDTH = 48.dp
private val HANDLE_HEIGHT = 2.dp

/** Wide enough for a label under a 48dp icon without truncating most app names. */
private val DRAWER_CELL_MIN = 76.dp

/** The corner radius of the sheet the drawer is drawn as. */
private val SHEET_CORNER = 28.dp

/** Translucent over the blurred wallpaper, rather than a flat fill that hides it entirely. */
private const val SHEET_ALPHA = 0.92f
