package com.example.ds04_monash_rideshare_application.feature.profile

import com.example.ds04_monash_rideshare_application.core.state.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePresentationTest {
    @Test fun readyToOfferNeedsDriverModeAndAVehicle() {
        assertTrue(showReadyToOffer(AppMode.DRIVER, hasVehicle = true))
        assertFalse(showReadyToOffer(AppMode.DRIVER, hasVehicle = false))
        assertFalse(showReadyToOffer(AppMode.PASSENGER, hasVehicle = true))
    }

    @Test fun saveLabelsAndFeedbackKeepExistingSemantics() {
        assertEquals("Save changes", saveButtonLabel(false)); assertEquals("Saving…", saveButtonLabel(true))
        assertEquals(SaveFeedback("Saved.", false), saveFeedback(null))
        assertEquals(SaveFeedback("offline", true), saveFeedback("offline"))
    }

    @Test fun identityFallsBackAndAvatarInitialIsSafe() {
        assertEquals("Test Student", identityName("Test Student", "ui", "Test"))
        assertEquals("ui", identityName(null, "ui", "Test"))
        assertEquals("Test", identityName(null, "", "Test"))
        assertEquals("T", avatarInitial(" test"))
        assertEquals("?", avatarInitial("  "))
    }
}
