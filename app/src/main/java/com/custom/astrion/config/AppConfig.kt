package com.custom.astrion.config

import com.custom.astrion.cards.CardConfig

/**
 * Full app configuration: swipeable pages of cards plus hardware-button
 * bindings. Loaded from /sdcard/astrion/dashboard.json by DashboardLoader,
 * with DashboardConfig.default as the compiled-in fallback.
 */
data class AppConfig(
    /** Left-to-right page order; swipe between them. */
    val pages: List<PageConfig>,
    /** Index of the page shown at launch (the "home" page). */
    val startPage: Int = 0,
    /** Short-press button bindings. */
    val hotkeys: List<HotkeyConfig> = emptyList(),
    /** Long-press (~500ms hold) button bindings — same shape as hotkeys. */
    val longHotkeys: List<HotkeyConfig> = emptyList(),
    /** Optional transient volume/mute overlay. */
    val overlay: OverlayConfig? = null,
    /** Optional VOICE-key config; absent means the key does nothing. */
    val voice: VoiceConfig? = null,
    /** Optional screen-edge gestures; absent means they do nothing. */
    val gestures: GesturesConfig? = null,
    /**
     * Diagnostics shown by the swipe-up panel's Status row, off the pager.
     *
     * These readouts are reference material you go LOOKING for -- they answer
     * "what is the chain actually doing right now" -- so they do not earn a
     * permanent dot next to the rooms you use daily. Absent means the panel
     * simply shows no Status row.
     */
    val status: StatusConfig? = null,
    /**
     * An input_select whose options are page names, mirrored both ways: HA
     * writing it navigates the remote, and swiping the remote writes it back.
     *
     * Per REMOTE, not per layout -- it belongs in that remote's override file.
     * Absent means the remote neither follows nor reports, which is the correct
     * behaviour for a device with no helper of its own.
     */
    val pageEntity: String? = null,
    /** Motion-wake tuning; see [MotionWakeConfig]. Absent means built-in defaults. */
    val motionWake: MotionWakeConfig = MotionWakeConfig(),
    /** What the display does while the remote is on charge. */
    val dockDisplay: DockDisplayConfig = DockDisplayConfig(),
)

/**
 * Motion wake: light the screen when the remote is picked up.
 *
 * Tunable, and tunable PER REMOTE, because the assumption underneath it is not
 * universal. It assumes the remote rests on something still. Two of these live
 * in a bed, where the surface moves whenever its owner does — at the original
 * fixed 0.9 m/s² that re-lit the display within seconds of every timeout, all
 * night. The living-room unit on a side table has no such problem, so one set of
 * numbers cannot serve both.
 *
 * [hits] is the part that actually separates the two cases. Amplitude alone does
 * not: rolling over can outweigh a gentle pick-up. But a pick-up is SUSTAINED —
 * the remote keeps moving while it travels to your hand — where a bed jolt is
 * one spike and over. Requiring several consecutive samples above [threshold]
 * discriminates on duration rather than force.
 */
data class MotionWakeConfig(
    /** Off entirely. The `no-motion-wake` file flag also forces this off. */
    val enabled: Boolean = true,
    /**
     * Degrees of TILT that count as a pick-up.
     *
     * The detector watches the direction of the gravity vector, not the size of
     * an acceleration spike, because that is what actually distinguishes the two
     * cases here: picking a remote off a surface rotates it several degrees,
     * while a knock or a body-shift on a bed jolts it without turning it. The
     * old magnitude test could not tell those apart at any threshold -- to it
     * they are the same number -- which is why tuning it kept trading "wakes in
     * your hand" against "wakes all night".
     *
     * Angles are also immune to the scale fault found on one of these remotes,
     * whose accelerometer reads ~2x true (18.9 m/s² at rest against 9.6 on its
     * twin). Normalising the vector cancels that, so one config number finally
     * means the same thing on every unit.
     */
    val tiltDegrees: Float = 10f,
    /**
     * Sudden movement as a FRACTION of this device's own resting gravity.
     *
     * Kept alongside the tilt test so a deliberate shake still wakes a remote
     * that is not being turned. Expressed as a ratio rather than m/s² for the
     * same reason as above: 0.25 means a quarter of g on any unit, however its
     * sensor is scaled.
     */
    val jerkRatio: Float = 0.25f,
    /** Samples that must qualify before the screen lights. */
    val hits: Int = 2,
    /** Ignore motion for this long after the display turns off. */
    val settleSeconds: Int = 8,
    /** Minimum gap between two wakes. */
    val cooldownSeconds: Int = 2,
    /**
     * Rolling window the [hits] must land inside.
     *
     * Hits are counted per-window rather than consecutively because real
     * movement is not monotonic: a hard flick produces large deltas with small
     * ones interleaved as the acceleration reverses, and strict consecutiveness
     * made even a violent shake fail to register.
     */
    val windowMs: Long = 1200,
    /**
     * Skip motion wake entirely while the remote is charging.
     *
     * A docked remote does not need to be woken by movement: it is sitting
     * still, and the hand that reaches for it will press something. This costs
     * nothing when it is right and saves the whole problem when it is not.
     */
    val ignoreWhileCharging: Boolean = true,
    /** Per-remote overrides, keyed by the `device` slug in ConnectionConfig. */
    val devices: Map<String, MotionWakeConfig> = emptyMap(),
) {
    /**
     * This remote's effective settings: its own block if it has one, else the
     * global values. A device block is a whole-value override for the same
     * reason a page's overlay is — threshold and hits are tuned together, and
     * inheriting one while overriding the other gives a combination nobody chose.
     */
    fun forDevice(device: String): MotionWakeConfig =
        devices[device]?.copy(devices = emptyMap()) ?: copy(devices = emptyMap())
}

/**
 * What the display does while the remote is charging.
 *
 * A docked remote is a small always-on status panel, so letting it sleep wastes
 * the one thing a dock makes free: mains power. Held on at the lowest readable
 * backlight it stays glanceable, and any touch brings it to full brightness for
 * [brightSeconds] before it fades back.
 *
 * Deliberately gated on CHARGING rather than on a dock intent: these remotes
 * report no dock state, and "on power" is the same question in practice.
 */
data class DockDisplayConfig(
    /** Off by default: this changes what an idle remote looks like all night. */
    val enabled: Boolean = false,
    /** Backlight while idle on the dock, 0..1. */
    val dimLevel: Float = 0.02f,
    /** Backlight after a touch, 0..1. */
    val brightLevel: Float = 0.6f,
    /** How long a touch holds full brightness before fading back to [dimLevel]. */
    val brightSeconds: Int = 20,
    /** Per-remote overrides, keyed by the `device` slug in ConnectionConfig. */
    val devices: Map<String, DockDisplayConfig> = emptyMap(),
) {
    fun forDevice(device: String): DockDisplayConfig =
        devices[device]?.copy(devices = emptyMap()) ?: copy(devices = emptyMap())
}

/** The full-screen Status view: a title and the same cards any page can hold. */
data class StatusConfig(
    val title: String = "Status",
    val cards: List<CardConfig> = emptyList(),
)

/**
 * Screen-edge gestures. The bottom bar already answers a swipe UP with the
 * info/sync panel; this is its mirror at the top of the screen.
 *
 * Config shape:
 *   { "gestures": { "swipe_down": {
 *       "action": "app_info", "package": "com.example.app"
 *   } } }
 */
data class GesturesConfig(
    val swipeDown: GesturePanel? = null,
)

/**
 * A sheet of shortcuts opened by a gesture. A LIST rather than a single action
 * because one destination is never enough in practice -- the OEM launcher's
 * App info screen is what prompted this, but Wi-Fi and Display get wanted just
 * as often, and one extra gesture per destination is not a design.
 */
data class GesturePanel(
    val title: String = "Device",
    val items: List<GestureAction> = emptyList(),
)

/**
 * One row in the sheet. A closed set of NAMED actions rather than an arbitrary
 * intent string: the layout is fetched over the network, and "launch anything"
 * is a far larger surface than this needs.
 *
 *   app_info       a package's App info screen (needs `package`) -- carries
 *                  Force stop, and Enable for a disabled package
 *   settings       system Settings
 *   wifi / display / sound / bluetooth / apps / date / storage /
 *   developer / accessibility / device_info
 */
data class GestureAction(
    val name: String,
    val action: String,
    val packageName: String? = null,
)

/**
 * The VOICE key: press, talk, and it ends itself on silence.
 *
 * The remote deliberately makes NO routing decision — it POSTs raw PCM16
 * (16 kHz, mono) to [path] and lets the server decide what to do with it. Point
 * it wherever you like: this setup uses a custom component that forwards to
 * Siri on an Apple TV or to Assist depending on what's on screen, but any
 * endpoint that accepts a chunked audio body will do.
 *
 * The body is streamed as the microphone produces it, so a server can begin
 * consuming the utterance before the user has finished speaking.
 */
data class VoiceConfig(
    /** Path on the HA base URL that receives the audio stream. */
    val path: String = "/api/hap_remote/audio",
    /** Hard cap on one utterance. */
    val maxMs: Int = 10_000,
    /** Quiet needed AFTER speech before the utterance is considered finished. */
    val silenceMs: Int = 1_200,
    /**
     * Give up if the user never speaks. Without this an accidental press holds
     * the microphone — and, on the Siri route, the Apple TV's SIRI button —
     * open for the whole [maxMs] window.
     */
    val noSpeechMs: Int = 4_000,
    /**
     * Prompts shown while listening -- what you can usefully SAY.
     *
     * A microphone that is listening tells you it works but not what it
     * understands, and a voice interface with no discoverable vocabulary gets
     * used for the two phrases you already know. These are shown at the moment
     * of the press, when the person is deciding what to say.
     *
     * Gated on an entity so the prompts can be specific to what is on: movie
     * searches are useful advice in front of a Kaleidescape and noise in front
     * of anything else. Leave [suggestEntity] unset to always show them.
     */
    val suggestions: List<String> = emptyList(),
    val suggestTitle: String = "Try saying",
    val suggestEntity: String? = null,
    val suggestState: String? = null,
)

/**
 * Transient on-screen readout shown when the volume or mute state changes —
 * the remote's own OSD, so a volume press gives feedback without looking up at
 * the TV.
 *
 * `volumeEntity` should be whatever is stepped *immediately* on the button press
 * (e.g. an input_number the volume scripts write), not the media player's own
 * volume_level, which lags behind the speaker's state feedback.
 */
data class OverlayConfig(
    val volumeEntity: String? = null,
    /** Entity carrying `is_volume_muted` (typically the media_player). */
    val muteEntity: String? = null,
    /**
     * Optional gate — the OSD is suppressed while this does not match. Same
     * shape as a `conditional` card's options (`entity_id` plus one of `state`,
     * `state_not`, `state_in`), and evaluated by the same code.
     *
     * The case this exists for: the speaker gets muted as a side effect of the
     * system being switched off, and reporting that is noise — the user did not
     * press mute and the screen they would look at is going dark anyway.
     */
    val condition: CardConfig? = null,
)

/**
 * One swipeable page: a name (used by hotkey `page` navigation) and its cards.
 *
 * A page may also carry its own hotkey bindings and VOICE target, which is what
 * makes the visible page the ROOM CONTEXT: swipe to the bedroom and the D-pad
 * drives the bedroom's Apple TV, because the keys resolve against this page
 * first. Resolution is per KEY -- a page overrides only the keys it names and
 * inherits the rest -- so a room page does not have to restate POWER, VOICE,
 * MENU and the four CUSTOM keys just to change the four it cares about.
 *
 * To make a key do NOTHING on a page, list it with no action (`- key: LIGHT`):
 * it resolves, is handled, and falls through to nothing.
 */
data class PageConfig(
    val name: String,
    val cards: List<CardConfig>,
    /** Page-scoped short-press bindings; override the global list per key. */
    val hotkeys: List<HotkeyConfig> = emptyList(),
    /** Page-scoped long-press bindings; override the global list per key. */
    val longHotkeys: List<HotkeyConfig> = emptyList(),
    /** Page-scoped VOICE overrides; unset fields inherit the global `voice:`. */
    val voice: PageVoiceConfig? = null,
    /**
     * Page-scoped volume/mute OSD. WHOLE-VALUE override, not a field merge like
     * [voice]: an overlay names a speaker, its instant-feedback cache and the
     * gate that decides when announcing is appropriate, and those three only
     * make sense together. Inheriting two of them from another room would
     * announce the wrong speaker's volume, which is exactly the bug this fixes.
     *
     * Unset means the global `overlay:` applies, so rooms that share the Living
     * Room's speaker need no config at all.
     */
    val overlay: OverlayConfig? = null,
)

/**
 * Partial override of [VoiceConfig]. Every field nullable = "not stated", the
 * same tri-state discipline as HotkeyConfig.quiet -- so a page that only wants a
 * different Apple TV does not silently blank the global prompts and timings.
 */
data class PageVoiceConfig(
    val path: String? = null,
)

/** Global voice config with a page's partial override applied over it. */
fun VoiceConfig?.mergedWith(o: PageVoiceConfig?): VoiceConfig? = when {
    o == null -> this
    this == null -> VoiceConfig(path = o.path ?: VoiceConfig().path)
    else -> copy(path = o.path ?: path)
}

/**
 * One physical-button binding. `key` is a HardwareKey name — the HA100 has:
 * UP DOWN LEFT RIGHT CENTER, PAGE_UP PAGE_DOWN, VOLUME_UP VOLUME_DOWN MUTE,
 * BACK HOME POWER VOICE, LIGHT CURTAIN SCENE AC, CUSTOM_1..CUSTOM_4.
 *
 * Exactly one action per binding:
 *  - `page`: navigate to the page with that name (case-insensitive),
 *  - `scrollTo` (JSON `scroll_to`): scroll the target page to the `separator`
 *    card whose name matches (combine with `page` to jump pages then scroll), or
 *  - `service` ("domain.service") + optional `entityId` + flat `data` map.
 *
 * More than one may be set (e.g. page + scrollTo, or scrollTo + service).
 */
data class HotkeyConfig(
    val key: String,
    val page: String? = null,
    val service: String? = null,
    val entityId: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    val scrollTo: String? = null,
    /** Built-in app action, currently just "sync" (re-pull the dashboard from HA). */
    val action: String? = null,
    /**
     * Whether this key may act with the screen OFF, without lighting it.
     *
     * Tri-state on purpose. `null` = "not stated", so the app's built-in
     * QUIET_KEYS default decides; `true`/`false` override it in either direction.
     * A plain Boolean would have made every unstated key an explicit `false` and
     * silently disabled the defaults the moment a layout listed one hotkey.
     *
     * This has to be per-layout rather than a constant because the HA100A and
     * HA100B remotes print different legends on the SAME keycodes: LIGHT is a
     * page jump on the A unit (wants the screen up) and SCAN on the B units
     * (wants it left dark). See astrion/dashboard.b.yaml.
     */
    val quiet: Boolean? = null,
)
