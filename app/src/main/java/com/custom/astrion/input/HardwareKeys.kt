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

        /**
         * RAW KERNEL scancodes from /dev/input/event1 (mt_gpio_kpd), as read by
         * the input bridge. These are Linux KEY_* codes, NOT Android keycodes --
         * a different numbering entirely, which is why this is a separate table
         * rather than a few extra entries above.
         *
         * Measured on the device by pressing each button with the bridge running
         * and naming them in order; corroborated independently by Key Mapper,
         * which recorded the red button as F8 (66) exactly as this table says.
         *
         * The F-key positions are the four coloured buttons and the four labelled
         * shortcuts, which is how the HA100 wires its top rows.
         */
        private val SCAN_MAP: Map<Int, HardwareKey> = mapOf(
            59 to HOME,          // KEY_F1
            61 to VOICE,         // KEY_F3
            62 to LIGHT,         // KEY_F4
            63 to CURTAIN,       // KEY_F5  (labelled "shade")
            64 to SCENE,         // KEY_F6  (labelled "music")
            65 to AC,            // KEY_F7
            66 to CUSTOM_1,      // KEY_F8   red
            67 to CUSTOM_2,      // KEY_F9   green
            68 to CUSTOM_3,      // KEY_F10  blue
            87 to CUSTOM_4,      // KEY_F11  yellow
            103 to UP,
            104 to PAGE_UP,
            105 to LEFT,
            106 to RIGHT,
            108 to DOWN,
            109 to PAGE_DOWN,
            113 to MUTE,
            114 to VOLUME_DOWN,
            115 to VOLUME_UP,
            139 to MENU,
            158 to BACK,
            353 to CENTER,       // KEY_SELECT
        )

        fun fromScanCode(code: Int): HardwareKey = SCAN_MAP[code] ?: UNKNOWN
    }
}

/**
 * Bind hardware buttons to actions. Register short- and long-press handlers at
 * startup; MainActivity does the tap-vs-hold timing and calls back here.
 */
class HardwareKeyRouter {
    private val shortHandlers = mutableMapOf<HardwareKey, () -> Boolean>()
    private val longHandlers = mutableMapOf<HardwareKey, () -> Boolean>()
    private val repeatable = mutableSetOf<HardwareKey>()

    /**
     * @param repeats whether holding the key should fire the handler again and
     *   again. True for level-triggered things like volume and channel; FALSE
     *   for edge-triggered ones like starting a voice capture, where auto-repeat
     *   would toggle it on and off several times a second.
     */
    fun on(key: HardwareKey, repeats: Boolean = true, handler: () -> Boolean) {
        shortHandlers[key] = handler
        if (repeats) repeatable += key else repeatable -= key
    }

    fun onLong(key: HardwareKey, handler: () -> Boolean) {
        longHandlers[key] = handler
    }

    /** Drop all bindings — used before rebinding from a reloaded config. */
    fun clear() {
        shortHandlers.clear()
        longHandlers.clear()
        repeatable.clear()
    }

    /** True if holding this key should re-fire its handler. */
    fun repeatsWhileHeld(code: Int): Boolean =
        HardwareKey.fromKeyCode(code).let { it != HardwareKey.UNKNOWN && it in repeatable }

    /**
     * Handlers by logical key, for callers that already resolved one -- the
     * input bridge delivers kernel scancodes, not Android keycodes, and routing
     * them through the SAME handler table is what makes a screen-off press do
     * exactly what a screen-on press does.
     */
    fun shortHandlerFor(key: HardwareKey): (() -> Boolean)? =
        if (key == HardwareKey.UNKNOWN) null else shortHandlers[key]

    fun shortHandler(code: Int): (() -> Boolean)? {
        val key = HardwareKey.fromKeyCode(code)
        return if (key == HardwareKey.UNKNOWN) null else shortHandlers[key]
    }

    fun longHandler(code: Int): (() -> Boolean)? {
        val key = HardwareKey.fromKeyCode(code)
        return if (key == HardwareKey.UNKNOWN) null else longHandlers[key]
    }
}
