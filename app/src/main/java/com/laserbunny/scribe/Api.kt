package com.laserbunny.scribe

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** A failure worth showing as is ("Scribe isn't connected…"). */
class ScribeError(message: String, val code: String? = null, val status: Int = 0) : IOException(message)

/**
 * Scribe's server step (the `scribe` function beside Inkwell's). Every call
 * blocks, so run them off the main thread.
 */
class Api(private val store: Store) {

    fun modes(): List<Mode> {
        val o = call("GET", "/scribe/modes", null, null)
        val a = o.optJSONArray("modes") ?: JSONArray()
        return (0 until a.length()).map { Mode.from(a.getJSONObject(it)) }
    }

    /** Writes out a recording and rewrites it in a mode: original + text. */
    fun dictate(audio: File, mode: String, app: String, durationMs: Long): JSONObject =
        upload(audio, mapOf("mode" to mode, "app" to app, "duration" to durationMs.toString()))

    /** The same words again in another mode. */
    fun redo(original: String, mode: String, app: String): JSONObject =
        call("POST", "/scribe", JSONObject().put("action", "redo").put("original", original).put("mode", mode).put("app", app), null)

    /** A mode drafted from what was said (not saved yet); `current` is the mode being changed. */
    fun makeMode(audio: File, current: Mode?, durationMs: Long): JSONObject =
        upload(audio, buildMap {
            put("action", "make-mode")
            put("duration", durationMs.toString())
            if (current != null) put("current", current.toJson().toString())
        })

    fun saveMode(mode: Mode): Mode =
        Mode.from(call("POST", "/scribe", JSONObject().put("action", "save-mode").put("mode", mode.toJson()), null).getJSONObject("mode"))

    fun deleteMode(id: String) {
        call("POST", "/scribe", JSONObject().put("action", "delete-mode").put("id", id), null)
    }

    private fun upload(audio: File, fields: Map<String, String>): JSONObject {
        val boundary = "scribe-" + UUID.randomUUID()
        return call("POST", "/scribe", null, boundary) { out ->
            for ((k, v) in fields) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
            }
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"scribe.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
            audio.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
    }

    private fun call(
        method: String,
        path: String,
        json: JSONObject?,
        boundary: String?,
        writeBody: ((java.io.OutputStream) -> Unit)? = null,
    ): JSONObject {
        if (!store.connected) throw ScribeError("Scribe isn't connected yet. Open Scribe and tap Connect.", "not-connected")
        val conn = URL(store.url.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            // A long ramble takes a while to write out and rewrite.
            conn.readTimeout = 240_000
            conn.setRequestProperty("Authorization", "Bearer ${store.token}")
            if (json != null || writeBody != null) {
                conn.doOutput = true
                if (boundary != null) {
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.setChunkedStreamingMode(64 * 1024)
                } else {
                    conn.setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { out ->
                    if (writeBody != null) writeBody(out) else out.write(json.toString().toByteArray())
                }
            }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val o = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
            if (status !in 200..299) {
                val message = o.optString("error").ifEmpty { "Scribe's server answered $status. Try again." }
                throw ScribeError(message, o.optString("code").ifEmpty { null }, status)
            }
            return o
        } catch (e: ScribeError) {
            throw e
        } catch (e: IOException) {
            throw ScribeError("Couldn't reach Scribe. Check your connection; your recording is kept.", "offline")
        } finally {
            conn.disconnect()
        }
    }
}
