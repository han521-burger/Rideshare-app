package com.example.ds04_monash_rideshare_application.core.state

// Driver access is granted only after an account-backed vehicle lookup succeeds.
internal data class DriverAccessState(
    val mode: AppMode = AppMode.PASSENGER,
    val checking: Boolean = false,
    val needsRegistration: Boolean = false
) {
    fun beginCheck() = DriverAccessState(checking = true)
    fun resolve(hasVehicle: Boolean) = if (hasVehicle) DriverAccessState(mode = AppMode.DRIVER)
        else DriverAccessState(needsRegistration = true)
    fun cancel() = DriverAccessState()
}
