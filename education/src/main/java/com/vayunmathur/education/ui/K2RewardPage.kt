package com.vayunmathur.education.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.education.Route
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.education.platform.LocalNarrator
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource
import com.vayunmathur.education.R

/** K-2 reward: celebratory stars and praise, no numbers or percentages. */
@Composable
fun K2RewardPage(backStack: NavBackStack<Route>, stars: Int) {
    val narrator = LocalNarrator.current
    LaunchedEffect(Unit) { narrator?.speak("You did it! Great job!") }

    AppScaffold(topBar = {}) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text("🦉", fontSize = 96.sp)
            Text("⭐".repeat(stars.coerceIn(1, 3)), fontSize = 56.sp)
            Text(
                stringResource(R.string.you_did_it),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = {
                    narrator?.stop()
                    backStack.reset(Route.Home)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_1), fontSize = 24.sp)
            }
        }
    }
}
