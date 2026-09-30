package com.example.ds04_monash_rideshare_application.core.state

/** Coalesces refreshes while retaining one refresh requested after a mutation or screen entry. */
internal class RequestRefreshGate {
    private var running = false
    private var pending = false

    fun start(requireFresh: Boolean): Boolean {
        if (running) {
            pending = pending || requireFresh
            return false
        }
        running = true
        return true
    }

    /** True means discard the superseded response and fetch once more, even after a failure. */
    fun finish(): Boolean {
        running = false
        val refreshAgain = pending
        pending = false
        return refreshAgain
    }
}
