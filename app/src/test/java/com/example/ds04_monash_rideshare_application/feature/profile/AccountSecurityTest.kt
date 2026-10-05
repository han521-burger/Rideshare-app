package com.example.ds04_monash_rideshare_application.feature.profile

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Repository.Remote.AuthResult
import com.example.ds04_monash_rideshare_application.Repository.Remote.BackendException
import com.example.ds04_monash_rideshare_application.Repository.Remote.FakeRideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.UserAccountSummary
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSecurityTest {
    @Test fun `password change form rules`() {
        assertEquals("Enter your current password", validatePasswordChange("", "longenough", "longenough"))
        assertEquals("New password must be at least 8 characters", validatePasswordChange("old-pass", "short", "short"))
        assertEquals("Choose a different password from your current one", validatePasswordChange("same-pass", "same-pass", "same-pass"))
        assertEquals("New passwords don't match", validatePasswordChange("old-pass", "longenough", "different"))
        assertNull(validatePasswordChange("old-pass", "longenough", "longenough"))
    }

    // Signs straight in; delete succeeds unless the password is "wrong".
    private class Api : RideApiService by FakeRideApiService() {
        override fun signIn(email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
            callback.onSuccess(AuthResult("token-1", UserAccountSummary("user-1", "Jess", "Jess", email, null, null, true, null, null)))
        }
        override fun deleteAccount(password: String, callback: RideApiService.Callback<Boolean>) {
            if (password == "wrong") callback.onFailure(BackendException("Your current password is incorrect"))
            else callback.onSuccess(true)
        }
    }

    private val vm = RideshareViewModel(Api())

    @After fun clearSession() = AuthSession.clear()

    @Test fun `a failed delete keeps you signed in and shows why`() {
        vm.signIn("jess@student.monash.edu", "password", keepSignedIn = false)
        var message: String? = null
        vm.deleteAccount("wrong") { message = it }
        assertEquals("Your current password is incorrect", message)
        assertTrue(vm.uiState.isSignedIn)
        assertEquals("token-1", AuthSession.token)
    }

    @Test fun `a successful delete signs this device out`() {
        vm.signIn("jess@student.monash.edu", "password", keepSignedIn = false)
        var finished = false
        vm.deleteAccount("right") { error -> finished = error == null }
        assertTrue(finished)
        assertFalse(vm.uiState.isSignedIn)
        assertNull(AuthSession.token)
    }
}
