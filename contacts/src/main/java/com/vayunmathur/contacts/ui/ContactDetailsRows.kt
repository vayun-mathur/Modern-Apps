package com.vayunmathur.contacts.ui

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedText
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailItem(
    icon: @Composable () -> Unit,
    data: String,
    label: String,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
    onTrailingIconClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    dropdownContent: (@Composable () -> Unit)? = null,
    trailingDropdownContent: (@Composable () -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    /**
     * Pairs this row's value text with the text inside the field it becomes, so the two travel together
     * while the row's box morphs into the field's box. Without it the box morphs alone and drags this
     * text along as cargo, crossfading it out somewhere it does not belong.
     */
    sharedTextKey: Any? = null,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick ?: { },
                onLongClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("detail", data))) } }
            )
    ) {
        ListItem(
            content = {
                Box {
                    Text(
                        data,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = if (sharedTextKey == null) Modifier
                        else Modifier.sharedText(sharedTextKey),
                    )
                    dropdownContent?.invoke()
                }
            },
            supportingContent = { Text(label, style = MaterialTheme.typography.bodySmall) },
            leadingContent = { icon() },
            trailingContent = {
                if (trailingIcon != null && onTrailingIconClick != null) {
                    Box {
                        IconButton(onClick = onTrailingIconClick) {
                            trailingIcon()
                        }
                        trailingDropdownContent?.invoke()
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
    }
}

fun groupShape(
    index: Int,
    size: Int,
    outerRadius: androidx.compose.ui.unit.Dp = 16.dp,
    innerRadius: androidx.compose.ui.unit.Dp = 4.dp,
    flatTop: Boolean = false,
    flatBottom: Boolean = false,
): androidx.compose.ui.graphics.Shape {
    val isFirst = index == 0 && !flatTop
    val isLast = index == size - 1 && !flatBottom
    return RoundedCornerShape(
        topStart = if (isFirst) outerRadius else innerRadius,
        topEnd = if (isFirst) outerRadius else innerRadius,
        bottomStart = if (isLast) outerRadius else innerRadius,
        bottomEnd = if (isLast) outerRadius else innerRadius,
    )
}
