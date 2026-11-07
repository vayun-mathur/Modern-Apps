package com.vayunmathur.games.unblockjam.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelDataTest {

    @Test
    fun packFromJsonParsesLevelsAndFlipsYCoordinates() {
        val pack = packFromJson(
            """
            {"name":"Mini","levels":[
              {"id":"a","w":6,"h":6,"e":{"x":6,"y":2},"c":3,"b":[
                {"x":0,"y":3,"w":2,"h":1},
                {"x":2,"y":0,"w":1,"h":3,"fixed":true},
                {"w":2,"h":2}
              ]}
            ]}
            """.trimIndent()
        )
        assertEquals("Mini", pack.name)
        assertNull(pack.colorScheme)
        assertEquals(1, pack.levels.size)
        val level = pack.levels.single()
        assertEquals("a", level.id)
        assertEquals(Dimension(6, 6), level.dimension)
        // exit y in file is bottom-origin: 6 - 2 - 1 = 3
        assertEquals(Coord(6, 3), level.exit)
        assertEquals(3, level.optimalMoves)
        assertNull(level.lastMovedBlockIndex)
        // main block y: 6 - 3 - 1 = 2
        assertEquals(Block(Coord(0, 2), Dimension(2, 1), false), level.blocks[0])
        assertEquals(Block(Coord(2, 3), Dimension(1, 3), true), level.blocks[1])
        // missing x/y default to 0 then flipped: y = 6 - 0 - 2 = 4
        assertEquals(Block(Coord(0, 4), Dimension(2, 2), false), level.blocks[2])
    }

    @Test
    fun packFromJsonParsesColorsWithDefaultsAndPrefixes() {
        val pack = packFromJson(
            """
            {"name":"Colored","colors":{"primary":"#112233","secondary":"0xFF445566","primaryContainer":"nothex"},
             "levels":[{"id":"x","w":4,"h":4,"e":{"x":4,"y":1},"c":1,"b":[{"x":0,"y":2,"w":2,"h":1}]}]}
            """.trimIndent()
        )
        val colors = pack.colorScheme!!
        assertEquals(0x112233L, colors.primary)
        assertEquals(0xFF445566L, colors.secondary)
        // invalid hex falls back to the default orange container
        assertEquals(0xFFFFA500L, colors.primaryContainer)
        assertEquals(0xFF8B7E6AL, colors.tertiary)
        assertEquals(0xFFFFFFFFL, colors.onPrimary)
        assertEquals(0xFFFF0000L, colors.error)
    }

    @Test
    fun wallAndSizePackIndicesMatchFilePrefixes() {
        // PACK_FILES order: original, walls_1..3, size_7..9
        assertEquals(listOf(1, 2, 3), LevelPack.WALL_PACK_INDICES)
        assertEquals(listOf(4, 5, 6), LevelPack.SIZE_PACK_INDICES)
    }

    @Test
    fun dataClassDefaultsAndCopy() {
        val scheme = PackColorScheme(1, 2, 3, 4, 5, 6, 7)
        assertEquals(0xFFFFFFFFL, scheme.onPrimary)
        assertEquals(0xFFFFFFFFL, scheme.onSurface)
        assertEquals(0xFFFF0000L, scheme.error)
        assertEquals(scheme, scheme.copy())
        assertFalse(scheme == scheme.copy(primary = 9))

        val level = LevelData("id", Dimension(6, 6), Coord(6, 2), emptyList(), 5)
        assertNull(level.lastMovedBlockIndex)
        assertEquals(2, level.copy(lastMovedBlockIndex = 2).lastMovedBlockIndex)
        assertTrue(Coord(1, 2) == Coord(1, 2))
        assertFalse(Coord(1, 2) == Coord(2, 1))
        val pack = LevelPack("p", listOf(level))
        assertNull(pack.colorScheme)
        assertEquals(listOf(level), pack.copy(name = "q").levels)
    }
}
