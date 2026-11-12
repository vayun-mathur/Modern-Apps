package com.vayunmathur.photos.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #912: which way a favourite toggle goes for a selection. */
class FavoriteRuleTest {

    @Test
    fun noneFavoriteFavorites() {
        assertTrue(FavoriteRule.targetValue(listOf(false, false)))
    }

    @Test
    fun mixedSelectionFavoritesAll() {
        assertTrue(FavoriteRule.targetValue(listOf(true, false, true)))
    }

    @Test
    fun allFavoriteUnfavorites() {
        assertFalse(FavoriteRule.targetValue(listOf(true, true)))
        assertFalse(FavoriteRule.targetValue(listOf(true)))
    }

    @Test
    fun emptySelectionFavorites() {
        assertTrue(FavoriteRule.targetValue(emptyList()))
    }
}
