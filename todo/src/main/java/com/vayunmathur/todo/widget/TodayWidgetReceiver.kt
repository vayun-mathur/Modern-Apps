package com.vayunmathur.todo.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

/** Glance receiver that hosts the Today widget and refreshes it when the date changes. */
class TodayWidgetReceiver : MaWidgetReceiver<TodayWidget>(
    widgetClass = TodayWidget::class,
    receiverClass = TodayWidgetReceiver::class,
    dateTriggers = true,
    widgetFactory = ::TodayWidget,
)
