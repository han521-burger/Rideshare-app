package com.example.ds04_monash_rideshare_application.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.*
import com.example.ds04_monash_rideshare_application.core.state.Resource

@Composable
fun ActivityScreen(
    currentUserId: String?,
    dashboard: Resource<DashboardSummary>,
    analytics: Resource<AnalyticsSummary>,
    tripHistory: Resource<List<RideSummary>>,
    onOpenLeaderboard: () -> Unit,
    onOpenRide: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRetryDashboard: (() -> Unit)? = null,
    onRetryAnalytics: (() -> Unit)? = null
) {
    // Deliberately local to history; never feeds impact, analytics or operational app mode.
    var historyFilter by rememberSaveable(currentUserId) { mutableStateOf(HistoryFilter.ALL) }
    ActivityTheme {
        LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("activity-list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "impact") {
                when {
                    dashboard.isLoading -> LoadingCard("Loading your impact")
                    dashboard.errorMessage != null -> UnavailableCard("Impact temporarily unavailable", onRetryDashboard)
                    dashboard.data != null -> ImpactHero(dashboard.data, onOpenLeaderboard)
                    else -> UnavailableCard("Impact temporarily unavailable", onRetryDashboard)
                }
            }
            when {
                analytics.isLoading -> item(key = "analytics-state") { LoadingCard("Loading analytics") }
                analytics.errorMessage != null || analytics.data == null -> item(key = "analytics-state") {
                    UnavailableCard("Analytics temporarily unavailable", onRetryAnalytics)
                }
                else -> {
                    val data = analytics.data
                    item(key = "driving") { OutcomeCard(data.driverFunnel, driving = true, data.averageDriverResponseMinutes) }
                    item(key = "riding") { OutcomeCard(data.passengerFunnel, driving = false) }
                    item(key = "locations") { LocationCard(data.hotzones) }
                    item(key = "weekday") { WeekdayCard(data.tripsByDayOfWeek) }
                    item(key = "route") { RouteInsight(data.mostUsedRoute) }
                    item(key = "occupancy") { OccupancyCard(data.averageSeatFillRatePercent) }
                }
            }
            item(key = "history-filter") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ride history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() })
                    ActivitySegments(HistoryFilter.entries.map { it.label }, historyFilter.ordinal,
                        onSelect = { historyFilter = HistoryFilter.entries[it] })
                }
            }
            if (tripHistory.isLoading) item(key = "history-loading") { LoadingCard("Loading ride history") }
            if (tripHistory.errorMessage != null) item(key = "history-error") {
                SupportingText("Ride history could not refresh. Please try opening Activity again.")
            }
            val trips = filteredHistory(tripHistory.data.orEmpty(), currentUserId, historyFilter)
            if (trips.isEmpty() && !tripHistory.isLoading && tripHistory.errorMessage == null) {
                item(key = "history-empty") {
                    InsightCard("More trip data needed") {
                        SupportingText(when (historyFilter) {
                            HistoryFilter.ALL -> "Your completed trips will appear here."
                            HistoryFilter.DRIVING -> "No completed trips as a driver yet."
                            HistoryFilter.RIDING -> "No completed trips as a passenger yet."
                        })
                    }
                }
            }
            items(trips, key = { "history-${it.rideId}" }) { trip ->
                HistoryRow(trip, trip.driverId == currentUserId, onClick = { onOpenRide(trip.rideId) })
            }
        }
    }
}
