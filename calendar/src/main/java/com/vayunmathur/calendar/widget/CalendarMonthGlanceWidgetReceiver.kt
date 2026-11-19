package com.vayunmathur.calendar.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

/** Widget receiver that registers the month-grid widget and refreshes it when the date changes. */
class CalendarMonthGlanceWidgetReceiver : MaWidgetReceiver<CalendarMonthGlanceWidget>(
    widgetClass = CalendarMonthGlanceWidget::class,
    receiverClass = CalendarMonthGlanceWidgetReceiver::class,
    dateTriggers = true,
    widgetFactory = ::CalendarMonthGlanceWidget,
)
