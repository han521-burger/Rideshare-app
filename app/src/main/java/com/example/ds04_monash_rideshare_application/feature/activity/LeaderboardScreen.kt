package com.example.ds04_monash_rideshare_application.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.LeaderboardEntry
import com.example.ds04_monash_rideshare_application.Repository.Remote.LeaderboardResult
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.FormBanner
import com.example.ds04_monash_rideshare_application.core.ui.FormBannerKind
import com.example.ds04_monash_rideshare_application.core.ui.FormInfoRow
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold

@Composable
fun LeaderboardScreen(
    leaderboard: Resource<LeaderboardResult>,
    currentUserId: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    RideshareScaffold(title = "Leaderboard", canNavigateBack = true, onNavigateBack = onNavigateBack, modifier = modifier) { contentPadding ->
        when {
            leaderboard.isLoading -> Column(
                modifier = Modifier.padding(contentPadding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            leaderboard.errorMessage != null -> Column(Modifier.padding(contentPadding).padding(16.dp)) {
                FormBanner(leaderboard.errorMessage, FormBannerKind.Error, title = "Couldn’t load the leaderboard")
            }

            else -> {
                val result = leaderboard.data
                LazyColumn(
                    modifier = Modifier.padding(contentPadding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Top CO2 savers this term", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Estimated CO2 avoided by drivers, from completed shared trips.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (result?.top.isNullOrEmpty()) {
                        item { FormInfoRow("No completed shared trips yet - the leaderboard fills up as rides finish.") }
                    }
                    items(result?.top.orEmpty(), key = { it.userId }) { entry ->
                        LeaderboardRow(entry, isMe = entry.userId == currentUserId)
                    }
                    val me = result?.me
                    if (me != null && result.top.none { it.userId == currentUserId }) {
                        item {
                            Text("Your rank", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                        }
                        item { LeaderboardRow(me, isMe = true) }
                    }
                }
            }
        }
    }
}

// Top three get a filled rank badge; your own row is tinted and outlined in the role colour.
@Composable
private fun LeaderboardRow(entry: LeaderboardEntry, isMe: Boolean) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val podium = entry.rank <= 3
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape)
            .background(if (isMe) colors.primaryContainer else colors.surface)
            .border(if (isMe) 1.5.dp else 1.dp, if (isMe) colors.primary else colors.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape)
                .background(if (podium) colors.primary else colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("${entry.rank}", fontWeight = FontWeight.Bold, color = if (podium) colors.onPrimary else colors.onSurface)
        }
        Text(
            "${entry.displayName}${if (isMe) " (you)" else ""}",
            Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
            color = if (isMe) colors.onPrimaryContainer else colors.onSurface
        )
        Text("%.1f kg".format(entry.totalCo2AvoidedKg), fontWeight = FontWeight.Bold,
            color = if (isMe) colors.onPrimaryContainer else colors.primary)
    }
}
