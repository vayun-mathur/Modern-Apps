package com.vayunmathur.speech

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.speech.ui.SpeechSetupActions
import com.vayunmathur.speech.ui.SpeechSetupScreen
import com.vayunmathur.speech.ui.SpeechSetupUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:speech`, rendered from Compose previews instead of from an
 * instrumented test on a device. See `common-conventions-preview-metadata`.
 *
 * `./gradlew :speech:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/speech/`, where `release.sh` picks them up.
 *
 * Three things to keep in mind when editing:
 *
 *  - Order comes from the function names. The generated PNG filenames embed them, so
 *    `Preview1Setup`/`Preview2Ready`/... sort into listing order.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in Studio
 *    but is not collected as a screenshot test, which surfaces as "did not discover any
 *    tests". Previews must also be class members, not top-level functions, for the same
 *    reason: the engine discovers them as JUnit tests.
 *  - Everything is a literal. The real screen reads Settings.Secure, a runtime permission
 *    and two ~100 MB model directories to decide which steps are done — none of which
 *    exists in a preview, which is exactly why that state was hoisted out of the screen.
 *
 * The setup checklist is the whole app: once the models are installed and the services are
 * selected, this app has no UI of its own and works through every *other* app's voice input
 * and read-aloud. So the listing shows the checklist before and after.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-setup", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Setup() {
        DynamicTheme(darkTheme = true) {
            SpeechSetupScreen(
                state = SpeechSetupUiState(),
                actions = SpeechSetupActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-partway", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Partway() {
        DynamicTheme(darkTheme = true) {
            SpeechSetupScreen(
                state = SpeechSetupUiState(
                    modelReady = true,
                    hasMic = true,
                    isRecognizerDefault = true,
                ),
                actions = SpeechSetupActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-ready", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Ready() {
        DynamicTheme(darkTheme = true) {
            SpeechSetupScreen(
                state = SpeechSetupUiState(
                    modelReady = true,
                    hasMic = true,
                    isRecognizerDefault = true,
                    ttsModelReady = true,
                    isTtsDefault = true,
                ),
                actions = SpeechSetupActions.Noop,
            )
        }
    }
}
