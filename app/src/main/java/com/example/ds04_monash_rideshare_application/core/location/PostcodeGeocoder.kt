package com.example.ds04_monash_rideshare_application.core.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.example.ds04_monash_rideshare_application.BuildConfig
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.*
import com.yandex.runtime.network.NetworkError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val MELBOURNE_BOUNDS = BoundingBox(Point(-38.6, 144.4), Point(-37.4, 145.6))
private const val SEARCH_TIMEOUT_MS = 20_000L
private const val NO_RESULT = "No matching postcode or suburb found."
private const val UNAVAILABLE = "Location search is currently unavailable. Please try again."
private const val NETWORK = "Unable to connect. Check your internet connection."

private fun inServiceArea(point: Point) = point.latitude in -38.6..-37.4 && point.longitude in 144.4..145.6

// Numeric Australian postcodes use Android's geocoder; place text retains the existing Yandex search.
// Both return a ranking origin only. CampusRoutePicker offers canonical approved points afterwards.
suspend fun geocodeText(context: Context, query: String): Point {
    val text = query.trim()
    try {
        return withTimeout(SEARCH_TIMEOUT_MS) {
            when (locationInputKind(text)) {
                LocationInputKind.EMPTY -> throw IOException("Enter a postcode or suburb.")
                LocationInputKind.POSTCODE -> geocodePostcode(context, text)
                LocationInputKind.PLACE -> {
                    if (BuildConfig.YANDEX_MAPKIT_API_KEY.isBlank()) throw IOException(UNAVAILABLE)
                    suspendCancellableCoroutine { continuation ->
                        val request = PlaceSearch(continuation)
                        continuation.invokeOnCancellation { request.cancel() }
                        request.start(text)
                    }
                }
            }
        }
    } catch (error: TimeoutCancellationException) {
        Log.w("LocationSearch", "Lookup timed out")
        throw IOException(UNAVAILABLE)
    }
}

private suspend fun geocodePostcode(context: Context, postcode: String): Point {
    if (!Geocoder.isPresent()) throw IOException(UNAVAILABLE)
    val geocoder = Geocoder(context, Locale("en", "AU"))
    val query = "$postcode, Victoria, Australia"
    val addresses: List<Address> = if (Build.VERSION.SDK_INT >= 33) {
        suspendCancellableCoroutine { continuation ->
            geocoder.getFromLocationName(query, 5, -38.6, 144.4, -37.4, 145.6,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses)
                    }
                    override fun onError(errorMessage: String?) {
                        Log.w("LocationSearch", "Platform postcode lookup unavailable")
                        if (continuation.isActive) continuation.resumeWithException(IOException(UNAVAILABLE))
                    }
                })
        }
    } else {
        withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 5, -38.6, 144.4, -37.4, 145.6).orEmpty()
            } catch (error: IOException) { throw IOException(NETWORK) }
        }
    }
    val match = addresses.firstOrNull {
        it.hasLatitude() && it.hasLongitude() && it.countryCode.equals("AU", ignoreCase = true) &&
            (it.postalCode == null || it.postalCode == postcode) && inServiceArea(Point(it.latitude, it.longitude))
    } ?: throw IOException(NO_RESULT)
    Log.d("LocationSearch", "Platform postcode lookup resolved")
    return Point(match.latitude, match.longitude)
}

// Keep the manager, session and listener strongly reachable while the continuation is pending.
// MapKit retains listeners weakly; inline listeners alone can disappear during a request.
private class PlaceSearch(private val continuation: CancellableContinuation<Point>) {
    private val manager = SearchFactory.getInstance().createSearchManager(SearchManagerType.ONLINE)
    private var session: Session? = null
    private val listener = object : Session.SearchListener {
        override fun onSearchResponse(response: Response) {
            val point = response.collection.children.asSequence().mapNotNull {
                it.obj?.geometry?.firstOrNull()?.point
            }.firstOrNull(::inServiceArea)
            if (!continuation.isActive) return
            if (point == null) continuation.resumeWithException(IOException(NO_RESULT))
            else { Log.d("LocationSearch", "Yandex place lookup resolved"); continuation.resume(point) }
        }
        override fun onSearchError(error: com.yandex.runtime.Error) {
            Log.w("LocationSearch", "Yandex search failed: ${error.javaClass.simpleName}")
            if (continuation.isActive) continuation.resumeWithException(IOException(if (error is NetworkError) NETWORK else UNAVAILABLE))
        }
    }
    fun start(text: String) {
        session = manager.submit(text, Geometry.fromBoundingBox(MELBOURNE_BOUNDS),
            SearchOptions().setSearchTypes(SearchType.GEO.value).setResultPageSize(5), listener)
    }
    fun cancel() { session?.cancel() }
}
