package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.keyboard.platform.ime.EnterAction
import com.vayunmathur.keyboard.platform.ime.ImeActions
import com.vayunmathur.keyboard.platform.ime.KeyboardState
import com.vayunmathur.keyboard.domain.KeyboardPage
import com.vayunmathur.keyboard.data.KeyboardSettings
import com.vayunmathur.keyboard.domain.ShiftState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:keyboard`. See `common-conventions-preview-metadata`.
 *
 * Unlike the other apps, the thing being sold here is the IME itself, not an activity, so
 * these render [KeyboardScreen] directly — which is already stateless ([KeyboardState] plus
 * [ImeActions]) and needed no refactor. The keyboard is bottom-anchored in a full-height
 * frame so the listing image is phone-shaped rather than a thin strip.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    private fun state(
        page: KeyboardPage,
        shift: ShiftState = ShiftState.OFF,
        suggestions: List<String> = emptyList(),
    ) = KeyboardState().apply {
        this.page = page
        this.basePage = if (page == KeyboardPage.EMOJI) KeyboardPage.LETTERS else page
        this.shift = shift
        this.suggestions = suggestions
        this.settings = KeyboardSettings(numberRow = true, showSuggestions = true)
        this.enterAction = EnterAction.SEND
    }

    /** Bottom-anchors the keyboard in a full-height surface, as it appears over an app. */
    @Composable
    private fun Framed(content: @Composable () -> Unit) {
        DynamicTheme(darkTheme = true) {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                verticalArrangement = Arrangement.Bottom,
            ) { content() }
        }
    }

    @PreviewTest
    @Preview(name = "1-letters", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Letters() {
        Framed {
            KeyboardScreen(
                state = state(
                    page = KeyboardPage.LETTERS,
                    shift = ShiftState.SHIFTED,
                    suggestions = listOf("Hello", "Help", "Held"),
                ),
                actions = ImeActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-symbols", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Symbols() {
        Framed {
            KeyboardScreen(state = state(KeyboardPage.SYMBOLS), actions = ImeActions.Noop)
        }
    }

    @PreviewTest
    @Preview(name = "3-emoji", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Emoji() {
        Framed {
            KeyboardScreen(state = state(KeyboardPage.EMOJI), actions = ImeActions.Noop)
        }
    }
}
