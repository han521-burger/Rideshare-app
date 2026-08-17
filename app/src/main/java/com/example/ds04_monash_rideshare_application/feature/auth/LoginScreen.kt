package com.example.ds04_monash_rideshare_application.feature.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ds04_monash_rideshare_application.Auth.TEST_IDENTITIES
import com.example.ds04_monash_rideshare_application.Auth.TestIdentity
import com.example.ds04_monash_rideshare_application.R
import com.example.ds04_monash_rideshare_application.core.state.RideshareUiState

private val MonashBlue = Color(0xFF006DAE)
private val PageBackground = Color(0xFFF4F4F5)
private val PrimaryText = Color(0xFF242424)
private val SecondaryText = Color(0xFF66666E)
private val FieldBorder = Color(0xFF85858F)
private val ErrorRed = Color(0xFFC7463D)

internal const val BLANK_EMAIL_ERROR = "This field cannot be left blank"
internal const val INVALID_EMAIL_ERROR = "Enter a valid Monash email address"

@Composable
fun LoginScreen(
    uiState: RideshareUiState,
    onSignIn: (String) -> Unit,
    onSignInAsTestUser: (TestIdentity) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var email by rememberSaveable { mutableStateOf("") }
    var keepSignedIn by rememberSaveable { mutableStateOf(false) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    var helpMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val submitEmail = {
        val error = validateMonashEmail(email)
        validationError = error
        helpMessage = null
        if (error == null) {
            onSignIn(email.trim())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBackground)
            .imePadding()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        ConnectionHeader()
        HorizontalDivider(color = Color(0xFFD9D9DD))
        MonashLogo()
        HorizontalDivider(color = Color(0xFFD9D9DD))

        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Sign in",
                color = PrimaryText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(36.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Email address",
                    color = PrimaryText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Sign in with your Monash email address",
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                    color = SecondaryText,
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { value ->
                        email = value
                        validationError = null
                        helpMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isSigningIn,
                    singleLine = true,
                    isError = validationError != null,
                    shape = RoundedCornerShape(4.dp),
                    textStyle = TextStyle(color = PrimaryText, fontSize = 17.sp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { submitEmail() })
                )

                validationError?.let { message ->
                    LoginError(message = message)
                }
                uiState.errorMessage?.let { message ->
                    LoginError(message = message)
                }

                Row(
                    modifier = Modifier.padding(top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = keepSignedIn,
                        onCheckedChange = { keepSignedIn = it },
                        enabled = !uiState.isSigningIn,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MonashBlue,
                            uncheckedColor = FieldBorder
                        )
                    )
                    Text(
                        text = "Keep me signed in",
                        color = SecondaryText,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Button(
                    onClick = submitEmail,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 30.dp)
                        .height(56.dp),
                    enabled = !uiState.isSigningIn,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MonashBlue,
                        contentColor = Color.White
                    )
                ) {
                    if (uiState.isSigningIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(text = "Next", fontSize = 18.sp)
                    }
                }

                LoginLink(
                    text = "Can't login?",
                    onClick = {
                        helpMessage = "Account recovery will be available through Monash SSO."
                    }
                )

                OrDivider()

                Text(
                    text = "Trial: sign in as a seeded test account",
                    color = SecondaryText,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Real Monash SSO isn't available yet. Each of these is pre-populated " +
                        "with rides/requests/messages - sign in as a different one to see the other " +
                        "side of a request (e.g. accept, as Amara, a request you made as Test Student).",
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    color = SecondaryText,
                    style = MaterialTheme.typography.bodySmall
                )
                TEST_IDENTITIES.forEach { identity ->
                    OutlinedButton(
                        onClick = {
                            validationError = null
                            helpMessage = null
                            onSignInAsTestUser(identity)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .height(56.dp),
                        enabled = !uiState.isSigningIn,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(2.dp, MonashBlue),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MonashBlue)
                    ) {
                        Text(
                            text = "${identity.displayName} (${identity.roleHint})",
                            fontSize = 16.sp
                        )
                    }
                }

                LoginLink(
                    text = "Unlock account?",
                    onClick = {
                        helpMessage = "Account unlocking will be handled by the Monash service desk."
                    }
                )

                helpMessage?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(top = 8.dp),
                        color = SecondaryText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Connecting to Monash University",
            color = SecondaryText,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Sign in with your account to access Monash Rideshare",
            modifier = Modifier.padding(top = 10.dp),
            color = SecondaryText,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MonashLogo() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .background(Color.White)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.monash_logo),
            contentDescription = "Monash University",
            modifier = Modifier
                .width(170.dp)
                .height(58.dp),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun LoginError(message: String) {
    Row(
        modifier = Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(21.dp)
                .background(ErrorRed, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "!",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = message,
            color = ErrorRed,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun LoginLink(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.padding(top = 22.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = SecondaryText)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = TextDecoration.Underline
        )
    }
}

@Composable
private fun OrDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = Color(0xFFD2D2D6)
        )
        Text(
            text = "OR",
            color = SecondaryText,
            style = MaterialTheme.typography.bodyLarge
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = Color(0xFFD2D2D6)
        )
    }
}

internal fun validateMonashEmail(value: String): String? {
    val email = value.trim()
    if (email.isBlank()) return BLANK_EMAIL_ERROR

    val validFormat = email.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
    val validDomain = email.endsWith("@monash.edu", ignoreCase = true) ||
        email.endsWith("@student.monash.edu", ignoreCase = true)
    return if (validFormat && validDomain) null else INVALID_EMAIL_ERROR
}
