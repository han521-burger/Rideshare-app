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
    fun `only Settings remains a placeholder screen`() {
        // create-ride, find-ride, ride-details, ride-requests, confirmed-ride, messages,
        // trip-history, and sustainability-dashboard all got real implementations - see
        // RideshareNavHost. Settings is the only one still pending.
        val expectedRoutes = setOf("settings")

        assertEquals(expectedRoutes, AppDestination.placeholders.map { it.route }.toSet())
    }

    @Test
    fun `route lookup returns known destinations only`() {
        assertNotNull(AppDestination.fromRoute("passenger-home"))
        assertNull(AppDestination.fromRoute("not-a-real-route"))
    }
}
