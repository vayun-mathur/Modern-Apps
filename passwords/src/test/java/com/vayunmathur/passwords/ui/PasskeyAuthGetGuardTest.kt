package com.vayunmathur.passwords.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.platform.CallingAppInfoFactory
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
 * The get/password flows first convert the framework credential request in
 * the intent, then look for the credential-id extra. With a framework request
 * present but no credential selected, both must cancel cleanly — before any
 * vault access. The framework request classes have no public constructors in
 * the SDK stubs, so they are built reflectively with an empty option list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasskeyAuthGetGuardTest {

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        DatabaseHelper(RuntimeEnvironment.getApplication()).storePassphrase("test-passphrase")
    }

    @Test fun getFlowWithRequestButNoCredentialIdCancels() {
        val activity = launch(PasskeyAuthActivity.FLOW_GET, withFrameworkGetRequest = true)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test fun passwordFlowWithRequestButNoPasswordIdCancels() {
        val activity = launch(PasskeyAuthActivity.FLOW_PASSWORD, withFrameworkGetRequest = true)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    private fun launch(flow: String, withFrameworkGetRequest: Boolean): PasskeyAuthActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), PasskeyAuthActivity::class.java).apply {
            putExtra(PasskeyAuthActivity.EXTRA_FLOW, flow)
            if (withFrameworkGetRequest) {
                putExtra(
                    "android.service.credentials.extra.GET_CREDENTIAL_REQUEST",
                    buildFrameworkGetRequest(),
                )
            }
        }
        val controller = Robolectric.buildActivity(PasskeyAuthActivity::class.java, intent)
        controller.create()
        return controller.get()
    }

    private fun buildFrameworkGetRequest(): Parcelable {
        val klass = Class.forName("android.service.credentials.GetCredentialRequest")
        val ctor = klass.declaredConstructors
            .filter { ctorCandidate ->
                ctorCandidate.parameterTypes.isNotEmpty() &&
                    ctorCandidate.parameterTypes.none { it.name == "android.os.Parcel" }
            }
            .minByOrNull { it.parameterTypes.size }!!
        ctor.isAccessible = true
        val args = ctor.parameterTypes.map { type ->
            when {
                List::class.java.isAssignableFrom(type) -> emptyList<Any>()
                type == String::class.java -> "com.example.app"
                type == Boolean::class.javaPrimitiveType -> false
                type == Bundle::class.java -> Bundle()
                type.name == "android.service.credentials.CallingAppInfo" -> frameworkCallingAppInfo()
                else -> null
            }
        }.toTypedArray()
        return ctor.newInstance(*args) as Parcelable
    }

    private fun frameworkCallingAppInfo(): Any {
        val klass = Class.forName("android.service.credentials.CallingAppInfo")
        val ctor = klass.declaredConstructors
            .filter { ctorCandidate ->
                ctorCandidate.parameterTypes.isNotEmpty() &&
                    ctorCandidate.parameterTypes.none { it.name == "android.os.Parcel" }
            }
            .minByOrNull { it.parameterTypes.size }!!
        ctor.isAccessible = true
        val args = ctor.parameterTypes.map { type ->
            when {
                type == String::class.java -> "com.example.app"
                type.name == "android.content.pm.SigningInfo" -> CallingAppInfoFactory.signingInfo()
                else -> null
            }
        }.toTypedArray()
        return ctor.newInstance(*args)
    }
}
