package com.example.ds04_monash_rideshare_application.core.location

import android.content.Context
import com.example.ds04_monash_rideshare_application.ui.RouteQueryResult
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
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine

// Headless equivalent of MapScreen's tap-to-route flow (same DrivingRouter call, see
// MapScreen.kt's requestRoute): a real Yandex driving distance/ETA between two already-known
// points, with no live MapView needed. Used by the campus/postcode route picker's confirm step
// and the driver Requests-page distance filter, neither of which show a full interactive map.
// Suspends and cancels the underlying request if the calling coroutine is cancelled (e.g. a
// filtered list scrolling a row out of view before its route resolves).
suspend fun requestDrivingRoute(context: Context, origin: Point, destination: Point): RouteQueryResult =
    suspendCancellableCoroutine { cont ->
        MapKitFactory.initialize(context)
        val requestPoints = listOf(
            RequestPoint(origin, RequestPointType.WAYPOINT, null, null, null),
            RequestPoint(destination, RequestPointType.WAYPOINT, null, null, null)
        )
        val drivingRouter = DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.COMBINED)
        val session = drivingRouter.requestRoutes(
            requestPoints,
            DrivingOptions(),
            VehicleOptions(),
            object : DrivingSession.DrivingRouteListener {
                override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
                    val route = routes.firstOrNull()
                    if (route == null) {
                        cont.resumeWith(Result.failure(NoSuchElementException("No route found between these points")))
                        return
                    }
                    val weight = route.metadata.weight
                    cont.resumeWith(
                        Result.success(
                            RouteQueryResult(
                                originLat = origin.latitude,
                                originLng = origin.longitude,
                                destinationLat = destination.latitude,
                                destinationLng = destination.longitude,
                                distanceMeters = weight.distance.value,
                                estimatedDurationSeconds = weight.time.value.toLong()
                            )
                        )
                    )
                }

                override fun onDrivingRoutesError(error: com.yandex.runtime.Error) {
                    cont.resumeWith(Result.failure(IOException("Route request failed - check network/API key")))
                }
            }
        )
        cont.invokeOnCancellation { session.cancel() }
    }
