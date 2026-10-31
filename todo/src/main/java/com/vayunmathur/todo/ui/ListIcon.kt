package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFlight
import com.vayunmathur.library.ui.IconHealth
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconLightbulb
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconMenuBook
import com.vayunmathur.library.ui.IconReceipt
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconWork
import com.vayunmathur.library.ui.LocalContentColor

/** Icon keys a list can carry; stored as the key so they sync as plain text. */
val LIST_ICON_KEYS = listOf("list", "work", "home", "cart", "star", "heart", "book", "idea", "travel", "health", "bills")

@Composable
fun ListIcon(key: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    when (key) {
        "work" -> IconWork(modifier, tint)
        "home" -> IconHome(modifier, tint)
        "cart" -> IconShoppingCart(modifier, tint)
        "star" -> IconStar(modifier, tint)
        "heart" -> IconFavorite(modifier, tint)
        "book" -> IconMenuBook(modifier, tint)
        "idea" -> IconLightbulb(modifier, tint)
        "travel" -> IconFlight(modifier, tint)
        "health" -> IconHealth(modifier, tint)
        "bills" -> IconReceipt(modifier, tint)
        else -> IconList(modifier, tint)
    }
}
