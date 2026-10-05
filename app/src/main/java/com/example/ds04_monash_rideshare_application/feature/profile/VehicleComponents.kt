package com.example.ds04_monash_rideshare_application.feature.profile

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ds04_monash_rideshare_application.core.ui.rememberLargeText

// Structured vehicle summary shared by the Profile "My vehicle" card and the read-only Vehicles
// screen. It renders only fields the app already has; nothing is invented.
@Composable
internal fun VehicleFactsBlock(facts: VehicleFacts, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(facts.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            facts.plate,
            modifier = Modifier.border(1.5.dp, colors.outline, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .semantics { contentDescription = "Plate ${facts.plate}" },
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp),
            fontWeight = FontWeight.Bold
        )
        HorizontalDivider(Modifier.padding(top = 4.dp), color = colors.outlineVariant)
        val cells = listOf(
            Triple("Type", facts.type, null), Triple("Colour", facts.colour, null),
            Triple("Seats", facts.seatsTotal, facts.seatsForPassengers), Triple("Fuel", facts.fuel, null)
        )
        val perRow = if (rememberLargeText()) 1 else 2
        cells.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { (label, value, sub) ->
                    Column(Modifier.weight(1f)) {
                        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                            color = colors.onSurfaceVariant, letterSpacing = 1.sp)
                        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
