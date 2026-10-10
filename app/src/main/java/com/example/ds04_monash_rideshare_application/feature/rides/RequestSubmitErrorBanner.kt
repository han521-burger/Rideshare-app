package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.example.ds04_monash_rideshare_application.core.state.RequestSubmitError
import com.example.ds04_monash_rideshare_application.core.ui.FormBanner
import com.example.ds04_monash_rideshare_application.core.ui.FormBannerKind

internal const val VIEW_EXISTING_REQUEST_TAG = "view-existing-request"

// What the request screens show when sending did not go through. A duplicate is worded as what it
// is - the passenger already has this request - rather than as a failure, and offers a way to the
// request the backend named.
internal fun requestSubmitErrorTitle(error: RequestSubmitError, failureTitle: String): String = when {
    error.isDuplicate -> "Already requested"
    error.isScheduleConflict -> "Doesn\u2019t fit your schedule"
    else -> failureTitle
}

@Composable
internal fun RequestSubmitErrorBanner(
    error: RequestSubmitError,
    failureTitle: String,
    onViewRequest: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val existingId = error.existingRequestId
    FormBanner(
        message = error.message,
        kind = FormBannerKind.Error,
        modifier = modifier,
        title = requestSubmitErrorTitle(error, failureTitle),
        trailing = if (error.isDuplicate && existingId != null) {
            { TextButton(onClick = { onViewRequest(existingId) }, modifier = Modifier.testTag(VIEW_EXISTING_REQUEST_TAG)) { Text("View") } }
        } else null
    )
}
