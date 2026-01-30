package com.vayunmathur.games.wordmaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                WordMakerGameLoader()
            }
        }
    }
}

@Composable
fun WordMakerGameLoader() {
    val context = LocalContext.current
    val levelDataStore = remember { LevelDataStore(context) }
    val currentLevel by levelDataStore.currentLevel.collectAsState(initial = 1)
    var crosswordData by remember { mutableStateOf<CrosswordData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val dictionary by remember { mutableStateOf(Dictionary()) }
    val coroutineScope = rememberCoroutineScope { Dispatchers.IO }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            dictionary.init(context)
        }
    }

    LaunchedEffect(currentLevel) { // Use currentLevel as key
        try {
            crosswordData = CrosswordData.fromAsset(context, "levels/$currentLevel.txt")
            if (crosswordData == null) {
                error = "Failed to parse level data."
            }
        } catch (e: Exception) {
            error = "Failed to load level data: ${e.message}"
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
                    dictionary
                )
            }

            else -> {
                CircularProgressIndicator()
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

@Composable
fun WordGameScreen(
    crosswordData: CrosswordData,
    levelDataStore: LevelDataStore,
    currentLevel: Int,
    dictionary: Dictionary
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
        mutableStateOf<Map<Char, Offset>>(
            emptyMap()
        )
    }
    var wordToAnimate by remember(currentLevel) { mutableStateOf<String?>(null) }
    var animatedLetters by remember(currentLevel) { mutableStateOf<List<AnimatedLetter>>(emptyList()) }

    // Animatables for shaking (we'll animate them directly when submission fails)
    val wordShakeAnim = remember { Animatable(0f) }
    val bonusShakeAnim = remember { Animatable(0f) }

    var scale by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(wordToAnimate) {
        wordToAnimate?.let { word ->
            val letterPositions = crosswordData.letterPositions[word]
            if (letterPositions != null) {
                val letters = word.mapIndexed { index, char ->
                    val start = (letterChooserPositions[char] ?: Offset.Zero) - rootOffset
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

    Scaffold(Modifier.fillMaxSize()) { innerPadding ->
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
                Text(
                    text = "Level $currentLevel",
                    style = typography.headlineMedium,
                    modifier = Modifier.padding(16.dp)
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CrosswordBoard(
                        foundWords = foundWords,
                        crosswordData = crosswordData,
                        wordToAnimate = wordToAnimate,
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
                            Text("Next Level")
                        }
                    } else {
                        LetterChooser(
                            letters = crosswordData.lettersInChooser,
                            onWordSubmitted = { word ->

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
                                    if (word !in foundWords) wordToAnimate = word
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
                                        levelDataStore.addBonusWord(word)
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
                            onLetterPositioned = { char, offset ->
                                if (letterChooserPositions[char] != offset) {
                                    letterChooserPositions =
                                        letterChooserPositions + (char to offset)
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
                    RoundedCornerShape(4.dp),
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
                Text("Close")
            }
        }
    )
}

@Composable
fun BonusWordsDialog(bonusWords: Set<String>, dictionary: Dictionary, onDismiss: () -> Unit) {
    var definitionDialog by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Bonus Words") },
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
                Text("Close")
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
fun SurfaceText(surfaceModifier: Modifier, surfaceShape: Shape, surfaceColor: Color, text: String, textModifier: Modifier, fontWeight: FontWeight? = null, fontSize: TextUnit = TextUnit.Unspecified, surfaceSize: Dp?) {
    val modifier2 = if(surfaceSize != null) surfaceModifier.size(surfaceSize) else surfaceModifier
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

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale *= zoomChange
        offset += offsetChange
        scaleUpdated(scale)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RectangleShape)
            .transformable(state = state)
    ) {
        Column(
            modifier = Modifier
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

@Composable
fun LetterChooser(
    letters: List<Char>,
    onWordSubmitted: suspend CoroutineScope.(String) -> Unit,
    onWordBoxPositioned: (Offset) -> Unit,
    onLetterPositioned: (char: Char, offset: Offset) -> Unit,
    wordShakeTranslation: Float
) {
    val coroutineScope = rememberCoroutineScope()

    val angleStep = 2 * Math.PI / letters.size.toDouble()
    val letterCenters = remember(letters) {
        mutableStateListOf(
            *List(
                letters.size,
                { Offset.Zero }).toTypedArray()
        )
    }
    var selectedLettersIndices by remember(letters) { mutableStateOf(listOf<Int>()) }
    val formedWord = selectedLettersIndices.map { letters[it] }.joinToString("")
    var dragStartOffset by remember(letters) { mutableStateOf(Offset.Zero) }
    var currentDragPosition by remember(letters) { mutableStateOf<Offset?>(null) }

    val letterCircleRadius = with(LocalDensity.current) { 30.dp.toPx() }

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

        Box(
            modifier = Modifier
                .size(250.dp)
                .onGloballyPositioned {
                    dragStartOffset = it.localToRoot(Offset.Zero)
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            currentDragPosition = startOffset
                            letterCenters.forEachIndexed { idx, center ->
                                if (distance(startOffset, center) < letterCircleRadius) {
                                    if (idx !in selectedLettersIndices) {
                                        selectedLettersIndices += idx
                                    }
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            currentDragPosition = change.position
                            letterCenters.forEachIndexed { idx, center ->
                                if (distance(change.position, center) < letterCircleRadius) {
                                    if (idx !in selectedLettersIndices) {
                                        selectedLettersIndices += idx
                                    } else if (selectedLettersIndices.size > 1 && idx == selectedLettersIndices[selectedLettersIndices.size - 2]) {
                                        selectedLettersIndices = selectedLettersIndices.dropLast(1)
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            coroutineScope.launch {
                                if (selectedLettersIndices.isNotEmpty()) {
                                    onWordSubmitted(selectedLettersIndices.map{letters[it]}.joinToString(""))
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
            Surface(Modifier.fillMaxSize(0.9f), CircleShape, colorScheme.secondaryContainer.copy(alpha = 0.5f)){}

            val radius = 85.dp
            letters.forEachIndexed { index, letter ->
                val angle = angleStep * index - (Math.PI / 2)
                val x = (cos(angle) * radius.value).dp
                val y = (sin(angle) * radius.value).dp

                SurfaceText(Modifier
                    .align(Alignment.Center)
                    .offset(x, y)
                    .onGloballyPositioned { coordinates ->
                        val localOffset = coordinates.localToRoot(Offset.Zero) - dragStartOffset
                        val centerX = localOffset.x + letterCircleRadius
                        val centerY = localOffset.y + letterCircleRadius
                        letterCenters[index] = Offset(centerX, centerY)
                        onLetterPositioned(letter, coordinates.localToRoot(Offset.Zero))
                    }
                    , CircleShape,
                    if (index in selectedLettersIndices) colorScheme.primary else colorScheme.secondary,
                    letter.toString(), Modifier.padding(1.dp), FontWeight.Bold, 36.sp, 60.dp)
            }
        }
    }
}

private fun distance(offset1: Offset, offset2: Offset): Float {
    return sqrt((offset1.x - offset2.x).pow(2) + (offset1.y - offset2.y).pow(2))
}
