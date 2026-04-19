package com.vayunmathur.games.wordmaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.AVAILABLE_LANGUAGES
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.games.wordmaker.util.AppBackupAgent
import com.vayunmathur.games.wordmaker.util.Dictionary
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Game : Route
    @Serializable
    data object GameCenter : Route
}

data class ChooserLetter(val id: Int, val char: Char)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val context = LocalContext.current
                val levelDataStore = remember { LevelDataStore(context) }
                val backStack = rememberNavBackStack<Route>(Route.Game)
                MainNavigation(backStack) {
                    entry<Route.Game> {
                        WordMakerGameLoader(backStack, levelDataStore)
                    }
                    entry<Route.GameCenter> {
                        GameCenterScreen(
                            backupAgent = AppBackupAgent(),
                            manager = rememberAchievementsManager(levelDataStore),
                            onBack = { backStack.pop() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun rememberAchievementsManager(levelDataStore: LevelDataStore): AchievementsManager {
    val context = LocalContext.current
    return remember {
        val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }
        com.vayunmathur.games.wordmaker.util.WordMakerAchievementsManager(context, json, levelDataStore)
    }
}

@Composable
fun WordMakerGameLoader(backStack: NavBackStack<Route>, levelDataStore: LevelDataStore) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val currentLanguageId by levelDataStore.currentLanguage.collectAsState(initial = "en")
    val currentLevel by levelDataStore.currentLevel.collectAsState(initial = 1)
    val languageConfig = remember(currentLanguageId) {
        AVAILABLE_LANGUAGES.find { it.id == currentLanguageId } ?: AVAILABLE_LANGUAGES.first()
    }
    var crosswordData by remember { mutableStateOf<CrosswordData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val dictionary by remember { mutableStateOf(Dictionary()) }
    val coroutineScope = rememberCoroutineScope { Dispatchers.IO }

    val achievementsManager = rememberAchievementsManager(levelDataStore)
    val newAchievement by achievementsManager.newAchievement.collectAsState()
    var dictionary by remember { mutableStateOf(Dictionary.EMPTY) }
    var totalLevels by remember { mutableStateOf<Int?>(null) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val isGameComplete = totalLevels != null && currentLevel > totalLevels!!

    LaunchedEffect(currentLanguageId) {
        achievementsManager.checkExistingAchievements()
        error = null
        dictionary = withContext(Dispatchers.IO) {
            Dictionary.load(context, languageConfig.dictionaryFile)
        }
        totalLevels = withContext(Dispatchers.IO) {
            context.assets.list(languageConfig.levelsPath)?.count { it.endsWith(".txt") }
        }
    }

    LaunchedEffect(currentLevel, currentLanguageId) {
        error = null
        try {
            val data = withContext(Dispatchers.IO) {
                CrosswordData.fromAsset(context, "${languageConfig.levelsPath}/$currentLevel.txt")
            }
            if (data == null) {
                crosswordData = null
                error = resources.getString(R.string.error_parse_level)
            } else {
                crosswordData = data
            }
        } catch (e: Exception) {
            crosswordData = null
            error = resources.getString(R.string.error_load_level, e.message)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            error != null -> {
                Text(text = error!!, color = colorScheme.error)
            }
            crosswordData != null -> {
                WordGameScreen(
                    crosswordData = crosswordData!!,
                    levelDataStore = levelDataStore,
                    currentLevel = currentLevel,
                    dictionary = dictionary,
                    achievementsManager = achievementsManager,
                    onOpenGameCenter = { backStack.add(Route.GameCenter) }
                )
            }
            else -> {
                CircularProgressIndicator()
            }
        }

        newAchievement?.let {
            AchievementNotification(it) {
                achievementsManager.dismissNotification()
            }
        }

        androidx.compose.material3.TextButton(
            onClick = { showLanguagePicker = true },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
        ) {
            Text(currentLanguageId.uppercase())
        }

        if (showLanguagePicker) {
            AlertDialog(
                onDismissRequest = { showLanguagePicker = false },
                title = { Text(stringResource(R.string.select_language)) },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(AVAILABLE_LANGUAGES) { lang ->
                            Text(
                                text = lang.displayName,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        coroutineScope.launch { levelDataStore.saveLanguage(lang.id) }
                                        showLanguagePicker = false
                                    }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                fontWeight = if (lang.id == currentLanguageId) FontWeight.Bold else null
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showLanguagePicker = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            )
        }
    }
}

data class AnimatedLetter(
    val char: Char,
    val startOffset: Offset,
    val endOffset: Offset,
    val progress: Animatable<Float, AnimationVector1D>
)

data class WordToAnimate(val word: String, val letterIds: List<Int>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordGameScreen(
    crosswordData: CrosswordData,
    levelDataStore: LevelDataStore,
    currentLevel: Int,
    dictionary: Dictionary,
    achievementsManager: AchievementsManager,
    onOpenGameCenter: () -> Unit
) {
    val foundWords by levelDataStore.foundWords.collectAsState(initial = emptySet())
    val bonusWords by levelDataStore.bonusWords.collectAsState(initial = emptySet())
    var showBonusWordsDialog by remember(currentLevel) { mutableStateOf(false) }
    val density = LocalDensity.current
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var wordWithDefinition by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    // Animation state
    val coroutineScope = rememberCoroutineScope()
    var animatedWord by remember(currentLevel) { mutableStateOf<String?>(null) }
    val animationProgress = remember(currentLevel) { Animatable(0f) }
    var wordBoxOffset by remember(currentLevel) { mutableStateOf(Offset.Zero) }
    var bonusButtonOffset by remember(currentLevel) { mutableStateOf(Offset.Zero) }
    var crosswordCellPositions by remember(currentLevel) {
        mutableStateOf<Map<Pair<Int, Int>, Offset>>(
            emptyMap()
        )
    }
    var letterChooserPositions by remember(currentLevel) {
        mutableStateOf<Map<Int, Offset>>(
            emptyMap()
        )
    }
    var wordToAnimate by remember(currentLevel) { mutableStateOf<WordToAnimate?>(null) }
    var animatedLetters by remember(currentLevel) { mutableStateOf<List<AnimatedLetter>>(emptyList()) }

    // Animatables for shaking (we'll animate them directly when submission fails)
    val wordShakeAnim = remember { Animatable(0f) }
    val bonusShakeAnim = remember { Animatable(0f) }

    var scale by remember { mutableFloatStateOf(1f) }
    var shuffledLetters by remember(crosswordData) {
        mutableStateOf(crosswordData.lettersInChooser.mapIndexed { index, char ->
            ChooserLetter(index, char)
        })
    }


    LaunchedEffect(wordToAnimate) {
        wordToAnimate?.let { animationInfo ->
            val word = animationInfo.word
            val letterPositions = crosswordData.letterPositions[word]
            if (letterPositions != null) {
                val letters = word.mapIndexed { index, char ->
                    val id = animationInfo.letterIds[index]
                    val start = (letterChooserPositions[id] ?: Offset.Zero) - rootOffset
                    val end =
                        (crosswordCellPositions[letterPositions[index]] ?: Offset.Zero) - rootOffset

                    val offsetCorrection = with(density) { 15.dp.toPx() }
                    val correctedStart = start.plus(Offset(offsetCorrection, offsetCorrection))

                    AnimatedLetter(char, correctedStart, end, Animatable(0f))
                }
                animatedLetters = letters

                // Animate
                val jobs = letters.map {
                    launch {
                        it.progress.animateTo(1f, animationSpec = tween(durationMillis = 800))
                    }
                }
                jobs.joinAll()

                // After animation
                levelDataStore.addFoundWord(word)
                wordToAnimate = null
                animatedLetters = emptyList()
            }
        }
    }

    val isWon = crosswordData.winsWith(foundWords)
    
    LaunchedEffect(isWon) {
        if (isWon) {
            if (currentLevel == 1) achievementsManager.onAchievementUnlocked("level_1_done")
            if (currentLevel == 861) achievementsManager.onAchievementUnlocked("manual_levels_done")
            
            achievementsManager.onProgressUpdated("manual_levels_done", currentLevel)
            achievementsManager.onProgressUpdated("level_50", currentLevel)
            achievementsManager.onProgressUpdated("level_100", currentLevel)
            achievementsManager.onProgressUpdated("level_500", currentLevel)
        }
    }

    Scaffold(
        Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.level_number, currentLevel)) },
                actions = {
                    IconButton(onClick = onOpenGameCenter) {
                        Icon(painterResource(id = android.R.drawable.btn_star_big_on), "Achievements")
                    }
                    com.vayunmathur.library.ui.BackupButtons(
                        datastoreNames = listOf("settings")
                    )
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding).padding(bottom = 32.dp)
                .fillMaxSize()
                .onGloballyPositioned {
                    rootOffset = it.localToRoot(Offset.Zero)
                }
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CrosswordBoard(
                        foundWords = foundWords,
                        crosswordData = crosswordData,
                        wordToAnimate = wordToAnimate?.word,
                        onCellPositioned = { position, offset ->
                            if (crosswordCellPositions[position] != offset) {
                                crosswordCellPositions =
                                    crosswordCellPositions + (position to offset)
                            }
                        },
                        onCellClicked = { row, col ->
                            val word = crosswordData.getWordAt(row, col, foundWords)
                            if (word != null && word in foundWords) {
                                coroutineScope.launch {
                                    val definition = dictionary.getDefinition(word)
                                    if (definition.isNotEmpty()) {
                                        wordWithDefinition = Pair(word, definition)
                                    }
                                }
                            }
                        }, {
                            scale = it
                        }
                    )
                }
                Box(
                    modifier = Modifier.height(320.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isWon) {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    levelDataStore.saveLevel(currentLevel + 1)
                                }
                            }
                        ) {
                            Text(stringResource(R.string.next_level))
                        }
                    } else {
                        LetterChooser(
                            letters = shuffledLetters,
                            onShuffle = {
                                var nextLetters = shuffledLetters.shuffled()
                                while (nextLetters == shuffledLetters && shuffledLetters.size > 1) {
                                    nextLetters = shuffledLetters.shuffled()
                                }
                                shuffledLetters = nextLetters
                            },
                            onWordSubmitted = { word, ids ->

                                suspend fun shakeWord() {
                                    val offsets = listOf(-16f, 12f, -8f, 6f, -3f, 0f)
                                    for (o in offsets) {
                                        val state = wordShakeAnim.animateTo(
                                            with(density) { o.dp.toPx() },
                                            animationSpec = tween(40)
                                        ).endState
                                        while (state.isRunning) delay(30)
                                    }
                                }
                                // Handle submission with shake animations for invalid cases
                                if (word in crosswordData.solutionWords) {
                                    if (word !in foundWords) {
                                        wordToAnimate = WordToAnimate(word, ids)
                                        if (word.length >= 7) achievementsManager.onAchievementUnlocked("long_word")
                                    }
                                    else shakeWord()
                                } else if (word.length < 3) {
                                    shakeWord()
                                } else if (word.lowercase() in dictionary && word !in bonusWords) {
                                    coroutineScope.launch {
                                        animatedWord = word
                                        animationProgress.snapTo(0f)
                                        animationProgress.animateTo(
                                            1f,
                                            animationSpec = tween(durationMillis = 800)
                                        )
                                        val newTotal = levelDataStore.addBonusWord(word)
                                        achievementsManager.onProgressUpdated("bonus_hunter", newTotal)
                                        animatedWord = null
                                    }
                                } else if (word.lowercase() in dictionary && word in bonusWords) {
                                    val j = launch {
                                        val offsets = listOf(-16f, 12f, -8f, 6f, -3f, 0f)
                                        // also animate bonus button concurrently across the same offsets
                                        offsets.forEach { o ->
                                            bonusShakeAnim.animateTo(
                                                with(density) { o.dp.toPx() },
                                                animationSpec = tween(60)
                                            )
                                        }
                                    }
                                    shakeWord()
                                    j.join()
                                } else {
                                    shakeWord()
                                }
                            },
                            onWordBoxPositioned = { wordBoxOffset = it },
                            onLetterPositioned = { id, offset ->
                                if (letterChooserPositions[id] != offset) {
                                    letterChooserPositions =
                                        letterChooserPositions + (id to offset)
                                }
                            },
                            wordShakeTranslation = wordShakeAnim.value
                        )
                    }
                }
            }

            FilledIconButton(
                onClick = { showBonusWordsDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .padding(bottom = 32.dp)
                    .onGloballyPositioned { bonusButtonOffset = it.localToRoot(Offset.Zero) }
                    .graphicsLayer {
                        translationX = bonusShakeAnim.value
                    },
                enabled = bonusWords.isNotEmpty()
            ) {
                Icon(painterResource(R.drawable.outline_book_2_24), null)
            }

            if (showBonusWordsDialog) {
                BonusWordsDialog(bonusWords = bonusWords, dictionary) {
                    showBonusWordsDialog = false
                }
            }

            wordWithDefinition?.let { (word, definition) ->
                DefinitionDialog(word, definition) {
                    wordWithDefinition = null
                }
            }

            animatedWord?.let { word ->
                val progress = animationProgress.value
                val currentOffset = lerp(wordBoxOffset, bonusButtonOffset, progress)
                val alpha = 1f - progress
                val scale = 1f - (progress * 0.5f)

                Text(
                    text = word,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    modifier = Modifier
                        .offset { IntOffset(currentOffset.x.toInt(), currentOffset.y.toInt()) }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            alpha = alpha
                        )
                )
            }
            val (size, fontSize) = Pair(35.dp * scale, 18.sp * scale)
            animatedLetters.forEach { letter ->
                val progress = letter.progress.value
                val offset = lerp(letter.startOffset, letter.endOffset, progress)

                SurfaceText(Modifier.offset { IntOffset(offset.x.toInt(), offset.y.toInt()) },
                    RoundedCornerShape(4.dp * scale),
                    colorScheme.primaryContainer, letter.char.toString(),
                    Modifier, FontWeight.Bold, fontSize, size)
            }
        }
    }
}

@Composable
fun DefinitionDialog(word: String, definition: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = word.replaceFirstChar { it.uppercase() }) },
        text = { Text(text = definition.joinToString("\n\n")) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
fun BonusWordsDialog(bonusWords: Set<String>, dictionary: Dictionary, onDismiss: () -> Unit) {
    var definitionDialog by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.bonus_words)) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(bonusWords.toList().sorted()) { word ->
                    Text(
                        text = word,
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .fillMaxWidth()
                            .clickable {
                                definitionDialog = Pair(word, dictionary.getDefinition(word))
                            })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.close))
            }
        }
    )
    definitionDialog?.let { (w, d) ->
        DefinitionDialog(word = w, definition = d) {
            definitionDialog = null
        }
    }
}

@Composable
fun SurfaceText(modifier: Modifier, surfaceShape: Shape, surfaceColor: Color, text: String, textModifier: Modifier, fontWeight: FontWeight? = null, fontSize: TextUnit = TextUnit.Unspecified, surfaceSize: Dp?) {
    val modifier2 = if(surfaceSize != null) modifier.size(surfaceSize) else modifier
    Surface(modifier2, surfaceShape, surfaceColor) {
        Box(if(surfaceSize == null) Modifier else Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, textModifier, fontWeight = fontWeight, fontSize = fontSize)
        }
    }
}

@Composable
fun CrosswordBoard(
    foundWords: Set<String>,
    crosswordData: CrosswordData,
    wordToAnimate: String?,
    onCellPositioned: (position: Pair<Int, Int>, offset: Offset) -> Unit,
    onCellClicked: (row: Int, col: Int) -> Unit,
    scaleUpdated: (Float) -> Unit
) {
    val allCharPositions = mutableMapOf<Pair<Int, Int>, Char>()
    crosswordData.letterPositions.forEach { (word, positions) ->
        if (word in foundWords && word != wordToAnimate) {
            word.forEachIndexed { index, char ->
                allCharPositions[positions[index]] = char
            }
        }
    }
    val (size, fontSize) = Pair(35.dp, 18.sp)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clip(RectangleShape)
    ) {
        val numRows = crosswordData.gridStructure.size
        val numCols = if (numRows > 0) crosswordData.gridStructure[0].length else 0

        val boardWidth = 37.dp * numCols
        val boardHeight = 37.dp * numRows

        val initialScale = remember(crosswordData, maxWidth, maxHeight) {
            val scaleX = if (boardWidth.value > 0) maxWidth / boardWidth else 1f
            val scaleY = if (boardHeight.value > 0) maxHeight / boardHeight else 1f
            minOf(scaleX, scaleY, 1f)
        }

        var scale by remember(crosswordData) { mutableFloatStateOf(initialScale) }
        var offset by remember(crosswordData) { mutableStateOf(Offset.Zero) }

        // Ensure parent knows the current scale, especially the initial one
        LaunchedEffect(scale) {
            scaleUpdated(scale)
        }

        val state = rememberTransformableState { zoomChange, offsetChange, _ ->
            scale *= zoomChange
            offset += offsetChange
            scaleUpdated(scale)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = state)
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center)
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ) // 3. Apply transformations
            ) {
                crosswordData.gridStructure.forEachIndexed { y, rowString ->
                    Row {
                        rowString.forEachIndexed { x, char ->
                            if (char != '.') {
                                val letter = allCharPositions[Pair(y, x)]
                                SurfaceText(
                                    Modifier.padding(1.dp)
                                        .onGloballyPositioned {
                                            onCellPositioned(Pair(y, x), it.localToRoot(Offset.Zero))
                                        }.clickable(enabled = letter != null) {
                                            onCellClicked(y, x)
                                        },
                                    RoundedCornerShape(4.dp),
                                    if (letter != null) colorScheme.primaryContainer else colorScheme.secondaryContainer,
                                    letter?.toString() ?: " ", Modifier, FontWeight.Bold, fontSize, size
                                )
                            } else {
                                Box(Modifier.padding(1.dp).size(size))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LetterChooser(
    letters: List<ChooserLetter>,
    onShuffle: () -> Unit,
    onWordSubmitted: suspend CoroutineScope.(String, List<Int>) -> Unit,
    onWordBoxPositioned: (Offset) -> Unit,
    onLetterPositioned: (id: Int, offset: Offset) -> Unit,
    wordShakeTranslation: Float
) {
    val coroutineScope = rememberCoroutineScope()

    var selectedLettersIndices by remember(letters) { mutableStateOf(listOf<Int>()) }
    val formedWord = selectedLettersIndices.map { letters[it].char }.joinToString("")
    var dragStartOffset by remember(letters) { mutableStateOf(Offset.Zero) }
    var currentDragPosition by remember(letters) { mutableStateOf<Offset?>(null) }

    val density = LocalDensity.current
    val letterCircleRadius = with(density) { 35.dp.toPx() }
    val boxSizePx = with(density) { 250.dp.toPx() }
    val boxCenter = Offset(boxSizePx / 2, boxSizePx / 2)

    val angleStep = 2 * Math.PI / letters.size.toDouble()
    val radius = 85.dp
    val radiusPx = with(density) { radius.toPx() }
    val letterCenters = remember(letters, boxCenter, radiusPx) {
        List(letters.size) { index ->
            val angle = angleStep * index - (Math.PI / 2)
            boxCenter + Offset(cos(angle).toFloat() * radiusPx, sin(angle).toFloat() * radiusPx)
        }
    }

    fun getLetterAtArc(position: Offset): Int {
        val relative = position - boxCenter

        val angle = atan2(relative.y, relative.x)
        var normalizedAngle = angle + Math.PI / 2
        while (normalizedAngle < 0) normalizedAngle += 2 * Math.PI
        while (normalizedAngle >= 2 * Math.PI) normalizedAngle -= 2 * Math.PI

        return (normalizedAngle / angleStep).roundToInt() % letters.size
    }

    fun getLetterAtCircle(position: Offset): Int? {
        for (i in letterCenters.indices) {
            val center = letterCenters[i]
            if (distance(position, center) <= letterCircleRadius) {
                return i
            }
        }
        return null
    }

    // wordShakeTranslation is provided from parent (WordGameScreen) and driven there

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SurfaceText(
            Modifier
                .padding(bottom = 20.dp)
                .graphicsLayer(
                    alpha = if (selectedLettersIndices.isNotEmpty()) 1f else 0f,
                    translationX = wordShakeTranslation
                ),
            RoundedCornerShape(8.dp), colorScheme.primaryContainer,
            formedWord.ifEmpty { " " },
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .onGloballyPositioned { onWordBoxPositioned(it.localToRoot(Offset.Zero)) },
            FontWeight.Bold,
            32.sp,
            null
        )

        Row(verticalAlignment = Alignment.Top) {
            Spacer(modifier = Modifier.width(72.dp))
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .onGloballyPositioned {
                        dragStartOffset = it.localToRoot(Offset.Zero)
                    }
                    .pointerInput(letters) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                currentDragPosition = startOffset
                                selectedLettersIndices = listOf(getLetterAtArc(startOffset))
                            },
                            onDrag = { change, _ ->
                                currentDragPosition = change.position
                                getLetterAtCircle(change.position)?.let { idx ->
                                    if (idx !in selectedLettersIndices) {
                                        selectedLettersIndices = selectedLettersIndices + idx
                                    } else if (selectedLettersIndices.size > 1 && idx == selectedLettersIndices[selectedLettersIndices.size - 2]) {
                                        selectedLettersIndices = selectedLettersIndices.dropLast(1)
                                    }
                                }
                            },
                            onDragEnd = {
                                coroutineScope.launch {
                                    if (selectedLettersIndices.isNotEmpty()) {
                                        onWordSubmitted(
                                            selectedLettersIndices.map { letters[it].char }.joinToString(""),
                                            selectedLettersIndices.map { letters[it].id }
                                        )
                                    }
                                    selectedLettersIndices = emptyList()
                                    currentDragPosition = null
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val primaryColor = colorScheme.primary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (selectedLettersIndices.size > 1) {
                        for (i in 0 until selectedLettersIndices.size - 1) {
                            val startLetter = selectedLettersIndices[i]
                            val endLetter = selectedLettersIndices[i + 1]
                            val startCenter = letterCenters[startLetter]
                            val endCenter = letterCenters[endLetter]
                            drawLine(
                                color = primaryColor,
                                start = startCenter,
                                end = endCenter,
                                strokeWidth = 10f,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                    val lastLetter = selectedLettersIndices.lastOrNull()
                    if (lastLetter != null && currentDragPosition != null) {
                        val lastCenter = letterCenters[lastLetter]
                        drawLine(
                            color = primaryColor,
                            start = lastCenter,
                            end = currentDragPosition!!,
                            strokeWidth = 10f,
                            cap = StrokeCap.Round
                        )
                    }
                }
                Surface(
                    Modifier.fillMaxSize(0.9f),
                    CircleShape,
                    colorScheme.secondaryContainer.copy(alpha = 0.5f)
                ) {}

                letters.forEachIndexed { index, chooserLetter ->
                    key(chooserLetter.id) {
                        val angle = angleStep * index - (Math.PI / 2)
                        val targetX = (cos(angle) * radius.value).dp
                        val targetY = (sin(angle) * radius.value).dp

                        val x by animateDpAsState(targetX, label = "x")
                        val y by animateDpAsState(targetY, label = "y")

                        SurfaceText(Modifier
                            .align(Alignment.Center)
                            .offset(x, y)
                            .onGloballyPositioned { coordinates ->
                                onLetterPositioned(chooserLetter.id, coordinates.localToRoot(Offset.Zero))
                            },
                            CircleShape,
                            if (index in selectedLettersIndices) colorScheme.primary else colorScheme.secondary,
                            chooserLetter.char.toString(),
                            Modifier.padding(1.dp),
                            FontWeight.Bold,
                            42.sp,
                            70.dp
                        )
                    }
                }
            }
            FilledIconButton(onClick = onShuffle) {
                Icon(painterResource(R.drawable.ic_shuffle), contentDescription = "Shuffle")
            }
        }
    }
}

private fun distance(offset1: Offset, offset2: Offset): Float {
    return sqrt((offset1.x - offset2.x).pow(2) + (offset1.y - offset2.y).pow(2))
}
