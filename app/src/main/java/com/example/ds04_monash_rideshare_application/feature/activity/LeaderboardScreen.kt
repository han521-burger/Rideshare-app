package com.example.ds04_monash_rideshare_application.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.LeaderboardEntry
import com.example.ds04_monash_rideshare_application.Repository.Remote.LeaderboardResult
import com.example.ds04_monash_rideshare_application.core.state.Resource
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

            leaderboard.errorMessage != null -> Text(
                leaderboard.errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(contentPadding).padding(16.dp)
            )

            else -> {
                val result = leaderboard.data
                LazyColumn(
                    modifier = Modifier.padding(contentPadding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Text("Top CO2 savers this term", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    items(result?.top.orEmpty(), key = { it.userId }) { entry ->
                        LeaderboardRow(entry, isMe = entry.userId == currentUserId)
                    }
                    val me = result?.me
                    if (me != null && result.top.none { it.userId == currentUserId }) {
                        item { Text("Your rank", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                        item { LeaderboardRow(me, isMe = true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardRow(entry: LeaderboardEntry, isMe: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("#${entry.rank}  ${entry.displayName}${if (isMe) " (you)" else ""}", fontWeight = FontWeight.SemiBold)
            Text("%.1f kg CO2".format(entry.totalCo2AvoidedKg), color = MaterialTheme.colorScheme.primary)
        }
    }
}
