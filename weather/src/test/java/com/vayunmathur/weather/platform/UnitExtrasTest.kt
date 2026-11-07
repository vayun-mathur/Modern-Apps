package com.vayunmathur.weather.platform

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.domain.PressureUnit
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.domain.WindUnit
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class UnitExtrasTest {

    @get:Rule val rule = createComposeRule()

    @Test fun rememberUnitsResolve() {
        var temp: TemperatureUnit? = null
        var wind: WindUnit? = null
        var pressure: PressureUnit? = null
        var use24: Boolean? = null
        rule.setContent {
            temp = rememberTempUnit()
            wind = rememberWindUnit()
            pressure = rememberPressureUnit()
            use24 = rememberUse24Hour()
            val units = rememberDisplayUnits()
            assertEquals(temp, units.temperature)
            assertEquals(wind, units.wind)
            assertEquals(pressure, units.pressure)
            assertEquals(use24, units.use24Hour)
        }
        assertNotNull(temp); assertNotNull(wind); assertNotNull(pressure); assertNotNull(use24)
    }

    @Test fun currentLocationWithoutPermissionIsNull() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(LocationProvider.currentLocation(ctx))
    }

    @Test fun backupAgentDbCodec() {
        val agent = AppBackupAgent()
        assertNotNull(agent.dbCodec)
    }
}
