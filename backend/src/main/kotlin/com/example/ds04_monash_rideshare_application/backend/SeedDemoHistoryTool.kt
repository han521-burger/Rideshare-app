package com.example.ds04_monash_rideshare_application.backend

import kotlin.system.exitProcess

// Command-line entry point for seedDemoHistory() (DemoHistorySeeder.kt). Operator-run, against the
// same rideshare-backend.db as `./gradlew :backend:run`; there is deliberately no HTTP endpoint.
//   ./gradlew :backend:seedDemoHistory --args="someone@student.monash.edu"
//   ./gradlew :backend:seedDemoHistory --args="someone@student.monash.edu --remove"
// The target must already exist (email or user id) and must have a registered vehicle.
object SeedDemoHistoryTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val remove = "--remove" in args
        val target = args.firstOrNull { !it.startsWith("--") }?.takeIf { it.isNotBlank() } ?: run {
            System.err.println("Usage: ./gradlew :backend:seedDemoHistory --args=\"<email or user id> [--remove]\"")
            exitProcess(2)
        }
        initDatabase()
        try {
            if (remove) {
                val removed = removeDemoHistoryInTransaction(target)
                println("Removed $removed synthetic demo rides (and their requests) for $target.")
            } else {
                val summary = seedDemoHistoryInTransaction(target)
                if (summary.alreadySeeded) {
                    println("$target already has demo history - nothing added. Use --remove first to start again.")
                } else {
                    println("Seeded SYNTHETIC demo history for $target: ${summary.rides} completed rides " +
                        "(${summary.driverRides} driven, ${summary.passengerRides} as passenger), ${summary.requests} completed requests.")
                    println("These are demonstration records, not real trips. Impact figures are computed from them by the normal dashboard.")
                }
            }
        } catch (e: ApiException) {
            System.err.println(e.message)
            exitProcess(1)
        }
    }
}
