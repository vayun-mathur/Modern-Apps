package com.vayunmathur.games.wordmaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilledIconButton
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.games.wordmaker.ui.SettingsPage
import com.vayunmathur.games.wordmaker.util.AppBackupAgent
import com.vayunmathur.games.wordmaker.util.WordMakerViewModel
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.GameHubSessionHook
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Game : Route
    @Serializable
    data object GameCenter : Route
    @Serializable
    data object Settings : Route
}

data class ChooserLetter(val id: Int, val char: Char)

class MainActivity : ComponentActivity() {
    private val viewModel: WordMakerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                val backStack = rememberNavBackStack<Route>(Route.Game)
                GameHubSessionHook("wordmaker", "Wordmaker")
                MainNavigation(backStack) {
                    entry<Route.Game> {
                        WordMakerGameLoader(backStack, viewModel)
                    }
                    entry<Route.GameCenter> {
                        val gcManager = rememberAchievementsManager(viewModel.levelDataStore)
                        if (gcManager != null) {
                            GameCenterScreen(
                                backupAgent = AppBackupAgent(),
                                manager = gcManager,
                                onBack = { backStack.pop() }
                            )
                        }
                    }
                    entry<Route.Settings> {
                        SettingsPage(viewModel = viewModel, onBack = { backStack.pop() })
                    }
                }
            }
        }
    }
}

@Composable
fun rememberAchievementsManager(levelDataStore: LevelDataStore): AchievementsManager? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = androidx.compose.runtime.produceState<AchievementsManager?>(initialValue = null, context, levelDataStore) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }
            com.vayunmathur.games.wordmaker.util.WordMakerAchievementsManager(context, json, levelDataStore)
        }
    }
    return state.value
}

@Composable
fun WordMakerGameLoader(backStack: NavBackStack<Route>, viewModel: WordMakerViewModel) {
    val currentLevel by viewModel.currentLevel.collectAsState()
    val crosswordData by viewModel.crosswordData.collectAsState()
    val error by viewModel.error.collectAsState()
    val gameMode by viewModel.gameMode.collectAsState()
    val competitiveActive by viewModel.competitiveActive.collectAsState()

    val achievementsManager = rememberAchievementsManager(viewModel.levelDataStore)
    if (achievementsManager == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val newAchievement by achievementsManager.newAchievement.collectAsState()

    LaunchedEffect(Unit) {
        achievementsManager.checkExistingAchievements()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            gameMode == GameMode.COMPETITIVE && !competitiveActive -> {
                CompetitiveLobbyScreen(
                    viewModel = viewModel,
                    onOpenGameCenter = { backStack.add(Route.GameCenter) },
                    onOpenSettings = { backStack.add(Route.Settings) }
                )
            }

            error != null -> {
                Text(text = error!!, color = MaterialTheme.colorScheme.error)
            }

            crosswordData != null -> {
                WordGameScreen(
                    crosswordData = crosswordData!!,
                    currentLevel = currentLevel,
                    viewModel = viewModel,
                    achievementsManager = achievementsManager,
                    onOpenGameCenter = { backStack.add(Route.GameCenter) },
                    onOpenSettings = { backStack.add(Route.Settings) }
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
    currentLevel: Int,
    viewModel: WordMakerViewModel,
    achievementsManager: AchievementsManager,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val foundWords by viewModel.foundWords.collectAsState()
    val bonusWords by viewModel.bonusWords.collectAsState()
    val tapToSpell by viewModel.tapToSpell.collectAsState()
    val revealedHints by viewModel.revealedHints.collectAsState()
    val hintCooldownEnd by viewModel.hintCooldownEnd.collectAsState()
    val gameMode by viewModel.gameMode.collectAsState()
    val competitiveScore by viewModel.competitiveScore.collectAsState()
    val competitiveLevelNumber by viewModel.competitiveLevelNumber.collectAsState()
    val competitiveDeadline by viewModel.competitiveDeadline.collectAsState()
    val isCompetitive = gameMode == GameMode.COMPETITIVE
    val levelKey = if (isCompetitive) "c$competitiveLevelNumber" else "n$currentLevel"
    var showBonusWordsDialog by remember(levelKey) { mutableStateOf(false) }
    var showHintDialog by remember(levelKey) { mutableStateOf(false) }
    var remainingCooldown by remember { mutableLongStateOf(0L) }
    var remainingTime by remember(levelKey) { mutableLongStateOf(0L) }
    var timedOut by remember(levelKey) { mutableStateOf(false) }
    val density = LocalDensity.current
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var wordWithDefinition by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    // Animation state
    val coroutineScope = rememberCoroutineScope()
    var animatedWord by remember(levelKey) { mutableStateOf<String?>(null) }
    val animationProgress = remember(levelKey) { Animatable(0f) }
    var wordBoxOffset by remember(levelKey) { mutableStateOf(Offset.Zero) }
    var bonusButtonOffset by remember(levelKey) { mutableStateOf(Offset.Zero) }
    var crosswordCellPositions by remember(levelKey) {
        mutableStateOf<Map<Pair<Int, Int>, Offset>>(
            emptyMap()
        )
    }
    var letterChooserPositions by remember(levelKey) {
        mutableStateOf<Map<Int, Offset>>(
            emptyMap()
        )
    }
    var wordToAnimate by remember(levelKey) { mutableStateOf<WordToAnimate?>(null) }
    var animatedLetters by remember(levelKey) { mutableStateOf<List<AnimatedLetter>>(emptyList()) }

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
            val letterPositions = crosswordData.letterPositions[word]?.firstOrNull()
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
                viewModel.addFoundWord(word)
                wordToAnimate = null
                animatedLetters = emptyList()
            }
        }
    }

    val isWon = crosswordData.winsWith(foundWords)

    LaunchedEffect(isWon) {
        if (!isWon) return@LaunchedEffect
        if (isCompetitive) {
            viewModel.onCompetitiveWin()
            return@LaunchedEffect
        }
        if (currentLevel == 1) achievementsManager.onAchievementUnlocked("level_1_done")
        if (currentLevel == 861) achievementsManager.onAchievementUnlocked("manual_levels_done")

        achievementsManager.onProgressUpdated("manual_levels_done", currentLevel)
        achievementsManager.onProgressUpdated("level_50", currentLevel)
        achievementsManager.onProgressUpdated("level_100", currentLevel)
        achievementsManager.onProgressUpdated("level_500", currentLevel)
    }

    LaunchedEffect(competitiveDeadline, isCompetitive, isWon, levelKey) {
        if (!isCompetitive || isWon || competitiveDeadline <= 0L) {
            if (competitiveDeadline <= 0L && !isWon) remainingTime = 0L
            return@LaunchedEffect
        }
        while (true) {
            remainingTime = (competitiveDeadline - System.currentTimeMillis()).coerceAtLeast(0)
            if (remainingTime <= 0L) break
            delay(200)
        }
        if (!isWon) {
            timedOut = true
            viewModel.onCompetitiveTimeout()
        }
    }

    LaunchedEffect(hintCooldownEnd) {
        while (true) {
            remainingCooldown = (hintCooldownEnd - System.currentTimeMillis()).coerceAtLeast(0)
            if (remainingCooldown <= 0) break
            delay(100)
        }
    }

    Scaffold(
        Modifier.fillMaxSize(),
        topBar = {
            WordMakerTopBar(
                gameMode = gameMode,
                onModeSelected = { viewModel.setGameMode(it) },
                onOpenGameCenter = onOpenGameCenter,
                onOpenSettings = onOpenSettings,
                levelNumber = currentLevel
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
                if (isCompetitive) {
                    CompetitiveStatusBar(
                        score = competitiveScore,
                        remainingTimeMs = remainingTime
                    )
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CrosswordBoard(
                        foundWords = foundWords,
                        revealedHints = revealedHints,
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
                                val definition = viewModel.getDefinition(word)
                                if (definition.isNotEmpty()) {
                                    wordWithDefinition = Pair(word, definition)
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
                    if (isWon && !isCompetitive) {
                        Button(onClick = { viewModel.saveLevel(currentLevel + 1) }) {
                            Text(stringResource(R.string.next_level))
                        }
                    } else if (isCompetitive && (isWon || timedOut)) {
                        // Level finished — the between-levels lobby (WordMakerGameLoader) takes over.
                    } else {
                        LetterChooser(
                            letters = shuffledLetters,
                            tapToSpell = tapToSpell,
                            onShuffle = {
                                var nextLetters = shuffledLetters.shuffled()
                                while (nextLetters == shuffledLetters && shuffledLetters.size > 1) {
                                    nextLetters = shuffledLetters.shuffled()
                                }
                                shuffledLetters = nextLetters
                            },
                            onWordSubmitted = { word, ids ->
                                suspend fun shakeAnim(anim: Animatable<Float, AnimationVector1D>, duration: Int = 40) {
                                    for (o in listOf(-16f, 12f, -8f, 6f, -3f, 0f)) {
                                        anim.animateTo(with(density) { o.dp.toPx() }, tween(duration))
                                    }
                                }

                                val isSolution = word in crosswordData.solutionWords
                                val isBonus = !isSolution && word.length >= 3 && viewModel.isInDictionary(word)

                                when {
                                    isSolution && word !in foundWords -> {
                                        wordToAnimate = WordToAnimate(word, ids)
                                        if (word.length >= 7) achievementsManager.onAchievementUnlocked("long_word")
                                    }
                                    isBonus && word !in bonusWords -> {
                                        coroutineScope.launch {
                                            animatedWord = word
                                            animationProgress.snapTo(0f)
                                            animationProgress.animateTo(1f, tween(800))
                                            val newTotal = viewModel.addBonusWord(word)
                                            achievementsManager.onProgressUpdated("bonus_hunter", newTotal)
                                            animatedWord = null
                                        }
                                    }
                                    isBonus && word in bonusWords -> {
                                        val j = launch { shakeAnim(bonusShakeAnim, 60) }
                                        shakeAnim(wordShakeAnim)
                                        j.join()
                                    }
                                    else -> shakeAnim(wordShakeAnim)
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

            if (!isWon && !isCompetitive) {
                val hintEnabled = remainingCooldown <= 0L
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .padding(bottom = 32.dp)
                ) {
                    FilledIconButton(
                        onClick = { showHintDialog = true },
                        enabled = hintEnabled
                    ) {
                        Icon(
                            painterResource(android.R.drawable.ic_menu_help),
                            contentDescription = "Hint",
                            modifier = Modifier.graphicsLayer { alpha = if (hintEnabled) 1f else 0.5f }
                        )
                    }
                    if (!hintEnabled) {
                        CircularProgressIndicator(
                            progress = { 1f - (remainingCooldown / 30_000f) },
                            modifier = Modifier.size(48.dp).align(Alignment.Center),
                            strokeWidth = 3.dp
                        )
                    }
                }
            }

            if (showHintDialog) {
                AlertDialog(
                    onDismissRequest = { showHintDialog = false },
                    title = { Text(stringResource(R.string.hint_confirmation)) },
                    confirmButton = {
                        Button(onClick = {
                            viewModel.revealHint(crosswordData, foundWords, revealedHints)
                            showHintDialog = false
                        }) {
                            Text(stringResource(R.string.yes))
                        }
                    },
                    dismissButton = {
                        Button(onClick = { showHintDialog = false }) {
                            Text(stringResource(R.string.no))
                        }
                    }
                )
            }

            if (showBonusWordsDialog) {
                BonusWordsDialog(bonusWords = bonusWords, getDefinition = viewModel::getDefinition) {
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
                    MaterialTheme.colorScheme.primary, letter.char.toString(),
                    Modifier, FontWeight.Bold, fontSize, size,
                    textColor = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
fun GameModeDropdown(selected: GameMode, onSelected: (GameMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                stringResource(
                    if (selected == GameMode.COMPETITIVE) R.string.mode_competitive else R.string.mode_casual
                ),
                fontWeight = FontWeight.Bold
            )
            Text("  ▾", fontWeight = FontWeight.Bold)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mode_casual)) },
                onClick = {
                    expanded = false
                    onSelected(GameMode.CASUAL)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mode_competitive)) },
                onClick = {
                    expanded = false
                    onSelected(GameMode.COMPETITIVE)
                }
            )
        }
    }
}

@Composable
fun DifficultyDropdown(selected: Difficulty, onSelected: (Difficulty) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun label(d: Difficulty) = when (d) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
    }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(label(selected)))
            Text(" ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Difficulty.values().forEach { d ->
                DropdownMenuItem(
                    text = { Text(stringResource(label(d))) },
                    onClick = {
                        expanded = false
                        onSelected(d)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordMakerTopBar(
    gameMode: GameMode,
    onModeSelected: (GameMode) -> Unit,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit,
    levelNumber: Int? = null
) {
        CenterAlignedTopAppBar(
            title = {
                if (gameMode == GameMode.CASUAL && levelNumber != null) {
                    Text(
                        text = stringResource(R.string.level_number, levelNumber),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            navigationIcon = {
                GameModeDropdown(selected = gameMode, onSelected = onModeSelected)
            },
            actions = {
                IconButton(onClick = onOpenGameCenter) {
                    Icon(painterResource(id = android.R.drawable.btn_star_big_on), "Achievements")
                }
                IconButton(onClick = onOpenSettings) {
                    IconSettings()
                }
            }
        )
}

/**
 * The between-levels screen for competitive mode: shows the score and last result, lets the player
 * pick the difficulty (which only applies from the next level), and starts the next level on demand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompetitiveLobbyScreen(
    viewModel: WordMakerViewModel,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val gameMode by viewModel.gameMode.collectAsState()
    val difficulty by viewModel.difficulty.collectAsState()
    val score by viewModel.competitiveScore.collectAsState()
    val result by viewModel.competitiveResult.collectAsState()

    Scaffold(
        topBar = {
            WordMakerTopBar(
                gameMode = gameMode,
                onModeSelected = { viewModel.setGameMode(it) },
                onOpenGameCenter = onOpenGameCenter,
                onOpenSettings = onOpenSettings
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                stringResource(R.string.competitive_score, score),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )

            result?.let {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = if (it.won) {
                        stringResource(R.string.competitive_solved, it.delta)
                    } else {
                        stringResource(R.string.competitive_timed_out, it.delta)
                    },
                    color = if (it.won) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            }

            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.competitive_difficulty), fontWeight = FontWeight.Bold)
            DifficultyDropdown(selected = difficulty, onSelected = { viewModel.setDifficulty(it) })
            Text(stringResource(R.string.competitive_time_limit, difficulty.timeLimitSeconds))

            Spacer(Modifier.height(32.dp))
            Button(onClick = { viewModel.loadNextCompetitiveLevel() }) {
                Text(
                    stringResource(
                        if (result == null) R.string.competitive_start else R.string.next_level
                    )
                )
            }
        }
    }
}

@Composable
fun CompetitiveStatusBar(
    score: Int,
    remainingTimeMs: Long
) {
    val totalSeconds = (remainingTimeMs / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val timeColor = if (totalSeconds <= 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.competitive_score, score))
        Spacer(Modifier.weight(1f))
        Text(
            text = "%d:%02d".format(minutes, seconds),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = timeColor
        )
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
fun BonusWordsDialog(
    bonusWords: Set<String>,
    getDefinition: (String) -> List<String>,
    onDismiss: () -> Unit
) {
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
                                definitionDialog = Pair(word, getDefinition(word))
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
fun SurfaceText(modifier: Modifier, surfaceShape: Shape, surfaceColor: Color, text: String, textModifier: Modifier, fontWeight: FontWeight? = null, fontSize: TextUnit = TextUnit.Unspecified, surfaceSize: Dp?, textColor: Color = Color.Unspecified) {
    val modifier2 = if(surfaceSize != null) modifier.size(surfaceSize) else modifier
    Surface(modifier2, surfaceShape, surfaceColor) {
        Box(if(surfaceSize == null) Modifier else Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, textModifier, color = textColor, fontWeight = fontWeight, fontSize = fontSize)
        }
    }
}

@Composable
fun CrosswordBoard(
    foundWords: Set<String>,
    revealedHints: Set<Pair<Int, Int>>,
    crosswordData: CrosswordData,
    wordToAnimate: String?,
    onCellPositioned: (position: Pair<Int, Int>, offset: Offset) -> Unit,
    onCellClicked: (row: Int, col: Int) -> Unit,
    scaleUpdated: (Float) -> Unit
) {
    val allCharPositions = mutableMapOf<Pair<Int, Int>, Char>()
    crosswordData.letterPositions.forEach { (word, occurrences) ->
        for (positions in occurrences) {
            val isFound = word in foundWords && word != wordToAnimate
            word.forEachIndexed { index, char ->
                val pos = positions[index]
                if (isFound || (pos in revealedHints && pos !in allCharPositions)) {
                    allCharPositions[pos] = char
                }
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
        var boxCenter by remember { mutableStateOf(Offset.Zero) }

        // Ensure parent knows the current scale, especially the initial one
        LaunchedEffect(scale) {
            scaleUpdated(scale)
        }

        val state = rememberTransformableState { centroid, zoomChange, offsetChange, _ ->
            scale *= zoomChange
            // Keep the board point under the gesture centroid fixed while zooming.
            // The graphicsLayer lives on the centered child (default centre
            // transformOrigin), so pivot relative to the container centre, then
            // apply the two-finger drag.
            val d = centroid - boxCenter
            offset = d - (d - offset) * zoomChange + offsetChange
            scaleUpdated(scale)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { boxCenter = Offset(it.size.width / 2f, it.size.height / 2f) }
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
                                    if (letter != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    letter?.toString() ?: " ",
                                    Modifier,
                                    FontWeight.Bold, fontSize, size,
                                    textColor = if (letter != null) MaterialTheme.colorScheme.onPrimary else Color.Unspecified
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
    tapToSpell: Boolean,
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

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SurfaceText(
            Modifier
                .padding(bottom = 20.dp)
                .graphicsLayer(
                    alpha = if (selectedLettersIndices.isNotEmpty()) 1f else 0f,
                    translationX = wordShakeTranslation
                ),
            RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primaryContainer,
            formedWord.ifEmpty { " " },
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .onGloballyPositioned { onWordBoxPositioned(it.localToRoot(Offset.Zero)) },
            FontWeight.Bold,
            32.sp,
            null
        )

        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (tapToSpell && selectedLettersIndices.isNotEmpty()) {
                    FilledIconButton(onClick = {
                        selectedLettersIndices = selectedLettersIndices.dropLast(1)
                    }) {
                        Icon(painterResource(R.drawable.backspace_24px), contentDescription = "Backspace")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledIconButton(onClick = {
                        if (selectedLettersIndices.isNotEmpty()) {
                            val word = selectedLettersIndices.map { letters[it].char }.joinToString("")
                            val ids = selectedLettersIndices.map { letters[it].id }
                            coroutineScope.launch {
                                onWordSubmitted(word, ids)
                                selectedLettersIndices = emptyList()
                            }
                        }
                    }) {
                        Icon(painterResource(R.drawable.keyboard_return_24px), contentDescription = "Submit")
                    }
                }
            }
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
                val primaryColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxSize()) {
                    selectedLettersIndices.zipWithNext { a, b ->
                        drawLine(primaryColor, letterCenters[a], letterCenters[b], 10f, cap = StrokeCap.Round)
                    }
                    val lastLetter = selectedLettersIndices.lastOrNull()
                    if (lastLetter != null && currentDragPosition != null) {
                        drawLine(primaryColor, letterCenters[lastLetter], currentDragPosition!!, 10f, cap = StrokeCap.Round)
                    }
                }
                Surface(
                    Modifier.fillMaxSize(0.9f),
                    CircleShape,
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
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
                            }
                            .then(if (tapToSpell) Modifier.clickable {
                                if (selectedLettersIndices.lastOrNull() == index) {
                                    selectedLettersIndices = selectedLettersIndices.dropLast(1)
                                } else if (index !in selectedLettersIndices) {
                                    selectedLettersIndices = selectedLettersIndices + index
                                }
                            } else Modifier),
                            CircleShape,
                            if (index in selectedLettersIndices) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
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

private fun distance(a: Offset, b: Offset): Float = (a - b).getDistance()
