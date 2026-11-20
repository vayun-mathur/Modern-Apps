package com.vayunmathur.library.util

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.captionBarPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass

// Result delivery (NavResultRegistry, ResultEffect, LocalNavResultRegistry) lives in
// NavigationResults.kt; the bottom bar lives in NavigationBottomBar.kt. This file keeps
// the back stack, motion, transitions, shared-element helpers and MainNavigation.

/** Marker for a navigation destination key in the type-safe back stack. */
interface NavKey
/** An observable, mutable navigation back stack over [NavKey] destinations. */
class NavBackStack<T: NavKey>(initial: Array<out T>) {
    private val backend = mutableStateListOf(*initial)
    val backStack: List<T> = backend

    fun pop() {
        if (backend.isNotEmpty()) backend.removeAt(backend.lastIndex)
    }

    fun set(index: Int, value: T) {
        backend[index] = value
    }

    fun add(value: T) {
        backend.add(value)
    }

    fun clear() {
        backend.clear()
    }

    fun setLast(value: T) {
        set(backend.lastIndex, value)
    }

    fun last(): T {
        return backend.last()
    }

    fun reset(vararg keys: T) {
        backend.clear()
        backend.addAll(keys)
    }

    /**
     * Push [value], replacing the current top instead when it is already a destination of the
     * same type.
     *
     * The detail-over-list case this exists for: on a two-pane layout the list stays visible
     * beside its detail, so tapping a second item of the same kind would otherwise pile one
     * detail on another and back would step through stale details instead of returning to
     * the list. Class equality rather than `is`, matching the hand-rolled helpers this
     * promotes (education's `pushChain`, photos' `pushPhoto`): a subclass instance is a
     * different destination, not a refresh of this one. On an empty stack this is [add].
     */
    fun pushOrReplace(value: T) {
        if (backend.isNotEmpty() && backend.last()::class == value::class) setLast(value) else add(value)
    }

    /**
     * [pushOrReplace] with the replace decision left to [replaceIf] on the current top.
     *
     * For the tops that are interchangeable but not the same type - contacts replaces an
     * open editor as readily as an open detail, maps swaps a place sheet for an admin
     * label - where the same-type rule alone would stack instead of replacing. The new
     * [value] need not be of the type [replaceIf] matches. On an empty stack this is [add].
     */
    fun pushOrReplace(value: T, replaceIf: (T) -> Boolean) {
        if (backend.isNotEmpty() && replaceIf(backend.last())) setLast(value) else add(value)
    }

    /**
     * Pop until [target] is the current destination, reporting whether it was there at all.
     *
     * Returns false and changes nothing when the target is absent, so a caller can fall
     * back to pushing rather than unwinding the whole stack looking for something that was
     * never on it (files' breadcrumb navigation is the model). Never pops the root: a
     * target that is only reachable by emptying the stack cannot be produced here, and an
     * absent target leaves even the root untouched. With duplicate entries it stops at the
     * topmost one.
     */
    fun popTo(target: T): Boolean {
        if (target !in backend) return false
        while (backend.size > 1 && backend.last() != target) pop()
        return true
    }

    /**
     * [popTo] by predicate: pop until the top satisfies [predicate], to its topmost match.
     *
     * Same contract as [popTo] by value - false with no change when nothing matches, and
     * the root is never popped, so a predicate matching only the root leaves just the root.
     */
    fun popTo(predicate: (T) -> Boolean): Boolean {
        if (backend.none(predicate)) return false
        while (backend.size > 1 && !predicate(backend.last())) pop()
        return true
    }

    /** [popTo] to the topmost destination of type [R]; false with no change when there is none. */
    inline fun <reified R : T> popToType(): Boolean = popTo { it is R }
}

// LocalNavResultRegistry lives in NavigationResults.kt.

val LocalSnackbarHostState = compositionLocalOf<SnackbarHostState?> { null }

/**
 * Whether the screen is hosted inside [MainNavigation].
 *
 * IME ownership no longer depends on this: the shared `Scaffold` wrapper in
 * `:library:ui` always unions IME into the insets M3 itself consumes and
 * consumes them for its content, so hosted, standalone (previews, screenshot
 * tests, pages hosted directly by an Activity) and preview rendering all
 * behave identically — never padding outside a Scaffold.
 * True now gates only the hosted-only extra: the shared wrapper injects the
 * global snackbar (see [LocalSnackbarHostState]) when a hosted screen
 * supplied no explicit snackbar host.
 */
val LocalHostScaffoldInfo = staticCompositionLocalOf { false }

/** Receiver scope that registers the NavEntry matching the current destination. */
class EntryProviderScope<T: NavKey>(val obj: T) {
    var result: NavEntry<T>? = null

    inline fun <reified E : T> entry(
        metadata: Map<String, Any> = emptyMap(),
        crossinline content: @Composable (E) -> Unit
    ) {
        if(obj is E) {
            result = NavEntry(obj, metadata = metadata) {
                // Republished as a nullable local so screens can ask for a shared element without
                // depending on nav3, and so the same screen still renders outside a NavDisplay.
                CompositionLocalProvider(
                    LocalEntryAnimatedScope provides LocalNavAnimatedContentScope.current
                ) {
                    content(obj)
                }
            }
        }
    }
}

// The motion enum and *Page() helpers live in NavigationMotion.kt; navMotion()/navMotionIn()
// are internal there so Navigation.kt's transition specs can keep reading them.

/**
 * Which screen edge the back gesture started from, mirroring `BackEventCompat.EDGE_LEFT`. A plain
 * Int because androidx.activity is not a dependency of this module.
 */
private const val EDGE_LEFT = 0

/**
 * The transitions, built from [MaterialTheme]'s expressive motion scheme.
 *
 * Springs rather than tweens, because a spring is interruptible from wherever it currently is -
 * which is what a half-completed back gesture or a fast double tap actually needs. Spatial specs
 * carry position and size, effects specs carry alpha; mixing the two up is what makes a fade look
 * like it lags the movement it belongs to.
 */
private class NavTransitions(
    val offset: FiniteAnimationSpec<IntOffset>,
    val scale: FiniteAnimationSpec<Float>,
    val alpha: FiniteAnimationSpec<Float>,
) {
    fun push(motion: NavMotion): ContentTransform = when (motion) {
        NavMotion.Detail -> slide(enterFrom = 1, exitTo = -1)
        NavMotion.Zoom -> zoom(enterFrom = 0.92f, exitTo = 1.04f)
        NavMotion.Fullscreen -> immersiveIn()
        NavMotion.Sibling, NavMotion.Morph -> crossFade()
    }

    fun pop(motion: NavMotion): ContentTransform = when (motion) {
        NavMotion.Detail -> slide(enterFrom = -1, exitTo = 1)
        NavMotion.Zoom -> zoom(enterFrom = 1.04f, exitTo = 0.92f)
        NavMotion.Fullscreen -> immersiveOut()
        NavMotion.Sibling, NavMotion.Morph -> crossFade()
    }

    /**
     * Predictive back: the screen being dismissed shrinks and slides *towards the edge the finger
     * came from*, revealing the screen behind it.
     *
     * The edge is the whole point, and was previously discarded - so a swipe from the right
     * animated identically to one from the left, and the motion fought the gesture half the time.
     * Everything here is a plain scale, translate or fade so it still reads correctly at any
     * fraction: the user can stop half way and change their mind.
     */
    fun predictivePop(motion: NavMotion, swipeEdge: Int): ContentTransform {
        // A morph has to stay a crossfade even under the gesture, or the element is chasing a target
        // that the predictive scale is still moving.
        if (motion == NavMotion.Sibling || motion == NavMotion.Morph) return crossFade()
        val towardsFinger = if (swipeEdge == EDGE_LEFT) 1 else -1
        return (fadeIn(alpha) + scaleIn(scale, initialScale = 0.96f)).togetherWith(
            fadeOut(alpha) +
                scaleOut(scale, targetScale = 0.90f) +
                slideOutHorizontally(offset) { towardsFinger * it / PREDICTIVE_SLIDE_DIVISOR }
        )
    }

    private fun slide(enterFrom: Int, exitTo: Int): ContentTransform =
        (fadeIn(alpha) + slideInHorizontally(offset) { enterFrom * it / PUSH_SLIDE_DIVISOR })
            .togetherWith(
                fadeOut(alpha) + slideOutHorizontally(offset) { exitTo * it / PUSH_SLIDE_DIVISOR }
            )

    private fun zoom(enterFrom: Float, exitTo: Float): ContentTransform =
        (fadeIn(alpha) + scaleIn(scale, initialScale = enterFrom)).togetherWith(
            fadeOut(alpha) + scaleOut(scale, targetScale = exitTo)
        )

    private fun immersiveIn(): ContentTransform =
        (fadeIn(alpha) + scaleIn(scale, initialScale = 0.97f)).togetherWith(fadeOut(alpha))

    private fun immersiveOut(): ContentTransform =
        fadeIn(alpha).togetherWith(fadeOut(alpha) + scaleOut(scale, targetScale = 0.97f))

    private fun crossFade(): ContentTransform = fadeIn(alpha).togetherWith(fadeOut(alpha))
}

@Composable
private fun rememberNavTransitions(): NavTransitions {
    val scheme = MaterialTheme.motionScheme
    // Keyed on the scheme, not the specs: the spec factories hand back a fresh object each call, so
    // keying on those would rebuild this on every recomposition.
    return remember(scheme) {
        NavTransitions(
            offset = scheme.defaultSpatialSpec(),
            scale = scheme.defaultSpatialSpec(),
            alpha = scheme.defaultEffectsSpec(),
        )
    }
}

/**
 * How a morphing element travels between its two positions.
 *
 * A tween rather than a spring, and that is not a style choice. Predictive back drives the transition
 * by gesture *fraction*, seeking it back and forth as the finger moves, and only duration-based specs
 * can be seeked. A spring has no notion of "40% of the way through", so under the gesture the element
 * would sit still and then snap into place on release.
 *
 * Also not from the motion scheme, whose spatial springs are all deliberately underdamped (0.8 by
 * default, 0.6 for fast). An overshoot on a bounds morph means the element visibly flies past its
 * target and rubber-bands back, which is far more obvious across a long travel than on a short slide.
 */
internal const val NAV_MORPH_MILLIS = 220

/** M3's emphasized curve: slow start, quick middle, gentle settle. */
private val NavMorphEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private val NavMorphBounds: FiniteAnimationSpec<Rect> =
    tween(NAV_MORPH_MILLIS, easing = NavMorphEasing)

private val NavMorphBoundsTransform = BoundsTransform { _, _ -> NavMorphBounds }

/**
 * [sharedText]'s resize mode, hoisted because `scaleToBounds` hands back a fresh instance per call
 * and the modifier node compares it by reference - building it inline rebuilds the node on every
 * recomposition.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val NavTextResize = SharedTransitionScope.ResizeMode.scaleToBounds()

/**
 * The crossfade between the two contents inside a morphing container.
 *
 * Faster than the bounds travel on purpose: the old content should be gone well before the container
 * finishes resizing, or both are legible at once and the item appears to contain two things. Linear,
 * and duration-based for the same seekability reason as the bounds above.
 */
private val NavMorphContentFade: FiniteAnimationSpec<Float> =
    tween(NAV_MORPH_MILLIS / 2, easing = LinearEasing)

/**
 * The scopes [sharedContainer] and [sharedContent] need to morph a component across a destination
 * change.
 *
 * Nullable and defaulting to null rather than erroring, because a screen rendered outside a
 * [NavDisplay] - a `@Preview`, a screenshot test, an Activity hosting one page directly - is a
 * legitimate caller that should simply not animate rather than crash.
 */
private val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * True when the host is showing more than one pane side by side.
 *
 * A morph is then meaningless and actively broken: on a two-pane layout the list and the detail are
 * composed *at the same time*, so the row and the header the row would morph into are both on screen.
 * There is no transition to animate, and two live shared elements holding one key cannot be matched.
 * [sharedContainer] and [sharedContent] step aside rather than produce that.
 */
private val LocalNavMultiPane = staticCompositionLocalOf { false }

/** Internal because [EntryProviderScope.entry] is a public inline function and has to reach it. */
@PublishedApi
internal val LocalEntryAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The [NavMotion] carrying the current transition, so an element can ask what the screen around it
 * is already doing.
 *
 * This is the motion of the destination on top of the stack - the same thing [navMotion] reads off
 * the scene - and not the motion of the entry the element happens to sit in. Those differ, and the
 * difference is the point: a list root declares no motion at all, yet it is pushed away from with
 * whatever motion the *destination* asked for.
 *
 * [dropsAway] and [sinksBelow] need this. Three of the five motions translate or scale the whole
 * screen, and an element that moves as well composes a second spatial transform on top of the first
 * - the conflict [sharedContainer] warns about, one level down. Publishing the motion lets those
 * helpers stand down structurally rather than via a comment nobody reads.
 *
 * [NavMotion.Detail] outside a [MainNavigation], which reads as "assume the screen is moving" and so
 * stands the helpers down.
 */
private val LocalNavMotion = staticCompositionLocalOf { NavMotion.Detail }

/**
 * Morphs this component into the component carrying the same [key] on the destination: the bounds
 * travel and resize while the two different contents crossfade inside them.
 *
 * This is the Material container transform, and it is meant for the *whole* item - put it on the
 * list row and on the block that row opens. Morphing one child in isolation, an avatar out of a row,
 * reads worse than no morph at all: everything around it cuts while one thing glides.
 *
 * Content is remeasured to the animating bounds rather than scaled, so text reflows at each size
 * instead of stretching. That costs a relayout per frame, which is affordable for one item and would
 * not be for a whole list. It is also what makes this the wrong choice across a *large* size change:
 * re-wrapping a paragraph at every intermediate width reads as the text garbling rather than
 * growing. This is one of three ways a morphing container can treat its contents - it reflows them,
 * [sharedText] scales them, and [sharedCrop] crops them - and the reflow is only convincing while
 * the two ends are a similar size.
 *
 * Pair with [MorphPage] on the destination, or the screen transition animates too and the two fight.
 *
 * A key must be unique *within* a screen and equal *across* the two. Watch for an item rendered
 * twice - a favourite shown both in a favourites section and again in the main list is one component
 * with two origins, and the morph has no way to choose. Key only one of them.
 *
 * No-ops outside a [MainNavigation], so previews and screenshot tests are unaffected.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedContainer(key: Any): Modifier {
    if (LocalNavMultiPane.current) return this
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalEntryAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedContainer.sharedBounds(
            rememberSharedContentState(key),
            animated,
            enter = fadeIn(NavMorphContentFade),
            exit = fadeOut(NavMorphContentFade),
            boundsTransform = NavMorphBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}

/**
 * A single leaf that keeps its identity through a [sharedContainer] or [sharedCrop] morph -
 * typically the photo or icon, whose pixels are the same on both screens.
 *
 * Nest inside one of those rather than using alone: on its own it produces exactly the
 * one-thing-glides effect that [sharedContainer] exists to avoid. Inside a [sharedCrop] it also
 * earns its keep in a second way - the crop deliberately does not move the contents, so a leaf that
 * genuinely sits in a different place at the two ends needs its own key to get there.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedContent(key: Any): Modifier {
    if (LocalNavMultiPane.current) return this
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalEntryAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedContent.sharedElement(
            rememberSharedContentState(key),
            animated,
            boundsTransform = NavMorphBoundsTransform,
        )
    }
}

/**
 * Host for every screen it hosts. It does NOT apply the IME (keyboard) inset
 * itself: the shared `Scaffold` wrapper in `:library:ui` is the single owner
 * of that inset for every AppScaffold-family screen (hosted or standalone —
 * it unions IME into the scaffold insets and consumes them for its content).
 * Screens and reusable components rendered inside must NOT call `imePadding`
 * themselves; under a scaffold it is now a consumed no-op, and outside one it
 * would double against the scaffold padding.
 *
 * [containerColor] is [Color.Unspecified] by default, which leaves the scaffold's own
 * opaque `colorScheme.background`. Pass [Color.Transparent] when something behind the
 * activity window has to show through - the launcher needs the wallpaper visible, and an
 * opaque scaffold paints over it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun <T : NavKey> MainNavigation(
    backStack: NavBackStack<T>,
    bottomBar: @Composable () -> Unit = {},
    containerColor: Color = Color.Unspecified,
    entryProvider: EntryProviderScope<T>.() -> Unit,
) {
    val sceneStrategy: ListDetailSceneStrategy<T> = rememberListDetailSceneStrategy()
    val resultRegistry = remember { NavResultRegistry() }
    val snackbarHostState = remember { SnackbarHostState() }
    val transitions = rememberNavTransitions()

    // Matches the width at which the list-detail strategy starts showing two panes at once.
    val multiPane = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    val resolvedContainerColor = containerColor.takeOrElse { MaterialTheme.colorScheme.background }

    // What nav3 will read off the scene for the transition that is running or about to run. Built
    // by asking the entry provider for the top destination and looking at its metadata only - the
    // entry's content lambda is never invoked, so this costs an allocation, not a composition.
    val topMotion = navMotionIn(
        backStack.backStack.lastOrNull()?.let { key ->
            EntryProviderScope(key).apply(entryProvider).result?.metadata
        }
    )

    val resolvedContentColor = resolveNavContentColor(resolvedContainerColor)

    NavMessageDrain(snackbarHostState)

    // No outer Scaffold by design: it used to own the IME inset here while every
    // screen's inner Scaffold consumed insets again, double-lifting bottom bars by
    // a keyboard height (live probe: bottomBar laid out at H-2K). Instead the host
    // is a plain Box that marks screens hosted via LocalHostScaffoldInfo, and the
    // shared Scaffold wrapper in :library:ui always unions IME into the insets M3
    // itself consumes and consumes them for its content — exactly once, hosted
    // or standalone, never padded outside a Scaffold.
    // The snackbar renderer lives there too (default host when the screen supplies
    // none, hosted only); this function keeps only the producer (state + drain).
    NavHostChrome(
        resultRegistry = resultRegistry,
        snackbarHostState = snackbarHostState,
        resolvedContainerColor = resolvedContainerColor,
        resolvedContentColor = resolvedContentColor,
        multiPane = multiPane,
        topMotion = topMotion,
        backStack = backStack,
        sceneStrategy = sceneStrategy,
        transitions = transitions,
        entryProvider = entryProvider,
        bottomBar = bottomBar
    )
}

// Resolved rather than left to Scaffold's own default, which is `contentColorFor(container)`.
// That has no answer for a colour outside the scheme and falls back to LocalContentColor -
// which is plain black unless something upstream set it. A transparent container is exactly
// that case, so the launcher's icons and text were coming out black on the wallpaper.
@Composable
private fun resolveNavContentColor(resolvedContainerColor: Color): Color {
    return MaterialTheme.colorScheme
        .contentColorFor(resolvedContainerColor)
        .takeOrElse { MaterialTheme.colorScheme.onBackground }
}

// Drain messages posted from outside composition - ViewModels, workers,
// anything without a Context. See AppMessages.
@Composable
private fun NavMessageDrain(snackbarHostState: SnackbarHostState) {
    LaunchedEffect(snackbarHostState) {
        AppMessages.messages.collect { message ->
            val result = snackbarHostState.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                duration = when (message.duration) {
                    AppMessages.Duration.Short -> androidx.compose.material3.SnackbarDuration.Short
                    AppMessages.Duration.Long -> androidx.compose.material3.SnackbarDuration.Long
                    AppMessages.Duration.Indefinite ->
                        androidx.compose.material3.SnackbarDuration.Indefinite
                },
            )
            if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun <T : NavKey> NavHostChrome(
    resultRegistry: NavResultRegistry,
    snackbarHostState: SnackbarHostState,
    resolvedContainerColor: Color,
    resolvedContentColor: Color,
    multiPane: Boolean,
    topMotion: NavMotion,
    backStack: NavBackStack<T>,
    sceneStrategy: ListDetailSceneStrategy<T>,
    transitions: NavTransitions,
    entryProvider: EntryProviderScope<T>.() -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(resolvedContainerColor)
            // captionBarPadding: on desktop windowing (freeform, ChromeOS) the
            // system caption bar overlays the top of the window, and nothing else
            // in the inset chain accounts for it. Zero on phones.
            .captionBarPadding()
            .consumeWindowInsets(WindowInsets.captionBar),
    ) {
        CompositionLocalProvider(
            LocalNavResultRegistry provides resultRegistry,
            LocalSnackbarHostState provides snackbarHostState,
            LocalHostScaffoldInfo provides true,
            LocalContentColor provides resolvedContentColor,
        ) {
            // Lets a component morph into its counterpart on the next screen instead of the two
            // screens merely swapping underneath it. See sharedContainer.
            SharedTransitionLayout {
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this@SharedTransitionLayout,
                    LocalNavMultiPane provides multiPane,
                    LocalNavMotion provides topMotion,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        NavDisplay(
                            modifier = Modifier.weight(1f),
                            sceneStrategies = listOf(DialogSceneStrategy(), sceneStrategy),
                            // The destination decides: the motion is read off the entry the user is
                            // arriving at when pushing, and off the one they are leaving when popping,
                            // so a route animates the same way in both directions.
                            transitionSpec = { transitions.push(targetState.navMotion()) },
                            popTransitionSpec = { transitions.pop(initialState.navMotion()) },
                            predictivePopTransitionSpec = { swipeEdge ->
                                transitions.predictivePop(initialState.navMotion(), swipeEdge)
                            },
                            backStack = backStack.backStack,
                            entryProvider = {
                                EntryProviderScope(it).apply {
                                    entryProvider()
                                }.result!!
                            }
                        )
                        bottomBar()
                    }
                }
            }
        }
    }
}

/**
 * Keeps a [NavBackStack] alive while the Activity around it is destroyed and recreated.
 *
 * A ViewModel rather than `rememberSaveable` because the stack holds app-defined [NavKey]s,
 * which are not all bundleable — and a configuration change is what actually loses them.
 */
internal class RetainedNavBackStack : ViewModel() {
    var stack: NavBackStack<*>? = null
}

@Composable
fun <T: NavKey> rememberNavBackStack(vararg elements: T): NavBackStack<T> {
    // Changing the font scale or rotating recreates the Activity, and a plain remember would
    // rebuild the stack from `elements` — dropping the user back on the start screen, losing
    // whatever they were part-way through. Keyed on the start route so an owner hosting more
    // than one stack (previews render several) gives each its own.
    val owner = LocalViewModelStoreOwner.current
        ?: return remember { NavBackStack(elements) }
    val retained = viewModel<RetainedNavBackStack>(
        viewModelStoreOwner = owner,
        key = "nav-back-stack-${elements.firstOrNull()?.let { it::class.qualifiedName }}",
    )
    return remember(retained) {
        @Suppress("UNCHECKED_CAST")
        (retained.stack as? NavBackStack<T>)
            ?: NavBackStack(elements).also { retained.stack = it }
    }
}

/**
 * [sharedContainer] for text.
 *
 * Scales the glyphs to the animating bounds instead of laying them out again at each intermediate
 * size. That difference is the whole point: the same name is `bodyLarge` in a list row and
 * `headlineMedium` in a header, and remeasuring between the two re-wraps the string on nearly every
 * frame, which reads as the text garbling rather than growing. Scaling interpolates it smoothly.
 *
 * Use [sharedContainer] instead when the two ends hold genuinely different content - a read-only row
 * becoming a text field - where a reflow is correct and scaling would distort. Use [sharedCrop] when
 * the text is one part of a whole component that changes size, rather than the thing being morphed.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedText(key: Any): Modifier {
    if (LocalNavMultiPane.current) return this
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalEntryAnimatedScope.current ?: return this
    return with(shared) {
        this@sharedText.sharedBounds(
            rememberSharedContentState(key),
            animated,
            enter = fadeIn(NavMorphContentFade),
            exit = fadeOut(NavMorphContentFade),
            boundsTransform = NavMorphBoundsTransform,
            resizeMode = NavTextResize,
        )
    }
}

/**
 * The third way a morphing container can treat its contents: crop them.
 *
 * The set is [sharedContainer] reflows, [sharedText] scales, and this one does neither - the content
 * is laid out once at the size it will have when it arrives, and the container is a window over it
 * that grows. Nothing inside reflows, stretches or moves relative to anything else beside it, which
 * is what lets a whole card become a whole screen without keying a single thing inside it.
 *
 * Reach for this when the two ends are the same *component* at wildly different sizes - a list row
 * and the header it opens into, a grid tile and the page it becomes. [sharedContainer] is still
 * right when the two ends genuinely need to re-flow into a different shape at a similar size, and
 * remains the cheaper option for a small travel.
 *
 * **Both ends must be congruent at their top-left.** The two contents are anchored to the same
 * corner of the growing box and crossfaded, so whatever comes first in each - an icon, an avatar -
 * has to be in the same place at both ends or it visibly jumps. A centred tile morphing into a
 * left-aligned header is the case this reads worst on, and no amount of tuning fixes it: pick a
 * different pairing, or keep a [sharedContent] on the one element that really is the same object.
 *
 * [shape] is the crop, and applies at rest as well as in flight - pass the destination's own shape
 * so the corners match rather than squaring off for the duration.
 *
 * Pair with [MorphPage], and mind the same one-key-one-origin rule as [sharedContainer]. No-ops
 * outside a [MainNavigation], and on a multi-pane layout, so previews and screenshot tests are
 * unaffected.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCrop(key: Any, shape: Shape = RectangleShape): Modifier {
    // Ahead of the early returns on purpose. Content measured at its arrival size is *larger* than
    // the box it is travelling in, and clipInOverlayDuringTransition below only covers the frames
    // where the element has been lifted into the shared-element overlay. At rest, before a match is
    // found, and whenever one of the three guards has bailed, this is the only thing stopping the
    // content painting over its neighbours - and the only thing giving `shape` any effect at all.
    //
    // Outside sharedBounds rather than inside, because the bounds node reports the *animating* size
    // upward. A clip chained on by the caller lands inside it and crops to the arrival size, which
    // is no crop at all.
    val clipped = clip(shape)
    if (LocalNavMultiPane.current) return clipped
    val shared = LocalSharedTransitionScope.current ?: return clipped
    val animated = LocalEntryAnimatedScope.current ?: return clipped
    // Remembered, not built inline: these are compared by reference when the modifier node decides
    // whether it has changed, so a fresh instance per recomposition rebuilds the node every time.
    val overlayClip = remember(shared, shape) { with(shared) { OverlayClip(shape) } }
    // Both ends default to the same z, leaving composition order to decide who paints on top. That
    // is invisible on an 80dp header and very visible on a frame filling the screen, so the arriving
    // end is put in front explicitly.
    val arrivingOnTop = if (isNavLeaving()) 0f else 1f
    return with(shared) {
        clipped
            .sharedBounds(
                rememberSharedContentState(key),
                animated,
                enter = fadeIn(NavMorphContentFade),
                exit = fadeOut(NavMorphContentFade),
                boundsTransform = NavMorphBoundsTransform,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                zIndexInOverlay = arrivingOnTop,
                clipInOverlayDuringTransition = overlayClip,
            )
            // Inside the bounds node, so the content is measured at the size it will settle at while
            // the node itself reports the animating size. This is the whole difference from
            // [sharedContainer]: the box moves, the contents do not.
            .skipToLookaheadSize()
    }
}

/**
 * Enters by unfurling from a flat horizontal line into full height, rather than fading in.
 *
 * For a block of fields arriving as a unit: it reads as the form opening up in place, which suits a
 * destination whose content is a stack of rows. A fade makes the same block look like it was already
 * there and merely became visible.
 *
 * Duration-based, so it stays seekable under a predictive-back gesture.
 *
 * No-ops outside a [MainNavigation].
 */
@Composable
fun Modifier.expandFromLine(): Modifier {
    val scope = LocalEntryAnimatedScope.current ?: return this
    return with(scope) {
        this@expandFromLine.animateEnterExit(
            enter = expandVertically(
                animationSpec = tween(NAV_MORPH_MILLIS, easing = NavMorphEasing),
                expandFrom = Alignment.CenterVertically,
                initialHeight = { 0 },
            ) + fadeIn(NavMorphContentFade),
            exit = shrinkVertically(
                animationSpec = tween(NAV_MORPH_MILLIS, easing = NavMorphEasing),
                shrinkTowards = Alignment.CenterVertically,
                targetHeight = { 0 },
            ) + fadeOut(NavMorphContentFade),
        )
    }
}

/**
 * True when the screen transition is a plain crossfade, and an element inside it is therefore free
 * to carry a movement of its own.
 *
 * [NavMotion.Detail] slides the screen, [NavMotion.Zoom] and [NavMotion.Fullscreen] scale it, and
 * predictive back does both - so on those an element exit that also translates or scales stacks two
 * spatial transforms on the same pixels, which is the conflict [sharedContainer] warns about one
 * level up. Only [NavMotion.Morph] and [NavMotion.Sibling] leave the spatial channel free.
 */
private fun NavMotion.leavesTheScreenStill(): Boolean =
    this == NavMotion.Morph || this == NavMotion.Sibling

/**
 * The shared spec for the exit helpers.
 *
 * Duration-based for the same reason as [NavMorphBounds]: predictive back seeks the transition by
 * gesture fraction, and a spring has no notion of being 40% through. Under the finger it would sit
 * still and then snap on release.
 */
private const val PUSH_SLIDE_DIVISOR = 12
private const val PREDICTIVE_SLIDE_DIVISOR = 8

private val NavExitScale: FiniteAnimationSpec<Float> =
    tween(NAV_MORPH_MILLIS, easing = NavMorphEasing)

private val NavExitOffset: FiniteAnimationSpec<IntOffset> =
    tween(NAV_MORPH_MILLIS, easing = NavMorphEasing)

/**
 * Leaves by shrinking into its own centre rather than ghosting out at full size in the spot it
 * occupies.
 *
 * For a small thing that owns a fixed place on the screen - a floating action button, a badge. This
 * is the navigation-time sibling of [com.vayunmathur.library.ui.PopVisibility], which does the same
 * for a component appearing and disappearing *within* one screen; reach for that when the trigger is
 * a state change rather than a destination change.
 *
 * Size only, no alpha, and no entrance. Every [NavMotion] already ends in a fade, so a helper that
 * faded too would multiply the two curves and make the element vanish long before it finished
 * shrinking - and the arrival is already carried by the destination's own fade, which is why there
 * is nothing here for it.
 *
 * Put this on chrome belonging to a screen that gets *pushed away from*. It no-ops unless the
 * destination on top of the stack is a [MorphPage] or [SiblingPage] - the other three motions move
 * the whole screen, and a second transform on top of that reads as a glitch - and outside a
 * [MainNavigation], so previews and screenshot tests are unaffected.
 */
@Composable
fun Modifier.dropsAway(): Modifier {
    val scope = LocalEntryAnimatedScope.current ?: return this
    if (!LocalNavMotion.current.leavesTheScreenStill()) return this
    return with(scope) {
        this@dropsAway.animateEnterExit(
            enter = EnterTransition.None,
            exit = scaleOut(NavExitScale),
        )
    }
}

/**
 * Leaves by sliding down past the bottom edge, the direction it would go if it were dismissed.
 *
 * For chrome docked at the bottom - a tab bar, a docked toolbar - which reads as merely switched off
 * when it crossfades in place. [com.vayunmathur.library.ui.BannerVisibility] owns the top edge for
 * the in-screen case; there is deliberately no in-screen bottom equivalent, because bottom chrome
 * here comes and goes with the destination rather than with state.
 *
 * Offset only, no alpha, no entrance, for the same reasons as [dropsAway], and it stands down under
 * the same two conditions.
 *
 * Only correct for something actually against the bottom edge - applied further up, it slides out
 * over whatever sits below it.
 */
@Composable
fun Modifier.sinksBelow(): Modifier {
    val scope = LocalEntryAnimatedScope.current ?: return this
    if (!LocalNavMotion.current.leavesTheScreenStill()) return this
    return with(scope) {
        this@sinksBelow.animateEnterExit(
            enter = EnterTransition.None,
            exit = slideOutVertically(NavExitOffset) { it },
        )
    }
}

/**
 * True while a shared element is mid-flight between two destinations.
 *
 * For content that would otherwise be visible in both places at once: a field can hide its own text
 * while the copy travelling in from the previous screen is on screen, so there is one piece of text
 * rather than two, and reveal it as the morph lands.
 *
 * False outside a [MainNavigation], so a screen on its own always shows its content.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun isNavMorphing(): Boolean =
    LocalSharedTransitionScope.current?.isTransitionActive == true

/**
 * True while this destination is being pushed away from, false while it is being returned to.
 *
 * For a graph that loops, where an element's counterpart is not the one it came from. Music is the
 * case this exists for: tapping a song carries it from the list row up to the now-playing screen,
 * but coming back it belongs in the mini-player, not in the row. Both the row and the mini-player
 * are composed the whole time, so a static key would give the morph two origins to choose between;
 * asking which way the entry is travelling picks exactly one.
 *
 * Only meaningful during a transition. Outside one - and outside a [MainNavigation] - it is false,
 * which reads as "arriving", so a screen sitting still keeps whichever element it settled on.
 */
@Composable
fun isNavLeaving(): Boolean {
    val animated = LocalEntryAnimatedScope.current ?: return false
    return animated.transition.targetState == EnterExitState.PostExit
}

// Public navigation-DSL entry points keep their PascalCase names to match the call sites
// across ~20 app modules; renaming them would break out-of-scope callers.
@Suppress("FunctionNaming")
fun DialogPage() = DialogSceneStrategy.dialog()

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun ListPage(detailPlaceholder: @Composable () -> Unit = {}) =
    ListDetailSceneStrategy.listPane(Unit) { detailPlaceholder() }

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun ListDetailPage() = ListDetailSceneStrategy.detailPane()

@Composable
inline fun <reified T: NavKey> rememberNavBackStack(elements: List<T>): NavBackStack<T> {
    return rememberNavBackStack(*elements.toTypedArray())
}
