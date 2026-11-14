package com.vayunmathur.astronomy.domain.projection

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.astronomy.domain.engine.AltAz

/** Maps horizontal coordinates to screen points and back for one sky view. */
interface SkyProjection {
    fun project(altAz: AltAz): Offset?
    fun unproject(screen: Offset): AltAz?
    fun isVisible(altAz: AltAz): Boolean
}
