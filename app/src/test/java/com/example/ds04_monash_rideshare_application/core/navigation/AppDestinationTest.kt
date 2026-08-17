package com.example.ds04_monash_rideshare_application.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppDestinationTest {
    @Test
    fun `every destination has a unique route`() {
        val routes = AppDestination.allDestinations.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `all four bottom-nav tabs are destinations`() {
        assertEquals(4, AppDestination.tabDestinations.size)
        assertEquals(
            setOf(AppDestination.Home, AppDestination.Requests, AppDestination.Activity, AppDestination.Profile),
            AppDestination.tabDestinations.toSet()
        )
    }

    @Test
    fun `route lookup returns known destinations only`() {
        assertNotNull(AppDestination.fromRoute("home"))
        assertNull(AppDestination.fromRoute("not-a-real-route"))
    }
}
