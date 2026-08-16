package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.core.model.SessionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideshareViewModelTest {
    @Test
    fun `demo sign in creates an authenticated session`() {
        val viewModel = RideshareViewModel()
        var callbackCalled = false

        viewModel.signIn { callbackCalled = true }

        assertTrue(callbackCalled)
        assertTrue(viewModel.uiState.isSignedIn)
        assertEquals("test.student@student.monash.edu", viewModel.uiState.email)
        assertEquals("Test Student", viewModel.uiState.greetingName)
    }

    @Test
    fun `role and profile can change within the current session`() {
        val viewModel = RideshareViewModel()
        viewModel.signIn()

        viewModel.selectRole(SessionRole.DRIVER)
        viewModel.updateProfile("Taylor Student", "Taylor")

        assertEquals(SessionRole.DRIVER, viewModel.uiState.activeRole)
        assertEquals("Taylor", viewModel.uiState.greetingName)
    }

    @Test
    fun `sign out clears personal session state`() {
        val viewModel = RideshareViewModel()
        viewModel.signIn()
        viewModel.selectRole(SessionRole.PASSENGER)

        viewModel.signOut()

        assertFalse(viewModel.uiState.isSignedIn)
        assertEquals(null, viewModel.uiState.activeRole)
        assertEquals("", viewModel.uiState.email)
    }
}
