package com.vayunmathur.library.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// The Registry that holds the events
class NavResultRegistry {
    // Use a SharedFlow with some extra buffer capacity so events are not dropped
    private val _results = MutableSharedFlow<Pair<String, Any>>(extraBufferCapacity = 64)
    val results = _results.asSharedFlow()

    suspend fun dispatchResult(key: String, result: Any) {
        // emit is suspend and will suspend until the value is delivered or buffer accepts it
        _results.emit(key to result)
    }
}

// The Composable helper (The "ResultEffect" you saw)
@Composable
inline fun <reified T> ResultEffect(key: String, crossinline onResult: (T) -> Unit) {
    val registry = LocalNavResultRegistry.current
    LaunchedEffect(registry) {
        registry.results.collect { (k, result) ->
            if (k == key && result is T) {
                onResult(result)
            }
        }
    }
}

// Make it available everywhere via CompositionLocal
val LocalNavResultRegistry = staticCompositionLocalOf<NavResultRegistry> {
    error("No NavResultRegistry provided")
}

fun <T: NavKey> NavBackStack<T>.pop() {
    removeAt(lastIndex)
}

fun <T: NavKey> NavBackStack<T>.setLast(value: T) {
    set(lastIndex, value)
}

fun <T: NavKey> NavBackStack<T>.reset(vararg keys: T) {
    // set values
    clear()
    while(size > keys.size) {
        pop()
    }
    keys.forEachIndexed { idx, key ->
        if(size <= idx) {
            add(key)
        } else
            set(idx, key)
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T: NavKey> MainNavigation(backStack: NavBackStack<T>, entryProvider:  EntryProviderScope<T>.() -> Unit) {
    val sceneStrategy: ListDetailSceneStrategy<T> = rememberListDetailSceneStrategy()
    val resultRegistry = remember { NavResultRegistry() }
    Scaffold(contentWindowInsets = WindowInsets.displayCutout
    ) { paddingValues ->
        CompositionLocalProvider(LocalNavResultRegistry provides resultRegistry) {
            NavDisplay(
                modifier = Modifier.padding(paddingValues).consumeWindowInsets(paddingValues).imePadding(),
                sceneStrategy = DialogSceneStrategy<T>().then(sceneStrategy),
                backStack = backStack, entryProvider = entryProvider {
                    entryProvider()
                })
        }
    }
}

@Composable
fun <T: NavKey> rememberNavBackStack(vararg elements: T): NavBackStack<T> {
    return rememberSerializable(
        serializer = NavBackStackSerializer(elementSerializer = NavKeySerializer())
    ) {
        NavBackStack(*elements)
    }
}

fun DialogPage() = DialogSceneStrategy.dialog()

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun ListPage(detailPlaceholder: @Composable () -> Unit = {}) = ListDetailSceneStrategy.listPane(detailPlaceholder)

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun ListDetailPage() = ListDetailSceneStrategy.detailPane()

@Composable
inline fun <reified T: NavKey> rememberNavBackStack(elements: List<T>): NavBackStack<T> {
    return rememberNavBackStack(*elements.toTypedArray())
}