package com.example.ds04_monash_rideshare_application.feature.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.MessageSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.FormBanner
import com.example.ds04_monash_rideshare_application.core.ui.FormBannerKind
import com.example.ds04_monash_rideshare_application.core.ui.FormInfoRow
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The Chat overlay from the ride-detail spec: basic text messaging tied to a ride once a seat's
// confirmed. Opened from RideDetailScreen for both the driver and the passengers on that ride.
@Composable
fun MessagesScreen(
    messages: Resource<List<MessageSummary>>,
    currentUserId: String?,
    onSend: (String, onDone: (String?) -> Unit) -> Unit,
    onRefresh: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val list = messages.data.orEmpty()

    // Open on, and follow, the newest message.
    LaunchedEffect(list.size) { if (list.isNotEmpty()) listState.animateScrollToItem(list.lastIndex) }

    RideshareScaffold(
        title = "Messages",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        actionLabel = "Refresh",
        onAction = onRefresh,
        modifier = modifier
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    messages.isLoading && list.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    messages.errorMessage != null && list.isEmpty() -> Column(Modifier.padding(16.dp)) {
                        FormBanner(messages.errorMessage, FormBannerKind.Error, title = "Couldn’t load messages")
                    }

                    list.isEmpty() -> Column(Modifier.padding(16.dp)) {
                        FormInfoRow("No messages yet. Say hello and agree where to meet.")
                    }

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(list, key = { it.messageId }) { message ->
                            MessageBubble(message, isMine = message.senderId == currentUserId)
                        }
                    }
                }
            }

            Composer(
                draft = draft,
                onDraftChange = { draft = it; sendError = null },
                sending = sending,
                error = sendError,
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty() && !sending) {
                        sending = true
                        onSend(text) { error ->
                            sending = false
                            sendError = error
                            if (error == null) draft = ""
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun Composer(draft: String, onDraftChange: (String) -> Unit, sending: Boolean, error: String?, onSend: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.navigationBarsPadding().imePadding(), color = colors.surface) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { FormBanner(it, FormBannerKind.Error) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )
                    FilledIconButton(
                        onClick = onSend,
                        enabled = draft.isNotBlank() && !sending,
                        modifier = Modifier.size(52.dp)
                    ) {
                        if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                        else Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send message")
                    }
                }
            }
        }
    }
}

// Mine: right-aligned, role colour. Theirs: left-aligned, outlined, with the sender's name.
@Composable
private fun MessageBubble(message: MessageSummary, isMine: Boolean) {
    val colors = MaterialTheme.colorScheme
    val shape = if (isMine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .background(if (isMine) colors.primaryContainer else colors.surface)
                .then(if (isMine) Modifier else Modifier.border(1.dp, colors.outlineVariant, shape))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (!isMine) {
                Text(message.senderName ?: "Rider", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = colors.primary)
            }
            Text(message.body, style = MaterialTheme.typography.bodyLarge,
                color = if (isMine) colors.onPrimaryContainer else colors.onSurface)
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.sentAt)),
                modifier = Modifier.align(Alignment.End),
                style = MaterialTheme.typography.labelSmall,
                color = if (isMine) colors.onPrimaryContainer.copy(alpha = .75f) else colors.onSurfaceVariant
            )
        }
    }
}
