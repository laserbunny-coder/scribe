package com.laserbunny.scribe

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** What a recording is for: words to type in, or a mode described out loud. */
sealed class Purpose {
    data class Dictate(val mode: String, val app: String, val field: String) : Purpose()
    /** `current` is the mode being changed (saved or still a draft); null for a new one. */
    data class MakeMode(val current: Mode?) : Purpose()

    fun toJson(): JSONObject = when (this) {
        is Dictate -> JSONObject().put("kind", "dictate").put("mode", mode).put("app", app).put("field", field)
        is MakeMode -> JSONObject().put("kind", "make-mode").put("current", current?.toJson())
    }

    companion object {
        fun from(o: JSONObject): Purpose = when (o.optString("kind")) {
            "make-mode" -> MakeMode(o.optJSONObject("current")?.let { Mode.from(it) })
            else -> Dictate(o.optString("mode"), o.optString("app"), o.optString("field"))
        }
    }
}

sealed class State {
    object Idle : State()
    data class Recording(val purpose: Purpose) : State()
    data class Working(val label: String) : State()
    /** A dictation came back (and is typed in, or waiting to be). */
    data class Done(val result: Result, val warning: String?) : State()
    data class Failed(val message: String, val canRetry: Boolean) : State()
    /** A mode Claude drafted from what was said, waiting for Save. */
    data class Draft(val mode: Mode, val said: String) : State()
}

/** Text for the keyboard to type in; `replace` is text it typed just before, to swap out. */
data class Insert(val text: String, val replace: String?, val field: String)

/**
 * Scribe's one brain on the phone, shared by the keyboard and the "listening"
 * notification: it records, sends the recording off, and holds the result
 * until the keyboard types it in. A recording stays on the phone until it has
 * been written out, so nothing said is lost to a dropped connection.
 */
object Scribe {
    private const val TAG = "Scribe"
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>()

    private lateinit var app: Context
    lateinit var store: Store
        private set
    private lateinit var api: Api

    var state: State = State.Idle
        private set
    private var pending: Insert? = null

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var pausedAt = 0L
    private var pausedFor = 0L
    var paused = false
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        store = Store(app)
        api = Api(store)
        // A recording left from last time (the phone restarted, the app was closed) is offered again.
        if (store.pendingAudio?.let { File(it).exists() } == true) {
            state = State.Failed("A recording wasn't written out yet. Tap Retry to finish it.", true)
        }
    }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun set(s: State) {
        state = s
        main.post { listeners.toList().forEach { it() } }
        RecordService.update(app)
    }

    // ---- Recording ----

    fun start(purpose: Purpose): Boolean {
        if (state is State.Recording) return true
        val dir = File(app.filesDir, "recordings").apply { mkdirs() }
        val f = File(dir, "rec-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            // About 15 MB an hour, well under the speech service's 25 MB limit.
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            Log.e(TAG, "start", e)
            r.release()
            set(State.Failed("Couldn't start the microphone. Is another app using it?", false))
            return false
        }
        recorder = r
        file = f
        startedAt = SystemClock.elapsedRealtime()
        pausedFor = 0
        paused = false
        set(State.Recording(purpose))
        RecordService.start(app)
        return true
    }

    /** Switch the mode while still talking. */
    fun setMode(mode: String) {
        val s = state as? State.Recording ?: return
        val p = s.purpose as? Purpose.Dictate ?: return
        set(State.Recording(p.copy(mode = mode)))
    }

    fun elapsedMs(): Long {
        if (state !is State.Recording) return 0
        val now = if (paused) pausedAt else SystemClock.elapsedRealtime()
        return now - startedAt - pausedFor
    }

    /** 0..1, how loud it is right now. */
    fun level(): Float = try { ((recorder?.maxAmplitude ?: 0) / 12_000f).coerceIn(0f, 1f) } catch (e: Exception) { 0f }

    fun pause() {
        val r = recorder ?: return
        if (paused) {
            r.resume(); pausedFor += SystemClock.elapsedRealtime() - pausedAt; paused = false
        } else {
            r.pause(); pausedAt = SystemClock.elapsedRealtime(); paused = true
        }
        set(state)
    }

    /** Stop and send it off. */
    fun stop() {
        val s = state as? State.Recording ?: return
        val ms = elapsedMs()
        val f = file
        if (!finishRecorder() || f == null || !f.exists() || f.length() < 1000) {
            f?.delete()
            set(State.Failed("That was too short to hear anything. Try again.", false))
            return
        }
        store.pendingAudio = f.absolutePath
        store.pendingMs = ms
        app.getSharedPreferences("scribe", Context.MODE_PRIVATE).edit().putString("pendingPurpose", s.purpose.toJson().toString()).apply()
        process()
    }

    fun cancel() {
        finishRecorder()
        file?.delete()
        set(State.Idle)
    }

    private fun finishRecorder(): Boolean {
        val r = recorder ?: return false
        recorder = null
        if (paused) try { r.resume() } catch (e: Exception) { }
        return try { r.stop(); true } catch (e: Exception) { Log.e(TAG, "stop", e); false } finally { r.release() }
    }

    // ---- Sending it off ----

    /** Writes out the kept recording (again, after a failure). */
    fun process() {
        val path = store.pendingAudio ?: return set(State.Idle)
        val audio = File(path)
        if (!audio.exists()) { store.pendingAudio = null; return set(State.Idle) }
        val purpose = app.getSharedPreferences("scribe", Context.MODE_PRIVATE).getString("pendingPurpose", null)
            ?.let { try { Purpose.from(JSONObject(it)) } catch (e: Exception) { null } }
            ?: Purpose.Dictate(store.modes.first().id, "", "")
        val ms = store.pendingMs
        set(State.Working(if (purpose is Purpose.MakeMode) "Making the mode…" else "Writing it out…"))
        work.execute {
            try {
                when (purpose) {
                    is Purpose.Dictate -> {
                        val o = api.dictate(audio, purpose.mode, purpose.app, ms)
                        done()
                        val result = Result(o.optString("original"), o.optString("text"), o.optString("mode", purpose.mode), purpose.field)
                        if (result.text.isBlank()) {
                            set(State.Failed("Didn't catch any words. Try again a little closer to the phone.", false))
                        } else {
                            store.last = result
                            store.lastWaiting = true
                            pending = Insert(result.text, null, result.field)
                            set(State.Done(result, o.optString("warning").ifEmpty { null }))
                        }
                    }
                    is Purpose.MakeMode -> {
                        val o = api.makeMode(audio, purpose.current, ms)
                        done()
                        val m = o.getJSONObject("mode")
                        val id = purpose.current?.id ?: ""
                        set(State.Draft(Mode.from(m).copy(id = id), o.optString("said")))
                    }
                }
            } catch (e: ScribeError) {
                // A recording the server could never write out isn't worth keeping.
                val keep = e.status == 0 || e.status >= 500 || e.status == 429 || e.code == "not-connected"
                if (!keep) done()
                set(State.Failed(e.message ?: "Something went wrong.", keep))
            } catch (e: Exception) {
                Log.e(TAG, "process", e)
                set(State.Failed("Something went wrong. Your recording is kept; tap Retry.", true))
            }
        }
    }

    /** The recording has been written out: let it go. */
    private fun done() {
        store.pendingAudio?.let { File(it).delete() }
        store.pendingAudio = null
    }

    // ---- After it's back ----

    /** The keyboard takes what's waiting to be typed into `field` (null if nothing is). */
    fun takeInsert(field: String): Insert? {
        val i = pending ?: return null
        if (i.field != field) return null
        pending = null
        typeFailed = false
        store.lastWaiting = false
        return i
    }

    /**
     * True when the last try to type in didn't show up in the text box (some
     * web editors drop it). The text waits again, with Try again and Copy,
     * instead of being retried by itself over and over.
     */
    var typeFailed = false
        private set

    fun putBack(i: Insert) {
        pending = i
        typeFailed = true
        store.lastWaiting = true
        main.post { listeners.toList().forEach { it() } }
    }

    /** What's waiting for a different text box than this one. */
    fun waitingElsewhere(field: String): Insert? = pending?.takeIf { it.field != field }

    /** Type the waiting text here instead of where it was said. */
    fun moveHere(field: String) { pending = pending?.copy(field = field) }

    /** Swap the typed-in text for the same words in another mode, or as said. */
    fun redo(mode: String, app: String) {
        val last = store.last ?: return
        if (mode == "original") {
            pending = Insert(last.original, last.text, last.field)
            val r = last.copy(text = last.original, mode = "original")
            store.last = r
            set(State.Done(r, null))
            return
        }
        set(State.Working("Rewriting…"))
        work.execute {
            try {
                val o = api.redo(last.original, mode, app)
                val r = last.copy(text = o.optString("text"), mode = o.optString("mode", mode))
                pending = Insert(r.text, last.text, last.field)
                store.last = r
                set(State.Done(r, null))
            } catch (e: ScribeError) {
                set(State.Failed(e.message ?: "Couldn't rewrite it.", false))
            }
        }
    }

    /** Take back what was typed in. */
    fun undo() {
        val last = store.last ?: return
        pending = Insert("", last.text, last.field)
        set(State.Idle)
    }

    fun dismiss() { if (state !is State.Recording && state !is State.Working) set(State.Idle) }

    // ---- Modes ----

    fun refreshModes() {
        if (!store.connected) return
        work.execute {
            try { store.modes = api.modes(); set(state) } catch (e: Exception) { Log.w(TAG, "modes", e) }
        }
    }

    /** Open a saved mode to look at, change by voice, or remove. */
    fun openMode(mode: Mode) { if (state !is State.Recording && state !is State.Working) set(State.Draft(mode, "")) }

    fun saveDraft() {
        val d = state as? State.Draft ?: return
        set(State.Working("Saving…"))
        work.execute {
            try {
                val saved = api.saveMode(d.mode)
                store.modes = api.modes()
                set(State.Idle)
                Log.i(TAG, "saved ${saved.id}")
            } catch (e: ScribeError) {
                set(State.Failed(e.message ?: "Couldn't save the mode.", false))
            }
        }
    }

    fun deleteMode(id: String) {
        set(State.Working("Removing…"))
        work.execute {
            try {
                api.deleteMode(id)
                store.modes = api.modes()
                set(State.Idle)
            } catch (e: ScribeError) {
                set(State.Failed(e.message ?: "Couldn't remove the mode.", false))
            }
        }
    }

    /** Opens Scribe's own screen (to connect, or allow the microphone). */
    fun openApp(context: Context) {
        context.startActivity(Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
