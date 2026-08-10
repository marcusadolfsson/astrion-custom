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
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sqrt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.custom.astrion.config.ConfigServer
import com.custom.astrion.config.ConnectionConfig
import com.custom.astrion.config.DashboardConfig
import com.custom.astrion.config.DashboardLoader
import android.content.IntentFilter
import com.custom.astrion.config.EntityRefs
import com.custom.astrion.config.HotkeyConfig
import com.custom.astrion.config.JsonPlain
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.input.HardwareKey
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.custom.astrion.bridge.BridgeClient
import com.custom.astrion.input.HardwareKeyRouter
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

        // Motion-wake tuning: accel magnitude delta (m/s²) that counts as
        // "moved", and a cooldown so a single lift fires one wake.
        const val MOTION_THRESHOLD = 0.9f
        const val WAKE_COOLDOWN_MS = 2000L
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
    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = event.values
            val mag = sqrt(x * x + y * y + z * z)
            if (lastMagnitude != 0f && abs(mag - lastMagnitude) > MOTION_THRESHOLD) {
                wakeScreen()
            }
            lastMagnitude = mag
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private lateinit var client: HaClient
    private val keyRouter = HardwareKeyRouter()

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
    private val bridge by lazy {
        BridgeClient(scope = lifecycleScope) { key ->
            keyRouter.shortHandlerFor(key)?.invoke()
        }
    }

    /** Current layout: starts as the compiled-in defaults, replaced from disk. */
    private var dashboard by mutableStateOf(DashboardLoader.Result(DashboardConfig.default, null))

    /** Page index requested by a hardware button; consumed by the Dashboard. */
    private var navTarget by mutableStateOf<Int?>(null)

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
     * The exact command to start the input bridge, with THIS install's apk path
     * filled in. The path changes on every reinstall, so a hardcoded one in the
     * docs would send people chasing a ClassNotFoundException; resolving it here
     * means the sheet always shows something that works.
     */
    private fun bridgeCommand(): String =
        "CLASSPATH=${applicationInfo.sourceDir} app_process /system/bin " +
            "com.custom.astrion.bridge.InputBridge"

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
        bindHotkeys(dashboard.config.hotkeys, dashboard.config.longHotkeys)
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

        bridge.start()
        // Poll the client's flag for display only. A StateFlow would be tidier,
        // but the bridge is deliberately not load-bearing and this costs one
        // comparison a second.
        lifecycleScope.launch {
            while (true) {
                if (bridgeConnected != bridge.connected) bridgeConnected = bridge.connected
                delay(1000)
            }
        }

        setContent {
            val entities = client.entities.collectAsState()
            val connection = client.connection.collectAsState()
            val voiceState = voice.state.collectAsState()
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
                bridgeConnected = bridgeConnected,
                bridgeCommand = bridgeCommand(),
            )
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

    override fun onResume() {
        super.onResume()
        // Re-read the local cache on foreground (instant, no network). Pulling
        // from HA is now on-demand — swipe up for the info panel's Sync button,
        // or the VOICE hotkey — not on every resume.
        reloadDashboard()
    }

    /** Load config from the local cache and apply it. Synchronous — tiny file. */
    private fun reloadDashboard() {
        applyDashboard(DashboardLoader.load())
    }

    /**
     * Adopt a layout: bind its hotkeys and narrow the live entity subscription to
     * just the entities it references (see EntityRefs) — otherwise the client
     * would track all ~1,650 entities on this HA to render about 30.
     */
    private fun applyDashboard(result: DashboardLoader.Result) {
        dashboard = result
        bindHotkeys(result.config.hotkeys, result.config.longHotkeys)
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

    /** Rebind the physical buttons to the config's short- and long-press hotkeys. */
    private fun bindHotkeys(short: List<HotkeyConfig>, long: List<HotkeyConfig>) {
        keyRouter.clear()
        short.forEach { hk ->
            val key = runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull()
                ?: return@forEach
            // Only a plain service call repeats while held. Built-in actions
            // (voice, sync) and navigation are edge-triggered: auto-repeat would
            // fire them many times a second.
            val repeats = hk.action == null && hk.page == null && hk.scrollTo == null
            keyRouter.on(key, repeats) { runHotkey(hk) }
        }
        long.forEach { hk ->
            val key = runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull()
                ?: return@forEach
            keyRouter.onLong(key) { runHotkey(hk) }
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
            voice.toggle(dashboard.config.voice)
            handled = true
        }
        hk.page?.let { pageName ->
            val idx = dashboard.config.pages.indexOfFirst { it.name.equals(pageName, ignoreCase = true) }
            if (idx >= 0) {
                navTarget = idx
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
        val code = event.keyCode
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

    private fun setupMotionWake() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        // Prefer a wake-up accelerometer so events still arrive with the screen
        // off; fall back to the normal one (which only helps while awake).
        motionSensor = sensorManager?.getSensorList(Sensor.TYPE_ACCELEROMETER)
            ?.firstOrNull { it.isWakeUpSensor }
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        motionSensor?.let {
            sensorManager?.registerListener(motionListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isInteractive) return // already on — nothing to do
        val now = System.currentTimeMillis()
        if (now - lastWakeMs < WAKE_COOLDOWN_MS) return
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
        runCatching { unregisterReceiver(micProbe) }
        stopSetupServer()
        sensorManager?.unregisterListener(motionListener)
        client.disconnect()
        super.onDestroy()
    }
}
