package com.vayunmathur.library.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation3.runtime.NavEntry
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
inline fun <reified T> ResultEffect(key: String, crossinline onResult: suspend (T) -> Unit) {
    val registry = LocalNavResultRegistry.current
    LaunchedEffect(registry) {
        registry.results.collect { (k, result) ->
            if (k == key && result is T) {
                onResult(result)
            }
        }
    }
}

interface NavKey
class NavBackStack<T: NavKey>(initial: Array<out T>) {
    private val backend = mutableStateListOf(*initial)
    val backStack: List<T> = backend

    fun pop() {
        backend.removeAt(backend.lastIndex)
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
        // set values
        clear()
        while(backend.size > keys.size) {
            pop()
        }
        keys.forEachIndexed { idx, key ->
            if(backend.size <= idx) {
                add(key)
            } else
                set(idx, key)
        }
    }
}

// Make it available everywhere via CompositionLocal
val LocalNavResultRegistry = staticCompositionLocalOf<NavResultRegistry> {
    error("No NavResultRegistry provided")
}

class EntryProviderScope<T: NavKey>(val obj: T) {
    var result: NavEntry<T>? = null

    inline fun <reified E: T> entry(metadata: Map<String, Any> = emptyMap(), crossinline content: @Composable (E) -> Unit) {
        if(obj is E) {
            result = NavEntry(obj, metadata = metadata) {
                content(obj)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T: NavKey> MainNavigation(backStack: NavBackStack<T>, entryProvider: EntryProviderScope<T>.() -> Unit) {
    val sceneStrategy: ListDetailSceneStrategy<T> = rememberListDetailSceneStrategy()
    val resultRegistry = remember { NavResultRegistry() }
    Scaffold(contentWindowInsets = WindowInsets()) { paddingValues ->
        CompositionLocalProvider(LocalNavResultRegistry provides resultRegistry) {
            NavDisplay(
                modifier = Modifier.padding(paddingValues).imePadding(),
                sceneStrategy = DialogSceneStrategy<T>().then(sceneStrategy),
                backStack = backStack.backStack, entryProvider = {
                    EntryProviderScope(it).apply {
                        entryProvider()
                    }.result!!
                })
        }
    }
}
@Composable
fun <T: NavKey> rememberNavBackStack(vararg elements: T): NavBackStack<T> {
    return remember { NavBackStack(elements) }
}

fun DialogPage() = DialogSceneStrategy.dialog()

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun ListPage(detailPlaceholder: @Composable () -> Unit = {}) = ListDetailSceneStrategy.listPane(Unit) {detailPlaceholder()}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun ListDetailPage() = ListDetailSceneStrategy.detailPane()

@Composable
inline fun <reified T: NavKey> rememberNavBackStack(elements: List<T>): NavBackStack<T> {
    return rememberNavBackStack(*elements.toTypedArray())
}

data class BottomBarItem<Route: NavKey>(
    val name: String,
    val route: Route,
    val icon: Int
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <Route : NavKey> BottomNavBar(backStack: NavBackStack<Route>, pages: List<BottomBarItem<out Route>>, currentPage: Route) {
    FlexibleBottomAppBar {
        pages.forEach { page ->
            NavigationBarItem(
                currentPage == page.route, {
                    if (backStack.last() != page.route) {
                        backStack.add(page.route)
                    }
                }, { Icon(painterResource(page.icon), null) }, label = { Text(page.name) }
            )
        }
    }
}