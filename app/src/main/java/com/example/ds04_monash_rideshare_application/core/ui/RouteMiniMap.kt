package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ds04_monash_rideshare_application.ui.haversineMeters
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.mapview.MapView

// A small, read-only map for "where actually is this ride" - not tap-to-select like MapScreen,
// just origin/destination pins with the camera framed to show both. Still a real MapView, so it
// supports normal pinch-zoom/pan to look around.
@Composable
fun RouteMiniMap(
    originLat: Double,
    originLng: Double,
    destinationLat: Double,
    destinationLng: Double,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember(originLat, originLng, destinationLat, destinationLng) {
        MapKitFactory.initialize(context)
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
            map.mapObjects.addPlacemark(origin)
            map.mapObjects.addPlacemark(destination)
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    MapKitFactory.getInstance().onStart()
                    mapView.onStart()
                }
                Lifecycle.Event.ON_STOP -> {
                    mapView.onStop()
                    MapKitFactory.getInstance().onStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(200.dp),
        factory = { mapView }
    )
}
