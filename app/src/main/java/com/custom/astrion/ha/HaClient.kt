package com.custom.astrion.ha

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal, dependency-light Home Assistant WebSocket client.
 *
 * This speaks the *standard* HA websocket API — exactly the same handshake and
 * commands the stock HaRemote app uses (confirmed by decompiling it):
 *
 *   1. Server sends   { type: "auth_required" }
 *   2. We send        { type: "auth", access_token: "<long-lived token>" }
 *   3. Server sends   { type: "auth_ok" }  (or "auth_invalid")
 *   4. We call        get_states  to seed the entity cache
 *   5. We             subscribe_events (state_changed) for live updates
 *   6. Heartbeat via  { type: "ping" } / { type: "pong" }
 *
 * Because it's the stock protocol, this app needs nothing from Sanytron's
 * cloud or their custom integration to function — only a reachable HA instance
 * and a long-lived access token.
 *
 * Deliberately small: one socket, a StateFlow of the entity map, a StateFlow of
 * connection status, and callService(). No Sanytron `astrion/...` events are used
 * here (those are only needed if you later want the local IR-blaster path).
 */
class HaClient(
    private val baseUrl: String,   // e.g. "http://10.0.1.10:8123" or "https://ha.example.com"
    private val token: String,     // long-lived access token
) {
    companion object {
        private const val TAG = "HaClient"
        private const val PING_INTERVAL_MS = 30_000L
        private const val PUBLISH_INTERVAL_MS = 120L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicInteger(1)

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Separate, sanely-timed client for one-shot HTTP GETs (album art).
    private val imageHttp = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    /** Outstanding request/response commands (e.g. browse_media), keyed by id. */
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    private val _connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    // Entities the current layout references. When set, we use HA's
    // subscribe_entities (server-side filtered, compressed deltas) instead of
    // pulling and tracking the whole instance. Empty = legacy whole-instance mode.
    @Volatile private var subscribedEntities: Set<String> = emptySet()
    @Volatile private var subId: Int? = null

    private val _entities = MutableStateFlow<EntityMap>(emptyMap())
    /** Live map of every entity's current state. Cards observe this. */
    val entities: StateFlow<EntityMap> = _entities.asStateFlow()

    // Working store updated on every event; published to _entities at most once
    // per PUBLISH_INTERVAL_MS so a chatty sensor (e.g. mmWave radar at several
    // Hz) can't force the whole UI to repaint faster than the SoC can handle.
    private val entityStore = ConcurrentHashMap<String, EntityState>()
    @Volatile private var entitiesDirty = false
    @Volatile private var publisherStarted = false

    // ---- public API ---------------------------------------------------------

    fun connect() {
        if (!baseUrl.startsWith("http")) {
            Log.w(TAG, "connect() skipped: no HA URL configured")
            _connection.value = ConnectionState.DISCONNECTED
            return
        }
        _connection.value = ConnectionState.CONNECTING
        val wsUrl = toWebSocketUrl(baseUrl)
        Log.i(TAG, "Connecting to $wsUrl")
        val req = Request.Builder().url(wsUrl).build()
        socket = http.newWebSocket(req, listener)
    }

    /**
     * Limit the live subscription to these entities (from the loaded layout).
     * Safe to call any time; re-subscribes in place if already connected.
     */
    fun setSubscribedEntities(ids: Collection<String>) {
        val next = ids.toSet()
        if (next == subscribedEntities) return
        subscribedEntities = next
        if (_connection.value == ConnectionState.CONNECTED) {
            subId?.let { old ->
                send(buildJsonObject {
                    put("id", idCounter.getAndIncrement())
                    put("type", "unsubscribe_events")
                    put("subscription", old)
                })
            }
            entityStore.keys.retainAll(next)
            subscribe()
        }
    }

    fun disconnect() {
        socket?.close(1000, "client closing")
        socket = null
        _connection.value = ConnectionState.DISCONNECTED
    }

    /** Fire a HA service call, e.g. light.toggle on light.kitchen. */
    fun callService(call: ServiceCall) {
        val target = buildJsonObject {
            call.entityId?.let { put("entity_id", it) }
        }
        val msg = buildJsonObject {
            put("id", idCounter.getAndIncrement())
            put("type", "call_service")
            put("domain", call.domain)
            put("service", call.service)
            if (call.data.isNotEmpty()) {
                put("service_data", JsonObject(call.data))
            }
            put("target", target)
        }
        send(msg)
    }

    /** Convenience helper mirroring the common toggle pattern. */
    fun toggle(entityId: String) {
        val domain = entityId.substringBefore('.')
        callService(ServiceCall(domain = domain, service = "toggle", entityId = entityId))
    }

    /**
     * Fetch an image (e.g. a media_player `entity_picture`) as an ImageBitmap.
     * `path` may be absolute or an HA-relative path like /api/media_player_proxy/…;
     * the bearer token is attached so proxied/authenticated art loads too.
     */
    /**
     * Fetch a text resource over HTTP (e.g. the dashboard JSON served at
     * /local/astrion/dashboard.json). Async, fire-and-forget; `onResult` runs on
     * an OkHttp background thread with the body, or null on any failure.
     */
    fun fetchText(path: String, onResult: (String?) -> Unit) {
        // A blank baseUrl (no credentials configured yet) would make OkHttp throw
        // on a scheme-less URL — fail soft instead.
        if (!path.startsWith("http") && !baseUrl.startsWith("http")) {
            onResult(null); return
        }
        val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.w(TAG, "fetchText failed for $path: ${e.message}")
                onResult(null)
            }

            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.use { onResult(if (it.isSuccessful) it.body?.string() else null) }
            }
        })
    }

    suspend fun fetchBitmap(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
            imageHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchBitmap failed for $path", e)
            null
        }
    }

    /**
     * Browse a media_player's library via the standard `media_player/browse_media`
     * command. Returns the `result` object (title + children), or null on timeout.
     * Pass a null contentId/type to browse the root.
     */
    suspend fun browseMedia(
        entityId: String,
        contentId: String? = null,
        contentType: String? = null,
    ): JsonObject? {
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val msg = buildJsonObject {
            put("id", id)
            put("type", "media_player/browse_media")
            put("entity_id", entityId)
            contentId?.let { put("media_content_id", it) }
            contentType?.let { put("media_content_type", it) }
        }
        send(msg)
        val reply = withTimeoutOrNull(8_000) { deferred.await() }
        pending.remove(id)
        return reply?.get("result") as? JsonObject
    }

    /**
     * Fetch a weather forecast via `weather.get_forecasts` (modern HA no longer
     * exposes `forecast` as an attribute). Returns the forecast array
     * (each item: datetime, condition, temperature, templow), or null.
     */
    suspend fun getForecast(entityId: String, forecastType: String = "daily"): JsonArray? {
        val id = idCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val msg = buildJsonObject {
            put("id", id)
            put("type", "call_service")
            put("domain", "weather")
            put("service", "get_forecasts")
            put("service_data", buildJsonObject { put("type", forecastType) })
            put("target", buildJsonObject { put("entity_id", entityId) })
            put("return_response", true)
        }
        send(msg)
        val reply = withTimeoutOrNull(8_000) { deferred.await() }
        pending.remove(id)
        val response = reply?.get("result")?.jsonObject?.get("response")?.jsonObject ?: return null
        return response[entityId]?.jsonObject?.get("forecast") as? JsonArray
    }

    /** Play a specific media item on a player. */
    fun playMedia(entityId: String, contentId: String, contentType: String) {
        callService(
            ServiceCall.of(
                "media_player", "play_media", entityId,
                "media_content_id" to contentId,
                "media_content_type" to contentType,
            )
        )
    }

    // ---- internals ----------------------------------------------------------

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Socket open, waiting for auth_required")
            _connection.value = ConnectionState.AUTHENTICATING
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val obj = json.parseToJsonElement(text).jsonObject
                when (obj["type"]?.jsonPrimitive?.content) {
                    "auth_required" -> sendAuth()
                    "auth_ok" -> onAuthOk()
                    "auth_invalid" -> _connection.value = ConnectionState.AUTH_FAILED
                    "result" -> {
                        // Route replies to an awaiting command if one matches this
                        // id; otherwise treat it as the get_states seed.
                        val id = obj["id"]?.jsonPrimitive?.intOrNull
                        val waiter = id?.let { pending.remove(it) }
                        if (waiter != null) waiter.complete(obj) else onResult(obj)
                    }
                    "event" -> onEvent(obj)
                    "pong" -> { /* heartbeat ok */ }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onMessage parse error", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Socket failure", t)
            _connection.value = ConnectionState.ERROR
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "Socket closed $code $reason")
            if (_connection.value != ConnectionState.DISCONNECTED) scheduleReconnect()
        }
    }

    private fun sendAuth() {
        val msg = buildJsonObject {
            put("type", "auth")
            put("access_token", token)
        }
        // NOTE: auth message must NOT include an id (HA rejects it otherwise).
        socket?.send(msg.toString())
    }

    private fun onAuthOk() {
        Log.i(TAG, "Authenticated")
        _connection.value = ConnectionState.CONNECTED
        startPublisher()
        subscribe()
        startHeartbeat()
    }

    /** Coalesce entity updates: publish the store to the StateFlow at a bounded rate. */
    private fun startPublisher() {
        if (publisherStarted) return
        publisherStarted = true
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(PUBLISH_INTERVAL_MS)
                if (entitiesDirty) {
                    entitiesDirty = false
                    _entities.value = HashMap(entityStore)
                }
            }
        }
    }

    /**
     * Preferred path: subscribe_entities with an entity_ids filter — HA sends one
     * compact "add" payload for just those entities, then deltas. Falls back to
     * the whole-instance get_states + subscribe_events only if the layout hasn't
     * been parsed yet (so the app still works before the first config load).
     */
    private fun subscribe() {
        val ids = subscribedEntities
        if (ids.isEmpty()) {
            requestStates()
            subscribeStateChanges()
            return
        }
        val id = idCounter.getAndIncrement()
        subId = id
        send(buildJsonObject {
            put("id", id)
            put("type", "subscribe_entities")
            put("entity_ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
        })
        Log.i(TAG, "subscribed to ${ids.size} entities (filtered)")
    }

    private fun requestStates() {
        val msg = buildJsonObject {
            put("id", idCounter.getAndIncrement())
            put("type", "get_states")
        }
        send(msg)
    }

    private fun subscribeStateChanges() {
        val msg = buildJsonObject {
            put("id", idCounter.getAndIncrement())
            put("type", "subscribe_events")
            put("event_type", "state_changed")
        }
        send(msg)
    }

    private fun startHeartbeat() {
        scope.launch {
            while (_connection.value == ConnectionState.CONNECTED) {
                kotlinx.coroutines.delay(PING_INTERVAL_MS)
                val ping = buildJsonObject {
                    put("id", idCounter.getAndIncrement())
                    put("type", "ping")
                }
                send(ping)
            }
        }
    }

    /** Result of get_states arrives as an array in the `result` field. */
    private fun onResult(obj: JsonObject) {
        val result = obj["result"] as? JsonArray ?: return
        for (el in result) {
            val e = el.jsonObject
            val entityId = e["entity_id"]?.jsonPrimitive?.content ?: continue
            entityStore[entityId] = EntityState(
                entityId = entityId,
                state = e["state"]?.jsonPrimitive?.content ?: "unknown",
                attributes = e["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
                lastChanged = e["last_changed"]?.jsonPrimitive?.content,
                lastUpdated = e["last_updated"]?.jsonPrimitive?.content,
            )
        }
        // Seed is important — publish immediately so the first frame has data.
        _entities.value = HashMap(entityStore)
    }

    /**
     * Handles BOTH subscription formats:
     *  - subscribe_entities (compressed): event = { a: {...}, c: {...}, r: [...] }
     *    where a=added (the initial seed + new entities), c=changed, r=removed.
     *    Per entity: s=state, a=attributes, lc=last_changed, lu=last_updated; a
     *    change is { "+": {partial}, "-": {a: [attrs to drop]} } and MERGES onto
     *    what we already hold — HA only sends the delta.
     *  - subscribe_events state_changed (legacy whole-instance): data.new_state.
     */
    private fun onEvent(obj: JsonObject) {
        val event = obj["event"]?.jsonObject ?: return

        // ---- compressed (filtered) format ----
        val added = event["a"] as? JsonObject
        val changed = event["c"] as? JsonObject
        val removed = event["r"] as? JsonArray
        if (added != null || changed != null || removed != null) {
            added?.forEach { (entityId, el) ->
                val o = el as? JsonObject ?: return@forEach
                entityStore[entityId] = EntityState(
                    entityId = entityId,
                    state = o["s"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                    attributes = o["a"] as? JsonObject ?: JsonObject(emptyMap()),
                    lastChanged = o["lc"]?.jsonPrimitive?.contentOrNull,
                    lastUpdated = o["lu"]?.jsonPrimitive?.contentOrNull,
                )
            }
            changed?.forEach { (entityId, el) ->
                val o = el as? JsonObject ?: return@forEach
                val prev = entityStore[entityId]
                val plus = o["+"] as? JsonObject
                val minus = o["-"] as? JsonObject
                // Merge attributes: start from what we have, apply +, drop -.
                val attrs = LinkedHashMap<String, JsonElement>(prev?.attributes ?: emptyMap())
                (plus?.get("a") as? JsonObject)?.forEach { (k, v) -> attrs[k] = v }
                (minus?.get("a") as? JsonArray)?.forEach { rem ->
                    (rem as? JsonPrimitive)?.contentOrNull?.let { attrs.remove(it) }
                }
                entityStore[entityId] = EntityState(
                    entityId = entityId,
                    state = plus?.get("s")?.jsonPrimitive?.contentOrNull ?: prev?.state ?: "unknown",
                    attributes = JsonObject(attrs),
                    lastChanged = plus?.get("lc")?.jsonPrimitive?.contentOrNull ?: prev?.lastChanged,
                    lastUpdated = plus?.get("lu")?.jsonPrimitive?.contentOrNull ?: prev?.lastUpdated,
                )
            }
            removed?.forEach { el ->
                (el as? JsonPrimitive)?.contentOrNull?.let { entityStore.remove(it) }
            }
            // The first "a" batch IS the seed, so publish it straight away.
            if (added != null && _entities.value.isEmpty()) {
                _entities.value = HashMap(entityStore)
            } else {
                entitiesDirty = true
            }
            return
        }

        // ---- legacy state_changed ----
        val data = event["data"]?.jsonObject ?: return
        val newState = data["new_state"] as? JsonObject ?: return
        val entityId = newState["entity_id"]?.jsonPrimitive?.content ?: return
        entityStore[entityId] = EntityState(
            entityId = entityId,
            state = newState["state"]?.jsonPrimitive?.content ?: "unknown",
            attributes = newState["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
            lastChanged = newState["last_changed"]?.jsonPrimitive?.content,
            lastUpdated = newState["last_updated"]?.jsonPrimitive?.content,
        )
        entitiesDirty = true // published by the coalescing publisher loop
    }

    private fun send(msg: JsonObject) {
        socket?.send(msg.toString())
    }

    private fun scheduleReconnect() {
        scope.launch {
            kotlinx.coroutines.delay(3_000)
            if (_connection.value == ConnectionState.ERROR) connect()
        }
    }

    private fun toWebSocketUrl(base: String): String {
        val trimmed = base.trimEnd('/')
        val ws = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> "ws://$trimmed"
        }
        return "$ws/api/websocket"
    }
}
