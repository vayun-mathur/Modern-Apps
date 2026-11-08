package com.vayunmathur.taxi.network.uber

import android.os.Build
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Native Uber sign-in against the "silkscreen" onboarding service.
 *
 * Recovered from `com.uber.model.core.generated.rtapi.services.silkscreen.SilkScreenApi`:
 *
 * ```java
 * @POST("/rt/silk-screen/submit-form")
 * Single<OnboardingFormContainer> submitForm(@Header("x-uber-call-uuid") String, @Body Map);
 * ```
 *
 * The flow is a server-driven form machine: you POST a screen answer, the server replies with
 * the next screen to render, and you repeat until it hands back a session. Screen and field
 * names come from `OnboardingScreenType` / `OnboardingFieldType`, and the request wrapper from
 * `OnboardingFormContainerAnswer` (`inAuthSessionID` + `formAnswer`).
 *
 * Caveat that decides whether this can work at all: `OnboardingFormAnswer` carries a
 * `deviceData` field, which in the official app is produced by the `libse_loader.so` security
 * engine (see `uber-re/api-notes.md` §3). We send it empty. If the server rejects that, native
 * login is not reachable off a Google-attested device and the WebView path stands.
 */
object UberAuth {
    private const val TAG = "UberAuth"
    const val BASE = "https://cn-geo1.uber.com"

    private const val APP_VERSION = "4.641.10000"
    private const val CLIENT_NAME = "client"

    /** Logcat previews: enough to identify the screen/error without dumping the whole form. */
    private const val LOG_BODY_PREVIEW_MAX = 800
    private const val ERROR_BODY_PREVIEW_MAX = 300

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private fun headers() = mapOf(
        "x-uber-call-uuid" to UUID.randomUUID().toString(),
        "x-uber-client-name" to CLIENT_NAME,
        "x-uber-client-version" to APP_VERSION,
        "x-uber-device" to "android",
        "x-uber-device-os" to Build.VERSION.RELEASE,
        "x-uber-app-variant" to "rider",
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "User-Agent" to "client/$APP_VERSION (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})",
    )

    /** Starts the flow with a phone number; on success Uber texts an OTP. */
    suspend fun startPhoneLogin(countryCode: String, phoneNumber: String): UberAuthResult =
        submit(
            inAuthSessionID = "",
            screen = ScreenAnswer(
                screenType = "PHONE_NUMBER_INITIAL",
                fieldAnswers = listOf(
                    FieldAnswer(fieldType = "PHONE_COUNTRY_CODE", phoneCountryCode = countryCode),
                    FieldAnswer(fieldType = "PHONE_NUMBER", phoneNumber = phoneNumber),
                ),
            ),
        )

    /** Answers the SMS OTP screen. */
    suspend fun submitSmsOtp(sessionId: String, code: String): UberAuthResult =
        submit(
            inAuthSessionID = sessionId,
            screen = ScreenAnswer(
                screenType = "PHONE_OTP",
                fieldAnswers = listOf(
                    FieldAnswer(fieldType = "PHONE_SMS_OTP", phoneSMSOTP = code),
                ),
            ),
        )

    private suspend fun submit(inAuthSessionID: String, screen: ScreenAnswer): UberAuthResult {
        val body = json.encodeToString(
            FormContainerAnswer.serializer(),
            FormContainerAnswer(
                inAuthSessionID = inAuthSessionID,
                formAnswer = FormAnswer(
                    flowType = if (inAuthSessionID.isEmpty()) "INITIAL" else "SIGN_IN",
                    screenAnswers = listOf(screen),
                    deviceData = "",
                    standardFlow = true,
                ),
            ),
        )
        val resp = NetworkClient.performRequest(
            url = "$BASE/rt/silk-screen/submit-form",
            method = "POST",
            headers = headers(),
            body = body,
        )
        Log.debug(TAG, "POST /rt/silk-screen/submit-form -> ${resp.status}")
        if (!resp.isSuccess) {
            Log.status(TAG, "submit-form ${resp.status}: ${resp.body.take(LOG_BODY_PREVIEW_MAX)}")
            return UberAuthResult.Failed("HTTP ${resp.status}: ${resp.body.take(ERROR_BODY_PREVIEW_MAX)}")
        }
        Log.dev(TAG, "submit-form ok: ${resp.body.take(LOG_BODY_PREVIEW_MAX)}")
        val container = runCatching {
            json.decodeFromString(FormContainer.serializer(), resp.body)
        }.getOrElse { return UberAuthResult.Failed("Unreadable silkscreen response") }
        return UberAuthResult.NextScreen(
            sessionId = container.inAuthSessionID.orEmpty(),
            screenType = container.form?.screens?.firstOrNull()?.screenType,
            raw = resp.body,
        )
    }
}

sealed interface UberAuthResult {
    /** Server accepted the answer and wants the next screen filled in. */
    data class NextScreen(
        val sessionId: String,
        val screenType: String?,
        val raw: String,
    ) : UberAuthResult

    data class Failed(val message: String) : UberAuthResult
}

@Serializable
private data class FormContainerAnswer(
    val inAuthSessionID: String,
    val formAnswer: FormAnswer,
)

@Serializable
private data class FormAnswer(
    val flowType: String,
    val screenAnswers: List<ScreenAnswer>,
    val deviceData: String,
    val standardFlow: Boolean,
)

@Serializable
private data class ScreenAnswer(
    val screenType: String,
    val fieldAnswers: List<FieldAnswer>,
)

@Serializable
private data class FieldAnswer(
    val fieldType: String,
    val phoneNumber: String? = null,
    val phoneCountryCode: String? = null,
    val phoneSMSOTP: String? = null,
    val emailAddress: String? = null,
    val password: String? = null,
)

@Serializable
private data class FormContainer(
    val inAuthSessionID: String? = null,
    val form: Form? = null,
)

@Serializable
private data class Form(
    val screens: List<Screen> = emptyList(),
)

@Serializable
private data class Screen(
    @SerialName("screenType") val screenType: String? = null,
)
