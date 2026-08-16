package com.nuvio.tv.ui.screens.settings

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.local.GitHubIssueReportingDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.data.repository.GitHubDevicePollResult
import com.nuvio.tv.data.repository.GitHubIssueAuthorizationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface GitHubIssueAuthorizationState {
    data object Idle : GitHubIssueAuthorizationState
    data object Starting : GitHubIssueAuthorizationState

    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val qrBitmap: Bitmap,
        val expiresAtMs: Long
    ) : GitHubIssueAuthorizationState

    data object Completing : GitHubIssueAuthorizationState
    data object Authorized : GitHubIssueAuthorizationState

    data class Error(
        val message: String
    ) : GitHubIssueAuthorizationState
}

data class AdvancedSettingsUiState(
    val fastHorizontalNavigationEnabled: Boolean = false,
    val smoothBringIntoViewEnabled: Boolean = true,
    val composeHighlighterEnabled: Boolean = false,
    val playbackIssueReportsEnabled: Boolean = false,
    val subtitleSyncIssueReportsEnabled: Boolean = false,
    val githubIssueTokenConfigured: Boolean = false,
    val githubIssueAuthorizationAvailable: Boolean = BuildConfig.GITHUB_ISSUE_CLIENT_ID.isNotBlank(),
    val githubIssueAuthorization: GitHubIssueAuthorizationState = GitHubIssueAuthorizationState.Idle,
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
    data object StartGitHubIssueAuthorization : AdvancedSettingsEvent()
    data object CancelGitHubIssueAuthorization : AdvancedSettingsEvent()
    data class SetSentryEnabled(val enabled: Boolean) : AdvancedSettingsEvent()
}

@HiltViewModel
class AdvancedSettingsViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val githubIssueReportingDataStore: GitHubIssueReportingDataStore,
    private val githubIssueAuthorizationRepository: GitHubIssueAuthorizationRepository,
    private val sentrySettingsDataStore: SentrySettingsDataStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(AdvancedSettingsUiState())
    val uiState: StateFlow<AdvancedSettingsUiState> = _uiState.asStateFlow()
    private var githubIssueAuthorizationJob: Job? = null

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
            AdvancedSettingsEvent.StartGitHubIssueAuthorization -> {
                startGitHubIssueAuthorization()
            }
            AdvancedSettingsEvent.CancelGitHubIssueAuthorization -> {
                cancelGitHubIssueAuthorization()
            }
            is AdvancedSettingsEvent.SetSentryEnabled -> {
                viewModelScope.launch {
                    sentrySettingsDataStore.setEnabled(event.enabled)
                }
            }
        }
    }

    private fun startGitHubIssueAuthorization() {
        githubIssueAuthorizationJob?.cancel()
        githubIssueAuthorizationJob = viewModelScope.launch {
            val authJob = coroutineContext[Job]
            _uiState.update {
                it.copy(githubIssueAuthorization = GitHubIssueAuthorizationState.Starting)
            }
            try {
                val session = withContext(Dispatchers.IO) {
                    githubIssueAuthorizationRepository.requestDeviceAuthorization()
                }.getOrThrow()
                val qrBitmap = withContext(Dispatchers.Default) {
                    QrCodeGenerator.generate(session.verificationUriComplete, 420, margin = 1)
                }
                _uiState.update {
                    it.copy(
                        githubIssueAuthorization = GitHubIssueAuthorizationState.AwaitingApproval(
                            userCode = session.userCode,
                            verificationUri = session.verificationUri,
                            verificationUriComplete = session.verificationUriComplete,
                            qrBitmap = qrBitmap,
                            expiresAtMs = session.expiresAtMs
                        )
                    )
                }

                var intervalSeconds = session.intervalSeconds.coerceAtLeast(1L)
                while (System.currentTimeMillis() < session.expiresAtMs) {
                    delay(intervalSeconds * 1_000L)
                    when (
                        val pollResult = withContext(Dispatchers.IO) {
                            githubIssueAuthorizationRepository.pollDeviceAuthorization(session)
                        }
                    ) {
                        GitHubDevicePollResult.Pending -> Unit
                        is GitHubDevicePollResult.SlowDown -> {
                            intervalSeconds = pollResult.intervalSeconds.coerceAtLeast(1L)
                        }
                        is GitHubDevicePollResult.Authorized -> {
                            _uiState.update {
                                it.copy(
                                    githubIssueAuthorization =
                                        GitHubIssueAuthorizationState.Completing
                                )
                            }
                            withContext(Dispatchers.IO) {
                                githubIssueAuthorizationRepository.saveAuthorizedToken(
                                    pollResult.token
                                )
                                githubIssueReportingDataStore.setEnabled(true)
                            }
                            _uiState.update {
                                it.copy(
                                    githubIssueAuthorization =
                                        GitHubIssueAuthorizationState.Authorized
                                )
                            }
                            return@launch
                        }
                        is GitHubDevicePollResult.Failed -> {
                            _uiState.update {
                                it.copy(
                                    githubIssueAuthorization =
                                        GitHubIssueAuthorizationState.Error(pollResult.message)
                                )
                            }
                            return@launch
                        }
                    }
                }
                _uiState.update {
                    it.copy(
                        githubIssueAuthorization = GitHubIssueAuthorizationState.Error(
                            "The GitHub authorization code expired"
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        githubIssueAuthorization = GitHubIssueAuthorizationState.Error(
                            e.message?.takeIf { message -> message.isNotBlank() }
                                ?: "GitHub authorization could not be completed"
                        )
                    )
                }
            } finally {
                if (githubIssueAuthorizationJob === authJob) {
                    githubIssueAuthorizationJob = null
                }
            }
        }
    }

    private fun cancelGitHubIssueAuthorization() {
        githubIssueAuthorizationJob?.cancel()
        githubIssueAuthorizationJob = null
        _uiState.update {
            it.copy(githubIssueAuthorization = GitHubIssueAuthorizationState.Idle)
        }
    }

    override fun onCleared() {
        githubIssueAuthorizationJob?.cancel()
        super.onCleared()
    }
}
