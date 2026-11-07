package com.vayunmathur.passwords.platform

import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.platform.cable.CableActivity
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServicesRoboTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test fun cableActivityWithoutUriFinishes() {
        val controller = Robolectric.buildActivity(CableActivity::class.java, Intent())
        controller.create()
        assertTrue(controller.get().isFinishing)
    }

    @Test fun cableActivityWithBogusUriFinishes() {
        val intent = Intent().putExtra(
            com.vayunmathur.passwords.platform.cable.CableService.EXTRA_FIDO_URI, "https://example.com",
        )
        val controller = Robolectric.buildActivity(CableActivity::class.java, intent)
        controller.create()
        assertTrue(controller.get().isFinishing)
    }

    @Test fun credentialServiceGetWithoutKeyReturnsUnlockAction() {
        val service = Robolectric.buildService(PasskeyCredentialService::class.java).create().get()
        var response: BeginGetCredentialResponse? = null
        service.onBeginGetCredentialRequest(
            BeginGetCredentialRequest(listOf()),
            CancellationSignal(),
            object : OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException> {
                override fun onResult(r: BeginGetCredentialResponse) { response = r }
                override fun onError(error: GetCredentialException) = Unit
            },
        )
        assertNotNull(response)
        assertEquals(1, response!!.authenticationActions.size)
    }

    @Test fun cableServiceWithoutUriStopsItself() {
        val controller = Robolectric.buildService(
            com.vayunmathur.passwords.platform.cable.CableService::class.java,
        ).create()
        val service = controller.get()
        val result = service.onStartCommand(Intent(), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
    }
}
