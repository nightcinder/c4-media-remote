package com.nightcinder.c4mediaremote.net

import com.nightcinder.c4mediaremote.data.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A request reached the bridge but it reported a failure (HTTP error or ok=false). */
class ControllerException(message: String, val httpCode: Int? = null) : IOException(message)

/**
 * Real client over plain HTTP to the Media Remote Bridge.
 * Runs every request on Dispatchers.IO with short timeouts. The token is sent only
 * in the Authorization header and is never logged or included in error messages.
 */
class HttpControllerClient(
    baseUrl: String,
    private val token: String,
) : ControllerClient {

    private val base = baseUrl.trimEnd('/')

    override suspend fun play() = command("play")
    override suspend fun pause() = command("pause")
    override suspend fun toggle() = command("toggle")
    override suspend fun next() = command("next")
    override suspend fun prev() = command("prev")
    override suspend fun mute() = command("mute")
    override suspend fun unmute() = command("unmute")
    override suspend fun volumeUp() = command("volume_up")
    override suspend fun volumeDown() = command("volume_down")

    override suspend fun volumeSet(level: Int) =
        command("volume_set", "level" to level.coerceIn(0, 100).toString())

    override suspend fun setActiveRoom(room: Room) =
        command("set_active_room", "room" to room.label)

    override suspend fun status(): Result<ControllerStatus> = guarded {
        val json = JSONObject(request("GET", "/v1/status"))
        if (!json.optBoolean("ok", false)) {
            throw ControllerException(json.optString("error", "status not ok"))
        }
        ControllerStatus(
            ok = true,
            version = json.optString("version", "unknown"),
            activeRoom = json.optString("active_room", ""),
            nowPlaying = json.optString("now_playing", "unknown"),
            lockRoom = json.optString("lock_room", ""),
            volume = if (json.has("volume") && !json.isNull("volume"))
                json.optInt("volume").coerceIn(0, 100) else null,
            muted = if (json.has("muted") && !json.isNull("muted")) json.optBoolean("muted") else null,
            power = if (json.has("power") && !json.isNull("power")) json.optBoolean("power") else null,
            source = json.optString("source", "").ifBlank { null },
        )
    }

    private suspend fun command(name: String, vararg query: Pair<String, String>): Result<Unit> = guarded {
        val qs = query.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val path = "/v1/command/$name" + if (qs.isEmpty()) "" else "?$qs"
        val json = JSONObject(request("POST", path))
        if (!json.optBoolean("ok", false)) {
            throw ControllerException(json.optString("error", "command $name failed"))
        }
    }

    private suspend fun request(method: String, path: String): String = withContext(Dispatchers.IO) {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (method == "POST") {
                // Empty body; opening and closing the stream sends Content-Length: 0.
                conn.doOutput = true
                conn.outputStream.use { }
            }
            val code = conn.responseCode
            val ok = code in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) {
                val reason = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                throw ControllerException(
                    reason.ifBlank { httpReason(code) },
                    httpCode = code,
                )
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    private fun httpReason(code: Int): String = when (code) {
        401 -> "unauthorized (check token)"
        405 -> "method not allowed"
        400 -> "bad request"
        else -> "HTTP $code"
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 5_000
    }
}
