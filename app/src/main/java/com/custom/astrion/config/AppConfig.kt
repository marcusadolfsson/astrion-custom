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
    /** Presentation for THIS device; see [UiConfig]. Defaults are the HA100's. */
    val ui: UiConfig = UiConfig(),
    /** Motion-wake tuning; see [MotionWakeConfig]. Absent means built-in defaults. */
    val motionWake: MotionWakeConfig = MotionWakeConfig(),
    /** What the display does while the remote is on charge. */
    val dockDisplay: DockDisplayConfig = DockDisplayConfig(),
    /** Idle clock shown on a docked remote; see [ScreensaverConfig]. */
    val screensaver: ScreensaverConfig = ScreensaverConfig(),
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
/**
 * How the layout is PRESENTED on this device, as opposed to what it contains.
 *
 * Every default here reproduces the HA100 exactly, so a layout file that says
 * nothing about `ui` behaves as it always has. These are set in a per-device
 * override file -- the Pixel Tablet is a 1280x800dp landscape screen with no
 * hardware buttons, which is a different presentation of the same dashboard,
 * not a different dashboard.
 */
data class UiConfig(
    /**
     * `portrait` | `landscape` | `auto`. Applied with setRequestedOrientation,
     * which overrides the manifest at runtime -- so the manifest stays pinned to
     * portrait and a handheld remote cannot rotate even if its layout is broken.
     */
    val orientation: String = "portrait",
    /**
     * Cards per row. Above 1 the page switches to a staggered grid and
     * `separator` cards take a full-width line of their own, so sections still
     * read as sections instead of being shuffled into a column.
     */
    val columns: Int = 1,
    /**
     * Show the "Screen-off keys" and "Stock app" sections in the swipe-up panel.
     * Both are HA100 internals -- the evdev input bridge and the OEM launcher's
     * wake lock -- and neither exists on a tablet.
     */
    val deviceInternals: Boolean = true,
    /**
     * Uniform UI scale, applied by overriding LocalDensity for the whole
     * dashboard, so every dp AND sp shrinks together and no card needs its own
     * tablet sizes. 1.0 is the HA100's design size; a 10" panel wants ~0.8,
     * where the same card carries the same proportions in less glass.
     */
    val scale: Float = 1f,
    /**
     * Margin in dp around the whole dashboard. The HA100 uses every pixel it
     * has; a wall tablet looks better inset from the bezel. Applied inside the
     * background, so the page colour still reaches the edge.
     */
    val padding: Int = 0,
    /**
     * Lock the device into this app (lock task mode). Requires the app to be
     * DEVICE OWNER -- without that the call is refused and this is a no-op, so
     * it is safe to leave set on a device that lost ownership.
     *
     * With ownership, LOCK_TASK_FEATURE_NONE also removes the system bars
     * outright, including the gesture handle that immersive mode cannot hide.
     */
    val kiosk: Boolean = false,
    /** Helper holding the PIN; compared locally. Blank/unset falls back to 0000. */
    val kioskPinEntity: String = "input_text.tv_wall_lock_pin",
    /**
     * How long the kiosk stays off after a correct PIN. Without this the app
     * would re-lock on the very next resume and the PIN would buy nothing.
     */
    val kioskExitMinutes: Int = 5,
    /**
     * Cap the full media card's album art at this many dp. 0 = uncapped, which
     * keeps the art's 1.2 aspect ratio -- the HA100's behaviour, unchanged.
     *
     * A wide lane makes the aspect ratio the problem: at ~520dp across, the art
     * alone is ~430dp tall and pushes the transport keys below the fold. This is
     * a `ui` setting rather than a card option precisely so the remotes cannot
     * be affected by a value set for the tablet -- they share the card.
     */
    val mediaArtMaxHeight: Int = 0,
    /**
     * Package that takes HOME back when the kiosk is exited. Blank just
     * releases our claim, which on a device where this app is also the chosen
     * default launcher means Home comes straight back here -- i.e. the exit
     * appears not to have worked, which is exactly what it looked like.
     */
    val kioskHomePackage: String = "",
    /**
     * Connect to the evdev input bridge.
     *
     * True for a remote, whose physical keys only reach the app through it.
     * False for a device that has no keys to bridge: the client retries a
     * refused localhost connection every three seconds forever -- correct on a
     * remote where the bridge may start at any time, pure noise on a tablet
     * where it never will, and enough of it to bury the log you are reading.
     */
    val inputBridge: Boolean = true,
)

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
     * The angle is computed from the DIFFERENCE between the current gravity
     * estimate and the resting one, measured against the physical constant g --
     * not from the angle between two normalised vectors. That form is what
     * survives the biased sensor found on one of these remotes, which rests at
     * ~+1.1 g on its z axis (raw 1787 counts where its twin reads 657, x and y
     * ordinary, both calibration stores empty). Normalising does NOT cancel a
     * constant bias -- it pulls the computed direction toward the biased axis and
     * compresses every angle -- but subtracting two measurements does, because
     * the bias is in both. So one config number finally means the same movement
     * on every unit, healthy or not.
     */
    val tiltDegrees: Float = 10f,
    /**
     * Sudden movement as a FRACTION of this device's own resting gravity.
     *
     * Kept alongside the tilt test so a deliberate shake still wakes a remote
     * that is not being turned. Expressed as a ratio of g rather than as raw
     * m/s² so it reads the same on any unit; like the tilt term it is a
     * difference, so a constant sensor bias cancels.
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
     * Skip motion wake while the remote is on power. Off by default.
     *
     * Off because it cannot be aimed at the case it was written for. The idea
     * was to suppress wake-on-raise for a remote PARKED IN ITS DOCK while
     * leaving it working for one on a cable in your hand -- but this hardware
     * cannot tell those apart. Measured on two remotes, one docked and one on a
     * USB-C charger: both report BATTERY_PLUGGED_AC, and both read
     * `ac/online = 1`, `usb/online = 0` in sysfs. The cradle's pins feed the
     * same charging path, and a dumb charger enumerates as Mains either way.
     *
     * It is also unnecessary. While docked, dock display holds the screen ON, so
     * wakeScreen() returns at its own isInteractive check before this gate is
     * reached. The only time the gate would bite is charging with the screen
     * off -- below the battery floor, or with dock display disabled -- and those
     * are exactly the times you would want a pick-up to wake it.
     */
    val ignoreWhileCharging: Boolean = false,
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

/**
 * A large digital clock, shown on a DOCKED remote once it has been idle.
 *
 * Only while charging, and only after [idleSeconds], because those two
 * conditions are what make it free: the dock supplies mains power, and an idle
 * remote is showing a dashboard nobody is reading. Off the dock the display
 * still sleeps normally, so this cannot cost battery.
 *
 * Any touch or button returns to the dashboard, and the idle timer starts again.
 */
data class ScreensaverConfig(
    /** Off by default: it replaces what an idle remote shows. */
    val enabled: Boolean = false,
    /** Idle time before the clock appears. */
    val idleSeconds: Int = 90,
    /** 24-hour clock; false shows 12-hour with a small AM/PM. */
    val clock24h: Boolean = false,
    /**
     * Clock colour, "#RRGGBB".
     *
     * Not white. This sits in a dark bedroom for eight hours a night, where
     * full white on black is a lamp; a dimmed grey stays readable across the
     * room without lighting it. Tunable because the right amount of dim is a
     * matter of the room, not of the code.
     */
    val color: String = "#6E6E6E",
    /** Show the date under the time. */
    val showDate: Boolean = true,
    /**
     * Date pattern, `SimpleDateFormat` syntax. Default is US order —
     * weekday, month, day: "Friday August 21".
     */
    val dateFormat: String = "EEEE MMMM d",
    /**
     * Backlight while the clock is up, 0..1 — SEPARATE from the dock's dim level.
     *
     * It has to be, and this was not obvious until the clock had been running
     * for an hour and looked like it had never started: the dock dims the
     * dashboard to 1% because nobody is meant to read it, but a clock is meant
     * to be read from across the room. At the dashboard's dim level the clock is
     * rendering perfectly and is simply invisible.
     */
    val brightness: Float = 0.15f,
    /**
     * Backlight while [dayEntity] reads one of [dayStates] — the daytime level.
     *
     * A clock wants opposite things at opposite ends of the day: readable across
     * a sunlit room, and not a lamp at 3am. One number cannot be both, so there
     * are two and something outside decides which applies.
     */
    val dayBrightness: Float = 1.0f,
    /**
     * Entity that says whether it is daytime. Absent means always use
     * [brightness], which is the safe direction to fail: dim.
     *
     * An entity rather than a clock time because the house already has an
     * opinion about this and it drives the lighting; a second, independent
     * definition of "night" would drift from the first and be wrong in exactly
     * the season when it matters.
     */
    val dayEntity: String? = null,
    /** States of [dayEntity] that count as daytime. */
    val dayStates: List<String> = listOf("day"),
    /** Per-remote overrides, keyed by the `device` slug in ConnectionConfig. */
    val devices: Map<String, ScreensaverConfig> = emptyMap(),
) {
    fun forDevice(device: String): ScreensaverConfig =
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
