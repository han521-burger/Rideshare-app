package com.example.ds04_monash_rideshare_application.core.ui

import android.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ds04_monash_rideshare_application.BuildConfig
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.haversineMeters
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.PolylineMapObject
import com.yandex.mapkit.mapview.MapView

// A small, read-only map for "where actually is this ride" - not tap-to-select like MapScreen,
// just origin/destination pins (and the road route between them, when known) with the camera framed
// to show both. Still a real MapView, so it
// supports normal pinch-zoom/pan to look around.
@Composable
fun RouteMiniMap(
    originLat: Double,
    originLng: Double,
    destinationLat: Double,
    destinationLng: Double,
    modifier: Modifier = Modifier,
    // Road geometry from pickup to drop-off; when given, it is drawn as a line between the pins.
    routePoints: List<Point> = emptyList()
) {
    // MapKit throws an AssertionError when it is initialized without an API key. The map-based
    // ride editor already handles that setup state, and read-only ride details must do the same so
    // a missing local developer secret never makes an otherwise usable ride/request flow crash.
    if (BuildConfig.YANDEX_MAPKIT_API_KEY.isBlank()) {
        Card(modifier = modifier.fillMaxWidth().height(200.dp)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Map preview unavailable", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${describeLocation(originLat, originLng)} → " +
                        describeLocation(destinationLat, destinationLng),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Add YANDEX_MAPKIT_API_KEY to local.properties to enable maps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val lineColor = MaterialTheme.colorScheme.primary.toArgb()

    // New endpoints mean a new map: AndroidView only calls its factory once, so the key swaps the whole view.
    key(originLat, originLng, destinationLat, destinationLng) {
        val mapView = remember {
            val origin = Point(originLat, originLng)
            val destination = Point(destinationLat, destinationLng)
            val midpoint = Point((originLat + destinationLat) / 2.0, (originLng + destinationLng) / 2.0)
            val distanceKm = haversineMeters(originLat, originLng, destinationLat, destinationLng) / 1000.0
            val zoom = when {
                distanceKm < 2 -> 14.0f
                distanceKm < 5 -> 13.0f
                distanceKm < 10 -> 12.0f
                distanceKm < 20 -> 11.0f
                else -> 9.5f
            }
            MapView(context).apply {
                map.move(CameraPosition(midpoint, zoom, 0.0f, 0.0f))
                // Pins in their own layer above the route line, which can arrive after the map is shown.
                map.mapObjects.addCollection().apply {
                    zIndex = 10f
                    addPlacemark(origin)
                    addPlacemark(destination)
                }
            }
        }
        val routeLayer = remember(mapView) { mapView.map.mapObjects.addCollection().apply { zIndex = 0f } }

        LaunchedEffect(routePoints, lineColor) {
            routeLayer.clear()
            if (routePoints.size >= 2) styleRouteLine(routeLayer.addPolyline(Polyline(routePoints)), lineColor)
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

        AndroidView(
            modifier = modifier.fillMaxWidth().height(200.dp),
            factory = { mapView }
        )
    }
}

// The one look for a driving route on any map: a thin line in the theme colour with a faint white edge.
fun styleRouteLine(line: PolylineMapObject, color: Int) {
    line.setStrokeColor(color)
    line.strokeWidth = 3f
    line.outlineColor = Color.WHITE
    line.outlineWidth = 1f
}
