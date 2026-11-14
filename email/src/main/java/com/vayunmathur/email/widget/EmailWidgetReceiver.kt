package com.vayunmathur.email.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

/** AppWidget receiver that binds EmailWidget to the launcher. */
class EmailWidgetReceiver : MaWidgetReceiver<EmailWidget>(
    widgetClass = EmailWidget::class,
    receiverClass = EmailWidgetReceiver::class,
    widgetFactory = ::EmailWidget,
)
