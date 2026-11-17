package com.vayunmathur.todo.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

/** Glance receiver that hosts the quick-add home-screen widget. */
class QuickAddWidgetReceiver : MaWidgetReceiver<QuickAddWidget>(
    widgetClass = QuickAddWidget::class,
    receiverClass = QuickAddWidgetReceiver::class,
    hourly = false,
    widgetFactory = ::QuickAddWidget,
)
