package com.vayunmathur.findfamily.intents

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.User
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Assistant intents launched as activities with a ResultReceiver channel:
 * [GetIntent] answers from the installed in-memory repository and
 * [CreateLinkIntent] mints a real link row and returns its share URL.
 */
@RunWith(AndroidJUnit4::class)
class AssistantIntentsRealRepoTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @BeforeTest
    fun setup() {
        TestFindFamilyRepository.install(ctx)
    }

    private class Capture {
        var code: Int? = null
        var data: Bundle? = null
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                code = resultCode
                data = resultData
            }
        }
    }

    private fun launch(activityClass: Class<*>, dataJson: String?): Capture {
        val capture = Capture()
        val intent = Intent(ctx, activityClass).apply {
            if (dataJson != null) putExtra("DATA", dataJson)
            putExtra("RECEIVER", capture.receiver)
        }
        val controller = Robolectric.buildActivity(activityClass as Class<android.app.Activity>, intent)
        controller.create()
        // The intent activities finish synchronously in onCreate and deliver
        // asynchronously; pump the loopers while the calculation lands.
        val deadline = System.currentTimeMillis() + 10_000
        while (capture.code == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        return capture
    }

    @Test fun getIntentReturnsMembersWithLocations() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        val alice = repo.upsertUser(User(
            name = "Alice", photo = null, locationName = "Home", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        repo.upsertUser(User(
            name = "Bob", photo = null, locationName = "Office", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION, lastLocationChangeTime = Clock.System.now(),
        ))
        repo.upsertLocation(LocationValue(
            userid = alice, coord = Coord(37.5, -122.25), speed = 0f, acc = 5f,
            timestamp = Clock.System.now(), battery = 50f, reportedAt = Clock.System.now(),
            source = LocationSource.LIVE,
        ))
        val capture = launch(GetIntent::class.java, Json.encodeToString(kotlinx.serialization.serializer<Unit>(), Unit))
        assertEquals(android.app.Activity.RESULT_OK, capture.code)
        val payload = capture.data?.getString("RESPONSE_DATA")
        assertNotNull(payload)
        assertTrue(payload.contains("Alice"), "result mentions Alice: $payload")
        assertTrue(payload.contains("37.5"), "result carries the latitude: $payload")
        Unit
    }

    @Test fun createLinkIntentMintsLinkAndReturnsUrl() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        val input = Json.encodeToString(CreateLinkData("Weekend", 3_600_000L))
        val capture = launch(CreateLinkIntent::class.java, input)
        assertEquals(android.app.Activity.RESULT_OK, capture.code)
        val links = repo.temporaryLinkStore.getAll()
        assertEquals(1, links.size)
        assertEquals("Weekend", links.single().name)
        assertTrue(links.single().pqcPublicKey.isNotBlank())
        assertTrue(links.single().pqcSeed?.isNotBlank() == true)
        val url = capture.data?.getString("RESPONSE_DATA")
        assertTrue(url?.contains("#s=") == true, "returned share URL carries the seed: $url")
        Unit
    }

    @Test fun malformedInputCancelsCleanly() {
        val capture = launch(GetIntent::class.java, "{not json")
        assertEquals(android.app.Activity.RESULT_CANCELED, capture.code)
    }
}
