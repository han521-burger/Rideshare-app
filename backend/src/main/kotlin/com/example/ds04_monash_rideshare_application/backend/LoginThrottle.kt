package com.example.ds04_monash_rideshare_application.backend

import java.util.concurrent.ConcurrentHashMap

// Slows down password guessing: after MAX_FAILURES wrong passwords for one email within
// FAILURE_WINDOW, sign-in for that email is refused for LOCKOUT, even with the right password.
// Tracked per email (whether or not the account exists, so the response never reveals that) and
// cleared by a successful sign-in. Kept in memory - a backend restart clears it, which is fine
// for a single trial server; a hosted, multi-instance version would move this to shared storage.
object LoginThrottle {
    const val MAX_FAILURES = 5
    const val FAILURE_WINDOW_MILLIS = 15L * 60_000L
    const val LOCKOUT_MILLIS = 15L * 60_000L

    // Overridable so tests can move time forward.
    internal var clock: () -> Long = System::currentTimeMillis

    private class Entry(val failures: MutableList<Long> = mutableListOf(), var lockedUntil: Long = 0)

    private val entries = ConcurrentHashMap<String, Entry>()

    // Throws if this email is currently locked out.
    fun checkAllowed(email: String) {
        val entry = entries[email] ?: return
        val remaining = synchronized(entry) { entry.lockedUntil - clock() }
        if (remaining > 0) {
            val minutes = (remaining + 59_999) / 60_000
            throw TooManyRequestsException(
                "Too many wrong passwords - try again in $minutes minute${if (minutes == 1L) "" else "s"}"
            )
        }
    }

    fun recordFailure(email: String) {
        val entry = entries.computeIfAbsent(email) { Entry() }
        synchronized(entry) {
            val now = clock()
            entry.failures.removeAll { it <= now - FAILURE_WINDOW_MILLIS }
            entry.failures += now
            if (entry.failures.size >= MAX_FAILURES) {
                entry.lockedUntil = now + LOCKOUT_MILLIS
                entry.failures.clear()
            }
        }
    }

    fun recordSuccess(email: String) {
        entries.remove(email)
    }

    internal fun resetForTests() {
        entries.clear()
        clock = System::currentTimeMillis
    }
}
