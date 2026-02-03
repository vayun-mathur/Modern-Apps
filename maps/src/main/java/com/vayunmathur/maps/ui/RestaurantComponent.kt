package com.vayunmathur.maps.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.timeFormat
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

fun goto(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@Composable
fun RestaurantBottomSheet(feature: SpecificFeature.Restaurant) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(feature.name ?: "", style = MaterialTheme.typography.titleLarge)
        feature.openingHours?.let {
            var showDetails by remember { mutableStateOf(false) }

            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val isOpen = it.isOpen(now)
            val nextChangeTime = it.nextStatusChangeTime(now)
            val text = AnnotatedString.Builder().apply {
                if(isOpen) withStyle(SpanStyle(Color.Green)){append("Open")} else withStyle(SpanStyle(Color.Red)){append("Closed")}
                append(" • ")
                if(isOpen) append("Closes ${nextChangeTime.time.format(timeFormat)}")
                else append("Opens ${nextChangeTime.time.format(timeFormat)}")
                if(nextChangeTime.date != now.date) append(" ${nextChangeTime.date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}")
            }.toAnnotatedString()
            Column {
                RestaurantItem(
                    R.drawable.outline_nest_clock_farsight_analog_24,
                    text,
                    shape = verticalShape(0, if (showDetails) 2 else 1)
                ) {
                    showDetails = !showDetails
                }
                if (showDetails) {
                    Spacer(Modifier.padding(2.dp))
                    Card(shape = verticalShape(1, 2)) {
                        for ((day, hours) in it.openingHours()) {
                            ListItem(
                                { Text(day.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                leadingContent = {},
                                trailingContent = { Text(hours) },
                                colors = ListItemDefaults.colors(Color.Transparent)
                            )
                        }
                    }
                }
            }
        }
        feature.menu?.let {
            RestaurantItem(R.drawable.outline_menu_book_24, "Menu") { goto(context, it) }
        }
        feature.website?.let {
            RestaurantItem(R.drawable.outline_globe_24, it.toUri().host!!) { goto(context, it) }
        }
        feature.phone?.let {
            RestaurantItem(R.drawable.outline_phone_enabled_24, it) { goto(context, "tel:$it") }
        }
    }
}

fun verticalShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) 12.dp else 0.dp
    val bottom = if (index == count - 1) 12.dp else 0.dp
    return RoundedCornerShape(top, top, bottom, bottom)
}

@Composable
fun RestaurantItem(icon: Int, text: String, shape: Shape = CardDefaults.shape, onClick: () -> Unit) {
    RestaurantItem(icon, AnnotatedString(text), shape, onClick)
}

@Composable
fun RestaurantItem(icon: Int, text: AnnotatedString, shape: Shape = CardDefaults.shape, onClick: () -> Unit) {
    Card(shape = shape) {
        ListItem({
            Text(text)
        }, Modifier.clickable(onClick = onClick), leadingContent = {
            Icon(painterResource(icon), null)
        }, colors = ListItemDefaults.colors(Color.Transparent))
    }
}