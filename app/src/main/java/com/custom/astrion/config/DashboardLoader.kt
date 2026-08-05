package com.custom.astrion.config

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
     * HA-served location of the master layout: /config/www/astrion/dashboard.json
     * -> http://<ha>/local/astrion/dashboard.json. The app syncs this over the
     * HA connection into the local cache file below, so layout edits no longer
     * need `adb push` — edit the file in HA and the app pulls it on next sync.
     */
    const val REMOTE_PATH = "/local/astrion/dashboard.json"

    /** Local cache, written by a successful sync; used offline / before first sync. */
    val configFile: File
        get() = File(Environment.getExternalStorageDirectory(), "astrion/dashboard.json")

    /**
     * Validate freshly-fetched remote JSON and, if valid AND different from the
     * current cache, write it to the cache and return the parsed config. Returns
     * null when the text is unchanged (no reload needed) or invalid (cache kept).
     */
    fun loadFromText(text: String): Result? {
        if (runCatching { configFile.readText() }.getOrNull() == text) return null
        return try {
            val cfg = parse(text) // throws on malformed JSON
            configFile.parentFile?.mkdirs()
            configFile.writeText(text)
            Result(cfg, null)
        } catch (e: Exception) {
            Log.w(TAG, "remote dashboard.json invalid, keeping cache: ${e.message}")
            null
        }
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
                    PageConfig(name, cards)
                }
                if (pages.isEmpty()) error("\"pages\" is empty")
                val start = (root["startPage"] as? JsonPrimitive)?.intOrNull ?: 0
                val hotkeys = (root["hotkeys"] as? JsonArray)?.map { parseHotkey(it as JsonObject) } ?: emptyList()
                val longHotkeys = (root["longHotkeys"] as? JsonArray)?.map { parseHotkey(it as JsonObject) } ?: emptyList()
                AppConfig(pages, start.coerceIn(0, pages.size - 1), hotkeys, longHotkeys)
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
        return HotkeyConfig(key, page, service, entityId, data, scrollTo, action)
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
                })
            }
        })
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
            })
        }
    }
}
