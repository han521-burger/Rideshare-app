package com.example.ds04_monash_rideshare_application.backend

import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction

// Concurrency model for anything that decides based on a passenger's (or driver's) schedule:
// creating a request, accepting one, undoing an accept, cancelling one, cancelling/completing a
// ride, and the expiry of stale records.
//
// THE RULES
//  1. A read-check-write on a person's schedule happens inside ONE transaction that first takes the
//     lock of every person it will change, so two operations touching the same person run strictly
//     one after the other and the second sees the first's committed result. That is
//     schedulingTransaction() / schedulingTransactionFor(). Unique constraints stay as a backstop for
//     anything that slips through; they are not the design.
//  2. A handler that only reads uses readTransaction(). It never takes the write lock, so it is never
//     queued behind (or ahead of) a schedule update.
//  3. Anything else that writes (profile, messages, ratings...) uses a plain transaction { }, which is a
//     write transaction. Nothing ever relies on a database "busy" error being silently retried.
//
// HOW EACH KIND RUNS, per database
//  * SQLite (today): write transactions begin with BEGIN IMMEDIATE (one writer at a time, others wait up
//    to the busy timeout). Read transactions are ordinary deferred ones on a second query_only
//    connection (Database.kt), so they proceed alongside a writer. The user-row locks below have nothing
//    extra to do there - Exposed leaves forUpdate() out of SQLite queries - because the single write
//    lock already serialises every schedule-changing operation.
//  * PostgreSQL (future): one ordinary connection for both kinds. schedulingTransaction runs
//    SELECT ... FOR UPDATE on the people's users rows (and the rides rows below) in the order given
//    here. That row lock IS the lock - no schema change is needed to move databases.
//
// LOCK ORDER (apply it everywhere more than one thing is locked, so two operations that need the same
// things can never wait on each other):
//   1. users rows, ascending by user_id        <- lockUsersInOrder()
//   2. rides rows, ascending by ride_id        <- lockRidesInOrder()
//   3. ride_requests rows are never locked on their own; they are protected by 1.
// Code that needs a lock earlier in this list than one it already holds must start a new transaction
// (or use schedulingTransactionFor, which re-discovers and retries), never acquire out of order.

// The order users are locked in: ascending id, duplicates and nulls dropped.
fun lockOrder(userIds: Collection<String?>): List<String> = userIds.filterNotNull().distinct().sorted()

fun Transaction.lockUsersInOrder(userIds: Collection<String?>) {
    val ordered = lockOrder(userIds)
    if (ordered.isEmpty()) return
    Users.selectAll().where { Users.userId inList ordered }.orderBy(Users.userId).forUpdate().toList()
}

// Lock the given rides, ascending by id. Always call AFTER lockUsersInOrder (lock order above).
fun Transaction.lockRidesInOrder(rideIds: Collection<String?>) {
    val ordered = rideIds.filterNotNull().distinct().sorted()
    if (ordered.isEmpty()) return
    Rides.selectAll().where { Rides.rideId inList ordered }.orderBy(Rides.rideId).forUpdate().toList()
}

// A transaction for handlers that only read. If one is already open (a helper called from inside a write
// transaction) it simply joins it, so a read never looks at a stale second snapshot of its own caller's
// uncommitted work.
fun <T> readTransaction(block: Transaction.() -> T): T {
    TransactionManager.currentOrNull()?.let { return it.block() }
    return transaction(Databases.read) { block() }
}

// A write transaction that owns the scheduling state of the given users (and rides) for its whole
// duration. Pass every user whose requests or rides the block will read-and-then-change (e.g. the
// passenger and the accepting driver).
fun <T> schedulingTransaction(
    lockUserIds: Collection<String?>,
    lockRideIds: Collection<String?> = emptyList(),
    block: Transaction.() -> T
): T = transaction(Databases.write) {
    lockUsersInOrder(lockUserIds)
    lockRidesInOrder(lockRideIds)
    block()
}

// For operations whose affected people are only known by reading the data (cancel/complete a ride:
// its passengers; expiry: whoever's records went stale). The affected set is discovered, locked in
// order, then DISCOVERED AGAIN under the lock; if someone new turned up in between, the whole thing
// is retried with the larger set rather than acting on a person who is not locked. This retry is
// about lock coverage, not about database errors.
fun <D, T> schedulingTransactionFor(
    usersOf: (D) -> Collection<String?>,
    ridesOf: (D) -> Collection<String?> = { emptyList() },
    discover: Transaction.() -> D,
    block: Transaction.(D) -> T
): T {
    var found = readTransaction { discover() }
    repeat(MAX_LOCK_DISCOVERY_ATTEMPTS) {
        val users = lockOrder(usersOf(found)).toSet()
        val rides = ridesOf(found).filterNotNull().toSet()
        val outcome = schedulingTransaction(users, rides) {
            val fresh = discover()
            val covered = users.containsAll(lockOrder(usersOf(fresh))) && rides.containsAll(ridesOf(fresh).filterNotNull())
            if (covered) LockedResult.Done(block(fresh)) else LockedResult.Wider(fresh)
        }
        when (outcome) {
            is LockedResult.Done -> return outcome.value
            is LockedResult.Wider -> found = outcome.found
        }
    }
    throw ConflictException("The schedule changed while this was being processed. Please try again.")
}

private const val MAX_LOCK_DISCOVERY_ATTEMPTS = 5

private sealed class LockedResult<out D, out T> {
    class Done<T>(val value: T) : LockedResult<Nothing, T>()
    class Wider<D>(val found: D) : LockedResult<D, Nothing>()
}
