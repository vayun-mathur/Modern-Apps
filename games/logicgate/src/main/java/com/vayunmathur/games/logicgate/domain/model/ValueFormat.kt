package com.vayunmathur.games.logicgate.domain.model

/** Signal text: 1-bit as 0/1, buses as zero-padded upper-case hex (one digit per nibble). */
fun formatValue(value: Int, width: Int): String =
    if (width == 1) value.toString() else value.toString(HEX).uppercase().padStart((width + 3) / 4, '0')

private const val HEX = 16
