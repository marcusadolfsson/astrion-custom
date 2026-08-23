package com.custom.astrion.config

import android.content.Context
import android.os.Environment
import android.util.Log
import com.custom.astrion.cards.CardConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Loads the whole app layout (swipeable pages + hardware-button bindings) from
 * a JSON file on shared storage, falling back to the compiled-in
 * DashboardConfig.default when the file is missing or malformed — the app never
 * crashes over a bad config, it just shows a notice banner.
 *
 * Path: /sdcard/astrion/dashboard.json. Shared storage keeps the file editable
 * over `adb push` or any file manager; MainActivity requests the storage
 * permission at runtime (Android 8.1 on the HA100).
 *
 * JSON shape:
 * {
 *   "startPage": 1,
 *   "pages": [
 *     { "name": "Lights", "cards": [ { "type": "...", "options": { ... } } ] },
 *     { "name": "Main",   "cards": [ ... ] },
 *     { "name": "TV",     "cards": [ ... ] }
 *   ],
 *   "hotkeys": [
 *     { "key": "UP", "service": "remote.send_command",
 *       "entityId": "remote.the_club_tvv", "data": { "command": "DPAD_UP" } },
 *     { "key": "LIGHT", "page": "Lights" }
 *   ]
 * }
 *
 * A bare top-level array is also accepted for convenience — it becomes a single
 * page named "Main" with no hotkeys.
 */
object DashboardLoader {
    private const val TAG = "DashboardLoader"

    /**
     * HA-served location of the master layout. The layout is authored as YAML
     * (astrion/dashboard.yaml — comments, HA-native, !include-able) and the
     * custom_components/astrion_dashboard component serves it as JSON here, so
     * the app needs no YAML parser and there is no generated file to fall out of
     * sync. Unlike the old /local/astrion/dashboard.json this endpoint requires
     * auth — fetchText already sends the bearer token.
     */
    const val REMOTE_PATH = "/api/astrion_dashboard"

    /**
     * App-private cache dir, set once at startup. Null until [init] runs.
     *
     * The cache used to live at /sdcard/astrion/dashboard.json so it could be
     * edited with `adb push`. That is fine on the HA100s (Android 8.1) and
     * IMPOSSIBLE from Android 11 on: scoped storage makes an app's own writes to
     * a non-media path in shared storage fail, so the cache silently stopped
     * working on the Pixel Tablet. App-private storage works on every version.
     */
    private var privateDir: File? = null

    /** Pre-scoped-storage location; still read once, to seed [privateDir]. */
    private val legacyFile: File
        get() = File(Environment.getExternalStorageDirectory(), "astrion/dashboard.json")

    /**
     * Point the cache at app-private storage. Call before the first [load].
     * Carries an existing shared-storage layout across on first run so a device
     * upgrading from the old path keeps its offline cache.
     */
    fun init(context: Context) {
        privateDir = context.filesDir
        val priv = File(context.filesDir, "dashboard.json")
        if (!priv.exists()) {
            runCatching { legacyFile.readText() }.getOrNull()?.let { seed ->
                runCatching { priv.writeText(seed) }
                    .onSuccess { Log.i(TAG, "seeded layout cache from ${legacyFile.path}") }
            }
        }
    }

    /** Local cache, written by a successful sync; used offline / before first sync. */
    val configFile: File
        get() = privateDir?.let { File(it, "dashboard.json") } ?: legacyFile

    /**
     * Validate freshly-fetched remote JSON and, if valid AND different from the
     * current cache, write it to the cache and return the parsed config. Returns
     * null when the text is unchanged (no reload needed) or invalid (cache kept).
     */
    fun loadFromText(text: String): Result? {
        if (runCatching { configFile.readText() }.getOrNull() == text) return null
        val cfg = try {
            parse(text) // throws on malformed JSON
        } catch (e: Exception) {
            Log.w(TAG, "remote dashboard.json invalid, keeping cache: ${e.message}")
            return null
        }
        // Caching is best-effort and MUST NOT gate the config. This used to sit
        // inside the same try as parse(), so an unwritable cache was reported as
        // "invalid" and threw away a layout that had just been fetched and
        // parsed successfully -- the Pixel Tablet showed built-in demo defaults
        // while talking to HA perfectly well.
        runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(text)
        }.onFailure { Log.w(TAG, "couldn't cache layout at ${configFile.path}: ${it.message}") }
        return Result(cfg, null)
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    data class Result(val config: AppConfig, val notice: String?)

    fun load(): Result {
        val file = configFile
        if (!file.exists()) {
            return if (writeDefaults()) {
                Result(DashboardConfig.default, "Wrote defaults to ${file.path} — edit it, then reopen the app")
            } else {
                Result(DashboardConfig.default, "Can't access ${file.path} (storage permission?) — using built-in defaults")
            }
        }
        return try {
            Result(parse(file.readText()), null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ${file.path}", e)
            Result(DashboardConfig.default, "dashboard.json invalid (${e.message?.take(80)}) — using built-in defaults")
        }
    }

    // ---- parse --------------------------------------------------------------

    private fun parse(text: String): AppConfig {
        return when (val root = json.parseToJsonElement(text)) {
            is JsonArray -> AppConfig(
                pages = listOf(PageConfig("Main", root.map { parseCard(it as JsonObject) })),
            )
            is JsonObject -> {
                val pagesArr = root["pages"] as? JsonArray ?: error("missing \"pages\" array")
                val pages = pagesArr.map { p ->
                    val obj = p as? JsonObject ?: error("each page must be an object")
                    val name = (obj["name"] as? JsonPrimitive)?.content ?: "Page"
                    val cards = (obj["cards"] as? JsonArray)?.map { parseCard(it as JsonObject) } ?: emptyList()
                    PageConfig(
                        name = name,
                        cards = cards,
                        // parseHotkey is reused verbatim, so a page-scoped binding
                        // supports `quiet` for free.
                        hotkeys = (obj["hotkeys"] as? JsonArray)
                            ?.map { parseHotkey(it as JsonObject) } ?: emptyList(),
                        longHotkeys = (obj["longHotkeys"] as? JsonArray)
                            ?.map { parseHotkey(it as JsonObject) } ?: emptyList(),
                        voice = (obj["voice"] as? JsonObject)?.let { v ->
                            PageVoiceConfig(path = (v["path"] as? JsonPrimitive)?.content)
                        },
                        // Same parser as the global overlay below, so a page's
                        // block takes exactly the same keys.
                        overlay = parseOverlay(obj["overlay"] as? JsonObject),
                    )
                }
                if (pages.isEmpty()) error("\"pages\" is empty")
                val start = (root["startPage"] as? JsonPrimitive)?.intOrNull ?: 0
                val hotkeys = (root["hotkeys"] as? JsonArray)?.map { parseHotkey(it as JsonObject) } ?: emptyList()
                val longHotkeys = (root["longHotkeys"] as? JsonArray)?.map { parseHotkey(it as JsonObject) } ?: emptyList()
                val overlay = parseOverlay(root["overlay"] as? JsonObject)
                val gestures = (root["gestures"] as? JsonObject)?.let { g ->
                    GesturesConfig(
                        swipeDown = (g["swipe_down"] as? JsonObject)?.let { s ->
                            GesturePanel(
                                title = (s["title"] as? JsonPrimitive)?.content ?: "Device",
                                items = (s["items"] as? JsonArray).orEmpty().mapNotNull { i ->
                                    val o = i as? JsonObject ?: return@mapNotNull null
                                    val act = (o["action"] as? JsonPrimitive)?.content
                                        ?: return@mapNotNull null
                                    GestureAction(
                                        name = (o["name"] as? JsonPrimitive)?.content ?: act,
                                        action = act,
                                        packageName = (o["package"] as? JsonPrimitive)?.content,
                                    )
                                },
                            )
                        },
                    )
                }
                val voice = (root["voice"] as? JsonObject)?.let { v ->
                    VoiceConfig(
                        path = (v["path"] as? JsonPrimitive)?.content ?: VoiceConfig().path,
                        maxMs = (v["max_ms"] as? JsonPrimitive)?.intOrNull ?: VoiceConfig().maxMs,
                        silenceMs = (v["silence_ms"] as? JsonPrimitive)?.intOrNull ?: VoiceConfig().silenceMs,
                        noSpeechMs = (v["no_speech_ms"] as? JsonPrimitive)?.intOrNull ?: VoiceConfig().noSpeechMs,
                        suggestions = (v["suggestions"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                            ?: VoiceConfig().suggestions,
                        suggestTitle = (v["suggest_title"] as? JsonPrimitive)?.content
                            ?: VoiceConfig().suggestTitle,
                        suggestEntity = (v["suggest_entity"] as? JsonPrimitive)?.content,
                        suggestState = (v["suggest_state"] as? JsonPrimitive)?.content,
                    )
                }
                val status = (root["status"] as? JsonObject)?.let { s ->
                    StatusConfig(
                        title = (s["title"] as? JsonPrimitive)?.content ?: "Status",
                        cards = (s["cards"] as? JsonArray)?.map { parseCard(it as JsonObject) }
                            ?: emptyList(),
                    )
                }
                val pageEntity = (root["page_entity"] as? JsonPrimitive)?.content
                AppConfig(
                    pages, start.coerceIn(0, pages.size - 1), hotkeys, longHotkeys,
                    overlay, voice, gestures, status, pageEntity,
                    parseUi(root["ui"] as? JsonObject),
                    parseMotionWake(root["motion_wake"] as? JsonObject),
                    parseDockDisplay(root["dock_display"] as? JsonObject),
                    parseScreensaver(root["screensaver"] as? JsonObject),
                )
            }
            else -> error("top level must be an object or array")
        }
    }

    private fun parseCard(obj: JsonObject): CardConfig {
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("card missing \"type\" string")
        val options = (obj["options"] as? JsonObject)
            ?.entries?.associate { (k, v) -> k to JsonPlain.toPlain(v) }
            ?: emptyMap()
        return CardConfig(type, options)
    }

    /**
     * Volume/mute OSD config. Shared by the global `overlay:` and a page's own,
     * so the two accept identical keys — a page block is a whole-value override,
     * not a merge (see [PageConfig.overlay]).
     */
    private fun parseOverlay(o: JsonObject?): OverlayConfig? = o?.let {
        OverlayConfig(
            volumeEntity = (it["volume_entity"] as? JsonPrimitive)?.content,
            muteEntity = (it["mute_entity"] as? JsonPrimitive)?.content,
            // Carried as a CardConfig purely so ConditionalCard.matches can
            // evaluate it unchanged; the type string is unused.
            condition = (it["condition"] as? JsonObject)?.let { c ->
                CardConfig("condition", c.entries.associate { (k, v) -> k to JsonPlain.toPlain(v) })
            },
        )
    }

    /**
     * Motion-wake tuning, with optional per-remote blocks under `devices:` keyed
     * by the ConnectionConfig `device` slug. A device block is parsed with the
     * same reader, so it takes the same keys; nested `devices` inside one is
     * ignored by [MotionWakeConfig.forDevice].
     */
    private fun parseUi(o: JsonObject?): UiConfig {
        val d = UiConfig()
        if (o == null) return d
        return UiConfig(
            orientation = (o["orientation"] as? JsonPrimitive)?.content ?: d.orientation,
            columns = (o["columns"] as? JsonPrimitive)?.content?.toIntOrNull() ?: d.columns,
            deviceInternals = (o["device_internals"] as? JsonPrimitive)?.content
                ?.toBooleanStrictOrNull() ?: d.deviceInternals,
            // Clamped: a stray 0 or a negative would divide the layout out of
            // existence, and a config typo should not need adb to undo.
            scale = ((o["scale"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: d.scale)
                .coerceIn(0.5f, 2f),
            padding = ((o["padding"] as? JsonPrimitive)?.content?.toIntOrNull() ?: d.padding)
                .coerceIn(0, 64),
            kiosk = (o["kiosk"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: d.kiosk,
            kioskPinEntity = (o["kiosk_pin_entity"] as? JsonPrimitive)?.content ?: d.kioskPinEntity,
            kioskExitMinutes = ((o["kiosk_exit_minutes"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: d.kioskExitMinutes).coerceIn(1, 120),
            mediaArtMaxHeight = ((o["media_art_max_height"] as? JsonPrimitive)?.content?.toIntOrNull()
                ?: d.mediaArtMaxHeight).coerceIn(0, 600),
            kioskHomePackage = (o["kiosk_home_package"] as? JsonPrimitive)?.content
                ?: d.kioskHomePackage,
            inputBridge = (o["input_bridge"] as? JsonPrimitive)?.content
                ?.toBooleanStrictOrNull() ?: d.inputBridge,
        )
    }

    private fun parseMotionWake(o: JsonObject?): MotionWakeConfig {
        val d = MotionWakeConfig()
        if (o == null) return d
        fun one(j: JsonObject, base: MotionWakeConfig) = MotionWakeConfig(
            enabled = (j["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: base.enabled,
            tiltDegrees = (j["tilt_degrees"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: base.tiltDegrees,
            jerkRatio = (j["jerk_ratio"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: base.jerkRatio,
            hits = (j["hits"] as? JsonPrimitive)?.content?.toIntOrNull() ?: base.hits,
            settleSeconds = (j["settle_seconds"] as? JsonPrimitive)?.content?.toIntOrNull() ?: base.settleSeconds,
            cooldownSeconds = (j["cooldown_seconds"] as? JsonPrimitive)?.content?.toIntOrNull() ?: base.cooldownSeconds,
            windowMs = (j["window_ms"] as? JsonPrimitive)?.content?.toLongOrNull() ?: base.windowMs,
            ignoreWhileCharging = (j["ignore_while_charging"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                ?: base.ignoreWhileCharging,
        )
        val global = one(o, d)
        val devices = (o["devices"] as? JsonObject)?.entries?.associate { (k, v) ->
            // Device blocks inherit the GLOBAL values for anything they omit, so
            // a block that only raises `threshold` keeps the shared settle window.
            k to ((v as? JsonObject)?.let { one(it, global) } ?: global)
        } ?: emptyMap()
        return global.copy(devices = devices)
    }

    /**
     * Dock display behaviour, same per-remote `devices:` shape as motion wake --
     * a remote on a nightstand and one on a side table want different night-time
     * brightness, and only one of them is in a bedroom.
     */
    private fun parseDockDisplay(o: JsonObject?): DockDisplayConfig {
        val d = DockDisplayConfig()
        if (o == null) return d
        fun one(j: JsonObject, base: DockDisplayConfig) = DockDisplayConfig(
            enabled = (j["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: base.enabled,
            dimLevel = (j["dim_level"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: base.dimLevel,
            brightLevel = (j["bright_level"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: base.brightLevel,
            brightSeconds = (j["bright_seconds"] as? JsonPrimitive)?.content?.toIntOrNull() ?: base.brightSeconds,
        )
        val global = one(o, d)
        val devices = (o["devices"] as? JsonObject)?.entries?.associate { (k, v) ->
            k to ((v as? JsonObject)?.let { one(it, global) } ?: global)
        } ?: emptyMap()
        return global.copy(devices = devices)
    }

    /** Idle-clock config, same per-remote `devices:` shape as the others. */
    private fun parseScreensaver(o: JsonObject?): ScreensaverConfig {
        val d = ScreensaverConfig()
        if (o == null) return d
        fun one(j: JsonObject, base: ScreensaverConfig) = ScreensaverConfig(
            enabled = (j["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: base.enabled,
            idleSeconds = (j["idle_seconds"] as? JsonPrimitive)?.content?.toIntOrNull() ?: base.idleSeconds,
            clock24h = (j["clock_24h"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: base.clock24h,
            color = (j["color"] as? JsonPrimitive)?.content ?: base.color,
            showDate = (j["show_date"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: base.showDate,
            dateFormat = (j["date_format"] as? JsonPrimitive)?.content ?: base.dateFormat,
            brightness = (j["brightness"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: base.brightness,
            dayBrightness = (j["day_brightness"] as? JsonPrimitive)?.content?.toFloatOrNull() ?: base.dayBrightness,
            dayEntity = (j["day_entity"] as? JsonPrimitive)?.content ?: base.dayEntity,
            dayStates = (j["day_states"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: base.dayStates,
        )
        val global = one(o, d)
        val devices = (o["devices"] as? JsonObject)?.entries?.associate { (k, v) ->
            k to ((v as? JsonObject)?.let { one(it, global) } ?: global)
        } ?: emptyMap()
        return global.copy(devices = devices)
    }

    private fun parseHotkey(obj: JsonObject): HotkeyConfig {
        val key = (obj["key"] as? JsonPrimitive)?.content ?: error("hotkey missing \"key\"")
        val page = (obj["page"] as? JsonPrimitive)?.content
        val service = (obj["service"] as? JsonPrimitive)?.content
        val entityId = (obj["entityId"] as? JsonPrimitive)?.content
        val data = (obj["data"] as? JsonObject)
            ?.entries?.associate { (k, v) -> k to JsonPlain.toPlain(v) }
            ?: emptyMap()
        val scrollTo = (obj["scroll_to"] as? JsonPrimitive)?.content
        val action = (obj["action"] as? JsonPrimitive)?.content
        // Absent stays null (= "use the built-in default"), so listing one quiet
        // key in a layout does not implicitly un-quiet every other key.
        val quiet = (obj["quiet"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        return HotkeyConfig(key, page, service, entityId, data, scrollTo, action, quiet)
    }

    // ---- serialize defaults -------------------------------------------------

    private fun writeDefaults(): Boolean = try {
        val file = configFile
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(JsonObject.serializer(), encode(DashboardConfig.default)))
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't write default config", e)
        false
    }

    private fun encode(cfg: AppConfig): JsonObject = buildJsonObject {
        put("startPage", cfg.startPage)
        put("pages", buildJsonArray {
            cfg.pages.forEach { page ->
                add(buildJsonObject {
                    put("name", page.name)
                    put("cards", buildJsonArray {
                        page.cards.forEach { card ->
                            add(buildJsonObject {
                                put("type", card.type)
                                put("options", JsonPlain.toJson(card.options))
                            })
                        }
                    })
                    // Emitted only when present, so a layout with no page-scoped
                    // overrides still writes byte-identically to before.
                    if (page.hotkeys.isNotEmpty()) put("hotkeys", encodeHotkeys(page.hotkeys))
                    if (page.longHotkeys.isNotEmpty()) {
                        put("longHotkeys", encodeHotkeys(page.longHotkeys))
                    }
                    page.voice?.path?.let { p ->
                        put("voice", buildJsonObject { put("path", p) })
                    }
                })
            }
        })
        cfg.status?.let { s ->
            put("status", buildJsonObject {
                put("title", s.title)
                put("cards", buildJsonArray {
                    s.cards.forEach { card ->
                        add(buildJsonObject {
                            put("type", card.type)
                            put("options", JsonPlain.toJson(card.options))
                        })
                    }
                })
            })
        }
        cfg.pageEntity?.let { put("page_entity", it) }
        cfg.overlay?.let { o ->
            put("overlay", buildJsonObject {
                o.volumeEntity?.let { put("volume_entity", it) }
                o.muteEntity?.let { put("mute_entity", it) }
                o.condition?.let { put("condition", JsonPlain.toJson(it.options)) }
            })
        }
        cfg.gestures?.swipeDown?.let { g ->
            put("gestures", buildJsonObject {
                put("swipe_down", buildJsonObject {
                    put("title", g.title)
                    put("items", buildJsonArray {
                        g.items.forEach { i ->
                            add(buildJsonObject {
                                put("name", i.name)
                                put("action", i.action)
                                i.packageName?.let { put("package", it) }
                            })
                        }
                    })
                })
            })
        }
        put("hotkeys", encodeHotkeys(cfg.hotkeys))
        put("longHotkeys", encodeHotkeys(cfg.longHotkeys))
    }

    private fun encodeHotkeys(hotkeys: List<HotkeyConfig>) = buildJsonArray {
        hotkeys.forEach { hk ->
            add(buildJsonObject {
                put("key", hk.key)
                hk.page?.let { put("page", it) }
                hk.service?.let { put("service", it) }
                hk.entityId?.let { put("entityId", it) }
                if (hk.data.isNotEmpty()) put("data", JsonPlain.toJson(hk.data))
                hk.scrollTo?.let { put("scroll_to", it) }
                hk.action?.let { put("action", it) }
                hk.quiet?.let { put("quiet", it) }
            })
        }
    }
}
