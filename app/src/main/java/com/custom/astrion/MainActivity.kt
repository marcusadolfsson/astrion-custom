package com.custom.astrion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sqrt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.config.ConfigServer
import com.custom.astrion.config.ConnectionConfig
import com.custom.astrion.config.DashboardConfig
import com.custom.astrion.config.DashboardLoader
import android.content.IntentFilter
import com.custom.astrion.config.EntityRefs
import com.custom.astrion.config.HotkeyConfig
import com.custom.astrion.config.JsonPlain
import com.custom.astrion.config.PageConfig
import com.custom.astrion.config.VoiceConfig
import com.custom.astrion.config.mergedWith
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.input.AdbStatus
import com.custom.astrion.input.HardwareKey
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.custom.astrion.bridge.BridgeClient
import com.custom.astrion.ha.BatteryReporter
import com.custom.astrion.input.HardwareKeyRouter
import com.custom.astrion.input.SleepAdminReceiver
import com.custom.astrion.ui.Dashboard
import com.custom.astrion.voice.MicProbe
import com.custom.astrion.voice.VoiceSession

/**
 * Single-activity host. Owns the HA client, wires physical buttons to the
 * config's hotkeys, and renders the swipeable dashboard.
 *
 * Connection details are NOT compiled in: they're resolved at runtime by
 * ConnectionConfig from app-private storage, populated by the ConfigServer setup
 * form on :8099. The BuildConfig fields remain only as a blank fallback, so
 * secrets.properties is needed for release SIGNING, not for credentials.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        // Temporary: on-screen toast + logcat for any button not yet mapped,
        // so unknown keycodes (e.g. power/menu on this unit) can be identified.
        const val DEBUG_KEYS = true
        const val KEY_TAG = "AstrionKeys"

        // Hold this long for a button's long-press action to fire.
        const val LONG_PRESS_MS = 1500L

        /**
         * Smoothing factor for the gravity estimate, 0..1 (higher = faster).
         *
         * Tilt sensing assumes gravity is the only acceleration present, which is
         * false during exactly the movement being detected, so the vector is
         * low-passed before its direction is used. 0.15 at ~15 Hz settles in a
         * few hundred milliseconds: quick enough to follow the remote being set
         * back down, slow enough that one jolt barely moves it.
         */
        const val GRAVITY_LPF = 0.15f

        /** How far resting |a| may sit from 1 g before it is worth warning about. */
        const val GRAVITY_EARTH_TOLERANCE = 2.0f

        // Motion-wake tuning moved to MotionWakeConfig (dashboard.yaml
        // `motion_wake:`, with per-remote blocks) -- the fixed values here could
        // not serve both a remote on a side table and two that live in a bed.
    }

    // Long-press timing state.
    private val keyHandler = Handler(Looper.getMainLooper())
    private var pendingLong: Runnable? = null
    private var activeLongKey = -1
    private var longFired = false

    // Motion-wake: a wake-up accelerometer wakes the screen when the remote is
    // lifted/moved. Only wakes the CPU on actual motion, so it's cheap at rest.
    private var sensorManager: SensorManager? = null
    private var motionSensor: Sensor? = null
    private var lastMagnitude = 0f
    private var lastWakeMs = 0L
    /** Above-threshold samples seen inside the current window. */
    private var motionHits = 0
    /** When the current counting window started. */
    private var motionWindowStart = 0L
    /**
     * Low-passed gravity estimate, and the reference direction to compare against.
     *
     * Tilt sensing assumes the only acceleration present is gravity, which is
     * exactly false during the movement being detected -- so the raw vector is
     * smoothed into a gravity estimate first, the standard treatment in the
     * accelerometer-inclinometer literature (ST DT0140, ADI AN-1057). The
     * reference is the direction the remote has been resting at; a pick-up is
     * then simply the angle between the two opening up.
     */
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var refX = 0f
    private var refY = 0f
    private var refZ = 0f
    /** One-shot guard for the resting-gravity sanity warning. */
    private var biasWarned = false

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val cfg = motionCfg()
            if (!cfg.enabled) return
            val (x, y, z) = event.values
            val mag = sqrt(x * x + y * y + z * z)
            if (mag < 0.001f) return

            // Seed on the first sample rather than treating it as movement.
            if (gx == 0f && gy == 0f && gz == 0f) {
                gx = x; gy = y; gz = z
                refX = x; refY = y; refZ = z
                lastMagnitude = mag
                return
            }

            // Smooth towards the current reading. At ~15 Hz this settles in a
            // couple of hundred ms -- fast enough to follow a real re-placement,
            // slow enough that a single jolt does not move the estimate far.
            gx += (x - gx) * GRAVITY_LPF
            gy += (y - gy) * GRAVITY_LPF
            gz += (z - gz) * GRAVITY_LPF

            val gMag = sqrt(gx * gx + gy * gy + gz * gz)
            val refMag = sqrt(refX * refX + refY * refY + refZ * refZ)
            if (gMag < 0.001f || refMag < 0.001f) return

            // Tilt from the DIFFERENCE between the current gravity direction and
            // the resting one, not from the angle between them.
            //
            // This is the form that survives a biased sensor. One of these
            // remotes reads ~+1.1 g on its z axis at rest (raw 1787 counts where
            // its twin reads 657, x and y ordinary, and both calibration stores
            // empty) -- a constant per-axis bias, not a scale error. Normalising
            // does NOT cancel a bias: it drags the computed direction toward the
            // biased axis and compresses every measured angle, so the same
            // tilt_degrees would quietly need a much bigger real movement on that
            // unit than on a healthy one.
            //
            // Subtracting two measurements DOES cancel it -- the bias is in both
            // terms. Two vectors of length g separated by angle t are 2*g*sin(t/2)
            // apart, so the angle comes back exactly, using the physical constant
            // rather than this sensor's own idea of how long gravity is.
            val dx = gx - refX
            val dy = gy - refY
            val dz = gz - refZ
            val chord = sqrt(dx * dx + dy * dy + dz * dz)
            val tiltDeg = Math.toDegrees(
                2.0 * kotlin.math.asin((chord / (2f * SensorManager.GRAVITY_EARTH)).coerceIn(0f, 1f).toDouble())
            ).toFloat()

            // Sudden movement, also as a difference and also against the physical
            // constant, for the same reason.
            val jerk = abs(mag - lastMagnitude) / SensorManager.GRAVITY_EARTH

            // Say so, once, if this sensor is not reporting 1 g at rest. The bias
            // above was invisible for weeks and cost two rounds of tuning that
            // could never have converged; a device that lies about gravity should
            // not do it silently.
            if (!biasWarned && motionHits == 0 && abs(gMag - SensorManager.GRAVITY_EARTH) > GRAVITY_EARTH_TOLERANCE) {
                biasWarned = true
                Log.w(KEY_TAG, "accelerometer rests at %.2f m/s^2, expected ~9.81 (device=%s) -- biased sensor, tilt uses differences so this is compensated"
                    .format(gMag, deviceName))
            }
            lastMagnitude = mag

            val now = SystemClock.elapsedRealtime()
            if (now - motionWindowStart > cfg.windowMs) {
                motionWindowStart = now
                motionHits = 0
            }
            if (tiltDeg > cfg.tiltDegrees || jerk > cfg.jerkRatio) {
                motionHits++
                if (motionHits >= cfg.hits) {
                    motionHits = 0
                    motionWindowStart = now
                    Log.i(KEY_TAG, "motion: tilt=%.1f° jerk=%.2f (device=%s)"
                        .format(tiltDeg, jerk, deviceName))
                    wakeScreen()
                }
            } else if (tiltDeg < cfg.tiltDegrees / 2f) {
                // Settled at a new attitude: adopt it as the reference, so being
                // set down at a different angle does not leave the remote one
                // degree from firing for the rest of the night.
                refX = gx; refY = gy; refZ = gz
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * This remote's effective motion-wake settings, read from the LIVE config so
     * a Sync re-tunes it without a restart. Only `enabled` needs a restart, since
     * that decides whether the sensor is registered at all.
     */
    private fun motionCfg() = dashboard.config.motionWake.forDevice(deviceName)

    private lateinit var client: HaClient
    /** When the display last turned on, for telling a waking press from a normal one. */
    @Volatile private var screenOnAt = 0L

    /** When the display last turned off, for the motion-wake settle window. */
    @Volatile private var screenOffAt = 0L

    /** True while the remote is on power. Drives both dock display and the
     *  motion-wake charging gate; kept as state so neither has to poll. */
    @Volatile private var charging = false

    private fun dockCfg() = dashboard.config.dockDisplay.forDevice(deviceName)

    private val screenWatcher = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOnAt = SystemClock.elapsedRealtime()
                    // Re-apply here as well as on resume. An activity that was
                    // already resumed when the display slept never gets a second
                    // onResume, so a remote whose first resume happened with the
                    // screen off would sit in the wrong mode indefinitely --
                    // observed on the living-room unit. The screen coming on is
                    // also interaction, so it brightens.
                    noteDockInteraction()
                }
                Intent.ACTION_SCREEN_OFF -> screenOffAt = SystemClock.elapsedRealtime()
                Intent.ACTION_POWER_CONNECTED -> { charging = true; applyDockDisplay() }
                Intent.ACTION_POWER_DISCONNECTED -> { charging = false; applyDockDisplay() }
            }
        }
    }

    /**
     * Hold the screen on, dim, while docked -- and let go when undocked.
     *
     * FLAG_KEEP_SCREEN_ON rather than a wake lock: it is scoped to this window,
     * so it cannot outlive the activity and leave the display stuck on, which is
     * the failure mode that matters on a device whose only recovery is a cable.
     *
     * screenBrightness is a WINDOW attribute, so it overrides the system level
     * only while this window is in front and reverts on its own afterwards --
     * nothing to restore, and nothing left behind if the app dies.
     */
    private fun applyDockDisplay(bright: Boolean = false) {
        val cfg = dockCfg()
        val on = cfg.enabled && charging
        runOnUiThread {
            if (on) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            window.attributes = window.attributes.apply {
                screenBrightness = when {
                    !on -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    bright -> cfg.brightLevel.coerceIn(0.01f, 1f)
                    else -> cfg.dimLevel.coerceIn(0.01f, 1f)
                }
            }
        }
    }

    /**
     * Any interaction while docked raises the backlight for a while.
     *
     * Called from the touch and key paths rather than from a global listener,
     * because "the user did something" is exactly what those already know and a
     * separate watcher would have to re-derive it.
     */
    private fun noteDockInteraction() {
        // Re-read power state here rather than trusting the cached flag: the
        // connect broadcast can be missed while the app is stopped, and this is
        // the cheap moment to notice.
        charging = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: charging
        if (!charging || !dockCfg().enabled) {
            applyDockDisplay()
            return
        }
        applyDockDisplay(bright = true)
        keyHandler.removeCallbacks(dockDimBack)
        keyHandler.postDelayed(dockDimBack, dockCfg().brightSeconds * 1000L)
    }

    /**
     * Fade back to the dim level.
     *
     * Deliberately does NOT re-check a deadline. It used to compare against
     * `dockBrightUntil`, computed a few instructions before the timer was posted
     * for the same duration -- so the two could land in either order, and when
     * the check lost the race nothing rescheduled and the backlight stayed at
     * full all night. removeCallbacks already guarantees this only runs when no
     * newer interaction has replaced it, which is the whole condition.
     */
    private val dockDimBack = Runnable {
        if (charging && dockCfg().enabled) applyDockDisplay(bright = false)
    }

    private val keyRouter = HardwareKeyRouter()

    /**
     * How long after the display lights that a keypress still counts as having
     * arrived in the dark. Android's wake and our evdev read race, so the press
     * that woke the screen frequently reaches us a few ms AFTER isInteractive
     * has already flipped true.
     */
    private val WAKE_GRACE_MS = 900L

    /**
     * Keys allowed to act without waking the screen.
     *
     * The test is not importance, it is whether the key changes anything ON THIS
     * DISPLAY. Volume, mute and channel act on the Sonos and the source; the
     * navigation keys drive the Apple TV or Kaleidescape, whose UI is on the wall
     * -- you are looking at the TV, not at a 3" screen in your hand, so lighting
     * it serves nothing and costs battery every press.
     *
     * Excluded by default: LIGHT / CURTAIN / SCENE / AC carry `page:` +
     * `scroll_to:` and move the remote's own view, so a dark screen would hide
     * the thing they just did. VOICE shows the listening UI. CUSTOM_1..4 and
     * POWER switch AV activity, which navigates the remote's page by automation.
     * Those all want the screen up.
     *
     * These are DEFAULTS, not the final answer: a layout may set `quiet` on any
     * hotkey to override its entry here. That exists because the same keycode
     * means different things on different hardware -- 134 is LIGHT (a page jump,
     * screen up) on the HA100A and SCAN (transport, screen stays dark) on the
     * HA100B, which print different legends on identical buttons. Encoding that
     * as a constant would have forced one behaviour onto both remotes.
     */
    private val DEFAULT_QUIET_KEYS = setOf(
        HardwareKey.VOLUME_UP, HardwareKey.VOLUME_DOWN, HardwareKey.MUTE,
        HardwareKey.PAGE_UP, HardwareKey.PAGE_DOWN,
        HardwareKey.UP, HardwareKey.DOWN, HardwareKey.LEFT, HardwareKey.RIGHT,
        HardwareKey.CENTER, HardwareKey.BACK, HardwareKey.HOME, HardwareKey.MENU,
    )

    /**
     * Effective quiet set: the defaults above, plus every hotkey that asked to be
     * quiet, minus every hotkey that asked not to be.
     *
     * Recomputed from the live config rather than captured once, because the
     * dashboard is re-read in onResume() and a Sync must be able to change this
     * without a reinstall -- the whole point of moving it out of a constant.
     */
    private fun quietKeys(): Set<HardwareKey> {
        // Resolved per key for the ACTIVE page, so the quiet flag always travels
        // with the binding that will actually run -- which is what makes this
        // correct when the same keycode is a page jump on one page and transport
        // on another.
        val stated = HardwareKey.ALL.mapNotNull { key ->
            val q = (shortFor(key)?.quiet ?: longFor(key)?.quiet) ?: return@mapNotNull null
            key to q
        }
        return DEFAULT_QUIET_KEYS + stated.filter { it.second }.map { it.first } -
            stated.filterNot { it.second }.map { it.first }.toSet()
    }

    /**
     * The page whose bindings the physical keys currently obey, BY NAME.
     *
     * By name and not index because a Sync can reorder pages: an index would
     * silently repoint the keys at whatever slid into that slot, and with the
     * screen off that window is unbounded.
     *
     * null = the pager has not reported yet (cold start), the only moment
     * startPage is consulted. Once settled it stays put even with the display
     * off: the page is a MODE, not a view, and treating a display timeout as
     * "back to the default room" would silently move the volume key mid-film.
     *
     * A plain field rather than mutableStateOf -- nothing composable reads it,
     * so making it snapshot state would feed a write from the pager's collector
     * back into the tree it came from for no benefit.
     */
    @Volatile
    private var currentPageName: String? = null

    /**
     * The last page value seen on, or written to, `page_entity`.
     *
     * This is the echo-loop guard. The entity is mirrored BOTH ways -- HA writing
     * it navigates us, and a swipe writes it back -- so without a memory of what
     * we last exchanged, our own write comes back as an incoming change and
     * bounces. Comparing against the live entity state is not enough: HA's echo
     * arrives before the state we compare to has settled.
     */
    @Volatile
    private var lastPageSync: String? = null

    /**
     * The pager settled on a page. Adopt it for hotkey scoping, and report it to
     * HA if this remote has a page entity.
     */
    private fun onPagerSettled(index: Int) {
        val name = dashboard.config.pages.getOrNull(index)?.name ?: return
        currentPageName = name
        val entity = dashboard.config.pageEntity ?: return
        if (name == lastPageSync) return
        lastPageSync = name
        client.callService(
            ServiceCall.of("input_select", "select_option", entity, "option" to name)
        )
    }

    /**
     * Follow HA when it writes the page entity.
     *
     * Called on every entity publish, which is cheap: it is a map lookup and a
     * string compare, and it no-ops unless the value actually differs from what
     * we last exchanged.
     */
    private fun followPageEntity(entities: com.custom.astrion.ha.EntityMap) {
        val entity = dashboard.config.pageEntity ?: return
        val want = entities[entity]?.state ?: return
        if (want.isBlank() || want == lastPageSync) return
        val idx = dashboard.config.pages.indexOfFirst { it.name.equals(want, ignoreCase = true) }
        if (idx < 0) return
        lastPageSync = want
        currentPageName = dashboard.config.pages[idx].name
        navTarget = idx
    }

    /**
     * Screen-off keys. Purely additive: when the bridge is not running (its
     * normal state, since it must be started over adb and dies on reboot) this
     * simply never fires and Android's own dispatch is unchanged. When it IS
     * running, the press that wakes the screen also runs its action instead of
     * being swallowed by PhoneWindowManager.
     *
     * It routes through the SAME handler table as dispatchKeyEvent, so a
     * screen-off press and a screen-on press are the same thing by construction
     * rather than by two tables kept in step by hand.
     */
    private var bridgeConnected by mutableStateOf(false)
    private var stockAllowed by mutableStateOf(false)
    private var stockStops by mutableStateOf(0)
    private var stockStopAt by mutableStateOf(0L)
    /**
     * The remote's battery, published to HA so it can be charted. Nothing else
     * can see it: this is not a HA-managed device, so without this the only
     * readings are spot values taken over adb while someone is looking.
     */
    /** Slug naming this remote's helper entities; blank = publish nothing. */
    private val deviceName: String by lazy { ConnectionConfig.load(this).device }

    private val battery by lazy {
        BatteryReporter(
            this, client, lifecycleScope,
            entityId = "input_number.astrion_${deviceName}_battery",
        )
    }

    private val bridge by lazy {
        BridgeClient(
            scope = lifecycleScope,
            quietKeys = ::quietKeys,
            isDisplayOff = {
                // NOT just isInteractive. Android wakes the display on its own
                // copy of this very keypress, and that can complete before we
                // sample -- which made the bridge skip the exact press it exists
                // for. So also treat "the screen came on within the last moment"
                // as dark: that transition IS this press waking it.
                val awake = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
                val justWoke = (SystemClock.elapsedRealtime() - screenOnAt) < WAKE_GRACE_MS
                (!awake) || justWoke
            },
            onQuietHandled = { sleepScreen() },
        ) { key ->
            keyRouter.shortHandlerFor(key)?.invoke()
        }
    }

    /**
     * Put the display back down after a keypress that should not have lit it.
     *
     * Requires an ACTIVE device admin holding force-lock -- granted once with
     *   adb shell dpm set-active-admin com.custom.astrion/.input.SleepAdminReceiver
     * and persistent across reboots, unlike the bridge. Without it lockNow()
     * throws SecurityException, which is caught: the press still does its job,
     * the screen just stays lit as it does today.
     */
    /**
     * Whether the OEM launcher is currently allowed to run.
     *
     * A flag FILE rather than a message to the bridge, because the bridge is the
     * thing that may be missing or restarting: a file is read fresh on every
     * watchdog tick and survives the socket dropping, where an in-band command
     * would need re-sending on every reconnect.
     */
    private val allowStockFile by lazy {
        java.io.File(createDeviceProtectedStorageContext().dataDir, "allow-stock")
    }

    private fun readStockAllowed(): Boolean = allowStockFile.exists()

    /**
     * The bridge's force-stop tally: "<count> <lastEpochMs>".
     *
     * Read from the file rather than counted here, because the app is not the
     * thing doing the stopping and is not even running for some of them -- the
     * bridge outlives an app restart.
     */
    private val stopsFile by lazy {
        java.io.File(createDeviceProtectedStorageContext().dataDir, "stock-stops")
    }

    private fun readStockStops(): Pair<Int, Long> = runCatching {
        val parts = stopsFile.readText().trim().split(' ')
        parts[0].toInt() to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }.getOrDefault(0 to 0L)

    private fun writeStockAllowed(allow: Boolean) {
        runCatching {
            if (allow) {
                allowStockFile.writeText("1")
                allowStockFile.setReadable(true, false)
            } else {
                allowStockFile.delete()
            }
        }
    }

    /**
     * Publish the force-stop tally to Home Assistant, so how often the OEM
     * launcher claws its way back can be charted beside the battery it drains.
     *
     * total_increasing, not measurement: this is a counter, and that class is
     * the one that survives it going back to zero (app data cleared, file lost)
     * without drawing a cliff through the statistics.
     */
    private fun postStockStops(count: Int, atMs: Long) {
        // A service call, not POST /api/states: publishing a state needs an ADMIN
        // token, and each remote now authenticates as its own scoped user. The
        // helper is per remote, so three of them no longer overwrite one counter.
        //
        // The `last_stop` timestamp the REST version carried as an attribute is
        // gone -- an input_number holds a value and nothing else. The count and
        // its own last_changed answer the same question ("how often, and when
        // was the most recent") without a second helper.
        if (deviceName.isBlank()) return
        client.callService(
            ServiceCall.of(
                "input_number", "set_value",
                "input_number.astrion_${deviceName}_stock_stops",
                "value" to count,
            )
        )
    }

    private fun sleepScreen() {
        runCatching {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, SleepAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) dpm.lockNow()
            else Log.d(KEY_TAG, "screen-off keys: device admin not active, leaving display on")
        }
    }

    /** Current layout: starts as the compiled-in defaults, replaced from disk. */
    private var dashboard by mutableStateOf(DashboardLoader.Result(DashboardConfig.default, null))

    /** Page index requested by a hardware button; consumed by the Dashboard. */
    private var navTarget by mutableStateOf<Int?>(null)

    /**
     * Overlay visibility, owned here rather than inside the Dashboard composable
     * so dispatchKeyEvent can see it -- see the BACK interception below.
     */
    private var settingsOpen by mutableStateOf(false)
    private var statusOpen by mutableStateOf(false)

    /** Network-adb state, refreshed when the panel opens (see AdbStatus). */
    private var adbStatus by mutableStateOf("—")

    private fun refreshAdbStatus() {
        // Off the main thread: it execs getprop and opens a socket.
        lifecycleScope.launch(Dispatchers.IO) {
            val s = AdbStatus.describe()
            runOnUiThread { adbStatus = s }
        }
    }

    /** Close the topmost overlay. True if there was one. */
    private fun dismissOverlay(): Boolean = when {
        statusOpen -> { statusOpen = false; true }
        settingsOpen -> { settingsOpen = false; true }
        else -> false
    }

    /** Section (separator name) to scroll to; consumed by the Dashboard. */
    private var scrollTarget by mutableStateOf<String?>(null)

    /** Section whose sole selector should auto-open; consumed by the Dashboard. */
    private var openTarget by mutableStateOf<String?>(null)

    /** Resolved HA base URL (shown in the swipe-up info panel). */
    private var haUrl by mutableStateOf("")

    /** Setup web server; non-null URL means it's listening (shown in the panel). */
    private var setupUrl by mutableStateOf<String?>(null)
    private var configServer: ConfigServer? = null
    private val micProbe = MicProbe()

    /** VOICE key: capture + stream to HA, which decides Siri vs Assist. */
    private lateinit var voice: VoiceSession

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { reloadDashboard() }

    /**
     * Write a launcher script into our own data dir and return the (short)
     * command that runs it.
     *
     * The alternative is making the user paste a 120-character line containing
     * the apk path -- which changes on every reinstall, so it cannot be written
     * down anywhere. Baking it into a script HERE, rewritten on every launch,
     * means the command a human types stays short and constant while the part
     * that moves is regenerated behind it.
     *
     * Device-protected storage on purpose: it is readable before first unlock,
     * so the script is available at the point in boot where you would want to
     * start the bridge. World-readable because the shell that runs it is not us.
     */
    private fun bridgeCommand(): String {
        val de = createDeviceProtectedStorageContext()
        val script = java.io.File(de.dataDir, "start-bridge.sh")
        runCatching {
            script.writeText(
                "#!/system/bin/sh\n" +
                    "# Starts the Astrion input bridge (screen-off keys).\n" +
                    "# Regenerated by the app on every launch -- do not edit.\n" +
                    "CLASSPATH=${applicationInfo.sourceDir} \\\n" +
                    "  exec app_process /system/bin com.custom.astrion.bridge.InputBridge \\\n" +
                    "  --stop=com.aiks.HaRemote \"$@\"\n"
            )
            script.setReadable(true, false)
            script.setExecutable(true, false)
        }
        return "adb shell sh ${script.absolutePath}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Kiosk-style fullscreen: reclaim the status bar's height for the
        // dashboard (physical buttons make the nav bar redundant too).
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        // /sdcard/astrion/dashboard.json lives on shared storage, and the mic is
        // needed for voice — all classic runtime permissions on Android 8.1.
        // The stock app gets RECORD_AUDIO as SYSTEM_FIXED because it ships as a
        // system app; we're a normal user app, so we have to ask.
        val needed = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) storagePermission.launch(needed.toTypedArray())

        // adb-only mic diagnostic; see MicProbe. Registered at runtime rather
        // than in the manifest so it exists only while the app is alive.
        registerReceiver(micProbe, IntentFilter(MicProbe.ACTION))

        setupMotionWake()

        // Credentials come from app-private prefs (seeded once from
        // /sdcard/astrion/connection.json), NOT from the APK — see ConnectionConfig.
        val conn = ConnectionConfig.load(this)
        haUrl = conn.url
        if (!conn.isComplete) {
            Toast.makeText(
                this,
                "No HA connection configured — push /sdcard/astrion/connection.json",
                Toast.LENGTH_LONG,
            ).show()
        }
        client = HaClient(baseUrl = conn.url, token = conn.token)
        voice = VoiceSession(baseUrl = conn.url, token = conn.token)
        rebuildBindings()
        // Load the cached layout first so the initial subscribe is already
        // filtered — avoids a 0.7 MB whole-instance seed on every launch.
        reloadDashboard()
        if (conn.isComplete) {
            client.connect()
            // One pull from HA on cold launch; after that it's on-demand (panel / VOICE).
            syncFromHa()
        } else {
            // No credentials yet -> open the setup page immediately so a fresh
            // install can be provisioned from a browser, with no adb.
            startSetupServer()
        }

        registerReceiver(
            screenWatcher,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
        bridge.start()
        battery.start()
        // Poll the client's flag for display only. A StateFlow would be tidier,
        // but the bridge is deliberately not load-bearing and this costs one
        // comparison a second.
        lifecycleScope.launch {
            while (true) {
                if (bridgeConnected != bridge.connected) bridgeConnected = bridge.connected
                // Re-read the flag file rather than trusting our own last write:
                // the bridge is a separate process and the file is the contract
                // between them, so the file is the truth.
                if (stockAllowed != readStockAllowed()) stockAllowed = readStockAllowed()
                val (count, at) = readStockStops()
                if (count != stockStops) {
                    stockStops = count
                    stockStopAt = at
                    // Only on CHANGE. The count is usually static, and this is a
                    // once-a-second loop.
                    postStockStops(count, at)
                }
                delay(1000)
            }
        }

        setContent {
            val entities = client.entities.collectAsState()
            val connection = client.connection.collectAsState()
            val voiceState = voice.state.collectAsState()
            // Follow HA-driven page changes. Keyed on the state object rather
            // than its value so the collector is started once, not restarted on
            // every publish tick.
            LaunchedEffect(entities) {
                snapshotFlow { entities.value }.collect { followPageEntity(it) }
            }
            AstrionTheme {
                Dashboard(
                    client = client,
                    entitiesState = entities,
                    connectionState = connection,
                    config = dashboard.config,
                    configNotice = dashboard.notice,
                    navTarget = navTarget,
                    onNavHandled = { navTarget = null },
                    scrollTarget = scrollTarget,
                    onScrollHandled = { scrollTarget = null },
                    openTarget = openTarget,
                    onOpenHandled = { openTarget = null },
                    onSync = { syncFromHa(manual = true) },
                    haUrl = haUrl,
                    setupUrl = setupUrl,
                    onSetup = { if (setupUrl == null) startSetupServer() else stopSetupServer() },
                    voiceState = voiceState.value,
                    onVoiceDismiss = { voice.dismiss() },
                    onPageChange = { i -> onPagerSettled(i) },
                    deviceName = deviceName,
                    settingsOpen = settingsOpen,
                    onSettingsOpen = { settingsOpen = it; if (it) refreshAdbStatus() },
                    adbStatus = adbStatus,
                    statusOpen = statusOpen,
                    onStatusOpen = { statusOpen = it },
                    bridgeConnected = bridgeConnected,
                    bridgeCommand = bridgeCommand(),
                    stockAllowed = stockAllowed,
                    onStockAllowedChange = { allow ->
                        writeStockAllowed(allow)
                        stockAllowed = allow
                    },
                    stockStops = stockStops,
                    stockStopAt = stockStopAt,
                )
            }
        }
    }

    /**
     * Start the pairing-style setup server (http://<remote-ip>:8099). Mirrors the
     * stock app's :8080 pairing endpoint. Stops itself once credentials are saved
     * so it isn't a permanently open write endpoint on the LAN.
     */
    private fun startSetupServer() {
        if (configServer != null) return
        val srv = ConfigServer { conn ->
            ConnectionConfig.save(this, conn)
            runOnUiThread {
                haUrl = conn.url
                Toast.makeText(this, "Connection saved — reconnecting", Toast.LENGTH_SHORT).show()
                // Reconnect with the new credentials, then close setup.
                client.disconnect()
                client = HaClient(baseUrl = conn.url, token = conn.token)
                client.connect()
                syncFromHa()
                stopSetupServer()
            }
        }
        configServer = srv
        srv.start()
        setupUrl = srv.url
    }

    private fun stopSetupServer() {
        configServer?.stop()
        configServer = null
        setupUrl = null
    }

    /** Any touch counts as interaction for the dock's brightness. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        noteDockInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        // Read the CURRENT power state rather than waiting for the next
        // connect/disconnect broadcast -- otherwise a remote that was already on
        // charge when the app started would sit in the wrong mode until someone
        // unplugged it.
        charging = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false
        // Re-read the local cache on foreground (instant, no network). Pulling
        // from HA is now on-demand — swipe up for the info panel's Sync button,
        // or the VOICE hotkey — not on every resume.
        reloadDashboard()
        // AFTER the reload, not before: applyDockDisplay reads the live config,
        // and running it first meant the first resume after an update decided
        // using the PREVIOUS layout -- which, for a setting that had just been
        // added, meant deciding it was absent and then never revisiting it,
        // because a screen that goes to sleep produces no second resume.
        applyDockDisplay()
    }

    /** Load config from the local cache and apply it. Synchronous — tiny file. */
    private fun reloadDashboard() {
        applyDashboard(DashboardLoader.load())
        applyDockDisplay()
    }

    /**
     * Adopt a layout: bind its hotkeys and narrow the live entity subscription to
     * just the entities it references (see EntityRefs) — otherwise the client
     * would track all ~1,650 entities on this HA to render about 30.
     */
    private fun applyDashboard(result: DashboardLoader.Result) {
        dashboard = result
        rebuildBindings()
        client.setSubscribedEntities(EntityRefs.collect(result.config))
    }

    /**
     * Pull the master dashboard.json from HA (/local/astrion/dashboard.json),
     * write it to the local cache and reload if it changed. `manual` toasts the
     * outcome (for a hotkey `action: "sync"`); the on-resume auto-sync is silent.
     */
    private fun syncFromHa(manual: Boolean = false) {
        if (haUrl.isBlank()) {
            if (manual) Toast.makeText(this, "No HA connection configured", Toast.LENGTH_SHORT).show()
            return
        }
        client.fetchText(DashboardLoader.REMOTE_PATH) { text ->
            val result = text?.let { DashboardLoader.loadFromText(it) }
            runOnUiThread {
                when {
                    text == null ->
                        if (manual) Toast.makeText(this, "Sync failed — HA unreachable", Toast.LENGTH_SHORT).show()
                    result != null -> {
                        applyDashboard(result)
                        if (manual) Toast.makeText(this, "Dashboard updated", Toast.LENGTH_SHORT).show()
                    }
                    else -> if (manual) Toast.makeText(this, "Already up to date", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---- hotkeys ------------------------------------------------------------

    /** A key -> binding index, built once per config load. */
    private class Bindings(
        val short: Map<HardwareKey, HotkeyConfig>,
        val long: Map<HardwareKey, HotkeyConfig>,
    )

    private var globalBindings = Bindings(emptyMap(), emptyMap())
    /** Keyed by lowercased page name -- see currentPageName for why not by index. */
    private var pageBindings: Map<String, Bindings> = emptyMap()

    private fun index(list: List<HotkeyConfig>): Map<HardwareKey, HotkeyConfig> {
        val out = LinkedHashMap<HardwareKey, HotkeyConfig>()
        list.forEach { hk ->
            val key = runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull()
                ?: return@forEach
            // First wins, matching how page lookup uses indexOfFirst.
            if (!out.containsKey(key)) out[key] = hk
        }
        return out
    }

    /** Rebuild both binding tables from the live config, then (re)bind the router. */
    private fun rebuildBindings() {
        val cfg = dashboard.config
        globalBindings = Bindings(index(cfg.hotkeys), index(cfg.longHotkeys))
        pageBindings = cfg.pages.associate { p ->
            p.name.lowercase() to Bindings(index(p.hotkeys), index(p.longHotkeys))
        }
        bindHotkeys()
    }

    private fun activePage(): PageConfig? {
        val cfg = dashboard.config
        val name = currentPageName ?: cfg.pages.getOrNull(cfg.startPage)?.name
        return cfg.pages.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun activeBindings(): Bindings? {
        val cfg = dashboard.config
        val name = currentPageName ?: cfg.pages.getOrNull(cfg.startPage)?.name
        return name?.let { pageBindings[it.lowercase()] }
    }

    // Per-KEY fallback, page then global. Not all-or-nothing: a room page
    // overrides the handful of keys it cares about and inherits POWER, VOICE,
    // MENU and CUSTOM_1..4 rather than having to restate them -- which is the
    // same trap that once shipped two dead buttons in a device override.
    private fun shortFor(k: HardwareKey): HotkeyConfig? =
        activeBindings()?.short?.get(k) ?: globalBindings.short[k]

    private fun longFor(k: HardwareKey): HotkeyConfig? =
        activeBindings()?.long?.get(k) ?: globalBindings.long[k]

    /** The VOICE config for the page being shown, over the global one. */
    private fun effectiveVoice(): VoiceConfig? =
        dashboard.config.voice.mergedWith(activePage()?.voice)

    /**
     * Bind the UNION of every key mentioned anywhere, once, with handlers that
     * resolve at PRESS time.
     *
     * Deliberately not re-bound on page change. dispatchKeyEvent re-looks-up its
     * handler on every event including ACTION_UP, and a page-navigating key fires
     * its action on the UP edge -- so a keyRouter.clear() between a key's DOWN and
     * UP would run the NEW page's binding for that key, breaking precisely the
     * keys this feature exists for. Lazy resolution also means the screen-on path
     * and the bridge's screen-off path consult the same resolver at the same
     * instant, which is a stronger guarantee than keeping two tables in step.
     */
    private fun bindHotkeys() {
        keyRouter.clear()
        val shortKeys = globalBindings.short.keys + pageBindings.values.flatMap { it.short.keys }
        val longKeys = globalBindings.long.keys + pageBindings.values.flatMap { it.long.keys }
        shortKeys.forEach { key ->
            keyRouter.on(
                key,
                // Only a plain service call repeats while held. Built-in actions
                // (voice, sync) and navigation are edge-triggered: auto-repeat
                // would fire them many times a second.
                repeats = {
                    shortFor(key)?.let { it.action == null && it.page == null && it.scrollTo == null } == true
                },
            ) { shortFor(key)?.let { runHotkey(it) } ?: false }
        }
        longKeys.forEach { key ->
            keyRouter.onLong(key) { longFor(key)?.let { runHotkey(it) } ?: false }
        }
    }

    /**
     * Execute one hotkey. A binding may combine actions — e.g. `page` +
     * `scroll_to` jumps to a page then scrolls to a section, and any of them may
     * be paired with a `service` call.
     */
    private fun runHotkey(hk: HotkeyConfig): Boolean {
        var handled = false
        if (hk.action?.equals("sync", ignoreCase = true) == true) {
            syncFromHa(manual = true)
            handled = true
        }
        if (hk.action?.equals("voice", ignoreCase = true) == true) {
            // Press to talk; it ends itself on silence. A second press cancels.
            voice.toggle(effectiveVoice())
            handled = true
        }
        hk.page?.let { pageName ->
            val idx = dashboard.config.pages.indexOfFirst { it.name.equals(pageName, ignoreCase = true) }
            if (idx >= 0) {
                navTarget = idx
                // Adopt the new page IMMEDIATELY rather than waiting for the
                // pager to confirm. With the screen off the Recomposer is
                // paused, so the pager does not move until wake -- a second dark
                // press would otherwise still resolve against the old page.
                currentPageName = dashboard.config.pages[idx].name
                handled = true
            }
        }
        hk.scrollTo?.let {
            scrollTarget = it
            openTarget = it
            handled = true
        }
        hk.service?.let { service ->
            val domain = service.substringBefore('.')
            val svc = service.substringAfter('.')
            val data = hk.data.mapValues { JsonPlain.toJson(it.value) }
            client.callService(ServiceCall(domain, svc, hk.entityId, data))
            handled = true
        }
        return handled
    }

    /**
     * Physical buttons arrive as standard KeyEvents. We intercept here to run
     * tap-vs-hold logic:
     *  - keys with a long-press binding fire their SHORT action on release (if
     *    released before LONG_PRESS_MS) or their LONG action once held past it;
     *  - keys without a long binding fire immediately on each down (so volume
     *    etc. still repeat while held).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A button press is interaction too -- the dock brightens for it
        // exactly as it does for a touch.
        if (event.action == KeyEvent.ACTION_DOWN) noteDockInteraction()
        val code = event.keyCode

        // BACK closes an open sheet, and must be checked BEFORE the key router.
        // BACK is bound to the AV back action, so the lookup below would consume
        // it and Android would never call onBackPressed() -- pressing BACK with
        // a sheet open would navigate the Apple TV instead of closing the sheet.
        if (code == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            (statusOpen || settingsOpen)
        ) {
            dismissOverlay()
            return true
        }
        val shortH = keyRouter.shortHandler(code)
        val longH = keyRouter.longHandler(code)

        // Unmapped: log/toast for diagnosis, then let the OS handle it.
        if (shortH == null && longH == null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.i(KEY_TAG, "keyCode=$code (${KeyEvent.keyCodeToString(code)})")
                if (DEBUG_KEYS) Toast.makeText(this, "Unmapped key: $code", Toast.LENGTH_SHORT).show()
            }
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (longH != null) {
                    // Long-capable: start the hold timer on first press, ignore repeats.
                    if (event.repeatCount == 0) {
                        cancelPendingLong()
                        longFired = false
                        activeLongKey = code
                        val r = Runnable {
                            longFired = true
                            longH.invoke()
                        }
                        pendingLong = r
                        keyHandler.postDelayed(r, LONG_PRESS_MS)
                    }
                } else {
                    // Short-only. Repeats fire again for level-triggered keys
                    // (volume, channel); edge-triggered ones fire once per press.
                    if (event.repeatCount == 0 || keyRouter.repeatsWhileHeld(code)) {
                        shortH?.invoke()
                    }
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (longH != null && code == activeLongKey) {
                    cancelPendingLong()
                    activeLongKey = -1
                    // Released before the hold threshold → it was a tap.
                    if (!longFired) shortH?.invoke()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun cancelPendingLong() {
        pendingLong?.let { keyHandler.removeCallbacks(it) }
        pendingLong = null
    }

    // ---- motion wake --------------------------------------------------------

    /**
     * Opt-out flag for motion wake, same file-flag pattern as `allow-stock`.
     *
     * Motion wake assumes the remote rests on something that does not move —
     * a table, an arm of a sofa. A remote that lives IN A BED breaks that
     * assumption completely: the surface moves whenever its owner does, easily
     * past the 0.9 m/s² threshold, so the display re-lit within seconds of every
     * timeout, all night. Raising the threshold does not rescue it, because a
     * person rolling over produces more acceleration than picking the remote up.
     *
     * So it is switchable per unit rather than tuned. Create the file to disable:
     *   adb shell touch /data/user_de/0/com.custom.astrion/no-motion-wake
     * Delete it to restore. Read once at startup — this changes what sensors are
     * registered, so it takes an app restart, which the adb command implies anyway.
     */
    private val noMotionWakeFile by lazy {
        java.io.File(createDeviceProtectedStorageContext().dataDir, "no-motion-wake")
    }

    private fun setupMotionWake() {
        if (noMotionWakeFile.exists()) {
            Log.i(KEY_TAG, "motion wake disabled by no-motion-wake flag")
            return
        }
        if (!motionCfg().enabled) {
            Log.i(KEY_TAG, "motion wake disabled by config for device=$deviceName")
            return
        }
        // Deliberately NOT logging the resolved values here: setup runs before
        // the dashboard has loaded, so it would print the compiled defaults and
        // read as "the per-device config did not apply". The real values are
        // logged at the first wake, by which time the config is live.
        Log.i(KEY_TAG, "motion wake armed (device=$deviceName)")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        // Prefer a wake-up accelerometer so events still arrive with the screen
        // off; fall back to the normal one (which only helps while awake).
        motionSensor = sensorManager?.getSensorList(Sensor.TYPE_ACCELEROMETER)
            ?.firstOrNull { it.isWakeUpSensor }
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        motionSensor?.let {
            // UI rate (~60ms), not NORMAL (~200ms). A 1-second window holds ~16
            // samples instead of ~5, which is what makes "N hits in a window"
            // able to tell a flick from a bump at all.
            sensorManager?.registerListener(motionListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isInteractive) return // already on — nothing to do
        // Settle window. The motion that matters is someone PICKING THE REMOTE
        // UP; the motion right after the screen times out is usually the tail of
        // putting it down, or the surface it is on still settling. Without this
        // the display lights again within a second or two of sleeping, over and
        // over, which is both maddening and the opposite of the battery saving
        // the timeout exists for.
        val cfg = motionCfg()
        // Docked remotes do not get woken by movement. A remote on charge is
        // sitting still by definition, and the hand reaching for it will press
        // something -- so the only motion it can see is the surface it shares.
        if (cfg.ignoreWhileCharging && charging) return
        if (SystemClock.elapsedRealtime() - screenOffAt < cfg.settleSeconds * 1000L) return
        val now = System.currentTimeMillis()
        if (now - lastWakeMs < cfg.cooldownSeconds * 1000L) return
        lastWakeMs = now
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                or PowerManager.ACQUIRE_CAUSES_WAKEUP
                or PowerManager.ON_AFTER_RELEASE,
            "astrion:motionwake",
        )
        wl.acquire(4000)
        keyHandler.postDelayed({ if (wl.isHeld) wl.release() }, 3500)
    }

    override fun onDestroy() {
        bridge.stop()
        runCatching { unregisterReceiver(screenWatcher) }
        runCatching { unregisterReceiver(micProbe) }
        stopSetupServer()
        sensorManager?.unregisterListener(motionListener)
        client.disconnect()
        super.onDestroy()
    }
}
