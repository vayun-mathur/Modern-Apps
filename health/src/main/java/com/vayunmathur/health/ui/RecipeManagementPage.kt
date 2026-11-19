@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.ui

import kotlin.uuid.Uuid
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.PrimaryTabRow
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.R as UiR
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.health.data.FoodDatabase
import com.vayunmathur.health.data.FoodSearchAPI
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.launch
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeManagementPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showIngredientSearch by remember { mutableStateOf(false) }

    val recipes by viewModel.allRecipes.collectAsState(emptyList())
    val ingredients by viewModel.allIngredients.collectAsState(emptyList())

    val isListEmpty = if (selectedTab == 0) recipes.isEmpty() else ingredients.isEmpty()

    if (showIngredientSearch) {
        IngredientSearchDialog(
            viewModel = viewModel,
            onDismiss = { showIngredientSearch = false },
            onIngredientSelected = { ingredient ->
                viewModel.insertIngredient(ingredient)
                showIngredientSearch = false
            }
        )
    }

    AppScaffold(
        title = stringResource(R.string.recipes),
        backStack = backStack,
        floatingActionButton = {
            if (!isListEmpty) {
                FloatingActionButton(onClick = { 
                    if (selectedTab == 0) {
                        backStack.add(Route.RecipeEditor()) 
                    } else {
                        showIngredientSearch = true
                    }
                }) {
                    IconAdd()
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                contentColor = HealthColors.Nutrition,
            ) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, selectedContentColor = HealthColors.Nutrition, text = { Text(stringResource(R.string.recipes)) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, selectedContentColor = HealthColors.Nutrition, text = { Text(stringResource(R.string.ingredients)) })
            }

            // Ingredient search depends on the bundled food database, so its
            // first-run state belongs on the Ingredients tab.
            if (selectedTab == 1) {
                FoodDatabaseCard(viewModel, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }

            if (isListEmpty) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        if (selectedTab == 0) {
                            backStack.add(Route.RecipeEditor())
                        } else {
                            showIngredientSearch = true
                        }
                    }) {
                        IconAdd()
                        Spacer(Modifier.width(8.dp))
                        Text(if (selectedTab == 0) stringResource(R.string.create_your_first_recipe) else stringResource(R.string.add_your_first_ingredient))
                    }
                }
            } else {
                if (selectedTab == 0) {
                    RecipesList(recipes, viewModel) { recipeId -> backStack.add(Route.RecipeEditor(recipeId)) }
                } else {
                    IngredientsList(ingredients, viewModel)
                }
            }
        }
    }
}

@Composable
private fun RecipesList(recipes: List<Recipe>, viewModel: HealthViewModel, onRecipeClick: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(recipes, key = { it.id }) { recipe ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onRecipeClick(recipe.id) }
            ) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(recipe.name, style = MaterialTheme.typography.titleMedium, color = HealthColors.Nutrition)
                    IconButton(onClick = { viewModel.deleteRecipe(recipe) }) {
                        IconDelete()
                    }
                }
            }
        }
    }
}

@Composable
private fun IngredientsList(ingredients: List<Ingredient>, viewModel: HealthViewModel) {
    var editingIngredient by remember { mutableStateOf<Ingredient?>(null) }
    
    if (editingIngredient != null) {
        var customName by remember { mutableStateOf(editingIngredient!!.customName ?: "") }
        AlertDialog(
            onDismissRequest = { editingIngredient = null },
            title = { Text(stringResource(R.string.rename_ingredient)) },
            text = {
                Column {
                    Text(stringResource(R.string.original_1, editingIngredient!!.originalName), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text(stringResource(R.string.custom_name)) }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newIng = editingIngredient!!.copy(customName = customName.ifBlank { null })
                    viewModel.updateIngredient(newIng)
                    editingIngredient = null
                }) { Text(stringResource(UiR.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editingIngredient = null }) { Text(stringResource(UiR.string.cancel)) }
            }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ingredients, key = { it.id }) { ingredient ->
            Card(modifier = Modifier.fillMaxWidth().clickable { editingIngredient = ingredient }) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ingredient.displayName, style = MaterialTheme.typography.titleMedium)
                        if (ingredient.customName != null) {
                            Text(stringResource(R.string.original_1, ingredient.originalName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            viewModel.updateIngredient(ingredient.copy(isRecipe = !ingredient.isRecipe))
                        }) {
                            if (ingredient.isRecipe) IconFire() else IconFire(tint = MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { viewModel.deleteIngredient(ingredient) }) {
                            IconDelete()
                        }
                    }
                }
            }
        }
    }
}
