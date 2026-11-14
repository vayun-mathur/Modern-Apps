package com.vayunmathur.calculator.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.vayunmathur.calculator.util.KEY_UNITS_CATEGORY
import com.vayunmathur.library.util.DataStoreUtils

/**
 * Opens and closes the inline category list. Glance state survives widget updates, so the list
 * stays open across the recomposition this triggers.
 */
class ToggleUnitsCategoryListAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[UnitsGlanceWidget.EXPANDED] = prefs[UnitsGlanceWidget.EXPANDED] != true
        }
        UnitsGlanceWidget().update(context, glanceId)
    }
}

/** Switches the widget to a category and collapses the list again. */
class SelectUnitsCategoryAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val name = parameters[CATEGORY_NAME] ?: return
        // The preference the app restores on launch, so the converter reopens on this category too.
        DataStoreUtils.getInstance(context)
            .setString(KEY_UNITS_CATEGORY, name)
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[UnitsGlanceWidget.EXPANDED] = false
        }
        UnitsGlanceWidget().update(context, glanceId)
    }

    /** Action parameter keys for the units widget actions. */
    companion object {
        val CATEGORY_NAME = ActionParameters.Key<String>("categoryName")
    }
}
