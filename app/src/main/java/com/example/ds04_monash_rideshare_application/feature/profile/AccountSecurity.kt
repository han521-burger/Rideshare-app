package com.example.ds04_monash_rideshare_application.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.ui.BusyLabel
import com.example.ds04_monash_rideshare_application.core.ui.FormBanner
import com.example.ds04_monash_rideshare_application.core.ui.FormBannerKind
import com.example.ds04_monash_rideshare_application.core.ui.FormSectionCard

internal const val MIN_NEW_PASSWORD_LENGTH = 8
internal const val PASSWORD_CHANGED_MESSAGE = "Password changed. Any other devices have been signed out."

// Profile's "Security" card: change password and delete account (backend routes/AuthRoutes.kt).
// Both actions report null on success or an error message to show.
@Composable
internal fun AccountSecurityCard(
    onChangePassword: (current: String, new: String, onDone: (String?) -> Unit) -> Unit,
    onDeleteAccount: (password: String, onDone: (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    var showChange by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var changedBanner by remember { mutableStateOf(false) }

    FormSectionCard("Security", modifier.testTag("profile-security")) {
        if (changedBanner) FormBanner(PASSWORD_CHANGED_MESSAGE, FormBannerKind.Success)
        OutlinedButton(
            onClick = { changedBanner = false; showChange = true },
            modifier = Modifier.fillMaxWidth().testTag("profile-change-password")
        ) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp))
            Text("Change password", Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = { showDelete = true },
            modifier = Modifier.fillMaxWidth().testTag("profile-delete-account"),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Rounded.DeleteForever, null, Modifier.size(18.dp))
            Text("Delete account", Modifier.padding(start = 8.dp))
        }
    }

    if (showChange) {
        ChangePasswordDialog(
            onSubmit = onChangePassword,
            onChanged = { showChange = false; changedBanner = true },
            onDismiss = { showChange = false }
        )
    }
    if (showDelete) {
        DeleteAccountDialog(onSubmit = onDeleteAccount, onDismiss = { showDelete = false })
    }
}

@Composable
private fun ChangePasswordDialog(
    onSubmit: (current: String, new: String, onDone: (String?) -> Unit) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(28.dp),
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PasswordField(current, { current = it; error = null }, "Current password", "change-current")
                PasswordField(new, { new = it; error = null }, "New password (at least $MIN_NEW_PASSWORD_LENGTH characters)", "change-new")
                PasswordField(confirm, { confirm = it; error = null }, "Confirm new password", "change-confirm")
                Text(
                    "You'll stay signed in here; other devices will be signed out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { FormBanner(it, FormBannerKind.Error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, modifier = Modifier.testTag("change-submit"), onClick = {
                error = validatePasswordChange(current, new, confirm)
                if (error == null) {
                    busy = true
                    onSubmit(current, new) { result ->
                        busy = false
                        if (result == null) onChanged() else error = result
                    }
                }
            }) {
                if (busy) BusyLabel("Saving", MaterialTheme.colorScheme.primary) else Text("Change password")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DeleteAccountDialog(
    onSubmit: (password: String, onDone: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(28.dp),
        title = { Text("Delete your account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This can't be undone. Your upcoming rides and requests will be cancelled, and your " +
                        "name, email, phone and car plate will be removed. Past trips stay in other people's " +
                        "history as \"Deleted user\"."
                )
                PasswordField(password, { password = it; error = null }, "Your password", "delete-password")
                error?.let { FormBanner(it, FormBannerKind.Error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                modifier = Modifier.testTag("delete-submit"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    if (password.isEmpty()) {
                        error = "Enter your password to confirm"
                    } else {
                        busy = true
                        onSubmit(password) { result ->
                            busy = false
                            // On success the app signs out and leaves this screen.
                            if (result != null) error = result
                        }
                    }
                }
            ) {
                if (busy) BusyLabel("Deleting", MaterialTheme.colorScheme.error) else Text("Delete my account")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Keep my account") } }
    )
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    )
}

// Same rules as the backend's change-password check, checked here first for instant feedback.
internal fun validatePasswordChange(current: String, new: String, confirm: String): String? = when {
    current.isEmpty() -> "Enter your current password"
    new.length < MIN_NEW_PASSWORD_LENGTH -> "New password must be at least $MIN_NEW_PASSWORD_LENGTH characters"
    new == current -> "Choose a different password from your current one"
    new != confirm -> "New passwords don't match"
    else -> null
}
