package com.vayunmathur.passwords.ui

import android.app.Activity
import android.content.Intent
import android.os.Parcelable
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.passwords.FakeAndroidKeyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The unlock flow is what the Credential Manager framework hands the activity
 * after the user picks "unlock": it arrives as a framework
 * `android.service.credentials` parcelable in the intent, which the Jetpack
 * handler converts. That framework request has no public constructor in the
 * SDK stubs, so it is built reflectively with an empty option list — enough
 * to exercise the whole flow, which must answer with an (empty) begin-get
 * response rather than a credential.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasskeyAuthUnlockTest {

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        DatabaseHelper(RuntimeEnvironment.getApplication()).storePassphrase("test-passphrase")
    }

    @Test fun unlockFlowWithEmptyFrameworkRequestReturnsOk() {
        val frameworkRequest = buildFrameworkBeginRequest()
        val intent = Intent(RuntimeEnvironment.getApplication(), PasskeyAuthActivity::class.java).apply {
            putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_UNLOCK)
            putExtra("android.service.credentials.extra.BEGIN_GET_CREDENTIAL_REQUEST", frameworkRequest)
        }
        val controller = Robolectric.buildActivity(PasskeyAuthActivity::class.java, intent)
        controller.create()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        // The result intent carries a begin-get response (empty: no options were requested).
        val resultData = shadowOf(activity).resultIntent
        assertTrue(resultData != null)
    }

    private fun buildFrameworkBeginRequest(): Parcelable {
        val klass = Class.forName("android.service.credentials.BeginGetCredentialRequest")
        val ctor = klass.declaredConstructors.first { it.parameterTypes.size == 2 }
        ctor.isAccessible = true
        // (List<GetCredentialOption>, String callingPackage) in some order; the
        // framework conversion only reads the option list for our purposes.
        val args = ctor.parameterTypes.map { type ->
            when {
                List::class.java.isAssignableFrom(type) -> emptyList<Any>()
                type == String::class.java -> "com.example.app"
                else -> null
            }
        }.toTypedArray()
        return ctor.newInstance(*args) as Parcelable
    }
}
