package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun GitHubIssueAuthorizationStatusDialog(
    state: GitHubIssueAuthorizationState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val message = when (state) {
        GitHubIssueAuthorizationState.Starting ->
            stringResource(R.string.github_issue_authorization_starting)
        GitHubIssueAuthorizationState.Completing ->
            stringResource(R.string.github_issue_authorization_completing)
        GitHubIssueAuthorizationState.Authorized ->
            stringResource(R.string.github_issue_authorization_success)
        is GitHubIssueAuthorizationState.Error -> state.message
        GitHubIssueAuthorizationState.Idle,
        is GitHubIssueAuthorizationState.AwaitingApproval -> return
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.github_issue_authorization_title),
        subtitle = stringResource(R.string.github_issue_authorization_subtitle),
        width = 640.dp,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }

        SettingsDialogActionRow {
            if (state is GitHubIssueAuthorizationState.Error) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.github_issue_authorization_retry),
                    onClick = onRetry,
                    primary = true
                )
            }
            SettingsDialogActionButton(
                text = stringResource(R.string.github_issue_authorization_close),
                onClick = onDismiss
            )
        }
    }
}
