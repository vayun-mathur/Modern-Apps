package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.data.WheelSpacing
import com.vayunmathur.games.wordmaker.ui.ChooserLetter
import com.vayunmathur.library.ui.animatedDp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LetterChooser(
    letters: List<ChooserLetter>,
    tapToSpell: Boolean,
    wheelSpacing: WheelSpacing,
    onShuffle: () -> Unit,
    onWordSubmitted: suspend CoroutineScope.(String, List<Int>) -> Unit,
    onWordBoxPositioned: (Offset) -> Unit,
    onLetterPositioned: (id: Int, offset: Offset) -> Unit,
    wordShakeTranslation: Float = 0f
) {
    val coroutineScope = rememberCoroutineScope()

    // The drag handler below lives in a pointerInput keyed on `letters`, so its coroutine keeps
    // running the closure it started with. Calling the captured parameter directly would submit
    // against the found/bonus word sets as they were when the board loaded, which is why an
    // already-accepted word could be entered again until the app was restarted (#543).
    val submitWord by rememberUpdatedState(onWordSubmitted)

    var selectedLettersIndices by remember(letters) { mutableStateOf(listOf<Int>()) }
    val formedWord = selectedLettersIndices.map { letters[it].char }.joinToString("")
    var currentDragPosition by remember(letters) { mutableStateOf<Offset?>(null) }

    // The word box keeps showing a submitted word while it is judged (a rejected word shakes
    // for 240-360 ms), but the selection itself is cleared the moment the word is submitted.
    // Clearing it only after the verdict wiped the first letters and the trail of a swipe the
    // player had already started in the meantime.
    var judgedWord by remember(letters) { mutableStateOf<String?>(null) }
    var submitSerial by remember(letters) { mutableIntStateOf(0) }

    fun submitSelection() {
        if (selectedLettersIndices.isEmpty()) return
        val word = selectedLettersIndices.map { letters[it].char }.joinToString("")
        val ids = selectedLettersIndices.map { letters[it].id }
        selectedLettersIndices = emptyList()
        currentDragPosition = null
        val serial = submitSerial + 1
        submitSerial = serial
        judgedWord = word
        coroutineScope.launch {
            try {
                submitWord(word, ids)
            } finally {
                // A newer submission owns the word box now; leave it alone.
                if (submitSerial == serial) judgedWord = null
            }
        }
    }

    // While a swipe is in progress the box shows it; otherwise it shows the word being judged.
    val isSpelling = selectedLettersIndices.isNotEmpty()
    val shownWord = if (isSpelling) formedWord else judgedWord.orEmpty()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SurfaceText(
            Modifier
                .padding(bottom = 20.dp)
                .graphicsLayer(
                    alpha = if (shownWord.isNotEmpty()) 1f else 0f,
                    // The shake belongs to the judged word, never to a new swipe.
                    translationX = if (isSpelling) 0f else wordShakeTranslation
                ),
            RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primaryContainer,
            shownWord.ifEmpty { " " },
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .onGloballyPositioned { onWordBoxPositioned(it.localToRoot(Offset.Zero)) },
            FontWeight.Bold,
            32.sp,
            null
        )

        // The wheel takes the room left between two equal side columns, so the shuffle button
        // (right column) always stays on screen and the wheel stays centred. Wider spacings
        // used to push the row past the screen edge on narrow phones, dropping shuffle.
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.width(SIDE_COLUMN_WIDTH),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (tapToSpell && selectedLettersIndices.isNotEmpty()) {
                    FilledIconButton(onClick = {
                        selectedLettersIndices = selectedLettersIndices.dropLast(1)
                    }) {
                        Icon(painterResource(R.drawable.backspace_24px), contentDescription = stringResource(R.string.cd_backspace))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledIconButton(onClick = { submitSelection() }) {
                        Icon(painterResource(R.drawable.keyboard_return_24px), contentDescription = stringResource(R.string.cd_submit))
                    }
                }
            }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .sizeIn(maxWidth = wheelSpacing.boxSize, maxHeight = wheelSpacing.boxSize)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                // Everything below is laid out from the box's measured size, so the tiles, the
                // trail and the touch targets agree even when the box got less than it asked for.
                val density = LocalDensity.current
                val widthPx = constraints.maxWidth.toFloat()
                val heightPx = constraints.maxHeight.toFloat()
                val tilePx = WheelGeometry.tileSize(
                    side = minOf(widthPx, heightPx),
                    defaultSide = with(density) { WheelSpacing.DEFAULT.boxSize.toPx() },
                    nominalTile = with(density) { LETTER_TILE.toPx() },
                )
                val geometry = WheelGeometry.fit(
                    widthPx, heightPx, with(density) { wheelSpacing.ringRadius.toPx() }, tilePx / 2, letters.size
                )
                val tileSize = with(density) { tilePx.toDp() }
                val radiusDp = with(density) { geometry.ringRadius.toDp() }
                // The gesture coroutine outlives recompositions; it reads the geometry through
                // updated state, so a size or spacing change mid-puzzle is hit-tested at the new
                // positions without restarting a swipe in progress.
                val currentGeometry by rememberUpdatedState(geometry)

                // Where the finger went down. The drag detector only reports the point where the
                // touch slop was crossed, which can already be past the first letter.
                var dragDown by remember { mutableStateOf<Offset?>(null) }

                // The tiles' parent handles the swipe, so it sees every move after the tiles
                // (which only take taps, in tap-to-spell mode).
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                dragDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
                            }
                        }
                        .pointerInput(letters) {
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    currentDragPosition = startOffset
                                    // Only a touch on a tile selects it. A drag that starts in the
                                    // middle of the wheel or in a corner used to pick a letter by
                                    // angle alone; now it selects nothing until it reaches a tile.
                                    val geometry = currentGeometry
                                    selectedLettersIndices = listOfNotNull(
                                        dragDown?.let(geometry::letterAt),
                                        geometry.letterAt(startOffset),
                                    ).distinct()
                                },
                                onDrag = { change, _ ->
                                    currentDragPosition = change.position
                                    currentGeometry.letterAt(change.position)?.let { idx ->
                                        if (idx !in selectedLettersIndices) {
                                            selectedLettersIndices = selectedLettersIndices + idx
                                        } else if (selectedLettersIndices.size > 1 && idx == selectedLettersIndices[selectedLettersIndices.size - 2]) {
                                            selectedLettersIndices = selectedLettersIndices.dropLast(1)
                                        }
                                    }
                                },
                                onDragEnd = {
                                    currentDragPosition = null
                                    submitSelection()
                                },
                                onDragCancel = {
                                    currentDragPosition = null
                                    selectedLettersIndices = emptyList()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        Modifier.fillMaxSize(0.9f),
                        CircleShape,
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ) {}

                    letters.forEachIndexed { index, chooserLetter ->
                        key(chooserLetter.id) {
                            val angle = WheelGeometry.angleOf(index, letters.size)
                            val x = animatedDp((cos(angle) * radiusDp.value).dp)
                            val y = animatedDp((sin(angle) * radiusDp.value).dp)

                            // absoluteOffset, not offset: offset {} mirrors x in RTL while the
                            // trail and hit-testing work in unmirrored pointer coordinates, so an
                            // RTL swipe selected the letter opposite the one under the finger.
                            SurfaceText(Modifier
                                .align(Alignment.Center)
                                .absoluteOffset { IntOffset(x.roundToPx(), y.roundToPx()) }
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
                                tileSize
                            )
                        }
                    }

                    // The trail goes on top of the tiles: drawn first, it vanished under them and
                    // under the wheel backdrop, so the live segment to the finger was hidden.
                    val primaryColor = MaterialTheme.colorScheme.primary
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val centers = geometry.centers
                        selectedLettersIndices.zipWithNext { a, b ->
                            drawLine(primaryColor, centers[a], centers[b], 10f, cap = StrokeCap.Round)
                        }
                        val lastLetter = selectedLettersIndices.lastOrNull()
                        val dragPosition = currentDragPosition
                        if (lastLetter != null && dragPosition != null) {
                            drawLine(primaryColor, centers[lastLetter], dragPosition, 10f, cap = StrokeCap.Round)
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.width(SIDE_COLUMN_WIDTH),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FilledIconButton(onClick = onShuffle) {
                    Icon(painterResource(R.drawable.ic_shuffle), contentDescription = stringResource(R.string.cd_shuffle))
                }
            }
        }
    }
}

/** Width of each column beside the wheel: tap-to-spell buttons on one side, shuffle on the other. */
private val SIDE_COLUMN_WIDTH = 56.dp

/** A letter tile at full size. */
private val LETTER_TILE = 70.dp
