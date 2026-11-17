package com.vayunmathur.youpipe.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.adaptiveGridCells
import com.vayunmathur.library.ui.isExpandedWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.SubscriptionFeedActions
import com.vayunmathur.youpipe.platform.SubscriptionFeedUiState
import com.vayunmathur.youpipe.platform.YouPipeViewModel


/**
 * A single placeholder row matching [VideoRow]'s layout: a 16:9 block on the left
 * and a few stacked gray bars on the right. Uses the same static `surfaceVariant`
 * gray-box idiom the real rows use for image placeholders — no shimmer library.
 */
@Composable
fun VideoRowSkeleton(modifier: Modifier = Modifier) {
    Row(modifier) {
        Box(Modifier.weight(1f)) {
            Box(
                Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        Box(Modifier.weight(1.5f)) {
            Column(Modifier.padding(16.dp)) {
                SkeletonBar(Modifier.fillMaxWidth().height(16.dp))
                Spacer(Modifier.height(8.dp))
                SkeletonBar(Modifier.fillMaxWidth(0.6f).height(12.dp))
                Spacer(Modifier.height(6.dp))
                SkeletonBar(Modifier.fillMaxWidth(0.4f).height(12.dp))
            }
        }
    }
}



@Composable
private fun SkeletonBar(modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
