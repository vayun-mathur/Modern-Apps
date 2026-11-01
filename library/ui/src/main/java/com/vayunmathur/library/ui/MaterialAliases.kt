@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.vayunmathur.library.ui

/**
 * Curated re-exports of Material 3 types, objects, defaults, enums, state classes and
 * experimental annotations.
 *
 * This is the single UI boundary for the monorepo: apps depend only on `:library:ui`
 * and reference Material through these same-named aliases (and the wrappers in
 * [MaterialComponents]) instead of importing `androidx.compose.material3.*` directly.
 *
 * Only symbols actually used by app code are re-exported; the set grows as needed.
 */

// --- Experimental annotations ---
// Material's opt-in markers cannot be re-exported via `typealias` (the compiler forbids
// referencing a @RequiresOptIn class as a type). Instead we declare our own same-named
// markers so app code that keeps `@OptIn(ExperimentalMaterial3Api::class)` compiles after
// only swapping the import. Apps call our non-experimental wrappers, so these opt-ins are
// effectively no-ops (they may surface a harmless "unnecessary @OptIn" warning).
@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalMaterial3Api

@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalMaterial3ExpressiveApi

@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalMaterial3AdaptiveApi

// --- Theme ---
typealias MaterialTheme = androidx.compose.material3.MaterialTheme
typealias Typography = androidx.compose.material3.Typography
typealias Shapes = androidx.compose.material3.Shapes
typealias MaterialShapes = androidx.compose.material3.MaterialShapes

// --- Adaptive navigation suite (openassistant) ---
typealias NavigationSuiteType = androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType

// --- Component defaults ---
typealias AssistChipDefaults = androidx.compose.material3.AssistChipDefaults
typealias BadgeDefaults = androidx.compose.material3.BadgeDefaults
typealias BottomSheetDefaults = androidx.compose.material3.BottomSheetDefaults
typealias ButtonDefaults = androidx.compose.material3.ButtonDefaults
typealias CardDefaults = androidx.compose.material3.CardDefaults
typealias ExposedDropdownMenuDefaults = androidx.compose.material3.ExposedDropdownMenuDefaults
typealias MenuDefaults = androidx.compose.material3.MenuDefaults
typealias FilterChipDefaults = androidx.compose.material3.FilterChipDefaults
typealias FloatingActionButtonDefaults = androidx.compose.material3.FloatingActionButtonDefaults
typealias ListItemDefaults = androidx.compose.material3.ListItemDefaults
typealias OutlinedTextFieldDefaults = androidx.compose.material3.OutlinedTextFieldDefaults
typealias SearchBarDefaults = androidx.compose.material3.SearchBarDefaults
typealias SegmentedButtonDefaults = androidx.compose.material3.SegmentedButtonDefaults
typealias SliderDefaults = androidx.compose.material3.SliderDefaults
typealias TabRowDefaults = androidx.compose.material3.TabRowDefaults
typealias TextFieldDefaults = androidx.compose.material3.TextFieldDefaults
typealias TopAppBarDefaults = androidx.compose.material3.TopAppBarDefaults

// --- Colors / elevation / shapes state ---
typealias ListItemColors = androidx.compose.material3.ListItemColors
typealias MenuItemColors = androidx.compose.material3.MenuItemColors
typealias SelectableMenuItemColors = androidx.compose.material3.SelectableMenuItemColors
typealias MenuItemShapes = androidx.compose.material3.MenuItemShapes
typealias CheckboxColors = androidx.compose.material3.CheckboxColors
typealias CheckboxDefaults = androidx.compose.material3.CheckboxDefaults
typealias ListItemElevation = androidx.compose.material3.ListItemElevation
typealias ColorScheme = androidx.compose.material3.ColorScheme
typealias ButtonColors = androidx.compose.material3.ButtonColors
typealias ButtonElevation = androidx.compose.material3.ButtonElevation
typealias CardColors = androidx.compose.material3.CardColors
typealias CardElevation = androidx.compose.material3.CardElevation
typealias IconButtonColors = androidx.compose.material3.IconButtonColors
typealias TextFieldColors = androidx.compose.material3.TextFieldColors
typealias SliderColors = androidx.compose.material3.SliderColors
typealias SegmentedButtonColors = androidx.compose.material3.SegmentedButtonColors
typealias TopAppBarColors = androidx.compose.material3.TopAppBarColors
typealias TopAppBarScrollBehavior = androidx.compose.material3.TopAppBarScrollBehavior
typealias FloatingActionButtonElevation = androidx.compose.material3.FloatingActionButtonElevation
typealias DatePickerColors = androidx.compose.material3.DatePickerColors
typealias SelectableChipColors = androidx.compose.material3.SelectableChipColors
typealias ChipColors = androidx.compose.material3.ChipColors
typealias NavigationDrawerItemColors = androidx.compose.material3.NavigationDrawerItemColors
typealias NavigationDrawerItemDefaults = androidx.compose.material3.NavigationDrawerItemDefaults

// --- Enums / value types ---
typealias DrawerValue = androidx.compose.material3.DrawerValue
typealias SheetValue = androidx.compose.material3.SheetValue
typealias FabPosition = androidx.compose.material3.FabPosition
typealias ExposedDropdownMenuAnchorType = androidx.compose.material3.ExposedDropdownMenuAnchorType
typealias SwipeToDismissBoxValue = androidx.compose.material3.SwipeToDismissBoxValue
typealias SnackbarResult = androidx.compose.material3.SnackbarResult
typealias SnackbarDuration = androidx.compose.material3.SnackbarDuration
typealias SelectableDates = androidx.compose.material3.SelectableDates
typealias TimePickerSelectionMode = androidx.compose.material3.TimePickerSelectionMode

// --- State classes ---
typealias SnackbarHostState = androidx.compose.material3.SnackbarHostState
typealias DrawerState = androidx.compose.material3.DrawerState
typealias SwipeToDismissBoxState = androidx.compose.material3.SwipeToDismissBoxState
typealias DatePickerState = androidx.compose.material3.DatePickerState
typealias DateRangePickerState = androidx.compose.material3.DateRangePickerState
typealias TimePickerState = androidx.compose.material3.TimePickerState
typealias SheetState = androidx.compose.material3.SheetState
typealias BottomSheetScaffoldState = androidx.compose.material3.BottomSheetScaffoldState
typealias SliderState = androidx.compose.material3.SliderState

// --- Scopes / positions (needed in wrapper signatures) ---
typealias TabIndicatorScope = androidx.compose.material3.TabIndicatorScope
typealias TabPosition = androidx.compose.material3.TabPosition
typealias ExposedDropdownMenuBoxScope = androidx.compose.material3.ExposedDropdownMenuBoxScope
typealias SingleChoiceSegmentedButtonRowScope = androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
typealias FloatingActionButtonMenuScope = androidx.compose.material3.FloatingActionButtonMenuScope
typealias ButtonGroupScope = androidx.compose.material3.ButtonGroupScope
typealias ToggleFloatingActionButtonScope = androidx.compose.material3.ToggleFloatingActionButtonScope

// --- Composition locals (re-exported as vals; cannot be typealiased) ---
val LocalContentColor = androidx.compose.material3.LocalContentColor
val LocalTextStyle = androidx.compose.material3.LocalTextStyle
