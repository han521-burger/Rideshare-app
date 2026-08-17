package com.example.ds04_monash_rideshare_application.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Database.Messaging.Message
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import java.text.SimpleDateFormat
import java.util.Locale

// Item 7: basic text messaging tied to a confirmed ride.
@Composable
fun MessagesScreen(
    messages: Resource<List<Message>>,
    currentUserId: String?,
    onSend: (String, onDone: (String?) -> Unit) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }

    RideshareScaffold(
        title = "Messages",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) { contentPadding ->
        Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            when {
                messages.isLoading -> Column(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }

                messages.errorMessage != null -> Text(
                    messages.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )

                else -> {
                    val list = messages.data.orEmpty()
                    if (list.isEmpty()) {
                        Text(
                            "No messages yet. Say hello once your seat is confirmed.",
                            modifier = Modifier.weight(1f).padding(16.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(list, key = { it.messageId }) { message ->
                                MessageBubble(message, isMine = message.senderId == currentUserId)
                            }
                        }
                    }
                }
            }

            sendError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") }
                )
                Button(onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        onSend(text) { error ->
                            sendError = error
                            if (error == null) draft = ""
                        }
                    }
                }) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message, isMine: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(if (isMine) "You" else message.senderId, style = MaterialTheme.typography.labelMedium)
            Text(message.body, style = MaterialTheme.typography.bodyLarge)
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(message.sentAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
