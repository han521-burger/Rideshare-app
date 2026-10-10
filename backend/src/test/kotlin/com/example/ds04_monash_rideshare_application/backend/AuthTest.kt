package com.example.ds04_monash_rideshare_application.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Email + password accounts (routes/AuthRoutes.kt) and session-token auth (Auth.kt).
class AuthTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Runs without the test-only "userId as token" shortcut, so only real sessions work.
    private fun withApp(test: suspend HttpClient.() -> Unit) {
        val file = Files.createTempFile("rideshare-auth-test-", ".db").toFile().apply { deleteOnExit() }
        val previous = System.clearProperty(TRUST_USER_ID_TOKENS_PROPERTY)
        try {
            testApplication {
                application { module("jdbc:sqlite:${file.absolutePath.replace('\\', '/')}", testTravelTimes()) }
                startApplication()
                client.test()
            }
        } finally {
            previous?.let { System.setProperty(TRUST_USER_ID_TOKENS_PROPERTY, it) }
        }
    }

    private suspend fun HttpClient.postJson(path: String, body: String): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.me(token: String): HttpResponse =
        get("/users/me") { header(HttpHeaders.Authorization, "Bearer $token") }

    @Test
    fun `sign up, use the token, sign out, sign back in`() = withApp {
        val signUp = postJson("/auth/register",
            """{"displayName":"Jess Tan","email":" Jess.Tan@Student.Monash.edu ","password":"longenough"}""")
        assertEquals(HttpStatusCode.OK, signUp.status, signUp.bodyAsText())
        val created = json.decodeFromString<AuthResponse>(signUp.bodyAsText())
        assertEquals("jess.tan@student.monash.edu", created.user.emailAccount)
        assertEquals(HttpStatusCode.OK, me(created.token).status)

        // The password is stored hashed, never as-is.
        val stored = transaction {
            Users.selectAll().where { Users.userId eq created.user.userId }.first()[Users.passwordHash]!!
        }
        assertFalse(stored.contains("longenough"))

        assertEquals(HttpStatusCode.OK, post("/auth/logout") { header(HttpHeaders.Authorization, "Bearer ${created.token}") }.status)
        assertEquals(HttpStatusCode.Unauthorized, me(created.token).status)

        val signIn = postJson("/auth/login", """{"email":"jess.tan@student.monash.edu","password":"longenough"}""")
        assertEquals(HttpStatusCode.OK, signIn.status)
        val again = json.decodeFromString<AuthResponse>(signIn.bodyAsText())
        assertEquals(created.user.userId, again.user.userId)
        assertEquals(HttpStatusCode.OK, me(again.token).status)
    }

    @Test
    fun `sign up rules`() = withApp {
        fun body(email: String, password: String = "longenough") =
            """{"displayName":"Someone","email":"$email","password":"$password"}"""
        assertEquals(HttpStatusCode.BadRequest, postJson("/auth/register", body("someone@gmail.com")).status)
        assertEquals(HttpStatusCode.BadRequest, postJson("/auth/register", body("someone@student.monash.edu", "short")).status)
        assertEquals(HttpStatusCode.OK, postJson("/auth/register", body("staff.member@monash.edu")).status)
        assertEquals(HttpStatusCode.Conflict, postJson("/auth/register", body("STAFF.member@monash.edu")).status)
    }

    @Test
    fun `wrong password, unknown email and bare userIds are all rejected`() = withApp {
        val wrong = postJson("/auth/login", """{"email":"ben.nguyen@student.monash.edu","password":"nope-nope"}""")
        val unknown = postJson("/auth/login", """{"email":"nobody@student.monash.edu","password":"nope-nope"}""")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals(wrong.bodyAsText(), unknown.bodyAsText())

        // The old trial shortcut - sending a userId as the token - no longer signs anyone in.
        assertEquals(HttpStatusCode.Unauthorized, me("demo-driver-002").status)
    }

    @Test
    fun `seeded demo accounts sign in with the demo password`() = withApp {
        val response = postJson("/auth/login",
            """{"email":"ben.nguyen@student.monash.edu","password":"$DEMO_ACCOUNT_PASSWORD"}""")
        assertEquals(HttpStatusCode.OK, response.status)
        val auth = json.decodeFromString<AuthResponse>(response.bodyAsText())
        assertEquals("demo-driver-002", auth.user.userId)
        assertTrue(auth.token.length >= 40)
    }
}
