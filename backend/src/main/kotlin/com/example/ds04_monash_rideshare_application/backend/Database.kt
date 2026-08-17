package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// SQLite file DB for local trial/dev use - see build.gradle header for why, and swap this for a
// hosted Postgres connection once the team picks real hosting (RTM FR-08).
fun initDatabase(jdbcUrl: String = "jdbc:sqlite:./rideshare-backend.db") {
    Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")
    transaction {
        SchemaUtils.create(Users, Vehicles, Rides, RideRequests, Messages)
        seedDemoDataIfEmpty()
    }
}

// Seeds a demo user matching FakeAuthenticationProvider's fixed "fake-user-001" account so the
// Android app has something real to talk to the moment it signs in, with zero manual setup.
private fun seedDemoDataIfEmpty() {
    if (Users.selectAll().count() > 0) return

    val now = System.currentTimeMillis()
    Users.insert {
        it[userId] = "fake-user-001"
        it[externalSubjectId] = "fake-subject-001"
        it[emailAccount] = "test.student@student.monash.edu"
        it[displayName] = "Test Student"
        it[preferredName] = "Test"
        it[accountStatus] = "ACTIVE"
        it[passengerEnabled] = true
        it[createdAt] = now
    }
    Users.insert {
        it[userId] = "demo-driver-001"
        it[externalSubjectId] = "demo-driver-subject-001"
        it[emailAccount] = "demo.driver@student.monash.edu"
        it[displayName] = "Demo Driver"
        it[preferredName] = "Demo"
        it[accountStatus] = "ACTIVE"
        it[passengerEnabled] = false
        it[createdAt] = now
    }
    Vehicles.insert {
        it[vehicleId] = "demo-vehicle-001"
        it[ownerId] = "demo-driver-001"
        it[year] = 2021
        it[make] = "Toyota"
        it[model] = "Corolla"
        it[variant] = "Hybrid"
        it[registrationMasked] = "1XX·YY2"
        it[seatCapacity] = 4
        it[fuelType] = "Hybrid"
    }
}
