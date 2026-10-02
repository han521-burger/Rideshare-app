package com.example.ds04_monash_rideshare_application.feature.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.*
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.rideStatusLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun InsightCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
internal fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ImpactHero(data: DashboardSummary, onLeaderboard: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().testTag("impact"), shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, colors.primaryContainer), colors = CardDefaults.cardColors(containerColor = colors.surface)) {
        Box(Modifier.background(Brush.linearGradient(listOf(colors.background, colors.primaryContainer)))) {
            Canvas(Modifier.align(Alignment.TopEnd).size(136.dp).clearAndSetSemantics {}) {
                val leaf = Path().apply {
                    moveTo(size.width * .14f, size.height * .8f)
                    cubicTo(size.width * .25f, 0f, size.width, 0f, size.width, 0f)
                    cubicTo(size.width * .85f, size.height * .7f, size.width * .5f, size.height * .7f, size.width * .14f, size.height * .8f)
                }
                drawPath(leaf, colors.primary.copy(alpha = .12f), style = Stroke(1.5.dp.toPx()))
                drawLine(colors.primary.copy(alpha = .12f), Offset(size.width * .12f, size.height), Offset(size.width, 0f), 1.5.dp.toPx())
            }
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Eco, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    Text("Your impact", fontWeight = FontWeight.SemiBold, color = colors.primary, modifier = Modifier.semantics { heading() })
                }
                Column {
                    Text("Estimated CO₂ avoided", style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
                    Text("${decimal(data.totalCo2AvoidedKg)} kg", style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.testTag("impact-co2"))
                    Text("From completed trips you drove", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
                }
                HorizontalDivider(color = colors.primary.copy(alpha = .2f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        SupportingText("Trips shared")
                        Text(data.totalRidesCompleted.toString(), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("impact-trips"))
                    }
                    Column(Modifier.weight(1f)) {
                        SupportingText("Fuel saved")
                        Text("${decimal(data.totalFuelSavedLitres)} L", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, modifier = Modifier.testTag("impact-fuel"))
                    }
                }
                TextButton(onClick = onLeaderboard, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                    Text("View leaderboard")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
internal fun OutcomeCard(counts: RequestFunnelCounts, driving: Boolean, responseMinutes: Double? = null) {
    val outcomes = outcomeDistribution(counts)
    val colors = outcomeColors()
    val scheme = MaterialTheme.colorScheme
    InsightCard(if (driving) "Driving insights" else "Riding insights") {
        Surface(color = if (driving) scheme.primaryContainer else scheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
            Text(if (driving) "As a driver" else "As a passenger", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = if (driving) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelMedium)
        }
        Text("${counts.total} ${if (counts.total == 1) "request" else "requests"} ${if (driving) "received" else "sent"}", fontWeight = FontWeight.SemiBold)
        if (counts.total == 0) {
            SupportingText("No requests yet. Outcomes will appear here as you share rides.")
        } else if (driving) {
            Canvas(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).semantics {
                contentDescription = "Request outcomes: " + outcomes.joinToString { "${it.label}: ${it.count}" }
            }) {
                var x = 0f
                outcomes.forEachIndexed { index, outcome ->
                    val width = size.width * outcome.fraction
                    if (width > 0f) drawRect(colors[index], Offset(x, 0f), Size(width, size.height))
                    x += width
                }
            }
        } else {
            val completed = outcomes.first { it.label == "Completed" }.fraction
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Canvas(Modifier.size(80.dp).semantics { contentDescription = "${decimal(completed * 100.0)} percent of sent requests completed" }) {
                    val stroke = 9.dp.toPx()
                    val inset = Offset(stroke / 2, stroke / 2)
                    val bounds = Size(size.width - stroke, size.height - stroke)
                    drawArc(scheme.surfaceVariant, 0f, 360f, false, inset, bounds, style = Stroke(stroke))
                    if (completed > 0f) drawArc(scheme.secondary, -90f, 360f * completed, false, inset, bounds, style = Stroke(stroke))
                }
                Column(Modifier.weight(1f)) {
                    Text("${decimal(completed * 100.0)}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    SupportingText("of sent requests completed")
                }
            }
        }
        outcomes.forEachIndexed { index, outcome ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(colors[index], RoundedCornerShape(2.dp)))
                Text(outcome.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Text(outcome.count.toString(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
        if (driving) {
            HorizontalDivider(color = scheme.outlineVariant)
            SupportingText(responseTimeLabel(responseMinutes)?.let { "Average first response: $it" }
                ?: "More decision data needed for response time.")
        }
    }
}

@Composable
internal fun LocationCard(locations: List<HotzoneEntry>) {
    InsightCard("Top ride locations") {
        SupportingText("Origins and destinations from rides created in the last 7 days.")
        if (locations.isEmpty()) SupportingText("More recent ride data needed.")
        val max = locations.maxOfOrNull { it.count } ?: 0
        locations.forEachIndexed { index, place ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Text((index + 1).toString().padStart(2, '0'), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.weight(1f)) {
                        Text(place.locationName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        SupportingText("${place.count} ride ${if (place.count == 1) "endpoint" else "endpoints"}")
                    }
                }
                MetricTrack(magnitude(place.count, max), "${place.locationName}: ${place.count} ride ${if (place.count == 1) "endpoint" else "endpoints"}")
            }
        }
    }
}

@Composable
internal fun WeekdayCard(counts: List<Int>) {
    val week = weekdayDistribution(counts)
    val compactDayLabels = LocalDensity.current.fontScale >= 1.5f
    InsightCard("Completed trips by weekday") {
        SupportingText("Completed trips as a driver or passenger")
        if (week.all { it.count == 0 }) SupportingText("More trip data needed.")
        val accent = MaterialTheme.colorScheme.primary
        val track = MaterialTheme.colorScheme.surfaceVariant
        // Canvas labels remain text in individual columns; each day has one TalkBack summary.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            week.forEach { day ->
                Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "${day.label}: ${day.count} completed trips" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(day.count.toString(), style = MaterialTheme.typography.labelSmall)
                    Canvas(Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(6.dp))) {
                        drawRect(track)
                        val height = size.height * day.fraction
                        if (height > 0f) drawRect(accent, Offset(0f, size.height - height), Size(size.width, height))
                    }
                    Text(if (compactDayLabels) day.label.take(2) else day.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun RouteInsight(route: RouteFrequency?) {
    InsightCard("Most-used route") {
        if (route == null) SupportingText("More trip data needed to identify a frequent route.")
        else {
            Column(Modifier.drawTimeline(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.padding(start = 24.dp)) {
                    SupportingText("Origin")
                    Text(route.originLabel, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.padding(start = 24.dp)) {
                    SupportingText("Destination")
                    Text(route.destinationLabel, fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SupportingText("${route.count} completed ${if (route.count == 1) "trip" else "trips"} on this route")
        }
    }
}

@Composable
private fun Modifier.drawTimeline(): Modifier {
    val color = MaterialTheme.colorScheme.primary
    return this.then(Modifier.padding(vertical = 4.dp)).then(Modifier.background(MaterialTheme.colorScheme.surface))
        .then(Modifier.drawBehindTimeline(color))
}

private fun Modifier.drawBehindTimeline(color: androidx.compose.ui.graphics.Color) = this.then(
    Modifier.drawBehind {
        val x = 5.dp.toPx(); val top = 10.dp.toPx(); val bottom = size.height - 12.dp.toPx()
        drawLine(color.copy(alpha = .4f), Offset(x, top), Offset(x, bottom), 2.dp.toPx())
        drawCircle(color, 4.dp.toPx(), Offset(x, top), style = Stroke(2.dp.toPx()))
        drawCircle(color, 4.dp.toPx(), Offset(x, bottom))
    }
)

@Composable
internal fun OccupancyCard(percent: Double?) {
    InsightCard("Average passenger occupancy") {
        SupportingText("From completed trips you drove")
        if (percent == null) SupportingText("More trip data needed for occupancy.")
        else {
            Text("${decimal(percent)}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            MetricTrack((percent / 100).toFloat().coerceIn(0f, 1f), "Average passenger occupancy: ${decimal(percent)} percent")
            SupportingText("Average share of offered passenger seats filled")
        }
    }
}

@Composable
private fun MetricTrack(fraction: Float, description: String) {
    val color = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).semantics { contentDescription = description }) {
        drawRect(background)
        if (fraction > 0f) drawRect(color, size = Size(size.width * fraction, size.height))
    }
}

@Composable
internal fun LoadingCard(title: String) {
    InsightCard(title) {
        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = title })
        Box(Modifier.fillMaxWidth(.65f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)))
        Box(Modifier.fillMaxWidth(.9f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)))
    }
}

@Composable
internal fun UnavailableCard(title: String, retry: (() -> Unit)?) {
    InsightCard(title) {
        SupportingText("Please try again. Available ride history is still shown below.")
        retry?.let { TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") } }
    }
}

@Composable
internal fun HistoryRow(trip: RideSummary, driving: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("history-${trip.rideId}"), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface), border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = RoundedCornerShape(12.dp), color = if (driving) colors.primaryContainer else colors.secondaryContainer) {
                Icon(if (driving) Icons.Rounded.DirectionsCar else Icons.Rounded.Person, null,
                    Modifier.padding(10.dp).size(20.dp), tint = if (driving) colors.onPrimaryContainer else colors.onSecondaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${describeLocation(trip.originLat, trip.originLng)} → ${describeLocation(trip.destinationLat, trip.destinationLng)}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(if (driving) "Drove" else "Rode", style = MaterialTheme.typography.labelMedium,
                    color = if (driving) colors.primary else colors.secondary)
                SupportingText(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(trip.departureTime)))
                SupportingText(rideStatusLabel(trip.status))
            }
        }
    }
}
