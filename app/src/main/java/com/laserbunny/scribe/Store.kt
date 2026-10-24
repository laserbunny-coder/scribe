package com.laserbunny.scribe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A mode: how a dictation is rewritten (Text message, Email, …), and which
 * Claude does it ("haiku", "sonnet" or "opus"; empty = keep the server's).
 */
data class Mode(val id: String, val name: String, val prompt: String, val apps: List<String>, val model: String = "") {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("prompt", prompt).put("apps", JSONArray(apps))
        .apply { if (model.isNotEmpty()) put("model", model) }

    companion object {
        fun from(o: JSONObject): Mode {
            val apps = o.optJSONArray("apps") ?: JSONArray()
            return Mode(
                o.optString("id"), o.optString("name"), o.optString("prompt"),
                (0 until apps.length()).map { apps.getString(it) },
                o.optString("model"),
            )
        }
    }
}

/** A finished dictation that hasn't been typed in yet, kept until it is. */
data class Result(val original: String, val text: String, val mode: String, val field: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("original", original).put("text", text).put("mode", mode).put("field", field)

    companion object {
        fun from(o: JSONObject) = Result(o.optString("original"), o.optString("text"), o.optString("mode"), o.optString("field"))
    }
}

/** What Scribe keeps on the phone: the connection, the modes, and anything not yet typed in. */
class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("scribe", Context.MODE_PRIVATE)

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v).apply()

    /** The server's address, e.g. https://xyz.supabase.co/functions/v1 */
    var url: String
        get() = p.getString("url", "") ?: ""
        set(v) = p.edit().putString("url", v).apply()

    val connected get() = token.isNotEmpty() && url.isNotEmpty()

    var modes: List<Mode>
        get() {
            val raw = p.getString("modes", null) ?: return STARTER
            return try {
                val a = JSONArray(raw)
                (0 until a.length()).map { Mode.from(a.getJSONObject(it)) }.ifEmpty { STARTER }
            } catch (e: Exception) { STARTER }
        }
        set(v) = p.edit().putString("modes", JSONArray(v.map { it.toJson() }).toString()).apply()

    /** The mode picked by hand in an app, which wins over the automatic one there. */
    fun chosenFor(app: String): String? = p.getString("chosen:$app", null)
    fun choose(app: String, mode: String) = p.edit().putString("chosen:$app", mode).apply()

    /** The mode for an app: picked by hand there, else the first mode listing it, else the first. */
    fun modeFor(app: String): Mode {
        val all = modes
        return all.firstOrNull { it.id == chosenFor(app) }
            ?: all.firstOrNull { app in it.apps }
            ?: all.first()
    }

    /** A recording that hasn't been written out yet (the file stays until it has). */
    var pendingAudio: String?
        get() = p.getString("pendingAudio", null)
        set(v) = p.edit().putString("pendingAudio", v).apply()
    var pendingMs: Long
        get() = p.getLong("pendingMs", 0)
        set(v) = p.edit().putLong("pendingMs", v).apply()

    /** The last finished dictation, kept so it can be typed in again or copied. */
    var last: Result?
        get() = p.getString("last", null)?.let { try { Result.from(JSONObject(it)) } catch (e: Exception) { null } }
        set(v) = p.edit().putString("last", v?.toJson()?.toString()).apply()

    /** True while the last result is still waiting to be typed in. */
    var lastWaiting: Boolean
        get() = p.getBoolean("lastWaiting", false)
        set(v) = p.edit().putBoolean("lastWaiting", v).apply()

    companion object {
        val MODELS = listOf(
            "haiku" to "Haiku: quick and cheap, for texts",
            "sonnet" to "Sonnet: in between, for emails",
            "opus" to "Opus: the best writing, for ideas and characters",
        )
        fun modelName(id: String) = when (id) { "haiku" -> "Haiku"; "sonnet" -> "Sonnet"; else -> "Opus" }

        // Shown until the account's own modes have been fetched once.
        val STARTER = listOf(
            Mode("text", "Text", "", listOf("com.google.android.apps.messaging")),
            Mode("email", "Email", "", listOf("com.google.android.gm")),
            Mode("clean", "Clean", "", emptyList()),
            Mode("idea", "Idea", "", emptyList()),
        )
    }
}
