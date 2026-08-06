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
)

/** One swipeable page: a name (used by hotkey `page` navigation) and its cards. */
data class PageConfig(
    val name: String,
    val cards: List<CardConfig>,
)

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
)
