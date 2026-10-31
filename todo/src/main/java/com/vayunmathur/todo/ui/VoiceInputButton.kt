package com.vayunmathur.todo.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMic

/**
 * Mic button that hands off to the user's speech recognizer (e.g. the offline Speech app)
 * and returns the transcript; hidden when no recognizer is installed.
 */
@Composable
fun VoiceInputButton(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    }
    val available = remember(context) { intent.resolveActivity(context.packageManager) != null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onResult)
    }
    if (!available) return
    val label = stringResource(com.vayunmathur.todo.R.string.voice_input)
    IconButton(onClick = { launcher.launch(intent) }, modifier = Modifier.semantics { contentDescription = label }) {
        IconMic()
    }
}
