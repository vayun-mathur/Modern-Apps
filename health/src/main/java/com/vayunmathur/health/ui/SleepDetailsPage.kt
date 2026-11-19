package com.vayunmathur.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberIs24Hour
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.SleepSessionRecord
import com.vayunmathur.health.R
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.SleepData
import com.vayunmathur.health.data.SleepStage
import com.vayunmathur.health.ui.components.hypnogramColors
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.health.platform.displayString
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepDetailsPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val initialPage = 999
    val pagerState = rememberPagerState(initialPage = initialPage) { 1000 }
    val scope = rememberCoroutineScope()
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)

    AppScaffold(
        title = {
            Text(
                stringResource(R.string.metric_sleep),
                modifier = Modifier.sharedText("health-metric-label-SLEEP"),
            )
        },
        onNavigateBack = { backStack.pop() },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Navigation Row (Same as BarChartDetails)
            val selectedDay = remember(pagerState.currentPage) {
                today.minus(initialPage - pagerState.currentPage, DateTimeUnit.DAY)
            }
            val headerLabel = if (selectedDay == today) stringResource(R.string.nav_today)
            else selectedDay.displayString()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    }) {
                    IconBack()
                }

                Text(
                    text = headerLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.widthIn(min = 140.dp),
                    textAlign = TextAlign.Center
                )

                IconButton(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    enabled = pagerState.currentPage < pagerState.pageCount - 1 && selectedDay < today
                ) {
                    IconArrowForward()
                }
            }

            HorizontalPager(
                state = pagerState, modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) { page ->
                val day = today.minus(initialPage - page, DateTimeUnit.DAY)
                val record by remember(day) { viewModel.sleepRecordForDay(day) }
                    .collectAsState(null)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    record?.let { r ->
                        val sleepData = r.sleepData
                        if (sleepData != null) {
                            item { SleepSummaryHeader(r) }
                            item { SleepStageGraph(r) }
                            item { SleepStageBreakdown(sleepData) }
                        }
                    } ?: item {
                        Box(
                            Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.no_data_available),
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

