package com.nightcinder.c4mediaremote.net

import android.util.Log
import com.nightcinder.c4mediaremote.data.Room

/**
 * Debug-only stub: logs what would be sent and reports success. No network traffic.
 * Enabled by MediaRemoteService.USE_STUB_CONTROLLER. The token is never logged.
 */
class StubControllerClient(
    private val baseUrl: String,
    private val token: String,
) : ControllerClient {

    private fun stub(command: String, query: String = ""): Result<Unit> {
        Log.i(TAG, "STUB POST $baseUrl/v1/command/$command$query (token ${if (token.isNotEmpty()) "set" else "missing"})")
        return Result.success(Unit)
    }

    override suspend fun play() = stub("play")
    override suspend fun pause() = stub("pause")
    override suspend fun toggle() = stub("toggle")
    override suspend fun next() = stub("next")
    override suspend fun prev() = stub("prev")
    override suspend fun mute() = stub("mute")
    override suspend fun unmute() = stub("unmute")
    override suspend fun volumeUp() = stub("volume_up")
    override suspend fun volumeDown() = stub("volume_down")
    override suspend fun volumeSet(level: Int) = stub("volume_set", "?level=${level.coerceIn(0, 100)}")
    override suspend fun setActiveRoom(room: Room) = stub("set_active_room", "?room=${room.label}")

    override suspend fun status(): Result<ControllerStatus> {
        Log.i(TAG, "STUB GET $baseUrl/v1/status")
        return Result.success(
            ControllerStatus(
                ok = true,
                version = "stub",
                activeRoom = "",
                nowPlaying = "unknown",
                lockRoom = "",
                volume = null,
                muted = null,
                power = null,
                source = null,
            ),
        )
    }

    private companion object {
        const val TAG = "MediaRemoteStub"
    }
}
