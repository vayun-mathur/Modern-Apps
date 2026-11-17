package com.vayunmathur.calculator

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.calculator.platform.CalculatorViewModel
import com.vayunmathur.calculator.widget.CalculatorGlanceWidgetReceiver
import com.vayunmathur.calculator.widget.UnitsGlanceWidget
import com.vayunmathur.calculator.widget.UnitsGlanceWidgetReceiver
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.widgets.updateWidgetPreviews

/** Entry activity hosting the calculator, graph and unit-converter pages. */
class MainActivity : MaActivity() {
    private val viewModel: CalculatorViewModel by viewModels()

    /**
     * Counts units deep-links rather than latching a flag: the widget can be tapped again after
     * the user has manually moved to another tab, and an unchanged flag would not re-navigate.
     */
    private var unitsRequest by mutableIntStateOf(0)

    override fun onCreateBeforeContent() {
        // FIRST_PARTY covers api.maproj.org, where the currency-rate proxy lives.
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        updateWidgetPreviews(CalculatorGlanceWidgetReceiver::class)
        updateWidgetPreviews(UnitsGlanceWidgetReceiver::class)
        if (intent.opensUnits()) unitsRequest++
    }

    @Composable
    override fun Content() {
        Navigation(viewModel, unitsRequest)
    }

    /**
     * Where widget taps land once the app is running: the units widget's intent carries
     * `FLAG_ACTIVITY_SINGLE_TOP`, and this activity is always top of its own task.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.opensUnits()) unitsRequest++
    }

    private fun Intent?.opensUnits(): Boolean =
        this?.getBooleanExtra(UnitsGlanceWidget.EXTRA_OPEN_UNITS, false) == true
}
