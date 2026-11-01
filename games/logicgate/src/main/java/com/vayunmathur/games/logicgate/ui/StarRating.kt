package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconStarBorder
import com.vayunmathur.library.ui.MaterialTheme

/** Zero to three stars. */
@Composable
fun StarRating(stars: Int, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val description = stringResource(R.string.lg_cd_stars, stars)
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        repeat(MAX_STARS) { i ->
            if (i < stars) {
                IconStar(Modifier.size(size), tint = MaterialTheme.colorScheme.tertiary)
            } else {
                IconStarBorder(Modifier.size(size), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val MAX_STARS = 3
