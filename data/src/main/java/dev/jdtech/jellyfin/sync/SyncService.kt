package dev.jdtech.jellyfin.sync

import dev.jdtech.jellyfin.api.JellyfinApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.SessionInfoDto
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class holding the current sync state from the target user
 */
data class SyncState(
    val targetUserName: String = "",
    val itemId: UUID? = null,
    val positionTicks: Long = 0,
    val isPaused: Boolean = false,
    val nowPlaying: String = "",
    val isTargetWatching: Boolean = false,
)

/**
 * Event sent when sync determines the player should seek/pause
 */
sealed class SyncEvent {
    data class SyncPlayback(
        val positionTicks: Long,
        val itemId: UUID,
        val isPaused: Boolean,
        val shouldSeek: Boolean,
    ) : SyncEvent()
    
    data class EpisodeChanged(
        val newItemId: UUID,
        val nowPlaying: String,
    ) : SyncEvent()
    
    data object TargetStoppedWatching : SyncEvent()
}

/**
 * Service that polls Jellyfin Sessions API to sync with a target user's playback
 */
@Singleton
class SyncService @Inject constructor(
    private val jellyfinApi: JellyfinApi,
) {
    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    
    private val _syncEvents = MutableSharedFlow<SyncEvent>()
    val syncEvents: SharedFlow<SyncEvent> = _syncEvents.asSharedFlow()
    
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()
    
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()
    
    private var targetUserName: String = ""
    private var syncThresholdSeconds: Int = 3
    private var syncOffsetSeconds: Int = 0
    private var lastKnownItemId: UUID? = null
    private var lastKnownUserWatching: Boolean = false
    
    /**
     * Configure sync settings
     */
    fun configure(
        targetUser: String,
        thresholdSeconds: Int = 3,
        offsetSeconds: Int = 0,
    ) {
        this.targetUserName = targetUser
        this.syncThresholdSeconds = thresholdSeconds
        this.syncOffsetSeconds = offsetSeconds
    }
    
    /**
     * Start the sync polling loop
     */
    suspend fun startSync() {
        if (_isSyncing.value) return
        if (targetUserName.isBlank()) {
            _syncError.value = "Target user not configured"
            return
        }
        
        _isSyncing.value = true
        _syncError.value = null
        
        Timber.d("Starting sync with target user: $targetUserName")
        
        while (_isSyncing.value) {
            try {
                pollAndSync()
            } catch (e: Exception) {
                Timber.e(e, "Sync error")
                _syncError.value = e.message
            }
            delay(2000) // Poll every 2 seconds
        }
    }
    
    /**
     * Stop the sync polling
     */
    fun stopSync() {
        Timber.d("Stopping sync")
        _isSyncing.value = false
        lastKnownItemId = null
        lastKnownUserWatching = false
        _syncState.value = SyncState()
    }
    
    /**
     * Poll the Sessions API and emit sync events
     */
    private suspend fun pollAndSync() = withContext(Dispatchers.IO) {
        try {
            val sessionsResponse = jellyfinApi.sessionApi.getSessions()
            val sessions = sessionsResponse.content
            
            // Find target user's session
            val targetSession = sessions.find { session ->
                session.userName?.equals(targetUserName, ignoreCase = true) == true &&
                    session.nowPlayingItem != null
            }
            
            if (targetSession == null) {
                // Target stopped watching
                if (lastKnownUserWatching) {
                    Timber.d("Target user stopped watching")
                    _syncEvents.emit(SyncEvent.TargetStoppedWatching)
                }
                lastKnownUserWatching = false
                _syncState.value = SyncState(
                    targetUserName = targetUserName,
                    isTargetWatching = false,
                )
                return@withContext
            }
            
            val nowPlayingItem = targetSession.nowPlayingItem!!
            val playState = targetSession.playState
            
            val itemId = nowPlayingItem.id
            val positionTicks = playState?.positionTicks ?: 0
            val isPaused = playState?.isPaused ?: false
            
            // Build now playing string
            val nowPlaying = if (nowPlayingItem.seriesName != null) {
                "${nowPlayingItem.seriesName} - S${nowPlayingItem.parentIndexNumber}E${nowPlayingItem.indexNumber}"
            } else {
                nowPlayingItem.name ?: "Unknown"
            }
            
            // Check for episode change
            val isDifferentItem = lastKnownItemId != null && lastKnownItemId != itemId && lastKnownUserWatching
            if (isDifferentItem) {
                Timber.d("Episode changed from $lastKnownItemId to $itemId")
                _syncEvents.emit(SyncEvent.EpisodeChanged(itemId, nowPlaying))
            }
            
            lastKnownItemId = itemId
            lastKnownUserWatching = true
            
            // Update sync state
            _syncState.value = SyncState(
                targetUserName = targetUserName,
                itemId = itemId,
                positionTicks = positionTicks,
                isPaused = isPaused,
                nowPlaying = nowPlaying,
                isTargetWatching = true,
            )
            
            // Apply offset
            val adjustedPositionTicks = positionTicks + (syncOffsetSeconds * 10_000_000L)
            
            // Emit sync event
            _syncEvents.emit(
                SyncEvent.SyncPlayback(
                    positionTicks = adjustedPositionTicks,
                    itemId = itemId,
                    isPaused = isPaused,
                    shouldSeek = true, // Will be evaluated by player
                )
            )
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to poll sessions")
            throw e
        }
    }
    
    /**
     * Calculate the sync difference in seconds
     */
    fun calculateSyncDiff(currentPositionTicks: Long): Float {
        val targetPosition = _syncState.value.positionTicks + (syncOffsetSeconds * 10_000_000L)
        val diffTicks = currentPositionTicks - targetPosition
        return diffTicks / 10_000_000f
    }
    
    /**
     * Check if we should sync based on threshold
     */
    fun shouldSync(currentPositionTicks: Long): Boolean {
        val diffSeconds = kotlin.math.abs(calculateSyncDiff(currentPositionTicks))
        return diffSeconds > syncThresholdSeconds
    }
}
