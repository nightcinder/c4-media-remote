package com.nightcinder.c4mediaremote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.VolumeProviderCompat
import androidx.media.session.MediaButtonReceiver
import com.nightcinder.c4mediaremote.MainActivity
import com.nightcinder.c4mediaremote.R
import com.nightcinder.c4mediaremote.data.Room
import com.nightcinder.c4mediaremote.data.SettingsRepository
import com.nightcinder.c4mediaremote.net.ControllerClient
import com.nightcinder.c4mediaremote.net.HttpControllerClient
import com.nightcinder.c4mediaremote.net.StubControllerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground media service exposing a MediaSession with a remote VolumeProvider.
 * Transport and custom actions call the Media Remote Bridge; status is polled to
 * drive the notification subtitle and the MediaSession metadata.
 */
class MediaRemoteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: SettingsRepository
    private lateinit var session: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager

    @Volatile private var muted = false
    @Volatile private var roomLocked = false
    @Volatile private var volumeLevel = 50
    @Volatile private var activeRoomName = ""
    @Volatile private var sourceName: String? = null
    /** Now-playing data from the bridge; npTitle null means no fresh data, so the room-based fallback is used. */
    @Volatile private var npTitle: String? = null
    @Volatile private var npArtist: String? = null
    @Volatile private var npApp: String? = null
    @Volatile private var powerOn: Boolean? = null
    @Volatile private var bridgeReportsVolume = false
    /** Volume ops queued or being sent; while non-zero, polled volume does not overwrite the local value. */
    private val pendingVolumeOps = AtomicInteger(0)
    @Volatile private var connectionError: String? = null
    /** Last known play state; drives the MediaSession PlaybackState so the UI offers the right button. */
    @Volatile private var playing = true

    private val volumeOps = Channel<VolumeOp>(Channel.UNLIMITED)

    private val volumeProvider = object : VolumeProviderCompat(
        VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE,
        100,
        50,
    ) {
        override fun onAdjustVolume(direction: Int) {
            when {
                direction > 0 -> enqueueVolume(VolumeOp.Up)
                direction < 0 -> enqueueVolume(VolumeOp.Down)
            }
        }

        override fun onSetVolumeTo(volume: Int) {
            enqueueVolume(VolumeOp.Set(volume.coerceIn(0, 100)))
        }
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = transport("play", { it.play() }) { playing = true }
        override fun onPause() = transport("pause", { it.pause() }) { playing = false }
        override fun onSkipToNext() = dispatch("next") { it.next() }
        override fun onSkipToPrevious() = dispatch("prev") { it.prev() }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                ACTION_MUTE_TOGGLE -> scope.launch {
                    val toMute = !muted
                    // Optimistic: flip and republish immediately so the icon reflects the new state.
                    muted = toMute
                    updatePlaybackState()
                    val result = guarded {
                        val c = buildClient()
                        if (toMute) c.mute() else c.unmute()
                    }
                    result.onFailure {
                        muted = !toMute
                        updatePlaybackState()
                    }
                    report(if (toMute) "mute" else "unmute", result)
                }
                ACTION_CYCLE_ROOM -> scope.launch {
                    val current = settings.settings.first()
                    if (current.roomLocked) {
                        Log.i(TAG, "cycle_room ignored: room is locked")
                        return@launch
                    }
                    val next: Room = current.activeRoom.next()
                    val result = guarded { buildClient().setActiveRoom(next) }
                    result.onSuccess {
                        settings.setActiveRoom(next)
                        activeRoomName = next.label
                        updateMetadata()
                    }
                    report("set_active_room", result)
                }
                ACTION_LOCK_ROOM -> scope.launch {
                    settings.toggleRoomLock()
                    roomLocked = settings.settings.first().roomLocked
                    updatePlaybackState()
                }
                else -> Log.w(TAG, "Unknown custom action: $action")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        session = MediaSessionCompat(this, "C4MediaRemote").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setPlaybackState(buildPlaybackState())
            setPlaybackToRemote(volumeProvider)
            setCallback(sessionCallback)
            isActive = true
        }
        updateMetadata()
        startVolumeWorker()
        startStatusPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            MediaButtonReceiver.handleIntent(session, intent)
        }
        // Always promote to foreground, including for media button intents, so the service never crashes.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    /** Runs one transport or room command in the background and reports the outcome. */
    private fun dispatch(name: String, block: suspend (ControllerClient) -> Result<Unit>) {
        scope.launch {
            report(name, guarded { block(buildClient()) })
        }
    }

    /** Like [dispatch], but on success updates the play state and republishes the PlaybackState. */
    private fun transport(
        name: String,
        block: suspend (ControllerClient) -> Result<Unit>,
        onSuccess: () -> Unit,
    ) {
        scope.launch {
            val result = guarded { block(buildClient()) }
            result.onSuccess {
                onSuccess()
                updatePlaybackState()
            }
            report(name, result)
        }
    }

    private fun updatePlaybackState() {
        session.setPlaybackState(buildPlaybackState())
        refreshNotification()
    }

    /**
     * Volume requests are debounced: the worker sends at most one request every
     * MIN_VOLUME_INTERVAL_MS. When an absolute set is queued, earlier relative steps
     * are dropped (the set supersedes them) but the set itself and anything after it
     * are always sent, so the final value is never lost.
     */
    private fun startVolumeWorker() {
        scope.launch {
            while (isActive) {
                val batch = mutableListOf(volumeOps.receive())
                while (true) {
                    val next = volumeOps.tryReceive().getOrNull() ?: break
                    batch += next
                }
                val lastSet = batch.indexOfLast { it is VolumeOp.Set }
                val toSend = if (lastSet >= 0) batch.subList(lastSet, batch.size) else batch
                try {
                    for (op in toSend) {
                        val (name, result) = when (op) {
                            VolumeOp.Up -> "volume_up" to guarded { buildClient().volumeUp() }
                            VolumeOp.Down -> "volume_down" to guarded { buildClient().volumeDown() }
                            is VolumeOp.Set -> "volume_set" to guarded { buildClient().volumeSet(op.level) }
                        }
                        report(name, result)
                        delay(MIN_VOLUME_INTERVAL_MS)
                    }
                } finally {
                    // Sent or superseded: these ops are no longer pending.
                    pendingVolumeOps.addAndGet(-batch.size)
                }
            }
        }
    }

    private fun enqueueVolume(op: VolumeOp) {
        // Optimistic local value so the system volume UI responds immediately.
        // The next status poll replaces it with the bridge's value.
        val step = if (bridgeReportsVolume) OPTIMISTIC_STEP else VOLUME_STEP_ESTIMATE
        val level = when (op) {
            VolumeOp.Up -> (volumeLevel + step).coerceIn(0, 100)
            VolumeOp.Down -> (volumeLevel - step).coerceIn(0, 100)
            is VolumeOp.Set -> op.level
        }
        volumeLevel = level
        volumeProvider.setCurrentVolume(level)
        pendingVolumeOps.incrementAndGet()
        volumeOps.trySend(op)
    }

    /** Polls the bridge once on start and then every STATUS_POLL_INTERVAL_MS. */
    private fun startStatusPolling() {
        scope.launch {
            while (isActive) {
                val result = guarded { buildClient().status() }
                result.onSuccess { status ->
                    connectionError = null
                    activeRoomName = status.activeRoom
                    sourceName = status.source
                    npTitle = status.npTitle
                    npArtist = status.npArtist
                    npApp = status.npApp
                    powerOn = status.power
                    status.muted?.let { muted = it }
                    // Bridge play state wins when reported; otherwise keep the locally tracked guess.
                    status.npPlaying?.let { playing = it }
                    bridgeReportsVolume = status.volume != null
                    status.volume?.let { reported ->
                        // Skip while our own volume ops are still queued or in flight,
                        // otherwise the poll would snap the slider back.
                        if (pendingVolumeOps.get() == 0) {
                            volumeLevel = reported
                            volumeProvider.setCurrentVolume(reported)
                        }
                    }
                    syncRoomFromBridge(status.activeRoom)
                    updateMetadata()
                }.onFailure { e ->
                    connectionError = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "status poll failed: ${e.javaClass.simpleName}")
                }
                refreshNotification()
                delay(STATUS_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun syncRoomFromBridge(name: String) {
        val room = Room.entries.firstOrNull { it.label.equals(name, ignoreCase = true) } ?: return
        if (settings.settings.first().activeRoom != room) settings.setActiveRoom(room)
    }

    private fun report(name: String, result: Result<*>) {
        result.onSuccess { connectionError = null }
            .onFailure { e ->
                connectionError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "$name failed: ${e.javaClass.simpleName}")
            }
        refreshNotification()
    }

    private suspend fun <T> guarded(block: suspend () -> Result<T>): Result<T> =
        try {
            block()
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun buildClient(): ControllerClient {
        val s = settings.settings.first()
        val baseUrl = "http://${s.address}:${s.port}"
        val token = settings.loadToken()
        return if (USE_STUB_CONTROLLER) {
            StubControllerClient(baseUrl, token)
        } else {
            HttpControllerClient(baseUrl, token)
        }
    }

    private fun subtitle(): String {
        connectionError?.let { return "Controller unreachable: $it" }
        if (powerOn == false) return "Room off"
        if (activeRoomName.isBlank()) return "Connected"
        val base = if (npTitle != null) {
            npApp?.let { "$activeRoomName - $it" } ?: activeRoomName
        } else {
            sourceName?.let { "Room: $activeRoomName - $it" } ?: "Room: $activeRoomName"
        }
        return if (roomLocked) "$base (locked)" else base
    }

    private fun updateMetadata() {
        val title = npTitle
        val metadata = if (title != null) {
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    npArtist ?: npApp ?: sourceName ?: "Control4",
                )
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, activeRoomName)
                .build()
        } else {
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, activeRoomName.ifBlank { "C4 Media Remote" })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, sourceName ?: "Control4")
                .build()
        }
        session.setMetadata(metadata)
        updatePlaybackState()
    }

    private fun refreshNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildPlaybackState(): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
            )
            .addCustomAction(
                if (muted) {
                    PlaybackStateCompat.CustomAction.Builder(
                        ACTION_MUTE_TOGGLE, "Unmute", android.R.drawable.ic_lock_silent_mode,
                    ).build()
                } else {
                    PlaybackStateCompat.CustomAction.Builder(
                        ACTION_MUTE_TOGGLE, "Mute", android.R.drawable.ic_lock_silent_mode_off,
                    ).build()
                },
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_CYCLE_ROOM, "Cycle room", android.R.drawable.ic_menu_rotate,
                ).build(),
            )
            .addCustomAction(
                if (roomLocked) {
                    PlaybackStateCompat.CustomAction.Builder(
                        ACTION_LOCK_ROOM, "Unlock room", android.R.drawable.ic_lock_idle_lock,
                    ).build()
                } else {
                    PlaybackStateCompat.CustomAction.Builder(
                        ACTION_LOCK_ROOM, "Lock room", R.drawable.ic_room_unlocked,
                    ).build()
                },
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                0L,
                1f,
            )
            .build()

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaRemoteService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val prev = mediaAction(
            android.R.drawable.ic_media_previous, "Previous", PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
        )
        val playPause = if (playing) {
            mediaAction(android.R.drawable.ic_media_pause, "Pause", PlaybackStateCompat.ACTION_PAUSE)
        } else {
            mediaAction(android.R.drawable.ic_media_play, "Play", PlaybackStateCompat.ACTION_PLAY)
        }
        val next = mediaAction(
            android.R.drawable.ic_media_next, "Next", PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("C4 Media Remote")
            .setContentText(subtitle())
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .addAction(prev)
            .addAction(playPause)
            .addAction(next)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun mediaAction(icon: Int, title: String, action: Long): NotificationCompat.Action =
        NotificationCompat.Action(
            icon,
            title,
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, action),
        )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Media remote", NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)
    }

    private sealed interface VolumeOp {
        data object Up : VolumeOp
        data object Down : VolumeOp
        data class Set(val level: Int) : VolumeOp
    }

    companion object {
        private const val TAG = "MediaRemoteService"
        private const val CHANNEL_ID = "media_remote"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "com.nightcinder.c4mediaremote.action.STOP"
        const val ACTION_MUTE_TOGGLE = "mute_toggle"
        const val ACTION_CYCLE_ROOM = "cycle_room"
        const val ACTION_LOCK_ROOM = "lock_room"

        const val STATUS_POLL_INTERVAL_MS = 3_000L
        const val MIN_VOLUME_INTERVAL_MS = 200L
        /** Optimistic local nudge per volume key press while the bridge reports volume; corrected on the next poll. */
        const val OPTIMISTIC_STEP = 2
        /** Fallback local step, used only when status has no volume field. */
        const val VOLUME_STEP_ESTIMATE = 5

        /** Debug switch: route all calls to StubControllerClient (no network). Keep false for real use. */
        const val USE_STUB_CONTROLLER = false

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MediaRemoteService::class.java))
        }
    }
}
