# Astrion Custom — a standalone Home Assistant UI for the Sanytron Astrion HA100

A from-scratch Android app that **replaces** the stock `HaRemote` app on the
Astrion remote with a fully custom, extensible UI you control end to end.

It connects directly to Home Assistant over the standard WebSocket API, renders
whatever cards and layouts you define, and maps the remote's physical buttons to
any action you want.


> **This is a fork.** It adds five card types (`separator`, `bubble_select`,
> `shade_control`, `bubble_climate`, `conditional`), hotkeys that scroll to a
> section rather than a page, runtime credentials with a setup web server (so the
> APK carries no token), layout sync from Home Assistant, a signed R8 release
> build, and a measured performance pass for the HA100's SoC.
> **See [docs/FORK_ADDITIONS.md](docs/FORK_ADDITIONS.md).**
> Upstream: [baes-cloud/astrion-dashboard](https://github.com/baes-cloud/astrion-dashboard).

---

## Why this instead of customising HaRemote?

The stock app pulls your HA Lovelace dashboard, then keeps **only** cards whose
`type` is one of 11 hardcoded `custom:aiks-*` strings, redrawing them in a fixed
native style you can't change via CSS or HA. There is no plugin path — the card
registry is a static list compiled into the APK.

This app inverts that: **you** own the card taxonomy. Adding a brand-new native
card type is three small steps (below), and each card is plain Jetpack Compose,
so layout / colour / sizing / animation are entirely yours.

Nothing here depends on Sanytron's cloud or their custom HA integration — only a
reachable HA instance and a long-lived token.

---

## Build & install

Requirements: Android Studio (Ladybug or newer) with the Android SDK.

1. Open the project folder in Android Studio and let it sync Gradle.
2. Copy `secrets.properties.example` to `secrets.properties` (gitignored —
   never committed) and fill in your own values:
   ```properties
   haUrl=http://<your-ha-ip>:8123
   haToken=<long-lived access token>
   ```
   Create the token in HA: Profile → Security → Long-lived access tokens.
   These are injected as `BuildConfig.HA_URL` / `BuildConfig.HA_TOKEN` at build
   time, so a real token never lands in source control.
3. Build the APK: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**,
   or from a terminal with the SDK on PATH: `./gradlew assembleDebug`.
4. Install onto the remote over ADB (same way you pulled the stock APK):
   ```
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
5. Launch it. To make it the default home experience you can set it as launcher
   or just open it manually; the stock HaRemote app can stay installed
   alongside.

### Screenshots

| | | |
|---|---|---|
| ![Main](screenshots/home.png) | ![Lights](screenshots/light-control.png) | ![Light detail](screenshots/light-card.png) |
| ![Climate](screenshots/climate-control.png) | ![Vacuum popup](screenshots/robovac-control.png) | ![Vacuum docked on floorplan](screenshots/robovac-docked.png) |

`screenshots/LD2450-tracking.gif` and `screenshots/sonos-control.gif` show the
mmWave presence dots moving live on the floorplan, and the Sonos group/volume
controls in action.

> Target: the HA100 runs Android 8.1 (API 27); `minSdk` is 26. Keep custom cards
> lightweight — the SoC (MT6580, 1 GB RAM) is modest.

---

## Add a new native card type (the whole point)

1. Create a renderer in `app/src/main/java/com/custom/astrion/cards/impl/`:
   ```kotlin
   class ThermostatCard : CardRenderer {
       override val type = "thermostat"
       @Composable
       override fun Render(config: CardConfig, ctx: CardContext) {
           // any Compose UI you like; read live state from ctx.entities,
           // fire actions with ctx.client.callService(...)
       }
   }
   ```
2. Register it in `AstrionApp.onCreate()`:
   ```kotlin
   CardRegistry.register(ThermostatCard())
   ```
3. Use it in `config/DashboardConfig.kt`:
   ```kotlin
   CardConfig("thermostat", mapOf("entity_id" to "climate.lounge"))
   ```

Order in `DashboardConfig.cards` is order on screen. An unregistered type shows
an inline warning rather than vanishing silently.

---

## Physical buttons

The HA100 button keycodes (extracted from the stock app's
`device_key_code.json`) are wired up in `input/HardwareKeys.kt`. Bind them in
`MainActivity.bindHardwareButtons()` — e.g. the dedicated LIGHT / SCENE / AC /
CURTAIN and CUSTOM_1..4 keys can each fire any service call. Presses arrive as
standard Android `KeyEvent`s, intercepted in `dispatchKeyEvent`.

---

## Project map

| Path | Role |
|------|------|
| `ha/HaClient.kt` | Standard HA WebSocket client (auth, get_states, subscribe, call_service, ping) |
| `ha/HaModels.kt` | Entity state + connection models |
| `cards/Card.kt` | `CardRenderer` interface + `CardRegistry` (extensibility core) |
| `cards/impl/*` | 18 card types — see the table in `COMMUNITY.md` for what each one does |
| `config/DashboardConfig.kt` | Your dashboard layout (compiled-in fallback; live layout is a JSON file, see below) |
| `config/DashboardLoader.kt` | Reads/writes `/sdcard/astrion/dashboard.json`, falls back to the compiled default |
| `ui/Dashboard.kt` | Renders the card list, page pager, hotkey dispatch |
| `input/HardwareKeys.kt` | HA100 keycode map + router (tap vs. long-press) |
| `MainActivity.kt` | Compose host + hardware key dispatch + motion-wake |
| `AstrionApp.kt` | Registers card types at startup |

See `COMMUNITY.md` for the full card reference, the JSON config schema, and
the physical-button map. See `ARCHITECTURE.md` for how the stock app works
internally and why this design follows from it.

---

## Status / caveats

- This is a working dashboard, actively running on two HA100 remotes day to
  day (a from-scratch replacement, not a scaffold anymore).
- Credentials come from `secrets.properties` (gitignored) via `BuildConfig` —
  see Build & install above.
- The local IR-blaster path (Sanytron's `astrion/control_command` custom events)
  is **not** implemented here — all control goes through HA `remote.*` /
  `media_player.*` services over the network, which covers the online case. See
  ARCHITECTURE.md if you want to add offline IR later.
