package com.example.ds04_monash_rideshare_application.Database.User

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileTest {

    private fun sample() = UserProfile(
        "user-1", "Tony D", "Tony", "0400000000", "photo-ref-1", true
    )

    @Test
    fun `constructor sets all fields`() {
        val profile = sample()
        assertEquals("user-1", profile.userId)
        assertEquals("Tony D", profile.displayName)
        assertEquals("Tony", profile.preferredName)
        assertEquals("0400000000", profile.phoneNumber)
        assertEquals("photo-ref-1", profile.photoRef)
        assertTrue(profile.isPassengerEnabled())
    }

    @Test
    fun `phone number and photo ref accept null (both optional per privacy guidance)`() {
        val profile = UserProfile("user-2", "Han", "Han", null, null, false)
        assertEquals(null, profile.phoneNumber)
        assertEquals(null, profile.photoRef)
        assertFalse(profile.isPassengerEnabled())
    }

    @Test
    fun `setDisplayName updates displayName`() {
        val profile = sample()
        profile.setDisplayName("Tony Doan")
        assertEquals("Tony Doan", profile.getDisplayName())
    }

    @Test
    fun `setPreferredName updates preferredName`() {
        val profile = sample()
        profile.setPreferredName("T")
        assertEquals("T", profile.getPreferredName())
    }

    @Test
    fun `setPassengerEnabled toggles role flag`() {
        val profile = sample()
        profile.setPassengerEnabled(false)
        assertFalse(profile.isPassengerEnabled())
    }
}
