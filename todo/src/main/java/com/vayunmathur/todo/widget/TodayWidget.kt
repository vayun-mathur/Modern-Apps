package com.vayunmathur.todo.widget

import com.vayunmathur.todo.data.getLists
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.vayunmathur.library.widgets.MaWidget
import com.vayunmathur.todo.MainActivity
import com.vayunmathur.todo.R
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoRepository
import com.vayunmathur.todo.domain.DueBuckets
import com.vayunmathur.todo.domain.TodoQuery
import com.vayunmathur.todo.domain.TodoView
import com.vayunmathur.todo.notifications.ReminderNotifications
import com.vayunmathur.todo.platform.IntentRouter
import kotlinx.datetime.TimeZone

/** Open tasks due today or overdue, as the Today view shows them. */
data class TodayWidgetData(val rows: List<TodayWidgetRow>)

data class TodayWidgetRow(val syncId: String, val title: String, val overdue: Boolean)

class TodayWidget : MaWidget<TodayWidgetData>() {
    override suspend fun loadData(context: Context, id: GlanceId): TodayWidgetData {
        val repo = TodoRepository.get(context)
        val now = System.currentTimeMillis()
        val zone = TimeZone.currentSystemDefault()
        val input = TodoQuery.Input(repo.getAll(), repo.getLists(), TodoView.Today, now = now, zone = zone)
        val model = TodoQuery.build(input)
        val rows = model.groups.flatMap { it.rows }.map { row ->
            TodayWidgetRow(row.todo.syncId, row.todo.title, DueBuckets.isOverdue(row.todo, now, zone))
        }
        return TodayWidgetData(rows)
    }

    override fun previewData(context: Context) = TodayWidgetData(
        listOf(
            TodayWidgetRow("1", context.getString(R.string.widget_preview_1), overdue = true),
            TodayWidgetRow("2", context.getString(R.string.widget_preview_2), overdue = false),
            TodayWidgetRow("3", context.getString(R.string.widget_preview_3), overdue = false),
        ),
    )

    @Composable
    override fun Content(data: TodayWidgetData) {
        TodayContent(data)
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun TodayContent(data: TodayWidgetData) {
    val context = LocalContext.current
    Scaffold(
        titleBar = {
            TitleBar(
                startIcon = ImageProvider(R.drawable.ic_notification),
                title = context.getString(R.string.view_today),
                modifier = GlanceModifier.clickable(actionStartActivity(openViewIntent(context))),
                actions = {
                    Box(
                        GlanceModifier.padding(12.dp).clickable(actionStartActivity(quickAddIntent(context))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+", style = TextStyle(color = GlanceTheme.colors.onSurface))
                    }
                },
            )
        },
        horizontalPadding = 0.dp,
    ) {
        if (data.rows.isEmpty()) {
            Box(
                GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    context.getString(R.string.all_done),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
            return@Scaffold
        }
        LazyColumn(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface)) {
            items(data.rows, itemId = { it.syncId.hashCode().toLong() }) { row -> TodayRow(row) }
        }
    }
}

@Composable
private fun TodayRow(row: TodayWidgetRow) {
    val context = LocalContext.current
    Row(
        GlanceModifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(
            checked = false,
            onCheckedChange = actionRunCallback<CompleteTaskAction>(
                actionParametersOf(CompleteTaskAction.SYNC_ID to row.syncId),
            ),
        )
        Column(
            GlanceModifier.defaultWeight().clickable(
                actionStartActivity(ReminderNotifications.openTaskIntent(context, row.syncId)),
            ),
        ) {
            Text(
                row.title.ifBlank { context.getString(R.string.untitled) },
                maxLines = 2,
                style = TextStyle(color = if (row.overdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurface),
            )
        }
    }
}

private fun openViewIntent(context: Context) =
    Intent(context, MainActivity::class.java).putExtra(IntentRouter.EXTRA_VIEW, TodoView.Today.key)

internal fun quickAddIntent(context: Context) =
    Intent(context, MainActivity::class.java).setAction(IntentRouter.ACTION_QUICK_ADD)

/** Checking a task off in the widget; the widget refreshes from the data change. */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val syncId = parameters[SYNC_ID] ?: return
        val repo = TodoRepository.get(context)
        val todo: Todo = repo.getBySyncId(syncId) ?: return
        if (!todo.done) repo.setDone(todo, true)
    }

    companion object {
        val SYNC_ID = ActionParameters.Key<String>("sync_id")
    }
}
