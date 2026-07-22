package com.vayunmathur.astronomy.domain.projection

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.astronomy.domain.engine.AltAz

interface SkyProjection {
    fun project(altAz: AltAz): Offset?
    fun unproject(screen: Offset): AltAz?
    fun isVisible(altAz: AltAz): Boolean
}
