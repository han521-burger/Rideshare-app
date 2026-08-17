package com.example.ds04_monash_rideshare_application.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppDestinationTest {
    @Test
    fun `every Sprint 1 destination has a unique route`() {
        val routes = AppDestination.sprintOneDestinations.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `no destinations remain as placeholders`() {
        // Settings was the last one - see RideshareNavHost and feature/settings/SettingsScreen.
        assertEquals(emptyList<AppDestination>(), AppDestination.placeholders)
    }

    @Test
    fun `route lookup returns known destinations only`() {
        assertNotNull(AppDestination.fromRoute("passenger-home"))
        assertNull(AppDestination.fromRoute("not-a-real-route"))
    }
}
