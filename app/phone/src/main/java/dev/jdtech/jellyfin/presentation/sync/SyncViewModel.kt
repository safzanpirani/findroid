package dev.jdtech.jellyfin.presentation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.sessionApi
import timber.log.Timber
import javax.inject.Inject

data class SyncUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val sessions: List<ActiveSession> = emptyList(),
    val currentSyncTarget: String? = null,
    val currentSyncNowPlaying: String? = null,
)

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val jellyfinApi: JellyfinApi,
    private val syncService: SyncService,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState = _uiState.asStateFlow()
    
    init {
        loadCurrentSyncState()
        refresh()
        startAutoRefresh()
    }
    
    private fun loadCurrentSyncState() {
        val syncEnabled = appPreferences.getValue(appPreferences.syncEnabled)
        val targetUser = appPreferences.getValue(appPreferences.syncTargetUser)
        
        if (syncEnabled && !targetUser.isNullOrBlank()) {
            _uiState.update { 
                it.copy(currentSyncTarget = targetUser) 
            }
        }
        
        // Observe sync state
        viewModelScope.launch {
            syncService.syncState.collect { state ->
                _uiState.update {
                    it.copy(
                        currentSyncTarget = if (syncService.isSyncing.value) state.targetUserName else null,
                        currentSyncNowPlaying = state.nowPlaying,
                    )
                }
            }
        }
    }
    
    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(5000) // Refresh every 5 seconds
                fetchSessions()
            }
        }
    }
    
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            fetchSessions()
        }
    }
    
    private suspend fun fetchSessions() {
        try {
            val sessions = withContext(Dispatchers.IO) {
                jellyfinApi.sessionApi.getSessions().content
            }
            
            val activeSessions = sessions
                .filter { it.nowPlayingItem != null }
                .map { session ->
                    val nowPlaying = session.nowPlayingItem!!
                    val positionTicks = session.playState?.positionTicks ?: 0
                    val durationTicks = nowPlaying.runTimeTicks ?: 0
                    
                    val nowPlayingText = buildString {
                        if (nowPlaying.seriesName != null) {
                            append(nowPlaying.seriesName)
                            append(" - S")
                            append(nowPlaying.parentIndexNumber ?: 0)
                            append("E")
                            append(nowPlaying.indexNumber ?: 0)
                            append(": ")
                            append(nowPlaying.name ?: "")
                        } else {
                            append(nowPlaying.name ?: "Unknown")
                        }
                    }
                    
                    val progressText = buildString {
                        val posSeconds = positionTicks / 10_000_000
                        val durSeconds = durationTicks / 10_000_000
                        
                        val posMinutes = posSeconds / 60
                        val posSecs = posSeconds % 60
                        val durMinutes = durSeconds / 60
                        val durSecs = durSeconds % 60
                        
                        append("%d:%02d".format(posMinutes, posSecs))
                        append(" / ")
                        append("%d:%02d".format(durMinutes, durSecs))
                    }
                    
                    ActiveSession(
                        userName = session.userName ?: "Unknown",
                        nowPlaying = nowPlayingText,
                        itemId = nowPlaying.id.toString(),
                        itemKind = nowPlaying.type?.serialName ?: "Movie",
                        positionTicks = positionTicks,
                        durationTicks = durationTicks,
                        isPaused = session.playState?.isPaused ?: false,
                        progressText = progressText,
                    )
                }
            
            _uiState.update { 
                it.copy(
                    isLoading = false, 
                    error = null, 
                    sessions = activeSessions
                ) 
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch sessions")
            _uiState.update { 
                it.copy(
                    isLoading = false, 
                    error = e.message ?: "Failed to load sessions"
                ) 
            }
        }
    }
    
    fun startSyncWith(userName: String) {
        appPreferences.setValue(appPreferences.syncEnabled, true)
        appPreferences.setValue(appPreferences.syncTargetUser, userName)
        
        val threshold = appPreferences.getValue(appPreferences.syncThreshold)
        val offset = appPreferences.getValue(appPreferences.syncOffset)
        
        syncService.configure(userName, threshold, offset)
        
        viewModelScope.launch {
            syncService.startSync()
        }
        
        _uiState.update { it.copy(currentSyncTarget = userName) }
    }
    
    fun stopSync() {
        syncService.stopSync()
        appPreferences.setValue(appPreferences.syncEnabled, false)
        _uiState.update { it.copy(currentSyncTarget = null, currentSyncNowPlaying = null) }
    }
}
