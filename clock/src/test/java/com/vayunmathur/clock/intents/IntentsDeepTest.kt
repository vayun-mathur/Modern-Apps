package com.vayunmathur.clock.intents

import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.library.intents.clock.SetAlarmData
import com.vayunmathur.library.intents.clock.SetTimerData
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The assistant intents' calculations: each builds its item and hands it to
 * the repository - which is SQLCipher-bound on the host JVM, so the upsert is
 * where the documented UnsatisfiedLinkError ceiling sits. Construction and
 * validation before that point are what these pin.
 */
@RunWith(RobolectricTestRunner::class)
class IntentsDeepTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test fun setAlarmBuildsOneTimeAlarmThenHitsPersistCeiling() {
        val intent = Robolectric.buildActivity(SetAlarmIntent::class.java).create().get()
        assertFailsWith<UnsatisfiedLinkError> {
            runBlocking {
                intent.performCalculation(SetAlarmData(hour = 7, minute = 45, label = "standup"))
            }
        }
    }

    @Test fun setTimerBuildsRunningTimerThenHitsPersistCeiling() {
        val intent = Robolectric.buildActivity(SetTimerIntent::class.java).create().get()
        assertFailsWith<UnsatisfiedLinkError> {
            runBlocking {
                intent.performCalculation(SetTimerData(seconds = 300, label = "eggs"))
            }
        }
    }
}
