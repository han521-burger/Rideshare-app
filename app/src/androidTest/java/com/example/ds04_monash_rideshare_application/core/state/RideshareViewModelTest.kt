package com.example.ds04_monash_rideshare_application.core.state

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ds04_monash_rideshare_application.core.model.SessionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Instrumented, not a plain JVM unit test: RideshareViewModel is an AndroidViewModel that builds
// a real Room database (AppDatabase.getInstance) on construction, which needs a real Android
// Context/SQLite implementation - that isn't available to app/src/test (see docs/BACKEND.md for
// the equivalent reasoning on the backend side re: fakes vs real infrastructure). The network
// calls this test path triggers (e.g. signIn's profile refresh) are fire-and-forget and don't
// block or get asserted on, so this still runs fine without a backend running.
@RunWith(AndroidJUnit4::class)
class RideshareViewModelTest {
    private val application: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @Test
    fun demoSignInCreatesAnAuthenticatedSession() {
        val viewModel = RideshareViewModel(application)
        var callbackCalled = false

        viewModel.signIn { callbackCalled = true }

        assertTrue(callbackCalled)
        assertTrue(viewModel.uiState.isSignedIn)
        assertEquals("test.student@student.monash.edu", viewModel.uiState.email)
        assertEquals("Test Student", viewModel.uiState.greetingName)
    }

    @Test
    fun signInKeepsTheEnteredMonashEmailAddress() {
        val viewModel = RideshareViewModel(application)

        viewModel.signIn("student.name@monash.edu")

        assertEquals("student.name@monash.edu", viewModel.uiState.email)
    }

    @Test
    fun roleAndProfileCanChangeWithinTheCurrentSession() {
        val viewModel = RideshareViewModel(application)
        viewModel.signIn()

        viewModel.selectRole(SessionRole.DRIVER)
        viewModel.updateProfile("Taylor Student", "Taylor")

        assertEquals(SessionRole.DRIVER, viewModel.uiState.activeRole)
        assertEquals("Taylor", viewModel.uiState.greetingName)
    }

    @Test
    fun signOutClearsPersonalSessionState() {
        val viewModel = RideshareViewModel(application)
        viewModel.signIn()
        viewModel.selectRole(SessionRole.PASSENGER)

        viewModel.signOut()

        assertFalse(viewModel.uiState.isSignedIn)
        assertEquals(null, viewModel.uiState.activeRole)
        assertEquals("", viewModel.uiState.email)
    }
}
