package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.ui.components.HealthRow
import com.vayunmathur.library.ui.ExpandVisibility
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.animatedColor
import com.vayunmathur.library.ui.animatedDp

/**
 * One category: a tappable header that opens into a vertical group of segmented rows.
 *
 * The contacts-groups arrangement — header plus expanded list in a single lazy item, the header's
 * colour and bottom radius animating alongside the [ExpandVisibility] — with the Today/Body
 * [HealthRow] kit inside instead of contact rows. Empty categories render the header only, which
 * does nothing when tapped.
 */
@Composable
fun MedicalCategoryGroup(
    title: String,
    icon: @Composable (Modifier, androidx.compose.ui.graphics.Color) -> Unit,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    itemCount: Int,
    row: @Composable (index: Int) -> Unit,
) {
    val attached = isExpanded && itemCount > 0
    Column {
        MedicalCategoryHeader(
            title = title,
            icon = icon,
            attached = attached,
            // An empty group still shows its header, so it says None rather than looking broken.
            trailing = if (itemCount == 0) stringResource(R.string.medical_empty) else null,
            onClick = onToggle,
        )
        ExpandVisibility(visible = attached) {
            Column {
                Spacer(Modifier.height(2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (index in 0 until itemCount) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = medicalGroupShape(
                                index,
                                itemCount,
                                flatTop = index == 0,
                            ),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            row(index)
                        }
                    }
                }
            }
        }
    }
}

/**
 * A category header — icon plus name, nothing else.
 *
 * A [Surface] rather than a bare row so the bottom corners can square off into the expanded group
 * below it, and the container can tint while it is attached to that group.
 */
@Composable
private fun MedicalCategoryHeader(
    title: String,
    icon: @Composable (Modifier, androidx.compose.ui.graphics.Color) -> Unit,
    attached: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val collapsedColor = MaterialTheme.colorScheme.surfaceVariant
    val expandedColor = MaterialTheme.colorScheme.secondaryContainer
    val headerColor = animatedColor(if (attached) expandedColor else collapsedColor)
    val bottomRadius = animatedDp(if (attached) 4.dp else 16.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = bottomRadius,
            bottomEnd = bottomRadius,
        ),
        color = headerColor,
    ) {
        HealthRow(
            headline = title,
            leadingIcon = icon,
            leadingTint = HealthColors.Medical,
            trailing = trailing?.let { label ->
                { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
        )
    }
}

/**
 * The segmented column shape: rounded on the outside, nearly square where rows meet.
 *
 * A local copy of the contacts-groups shape, which lives in another app module and cannot be
 * A single row is rounded all over; the first row keeps a flat top when
 * it attaches to the header above it.
 *
 * Empty categories render the header only, with a trailing None so the row reads as an answer
 * rather than a dead tap — the header still toggles, but there is nothing to expand into.
 */
internal fun medicalGroupShape(
    index: Int,
    size: Int,
    outerRadius: Dp = 16.dp,
    innerRadius: Dp = 4.dp,
    flatTop: Boolean = false,
    flatBottom: Boolean = false,
): Shape {
    val isFirst = index == 0 && !flatTop
    val isLast = index == size - 1 && !flatBottom
    return RoundedCornerShape(
        topStart = if (isFirst) outerRadius else innerRadius,
        topEnd = if (isFirst) outerRadius else innerRadius,
        bottomStart = if (isLast) outerRadius else innerRadius,
        bottomEnd = if (isLast) outerRadius else innerRadius,
    )
}
