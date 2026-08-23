package com.custom.astrion.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.HaClient
import com.custom.astrion.input.HardwareKey

/**
 * THE EXTENSIBILITY CORE.
 *
 * This is the thing the stock HaRemote app fundamentally does not have: an open
 * card registry. In HaRemote, the set of card types is a hardcoded static list
 * of 11 parsers (CardConfigParserFactory.<clinit>), with no plugin path — any
 * dashboard card whose `type` isn't one of their `custom:aiks-*` strings is
 * silently dropped, and the recognized ones are drawn in a fixed native style
 * you can't change.
 *
 * Here, adding a brand-new native card type is a two-line affair:
 *   1. Implement CardRenderer for your new `type` string.
 *   2. Register it in CardRegistry.
 * ...and it can render literally any Compose UI you want, with any layout.
 *
 * A card in *your* dashboard config is just:
 *   { type: "<your-type>", <arbitrary options...> }
 * and CardConfig.options carries those options straight through to your renderer.
 */

/** One card entry from your dashboard layout config. */
data class CardConfig(
    val type: String,
    /** Free-form per-card options. Your renderer decides how to read these. */
    val options: Map<String, Any?> = emptyMap(),
) {
    fun string(key: String): String? = options[key] as? String
    fun stringList(key: String): List<String> =
        (options[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    fun bool(key: String, default: Boolean = false): Boolean =
        options[key] as? Boolean ?: default
    fun int(key: String, default: Int = 0): Int =
        (options[key] as? Number)?.toInt() ?: default
}

/**
 * Context handed to every card render. Gives the card read access to live
 * entity states and the ability to fire service calls back to HA.
 */
/*
 * PERFORMANCE — why this is @Stable and why the instance must be remembered:
 *
 * Compose skips a composable only when its parameters compare equal. An unstable
 * parameter (this class, holding a Map) is compared by INSTANCE, so allocating a
 * fresh CardContext on every entity update meant no card could ever skip — every
 * visible card re-executed up to 8x/second (the publish rate) whenever any
 * subscribed entity changed, on a 1 GB MT6580.
 *
 * Now `entities` is a SnapshotStateMap and this context is remembered, so the
 * instance is stable. Reading `ctx.entities[id]` inside composition subscribes to
 * THAT KEY ONLY, so an entity change recomposes just the cards that read it.
 * Keep it that way: never iterate the whole map inside composition (values/keys/
 * forEach) — that would subscribe to every key and undo all of this.
 */
@Stable
class CardContext(
    val entities: EntityMap,
    val client: HaClient,
    /** Section name a hardware key asked to "open" (a sole-in-section
     *  bubble_select pops its dropdown when its `open_on` matches). Held as
     *  State so changing it doesn't change this context's identity — only the
     *  cards that actually read it recompose. */
    private val openTargetState: State<String?> = mutableStateOf(null),
    /**
     * Fire a logical hardware key, exactly as if the physical button had been
     * pressed. Lets an on-screen control (see DpadCard) reuse the layout's own
     * `hotkeys:` table rather than restating every service call -- so a tablet's
     * drawn D-pad and a remote's real one stay identical by construction,
     * including page-scoped overrides. Default is a no-op for previews/tests.
     */
    val onHardwareKey: (HardwareKey) -> Unit = {},
) {
    val openTarget: String? get() = openTargetState.value
}

/**
 * Implement this to create a new native card type. `type` is the string you'll
 * use in your dashboard config; Render is plain Jetpack Compose, so you have
 * full control over layout, colours, animation, sizing — everything you can't
 * do inside HaRemote's fixed renderers.
 */
interface CardRenderer {
    val type: String

    @Composable
    fun Render(config: CardConfig, ctx: CardContext)
}

/**
 * Global registry. Register once at startup (see AstrionApp). Lookup by type
 * happens when the dashboard is built.
 */
object CardRegistry {
    private val renderers = LinkedHashMap<String, CardRenderer>()

    fun register(renderer: CardRenderer) {
        renderers[renderer.type] = renderer
    }

    fun register(vararg rs: CardRenderer) = rs.forEach { register(it) }

    fun get(type: String): CardRenderer? = renderers[type]

    fun known(): Set<String> = renderers.keys
}
