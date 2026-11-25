package com.vayunmathur.todo.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.todo.wear.data.WearTodoStore
import com.vayunmathur.todo.wear.sync.WearLinkBridge

/**
 * Watch todo entry: the phone app's navigation (view index -> task pages ->
 * task detail) rendered from the locally cached catalog/page snapshots, so
 * it works standalone. Watch-originated changes queue in the store and
 * flow back over WearLink; nothing here claims a phone write.
 */
class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        val store = WearTodoStore.get(this)
        WearLinkBridge.get(this)
        WearLinkBridge.publishWatchState(this)
        setContent {
            MaterialTheme {
                var route by remember { mutableStateOf<WearRoute>(WearRoute.Home) }
                when (val current = route) {
                    WearRoute.Home -> WearTodoHomeScreen(
                        store = store,
                        onOpenPage = { route = WearRoute.Page(it) },
                        onQuickAdd = {
                            route = WearRoute.QuickAdd(store.catalog.value.defaultViewKey)
                        },
                    )
                    is WearRoute.Page -> WearTodoPageScreen(
                        store = store,
                        pageKey = current.pageKey,
                        onOpenTask = { route = WearRoute.TaskDetail(it, current.pageKey) },
                        onQuickAdd = { route = WearRoute.QuickAdd(current.pageKey) },
                        onBack = { route = WearRoute.Home },
                    )
                    is WearRoute.TaskDetail -> WearTodoTaskScreen(
                        store = store,
                        syncId = current.syncId,
                        pageKey = current.pageKey,
                        onEdit = { route = WearRoute.EditTask(it, current.pageKey) },
                        onConfirmDelete = {
                            route = WearRoute.ConfirmDelete(it, current.pageKey)
                        },
                        onBack = { route = WearRoute.Page(current.pageKey) },
                    )
                    is WearRoute.QuickAdd -> WearTodoQuickAddScreen(
                        store = store,
                        pageKey = current.pageKey,
                        onDone = { route = WearRoute.Page(current.pageKey) },
                    )
                    is WearRoute.EditTask -> WearTodoEditScreen(
                        store = store,
                        syncId = current.syncId,
                        onDone = {
                            route = WearRoute.TaskDetail(current.syncId, current.pageKey)
                        },
                    )
                    is WearRoute.ConfirmDelete -> WearTodoConfirmDeleteScreen(
                        store = store,
                        syncId = current.syncId,
                        onDone = { route = WearRoute.Page(current.pageKey) },
                        onCancel = {
                            route = WearRoute.TaskDetail(current.syncId, current.pageKey)
                        },
                    )
                }
            }
        }
    }
}
