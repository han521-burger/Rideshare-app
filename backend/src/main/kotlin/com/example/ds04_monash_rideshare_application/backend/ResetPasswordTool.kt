package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.system.exitProcess

// Command-line entry point for resetPassword() (AccountAdmin.kt):
//   ./gradlew :backend:resetPassword --args="someone@student.monash.edu"
// Uses the same database file as `./gradlew :backend:run`, so it works while the server is running.
fun main(args: Array<String>) {
    val email = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: run {
        System.err.println("Usage: ./gradlew :backend:resetPassword --args=\"someone@student.monash.edu\"")
        exitProcess(2)
    }
    initDatabase()
    val temporary = try {
        transaction { resetPassword(email) }
    } catch (e: NotFoundException) {
        System.err.println(e.message)
        exitProcess(1)
    }
    println("Password reset for ${email.trim()}.")
    println("Temporary password: $temporary")
    println("They've been signed out everywhere. Ask them to sign in with this and change it.")
}
