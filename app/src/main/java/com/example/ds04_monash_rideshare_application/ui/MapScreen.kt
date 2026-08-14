package com.example.ds04_monash_rideshare_application.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.directions.driving.DrivingOptions
import com.yandex.mapkit.directions.driving.DrivingRoute
import com.yandex.mapkit.directions.driving.DrivingRouterType
import com.yandex.mapkit.directions.driving.DrivingSession
import com.yandex.mapkit.directions.driving.VehicleOptions
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.InputListener
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.map.Map as YandexMap
import com.yandex.mapkit.mapview.MapView
import kotlinx.coroutines.delay

private val MELBOURNE_CBD = Point(-37.8136, 144.9631)
private const val MELBOURNE_ZOOM = 11.0f
private const val ROUTE_REQUEST_TIMEOUT_MS = 15_000L

//Result of an origin -> Yandex -> destination routing request (proposal Member C: Yandex first implementation).
data class RouteQueryResult(
    val originLat: Double,
    val originLng: Double,
    val destinationLat: Double,
    val destinationLng: Double,
    val distanceMeters: Double,
    val estimatedDurationSeconds: Long
)

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var originPoint by remember { mutableStateOf<Point?>(null) }
    var destinationPoint by remember { mutableStateOf<Point?>(null) }
    var routeResult by remember { mutableStateOf<RouteQueryResult?>(null) }
    var statusMessage by remember { mutableStateOf("Tap the map to select your origin") }

    val mapView = remember {
        MapKitFactory.initialize(context)
        MapView(context).apply {
            map.move(CameraPosition(MELBOURNE_CBD, MELBOURNE_ZOOM, 0.0f, 0.0f))
        }
    }

    var originMarker by remember { mutableStateOf<PlacemarkMapObject?>(null) }
    var destinationMarker by remember { mutableStateOf<PlacemarkMapObject?>(null) }
    var drivingSession by remember { mutableStateOf<DrivingSession?>(null) }
    var pendingRequestId by remember { mutableStateOf(0) }

    fun resetSelection() {
        drivingSession?.cancel()
        drivingSession = null
        originMarker?.let { mapView.map.mapObjects.remove(it) }
        destinationMarker?.let { mapView.map.mapObjects.remove(it) }
        originMarker = null
        destinationMarker = null
        originPoint = null
        destinationPoint = null
        routeResult = null
        pendingRequestId = 0
        statusMessage = "Tap the map to select your origin"
    }

    fun requestRoute(origin: Point, destination: Point) {
        statusMessage = "Requesting route from Yandex..."
        pendingRequestId++
        val requestPoints = listOf(
            RequestPoint(origin, RequestPointType.WAYPOINT, null, null, null),
            RequestPoint(destination, RequestPointType.WAYPOINT, null, null, null)
        )
        val drivingRouter = DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.COMBINED)
        drivingSession = drivingRouter.requestRoutes(
            requestPoints,
            DrivingOptions(),
            VehicleOptions(),
            object : DrivingSession.DrivingRouteListener {
                override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
                    val route = routes.firstOrNull()
                    if (route == null) {
                        statusMessage = "No route found between these points"
                        return
                    }
                    val weight = route.metadata.weight
                    val distanceMeters = weight.distance.value
                    val durationSeconds = weight.time.value.toLong()
                    routeResult = RouteQueryResult(
                        originLat = origin.latitude,
                        originLng = origin.longitude,
                        destinationLat = destination.latitude,
                        destinationLng = destination.longitude,
                        distanceMeters = distanceMeters,
                        estimatedDurationSeconds = durationSeconds
                    )
                    statusMessage = "Distance: %.1f km · Duration: %d min (tap to start over)".format(
                        distanceMeters / 1000.0,
                        durationSeconds / 60
                    )
                }

                override fun onDrivingRoutesError(error: com.yandex.runtime.Error) {
                    statusMessage = "Route request failed - check Yandex API key/network (tap to start over)"
                }
            }
        )
    }

    val inputListener = remember {
        object : InputListener {
            override fun onMapTap(map: YandexMap, point: Point) {
                when {
                    originPoint == null -> {
                        originPoint = point
                        originMarker = map.mapObjects.addPlacemark(point)
                        statusMessage = "Origin set. Tap the map to select your destination"
                    }
                    destinationPoint == null -> {
                        destinationPoint = point
                        destinationMarker = map.mapObjects.addPlacemark(point)
                        requestRoute(originPoint!!, point)
                    }
                    else -> {
                        resetSelection()
                        originPoint = point
                        originMarker = map.mapObjects.addPlacemark(point)
                        statusMessage = "Origin set. Tap the map to select your destination"
                    }
                }
            }

            override fun onMapLongTap(map: YandexMap, point: Point) = Unit
        }
    }

    LaunchedEffect(pendingRequestId) {
        if (pendingRequestId == 0) return@LaunchedEffect
        val requestId = pendingRequestId
        delay(ROUTE_REQUEST_TIMEOUT_MS)
        if (pendingRequestId == requestId && routeResult == null) {
            statusMessage = "Route request timed out - check Yandex API key/network (tap to start over)"
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    MapKitFactory.getInstance().onStart()
                    mapView.onStart()
                    mapView.map.addInputListener(inputListener)
                }
                Lifecycle.Event.ON_STOP -> {
                    mapView.map.removeInputListener(inputListener)
                    mapView.onStop()
                    MapKitFactory.getInstance().onStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            drivingSession?.cancel()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp),
            tonalElevation = 4.dp
        ) {
            Text(text = statusMessage, modifier = Modifier.padding(12.dp))
        }
    }
}
