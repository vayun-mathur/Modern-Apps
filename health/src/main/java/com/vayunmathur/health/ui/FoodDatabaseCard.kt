package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.data.FoodDatabase
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import java.text.NumberFormat

/**
 * First-run expansion of the food database shipped inside the APK.
 *
 * There is nothing to download and nothing to choose, so this only ever
 * reports progress or an error. SQLite can't read a file inside an APK, so the
 * asset has to be unpacked into app storage once before ingredient search can
 * use it; until then the recipe builder can only find ingredients already
 * saved on the device.
 */
@Composable
fun FoodDatabaseCard(viewModel: HealthViewModel, modifier: Modifier = Modifier) {
    val status by viewModel.foodDatabaseStatus.collectAsState()

    // Unpacking is what makes search work, so start it as soon as a screen
    // that needs it appears. A no-op once it has been done.
    LaunchedEffect(Unit) { viewModel.prepareFoodDatabase() }

    // Nothing worth saying once it's ready and searchable.
    if (status is FoodDatabase.Status.Ready) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.food_database),
                style = MaterialTheme.typography.titleMedium,
                color = HealthColors.Nutrition,
            )

            when (val state = status) {
                is FoodDatabase.Status.Absent -> {
                    Text(stringResource(R.string.food_database_absent), style = MaterialTheme.typography.bodySmall)
                }

                is FoodDatabase.Status.Preparing -> {
                    // totalProducts is 0 only in the instant before the asset
                    // header is read; show an indeterminate bar, not 0%.
                    if (state.totalProducts > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (state.productsWritten.toFloat() / state.totalProducts).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        stringResource(
                            R.string.food_database_preparing,
                            NumberFormat.getIntegerInstance().format(state.productsWritten),
                            NumberFormat.getIntegerInstance().format(state.totalProducts),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is FoodDatabase.Status.Failed -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { viewModel.prepareFoodDatabase() }) {
                        Text(stringResource(R.string.food_database_retry))
                    }
                }

                // Filtered out above; the compiler still wants the branch.
                is FoodDatabase.Status.Ready -> Unit
            }
        }
    }
}
