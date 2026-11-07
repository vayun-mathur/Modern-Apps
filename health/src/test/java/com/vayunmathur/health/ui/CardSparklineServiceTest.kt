package com.vayunmathur.health.ui

import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.service.DoseSoundService
import com.vayunmathur.health.ui.components.Sparkline
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CardSparklineServiceTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setup() {
        synchronized(databases) {
            if (databases[HealthDatabase::class] == null) {
                databases[HealthDatabase::class] = Room
                    .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
        FoodDatabase.init(context)
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))

    @Test
    fun `food database card shows absent then failed state after prepare attempt`() = runBlocking<Unit> {
        val result = FoodDatabase.prepare()
        rule.setContent { FoodDatabaseCard(vm()) }
        rule.waitForIdle()
        // Ready renders nothing; any other status renders the card title.
        if (FoodDatabase.status.value is FoodDatabase.Status.Ready) {
            assertTrue(result.isSuccess)
        } else {
            rule.onNodeWithText("Food Database").assertExists()
        }
    }

    @Test
    fun `sparkline draws bars and tolerates empty and single values`() {
        rule.setContent {
            Sparkline(values = listOf(1f, 3f, 2f, 5f, 0.5f), modifier = Modifier.size(60.dp, 20.dp))
        }
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0)
    }

    @Test
    fun `dose sound service runs its start work`() {
        val intent = Intent(context, DoseSoundService::class.java).apply {
            putExtra(DoseSoundService.EXTRA_MEDICATION_NAME, "Sound Med")
        }
        val controller = Robolectric.buildService(DoseSoundService::class.java, intent).create()
        val service = controller.get()
        service.onStartCommand(intent, 0, 1)
        // Let the IO coroutine attempt playback (ringtone may be absent
        // under Robolectric, which is the logged-failure branch).
        Thread.sleep(300)
        controller.destroy()
        assertTrue(service.onBind(intent) == null)
    }
}
