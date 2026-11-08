package com.vayunmathur.maps.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Map geometry that two or more call sites have to agree on.
 *
 * This is NOT a dimens file. Ordinary padding belongs to
 * [com.vayunmathur.library.ui.Spacing], and a genuinely one-off size is better as a literal
 * where it is used — `Spacing`'s own KDoc says so. What lives here is the narrower set where
 * a number appearing in one file is only correct because of a number in another: get them
 * out of sync and the UI overlaps, which is exactly what happened to the two overlay insets
 * below.
 */
object MapChromeMetrics {

    /**
     * How much of the bottom sheet shows when it is peeking, for a selection with no
     * measurable header.
     *
     * Places do not use this: `BottomSheetHeader` gives the scaffold a header slot and the
     * peek is measured from it, so a place peeks at exactly its title through its action
     * row. What is left on this number is routes and admin labels, which have no header
     * split yet — and for those it keeps the known limitation it always had, that content
     * shorter than 170 dp renders padding below it.
     */
    val sheetPeekHeight: Dp = 170.dp

    /**
     * Height of the navigation ETA strip, measured from its own layout: 12 dp vertical
     * padding either side of a 20 sp line over a 14 sp line, with the "End trip" button
     * setting the real floor at a 40 dp minimum touch target.
     *
     * An estimate, and the reason the two insets below are derived from it rather than from
     * each other. If the strip's typography changes this number has to change with it —
     * which is still strictly better than the two independent magic numbers it replaces
     * (`96.dp` and `84.dp`, 12 dp apart for no stated reason, so one of them was wrong).
     */
    val etaStripHeight: Dp = 72.dp

    /** Clearance for chrome sitting directly above the ETA strip. */
    val aboveEtaStrip: Dp = etaStripHeight + 12.dp

    /** Clearance for the full-width step list, which tucks closer than free-floating chrome. */
    val aboveEtaStripTight: Dp = etaStripHeight + 4.dp

    /**
     * Clearance for chrome sitting directly above the scale bar.
     *
     * The bar is a labelSmall line (16 dp) + 8 dp vertical padding + a 2 dp gap
     * + a 3 dp rule ≈ 29 dp, rounded up so the pill never kisses it. An
     * estimate like [etaStripHeight], for the same reason: one shared number
     * beats a magic one at each call site.
     */
    val aboveScaleBar: Dp = 32.dp + 12.dp

    /**
     * Radius of the tolerance box around a tap used to hit-test map pins.
     *
     * Coupled to the marker `iconSize` ramps in the pin layers, not to any spacing value: it
     * exists so tapping *near* a small glyph still selects it. 22 dp gives a 44 dp box, just
     * under Material's 48 dp minimum touch target. Revisit it whenever those ramps change —
     * the marker sizes were halved once already without this being looked at.
     */
    val hitSlop: Dp = 22.dp

    /** Outer margin of the floating map chrome (FAB stacks, scale bar). */
    val chromeMargin: Dp = 16.dp

    /** Gap between stacked floating action buttons. */
    val fabSpacing: Dp = 12.dp
}
