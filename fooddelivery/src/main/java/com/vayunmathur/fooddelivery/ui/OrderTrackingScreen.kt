package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.GestureOptions
import com.vayunmathur.library.map.MapOptions
import com.vayunmathur.library.map.VectorMap
import com.vayunmathur.library.map.rememberCameraState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconDeliveryDining
import com.vayunmathur.library.ui.IconRestaurant
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.library.log.Log
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.fooddelivery.api.BitesOrders
import com.vayunmathur.fooddelivery.data.Order
import com.vayunmathur.fooddelivery.data.OrderStage
import com.vayunmathur.fooddelivery.notifications.OrderTrackingService
import com.vayunmathur.fooddelivery.platform.AppInit
import kotlinx.coroutines.delay
import kotlin.time.Instant
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.rememberIs24Hour
import com.vayunmathur.library.ui.appBarScrollBehavior
import kotlin.math.max
import kotlin.math.min

/** How often to re-poll while the order is still moving. */
private const val POLL_MS = 15_000L

/**
 * Live tracking for one order: the courier's position against the restaurant and the
 * delivery address, the current stage, an ETA, and the courier's details.
 *
 * There is no per-order tracking endpoint — the reference re-reads the same
 * `/orders/past/all` list the Orders tab uses, so this polls that and re-finds the order.
 */
@Composable
fun OrderTrackingScreen(orderId: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    var order by remember { mutableStateOf<Order?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(orderId) {
        AppInit.awaitReady()
        // Keep the background Live Update tracker running for this order too, so tracking
        // opened from the Orders tab (or a re-opened app) is covered. Idempotent.
        OrderTrackingService.start(context, orderId)
        while (true) {
            val found = BitesOrders.getOrders().firstOrNull { it.id == orderId }
            order = found
            Log.dev("Tracking", "order=${found?.id} stage=${found?.stage} " +
                "driver=${found?.driverPosition} eta=${found?.etaMillis}")
            loading = false
            // Stop polling once it's done; nothing more will change.
            if (found == null || found.isDone) break
            delay(POLL_MS)
        }
    }

    AppScaffold(
        // Title slot rather than the string overload so the merchant name can carry the shared
        // key it travels in on from the Orders row.
        title = {
            Text(
                order?.merchant?.name ?: stringResource(R.string.tracking),
                modifier = Modifier.sharedText("food-order-merchant-$orderId"),
            )
        },
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val o = order
        when {
            loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            o == null -> EmptyState(
                title = stringResource(R.string.order_not_found),
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> Box(
                Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())
            ) {
                // Map runs edge to edge; the details float on top of it.
                TrackingMap(o, Modifier.fillMaxSize())
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 12.dp,
                ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .padding(bottom = padding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StageHeader(o)
                    CourierCard(o, onCall = { phone -> ExternalIntents.dial(context, phone) })
                    o.deliveryTrackingUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        Button(
                            onClick = {
                                ExternalIntents.openUrl(context, url)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.live_courier_map)) }
                    }
                    o.proofOfDeliveryImageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        Text(stringResource(R.string.delivered_photo),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold)
                        AsyncImage(
                            model = url,
                            contentDescription = stringResource(R.string.delivered_photo),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
                }
            }
        }
    }
}

/**
 * Restaurant, courier and destination on a raster map. Markers are absolutely positioned
 * from the camera projection, matching how findfamily overlays its map.
 */
@Composable
private fun TrackingMap(order: Order, modifier: Modifier = Modifier) {
    val restaurant = order.merchant?.let { m ->
        val la = m.latitude; val lo = m.longitude
        if (la != null && lo != null) GeoPoint(lo, la) else null
    }
    val destination = order.address?.let { a ->
        val la = a.latitude; val lo = a.longitude
        if (la != null && lo != null) GeoPoint(lo, la) else null
    }
    val driver = order.driverPosition?.let { d ->
        val la = d.latitude; val lo = d.longitude
        if (la != null && lo != null) GeoPoint(lo, la) else null
    }
    val points = listOfNotNull(restaurant, destination, driver)

    if (points.isEmpty()) {
        // Without coordinates a map would just be a blank tile grid.
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_map_location),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    // Frame everything we know about; zoom is a rough fit from the spread of the points.
    val centre = GeoPoint(
        longitude = points.sumOf { it.longitude } / points.size,
        latitude = points.sumOf { it.latitude } / points.size,
    )
    val spread = max(
        points.maxOf { it.longitude } - points.minOf { it.longitude },
        points.maxOf { it.latitude } - points.minOf { it.latitude },
    )
    val zoom = when {
        spread <= 0.0 -> 15.0
        else -> min(16.0, max(10.0, Math.log(360.0 / spread) / Math.log(2.0)))
    }
    val camera = rememberCameraState(CameraPosition(target = centre, zoom = zoom))
    LaunchedEffect(centre, zoom) {
        camera.position = CameraPosition(target = centre, zoom = zoom)
    }

    Box(modifier) {
        VectorMap(cameraState = camera, options = MapOptions(gestureOptions = GestureOptions.TiltLocked), online = ConnectivityMonitor.isOnline) {
            // Distinct glyph per pin: menu = restaurant, arrow = courier, home = you.
            restaurant?.let {
                MapMarker(it) { PinChrome(MaterialTheme.colorScheme.tertiary) { IconRestaurant() } }
            }
            destination?.let {
                MapMarker(it) { PinChrome(MaterialTheme.colorScheme.primary) { IconHome() } }
            }
            driver?.let {
                MapMarker(it) { PinChrome(MaterialTheme.colorScheme.secondary) { IconDeliveryDining() } }
            }
        }
    }
}

/**
 * The circular disc a pin's icon sits on. Stays app-side rather than moving into
 * `:library:map` with the positioning: it reads `colorScheme`, and that module has no
 * material3.
 */
@Composable
private fun PinChrome(
    color: androidx.compose.ui.graphics.Color,
    icon: @Composable () -> Unit,
) {
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(32.dp)) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun StageHeader(order: Order) {
    val stage = order.stage
    val locale = LocalConfiguration.current.locales[0]
    val is24 = rememberIs24Hour()
    Column {
        Text(stage.label, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)

        val eta = order.etaMillis
        if (stage != OrderStage.DELIVERED && eta != null) {
            val minutes = ((eta - System.currentTimeMillis()) / 60_000L).toInt()
            Spacer(Modifier.height(4.dp))
            Text(
                if (minutes > 0) stringResource(R.string.eta_minutes, minutes,
                    DateString.time(Instant.fromEpochMilliseconds(eta), is24Hour = is24, locale = locale))
                else stringResource(R.string.eta_any_moment),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        order.driverDistanceMiles?.let { miles ->
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.driver_distance, "%.1f".format(miles)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = {
                when (stage) {
                    OrderStage.PREPARING_SOON -> 0.1f
                    OrderStage.PREPARING -> 0.3f
                    OrderStage.READY -> 0.5f
                    OrderStage.PICKED_UP -> if (order.isDelivery) 0.65f else 1f
                    OrderStage.DRIVING -> 0.8f
                    OrderStage.ARRIVING -> 0.95f
                    OrderStage.DELIVERED -> 1f
                }
            },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
private fun CourierCard(order: Order, onCall: (String) -> Unit) {
    val name = order.courierName?.takeIf { it.isNotBlank() } ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val image = order.courierImageUrl?.takeIf { it.isNotBlank() }
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape),
                    )
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape, modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { IconPerson() }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.your_courier),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(name, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium)
                }
                order.courierPhone?.takeIf { it.isNotBlank() }?.let { phone ->
                    OutlinedButton(onClick = { onCall(phone) }) {
                        Text(stringResource(UiR.string.call))
                    }
                }
            }
        }
    }
}
