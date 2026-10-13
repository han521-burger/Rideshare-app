package com.example.ds04_monash_rideshare_application.core.location

import android.content.Context
import android.util.Log
import com.example.ds04_monash_rideshare_application.BuildConfig
import com.example.ds04_monash_rideshare_application.ui.RouteQueryResult
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.directions.driving.*
import com.yandex.mapkit.geometry.Point
import com.yandex.runtime.network.NetworkError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Activity owns MapKit startup, including headless requests made before any map is displayed.
// `via` are stops visited in order between origin and destination.
@Suppress("UNUSED_PARAMETER")
suspend fun requestDrivingRoute(context: Context, origin: Point, destination: Point, via: List<Point> = emptyList()): RouteQueryResult {
    if (BuildConfig.YANDEX_MAPKIT_API_KEY.isBlank()) throw IOException("Route service is currently unavailable. Please try again.")
    try {
        return withTimeout(30_000L) {
            suspendCancellableCoroutine { continuation ->
                val request = DrivingRequest(origin, destination, via, continuation)
                continuation.invokeOnCancellation { request.cancel() }
                request.start()
            }
        }
    } catch (error: TimeoutCancellationException) {
        throw IOException("Route calculation timed out. Please try again.")
    }
}

private class DrivingRequest(private val origin: Point, private val destination: Point, private val via: List<Point>,
    private val continuation: CancellableContinuation<RouteQueryResult>) {
    private val router = DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.COMBINED)
    private var session: DrivingSession? = null
    private val listener = object : DrivingSession.DrivingRouteListener {
        override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
            if (!continuation.isActive) return
            val route = routes.firstOrNull()
            if (route == null) continuation.resumeWithException(IOException("No driving route found between these approved points."))
            else {
                val weight = route.metadata.weight
                continuation.resume(RouteQueryResult(origin.latitude, origin.longitude, destination.latitude,
                    destination.longitude, weight.distance.value, weight.time.value.toLong(), route.geometry.points))
            }
        }
        override fun onDrivingRoutesError(error: com.yandex.runtime.Error) {
            Log.w("LocationSearch", "Yandex routing failed: ${error.javaClass.simpleName}")
            if (continuation.isActive) continuation.resumeWithException(IOException(
                if (error is NetworkError) "Unable to connect. Check your internet connection."
                else "Route service is currently unavailable. Please try again."))
        }
    }
    fun start() {
        val points = (listOf(origin) + via + destination).map { RequestPoint(it, RequestPointType.WAYPOINT, null, null, null) }
        session = router.requestRoutes(points, DrivingOptions(), VehicleOptions(), listener)
    }
    fun cancel() { session?.cancel() }
}
