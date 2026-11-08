package com.vayunmathur.games.chess.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.chess.auth.LichessAuthBridge
import com.vayunmathur.games.chess.auth.LichessAuthManager
import com.vayunmathur.games.chess.auth.LichessTokenStore
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.opposite
import com.vayunmathur.games.chess.network.GameState
import com.vayunmathur.games.chess.network.LichessApi
import com.vayunmathur.games.chess.network.LichessAuthExpired
import com.vayunmathur.games.chess.network.LichessEvent
import com.vayunmathur.games.chess.network.LichessGameUpdate
import com.vayunmathur.games.chess.network.LichessTimeControl
import com.vayunmathur.games.chess.network.UciMove
import com.vayunmathur.games.chess.network.parseUci
import com.vayunmathur.games.chess.network.splitUciMoves
import com.vayunmathur.games.chess.network.toUciSquare
import com.vayunmathur.library.util.AppMessages
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Online (Lichess) chess session: sign-in, matchmaking, and one live game at a time.
 *
 * The move path mirrors `ChessViewModel.onSquareClick` — guard `turn == localColor` plus
 * `board.isValidMove`, show `PawnPromotionDialog` for last-rank pawn steps, then `POST` the UCI
 * (`e2e4`, `e7e8q`, `e1g1`) — but the board only advances on the server's next `gameState`: the
 * client replays the unseen UCI tail via `Board.movePiece` / `promotePawn` (which already cover
 * castling, en passant and promotion), so a rejected or reordered move can never fork the
 * position. The promotion choice is held in `pendingPromotion` without touching the board, so a
 * failed POST leaves nothing to roll back. Server `status` (resign/draw/abort/outoftime/…) wins
 * over local rules on conflict. Clocks are display-only for v1.
 */
class OnlineChessViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = LichessTokenStore(application)
    private val authManager = LichessAuthManager(application, tokenStore)
    private val api = LichessApi(tokenStore)

    private val _uiState = MutableStateFlow(OnlineChessUiState())
    val uiState: StateFlow<OnlineChessUiState> = _uiState.asStateFlow()

    private var eventJob: Job? = null
    private var gameJob: Job? = null
    private var redirectJob: Job? = null

    /** UCI tokens already applied to [OnlineChessUiState.board]; the tail past this is new. */
    private var appliedUciCount: Int = 0

    /** Game the stream is currently attached to; stale `gameStart`s for other ids are ignored. */
    private var attachedGameId: String? = null

    companion object {
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS = 30_000L
        private const val CHALLENGE_TIMEOUT_MS = 60_000L
    }

    init {
        viewModelScope.launch {
            val username = tokenStore.usernameNow()
            if (tokenStore.tokenNow() != null && username != null) {
                _uiState.update { it.copy(auth = OnlineAuth.SignedIn(username)) }
                startEventStream()
            }
        }
        redirectJob = viewModelScope.launch {
            LichessAuthBridge.redirects.collect { redirect ->
                val auth = _uiState.value.auth
                if (auth !is OnlineAuth.SigningIn) return@collect
                try {
                    val username = authManager.finishSignIn(redirect)
                    _uiState.update { it.copy(auth = OnlineAuth.SignedIn(username)) }
                    startEventStream()
                } catch (_: IllegalStateException) {
                    failAuth(OnlineAuth.Error.SignInFailed)
                } catch (_: IOException) {
                    failAuth(OnlineAuth.Error.SignInFailed)
                }
            }
        }
    }

    fun signIn() {
        if (_uiState.value.auth is OnlineAuth.SigningIn) return
        _uiState.update { it.copy(auth = OnlineAuth.SigningIn, authError = null) }
        authManager.startSignIn()
    }

    fun signOut() {
        stopStreams()
        viewModelScope.launch { tokenStore.clear() }
        attachedGameId = null
        appliedUciCount = 0
        _uiState.value = OnlineChessUiState()
    }

    private fun failAuth(error: OnlineAuth.Error) {
        _uiState.update { it.copy(auth = OnlineAuth.SignedOut, authError = error) }
    }

    private suspend fun requireReauth() {
        stopStreams()
        tokenStore.clear()
        attachedGameId = null
        _uiState.update {
            it.copy(connection = OnlineConnection.Idle, auth = OnlineAuth.SignedOut, authError = OnlineAuth.Error.Expired)
        }
        AppMessages.show(authErrorText(OnlineAuth.Error.Expired))
    }

    /** Localized text for auth errors ([OnlineAuth.Error] stays locale-free for previews/tests). */
    fun authErrorText(error: OnlineAuth.Error): String {
        val context = getApplication<Application>()
        return when (error) {
            OnlineAuth.Error.SignInFailed -> context.getString(
                com.vayunmathur.games.chess.R.string.online_sign_in_failed
            )
            OnlineAuth.Error.Expired -> context.getString(
                com.vayunmathur.games.chess.R.string.online_auth_expired
            )
        }
    }

    // -- matchmaking ----------------------------------------------------------

    fun selectTimeControl(timeControl: LichessTimeControl) {
        _uiState.update { it.copy(selectedTimeControl = timeControl) }
    }

    fun onChallengeUsernameChange(username: String) {
        _uiState.update { it.copy(challengeUsername = username) }
    }

    fun quickSeek() {
        val connection = _uiState.value.connection
        if (connection is OnlineConnection.Seeking || connection is OnlineConnection.Playing) return
        _uiState.update {
            it.copy(connection = OnlineConnection.Seeking(it.selectedTimeControl), lobbyError = null)
        }
        viewModelScope.launch {
            try {
                api.seek(_uiState.value.selectedTimeControl)
                // Pairing arrives as gameStart on the event stream; the Seeking UI persists until
                // then. Seeks live server-side — leaving the tab does not cancel them.
            } catch (e: LichessAuthExpired) {
                requireReauth()
            } catch (_: IOException) {
                _uiState.update {
                    it.copy(connection = OnlineConnection.Idle, lobbyError = OnlineLobby.Error.SeekFailed)
                }
            }
        }
    }

    fun cancelSeek() {
        _uiState.update { it.copy(connection = OnlineConnection.Idle) }
    }

    fun challenge() {
        val username = _uiState.value.challengeUsername.trim()
        if (username.isEmpty()) return
        val connection = _uiState.value.connection
        if (connection is OnlineConnection.Seeking || connection is OnlineConnection.Playing) return
        _uiState.update {
            it.copy(connection = OnlineConnection.Challenging(username), lobbyError = null)
        }
        viewModelScope.launch {
            try {
                api.challenge(username, _uiState.value.selectedTimeControl)
                _uiState.update { it.copy(lobbyNotice = OnlineLobby.Notice.ChallengeSent(username)) }
                delay(CHALLENGE_TIMEOUT_MS)
                if (_uiState.value.connection is OnlineConnection.Challenging) {
                    _uiState.update { it.copy(connection = OnlineConnection.Idle) }
                }
            } catch (e: LichessAuthExpired) {
                requireReauth()
            } catch (_: IOException) {
                _uiState.update {
                    it.copy(connection = OnlineConnection.Idle, lobbyError = OnlineLobby.Error.ChallengeFailed)
                }
            }
        }
    }

    fun dismissLobbyMessage() {
        _uiState.update { it.copy(lobbyError = null, lobbyNotice = null, authError = null) }
    }

    // -- streams ----------------------------------------------------------------

    private fun startEventStream() {
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            try {
                reconnectLoop { api.streamEvents(::onEvent) }
            } catch (_: LichessAuthExpired) {
                requireReauth()
            }
        }
    }

    private fun attachGame(gameId: String) {
        if (attachedGameId == gameId && gameJob?.isActive == true) return
        attachedGameId = gameId
        gameJob?.cancel()
        gameJob = viewModelScope.launch {
            try {
                reconnectLoop(
                    onDrop = {
                        if (_uiState.value.connection is OnlineConnection.Playing) {
                            _uiState.update { state ->
                                val playing = state.connection as OnlineConnection.Playing
                                state.copy(connection = playing.copy(reconnecting = true))
                            }
                        }
                    }
                ) {
                    api.streamGame(gameId, ::onGameUpdate)
                }
            } catch (_: LichessAuthExpired) {
                requireReauth()
            }
        }
    }

    /**
     * Holds [block]'s stream open across drops: both a transport failure and a clean EOF (the
     * server closed an ostensibly infinite push stream) back off and re-attach, because a sync
     * gap heals by resuming from the next `gameState`. Only job cancellation (sign-out,
     * game end, ViewModel clear) and [LichessAuthExpired] escape: re-auth restarts the streams
     * from a clean state. [onDrop] marks the UI reconnecting before each retry so a mid-game
     * drop shows the banner.
     */
    private suspend fun reconnectLoop(onDrop: suspend () -> Unit = {}, block: suspend () -> Unit) {
        var backoff = RECONNECT_BASE_MS
        while (true) {
            try {
                block()
            } catch (e: LichessAuthExpired) {
                throw e
            } catch (_: IOException) {
                // Fall through to the shared backoff below.
            }
            onDrop()
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
        }
    }

    private suspend fun onEvent(event: LichessEvent) {
        when (event) {
            is LichessEvent.GameStart -> {
                _uiState.update {
                    it.copy(
                        connection = OnlineConnection.Playing(
                            gameId = event.gameId,
                            localColor = event.color,
                            opponentName = event.opponent,
                            board = Board.initialState,
                            turn = PieceColor.WHITE,
                            selectedPiece = null,
                            resultText = null,
                        ),
                        lobbyError = null,
                        lobbyNotice = null,
                    )
                }
                appliedUciCount = 0
                attachGame(event.gameId)
            }
            is LichessEvent.GameFinish -> {
                if (attachedGameId == event.gameId) {
                    gameJob?.cancel()
                    attachedGameId = null
                }
            }
            is LichessEvent.ChallengeDeclined -> {
                if (_uiState.value.connection is OnlineConnection.Challenging) {
                    _uiState.update {
                        it.copy(
                            connection = OnlineConnection.Idle,
                            lobbyNotice = OnlineLobby.Notice.ChallengeDeclined,
                        )
                    }
                }
            }
            is LichessEvent.Challenge -> {
                // v1 scope is outgoing challenges only; incoming ones stay on lichess.org.
                viewModelScope.launch {
                    try {
                        api.declineChallenge(event.id)
                    } catch (_: IOException) {
                    }
                }
            }
            LichessEvent.Ignored -> Unit
        }
    }

    private suspend fun onGameUpdate(update: LichessGameUpdate) {
        when (update) {
            is LichessGameUpdate.Full -> {
                val myId = tokenStore.userIdNow() ?: return
                val localColor = update.game.myColor(myId)
                val whiteName = update.game.white.name.ifEmpty { null }
                val blackName = update.game.black.name.ifEmpty { null }
                val opponent = when (localColor) {
                    PieceColor.WHITE -> blackName
                    PieceColor.BLACK -> whiteName
                }
                _uiState.update {
                    val playing = (it.connection as? OnlineConnection.Playing)
                    it.copy(
                        connection = OnlineConnection.Playing(
                            gameId = update.game.id,
                            localColor = localColor,
                            opponentName = opponent ?: playing?.opponentName,
                            board = Board.initialState,
                            turn = PieceColor.WHITE,
                            whiteClockMs = update.game.state.wtime.takeIf { t -> t > 0 },
                            blackClockMs = update.game.state.btime.takeIf { t -> t > 0 },
                            selectedPiece = null,
                            resultText = null,
                            reconnecting = false,
                        )
                    )
                }
                appliedUciCount = 0
                applyGameState(update.game.state)
            }
            is LichessGameUpdate.State -> applyGameState(update.state)
            LichessGameUpdate.Ignored -> Unit
        }
    }

    /**
     * Applies the authoritative [GameState]: replays the unseen UCI tail onto the board and
     * adopts the server turn, clocks, and terminal status. Unknown UCI tokens are skipped, never
     * applied partially — a short suffix mismatch resyncs on the next state.
     */
    private fun applyGameState(state: GameState) {
        val playing = _uiState.value.connection as? OnlineConnection.Playing ?: return
        val tokens = splitUciMoves(state.moves)
        var board = playing.board
        var applied = appliedUciCount
        var selectedPiece: Position? = playing.selectedPiece
        while (applied < tokens.size) {
            val uci = try {
                parseUci(tokens[applied])
            } catch (_: IllegalArgumentException) {
                applied++
                continue
            }
            if (!board.isValidMove(uci.from, uci.to)) break
            board = board.movePiece(uci.from, uci.to, uci.promotion)
            val promotionSquare = board.promotionPosition
            if (promotionSquare != null && uci.promotion != null) {
                board = board.promotePawn(promotionSquare, uci.promotion)
            }
            applied++
            selectedPiece = null
        }
        appliedUciCount = applied
        val resultText = resultTextFor(state, playing.localColor)
        _uiState.update {
            it.copy(
                connection = playing.copy(
                    board = board,
                    turn = turnForMoveCount(applied),
                    whiteClockMs = state.wtime.takeIf { t -> t > 0 },
                    blackClockMs = state.btime.takeIf { t -> t > 0 },
                    selectedPiece = selectedPiece,
                    resultText = resultText,
                    reconnecting = false,
                )
            )
        }
    }

    /** Side to move after [moveCount] half-moves from the initial position: White starts. */
    private fun turnForMoveCount(moveCount: Int): PieceColor =
        if (moveCount % 2 == 0) PieceColor.WHITE else PieceColor.BLACK

    private fun resultTextFor(state: GameState, localColor: PieceColor): String? {
        if (!state.isTerminal) return null
        val context = getApplication<Application>()
        val iWon = state.winner?.let {
            (it == "white") == (localColor == PieceColor.WHITE)
        }
        return when (state.status) {
            "draw", "stalemate" -> context.getString(
                com.vayunmathur.games.chess.R.string.online_result_draw
            )
            // `winner` is the color that won: present → the other side resigned; absent → I resigned
            // (my resign POST raced the state, or the server reports it without a winner).
            "resign" ->
                if (iWon == false) context.getString(
                    com.vayunmathur.games.chess.R.string.online_result_opponent_resigned
                ) else context.getString(
                    com.vayunmathur.games.chess.R.string.online_result_resigned
                )
            "aborted" -> context.getString(com.vayunmathur.games.chess.R.string.online_result_draw)
            else -> {
                if (iWon == true) context.getString(com.vayunmathur.games.chess.R.string.online_result_win)
                else context.getString(com.vayunmathur.games.chess.R.string.online_result_loss)
            }
        }
    }

    // -- moves ------------------------------------------------------------------

    fun onSquareClick(position: Position) {
        val playing = _uiState.value.connection as? OnlineConnection.Playing ?: return
        if (playing.resultText != null) return
        if (playing.turn != playing.localColor) return
        // The promotion dialog owns input while a choice is pending; the dialog has no dismiss
        // affordance (mirrors offline PawnPromotionDialog), so squares stay dead until then.
        if (playing.pendingPromotion != null) return
        val board = playing.board
        val selectedPiece = playing.selectedPiece
        if (selectedPiece == null) {
            val piece = board.pieces[position.row][position.col]
            if (piece != null && piece.color == playing.turn) {
                _uiState.update { it.copy(connection = playing.copy(selectedPiece = position)) }
            }
            return
        }
        if (board.isValidMove(selectedPiece, position)) {
            val moving = board.pieces[selectedPiece.row][selectedPiece.col] ?: return
            val preview = board.movePiece(selectedPiece, position)
            if (preview.promotionPosition != null && moving.type == PieceType.PAWN) {
                // Choice only — the board keeps the confirmed position until the server echoes
                // the promotion UCI, so a failed POST leaves nothing to roll back.
                _uiState.update {
                    it.copy(
                        connection = playing.copy(
                            pendingPromotion = UciMove(selectedPiece, position),
                            selectedPiece = null,
                        )
                    )
                }
                return
            }
            sendLocalMove(UciMove(selectedPiece, position))
        } else {
            _uiState.update { it.copy(connection = playing.copy(selectedPiece = null)) }
        }
    }

    fun onPromote(pieceType: PieceType) {
        val playing = _uiState.value.connection as? OnlineConnection.Playing ?: return
        val pending = playing.pendingPromotion ?: return
        sendLocalMove(pending.copy(promotion = pieceType))
    }

    /**
     * POSTs the UCI; the board advances when the server echoes it in the next `gameState`.
     * Sending twice (double-tap) is harmless — Lichess rejects the duplicate and the tail replay
     * skips what is already applied. A 401 drops to re-auth; other failures surface a message
     * while the position stays exactly as the server last confirmed it.
     */
    private fun sendLocalMove(uci: UciMove) {
        val playing = _uiState.value.connection as? OnlineConnection.Playing ?: return
        if (playing.turn != playing.localColor || playing.resultText != null) return
        viewModelScope.launch {
            try {
                api.sendMove(playing.gameId, uci.toString())
                _uiState.update {
                    val current = it.connection as? OnlineConnection.Playing ?: return@update it
                    it.copy(
                        connection = current.copy(selectedPiece = null, pendingPromotion = null)
                    )
                }
            } catch (e: LichessAuthExpired) {
                requireReauth()
            } catch (_: IOException) {
                AppMessages.show(moveFailedText())
            }
        }
    }

    private fun moveFailedText(): String =
        getApplication<Application>().getString(com.vayunmathur.games.chess.R.string.online_move_failed)

    // -- resign / abort -----------------------------------------------------------

    fun onResignConfirm() {
        val playing = _uiState.value.connection as? OnlineConnection.Playing ?: return
        viewModelScope.launch {
            try {
                api.resign(playing.gameId)
            } catch (e: LichessAuthExpired) {
                requireReauth()
            } catch (_: IOException) {
                AppMessages.show(moveFailedText())
            }
        }
    }

    fun onAbortConfirm() {
        val playing = _uiState.value.connection as? OnlineConnection.Playing ?: return
        viewModelScope.launch {
            try {
                api.abort(playing.gameId)
            } catch (e: LichessAuthExpired) {
                requireReauth()
            } catch (_: IOException) {
                AppMessages.show(moveFailedText())
            }
        }
    }

    /** Terminal result arrived via `gameState.status` or `gameFinish` — back to the lobby. */
    fun backToLobby() {
        gameJob?.cancel()
        attachedGameId = null
        appliedUciCount = 0
        _uiState.update { it.copy(connection = OnlineConnection.Idle) }
    }

    private fun stopStreams() {
        eventJob?.cancel()
        eventJob = null
        gameJob?.cancel()
        gameJob = null
    }

    override fun onCleared() {
        redirectJob?.cancel()
        stopStreams()
    }
}

/** Whether the lobby shows the sign-in CTA, the signing-in wait, or the signed-in identity. */
sealed interface OnlineAuth {
    data object SignedOut : OnlineAuth
    data object SigningIn : OnlineAuth
    data class SignedIn(val username: String) : OnlineAuth

    /** Sign-in failure vs token expiry; text resolves via `OnlineChessViewModel.authErrorText`. */
    sealed interface Error {
        data object SignInFailed : Error
        data object Expired : Error
    }
}

/** Matchmaking / game session state; `Playing` doubles as the game screen's state holder. */
sealed interface OnlineConnection {
    data object Idle : OnlineConnection
    data class Seeking(val timeControl: LichessTimeControl) : OnlineConnection
    data class Challenging(val username: String) : OnlineConnection
    data class Playing(
        val gameId: String,
        val localColor: PieceColor,
        val opponentName: String? = null,
        val board: Board = Board.initialState,
        val turn: PieceColor = PieceColor.WHITE,
        val whiteClockMs: Long? = null,
        val blackClockMs: Long? = null,
        val selectedPiece: Position? = null,
        /** Local promotion choice awaiting confirm; the preview board shows the pawn stepped. */
        val pendingPromotion: UciMove? = null,
        val resultText: String? = null,
        val reconnecting: Boolean = false,
    ) : OnlineConnection {
        /** True while the first moves can still be aborted instead of resigned. */
        val canAbort: Boolean get() = board.moves.isEmpty()
    }
}

/** Lobby-scoped transient messages; cleared by [OnlineChessViewModel.dismissLobbyMessage]. */
object OnlineLobby {
    sealed interface Error {
        data object SeekFailed : Error
        data object ChallengeFailed : Error
    }

    sealed interface Notice {
        data class ChallengeSent(val username: String) : Notice
        data object ChallengeDeclined : Notice
    }
}

data class OnlineChessUiState(
    val auth: OnlineAuth = OnlineAuth.SignedOut,
    val authError: OnlineAuth.Error? = null,
    val connection: OnlineConnection = OnlineConnection.Idle,
    val selectedTimeControl: LichessTimeControl = LichessTimeControl.BLITZ_3_2,
    val challengeUsername: String = "",
    val lobbyError: OnlineLobby.Error? = null,
    val lobbyNotice: OnlineLobby.Notice? = null,
) {
    /** Clocks shown beside the board for the side to move; null hides the row. */
    val turn: PieceColor get() = (connection as? OnlineConnection.Playing)?.turn ?: PieceColor.WHITE

    /** BoardGrid flips when the local player holds Black, mirroring offline VsAI-as-Black. */
    val isBoardFlipped: Boolean get() {
        val playing = connection as? OnlineConnection.Playing ?: return false
        return playing.localColor == PieceColor.BLACK
    }
}
