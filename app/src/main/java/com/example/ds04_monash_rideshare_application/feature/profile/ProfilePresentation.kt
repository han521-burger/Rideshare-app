package com.example.ds04_monash_rideshare_application.feature.profile

import com.example.ds04_monash_rideshare_application.core.state.AppMode

// "Ready to offer rides" is only meaningful when Driver is active and a vehicle exists.
internal fun showReadyToOffer(mode: AppMode, hasVehicle: Boolean): Boolean = mode == AppMode.DRIVER && hasVehicle

internal fun saveButtonLabel(isSaving: Boolean): String = if (isSaving) "Saving…" else "Save changes"

// The save callback reports null on success or an error message; keep the same "Saved." copy.
internal data class SaveFeedback(val message: String, val isError: Boolean)

internal fun saveFeedback(error: String?): SaveFeedback =
    if (error == null) SaveFeedback("Saved.", isError = false) else SaveFeedback(error, isError = true)

internal fun avatarInitial(name: String): String = name.trim().take(1).uppercase().ifEmpty { "?" }

// Display name for the identity header; falls back to the signed-in state before the profile loads.
internal fun identityName(profileDisplayName: String?, uiDisplayName: String, greetingName: String): String =
    (profileDisplayName ?: uiDisplayName).ifBlank { greetingName }
