package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.GitHubIssueReportingDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SentrySettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdvancedSettingsUiState(
    val fastHorizontalNavigationEnabled: Boolean = false,
    val smoothBringIntoViewEnabled: Boolean = true,
    val composeHighlighterEnabled: Boolean = false,
    val playbackIssueReportsEnabled: Boolean = false,
    val subtitleSyncIssueReportsEnabled: Boolean = false,
    val githubIssueTokenConfigured: Boolean = false,
    val sentryEnabled: Boolean = true
)

sealed class AdvancedSettingsEvent {
    data class SetFastHorizontalNavigationEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetSmoothBringIntoViewEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetComposeHighlighterEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetPlaybackIssueReportsEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetSubtitleSyncIssueReportsEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
    data class SetGitHubIssueToken(
        val token: String,
        val enableAfterSave: Boolean = false
    ) : AdvancedSettingsEvent()
    data object ClearGitHubIssueToken : AdvancedSettingsEvent()
    data class SetSentryEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
}

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val githubIssueReportingDataStore: GitHubIssueReportingDataStore,
    private val sentrySettingsDataStore: SentrySettingsDataStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(AdvancedSettingsUiState())
    val uiState: StateFlow<AdvancedSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            layoutPreferenceDataStore.fastHorizontalNavigationEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(fastHorizontalNavigationEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.smoothBringIntoViewEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(smoothBringIntoViewEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.composeHighlighterEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(composeHighlighterEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            playerSettingsDataStore.playerSettings.collectLatest { settings ->
                _uiState.update { it.copy(playbackIssueReportsEnabled = settings.playbackIssueReportsEnabled) }
            }
        }
        viewModelScope.launch {
            githubIssueReportingDataStore.settings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        subtitleSyncIssueReportsEnabled = settings.enabled,
                        githubIssueTokenConfigured = settings.tokenConfigured
                    )
                }
            }
        }
        viewModelScope.launch {
            sentrySettingsDataStore.enabled.collectLatest { enabled ->
                _uiState.update { it.copy(sentryEnabled = enabled) }
            }
        }
    }

    fun onEvent(event: AdvancedSettingsEvent) {
        when (event) {
            is AdvancedSettingsEvent.SetFastHorizontalNavigationEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setFastHorizontalNavigationEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetSmoothBringIntoViewEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setSmoothBringIntoViewEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetComposeHighlighterEnabled -> {
                viewModelScope.launch {
                    layoutPreferenceDataStore.setComposeHighlighterEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetPlaybackIssueReportsEnabled -> {
                viewModelScope.launch {
                    playerSettingsDataStore.setPlaybackIssueReportsEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetSubtitleSyncIssueReportsEnabled -> {
                viewModelScope.launch {
                    githubIssueReportingDataStore.setEnabled(event.enabled)
                }
            }
            is AdvancedSettingsEvent.SetGitHubIssueToken -> {
                viewModelScope.launch {
                    githubIssueReportingDataStore.setToken(event.token)
                    if (event.enableAfterSave) {
                        githubIssueReportingDataStore.setEnabled(true)
                    }
                }
            }
            AdvancedSettingsEvent.ClearGitHubIssueToken -> {
                viewModelScope.launch {
                    githubIssueReportingDataStore.clearToken()
                }
            }
            is AdvancedSettingsEvent.SetSentryEnabled -> {
                viewModelScope.launch {
                    sentrySettingsDataStore.setEnabled(event.enabled)
                }
            }
        }
    }
}
