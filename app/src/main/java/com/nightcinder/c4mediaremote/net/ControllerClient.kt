package com.nightcinder.c4mediaremote.net

import com.nightcinder.c4mediaremote.data.Room

/** Parsed body of GET /v1/status. */
data class ControllerStatus(
    val ok: Boolean,
    val version: String,
    val activeRoom: String,
    val nowPlaying: String,
    val lockRoom: String,
    /** 0-100 when the bridge reports it (driver 0.3.0+). */
    val volume: Int? = null,
    val muted: Boolean? = null,
    val power: Boolean? = null,
    /** Display name of the selected source, when the bridge reports one. */
    val source: String? = null,
    /** Now-playing title from the bridge (driver 0.4.0+); null when no fresh data. */
    val npTitle: String? = null,
    /** Now-playing artist from the bridge; null when absent. */
    val npArtist: String? = null,
    /** Now-playing app name from the bridge; null when absent. */
    val npApp: String? = null,
    /** Real play state from the bridge; null when the bridge does not report it. */
    val npPlaying: Boolean? = null,
)

/**
 * Client for the Control4 "Media Remote Bridge" driver on the EA-1.
 *
 * Wire contract: GET /v1/status and POST /v1/command/<name>, both with
 * "Authorization: Bearer <token>". Level and room go in query params.
 * Implementations: [HttpControllerClient] (real), [StubControllerClient] (debug only).
 */
interface ControllerClient {
    suspend fun play(): Result<Unit>
    suspend fun pause(): Result<Unit>
    suspend fun toggle(): Result<Unit>
    suspend fun next(): Result<Unit>
    suspend fun prev(): Result<Unit>
    suspend fun mute(): Result<Unit>
    suspend fun unmute(): Result<Unit>
    suspend fun volumeUp(): Result<Unit>
    suspend fun volumeDown(): Result<Unit>
    suspend fun volumeSet(level: Int): Result<Unit>
    suspend fun setActiveRoom(room: Room): Result<Unit>
    suspend fun status(): Result<ControllerStatus>
}
