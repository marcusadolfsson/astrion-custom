package com.custom.astrion.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.custom.astrion.config.VoiceConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

/** What the on-screen indicator shows. */
sealed class VoiceState {
    data object Idle : VoiceState()
    data object Listening : VoiceState()
    data object Thinking : VoiceState()
    data class Done(val route: String?, val transcript: String?, val response: String?) : VoiceState()
    data class Error(val message: String) : VoiceState()
}

/**
 * The VOICE key: press, talk, and it ends itself on silence.
 *
 * The remote deliberately makes NO routing decision. It streams the microphone
 * to the endpoint named by `voice.path` in the layout and lets the server decide
 * what to do with it. In this setup that is a custom component which forwards to
 * Siri on an Apple TV or to HA Assist depending on what is on screen — but any
 * endpoint accepting a chunked PCM16 body works, so this is not tied to that.
 *
 * The upload is chunked and the request body IS the live microphone: audio
 * leaves the remote while the user is still speaking rather than after they
 * finish. On the Siri route that matters twice over, because the server holds
 * the Apple TV's SIRI button down for exactly as long as this request body
 * stays open.
 *
 * Press-to-start, silence-to-stop. The HA100's VOICE key emits an instant
 * press+release rather than a hold, so hold-to-talk isn't available on this
 * hardware and the end of an utterance is inferred (see MicCapture). A second
 * press while listening cancels.
 */
class VoiceSession(
    private val baseUrl: String,
    private val token: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    // No timeouts on write/read: the call lasts as long as the person talks.
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var capture: MicCapture? = null
    private var job: Job? = null
    private var startedAt = 0L

    /**
     * VOICE key handler: start listening, or cancel if already listening.
     *
     * `cfg` is passed per press rather than held, so a layout re-sync takes
     * effect on the next press with no re-wiring.
     */
    fun toggle(cfg: VoiceConfig?) {
        if (_state.value is VoiceState.Listening) {
            // Holding the key down must not cancel what the hold just started.
            // The dispatcher already drops auto-repeats, but some firmware
            // reports a held key as discrete press/release pairs rather than an
            // incrementing repeatCount, which would otherwise flip listening on
            // and off several times a second. Treat anything this quick as part
            // of the same press.
            if (System.currentTimeMillis() - startedAt < CANCEL_GRACE_MS) {
                Log.d(TAG, "ignoring repeat within the hold grace period")
                return
            }
            Log.i(TAG, "cancelled by second press")
            capture?.stop()
            return
        }
        start(cfg ?: VoiceConfig())
    }

    private fun start(cfg: VoiceConfig) {
        if (baseUrl.isBlank() || token.isBlank()) {
            _state.value = VoiceState.Error("No Home Assistant connection")
            autoDismiss()
            return
        }
        job?.cancel()
        val mic = MicCapture(
            maxMs = cfg.maxMs,
            endOnSilence = true,
            endSilenceMs = cfg.silenceMs,
            noSpeechMs = cfg.noSpeechMs,
        )
        capture = mic
        startedAt = System.currentTimeMillis()
        _state.value = VoiceState.Listening

        job = scope.launch {
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()

                // Unknown length -> OkHttp uses chunked encoding, which is what
                // lets the body stay open for the length of the utterance.
                override fun contentLength() = -1L

                override fun writeTo(sink: BufferedSink) {
                    val (total, peak) = mic.captureInto { buf, n ->
                        sink.write(buf, 0, n)
                        sink.flush()   // push each ~100 ms chunk out immediately
                    }
                    Log.i(TAG, "streamed $total bytes (peak rms=$peak)")
                    // Capture has stopped; the indicator can move on while the
                    // server is still processing.
                    _state.value = VoiceState.Thinking
                }
            }

            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}${cfg.path}")
                .addHeader("Authorization", "Bearer $token")
                .post(body)
                .build()

            try {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "HTTP ${resp.code}: $text")
                        _state.value = VoiceState.Error("HA returned ${resp.code}")
                    } else {
                        val o = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
                        fun str(k: String) = o?.get(k)?.jsonPrimitive?.contentOrNull()
                        _state.value = VoiceState.Done(
                            route = str("route"),
                            transcript = str("transcript"),
                            response = str("response"),
                        )
                        Log.i(TAG, "route=${str("route")} transcript=${str("transcript")}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "voice request failed: ${e.message}")
                _state.value = VoiceState.Error(e.message ?: "Request failed")
            }
            autoDismiss()
        }
    }

    fun dismiss() {
        _state.value = VoiceState.Idle
    }

    private fun autoDismiss() {
        scope.launch {
            delay(DISMISS_MS)
            if (_state.value !is VoiceState.Listening) _state.value = VoiceState.Idle
        }
    }

    companion object {
        private const val TAG = "AstrionVoice"
        private const val DISMISS_MS = 4_000L

        /** A second "press" sooner than this is the same hold, not a cancel. */
        private const val CANCEL_GRACE_MS = 1_200L
    }
}

/** kotlinx-serialization returns "null" as a literal for JSON null. */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    if (isString || content != "null") content.takeIf { it != "null" } else null
