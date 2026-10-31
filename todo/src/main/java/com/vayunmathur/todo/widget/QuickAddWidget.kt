package com.vayunmathur.todo.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.vayunmathur.library.widgets.MaWidget
import com.vayunmathur.todo.R

/** One-tap "Add a task" button that opens quick add. */
class QuickAddWidget : MaWidget<Unit>() {
    override suspend fun loadData(context: Context, id: GlanceId) = Unit
    override fun previewData(context: Context) = Unit

    @Composable
    override fun Content(data: Unit) {
        val context = LocalContext.current
        Row(
            GlanceModifier.fillMaxSize().cornerRadius(28.dp).background(GlanceTheme.colors.primaryContainer)
                .padding(horizontal = 16.dp).clickable(actionStartActivity(quickAddIntent(context))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(ImageProvider(R.drawable.ic_add), contentDescription = null)
            Spacer(GlanceModifier.width(8.dp))
            Text(context.getString(R.string.add_task_hint), style = TextStyle(color = GlanceTheme.colors.onPrimaryContainer))
        }
    }
}
