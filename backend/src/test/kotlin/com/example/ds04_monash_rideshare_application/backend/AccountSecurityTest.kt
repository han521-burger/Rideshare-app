package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Login lockout (LoginThrottle.kt), change password, admin password reset and account deletion
// (AccountAdmin.kt, routes/AuthRoutes.kt). Runs without the test-only "userId as token" shortcut,
// so every call goes through a real signed-in session.
class AccountSecurityTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val minuteMs = 60_000L

    @AfterTest fun resetThrottle() = LoginThrottle.resetForTests()

    private fun withApp(test: suspend HttpClient.() -> Unit) {
        val file = Files.createTempFile("rideshare-account-test-", ".db").toFile().apply { deleteOnExit() }
        val previous = System.clearProperty(TRUST_USER_ID_TOKENS_PROPERTY)
        LoginThrottle.resetForTests()
        try {
            testApplication {
                application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}") }
                startApplication()
                client.test()
            }
        } finally {
            previous?.let { System.setProperty(TRUST_USER_ID_TOKENS_PROPERTY, it) }
        }
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse =
        post(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.login(email: String, password: String) =
        postJson("/auth/login", """{"email":"$email","password":"$password"}""")

    private suspend fun HttpClient.token(email: String, password: String = DEMO_ACCOUNT_PASSWORD): String {
        val response = login(email, password)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString<AuthResponse>(response.bodyAsText()).token
    }

    private suspend fun HttpClient.me(token: String) =
        get("/users/me") { header(HttpHeaders.Authorization, "Bearer $token") }

    private fun requestStatus(id: String) = transaction {
        RideRequests.selectAll().where { RideRequests.requestId eq id }.first()[RideRequests.status]
    }

    private fun ride(id: String) = transaction { Rides.selectAll().where { Rides.rideId eq id }.first() }

    // Priya books a seat on Ben's seeded ride and Ben accepts. Returns the request id.
    private suspend fun HttpClient.priyaBooksBensRide(): String {
        val priya = token("priya.kapoor@student.monash.edu")
        val ben = token("ben.nguyen@student.monash.edu")
        val created = postJson("/rides/ride-open-2/requests", """{"seatsRequested":1,"applyToRecurringGroup":false}""", priya)
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        val requestId = json.decodeFromString<List<RideRequestDto>>(created.bodyAsText()).single().requestId
        val accepted = put("/requests/$requestId") {
            header(HttpHeaders.Authorization, "Bearer $ben")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"ACCEPTED"}""")
        }
        assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
        return requestId
    }

    @Test
    fun `five wrong passwords lock the email for 15 minutes`() = withApp {
        val email = "ben.nguyen@student.monash.edu"
        var now = System.currentTimeMillis()
        LoginThrottle.clock = { now }

        repeat(LoginThrottle.MAX_FAILURES) {
            assertEquals(HttpStatusCode.Unauthorized, login(email, "wrong-guess").status)
        }
        // Locked: even the right password is refused, with a clear message.
        val locked = login(email, DEMO_ACCOUNT_PASSWORD)
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertTrue(locked.bodyAsText().contains("try again in 15 minutes"), locked.bodyAsText())

        now += LoginThrottle.LOCKOUT_MILLIS + minuteMs
        assertEquals(HttpStatusCode.OK, login(email, DEMO_ACCOUNT_PASSWORD).status)
    }

    @Test
    fun `a successful sign-in clears earlier wrong attempts, and unknown emails lock too`() = withApp {
        val email = "ben.nguyen@student.monash.edu"
        repeat(LoginThrottle.MAX_FAILURES - 1) { login(email, "wrong-guess") }
        assertEquals(HttpStatusCode.OK, login(email, DEMO_ACCOUNT_PASSWORD).status)
        repeat(LoginThrottle.MAX_FAILURES - 1) { login(email, "wrong-guess") }
        assertEquals(HttpStatusCode.OK, login(email, DEMO_ACCOUNT_PASSWORD).status)

        // Same behaviour for an email with no account, so lockouts don't reveal which emails exist.
        val nobody = "nobody@student.monash.edu"
        repeat(LoginThrottle.MAX_FAILURES) { login(nobody, "wrong-guess") }
        assertEquals(HttpStatusCode.TooManyRequests, login(nobody, "wrong-guess").status)
    }

    @Test
    fun `changing password keeps this device and signs out the others`() = withApp {
        val email = "amara.singh@student.monash.edu"
        val phone = token(email)
        val laptop = token(email)

        val wrongCurrent = postJson("/auth/change-password",
            """{"currentPassword":"not-it","newPassword":"brand-new-pass"}""", phone)
        assertEquals(HttpStatusCode.BadRequest, wrongCurrent.status)
        val tooShort = postJson("/auth/change-password",
            """{"currentPassword":"$DEMO_ACCOUNT_PASSWORD","newPassword":"short"}""", phone)
        assertEquals(HttpStatusCode.BadRequest, tooShort.status)

        val changed = postJson("/auth/change-password",
            """{"currentPassword":"$DEMO_ACCOUNT_PASSWORD","newPassword":"brand-new-pass"}""", phone)
        assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())

        assertEquals(HttpStatusCode.OK, me(phone).status)
        assertEquals(HttpStatusCode.Unauthorized, me(laptop).status)
        assertEquals(HttpStatusCode.Unauthorized, login(email, DEMO_ACCOUNT_PASSWORD).status)
        assertEquals(HttpStatusCode.OK, login(email, "brand-new-pass").status)
    }

    @Test
    fun `admin reset gives a working temporary password and signs out everywhere`() = withApp {
        val email = "priya.kapoor@student.monash.edu"
        val oldSession = token(email)

        val temporary = transaction { resetPassword(" Priya.Kapoor@Student.Monash.edu ") }

        assertEquals(12, temporary.length)
        assertEquals(HttpStatusCode.Unauthorized, me(oldSession).status)
        assertEquals(HttpStatusCode.Unauthorized, login(email, DEMO_ACCOUNT_PASSWORD).status)
        assertEquals(HttpStatusCode.OK, login(email, temporary).status)
    }

    @Test
    fun `deleting a driver cancels their upcoming rides and frees the email`() = withApp {
        val bookingId = priyaBooksBensRide()
        val ben = token("ben.nguyen@student.monash.edu")

        val wrong = postJson("/auth/delete-account", """{"password":"not-it"}""", ben)
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertEquals(RideStatus.CONFIRMED, ride("ride-open-2")[Rides.status])

        val deleted = postJson("/auth/delete-account", """{"password":"$DEMO_ACCOUNT_PASSWORD"}""", ben)
        assertEquals(HttpStatusCode.OK, deleted.status, deleted.bodyAsText())

        assertEquals(RideStatus.CANCELLED, ride("ride-open-2")[Rides.status])
        assertEquals(RideRequestStatus.CANCELLED, requestStatus(bookingId))
        val user = transaction { Users.selectAll().where { Users.userId eq "demo-driver-002" }.first() }
        assertEquals(DELETED_USER_NAME, user[Users.displayName])
        assertEquals(ACCOUNT_STATUS_DELETED, user[Users.accountStatus])
        assertNull(user[Users.passwordHash])
        val plate = transaction { Vehicles.selectAll().where { Vehicles.ownerId eq "demo-driver-002" }.first()[Vehicles.registrationMasked] }
        assertNull(plate)

        assertEquals(HttpStatusCode.Unauthorized, me(ben).status)
        assertEquals(HttpStatusCode.Unauthorized, login("ben.nguyen@student.monash.edu", DEMO_ACCOUNT_PASSWORD).status)
        val again = postJson("/auth/register",
            """{"displayName":"Ben Nguyen","email":"ben.nguyen@student.monash.edu","password":"new-account-pass"}""")
        assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
    }

    @Test
    fun `deleting a passenger gives their seat back`() = withApp {
        val seatsBefore = ride("ride-open-2")[Rides.seatsAvailable]
        val bookingId = priyaBooksBensRide()
        assertEquals(seatsBefore - 1, ride("ride-open-2")[Rides.seatsAvailable])

        val priya = token("priya.kapoor@student.monash.edu")
        assertEquals(HttpStatusCode.OK,
            postJson("/auth/delete-account", """{"password":"$DEMO_ACCOUNT_PASSWORD"}""", priya).status)

        assertEquals(RideRequestStatus.CANCELLED, requestStatus(bookingId))
        assertEquals(seatsBefore, ride("ride-open-2")[Rides.seatsAvailable])
        assertEquals(RideStatus.OPEN, ride("ride-open-2")[Rides.status])
    }
}
