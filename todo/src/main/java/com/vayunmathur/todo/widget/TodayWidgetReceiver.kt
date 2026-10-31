package com.vayunmathur.todo.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

class TodayWidgetReceiver : MaWidgetReceiver<TodayWidget>(
    widgetClass = TodayWidget::class,
    receiverClass = TodayWidgetReceiver::class,
    dateTriggers = true,
    widgetFactory = ::TodayWidget,
)
