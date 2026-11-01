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
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.VolumeProviderCompat
import com.nightcinder.c4mediaremote.MainActivity
import com.nightcinder.c4mediaremote.data.Room
import com.nightcinder.c4mediaremote.data.SettingsRepository
import com.nightcinder.c4mediaremote.net.ControllerClient
import com.nightcinder.c4mediaremote.net.StubControllerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground media service exposing a MediaSession with a remote VolumeProvider.
 * Every command is a stub that logs via StubControllerClient; nothing hits the network yet.
 */
class MediaRemoteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: SettingsRepository
    private lateinit var session: MediaSessionCompat

    @Volatile
    private var muted = false

    private val volumeProvider = object : VolumeProviderCompat(
        VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE,
        100,
        50,
    ) {
        override fun onAdjustVolume(direction: Int) {
            when {
                direction > 0 -> dispatch("volume_up") { it.volumeUp() }
                direction < 0 -> dispatch("volume_down") { it.volumeDown() }
            }
        }

        override fun onSetVolumeTo(volume: Int) {
            dispatch("volume_set") { it.volumeSet(volume) }
        }
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = dispatch("play") { it.play() }
        override fun onPause() = dispatch("pause") { it.pause() }
        override fun onSkipToNext() = dispatch("next") { it.next() }
        override fun onSkipToPrevious() = dispatch("prev") { it.prev() }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                ACTION_MUTE_TOGGLE -> {
                    val toMute = !muted
                    muted = toMute
                    dispatch("mute_toggle") { c -> if (toMute) c.mute() else c.unmute() }
                }
                ACTION_CYCLE_ROOM -> scope.launch {
                    val current = settings.settings.first()
                    if (current.roomLocked) {
                        Log.i(TAG, "STUB cycle_room ignored: room is locked")
                        return@launch
                    }
                    val next: Room = current.activeRoom.next()
                    settings.setActiveRoom(next)
                    buildClient().setActiveRoom(next)
                }
                ACTION_LOCK_ROOM -> scope.launch {
                    settings.toggleRoomLock()
                    Log.i(TAG, "STUB lock_room toggled")
                }
                else -> Log.w(TAG, "Unknown custom action: $action")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
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
        session.isActive = false
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun dispatch(name: String, block: suspend (ControllerClient) -> Result<*>) {
        scope.launch {
            block(buildClient()).onFailure { Log.w(TAG, "$name failed", it) }
        }
    }

    private suspend fun buildClient(): ControllerClient {
        val s = settings.settings.first()
        return StubControllerClient(
            baseUrl = "http://${s.address}:${s.port}",
            token = settings.loadToken(),
        )
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
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_MUTE_TOGGLE, "Mute toggle", android.R.drawable.ic_lock_silent_mode,
                ).build(),
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_CYCLE_ROOM, "Cycle room", android.R.drawable.ic_menu_rotate,
                ).build(),
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_LOCK_ROOM, "Lock room", android.R.drawable.ic_lock_idle_lock,
                ).build(),
            )
            .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 1f)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("C4 Media Remote")
            .setContentText("Session active (stubs only)")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken),
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Media remote", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MediaRemoteService"
        private const val CHANNEL_ID = "media_remote"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "com.nightcinder.c4mediaremote.action.STOP"
        const val ACTION_MUTE_TOGGLE = "mute_toggle"
        const val ACTION_CYCLE_ROOM = "cycle_room"
        const val ACTION_LOCK_ROOM = "lock_room"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MediaRemoteService::class.java))
        }
    }
}
