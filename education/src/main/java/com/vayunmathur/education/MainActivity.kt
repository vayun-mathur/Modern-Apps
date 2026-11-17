package com.vayunmathur.education

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.education.data.ContentRepository
import com.vayunmathur.education.data.EducationRepository
import com.vayunmathur.education.data.Learner
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.education.platform.EducationViewModelFactory
import com.vayunmathur.education.platform.LocalNarrator
import com.vayunmathur.education.platform.rememberNarrator
import com.vayunmathur.library.ui.MaActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry activity for Education: loads content and repositories off the main thread, seeds the single learner,
 * then hosts navigation with the narrator provided.
 */
class MainActivity : MaActivity() {
    private lateinit var content: ContentRepository
    private lateinit var repository: EducationRepository

    private val viewModel: EducationViewModel by viewModels {
        EducationViewModelFactory(application, content, repository)
    }
    private var ready by mutableStateOf(false)

    override fun onCreateBeforeContent() {
        lifecycleScope.launch(Dispatchers.IO) {
            content = ContentRepository.load(this@MainActivity)
            repository = EducationRepository.get(this@MainActivity)
            // Seed the single learner row so onboarding can observe it.
            if (repository.getLearner() == null) repository.upsertLearner(Learner())
            withContext(Dispatchers.Main) { ready = true }
        }
    }

    @Composable
    override fun Content() {
        CompositionLocalProvider(LocalNarrator provides rememberNarrator()) {
            if (ready) Navigation(viewModel)
        }
    }
}
