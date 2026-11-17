package com.vayunmathur.flashcards

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.flashcards.data.FlashcardsRepository
import com.vayunmathur.flashcards.platform.FlashcardsViewModel
import com.vayunmathur.flashcards.platform.FlashcardsViewModelFactory
import com.vayunmathur.flashcards.ui.Navigation
import com.vayunmathur.flashcards.ui.ThemeMode
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.MaActivity

/** Entry activity that owns the [FlashcardsViewModel] and applies the theme from its settings. */
class MainActivity : MaActivity() {
    private val viewModel: FlashcardsViewModel by viewModels {
        FlashcardsViewModelFactory(application, FlashcardsRepository.get(application))
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        val settings by viewModel.settings.collectAsStateWithLifecycle()
        val darkTheme = when (settings.themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            else -> null
        }
        DynamicTheme(darkTheme = darkTheme, content = content)
    }

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }
}
