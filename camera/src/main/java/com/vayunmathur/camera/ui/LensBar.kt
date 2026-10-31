package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.domain.LensLabels
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.library.ui.Text

/**
 * Physical-lens picker: one pill per enumerated lens of the current facing,
 * labeled Aperture-style (".4", ".5", "1x", "2x"). Pills are fixed 36dp
 * circles with 13sp labels (mirroring [ZoomBar]), so labels stay short —
 * never the old "UW · 13mm" compound that truncated in the chip.
 * Hidden when fewer than two lenses are available. Stateless: the caller
 * passes the family plus the selection.
 */
@Composable
fun LensBar(
    lenses: List<PhysicalLens>,
    selected: PhysicalLens?,
    onSelect: (PhysicalLens) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lenses.size < 2) return
    // Pair each lens with its zoom-factor label, then sort ascending (".4",
    // ".5", "1x", …) like Aperture regardless of enumeration order. Labels
    // are positional — sorted once so every pill matches its lens.
    val ordered = remember(lenses) {
        val sorted = LensLabels.sortedByFactor(LensLabels.dedupe(lenses))
        sorted.zip(LensLabels.labelsFor(sorted))
    }
    val pickerDescription = stringResource(R.string.lens_picker_description)
    Row(
        modifier = modifier
            .semantics { contentDescription = pickerDescription }
            .background(Color(0x99000000), RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ordered.forEach { (lens, label) ->
            val isSelected = lens == selected
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .selectedPill(isSelected, CircleShape)
                    .clip(CircleShape)
                    .clickable { onSelect(lens) },
                contentAlignment = Alignment.Center,
            ) {
                // Precise collision labels (".50", "2.03x") run to 5 glyphs:
                // step down to 11sp so they fit the fixed 36dp chip instead
                // of clipping (short labels stay at the 13sp ZoomBar size).
                Text(
                    label,
                    color = if (isSelected) Color.White else Color(0xFFBBBBBB),
                    fontSize = if (label.length > 3) 11.sp else 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}
