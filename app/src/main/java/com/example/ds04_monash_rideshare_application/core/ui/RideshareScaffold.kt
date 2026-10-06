package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Shell for pushed detail screens (Ride detail, Request detail, Find a ride, Messages, Leaderboard):
// the same header as the Vehicles screen - back arrow, title, optional text action - over the
// brand background, so these match the redesigned tabs.
@Composable
fun RideshareScaffold(
    title: String,
    modifier: Modifier = Modifier,
    canNavigateBack: Boolean = false,
    onNavigateBack: () -> Unit = {},
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier,
        containerColor = colors.background,
        topBar = {
            Surface(color = colors.surface) {
                Column(Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(start = if (canNavigateBack) 6.dp else 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (canNavigateBack) {
                            IconButton(onClick = onNavigateBack, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.primary)
                            }
                        }
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (actionLabel != null) {
                            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(actionLabel, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }
        },
        content = content
    )
}

// The card used across the detail screens - same shape and outline as the redesigned tabs'
// FormSectionCard, but without a built-in title, so existing card bodies drop straight in.
@Composable
fun DetailCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val cardColors = CardDefaults.cardColors(containerColor = colors.surface, contentColor = colors.onSurface)
    val border = BorderStroke(1.dp, colors.outlineVariant)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = cardColors, border = border, content = content)
    } else {
        Card(modifier = modifier, shape = shape, colors = cardColors, border = border, content = content)
    }
}
