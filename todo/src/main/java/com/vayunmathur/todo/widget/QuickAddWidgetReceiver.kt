package com.vayunmathur.todo.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

class QuickAddWidgetReceiver : MaWidgetReceiver<QuickAddWidget>(
    widgetClass = QuickAddWidget::class,
    receiverClass = QuickAddWidgetReceiver::class,
    hourly = false,
    widgetFactory = ::QuickAddWidget,
)
