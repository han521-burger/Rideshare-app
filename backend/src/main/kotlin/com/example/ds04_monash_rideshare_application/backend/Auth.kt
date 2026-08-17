package com.example.ds04_monash_rideshare_application.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// Trial-only auth: the bearer token IS the userId, trusted as-is. This stands in for real Monash
// SSO the same way FakeAuthenticationProvider does on the Android side - the RTM (FR-01) notes
// real SSO is blocked on Gate G1 (SAML metadata from Monash IT), so build against a fake here too
// and swap this file for real OIDC/JWT verification once that's unblocked. Every protected route
// calls requireUserId() to resolve "who is calling", and route handlers are responsible for
// checking that userId owns whatever resource it's trying to modify (NFR: "users can't modify
// other users' profiles or rides they don't own").
fun ApplicationCall.requireUserId(): String {
    val header = request.header(HttpHeaders.Authorization)
        ?: throw UnauthorizedException()
    val userId = header.removePrefix("Bearer ").trim()
    if (userId.isBlank()) throw UnauthorizedException()

    val exists = transaction {
        Users.selectAll().where { Users.userId eq userId }.count() > 0
    }
    if (!exists) throw UnauthorizedException("Unknown user")
    return userId
}
