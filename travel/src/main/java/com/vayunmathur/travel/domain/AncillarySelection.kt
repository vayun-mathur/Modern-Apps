package com.vayunmathur.travel.domain

import com.vayunmathur.travel.network.ServiceSelectionDto

/**
 * Whether [selections] must be sent to the trip. The server replaces the whole
 * set on every save, so an unchanged set is skipped, but going from some
 * extras back to none must still be saved to detach them.
 */
fun ancillariesNeedSaving(selections: List<ServiceSelectionDto>, saved: List<ServiceSelectionDto>): Boolean =
    selections.toSet() != saved.toSet()
