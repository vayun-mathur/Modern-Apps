package com.vayunmathur.games.hub.domain

/** One first-party Modern Apps game the hub knows about before it has ever reported in. */
data class KnownGame(
    val gameId: String,
    val packageName: String,
    val displayName: String,
)

/**
 * The single source for turning a game id into something a person can read, and the catalog
 * of first-party games the hub can offer to install.
 *
 * Ids are what games report over `:sdk:games` (`GameHubComposeHook("unblockjam", …)`); they
 * are internal and must never be shown as-is. The names here match the ones the shared
 * `GameHubCompose` registration sends, and the packages match the manifest `<queries>` list,
 * so a game that has not registered yet (or whose rows outlived its uninstall) still reads
 * the same as one that has.
 */
object KnownGames {

    val all: List<KnownGame> = listOf(
        known("arrows", "Arrows"),
        known("chess", "Chess"),
        known("kart", "Kart"),
        known("logicgate", "Logic Gates"),
        known("minesweeper", "Minesweeper"),
        known("nonogram", "Nonogram"),
        known("pipes", "Pipes"),
        known("solitaire", "Solitaire"),
        known("sudoku", "Sudoku"),
        known("unblockjam", "Unblock Jam"),
        known("voxels", "Voxels"),
        known("wordmaker", "Wordmaker"),
    )

    private val byId: Map<String, KnownGame> = all.associateBy { it.gameId }

    fun find(gameId: String): KnownGame? = byId[gameId]

    /**
     * The name to show for [gameId]: the name the game registered with ([registeredName], from
     * its hub row) when there is one, else the catalog name, else the id made readable
     * ("my_game" -> "My game") so an unknown third-party id still never shows raw.
     */
    fun displayName(gameId: String, registeredName: String? = null): String =
        registeredName?.takeIf { it.isNotBlank() }
            ?: byId[gameId]?.displayName
            ?: readable(gameId)

    private fun readable(gameId: String): String =
        gameId.replace('_', ' ').replace('-', ' ').trim()
            .replaceFirstChar { it.uppercaseChar() }
            .ifEmpty { gameId }

    private fun known(gameId: String, displayName: String) =
        KnownGame(gameId, "$PACKAGE_PREFIX$gameId", displayName)

    private const val PACKAGE_PREFIX = "com.vayunmathur.games."
}
