package com.vayunmathur.photos.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

/** Photo Glance Widget Receiver: a ma widget receiver implementation for photo glance widget receiver. */
class PhotoGlanceWidgetReceiver : MaWidgetReceiver<PhotoGlanceWidget>(
    widgetClass = PhotoGlanceWidget::class,
    receiverClass = PhotoGlanceWidgetReceiver::class,
    widgetFactory = ::PhotoGlanceWidget,
)
