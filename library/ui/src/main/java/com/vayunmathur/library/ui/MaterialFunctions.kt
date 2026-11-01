@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class,
)

package com.vayunmathur.library.ui

import android.content.Context
import androidx.compose.material3.toShape as m3ToShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon

/**
 * Re-exports of Material 3 top-level functions (color-scheme builders and `remember*`
 * state factories) that app code calls. Functions cannot be `typealias`ed, so each is a
 * thin delegate exposing the parameters app code actually uses.
 */

// --- Dynamic color schemes ---
fun dynamicLightColorScheme(context: Context) =
    androidx.compose.material3.dynamicLightColorScheme(context)

fun dynamicDarkColorScheme(context: Context) =
    androidx.compose.material3.dynamicDarkColorScheme(context)

// --- Drawer / picker state ---
@Composable
fun rememberDrawerState(
    initialValue: DrawerValue,
    confirmStateChange: (DrawerValue) -> Boolean = { true },
) = androidx.compose.material3.rememberDrawerState(initialValue, confirmStateChange)

@Composable
fun rememberTimePickerState(
    initialHour: Int = 0,
    initialMinute: Int = 0,
    is24Hour: Boolean = true,
    initialSelection: TimePickerSelectionMode = TimePickerSelectionMode.Hour,
): TimePickerState {
    val state = androidx.compose.material3.rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = is24Hour,
    )
    // M3 alpha27 exposes selection only as a mutable property, so apply the initial
    // value once when the state is created — never on recomposition, or user taps
    // on the minute dial would be stomped back.
    return remember(state) { state.apply { selection = initialSelection } }
}

@Composable
fun rememberDatePickerState(
    initialSelectedDateMillis: Long? = null,
    selectableDates: SelectableDates = androidx.compose.material3.DatePickerDefaults.AllDates,
) =
    androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = initialSelectedDateMillis,
        selectableDates = selectableDates,
    )

@Composable
fun rememberDateRangePickerState(
    initialSelectedStartDateMillis: Long? = null,
    initialSelectedEndDateMillis: Long? = null,
    selectableDates: SelectableDates = androidx.compose.material3.DatePickerDefaults.AllDates,
) =
    androidx.compose.material3.rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialSelectedStartDateMillis,
        initialSelectedEndDateMillis = initialSelectedEndDateMillis,
        selectableDates = selectableDates,
    )

// --- Color scheme builder (params limited to those app themes set) ---
private val baseDarkColorScheme = androidx.compose.material3.darkColorScheme()

fun darkColorScheme(
    primary: Color = baseDarkColorScheme.primary,
    secondary: Color = baseDarkColorScheme.secondary,
    tertiary: Color = baseDarkColorScheme.tertiary,
    background: Color = baseDarkColorScheme.background,
    surface: Color = baseDarkColorScheme.surface,
    onPrimary: Color = baseDarkColorScheme.onPrimary,
    onSecondary: Color = baseDarkColorScheme.onSecondary,
    onTertiary: Color = baseDarkColorScheme.onTertiary,
    onBackground: Color = baseDarkColorScheme.onBackground,
    onSurface: Color = baseDarkColorScheme.onSurface,
    primaryContainer: Color = baseDarkColorScheme.primaryContainer,
    secondaryContainer: Color = baseDarkColorScheme.secondaryContainer,
    error: Color = baseDarkColorScheme.error,
): ColorScheme = androidx.compose.material3.darkColorScheme(
    primary = primary, secondary = secondary, tertiary = tertiary, background = background,
    surface = surface, onPrimary = onPrimary, onSecondary = onSecondary, onTertiary = onTertiary,
    onBackground = onBackground, onSurface = onSurface, primaryContainer = primaryContainer,
    secondaryContainer = secondaryContainer, error = error,
)

// --- Shape conversion (Material expressive shapes) ---
@Composable
fun RoundedPolygon.toShape(startAngle: Int = 0): Shape = m3ToShape(startAngle)

// --- Sheet / slider state ---
@Composable
fun rememberModalBottomSheetState(skipPartiallyExpanded: Boolean = false) =
    androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)

@Composable
fun rememberBottomSheetState(
    initialValue: SheetValue = SheetValue.PartiallyExpanded,
    enabledValues: Set<SheetValue> = SheetValue.entries.toSet(),
    confirmValueChange: (SheetValue) -> Boolean = { true },
) = androidx.compose.material3.rememberBottomSheetState(
    initialValue = initialValue, enabledValues = enabledValues, confirmValueChange = confirmValueChange,
)

@Composable
fun rememberBottomSheetScaffoldState() = androidx.compose.material3.rememberBottomSheetScaffoldState()

@Composable
fun rememberBottomSheetScaffoldState(bottomSheetState: SheetState) =
    androidx.compose.material3.rememberBottomSheetScaffoldState(bottomSheetState = bottomSheetState)

@Composable
fun rememberSliderState(
    value: Float = 0f,
    steps: Int = 0,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) = androidx.compose.material3.rememberSliderState(value, steps, valueRange)

// --- Adaptive ---
@Composable
fun currentWindowAdaptiveInfo() = androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2()

@Composable
fun rememberSwipeToDismissBoxState(
    initialValue: SwipeToDismissBoxValue = SwipeToDismissBoxValue.Settled,
    confirmValueChange: (SwipeToDismissBoxValue) -> Boolean = { true },
) = androidx.compose.material3.rememberSwipeToDismissBoxState(
    initialValue = initialValue, confirmValueChange = confirmValueChange,
)

// --- App bar scroll behavior ---
/**
 * The scroll behavior an [AppBarSize] needs, so a screen cannot pair a size with the wrong one.
 *
 * [AppBarSize.Small] gets `pinned`, which does not move the bar - it only lets the bar's container
 * colour react to scroll offset. The two flexible sizes get `exitUntilCollapsed`, which is what
 * makes their second row actually collapse; without it they would render permanently expanded and
 * just eat vertical space.
 */
@Composable
fun appBarScrollBehavior(size: AppBarSize = AppBarSize.Small): TopAppBarScrollBehavior =
    when (size) {
        AppBarSize.Small -> TopAppBarDefaults.pinnedScrollBehavior()
        AppBarSize.MediumFlexible, AppBarSize.LargeFlexible ->
            TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    }

// --- Density ---
/**
 * Relaxes Material's minimum touch target, which it enforces by padding a control out to 48dp
 * without drawing anything there. A 32dp chip therefore reserves 48dp, and in a wrapping layout
 * that reads as ~16dp of stray space between rows that no spacing setting can remove.
 *
 * Only worth reaching for in a dense grid of small controls, and do not go far below [size]'s
 * default — the padding exists so controls stay hittable.
 */
@Composable
fun CompactTouchTargets(size: Dp = 40.dp, content: @Composable () -> Unit) =
    CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentSize provides size,
        content = content,
    )
