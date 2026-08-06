# Fork additions

Everything this fork adds on top of upstream `baes-cloud/astrion-dashboard`, in
the order it is useful to read. Each item below landed as its own commit.

Nothing here changes the upstream architecture — the open `CardRegistry` is still
the extension point, and the layout is still a JSON document. The additions are
five new card types, three new hotkey behaviours, runtime configuration (so the
APK carries no credentials), a release build, and a performance pass aimed at the
HA100's hardware (1 GB RAM, MediaTek MT6580, Mali-400, Android 8.1).

---

## New card types

### `separator`

A section header: small icon + label + divider line. Purely visual, but its
`name` doubles as the anchor that a `scroll_to` hotkey jumps to, so renaming one
means updating any hotkey that targets it.

```json
{ "type": "separator", "options": { "name": "Display", "icon": "screen" } }
```

`icon` accepts a small vocabulary (`tv`, `brightness`, `screen`, `light`,
`curtain`, `thermostat`, `movie`, `remote`, …) mapped to Material icons in
`CardIcons`; unknown names fall back to a generic icon.

### `bubble_select`

A selector pill — circular icon, name, current value, chevron — that opens a
dropdown of options. Modelled on the Home Assistant "Bubble Card" look but sized
for a 320 dp-wide screen and fat fingers.

Each option fires a service, so it drives script-based selectors, while
`active_entity` supplies the value shown. **`active_value` must match that
entity's state string exactly.**

```json
{ "type": "bubble_select", "options": {
    "name": "Activity", "icon": "tv", "open_on": "Watch",
    "active_entity": "input_select.lr_av_activity",
    "options": [
      { "name": "Apple TV", "service": "script.watch_apple_tv",
        "active_value": "Watch Apple TV" },
      { "name": "Off", "service": "script.av_off", "active_value": "Off" }
    ] } }
```

`open_on` is described under [Hotkeys](#hotkeys).

### `shade_control`

A cover controller with the target selector on the left and open / stop / close
on the right, in one pill. The selector is backed by an `input_select` whose
options are read **live from the entity**, so adding a shade in Home Assistant
needs no app change; picking one calls `input_select.select_option` and the
buttons fire whatever services you configured (which route to the target on the
HA side).

```json
{ "type": "shade_control", "options": {
    "name": "Shades",
    "target_entity": "input_select.shade_target",
    "open":  { "service": "script.shade_target_open" },
    "stop":  { "service": "script.shade_target_stop" },
    "close": { "service": "script.shade_target_close" } } }
```

### `bubble_climate`

A thermostat pill: name, `setpoint · now current`, and − / + buttons calling
`climate.set_temperature`. The setpoint is tinted by `hvac_action`, so a glance
tells you whether the system is actually cooling/heating rather than just set to.

**Deliberately minimal** — no mode picker, fan speed, humidity or schedule. A
remote in your hand is for "it's a degree too warm"; each extra control costs
vertical space on a 480×800 screen and a press to hunt for. Anything beyond
nudging the setpoint belongs on a wall dashboard, or use the stock `climate` card
for the full set.

```json
{ "type": "bubble_climate", "options": {
    "entity_id": "climate.living_room", "name": "Common Areas AC", "step": 1 } }
```

### `conditional`

A wrapper that renders its child `card` (or `cards`) only while an entity
matches. Used to show source-specific controls — e.g. an Apple TV app picker only
while that source is selected.

```json
{ "type": "conditional", "options": {
    "entity_id": "input_select.av_activity", "state": "Watch Apple TV",
    "cards": [ { "type": "media_player", "options": { … } } ] } }
```

Supports `state`, `state_not`, and `state_in`.

---

## Changed cards

- **`media_player` (full variant)** — the transport row is now
  `[reverse] play/pause [forward]`, all one size, with the chapter skip buttons
  removed and volume left to the hardware keys. `reverse` and `forward` are
  optional action maps (`{service, entity_id, data}`) shown only when configured.
  While scanning, the centre button shows **Play**, not Pause, so a tap resumes
  normal playback. The card **collapses entirely** when there is no media title
  and no cover art, instead of rendering a bare entity name.
- **`monitor`** — rows accept an optional `attribute`, reading that attribute
  instead of the entity state. This lets one attribute-packed sensor produce a
  row per field:
  ```json
  { "entity_id": "sensor.video_in", "attribute": "resolution", "name": "Resolution" }
  ```
- **`button_grid`** — buttons can reflect live state (`active_entity` +
  `active_value`) so the current choice is highlighted, and `button_height` plus
  larger defaults make them finger-sized.
- **`fan` tile** — restyled to match the other control pills (same height, corner
  radius, label-over-value typography and button treatment).

---

## Hotkeys

`HardwareKeys.kt` also corrects the HA100 keycode map: the physical mute button
emits **164** (`KEYCODE_VOLUME_MUTE`); **82** is `KEYCODE_MENU` and is now mapped
to `MENU` instead of acting as a second mute.

Three additions to the hotkey table, all combinable:

| Field | Effect |
|---|---|
| `scroll_to` | Scroll the page to the `separator` whose name matches, instead of only switching pages. Combine with `page` to jump pages *then* scroll. |
| `open_on` (on a `bubble_select`) | When a hotkey scrolls to that section, also pop this card's dropdown — so a section with a single selector is one keypress away from its options. |
| `action: "sync"` | Re-pull the layout from Home Assistant (see below) and toast the result. |

```json
{ "key": "LIGHT", "page": "AV", "scroll_to": "Lights" }
{ "key": "VOICE", "action": "sync" }
```

### Volume / mute overlay

A transient on-screen readout — the remote's own OSD — so a volume press gives
feedback in your hand instead of only on the TV. A centred pill (speaker icon,
percentage or **Muted**, level bar) fades in on a change and hides after 1.5 s.

```json
"overlay": {
  "volume_entity": "input_number.sonos_volume_cache",
  "mute_entity": "media_player.living_room"
}
```

Top-level in the layout, alongside `pages` and `hotkeys`.

> **Point `volume_entity` at whatever is stepped *immediately* on the button
> press** — e.g. an `input_number` your volume scripts write — **not** the media
> player's `volume_level`. A speaker's state feedback lags by enough to make the
> overlay feel broken.

`mute_entity` is read for the `is_volume_muted` attribute.

Two implementation notes if you extend this: the overlay's entities are collected
explicitly by `EntityRefs` because they are read outside any card (the filtered
subscription would otherwise never deliver them), and it deliberately ignores the
first values it sees so opening the app doesn't flash it.

---

## Voice

### The VOICE key streams the microphone

The HA100 has a real microphone (`android.hardware.microphone`, and a
`MultiMedia1_Capture` PCM device), and the stock app holds `RECORD_AUDIO` as
`SYSTEM_FIXED`. Upstream never uses it. This fork wires the physical **VOICE**
key: press, talk, and it ends itself on silence.

**The remote makes no routing decision.** It POSTs raw PCM16 to an endpoint you
name and lets the server decide what the audio means. Configure it in the
layout:

```yaml
hotkeys:
  - key: VOICE
    action: voice

voice:
  path: /api/hap_remote/audio   # anything accepting a chunked PCM16 body
  max_ms: 10000                 # hard cap on one utterance
  silence_ms: 1200              # quiet AFTER speech before it counts as finished
  no_speech_ms: 4000            # give up if you press the key and never speak
```

Audio is **16 kHz / mono / PCM16**, which is what HA's Assist pipeline expects
and also exactly what HAP requires for Siri audio to an Apple TV — so one
capture format serves both with no resampling on a 1 GB MT6580.

### Why the upload is streamed, not buffered

The request body **is** the live microphone: a blocking `MicCapture.captureInto()`
is driven straight from OkHttp's `RequestBody.writeTo`, with no queue in
between, and each ~100 ms chunk is flushed as it is read. Audio therefore leaves
the remote while the user is still speaking. If the far end holds something open
for the duration of the request — a HomeKit Siri session does exactly that — the
property matters twice over.

### Ending an utterance without hold-to-talk

**The HA100's VOICE key emits an instant press+release, not a hold**, so
hold-to-talk is impossible on this hardware and the end of an utterance has to be
inferred: ~1.2 s of quiet after speech was heard. A separate no-speech timeout
gives up if the user never speaks, because otherwise an accidental press pins the
microphone open for the full window. (Credit to
[vvaters/astrion-ha-dashboard](https://github.com/vvaters/astrion-ha-dashboard),
which hit the same hardware wall; the thresholds match theirs.)

`VoiceOverlay` shows Listening / Thinking / result in the style of the volume
OSD — press-and-talk with no visible state is indistinguishable from a broken
microphone.

### Mic diagnostic

`MicProbe` is **adb-gated on purpose**; an always-listening "record the room"
HTTP route on a living-room device is a bad trade for a diagnostic:

```
adb shell am broadcast -a com.custom.astrion.MIC_TEST --ei secs 5 -p com.custom.astrion
adb pull /sdcard/astrion/mic-test.wav
```

The `-p <package>` is **required** — Android 8.1 drops implicit broadcasts, and
the send still reports `result=0` while the receiver never fires.

## Runtime configuration

### Credentials are no longer compiled in

Upstream injects `HA_URL`/`HA_TOKEN` as `BuildConfig` fields, so the APK carries a
long-lived token and rotating it means rebuilding. This fork resolves them at
runtime from **app-private `SharedPreferences`** (`ConnectionConfig`), falling
back to `BuildConfig` if present — leave those blank in `secrets.properties` and
nothing sensitive is in the binary.

There is deliberately **no config file on `/sdcard`**: a long-lived token there is
readable by any app holding the storage permission.

### Setup web server

`ConfigServer` serves a small form on **`http://<remote-ip>:8099`** for the HA URL
and token, saving them to those private preferences — the same idea as the stock
HaRemote app's pairing endpoint, so provisioning needs no adb.

It runs **only while setup is open**: started automatically when no credentials
exist, toggled from the info panel, and stopped as soon as credentials are saved,
so it is not a permanently open write endpoint on your LAN. On first run the app
shows a start screen with the address and port instead of an empty dashboard.

It is a hand-rolled `ServerSocket` — one form and one POST did not justify a HTTP
dependency in a 1.3 MB APK.

### Layout sync (no more `adb push`)

The layout can be fetched from Home Assistant instead of living only at
`/sdcard/astrion/dashboard.json`, which becomes an offline cache:

- `DashboardLoader.REMOTE_PATH` is the HA path to fetch. Point it at
  `/local/astrion/dashboard.json` (drop the file in HA's `www/`) — or at a custom
  endpoint if you want the layout authored in YAML and converted server-side,
  which is what this fork's author does.
- Sync is **on demand**: once on cold launch, then from the info panel's **Sync**
  button or a hotkey with `action: "sync"`. `onResume` only re-reads the cache, so
  returning to the app costs no network.
- Invalid JSON is rejected and the cache kept, so a bad edit can't brick the
  remote.

### Swipe-up info panel

Swiping up from the bottom bar opens a panel showing the build number, the live HA
URL, a **Sync dashboard** button, and a toggle for the setup server.

---

## Release build

Upstream ships `isMinifyEnabled = false` and no signing config, so the natural
thing to install is a debug build. Debug builds are markedly slower on this
hardware — `debuggable` ART runs less-optimised code, and the Compose compiler
keeps live literals and source information, all of which land on the UI thread.

This fork adds R8 + resource shrinking and a release signing config that reads the
keystore path and passwords from the gitignored `secrets.properties`:

```properties
releaseKeystore=/path/to/release.jks
releaseKeystorePassword=…
releaseKeyAlias=astrion
releaseKeyPassword=…
```

`proguard-rules.pro` keeps kotlinx-serialization's reflective serializers — without
those keep rules R8 silently breaks JSON parsing **at runtime, not build time**.

Measured on an HA100: **APK 16 MB → 1.3 MB**, and warm scroll jank fell from ~12 %
to ~7 % of frames.

> Keep the same signing key forever. Android only allows in-place updates when the
> new APK is signed with the same key; a different key means uninstalling first.

Verify a release APK before trusting it:

```sh
apksigner verify --min-sdk-version 26 --verbose app-release.apk   # needs v2 = true
```

---

## Performance work

Aimed at the HA100's SoC; all figures measured on-device with `dumpsys gfxinfo`
and `dumpsys meminfo`.

### Subscribe only to the entities the layout uses

Upstream calls `get_states` and subscribes to every `state_changed` event. On a
mid-sized instance that meant parsing **0.7 MB for ~1,650 entities on every
connect** and then ~2.6 events/s forever — for a layout that referenced 44.

`EntityRefs` walks the loaded layout for anything shaped like an entity id and
`HaClient` uses **`subscribe_entities`** with that filter, so Home Assistant
filters server-side and streams compressed deltas. `onEvent` handles both the
compressed `a`/`c`/`r` format (merging attribute deltas — HA only sends what
changed) and the legacy `state_changed`.

The collector deliberately **over-collects**: an extra subscription is harmless,
a missed one would leave a card permanently blank.

Result: 1,656 → 44 entities, Java heap **5.9 MB → 2.8 MB**.

### Per-key entity observation

Compose skips a composable only when its parameters compare equal, and an
*unstable* parameter (`CardContext`, which holds a `Map`) is compared by
**instance**. Upstream allocates a fresh `CardContext` on every entity publish, so
no card could ever skip: every visible card re-executed on every tick, and every
memoized lambda capturing `ctx` was invalidated with it.

`CardContext` is now `@Stable` and remembered, and the entity flow is drained
*outside* composition into a `SnapshotStateMap` diffed per key. Compose tracks
those reads per key, so `ctx.entities[id]` subscribes to **that key only**.

Result: churning an entity 45 times while viewing a page that doesn't display it
renders **0 frames** (previously it recomposed everything).

> If you add a card, never iterate the whole map inside composition
> (`values`/`keys`/`forEach`) — that subscribes to every key and undoes this.

### Card-level pass

- `media_player`: `::mp` was a *function reference*, which Compose's lambda
  memoization does not cover, so a new instance every recomposition made the media
  layouts unskippable — now a remembered lambda. The blurred backdrop's filtered
  bitmap scale ran inside `remember{}`, i.e. on the composition thread — now on
  `Dispatchers.Default`. One full-card overdraw layer removed.
- `source_select` / `shade_control`: option lists re-walked their `JsonArray` and
  allocated on every recomposition — now keyed on the raw attribute.
- `bubble_climate`: label `remember`ed; `String.format` dropped.

**Honest result:** this last pass did *not* move the scroll benchmark (~17 % janky
before and after). Scroll cost on this SoC is per-item composition, measure and
draw as cards enter the viewport — not allocations. The changes are still correct
(less main-thread work, less garbage, one less layer), and idle cost is good
(~2 % CPU, 2.8 MB Java heap), but if you are chasing scroll smoothness the next
lever is a **baseline profile**, which this fork has not attempted.
