package com.custom.astrion.input

/**
 * Physical button map for the Astrion HA100 hardware.
 *
 * These keycodes were extracted directly from the stock app's
 * assets/device_key_code.json (the "HA100" block). When a physical button is
 * pressed, the OS delivers a standard Android KeyEvent with these codes to the
 * focused Activity, which is why a standalone app can handle them via
 * dispatchKeyEvent / onKeyDown — no vendor SDK required for the buttons.
 *
 * The dedicated shortcut buttons (light / curtain / scene / ac / custom_1..4)
 * are the real prize: you can bind each of them to any action you want, instead
 * of HaRemote's fixed behaviour.
 */
enum class HardwareKey {
    BACK, HOME, POWER,
    VOLUME_UP, VOLUME_DOWN, MUTE,
    PAGE_UP, PAGE_DOWN,
    UP, DOWN, LEFT, RIGHT, CENTER,
    VOICE, MENU,
    LIGHT, CURTAIN, SCENE, AC,
    CUSTOM_1, CUSTOM_2, CUSTOM_3, CUSTOM_4,
    UNKNOWN;

    companion object {
        // Android keycode -> logical button, from device_key_code.json (HA100),
        // corrected against the real hardware: the physical MUTE button emits
        // 164 (KEYCODE_VOLUME_MUTE), not 82/91. 82 is KEYCODE_MENU (the button
        // opposite mute) — mapped to MENU so it stops acting as mute.
        private val MAP: Map<Int, HardwareKey> = mapOf(
            4 to BACK,
            131 to HOME,
            132 to POWER,
            24 to VOLUME_UP,
            25 to VOLUME_DOWN,
            92 to PAGE_UP,
            93 to PAGE_DOWN,
            19 to UP,
            20 to DOWN,
            21 to LEFT,
            22 to RIGHT,
            23 to CENTER,
            164 to MUTE,
            91 to MUTE,
            82 to MENU,
            133 to VOICE,
            134 to LIGHT,
            135 to CURTAIN,
            136 to SCENE,
            137 to AC,
            138 to CUSTOM_1,
            139 to CUSTOM_2,
            140 to CUSTOM_3,
            141 to CUSTOM_4,
        )

        fun fromKeyCode(code: Int): HardwareKey = MAP[code] ?: UNKNOWN
    }
}

/**
 * Bind hardware buttons to actions. Register short- and long-press handlers at
 * startup; MainActivity does the tap-vs-hold timing and calls back here.
 */
class HardwareKeyRouter {
    private val shortHandlers = mutableMapOf<HardwareKey, () -> Boolean>()
    private val longHandlers = mutableMapOf<HardwareKey, () -> Boolean>()

    fun on(key: HardwareKey, handler: () -> Boolean) {
        shortHandlers[key] = handler
    }

    fun onLong(key: HardwareKey, handler: () -> Boolean) {
        longHandlers[key] = handler
    }

    /** Drop all bindings — used before rebinding from a reloaded config. */
    fun clear() {
        shortHandlers.clear()
        longHandlers.clear()
    }

    fun shortHandler(code: Int): (() -> Boolean)? {
        val key = HardwareKey.fromKeyCode(code)
        return if (key == HardwareKey.UNKNOWN) null else shortHandlers[key]
    }

    fun longHandler(code: Int): (() -> Boolean)? {
        val key = HardwareKey.fromKeyCode(code)
        return if (key == HardwareKey.UNKNOWN) null else longHandlers[key]
    }
}
