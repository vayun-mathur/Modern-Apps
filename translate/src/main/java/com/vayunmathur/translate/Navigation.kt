package com.vayunmathur.translate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.translate.data.TranslationRecord
import com.vayunmathur.translate.platform.SharedImageArrival
import com.vayunmathur.translate.platform.TranslateViewModel
import com.vayunmathur.translate.ui.CameraTranslateScreen
import com.vayunmathur.translate.ui.HistoryPage
import com.vayunmathur.translate.ui.ImageTranslatePage
import com.vayunmathur.translate.ui.LanguagePickerPage
import com.vayunmathur.translate.ui.SharedImageEffect
import com.vayunmathur.translate.ui.TextTranslatePage

/**
 * [sharedImage] is an image handed over by Share / Open with (#846); once acted on,
 * [onSharedImageConsumed] clears it. A ready image replaces the stack with
 * Text → Image, the same shape the in-app picker leaves, so Back lands on Text.
 */
@Composable
fun Navigation(
    viewModel: TranslateViewModel,
    initialText: String,
    sharedImage: SharedImageArrival? = null,
    onSharedImageConsumed: () -> Unit = {},
) {
    val backStack = rememberNavBackStack<Route>(Route.Text)
    // A history row tapped for reuse: consumed once by the text page, which loads
    // the record's source text and language pair, then the stack pops back to Text.
    var reuseRequest by remember { mutableStateOf<TranslationRecord?>(null) }
    MainNavigation(backStack) {
        entry<Route.Text> {
            TextTranslatePage(
                viewModel = viewModel,
                initialText = initialText,
                reuseRequest = reuseRequest,
                onReuseConsumed = { reuseRequest = null },
                onOpenCamera = { backStack.add(Route.Camera) },
                onOpenImage = { uri -> backStack.add(Route.Image(uri)) },
                onOpenHistory = { backStack.add(Route.History) },
                onOpenLanguagePicker = { forSource ->
                    backStack.add(Route.LanguagePicker(forSource))
                },
            )
        }
        entry<Route.History> {
            HistoryPage(
                viewModel = viewModel,
                onReuse = { record ->
                    reuseRequest = record
                    backStack.pop()
                },
                onBack = { backStack.pop() },
            )
        }
        entry<Route.Camera>(metadata = FullscreenPage()) {
            CameraTranslateScreen(
                viewModel = viewModel,
                onBack = { backStack.pop() },
                onOpenLanguagePicker = { forSource ->
                    backStack.add(Route.LanguagePicker(forSource))
                },
            )
        }
        entry<Route.Image>(metadata = FullscreenPage()) { route ->
            ImageTranslatePage(
                viewModel = viewModel,
                imageUri = route.imageUri,
                onReplaceImage = { uri -> backStack.setLast(Route.Image(uri)) },
                onBack = { backStack.pop() },
                onOpenLanguagePicker = { forSource ->
                    backStack.add(Route.LanguagePicker(forSource))
                },
            )
        }
        entry<Route.LanguagePicker>(metadata = DialogPage()) { route ->
            LanguagePickerPage(
                viewModel = viewModel,
                forSource = route.forSource,
                onBack = { backStack.pop() },
            )
        }
    }
    // After MainNavigation so its message drain is already collecting when a
    // rejection is posted on the first frame.
    SharedImageEffect(sharedImage, backStack, onSharedImageConsumed)
}
