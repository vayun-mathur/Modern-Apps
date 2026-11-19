package com.vayunmathur.calendar.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

/** Widget receiver that registers the agenda widget and refreshes it when the date changes. */
class CalendarGlanceWidgetReceiver : MaWidgetReceiver<CalendarGlanceWidget>(
    widgetClass = CalendarGlanceWidget::class,
    receiverClass = CalendarGlanceWidgetReceiver::class,
    dateTriggers = true,
    widgetFactory = ::CalendarGlanceWidget,
)
