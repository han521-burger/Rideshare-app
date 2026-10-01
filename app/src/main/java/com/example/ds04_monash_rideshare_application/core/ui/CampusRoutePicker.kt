package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.location.*
import com.example.ds04_monash_rideshare_application.ui.MONASH_CAMPUSES
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import com.example.ds04_monash_rideshare_application.ui.RouteQueryResult
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

data class RoutePickerResult(
    val origin: NamedLocation,
    val destination: NamedLocation,
    val distanceMeters: Double,
    val estimatedDurationSeconds: Long
)

@Composable
fun CampusRoutePicker(onResult: (RoutePickerResult?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var fromMonash by remember { mutableStateOf(true) }
    var selectedCampus by remember { mutableStateOf<NamedLocation?>(null) }
    var query by remember { mutableStateOf("") }
    var queryLocation by remember { mutableStateOf<Point?>(null) }
    var selectedPoint by remember { mutableStateOf<NamedLocation?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchRequestId by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var routeResult by remember { mutableStateOf<RouteQueryResult?>(null) }
    var routing by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var routeAttempt by remember { mutableIntStateOf(0) }
    val campus = selectedCampus
    val approved = selectedPoint
    val choices = queryLocation?.let { nearestApprovedPoints(it.latitude, it.longitude, excluded = campus) }.orEmpty()
    val endpoints = if (campus != null && approved != null) approvedRouteEndpoints(campus, approved, fromMonash) else null
    // Never publish an old route with newly selected endpoints while a replacement request starts.
    val route = routeResult?.takeIf { result -> endpoints?.let { (origin, destination) ->
        result.originLat == origin.point.latitude && result.originLng == origin.point.longitude &&
            result.destinationLat == destination.point.latitude && result.destinationLng == destination.point.longitude
    } == true }

    LaunchedEffect(campus, approved, fromMonash, routeAttempt) {
        routeResult = null; routeError = null; routing = false
        if (endpoints != null) {
            routing = true
            try {
                routeResult = requestDrivingRoute(context, endpoints.first.point, endpoints.second.point)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { routeError = error.message ?: "Unable to calculate this route. Please try again." }
            finally { routing = false }
        }
    }
    LaunchedEffect(endpoints, route) {
        onResult(if (endpoints != null && route != null)
            RoutePickerResult(endpoints.first, endpoints.second, route.distanceMeters, route.estimatedDurationSeconds)
        else null)
    }

    Column(modifier) {
        Text("Which Monash campus?", fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(MONASH_CAMPUSES, key = { it.name }) { location ->
                FilterChip(selected = campus == location, onClick = {
                    if (selectedCampus != location) { onResult(null); selectedCampus = location; selectedPoint = null }
                }, label = { Text(location.name) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = fromMonash, onClick = { if (!fromMonash) { onResult(null); fromMonash = true } },
                label = { Text("From Monash") }, modifier = Modifier.weight(1f))
            FilterChip(selected = !fromMonash, onClick = { if (fromMonash) { onResult(null); fromMonash = false } },
                label = { Text("To Monash") }, modifier = Modifier.weight(1f))
        }
        Text(if (fromMonash) "Find an approved drop-off near a postcode or suburb."
            else "Find an approved pickup near a postcode or suburb.",
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = query, onValueChange = {
                searchRequestId++; searchJob?.cancel(); searching = false; query = it; queryLocation = null
                selectedPoint = null; routeResult = null; searchError = null; onResult(null)
            }, label = { Text("Postcode or suburb") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(enabled = !searching, onClick = {
                focus.clearFocus(); val requestId = ++searchRequestId; searchJob?.cancel(); queryLocation = null; selectedPoint = null
                routeResult = null; searchError = null; onResult(null)
                if (query.isBlank()) searchError = "Enter a postcode or suburb."
                else {
                    val submitted = query.trim()
                    searchJob = scope.launch {
                        searching = true
                        try {
                            queryLocation = geocodeText(context, submitted)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { searchError = error.message ?: "Location search is currently unavailable. Please try again." }
                        finally { if (requestId == searchRequestId) searching = false }
                    }
                }
            }) { if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Find") }
        }
        searchError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        if (queryLocation != null) {
            Text(if (fromMonash) "Nearest drop-off points" else "Nearest pickup points",
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Text("Choose an approved point. Distances are approximate, straight-line from your search location.",
                style = MaterialTheme.typography.bodySmall)
            choices.forEach { choice ->
                FilterChip(selected = approved == choice.location, onClick = {
                    if (selectedPoint != choice.location) { onResult(null); selectedPoint = choice.location }
                }, label = {
                    Text("${choice.location.name} ~ ${String.format(Locale.getDefault(), "%.1f", choice.distanceMeters / 1000)} km")
                }, modifier = Modifier.fillMaxWidth())
            }
            if (choices.isEmpty()) Text("No approved points available for this selection.")
        }
        if (routing) Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp)); Text("Calculating route...")
        }
        routeError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { routeAttempt++ }) { Text("Retry route") }
        }
        if (endpoints != null && route != null) {
            val (origin, destination) = endpoints
            RouteMiniMap(originLat = origin.point.latitude, originLng = origin.point.longitude,
                destinationLat = destination.point.latitude, destinationLng = destination.point.longitude,
                modifier = Modifier.padding(top = 16.dp))
            Text("${origin.name} → ${destination.name}  •  %.1f km  •  %d min".format(
                route.distanceMeters / 1000, route.estimatedDurationSeconds / 60),
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
