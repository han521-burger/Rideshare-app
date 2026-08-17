package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Shared shell for the two multi-step flows (Request-a-Ride, Offer-a-Ride): a title bar, a
// "Step X of N: label" indicator, the step's own content, and a Back/Next footer. Both flows are
// identical in shape (linear steps, one screen each) even though their steps' content differs.
@Composable
fun StepWizardScaffold(
    title: String,
    stepLabels: List<String>,
    currentStep: Int,
    canGoNext: Boolean,
    isSubmitting: Boolean = false,
    onBackStep: () -> Unit,
    onNextStep: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit
) {
    val isLastStep = currentStep == stepLabels.lastIndex

    Scaffold(
        modifier = modifier,
        topBar = {
            Surface(shadowElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onExit) { Text("Cancel") }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Text(
                        "Step ${currentStep + 1} of ${stepLabels.size}: ${stepLabels[currentStep]}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (currentStep > 0) {
                            OutlinedButton(onClick = onBackStep, modifier = Modifier.weight(1f)) { Text("Back") }
                        }
                        Button(
                            onClick = onNextStep,
                            enabled = canGoNext && !isSubmitting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(modifier = Modifier.heightIn(max = 20.dp))
                            } else {
                                Text(if (isLastStep) "Confirm" else "Next")
                            }
                        }
                    }
                }
            }
        },
        content = content
    )
}
