package com.vayunmathur.games.chess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.network.LichessTimeControl
import com.vayunmathur.games.chess.platform.OnlineAuth
import com.vayunmathur.games.chess.platform.OnlineChessUiState
import com.vayunmathur.games.chess.platform.OnlineChessViewModel
import com.vayunmathur.games.chess.platform.OnlineConnection
import com.vayunmathur.games.chess.platform.OnlineLobby
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.IconClock
import com.vayunmathur.library.ui.IconFlag
import com.vayunmathur.library.ui.Messenger
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.util.rememberIsOnline
import com.vayunmathur.library.ui.R as UiR

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

/**
 * The online play tab: Lichess sign-in → lobby (quick seek + challenge) → live game.
 *
 * The game board reuses [BoardGrid], [MovesList] and [PawnPromotionDialog] from
 * [ChessGameScreens.kt] — no second board renderer. State comes from [OnlineChessViewModel]:
 * the lobby reads `auth` + `connection`, the game reads the `Playing` connection. Errors
 * surface on the shared snackbar via [rememberMessenger]; the offline banner comes from
 * [OfflineAware]. Preview entry: [ChessOnlineScreen] (stateless).
 */
@Composable
fun ChessOnlineScreens(viewModel: OnlineChessViewModel) {
    val state by viewModel.uiState.collectAsState()
    val online by rememberIsOnline()
    val messenger = rememberMessenger()
    OfflineAware {
        when (val connection = state.connection) {
            is OnlineConnection.Playing -> ChessOnlineGame(
                state = state,
                playing = connection,
                onSquareClick = viewModel::onSquareClick,
                onSquareDrop = viewModel::onSquareDrop,
                onPromote = viewModel::onPromote,
                onResignConfirm = viewModel::onResignConfirm,
                onAbortConfirm = viewModel::onAbortConfirm,
                onBackToLobby = viewModel::backToLobby,
            )
            else -> {
                LobbyMessages(state = state, viewModel = viewModel, messenger = messenger)
                ChessOnlineLobbyScreen(
                    state = state,
                    online = online,
                    onSignIn = viewModel::signIn,
                    onSignOut = viewModel::signOut,
                    onSelectTimeControl = viewModel::selectTimeControl,
                    onQuickSeek = viewModel::quickSeek,
                    onCancelSeek = viewModel::cancelSeek,
                    onChallengeUsernameChange = viewModel::onChallengeUsernameChange,
                    onChallenge = viewModel::challenge,
                )
            }
        }
    }
}

@Composable
private fun ChessOnlineLobbyScreen(
    state: OnlineChessUiState,
    online: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSelectTimeControl: (LichessTimeControl) -> Unit,
    onQuickSeek: () -> Unit,
    onCancelSeek: () -> Unit,
    onChallengeUsernameChange: (String) -> Unit,
    onChallenge: () -> Unit,
) {
    val seeking = state.connection as? OnlineConnection.Seeking
    val challenging = state.connection as? OnlineConnection.Challenging

    AppScaffold(
        title = stringResource(R.string.tab_online),
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val auth = state.auth) {
                is OnlineAuth.SignedIn -> SignedInHeader(
                    username = auth.username,
                    onSignOut = onSignOut,
                )
                OnlineAuth.SigningIn -> SigningInRow()
                OnlineAuth.SignedOut -> SignInCta(
                    enabled = online,
                    onSignIn = onSignIn,
                )
            }
            if (state.auth is OnlineAuth.SignedIn) {
                TimeControlRow(
                    selected = state.selectedTimeControl,
                    enabled = online && seeking == null && challenging == null,
                    onSelect = onSelectTimeControl,
                )
                SeekRow(
                    seeking = seeking,
                    enabled = online,
                    onSeek = onQuickSeek,
                    onCancel = onCancelSeek,
                )
                ChallengeRow(
                    username = state.challengeUsername,
                    challenging = challenging,
                    enabled = online,
                    onUsernameChange = onChallengeUsernameChange,
                    onChallenge = onChallenge,
                )
            }
        }
    }
}

/**
 * Posts lobby-scoped transient messages (challenge sent/declined, seek failures) to the shared
 * snackbar and clears them so a recomposition never reposts.
 */
@Composable
private fun LobbyMessages(
    state: OnlineChessUiState,
    viewModel: OnlineChessViewModel,
    messenger: Messenger,
) {
    val matchmakingFailed = stringResource(R.string.online_matchmaking_failed)
    val challengeSentFmt = stringResource(R.string.online_challenge_sent)
    val challengeDeclined = stringResource(R.string.online_challenge_declined)
    val authErrorText = state.authError?.let { viewModel.authErrorText(it) }
    val lobbyError = state.lobbyError
    val lobbyNotice = state.lobbyNotice
    LaunchedEffect(lobbyError, lobbyNotice, authErrorText) {
        val text = when {
            authErrorText != null -> authErrorText
            lobbyError != null -> matchmakingFailed
            lobbyNotice is OnlineLobby.Notice.ChallengeSent ->
                challengeSentFmt.format(lobbyNotice.username)
            lobbyNotice is OnlineLobby.Notice.ChallengeDeclined -> challengeDeclined
            else -> null
        }
        if (text != null) {
            messenger.show(text)
            viewModel.dismissLobbyMessage()
        }
    }
}

@Composable
private fun SignedInHeader(username: String, onSignOut: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.online_signed_in_as, username),
            fontWeight = FontWeight.Bold,
        )
        OutlinedButton(onClick = onSignOut) {
            Text(stringResource(R.string.online_sign_out))
        }
    }
}

@Composable
private fun SigningInRow() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.online_signing_in))
    }
}

@Composable
private fun SignInCta(enabled: Boolean, onSignIn: () -> Unit) {
    Button(onClick = onSignIn, enabled = enabled) {
        Text(stringResource(R.string.online_sign_in))
    }
}

@Composable
private fun TimeControlRow(
    selected: LichessTimeControl,
    enabled: Boolean,
    onSelect: (LichessTimeControl) -> Unit,
) {
    val labels = listOf(
        stringResource(R.string.online_tc_1_0) to LichessTimeControl.BULLET,
        stringResource(R.string.online_tc_3_2) to LichessTimeControl.BLITZ_3_2,
        stringResource(R.string.online_tc_5_3) to LichessTimeControl.RAPID_5_3,
        stringResource(R.string.online_tc_10_0) to LichessTimeControl.RAPID_10_0,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { (label, control) ->
            if (control == selected) {
                Button(onClick = { onSelect(control) }, enabled = enabled) {
                    Text(label)
                }
            } else {
                OutlinedButton(onClick = { onSelect(control) }, enabled = enabled) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun SeekRow(
    seeking: OnlineConnection.Seeking?,
    enabled: Boolean,
    onSeek: () -> Unit,
    onCancel: () -> Unit,
) {
    if (seeking != null) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.online_seeking, seeking.timeControl.label))
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(UiR.string.cancel))
            }
        }
    } else {
        Button(onClick = onSeek, enabled = enabled) {
            Text(stringResource(R.string.online_quick_seek))
        }
    }
}

@Composable
private fun ChallengeRow(
    username: String,
    challenging: OnlineConnection.Challenging?,
    enabled: Boolean,
    onUsernameChange: (String) -> Unit,
    onChallenge: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            enabled = challenging == null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            label = { Text(stringResource(R.string.online_challenge_username)) },
        )
        if (challenging != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.online_challenge_sent, challenging.username))
            }
        } else {
            Button(onClick = onChallenge, enabled = enabled && username.isNotBlank()) {
                Text(stringResource(R.string.online_challenge))
            }
        }
    }
}

@Composable
private fun ChessOnlineGame(
    state: OnlineChessUiState,
    playing: OnlineConnection.Playing,
    onSquareClick: (com.vayunmathur.games.chess.data.Position) -> Unit,
    onSquareDrop: (com.vayunmathur.games.chess.data.Position, com.vayunmathur.games.chess.data.Position) -> Unit,
    onPromote: (com.vayunmathur.games.chess.data.PieceType) -> Unit,
    onResignConfirm: () -> Unit,
    onAbortConfirm: () -> Unit,
    onBackToLobby: () -> Unit,
) {
    var confirmResign by remember { mutableStateOf(false) }
    var confirmAbort by remember { mutableStateOf(false) }

    if (playing.pendingPromotion != null) {
        PawnPromotionDialog(playing.localColor, onPromote = onPromote)
    }
    if (confirmResign) {
        ConfirmDialog(
            title = stringResource(R.string.online_resign_title),
            confirmLabel = stringResource(R.string.online_resign_confirm),
            onConfirm = onResignConfirm,
            onDismiss = { confirmResign = false },
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
        )
    }
    if (confirmAbort) {
        ConfirmDialog(
            title = stringResource(R.string.online_abort_title),
            confirmLabel = stringResource(R.string.online_abort_confirm),
            onConfirm = onAbortConfirm,
            onDismiss = { confirmAbort = false },
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
        )
    }

    AppScaffold(
        title = playing.opponentName?.let { stringResource(R.string.online_opponent, it) }
            ?: stringResource(R.string.tab_online),
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            val compact = maxWidth < maxHeight
            if (compact) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OnlineGameContent(
                        state = state,
                        playing = playing,
                        onSquareClick = onSquareClick,
                        onSquareDrop = onSquareDrop,
                        onResign = { confirmResign = true },
                        onAbort = { confirmAbort = true },
                        onBackToLobby = onBackToLobby,
                    )
                }
            } else {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        OnlineGameContent(
                            state = state,
                            playing = playing,
                            onSquareClick = onSquareClick,
                        onSquareDrop = onSquareDrop,
                            onResign = { confirmResign = true },
                            onAbort = { confirmAbort = true },
                            onBackToLobby = onBackToLobby,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineGameContent(
    state: OnlineChessUiState,
    playing: OnlineConnection.Playing,
    onSquareClick: (com.vayunmathur.games.chess.data.Position) -> Unit,
    onSquareDrop: (com.vayunmathur.games.chess.data.Position, com.vayunmathur.games.chess.data.Position) -> Unit,
    onResign: () -> Unit,
    onAbort: () -> Unit,
    onBackToLobby: () -> Unit,
) {
    if (playing.reconnecting) {
        Text(
            stringResource(R.string.online_reconnecting),
            fontWeight = FontWeight.Bold,
        )
    }
    ClockRow(playing = playing)
    BoardGrid(
        board = playing.board,
        selectedPiece = playing.selectedPiece,
        isFlipped = state.isBoardFlipped,
        turn = playing.turn,
        onSquareClick = onSquareClick,
        onSquareDrop = onSquareDrop,
        showLastMove = true,
    )
    MovesList(moves = playing.board.moves, turn = playing.turn)
    playing.resultText?.let {
        Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Button(onClick = onBackToLobby) {
            Text(stringResource(R.string.online_back_to_lobby))
        }
    } ?: GameEndBar(
        canAbort = playing.canAbort,
        onResign = onResign,
        onAbort = onAbort,
    )
}

/** Display-only clocks (v1): no local ticking, values come from the last `gameState`. */
@Composable
private fun ClockRow(playing: OnlineConnection.Playing) {
    val white = playing.whiteClockMs
    val black = playing.blackClockMs
    if (white == null && black == null) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClockCell(
            label = stringResource(R.string.color_white),
            millis = white,
            active = playing.turn == PieceColor.WHITE,
        )
        IconClock()
        ClockCell(
            label = stringResource(R.string.color_black),
            millis = black,
            active = playing.turn == PieceColor.BLACK,
        )
    }
}

@Composable
private fun ClockCell(label: String, millis: Long?, active: Boolean) {
    Text(
        "$label ${millis?.let(::formatClock) ?: "--:--"}",
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
    )
}

/** `m:ss`, prefixing `0:` urgency under 10 s. `null`/negative never reach here (row hides). */
private fun formatClock(millis: Long): String {
    val totalSeconds = millis / MILLIS_PER_SECOND
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun GameEndBar(canAbort: Boolean, onResign: () -> Unit, onAbort: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canAbort) {
            OutlinedButton(onClick = onAbort) {
                Text(stringResource(R.string.online_abort_confirm))
            }
        }
        OutlinedButton(onClick = onResign) {
            IconFlag()
            Text(stringResource(R.string.online_resign_confirm))
        }
    }
}

/**
 * Stateless online screen for previews: lobby vs game is driven by [OnlineChessUiState]
 * (same shape the ViewModel exposes) with no-op actions. No ViewModel, no network.
 */
@Composable
fun ChessOnlineScreen(state: OnlineChessUiState) {
    val online = true
    OfflineAware {
        when (val connection = state.connection) {
            is OnlineConnection.Playing -> ChessOnlineGame(
                state = state,
                playing = connection,
                onSquareClick = {},
                onSquareDrop = { _, _ -> },
                onPromote = {},
                onResignConfirm = {},
                onAbortConfirm = {},
                onBackToLobby = {},
            )
            else -> ChessOnlineLobbyScreen(
                state = state,
                online = online,
                onSignIn = {},
                onSignOut = {},
                onSelectTimeControl = {},
                onQuickSeek = {},
                onCancelSeek = {},
                onChallengeUsernameChange = {},
                onChallenge = {},
            )
        }
    }
}

