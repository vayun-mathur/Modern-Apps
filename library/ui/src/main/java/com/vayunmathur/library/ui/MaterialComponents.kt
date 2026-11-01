@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.vayunmathur.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.NavigationBarItem as Material3NavigationBarItem
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.SegmentedButton as Material3SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.LocalHostScaffoldInfo
import com.vayunmathur.library.util.LocalSnackbarHostState

/**
 * Thin same-named `@Composable` wrappers around Material 3 components.
 *
 * Each wrapper exposes the parameters app code passes and delegates to the Material
 * implementation, letting Material fill any remaining parameters with its own defaults.
 * Apps import these from `com.vayunmathur.library.ui` instead of `androidx.compose.material3`,
 * giving us one place to restyle or replace Material later.
 */

// --- Text ---
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
    fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
    textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
    overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
    onTextLayout = onTextLayout, style = style,
)

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
    fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing,
    textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
    overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
    style = style,
)

// --- Icon ---
@Composable
fun Icon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = androidx.compose.material3.Icon(painter = painter, contentDescription = contentDescription, modifier = modifier, tint = tint)

@Composable
fun Icon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) = androidx.compose.material3.Icon(imageVector = imageVector, contentDescription = contentDescription, modifier = modifier, tint = tint)

// --- Buttons ---
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit,
) = androidx.compose.material3.IconButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors, content = content)

@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) = androidx.compose.material3.FilledIconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)

@Composable
fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) = androidx.compose.material3.FilledTonalIconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors,
    elevation = elevation, border = border, contentPadding = contentPadding, content = content,
)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors,
    contentPadding = contentPadding, content = content,
)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.TextButton(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors,
    contentPadding = contentPadding, content = content,
)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.FilledTonalButton(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors,
    contentPadding = contentPadding, content = content,
)

@Composable
fun FloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FloatingActionButtonDefaults.shape,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = contentColorFor(containerColor),
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(),
    content: @Composable () -> Unit,
) = androidx.compose.material3.FloatingActionButton(
    onClick = onClick, modifier = modifier, shape = shape, containerColor = containerColor,
    contentColor = contentColor, elevation = elevation, content = content,
)

// --- Surface ---
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) = androidx.compose.material3.Surface(
    modifier = modifier, shape = shape, color = color, contentColor = contentColor,
    tonalElevation = tonalElevation, shadowElevation = shadowElevation, border = border, content = content,
)

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) = androidx.compose.material3.Surface(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, color = color,
    contentColor = contentColor, tonalElevation = tonalElevation, shadowElevation = shadowElevation,
    border = border, content = content,
)

// --- Scaffold ---
//
// Hosted-mode IME ownership: screens under MainNavigation must apply the keyboard
// inset exactly once. MainNavigation no longer lifts content itself; instead it
// publishes LocalHostScaffoldInfo, and this funnel — the single path every
// AppScaffold screen goes through — unions IME into the insets M3 itself consumes,
// so bars, FAB, snackbar and content padding are all positioned consistently by
// M3, exactly once. Nothing is ever padded outside a Scaffold. When the local is
// absent (previews, screenshot tests, directly-hosted pages) behavior is
// byte-identical to before.
@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: (@Composable () -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    val hosted = LocalHostScaffoldInfo.current
    if (!hosted) {
        androidx.compose.material3.Scaffold(
            modifier = modifier, topBar = topBar, bottomBar = bottomBar,
            snackbarHost = snackbarHost ?: {},
            floatingActionButton = floatingActionButton, floatingActionButtonPosition = floatingActionButtonPosition,
            containerColor = containerColor, contentColor = contentColor, contentWindowInsets = contentWindowInsets,
            content = content,
        )
        return
    }
    // Hosted: render the global snackbar when the screen supplied no explicit host.
    val globalSnackbarState: SnackbarHostState? = LocalSnackbarHostState.current
    val resolvedSnackbarHost: @Composable () -> Unit = snackbarHost
        ?: globalSnackbarState?.let { state ->
            {
                SnackbarHost(
                    state,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(WindowInsets.ime.asPaddingValues()),
                )
            }
        } ?: {}
    androidx.compose.material3.Scaffold(
        modifier = modifier, topBar = topBar, bottomBar = bottomBar,
        snackbarHost = resolvedSnackbarHost,
        floatingActionButton = floatingActionButton, floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor, contentColor = contentColor,
        contentWindowInsets = contentWindowInsets.union(WindowInsets.ime),
        content = content,
    )
}

// --- Top app bars ---
@Composable
fun TopAppBar(
    title: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    expandedHeight: Dp = TopAppBarDefaults.TopAppBarExpandedHeight,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) = androidx.compose.material3.TopAppBar(
    title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions,
    expandedHeight = expandedHeight, colors = colors, scrollBehavior = scrollBehavior,
)

@Composable
fun CenterAlignedTopAppBar(
    title: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) = androidx.compose.material3.CenterAlignedTopAppBar(
    title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions,
    colors = colors, scrollBehavior = scrollBehavior,
)

// --- Top app bars (expressive, two-row) ---
/**
 * A two-row top app bar that collapses to a single row as content scrolls under it.
 *
 * The Expressive medium bar: unlike `MediumTopAppBar` it takes a [subtitle] and lets the title
 * block be centred, and its heights follow the expressive scale. Worth a taller bar on a
 * top-level screen whose title is the screen's identity; ordinary content screens should stay
 * on [TopAppBar].
 *
 * Pass a `scrollBehavior` or the bar has nothing to collapse in response to.
 */
@Composable
fun MediumFlexibleTopAppBar(
    title: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titleHorizontalAlignment: Alignment.Horizontal = Alignment.Start,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) = androidx.compose.material3.MediumFlexibleTopAppBar(
    title = title, modifier = modifier, subtitle = subtitle, navigationIcon = navigationIcon,
    actions = actions, titleHorizontalAlignment = titleHorizontalAlignment,
    colors = colors, scrollBehavior = scrollBehavior,
)

/** [MediumFlexibleTopAppBar] with a taller expanded row, for a screen whose title is the headline. */
@Composable
fun LargeFlexibleTopAppBar(
    title: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titleHorizontalAlignment: Alignment.Horizontal = Alignment.Start,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) = androidx.compose.material3.LargeFlexibleTopAppBar(
    title = title, modifier = modifier, subtitle = subtitle, navigationIcon = navigationIcon,
    actions = actions, titleHorizontalAlignment = titleHorizontalAlignment,
    colors = colors, scrollBehavior = scrollBehavior,
)

// --- Slider ---
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) = androidx.compose.material3.Slider(
    value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
    valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished, colors = colors,
)

@Composable
fun RangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
) = androidx.compose.material3.RangeSlider(
    value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
    valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished, colors = colors,
)

// --- Progress ---
@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = ProgressIndicatorDefaults.circularIndeterminateTrackColor,
) = androidx.compose.material3.CircularProgressIndicator(
    modifier = modifier, color = color, strokeWidth = strokeWidth, trackColor = trackColor,
)

@Composable
fun CircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = ProgressIndicatorDefaults.circularDeterminateTrackColor,
) = androidx.compose.material3.CircularProgressIndicator(
    progress = progress, modifier = modifier, color = color, strokeWidth = strokeWidth, trackColor = trackColor,
)

@Composable
fun LinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
) = androidx.compose.material3.LinearProgressIndicator(modifier = modifier, color = color, trackColor = trackColor)

@Composable
fun LinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
) = androidx.compose.material3.LinearProgressIndicator(progress = progress, modifier = modifier, color = color, trackColor = trackColor, strokeCap = strokeCap)

// --- Loading indicator (expressive) ---
/**
 * The Expressive "working on it" indicator: a sequence of morphing shapes rather than a
 * spinning arc.
 *
 * Prefer this to [CircularProgressIndicator] for an ordinary wait. It occupies the same
 * footprint, so swapping one for the other does not move layout. [CircularProgressIndicator]
 * stays for callers that set a `strokeWidth` or `trackColor`, which this has no equivalent of.
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = LoadingIndicatorDefaults.indicatorColor,
) = androidx.compose.material3.LoadingIndicator(modifier = modifier, color = color)

/** Determinate [LoadingIndicator]: the shape morphs in step with [progress]. */
@Composable
fun LoadingIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = LoadingIndicatorDefaults.indicatorColor,
) = androidx.compose.material3.LoadingIndicator(progress = progress, modifier = modifier, color = color)

/**
 * [LoadingIndicator] on its own filled container.
 *
 * For an indicator that sits over content - a pull-to-refresh or an overlay - where the bare
 * indicator would not have enough contrast against whatever is behind it.
 */
@Composable
fun ContainedLoadingIndicator(
    modifier: Modifier = Modifier,
    containerColor: Color = LoadingIndicatorDefaults.containedContainerColor,
    indicatorColor: Color = LoadingIndicatorDefaults.containedIndicatorColor,
    containerShape: Shape = LoadingIndicatorDefaults.containerShape,
) = androidx.compose.material3.ContainedLoadingIndicator(
    modifier = modifier, containerColor = containerColor,
    indicatorColor = indicatorColor, containerShape = containerShape,
)

/** Determinate [ContainedLoadingIndicator]. */
@Composable
fun ContainedLoadingIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    containerColor: Color = LoadingIndicatorDefaults.containedContainerColor,
    indicatorColor: Color = LoadingIndicatorDefaults.containedIndicatorColor,
    containerShape: Shape = LoadingIndicatorDefaults.containerShape,
) = androidx.compose.material3.ContainedLoadingIndicator(
    progress = progress, modifier = modifier, containerColor = containerColor,
    indicatorColor = indicatorColor, containerShape = containerShape,
)

// --- Cards ---
@Composable
fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.Card(
    modifier = modifier, shape = shape, colors = colors, elevation = elevation, border = border, content = content,
)

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.Card(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors,
    elevation = elevation, border = border, content = content,
)

@Composable
fun ElevatedCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.elevatedShape,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    elevation: CardElevation = CardDefaults.elevatedCardElevation(),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.ElevatedCard(modifier = modifier, shape = shape, colors = colors, elevation = elevation, content = content)

@Composable
fun OutlinedCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.outlinedShape,
    colors: CardColors = CardDefaults.outlinedCardColors(),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.OutlinedCard(modifier = modifier, shape = shape, colors = colors, content = content)

// --- Text fields ---
@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) = androidx.compose.material3.OutlinedTextField(
    value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
    textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
    trailingIcon = trailingIcon, prefix = prefix, suffix = suffix, supportingText = supportingText, isError = isError,
    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    singleLine = singleLine, maxLines = maxLines, minLines = minLines, interactionSource = interactionSource,
    shape = shape, colors = colors,
)

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = TextFieldDefaults.shape,
    colors: TextFieldColors = TextFieldDefaults.colors(),
) = androidx.compose.material3.TextField(
    value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
    textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
    trailingIcon = trailingIcon, prefix = prefix, suffix = suffix, supportingText = supportingText, isError = isError,
    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    singleLine = singleLine, maxLines = maxLines, minLines = minLines, interactionSource = interactionSource,
    shape = shape, colors = colors,
)

// --- Text fields (TextFieldValue overloads) ---
@Composable
fun OutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) = androidx.compose.material3.OutlinedTextField(
    value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
    textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
    trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape, colors = colors,
)

@Composable
fun TextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = TextFieldDefaults.shape,
    colors: TextFieldColors = TextFieldDefaults.colors(),
) = androidx.compose.material3.TextField(
    value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, readOnly = readOnly,
    textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
    trailingIcon = trailingIcon, supportingText = supportingText, isError = isError,
    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = shape, colors = colors,
)

// --- Snackbar ---
@Composable
fun SnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) =
    androidx.compose.material3.SnackbarHost(hostState = hostState, modifier = modifier)

// --- Menus ---
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, content = content)

@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
) = androidx.compose.material3.DropdownMenuItem(
    text = text, onClick = onClick, modifier = modifier, leadingIcon = leadingIcon,
    trailingIcon = trailingIcon, enabled = enabled, colors = colors, contentPadding = contentPadding,
)

/**
 * A dropdown item for a single choice among [DropdownMenu] siblings.
 *
 * Unlike [DropdownMenuItem], the [selected] item renders with selection colors
 * ([colors]) and an optional [selectedLeadingIcon] that only shows while
 * selected, so a menu reads which option is current without a hand-rolled
 * tick. Pass `MenuDefaults.selectableItemVibrantColors()` for [colors] for
 * the vibrant variant.
 */
@Composable
fun SelectableDropdownMenuItem(
    selected: Boolean,
    onClick: () -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    selectedLeadingIcon: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    shapes: MenuItemShapes = MenuDefaults.itemShapes(),
    colors: SelectableMenuItemColors = MenuDefaults.selectableItemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuSelectableItemContentPadding,
) = androidx.compose.material3.SelectableDropdownMenuItem(
    selected = selected, onClick = onClick, text = text, shapes = shapes, modifier = modifier,
    leadingIcon = leadingIcon, selectedLeadingIcon = selectedLeadingIcon,
    trailingContent = trailingContent, supportingText = supportingText, enabled = enabled,
    colors = colors, contentPadding = contentPadding,
)

/**
 * A dropdown item for toggling one option on/off, e.g. a multi-choice menu.
 *
 * Mirrors [SelectableDropdownMenuItem] but reports the new [checked] value
 * through [onCheckedChange] and shows [checkedLeadingIcon] while checked.
 */
@Composable
fun CheckableDropdownMenuItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    checkedLeadingIcon: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    shapes: MenuItemShapes = MenuDefaults.itemShapes(),
    colors: SelectableMenuItemColors = MenuDefaults.selectableItemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuSelectableItemContentPadding,
) = androidx.compose.material3.CheckableDropdownMenuItem(
    checked = checked, onCheckedChange = onCheckedChange, text = text, shapes = shapes,
    modifier = modifier, leadingIcon = leadingIcon, checkedLeadingIcon = checkedLeadingIcon,
    trailingContent = trailingContent, supportingText = supportingText, enabled = enabled,
    colors = colors, contentPadding = contentPadding,
)

// --- Chips ---
@Composable
fun InputChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) = androidx.compose.material3.InputChip(
    selected = selected, onClick = onClick, label = label, modifier = modifier,
    enabled = enabled, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
)

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
) = androidx.compose.material3.FilterChip(
    selected = selected, onClick = onClick, label = label, modifier = modifier,
    enabled = enabled, leadingIcon = leadingIcon, trailingIcon = trailingIcon, colors = colors,
)

@Composable
fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    colors: ChipColors = AssistChipDefaults.assistChipColors(),
) = androidx.compose.material3.AssistChip(
    onClick = onClick, label = label, modifier = modifier, enabled = enabled,
    leadingIcon = leadingIcon, trailingIcon = trailingIcon, colors = colors,
)

// --- Segmented buttons ---
@Composable
fun SingleChoiceSegmentedButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable SingleChoiceSegmentedButtonRowScope.() -> Unit,
) = androidx.compose.material3.SingleChoiceSegmentedButtonRow(modifier = modifier, content = content)

@Composable
fun SingleChoiceSegmentedButtonRowScope.SegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SegmentedButtonColors = SegmentedButtonDefaults.colors(),
    label: @Composable () -> Unit,
) = Material3SegmentedButton(
    selected = selected, onClick = onClick, shape = shape, modifier = modifier,
    enabled = enabled, colors = colors, label = label,
)

// --- List item (interactive overload: content is the trailing lambda) ---
@Composable
fun ListItem(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    elevation: ListItemElevation = ListItemDefaults.elevation(),
    contentPadding: PaddingValues = ListItemDefaults.ContentPadding,
    content: @Composable () -> Unit,
) = SafeListItemLayout(
    // Not material3's ListItem: it crashes on rotation inside the adaptive
    // navigation scenes every app uses. See SafeListItemLayout.
    colors = colors, modifier = modifier, enabled = enabled, contentPadding = contentPadding,
    overlineContent = overlineContent, supportingContent = supportingContent,
    leadingContent = leadingContent, trailingContent = trailingContent, content = content,
)

// --- List item (classic overload with headlineContent + tonal/shadow elevation) ---
@Composable
fun ListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    // Accepted for source compatibility with material3 ListItem; nothing sets
    // either away from zero (SafeListItemLayout takes no elevation).
    @Suppress("UnusedParameter") tonalElevation: Dp = 0.dp,
    @Suppress("UnusedParameter") shadowElevation: Dp = 0.dp,
) = SafeListItemLayout(
    // See the overload above; elevation is accepted for source compatibility
    // but nothing sets it away from zero.
    colors = colors, modifier = modifier, overlineContent = overlineContent,
    supportingContent = supportingContent, leadingContent = leadingContent,
    trailingContent = trailingContent, content = headlineContent,
)

// --- Selection controls ---
@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
) = androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled, colors = colors)

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)

@Composable
fun RadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = androidx.compose.material3.RadioButton(selected = selected, onClick = onClick, modifier = modifier, enabled = enabled)

// --- Dividers ---
@Composable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = DividerDefaults.Thickness,
    color: Color = DividerDefaults.color,
) = androidx.compose.material3.HorizontalDivider(modifier = modifier, thickness = thickness, color = color)

@Composable
fun VerticalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = DividerDefaults.Thickness,
    color: Color = DividerDefaults.color,
) = androidx.compose.material3.VerticalDivider(modifier = modifier, thickness = thickness, color = color)

// --- Alert dialog ---
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = androidx.compose.material3.AlertDialogDefaults.shape,
    properties: DialogProperties = DialogProperties(),
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier,
    dismissButton = dismissButton, icon = icon, title = title, text = text, shape = shape, properties = properties,
)

// --- Time picker ---
@Composable
fun TimePicker(state: TimePickerState, modifier: Modifier = Modifier) =
    androidx.compose.material3.TimePicker(state = state, modifier = modifier)

// --- Date picker ---
@Composable
fun DatePicker(state: DatePickerState, modifier: Modifier = Modifier) =
    androidx.compose.material3.DatePicker(state = state, modifier = modifier)

/** Start + end date selection in one calendar (e.g. depart/return, check-in/check-out). */
@Composable
fun DateRangePicker(state: DateRangePickerState, modifier: Modifier = Modifier) =
    androidx.compose.material3.DateRangePicker(state = state, modifier = modifier)

@Composable
fun DatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    shape: Shape = DatePickerDefaults.shape,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.DatePickerDialog(
    onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier,
    dismissButton = dismissButton, shape = shape, content = content,
)

// --- Text style provider ---
@Composable
fun ProvideTextStyle(value: TextStyle, content: @Composable () -> Unit) =
    androidx.compose.material3.ProvideTextStyle(value = value, content = content)

// --- Badge ---
@Composable
fun BadgedBox(
    badge: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) = androidx.compose.material3.BadgedBox(badge = badge, modifier = modifier, content = content)

@Composable
fun Badge(
    modifier: Modifier = Modifier,
    containerColor: Color = BadgeDefaults.containerColor,
    content: (@Composable RowScope.() -> Unit)? = null,
) = androidx.compose.material3.Badge(modifier = modifier, containerColor = containerColor, content = content)

// --- Navigation drawer ---
@Composable
fun ModalNavigationDrawer(
    drawerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    gesturesEnabled: Boolean = true,
    content: @Composable () -> Unit,
) = androidx.compose.material3.ModalNavigationDrawer(
    drawerContent = drawerContent, modifier = modifier, drawerState = drawerState,
    gesturesEnabled = gesturesEnabled, content = content,
)

@Composable
fun ModalDrawerSheet(
    modifier: Modifier = Modifier,
    drawerContainerColor: Color = DrawerDefaults.modalContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.ModalDrawerSheet(modifier = modifier, drawerContainerColor = drawerContainerColor, content = content)

// --- Navigation rail ---
/**
 * A vertical navigation rail for expanded windows: the wide-screen
 * counterpart of [BottomNavBar].
 *
 * [TabbedPagerScaffold] renders this automatically on [WindowWidthClass.Expanded]
 * instead of the bottom bar, so tabbed apps get desktop chrome without touching
 * their own code. Prefer that over calling this directly; reach for it only for
 * a screen with its own tab model that cannot use the shared host.
 */
@Composable
fun NavigationRail(
    modifier: Modifier = Modifier,
    header: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.NavigationRail(modifier = modifier, header = header, content = content)

@Composable
fun ColumnScope.NavigationRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (@Composable () -> Unit)? = null,
    alwaysShowLabel: Boolean = true,
) = androidx.compose.material3.NavigationRailItem(
    selected = selected, onClick = onClick, icon = icon, modifier = modifier,
    enabled = enabled, label = label, alwaysShowLabel = alwaysShowLabel,
)

// --- Navigation bar ---
@Composable
fun NavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.NavigationBar(modifier = modifier, content = content)

@Composable
fun RowScope.NavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (@Composable () -> Unit)? = null,
    alwaysShowLabel: Boolean = true,
) = Material3NavigationBarItem(
    selected = selected, onClick = onClick, icon = icon, modifier = modifier,
    enabled = enabled, label = label, alwaysShowLabel = alwaysShowLabel,
)

// --- Tab ---
@Composable
fun Tab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    selectedContentColor: Color = LocalContentColor.current,
) = androidx.compose.material3.Tab(
    selected = selected, onClick = onClick, modifier = modifier, enabled = enabled,
    text = text, icon = icon, selectedContentColor = selectedContentColor,
)

// --- Pull to refresh ---
/**
 * Shared pull-to-refresh container using the M3 Expressive contained loading
 * indicator (the spinner from the M2 era is gone).
 *
 * @param indicatorAlignment where the indicator sits. Default is the M3
 * top-center slot; pass e.g. [Alignment.TopCenter] with a top padding to
 * clear a floating bar the default would overlap.
 */
@Composable
fun PullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorAlignment: Alignment = Alignment.TopCenter,
    indicatorPadding: PaddingValues = PaddingValues(),
    content: @Composable BoxScope.() -> Unit,
) {
    val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            Box(
                modifier = Modifier.align(indicatorAlignment).padding(indicatorPadding),
            ) {
                androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.LoadingIndicator(
                    state = state,
                    isRefreshing = isRefreshing,
                )
            }
        },
        content = content,
    )
}

// --- Tab (content overload) ---
@Composable
fun Tab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.Tab(selected = selected, onClick = onClick, modifier = modifier, enabled = enabled, content = content)

// --- Elevated card (clickable overload) ---
@Composable
fun ElevatedCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.elevatedShape,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    elevation: CardElevation = CardDefaults.elevatedCardElevation(),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.ElevatedCard(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
    colors = colors, elevation = elevation, content = content,
)

// --- Tri-state checkbox ---
@Composable
fun TriStateCheckbox(
    state: ToggleableState,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = androidx.compose.material3.TriStateCheckbox(state = state, onClick = onClick, modifier = modifier, enabled = enabled)

// --- Swipe to dismiss ---
@Composable
fun SwipeToDismissBox(
    state: SwipeToDismissBoxState,
    backgroundContent: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    enableDismissFromStartToEnd: Boolean = true,
    enableDismissFromEndToStart: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.SwipeToDismissBox(
    state = state, backgroundContent = backgroundContent, modifier = modifier,
    enableDismissFromStartToEnd = enableDismissFromStartToEnd,
    enableDismissFromEndToStart = enableDismissFromEndToStart, content = content,
)

// --- Slider (SliderState overload) ---
@Composable
fun Slider(
    state: SliderState,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SliderColors = SliderDefaults.colors(),
) = androidx.compose.material3.Slider(state = state, onValueChange = onValueChange, modifier = modifier, enabled = enabled, colors = colors)

// --- Search bar (inputField overload) ---
@Composable
fun SearchBar(
    inputField: @Composable () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.SearchBar(
    inputField = inputField, expanded = expanded, onExpandedChange = onExpandedChange,
    modifier = modifier, content = content,
)
