package com.example.ds04_monash_rideshare_application.Auth

// Trial-only: the exact identities backend/Database.kt seeds, so "sign in as" on the Login screen
// always matches a real, pre-populated account. FakeAuthenticationProvider can only ever produce
// fake-user-001 - this is what makes it possible to test both sides of a request/accept/message
// flow (e.g. sign in as Amara Singh to accept a request fake-user-001 made on her ride).
data class TestIdentity(
    val userId: String,
    val externalSubjectId: String,
    val email: String,
    val displayName: String,
    val roleHint: String
)

val TEST_IDENTITIES = listOf(
    TestIdentity("fake-user-001", "fake-subject-001", "test.student@student.monash.edu", "Test Student", "passenger + driver"),
    TestIdentity("demo-driver-001", "demo-driver-subject-001", "amara.singh@student.monash.edu", "Amara Singh", "driver"),
    TestIdentity("demo-driver-002", "demo-driver-subject-002", "ben.nguyen@student.monash.edu", "Ben Nguyen", "driver"),
    TestIdentity("demo-passenger-002", "demo-passenger-subject-002", "priya.kapoor@student.monash.edu", "Priya Kapoor", "passenger")
)
