package com.vayunmathur.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.*
import com.vayunmathur.health.util.FoodSearchAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.*
import kotlinx.coroutines.launch
import java.util.UUID

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipe & Ingredients") }
            )
        },
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
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Recipes") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Ingredients") })
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
                        Text(if (selectedTab == 0) "Create your first recipe" else "Add your first ingredient")
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
fun RecipesList(recipes: List<Recipe>, viewModel: HealthViewModel, onRecipeClick: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(recipes) { recipe ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onRecipeClick(recipe.id) }
            ) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(recipe.name, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { viewModel.deleteRecipe(recipe) }) {
                        IconDelete()
                    }
                }
            }
        }
    }
}

@Composable
fun IngredientsList(ingredients: List<Ingredient>, viewModel: HealthViewModel) {
    var editingIngredient by remember { mutableStateOf<Ingredient?>(null) }
    
    if (editingIngredient != null) {
        var customName by remember { mutableStateOf(editingIngredient!!.customName ?: "") }
        AlertDialog(
            onDismissRequest = { editingIngredient = null },
            title = { Text("Rename Ingredient") },
            text = {
                Column {
                    Text("Original: ${editingIngredient!!.originalName}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Custom Name") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newIng = editingIngredient!!.copy(customName = customName.ifBlank { null })
                    viewModel.updateIngredient(newIng)
                    editingIngredient = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingIngredient = null }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ingredients) { ingredient ->
            Card(modifier = Modifier.fillMaxWidth().clickable { editingIngredient = ingredient }) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ingredient.displayName, style = MaterialTheme.typography.titleMedium)
                        if (ingredient.customName != null) {
                            Text("Original: ${ingredient.originalName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            viewModel.updateIngredient(ingredient.copy(isRecipe = !ingredient.isRecipe))
                        }) {
                            if (ingredient.isRecipe) IconFire() else Icon(painterResource(R.drawable.baseline_local_fire_department_24), "Mark as Recipe", tint = MaterialTheme.colorScheme.outline)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel, recipeId: String? = null) {
    var recipeName by remember { mutableStateOf("") }
    var recipeIngredients by remember { mutableStateOf(listOf<RecipeIngredientData>()) }
    var showSearch by remember { mutableStateOf(false) }
    var editingIngredientData by remember { mutableStateOf<RecipeIngredientData?>(null) }
    var isAddingNewIngredient by remember { mutableStateOf(false) }

    // Load existing recipe if editing
    LaunchedEffect(recipeId) {
        if (recipeId != null) {
            val loaded = viewModel.loadRecipeForEdit(recipeId)
            if (loaded != null) {
                recipeName = loaded.name
                recipeIngredients = loaded.ingredients.map { RecipeIngredientData(it.ingredient, it.unit, it.quantity) }
            }
        }
    }

    if (showSearch) {
        IngredientSearchDialog(
            viewModel = viewModel,
            includeLocal = true,
            onDismiss = { showSearch = false },
            onIngredientSelected = { ingredient ->
                showSearch = false
                // Default to 100g
                editingIngredientData = RecipeIngredientData(
                    ingredient,
                    ServingUnit(id = UUID.randomUUID().toString(), ingredientId = ingredient.id, name = "g", grams = 1.0),
                    100.0
                )
                isAddingNewIngredient = true
            }
        )
    }

    if (editingIngredientData != null) {
        IngredientQuantityDialog(
            viewModel = viewModel,
            ingredient = editingIngredientData!!.ingredient,
            initialQuantity = editingIngredientData!!.quantity,
            initialUnit = editingIngredientData!!.unit,
            onDismiss = {
                editingIngredientData = null
                isAddingNewIngredient = false
            },
            onConfirm = { quantity, unit ->
                if (isAddingNewIngredient) {
                    recipeIngredients = recipeIngredients + RecipeIngredientData(editingIngredientData!!.ingredient, unit, quantity)
                } else {
                    recipeIngredients = recipeIngredients.map {
                        if (it === editingIngredientData) {
                            RecipeIngredientData(it.ingredient, unit, quantity)
                        } else it
                    }
                }
                editingIngredientData = null
                isAddingNewIngredient = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (recipeId == null) "Create Recipe" else "Edit Recipe") },
                navigationIcon = {
                    IconNavigation(backStack)
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = recipeName,
                onValueChange = { recipeName = it },
                label = { Text("Recipe Name") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Ingredients", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showSearch = true }) {
                    IconAdd()
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recipeIngredients) { riData ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            editingIngredientData = riData
                            isAddingNewIngredient = false
                        }
                    ) {
                        Row(modifier = Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(riData.ingredient.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text("${riData.quantity} ${riData.unit.name}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = {
                                recipeIngredients = recipeIngredients - riData
                            }) {
                                IconDelete()
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val items = recipeIngredients.map {
                        HealthViewModel.RecipeIngredientLoad(it.ingredient, it.unit, it.quantity)
                    }
                    viewModel.saveRecipe(recipeId, recipeName, items) {
                        backStack.pop()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = recipeName.isNotBlank() && recipeIngredients.isNotEmpty()
            ) {
                Text("Save Recipe")
            }
        }
    }
}

data class RecipeIngredientData(
    val ingredient: Ingredient,
    val unit: ServingUnit,
    val quantity: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientQuantityDialog(
    viewModel: HealthViewModel,
    ingredient: Ingredient,
    initialQuantity: Double,
    initialUnit: ServingUnit,
    onDismiss: () -> Unit,
    onConfirm: (Double, ServingUnit) -> Unit
) {
    var quantityStr by remember { mutableStateOf(initialQuantity.toString()) }
    var selectedUnit by remember { mutableStateOf(initialUnit) }
    var unitExpanded by remember { mutableStateOf(false) }
    var availableUnits by remember { mutableStateOf(listOf<ServingUnit>()) }

    LaunchedEffect(ingredient.id) {
        val units = viewModel.getUnitsForIngredient(ingredient.id)
        // Ensure the default unit or current unit is in the list
        val unitsWithCurrent = if (units.none { it.name == initialUnit.name }) {
            units + initialUnit
        } else units
        
        // Add "g" if not present at all
        val finalUnits = if (unitsWithCurrent.none { it.name == "g" }) {
            unitsWithCurrent + ServingUnit(id = UUID.randomUUID().toString(), ingredientId = ingredient.id, name = "g", grams = 1.0)
        } else unitsWithCurrent
        
        availableUnits = finalUnits
        selectedUnit = finalUnits.find { it.name == initialUnit.name } ?: initialUnit
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Quantity for ${ingredient.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = quantityStr,
                    onValueChange = { quantityStr = it },
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = unitExpanded,
                    onExpandedChange = { unitExpanded = !unitExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedUnit.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Unit") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = unitExpanded,
                        onDismissRequest = { unitExpanded = false }
                    ) {
                        availableUnits.forEach { unit ->
                            DropdownMenuItem(
                                text = { Text(unit.name) },
                                onClick = {
                                    selectedUnit = unit
                                    unitExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val q = quantityStr.toDoubleOrNull() ?: 0.0
                    onConfirm(q, selectedUnit)
                },
                enabled = quantityStr.toDoubleOrNull() != null
            ) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun IngredientSearchDialog(
    viewModel: HealthViewModel,
    includeLocal: Boolean = false,
    onDismiss: () -> Unit,
    onIngredientSelected: (Ingredient) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var remoteResults by remember { mutableStateOf(listOf<FoodSearchAPI.SearchResult>()) }
    var localResults by remember { mutableStateOf(listOf<Ingredient>()) }
    var isSearching by remember { mutableStateOf(false) }
    var isFetchingData by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search Ingredient") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        isSearching = true
                        scope.launch {
                            val results = viewModel.searchIngredients(query, includeLocal)
                            remoteResults = results.remote
                            localResults = results.local
                            isSearching = false
                        }
                    }) {
                        Text("Search")
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                if (isSearching || isFetchingData) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // Show Local results first if included
                        if (includeLocal && localResults.isNotEmpty()) {
                            item {
                                Text("Downloaded", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
                            }
                            items(localResults) { ingredient ->
                                ListItem(
                                    headlineContent = { Text(ingredient.displayName) },
                                    supportingContent = { Text("Saved Locally", style = MaterialTheme.typography.bodySmall) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable { onIngredientSelected(ingredient) }
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }

                        // Show Remote results
                        if (remoteResults.isNotEmpty()) {
                            item {
                                Text("Online", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
                            }
                            items(remoteResults) { result ->
                                // Skip if already in local results to avoid duplicates
                                if (includeLocal && localResults.any { it.id == result.id.toString() }) return@items

                                ListItem(
                                    headlineContent = { Text(result.displayName) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    modifier = Modifier.clickable {
                                        isFetchingData = true
                                        scope.launch {
                                            val ingredient = FoodSearchAPI.getIngredientData(result.id, result.displayName)
                                            isFetchingData = false
                                            if (ingredient != null) {
                                                onIngredientSelected(ingredient)
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                        
                        if (!isSearching && !isFetchingData && remoteResults.isEmpty() && localResults.isEmpty() && query.isNotBlank()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("No results found", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
