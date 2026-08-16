package com.example.ds04_monash_rideshare_application.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.ds04_monash_rideshare_application.Auth.AuthenticatedUser
import com.example.ds04_monash_rideshare_application.Auth.AuthenticationProvider
import com.example.ds04_monash_rideshare_application.Auth.FakeAuthenticationProvider
import com.example.ds04_monash_rideshare_application.core.model.SessionRole

data class RideshareUiState(
    val isSigningIn: Boolean = false,
    val userId: String? = null,
    val email: String = "",
    val displayName: String = "",
    val preferredName: String = "",
    val activeRole: SessionRole? = null,
    val errorMessage: String? = null
) {
    val isSignedIn: Boolean
        get() = userId != null

    val greetingName: String
        get() = preferredName.ifBlank { displayName.ifBlank { "Monash traveller" } }
}

class RideshareViewModel : ViewModel() {
    private val authenticationProvider: AuthenticationProvider = FakeAuthenticationProvider()

    var uiState by mutableStateOf(RideshareUiState())
        private set

    fun signIn(onSuccess: () -> Unit = {}) {
        if (uiState.isSigningIn) return

        uiState = uiState.copy(isSigningIn = true, errorMessage = null)
        authenticationProvider.login(object : AuthenticationProvider.AuthCallback {
            override fun onSuccess(user: AuthenticatedUser) {
                uiState = uiState.copy(
                    isSigningIn = false,
                    userId = user.userId,
                    email = user.emailAccount,
                    displayName = user.displayName,
                    preferredName = user.displayName,
                    activeRole = null
                )
                onSuccess()
            }

            override fun onFailure(error: Exception) {
                uiState = uiState.copy(
                    isSigningIn = false,
                    errorMessage = error.message ?: "Sign in was unsuccessful. Please try again."
                )
            }
        })
    }

    fun selectRole(role: SessionRole) {
        uiState = uiState.copy(activeRole = role)
    }

    fun updateProfile(displayName: String, preferredName: String) {
        uiState = uiState.copy(
            displayName = displayName.trim(),
            preferredName = preferredName.trim()
        )
    }

    fun signOut(onComplete: () -> Unit = {}) {
        authenticationProvider.logout(object : AuthenticationProvider.AuthCallback {
            override fun onSuccess(user: AuthenticatedUser?) {
                uiState = RideshareUiState()
                onComplete()
            }

            override fun onFailure(error: Exception) {
                uiState = uiState.copy(
                    errorMessage = error.message ?: "Sign out was unsuccessful. Please try again."
                )
            }
        })
    }
}
