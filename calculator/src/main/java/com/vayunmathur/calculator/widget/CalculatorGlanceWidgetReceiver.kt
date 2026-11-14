package com.vayunmathur.calculator.widget

import com.vayunmathur.library.widgets.MaWidgetReceiver

/** Glance receiver for the calculator home-screen widget, refreshed only on key taps. */
class CalculatorGlanceWidgetReceiver : MaWidgetReceiver<CalculatorGlanceWidget>(
    widgetClass = CalculatorGlanceWidget::class,
    receiverClass = CalculatorGlanceWidgetReceiver::class,
    // No periodic refresh: the keypad's content only changes when the user taps a key.
    hourly = false,
    widgetFactory = ::CalculatorGlanceWidget,
)
