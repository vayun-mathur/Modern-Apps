package com.vayunmathur.games.chess.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.stockfish.Stockfish

object StockfishEngine {
    val inputChannel = Channel<String>(Channel.UNLIMITED)
    val outputChannel = Channel<String>(Channel.UNLIMITED)
    private var engineStarted = false

    enum class Difficulty(val depth: Int, val skill: Int) {
        BEGINNER(8, 0),
        INTERMEDIATE(8, 5),
        ADVANCED(8, 12),
        GRANDMASTER(8, 20)
    }

    var difficulty: Difficulty = Difficulty.BEGINNER

    suspend fun nextMove(board: Board) {
        inputChannel.send("position fen ${board.toFen()}")
        inputChannel.send("setoption name Skill Level value ${difficulty.skill}")
        inputChannel.send("go depth ${difficulty.depth}")
    }

    fun start(context: Context) {
        if (engineStarted) return
        engineStarted = true

        Stockfish.init { line ->
            Log.d("StockfishEngine", "Stockfish output: $line")
            outputChannel.trySend(line)
        }

        val evalFileSpec = nnueEvalFileSpec(context, "nn-71d6d32cb962.nnue")

        CoroutineScope(Dispatchers.IO).launch {
            for (cmd in inputChannel) {
                Log.d("StockfishEngine", "Stockfish Input: $cmd")
                Stockfish.sendCommand(cmd)
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            inputChannel.send("uci")
            inputChannel.send("setoption name EvalFile value $evalFileSpec")
            inputChannel.send("isready")
        }
    }

    /**
     * Points Stockfish at the NNUE stored uncompressed inside the APK, read in
     * place via a file descriptor instead of being copied to internal storage.
     * Returns an "fd:<fd>:<offset>:<length>" spec understood by the engine's
     * EvalFile loader. The fd is detached (ownership handed to native, which
     * closes it after loading). Requires `noCompress += "nnue"` so the asset is
     * page-aligned and openable as a descriptor.
     */
    private fun nnueEvalFileSpec(context: Context, fileName: String): String {
        val afd = context.assets.openFd(fileName)
        val offset = afd.startOffset
        val length = afd.length
        val fd = afd.parcelFileDescriptor.detachFd()
        afd.close()
        return "fd:$fd:$offset:$length"
    }
}
