package com.vayunmathur.findfamily.wear.ui

/** Watch navigation: detail routes carry the person id, never selection state. */
sealed interface WearRoute {
    /** Every person in the circle (home). */
    data object People : WearRoute

    /** One person's location detail, identified by phone user id. */
    data class Person(val personId: Long) : WearRoute

    /** All-people relative map around the snapshot region centre. */
    data object AllMap : WearRoute
}
