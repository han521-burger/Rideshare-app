package com.example.ds04_monash_rideshare_application.core.location

import android.content.Context
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine

// Melbourne-wide bounding box, used to bias free-text search (a postcode or suburb name) toward
// this city - without it, a bare postcode like "3168" has no geographic hint and Yandex's
// geocoder may not resolve it to the right country/city at all.
private val MELBOURNE_BOUNDS = BoundingBox(Point(-38.6, 144.4), Point(-37.4, 145.6))

// Resolves a postcode/suburb typed by the driver or passenger to a coordinate via Yandex's
// geocoder (same "full" MapKit SDK/API key already used for driving directions - see
// requestDrivingRoute in DrivingRouteQuery.kt). The caller then snaps this to the nearest of our
// own fixed points (NamedLocations.kt's nearestFixedPoint) rather than using the raw coordinate
// directly, since every pickup/drop-off in this app is one of our known points. Suspends and
// cancels the underlying search if the calling coroutine is cancelled (e.g. superseded by a
// newer query while the driver is still typing).
suspend fun geocodeText(context: Context, query: String): Point = suspendCancellableCoroutine { cont ->
    MapKitFactory.initialize(context)
    val searchManager = SearchFactory.getInstance().createSearchManager(SearchManagerType.ONLINE)
    val options = SearchOptions().setSearchTypes(SearchType.GEO.value).setResultPageSize(1)
    val session = searchManager.submit(
        query,
        Geometry.fromBoundingBox(MELBOURNE_BOUNDS),
        options,
        object : Session.SearchListener {
            override fun onSearchResponse(response: Response) {
                val point = response.collection.children.firstOrNull()?.obj?.geometry?.firstOrNull()?.point
                if (point != null) {
                    cont.resumeWith(Result.success(point))
                } else {
                    cont.resumeWith(Result.failure(NoSuchElementException("No location found for \"$query\"")))
                }
            }

            override fun onSearchError(error: com.yandex.runtime.Error) {
                cont.resumeWith(Result.failure(IOException("Search failed - check network/API key")))
            }
        }
    )
    cont.invokeOnCancellation { session.cancel() }
}
