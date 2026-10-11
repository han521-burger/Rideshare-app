package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ds04_monash_rideshare_application.BuildConfig
import com.example.ds04_monash_rideshare_application.core.demand.DemandModel
import com.example.ds04_monash_rideshare_application.core.demand.DemandPalette
import com.example.ds04_monash_rideshare_application.core.demand.DemandPeriod
import com.example.ds04_monash_rideshare_application.core.demand.DemandPoint
import com.example.ds04_monash_rideshare_application.core.demand.DemandProvider
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import com.yandex.mapkit.geometry.Circle
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.mapview.MapView

private val MELBOURNE_SOUTH_EAST = Point(-37.95, 145.05)
private const val OVERVIEW_ZOOM = 9.4f
private const val CAMPUS_ZOOM = 11.0f
private const val TOP_ZONES = 3

// Informational only: where passenger demand has been concentrated around the approved hubs, for
// the time bucket we are in now. It never selects a hub, changes the route or intercepts a tap -
// the zones are non-interactive map circles beneath any pins. Used by the Request Ride and Offer
// Ride route step (CampusRoutePicker). `map` is a slot so tests can run without Yandex MapKit.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemandZonesCard(
    modifier: Modifier = Modifier,
    campus: NamedLocation? = null,
    markers: List<NamedLocation> = emptyList(),
    model: DemandModel = DemandProvider.model,
    // Predicted demand (ForecastDemand.kt). When present the card opens on it and offers past requests as the other view.
    forecastModel: DemandModel? = DemandProvider.forecast,
    nowMillis: Long = remember { System.currentTimeMillis() },
    map: @Composable (points: List<DemandPoint>, showZones: Boolean, campus: NamedLocation?, markers: List<NamedLocation>) -> Unit =
        { points, show, c, m -> DemandZonesMap(points, show, c, m) }
) {
    var showZones by rememberSaveable { mutableStateOf(true) }
    var showForecast by rememberSaveable { mutableStateOf(true) }
    val active = if (showForecast && forecastModel != null) forecastModel else model
    // Recomputed only when the bucket changes (the model also caches per bucket).
    val period = remember(nowMillis) { DemandPeriod.of(nowMillis) }
    val points = remember(active, period) { active.pointsFor(period) }
    val busiest = remember(points) { points.filter { it.rawDemand > 0.0 }.take(TOP_ZONES) }

    FormSectionCard(
        "Demand zones", modifier.testTag("demand-zones-card"),
        supporting = if (active.isForecast) "Where passengers are expected to request rides at this time of day, predicted by a demand-forecasting model."
            else "Demand zones show where passengers have historically requested rides.",
        trailing = { FormStatusPill(period.label, Modifier.testTag("demand-period")) }
    ) {
        if (forecastModel != null) {
            ChoiceChipGroup(
                options = listOf(true, false), selected = showForecast,
                label = { if (it) "Predicted" else "Past requests" },
                onSelect = { showForecast = it }, groupDescription = "Demand view",
                modifier = Modifier.testTag("demand-view")
            )
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("demand-toggle")
                .toggleable(value = showZones, role = Role.Switch, onValueChange = { showZones = it }),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Show demand zones", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Switch(checked = showZones, onCheckedChange = null)
        }
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).testTag("demand-map")) {
            map(points, showZones, campus, markers)
        }
        if (showZones) {
            DemandLegend()
            Text(
                when {
                    busiest.isEmpty() && active.isForecast -> "No demand expected for this time of day."
                    busiest.isEmpty() -> "No demand recorded for this time of day yet."
                    // The forecast is requests expected in this block on one day.
                    active.isForecast -> "Expected busiest: " + busiest.joinToString(" · ") { "${it.rank}. ${it.hubName} (~${"%.1f".format(it.rawDemand)})" }
                    else -> "Busiest now: " + busiest.joinToString(" · ") { "${it.rank}. ${it.hubName}" }
                },
                Modifier.testTag("demand-top"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
            )
            if (active.isForecast) {
                Text(
                    "Numbers are expected requests per day in this time block.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        active.disclosure?.let {
            Text(it, Modifier.testTag("demand-disclosure"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemandLegend() {
    val ramp = Brush.horizontalGradient(
        listOf(0.0, 0.5, 1.0).map { Color(DemandPalette.strokeArgb(it)) }
    )
    FlowRow(
        Modifier.fillMaxWidth().testTag("demand-legend").semantics(mergeDescendants = true) {
            contentDescription = "Legend: lighter zones mean lower demand, red zones mean higher demand"
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Lower demand", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.align(Alignment.CenterVertically).width(72.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(ramp).clearAndSetSemantics { })
        Text("Higher demand", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// One translucent circle per hub with demand (<= 30), drawn beneath the route pins. Circles and
// pins live in separate collections so toggling or refreshing the zones never touches the pins.
@Composable
internal fun DemandZonesMap(
    points: List<DemandPoint>,
    showZones: Boolean,
    campus: NamedLocation?,
    markers: List<NamedLocation>,
    modifier: Modifier = Modifier
) {
    if (BuildConfig.YANDEX_MAPKIT_API_KEY.isBlank()) {
        Card(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Map preview unavailable", style = MaterialTheme.typography.titleMedium)
                Text("Add YANDEX_MAPKIT_API_KEY to local.properties to enable maps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context).apply { map.move(CameraPosition(MELBOURNE_SOUTH_EAST, OVERVIEW_ZOOM, 0.0f, 0.0f)) } }
    val zoneLayer = remember(mapView) { mapView.map.mapObjects.addCollection().apply { zIndex = 0f } }
    val markerLayer = remember(mapView) { mapView.map.mapObjects.addCollection().apply { zIndex = 10f } }

    LaunchedEffect(points) {
        zoneLayer.clear()
        DemandPalette.visibleZones(points).forEach { zone ->
            val circle = zoneLayer.addCircle(Circle(Point(zone.latitude, zone.longitude), DemandPalette.radiusMeters(zone.normalizedIntensity).toFloat()))
            circle.fillColor = DemandPalette.fillArgb(zone.normalizedIntensity)
            circle.strokeColor = DemandPalette.strokeArgb(zone.normalizedIntensity)
            circle.strokeWidth = 1f
        }
    }
    LaunchedEffect(showZones) { zoneLayer.isVisible = showZones }
    LaunchedEffect(markers) {
        markerLayer.clear()
        markers.forEach { markerLayer.addPlacemark(it.point) }
    }
    LaunchedEffect(campus) {
        val target = campus?.point ?: MELBOURNE_SOUTH_EAST
        mapView.map.move(CameraPosition(target, if (campus != null) CAMPUS_ZOOM else OVERVIEW_ZOOM, 0.0f, 0.0f))
    }

    DisposableEffect(lifecycleOwner, mapView) {
        var started = false
        fun stopMap() { if (started) { mapView.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_STOP -> stopMap()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); stopMap() }
    }

    AndroidView(modifier = modifier.fillMaxSize(), factory = { mapView })
}
