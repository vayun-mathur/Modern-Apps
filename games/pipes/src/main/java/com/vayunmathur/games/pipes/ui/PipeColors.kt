package com.vayunmathur.games.pipes.ui

import androidx.compose.ui.graphics.Color

/**
 * Flow colours by colour index. Levels use up to 17 colours, so the order matters: after
 * the classic red, green, blue, yellow, each next colour is the one farthest (CIEDE2000)
 * from all colours before it and from the dark cell background. The closest pair within
 * the first 17 is now about 12 dE00 apart (Bright Teal/Cyan); the previous order put
 * Blue/Light Blue (7.6) and Green/Lime (10) in the same board.
 */
val PIPE_COLORS = listOf(
    Color(0xFFE53935),   // Red
    Color(0xFF43A047),   // Green
    Color(0xFF1E88E5),   // Blue
    Color(0xFFFDD835),   // Yellow
    Color(0xFF00E5FF),   // Bright Cyan
    Color(0xFFFF8F00),   // Orange
    Color(0xFFAB47BC),   // Magenta
    Color(0xFF26A69A),   // Bright Teal
    Color(0xFF9E9D24),   // Olive
    Color(0xFFEC407A),   // Pink
    Color(0xFF3949AB),   // Indigo
    Color(0xFF8D6E63),   // Maroon
    Color(0xFF546E7A),   // Blue Grey
    Color(0xFFFFB300),   // Amber
    Color(0xFFC0CA33),   // Chartreuse
    Color(0xFF00ACC1),   // Cyan
    Color(0xFF6D4C41),   // Brown
    Color(0xFFF4511E),   // Deep Orange
    Color(0xFF7CB342),   // Lime
    Color(0xFF00897B),   // Teal
    Color(0xFF8E24AA),   // Purple
    Color(0xFF5E35B1),   // Deep Purple
    Color(0xFFD81B60),   // Raspberry
    Color(0xFF039BE5),   // Light Blue
)
