package com.vayunmathur.photos.domain

/**
 * What a favourite toggle does to a set of items (#912), shared by the viewer's
 * heart and the grid's multi-select action so both follow one rule.
 */
object FavoriteRule {

    /**
     * Most URIs sent in one MediaStore favourite request. The URIs travel as
     * ClipData through a binder call, so a selection of the whole library is
     * split into requests of this size and run one after another.
     */
    const val MAX_URIS_PER_REQUEST = 500

    /**
     * The value a toggle applies to items whose current favourite flags are
     * [current]: unfavourite only when every item already is a favourite;
     * a mixed (or empty) selection favourites everything.
     */
    fun targetValue(current: Collection<Boolean>): Boolean = current.isEmpty() || !current.all { it }
}
