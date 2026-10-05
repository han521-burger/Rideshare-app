package com.example.ds04_monash_rideshare_application.feature.auth

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
internal const val BLANK_NAME_ERROR = "Enter your name"
internal const val BLANK_PASSWORD_ERROR = "Enter your password"
internal const val SHORT_PASSWORD_ERROR = "Password must be at least 8 characters"
internal const val PASSWORD_MISMATCH_ERROR = "Passwords don't match"
private const val MIN_PASSWORD_LENGTH = 8

// Email + password sign-in, with a "create account" mode for new users. Monash emails only -
// the backend enforces the same rules (backend routes/AuthRoutes.kt).
@Composable
fun LoginScreen(
    uiState: RideshareUiState,
    onSignIn: (email: String, password: String, keepSignedIn: Boolean) -> Unit,
    onSignUp: (name: String, email: String, password: String, keepSignedIn: Boolean) -> Unit,
    onClearServerError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var creatingAccount by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords are deliberately not saved across process death.
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var keepSignedIn by rememberSaveable { mutableStateOf(true) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    var helpMessage by rememberSaveable { mutableStateOf<String?>(null) }

    fun edited() {
        validationError = null
        helpMessage = null
        onClearServerError()
    }

    val submit = {
        validationError = validateLoginForm(creatingAccount, name, email, password, confirmPassword)
        helpMessage = null
        if (validationError == null) {
            if (creatingAccount) onSignUp(name.trim(), email.trim(), password, keepSignedIn)
            else onSignIn(email.trim(), password, keepSignedIn)
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
                text = if (creatingAccount) "Create account" else "Sign in",
                color = PrimaryText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(28.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                if (creatingAccount) {
                    FieldLabel("Your name")
                    LoginField(
                        value = name,
                        onValueChange = { name = it; edited() },
                        enabled = !uiState.isSigningIn,
                        isError = validationError == BLANK_NAME_ERROR,
                        keyboardType = KeyboardType.Text
                    )
                    Spacer(Modifier.height(20.dp))
                }

                FieldLabel("Email address", "Your Monash email (@student.monash.edu or @monash.edu)")
                LoginField(
                    value = email,
                    onValueChange = { email = it; edited() },
                    enabled = !uiState.isSigningIn,
                    isError = validationError == BLANK_EMAIL_ERROR || validationError == INVALID_EMAIL_ERROR,
                    keyboardType = KeyboardType.Email
                )
                Spacer(Modifier.height(20.dp))

                FieldLabel("Password", if (creatingAccount) "At least $MIN_PASSWORD_LENGTH characters" else null)
                LoginField(
                    value = password,
                    onValueChange = { password = it; edited() },
                    enabled = !uiState.isSigningIn,
                    isError = validationError in listOf(BLANK_PASSWORD_ERROR, SHORT_PASSWORD_ERROR, PASSWORD_MISMATCH_ERROR),
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                    isLast = !creatingAccount,
                    onDone = submit
                )

                if (creatingAccount) {
                    Spacer(Modifier.height(20.dp))
                    FieldLabel("Confirm password")
                    LoginField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; edited() },
                        enabled = !uiState.isSigningIn,
                        isError = validationError == PASSWORD_MISMATCH_ERROR,
                        keyboardType = KeyboardType.Password,
                        isPassword = true,
                        isLast = true,
                        onDone = submit
                    )
                }

                validationError?.let { message ->
                    LoginError(message = message)
                }
                uiState.errorMessage?.let { message ->
                    LoginError(message = message)
                }

                Row(
                    modifier = Modifier.padding(top = 20.dp),
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
                    onClick = submit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
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
                        Text(text = if (creatingAccount) "Create account" else "Sign in", fontSize = 18.sp)
                    }
                }

                LoginLink(
                    text = if (creatingAccount) "Already have an account? Sign in" else "New here? Create an account",
                    onClick = {
                        creatingAccount = !creatingAccount
                        password = ""
                        confirmPassword = ""
                        edited()
                    }
                )

                if (!creatingAccount) {
                    LoginLink(
                        text = "Forgot password?",
                        onClick = {
                            helpMessage = "Password reset isn't available yet - ask the project team to reset your account."
                        }
                    )
                }

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
private fun FieldLabel(title: String, hint: String? = null) {
    Text(
        text = title,
        color = PrimaryText,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    if (hint != null) {
        Text(
            text = hint,
            modifier = Modifier.padding(top = 6.dp),
            color = SecondaryText,
            style = MaterialTheme.typography.bodyLarge
        )
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
    isLast: Boolean = false,
    onDone: () -> Unit = {}
) {
    var showPassword by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        isError = isError,
        shape = RoundedCornerShape(4.dp),
        textStyle = TextStyle(color = PrimaryText, fontSize = 17.sp),
        visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (isPassword) {
            {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "Hide" else "Show", color = MonashBlue)
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = if (isLast) ImeAction.Done else ImeAction.Next
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() })
    )
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
        modifier = Modifier.padding(top = 18.dp),
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

internal fun validateMonashEmail(value: String): String? {
    val email = value.trim()
    if (email.isBlank()) return BLANK_EMAIL_ERROR

    val validFormat = email.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
    val validDomain = email.endsWith("@monash.edu", ignoreCase = true) ||
        email.endsWith("@student.monash.edu", ignoreCase = true)
    return if (validFormat && validDomain) null else INVALID_EMAIL_ERROR
}

// Same rules as the backend (AuthRoutes.kt), checked here first for instant feedback.
internal fun validateLoginForm(
    creatingAccount: Boolean,
    name: String,
    email: String,
    password: String,
    confirmPassword: String
): String? {
    if (creatingAccount && name.isBlank()) return BLANK_NAME_ERROR
    validateMonashEmail(email)?.let { return it }
    if (password.isEmpty()) return BLANK_PASSWORD_ERROR
    if (creatingAccount) {
        if (password.length < MIN_PASSWORD_LENGTH) return SHORT_PASSWORD_ERROR
        if (password != confirmPassword) return PASSWORD_MISMATCH_ERROR
    }
    return null
}
