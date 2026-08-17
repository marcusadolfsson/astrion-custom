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

An option may instead be a **brightness slider** (`"type": "light"`), which puts
the lights a scene adjusts in the same menu that selects the scene — the natural
place to look for them, and it saves a card each.

```json
{ "name": "Nightstand His", "type": "light",
  "entity_id": "light.master_bedroom_nightstand_1" }
```

Implementation note if you add controls of your own here: a slider inside a
`DropdownMenu` must use a plain `Box` with an **explicit width** and its own
`pointerInput`. `BoxWithConstraints` throws in that position — the menu measures
its content with unbounded constraints — and inheriting the menu item's gesture
handling makes every drag select the item and close the menu.

The pill can also carry a **latch button**, beside the chevron, for when a card
needs to say *this one is the active target* as well as show a value:

```json
{ "type": "bubble_select", "options": {
    "name": "Source 2", "icon": "source",
    "active_entity": "input_select.mv_source_2",
    "toggle": { "icon": "remote", "entity": "input_select.key_target",
                "on_value": "Source 2", "off_value": "Default",
                "hidden_for": ["Off", "Switch"] },
    "options": [ … ] } }
```

It is a **radio, not a checkbox**. Every card writes its own `on_value` into one
shared entity, so latching a second card releases the first with no coordination
between them — "only one at a time" becomes a property of the data rather than a
rule something has to enforce.

`hidden_for` lists states of the card's *own* `active_entity` for which the
button is not drawn at all. It exists for targets that cannot be acted on: an
inert button is worse than no button, because it invites the press it is going
to ignore.

> Used here to point the remote's physical keys at one pane of a multi-view wall
> instead of at whatever the room is watching. That routing is Home Assistant's
> job, not the app's — the card only writes a value into an `input_select`, and
> what the keys do with it is decided by the scripts they call.

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

A thermostat card modelled on what a thermostat app shows: the **current**
temperature large on the left, what the system is doing under it
(idle / fan / cooling / heating), and the setpoint in a coloured pill on the
right. Heat draws orange, cool blue — keyed on the **mode**, not on
`hvac_action`, so a thermostat set to heat and currently idle is still orange
rather than falling through to the cool colour.

Tapping anywhere on the card opens a scrolling temperature picker. The mode
button (cool / heat / auto / off) sits inline beside the wheel, in a slot the
same width as the +/− column opposite it, so the numbers stay on the dialog's
centre line; it only offers the modes the entity actually advertises in
`hvac_modes`. Commits happen when the wheel **settles**, not per frame — a fling
across fifteen degrees would otherwise fire fifteen service calls.

In `heat_cool` there is no single `temperature` attribute at all, so the card
shows **both** bounds and the picker gets a toggle for which one is being edited.
The wheel is keyed on mode *and* bound: heat and cool store separate setpoints,
and keying on the bound alone leaves it sitting on the previous mode's number.

```json
{ "type": "bubble_climate", "options": {
    "entity_id": "climate.living_room", "step": 1,
    "hold_entity": "binary_sensor.living_room_holding",
    "clear_hold": "button.living_room_clear_hold" } }
```

**Hold indicator.** `hold_entity` is any binary sensor that is `on` while the
thermostat is off its schedule; when it is, the pill grows a "Holding" chip with
a ⊗ that calls `clear_hold`. Both are optional and independent of any particular
thermostat brand — the card only reads a binary sensor and presses a button.

Resume dismisses the chip **optimistically for a bounded 12 seconds**. It has to
look like it worked immediately and it cannot: the press goes to the thermostat,
the thermostat updates its own state, and only then does the sensor change. If
the hold is still there when the window expires the chip comes back, which is the
honest outcome for a resume that failed.

> The Home Assistant side of this is not shipped here, because it is specific to
> how one manufacturer reports its schedule. For ecobee over HomeKit, note that
> `VENDOR_ECOBEE_CURRENT_MODE` is not trustworthy on its own — it was observed
> reporting "home" during a hold and "sleep" while running the away profile. The
> thermostat also publishes each comfort profile's own heat/cool setpoints; the
> reliable test is whether the active setpoint matches *some* profile, since a
> scheduled setpoint is copied from one by definition and a held one is a number
> somebody dialled in.

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

### `source_modal`

`source_select`'s big sibling, for a `source_list` that **arrives** rather than
one you go looking for. source_select is a compact row plus a dropdown — right
for "which HDMI input?", where the list is short, stable and you went hunting for
it. This one is for a list that shows up on its own and wants reading from across
a room: it opens a full-screen modal of large tappable rows.

It was built for a voice search — ask for "james bond" and the remote shows the
17 matches as thumb-sized rows — but it takes any `media_player` with a
`source_list`, and picks with `media_player.select_source` exactly as
source_select does, so the two are interchangeable.

```json
{ "type": "source_modal", "options": {
    "entity_id": "media_player.movie_search",
    "name": "Search results",
    "subtitle_attr": "media_title",
    "open_when": "on",
    "trigger_label": "Show results",
    "item_font_size": 20, "item_height": 62 } }
```

| Option | Meaning |
|---|---|
| `open_when` | Entity state that auto-opens the modal. Omit for tap-only. |
| `subtitle_attr` | Attribute shown under the title — e.g. the query that produced the list. |
| `show_trigger` / `trigger_label` | The inline row that reopens the modal (default on). |
| `item_font_size` / `item_height` | Row type size and minimum height. |
| `max_items` | Cap the list; `0` (default) shows all. |

**The auto-open is keyed on the result identity — the `source_list` plus the
subtitle attribute — not on the entity state.** That is deliberate, and keying it
on state fails two ways that are easy to miss until it is on a device:

- Wrapped in a `conditional`, the card is only composed while results exist, so
  it never observes an off→on edge. It is born with the state already matching,
  and so never opens at all.
- Two searches in a row both leave the entity `on`. The state does not change
  between them, so every search after the first stays silent.

Keying on the content handles both. It also makes dismissal behave: closing the
modal changes neither key, so it stays closed until genuinely new results arrive
— whereas an implementation that opens *while* the state matches reopens itself
on the next recomposition and cannot be closed at all. The modal also closes
itself if the list empties, so a cleared search leaves nothing stranded.

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

#### Suppressing it with `condition`

An optional `condition` gates the overlay — while it does not match, volume and
mute changes are tracked but nothing is drawn:

```json
"overlay": {
  "volume_entity": "input_number.sonos_volume_cache",
  "mute_entity": "media_player.living_room",
  "condition": {
    "entity_id": "input_select.av_activity",
    "state_not": "Off"
  }
}
```

It takes the same shape as a `conditional` card (`entity_id` plus one of `state`,
`state_not`, `state_in`) and is evaluated by the same code, so there is no second
condition dialect to learn.

The case it exists for: a system that mutes its speaker as part of shutting down.
The mute is real, so the overlay dutifully announced it — but nobody pressed mute,
and the screen being announced to is about to go dark. Gating on "the system is
not off" leaves genuine mute presses reporting normally.

> **The gate is checked *after* the last-seen values are recorded, never before.**
> A suppressed change must be absorbed, or it still looks like a change the next
> time the gate is open — so the overlay flashes **Muted** the moment you switch
> back *on*, which is worse than the behaviour being fixed.

Two implementation notes if you extend this: the overlay's entities are collected
explicitly by `EntityRefs` because they are read outside any card (the filtered
subscription would otherwise never deliver them) — **including the condition's
entity, or the gate evaluates against a null state forever and the overlay simply
never appears** — and it deliberately ignores the first values it sees so opening
the app doesn't flash it.

### Screen-edge gestures

The bottom bar answers a swipe **up** with the info/sync panel. `gestures` adds
its mirror at the top: swipe **down** on the status strip to open a sheet of
shortcuts into system Settings.

```json
"gestures": {
  "swipe_down": {
    "title": "Device",
    "items": [
      { "name": "Force stop X", "action": "app_info", "package": "com.example.x" },
      { "name": "Wi-Fi",        "action": "wifi" },
      { "name": "All settings", "action": "settings" }
    ]
  }
}
```

Top-level in the layout, alongside `pages` and `hotkeys`. Actions:
`app_info` (needs `package`), `settings`, `wifi`, `display`, `sound`,
`bluetooth`, `apps`, `date`, `storage`, `developer`, `accessibility`,
`device_info`.

`app_info` is the useful one on a device whose OEM launcher misbehaves: that
screen carries **Force stop**, and **Enable** for a disabled package. On the
HA100 the stock launcher holds a partial wake lock for as long as it runs — one
unbroken 68-hour hold in testing, so the device never deep-slept — and a
force-stop clears it. The app returns on every boot, so the fix has to be
repeatable; this makes it two taps rather than a laptop and an adb cable.

> **Do not disable an OEM launcher package outright.** On the HA100,
> `pm disable-user` on the launcher bricks the device into a bootloop that safe
> mode, recovery and factory reset cannot reach. Repeating a harmless force-stop
> is the supported shape of this workaround.

> **Launch from the Activity, with no intent flags.** An earlier version used
> `FLAG_ACTIVITY_NEW_TASK`, which puts Settings in a task of its own: BACK then
> only descends Settings' internal stack, and on a device whose launcher is
> stopped there is nothing behind it — no way back to the dashboard at all.
> Without the flag it stacks on the app's own task and BACK returns.

Actions are a closed set rather than an arbitrary intent string: the layout is
fetched over the network, and "launch anything" is a much larger surface than
this needs. The launch is wrapped so a missing Settings activity does nothing
rather than taking the dashboard down.

### `media_player` transport: scan vs chapter skip

The full-variant card's optional `reverse`/`forward` buttons take an action map
`{service, entity_id, data}`. Adding **`scan: false`** turns them from
fast-forward/rewind into **chapter skip** — skip icons rather than scan icons,
and no scanning state.

```json
"forward": { "service": "shell_command.chapter_next",
             "scan": false, "data": { "event": "NEXT" } }
```

Both halves are needed together. The scanning state exists so the centre button
shows Play while scanning, because a tap there has to resume normal playback; a
chapter jump already lands in normal playback, so leaving the flag set would show
Play over a playing film.

> Useful to know if you drive a Kaleidescape: chapter skip is **not** reachable
> through `remote.send_command`. Home Assistant's integration whitelists eleven
> zero-argument commands, and `scan_forward` / `scan_reverse` are fast-forward and
> rewind, not chapter skip. The real `NEXT` / `PREVIOUS` are top-level protocol
> commands and need a helper that speaks to the player directly.

## Input bridge — keys while the screen is off

By default the press that wakes the screen is consumed by `PhoneWindowManager`
and never dispatched, so only the second press does anything. `bridge/` adds an
optional helper that removes that limitation.

`InputBridge` is an **entry point run as a shell process**, not part of the app's
runtime:

```sh
CLASSPATH=<apk> app_process /system/bin com.custom.astrion.bridge.InputBridge
```

The app writes that into a launcher script in device-protected storage on every
launch, so what a user actually types is short and never changes:

```sh
adb shell 'sh /data/user_de/0/<pkg>/start-bridge.sh &'
```

Worth doing rather than documenting the long form: the apk path changes on every
reinstall, so a written-down command goes stale. Device-protected storage is
readable before first unlock — which is when you want to start the bridge after a
reboot — and the file must be world-readable, because the shell that runs it is
not the app.

It opens `/dev/input/event*` directly, parses raw evdev structs, and serves one
line per key edge on `127.0.0.1:8098`:

```
KEY <scancode> <1=down|0=up|2=repeat>
```

`BridgeClient` (in the app) connects, translates the scancode through
`HardwareKey.SCAN_MAP`, and invokes **the same handler table** as
`dispatchKeyEvent` — so a screen-off press and a screen-on press are the same
action by construction, not two lists kept in step by hand.

### Why it cannot live inside the app

`/dev/input/eventN` is `root:input` mode 0660 with SELinux label `input_device`.
Opening it needs group **1004 (`input`)** *and* a permitted domain. An app is
`untrusted_app` and has neither.

Platform-signing does **not** solve this: `sharedUserId=android.uid.system` gives
the `system_app` domain, which is also not in group 1004 and also has no
`input_device` access. And an app cannot escalate to obtain them — AOSP's `su`
refuses any caller that is not already root or `shell`. Hence a shell-started
helper, the same approach Shizuku and Key Mapper take.

### Design notes if you extend it

- **Strictly additive.** The bridge cannot survive a reboot, so it is absent more
  often than present. The client retries quietly and logs at debug; a missing
  bridge is the normal case, not a fault. Never make anything depend on it.
- **The struct is 16 bytes on 32-bit ARM** (two 4-byte `timeval` fields), 24 on
  64-bit. Get this wrong and you get plausible-looking garbage codes rather than
  an error.
- **Repeats (`value 2`) are dropped.** A thumb resting on a remote key is not a
  request to fire an action twenty times.
- **The class needs a proguard keep rule.** Nothing in the app references it, so
  R8 strips it and the launch fails with `ClassNotFoundException` — at the exact
  moment debugging costs an adb session.
- **Resolve the apk path at runtime** when showing the command to a user. It
  changes on every reinstall.
- **The waking press is a race you can lose.** Do not decide "was the screen
  off?" by sampling `PowerManager.isInteractive` when the key arrives: Android
  wakes the display on its own copy of that same press and usually gets there
  first, so the waking press reads as an ordinary one and gets skipped — the
  exact press the bridge exists to recover. Track `ACTION_SCREEN_ON` and treat a
  key within ~900 ms of that transition as having arrived in the dark.
- **Only act on presses Android will not deliver itself.** With the screen awake
  it dispatches to `dispatchKeyEvent` as normal *and* the bridge sees the same
  press off `/dev/input`, so acting on both runs every action twice.
- **One client per app.** If your activity is HOME and can also be started with
  `am start`, it lands in a second task as a second instance with a second
  client, and every dark press fires twice. `launchMode="singleTask"`. Closing
  the socket is the only way to drop a stale client — cancelling the coroutine
  scope cannot interrupt a read blocked in `forEachLine`.

### Which keys should skip the wake

A key that acts while the display stays dark is the point of the bridge, but it
is only right for *some* keys. The test is not how important the key is — it is
whether the key changes anything **on the remote's own display**.

Volume, mute, channel and the whole navigation cluster (D-pad, BACK, HOME, MENU)
drive the soundbar and the media player; their UI is on the television, so
lighting a 3" screen in your hand shows nothing and costs battery every press.
Keys bound to `page:`/`scroll_to:` move the remote's own view, the voice key
shows a listening UI, and the activity shortcuts navigate the remote's page by
automation — those all want the screen up.

## Keeping the OEM launcher stopped

The stock launcher holds a `PARTIAL_WAKE_LOCK` for as long as it runs, so the
device never deep-sleeps while it is up (one observed hold ran 68 hours). Making
this app the preferred home stops it starting at boot, but the firmware brings it
back later by paths outside our control — one instance held the lock for 23 hours
before anyone noticed. So the bridge force-stops it on a timer, and a toggle in
the settings sheet turns that off when you want the stock app back.

The watchdog lives in the bridge because the bridge is the only component with
the privilege: `am force-stop` needs `FORCE_STOP_PACKAGES`, which shell has and
an app never will. The toggle is a **flag file** the watchdog re-reads each tick
rather than a command sent over the socket — the bridge is the part that
restarts, and a file survives that where a message would need re-sending on every
reconnect.

The watchdog also keeps a running **tally** of how many times it has had to act,
which the app republishes as a `total_increasing` counter. Worth having because
"does it keep coming back?" is a question about a rate, and `ps` says nothing
about what happened overnight. The count lives in a file, not in either process's
memory: the bridge is restarted by hand after every install and the app restarts
on its own, so a counter held by either would only measure the current session.
The bridge runs as shell and the app as its own uid — the file needs
`setReadable(true, false)` or the app cannot read what was just written to it.

**force-stop, never `pm disable-user`.** Disabling the OEM launcher bricks this
hardware into a bootloop that safe mode, recovery and factory reset cannot reach;
at least one user has needed SP Flash Tool and vendor firmware to recover. A
force-stop is transient and leaves the package enabled as a working fallback.

## Battery reported back to Home Assistant

The remote publishes its own battery level as `sensor.astrion_remote_battery`,
so it can be charted and alerted on like any other sensor.

Worth having because the battery is otherwise invisible from Home Assistant: the
remote is not a HA-managed device and has no integration, so the only readings
available are spot values pulled over adb. Answering anything about *drain* needs
a recorded series, not a reading.

```
state          68
device_class   battery
state_class    measurement
unit           %
attributes     charging: true|false
```

`state_class: measurement` is the part that matters — it puts the sensor into
long-term statistics, so the curve outlives the recorder's purge window rather
than being a rolling few days.

### Two implementation notes

**It uses REST, not the WebSocket the app already holds.** The WS API calls
services, and setting a state is not a service — `POST /api/states/<entity>` is
the only way to publish a value HA does not already know about. That is the one
place in the app that speaks HTTP.

**REST-created states do not survive an HA restart.** The entity disappears
until the next post. The reporter therefore posts on every level change (so the
curve keeps its shape) plus a five-minute heartbeat — short enough that a restart
leaves a blip rather than a hole, long enough that a device which spends its life
asleep is not woken to report a number that has not moved.

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
  path: /api/appletv_siri/audio/living_room   # anything taking a chunked PCM16 body
  max_ms: 10000                 # hard cap on one utterance
  silence_ms: 1200              # quiet AFTER speech before it counts as finished
  no_speech_ms: 4000            # give up if you press the key and never speak
```

That path is whatever you want to consume the audio. For sending it to **Siri on
an Apple TV**, see
[appletv-siri-voice](https://github.com/marcusadolfsson/appletv-siri-voice),
which gives each Apple TV its own URL to POST to.

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

### Suggested phrases while listening

"Listening…" tells you the microphone works. It does not tell you what the thing
on the other end *understands* — and a voice interface with no discoverable
vocabulary gets used for the two phrases you already know, forever. So the
overlay can show example phrasings, configured in the `voice:` block:

```yaml
voice:
  suggest_title: Try saying
  suggest_entity: input_select.av_activity   # optional gate
  suggest_state: Watch Kaleidescape
  suggestions:
    - play Air Force One
    - find james bond
    - what Kubrick movies do I have
```

They appear at the moment of the press, while the person is deciding what to say,
and **only in the Listening state** — by Thinking/Done the utterance is already
spoken and advice about what to say is clutter over the answer.

The gate exists because useful prompts are context-specific: movie searches are
good advice in front of a media player and noise in front of a TV where the key
routes to a vendor assistant. Omit `suggest_entity` to always show them.

Keep the lines in step with whatever actually parses them — a suggested phrase
that the sentence templates don't match is worse than showing nothing.

### Voice routes that acted, not just listened

The overlay's Done state assumed the utterance was *forwarded* somewhere — it
showed where it went, and for Assist the transcript, because a transcript is all
the feedback that path can give.

A route that ACTS has better feedback available, so it is rendered the other way
round: the outcome is the headline and what was heard sits beneath it.

```
Playing Air Force One
the one where the president fights terrorists on a plane
```

The endpoint supplies that headline in `response`. `route: "none"` — nothing
active was listening — says "No active source" in a muted colour rather than
"Sent", which would imply the utterance went somewhere.

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

---

## Theme: menus and dialogs come from `MaterialTheme`, not from you

Every surface the app draws itself is dark. `DropdownMenu` and `Dialog` are not
drawn by the app — Compose renders their container from the ambient theme — so on
a stock `MaterialTheme` they arrived in the **default light palette** on top of a
dark dashboard, which is the single most jarring thing a user can be shown.

The app now wraps its content in its own dark theme. Three things are worth
knowing if you retheme it:

- Setting `surface` alone is not enough on Material3 1.3. Menus read
  **`surfaceContainer`**, which is a separate token.
- **`surfaceTint` composites an elevation overlay** on top of whatever colour you
  set, so it has to go to `Transparent` or your colour lands slightly wrong and
  you will chase it in the wrong place.
- Menu corner radius comes from **`shapes.extraSmall`**, not from any parameter
  on `DropdownMenu`.

## Press feedback on controls that answer late

Compose drops a control's pressed state the instant the finger lifts. On a
control whose entity answers half a second later — a media player over the
network, a thermostat over HomeKit, a shade over RF — that reads as a press that
did not register, so people press again, and the second press often undoes the
first.

`PressFeedback` holds the highlight for a floor duration regardless of how
briefly the touch lasted. It is applied to the button grid, shade, climate, media
and tile cards: every control here that drives something slower than the UI.

## `media_player`: metadata has a shelf life

A player that has been idle for days keeps its last title in its state machine,
so a card will cheerfully caption it as though it were current — "now playing"
last week's episode. `stale_after_minutes` (default 360) treats metadata older
than that as absent.

Parse Home Assistant's timestamps with **`OffsetDateTime`**, not `Instant.parse`.
HA emits offsets as `+00:00`; `Instant.parse` insists on a trailing `Z` and
throws on that form. Wrapped in a `runCatching`, as it was here, that silently
disabled the entire check while looking like it worked.

## Motion wake, per remote

The remote can wake on movement rather than on a button. That assumes it rests
on something still — which is wrong for a remote that lives in a bed.

**Detect the pick-up, not the knock.** The obvious implementation watches the
change in the acceleration vector's *magnitude*, and it does not work: lifting a
remote ROTATES it, and rotation leaves the magnitude at ~1 g while only the axis
components move. Magnitude therefore responds to a jolt — someone shifting on a
mattress — and is nearly blind to the gesture you actually care about, so no
threshold satisfies both "wakes in my hand" and "does not wake all night".

This fork low-passes the vector into a gravity estimate and fires on the **angle**
between it and the direction the remote has been resting at — the standard
inclinometer treatment, see [ST DT0140](https://www.st.com/resource/en/design_tip/dt0140-tilt-computation-using-accelerometer-data-for-inclinometer-applications-stmicroelectronics.pdf)
and [ADI AN-1057](https://www.analog.com/en/resources/app-notes/an-1057.html);
the low-pass matters because tilt sensing assumes gravity is the only
acceleration present, which is false during the movement being detected. A jerk
term survives for a deliberate shake. The resting reference is re-adopted once
the angle settles, so setting the remote down at a new attitude does not leave it
one degree from firing.

```yaml
motion_wake:
  tilt_degrees: 10        # degrees of rotation that count as a pick-up
  jerk_ratio: 0.25        # sudden movement, as a fraction of this unit's own 1 g
  hits: 2
  window_ms: 1200
  settle_seconds: 8
  ignore_while_charging: true
  devices:
    nightstand: { tilt_degrees: 14, settle_seconds: 10 }
```

**Compute the tilt as a difference, not as an angle between normalised vectors.**
That detail is what makes it survive a miscalibrated sensor, and it is worth
spelling out because the wrong version looks correct and fails silently.

One remote here rests at 18.9 m/s² where an identical unit reads 9.6. Reading the
driver's raw counts (1024 = 1 g) shows why: `x=-53 y=-772 z=1787` against
`x=-23 y=-746 z=657`. x and y are ordinary on both — the entire error is z, about
+1130 counts, i.e. a **constant per-axis bias of ~1.1 g**, not a scale error.

Normalising cancels a scale error but *not* a bias: a bias drags the computed
gravity direction toward its own axis and compresses every angle measured from
it, so the same `tilt_degrees` would demand a much larger real movement on that
unit. Subtracting two measurements does cancel it, because the bias is present in
both terms. Tilt is therefore the chord between the current gravity estimate and
the resting one, converted with `2·asin(chord / 2g)` against the physical
constant rather than against the sensor's own idea of how long gravity is — exact
on a healthy unit, unaffected on a biased one. `jerk_ratio` is a difference
against the same constant.

The app also logs a warning once at startup when resting |a| is more than
2 m/s² away from 1 g. A device that lies about gravity should not be able to do
it silently; this one did for weeks, and cost two rounds of tuning that could
never have converged.

`ignore_while_charging` skips motion wake entirely while docked. A remote on
power is sitting still by definition, and the hand reaching for it will press
something.

Hits are counted inside a rolling **window** rather than consecutively: real
movement is not monotonic, and strict consecutiveness meant even a hard flick
failed to register. Sampling is `SENSOR_DELAY_UI`.

## Dock display: a docked remote is a panel, not a sleeping phone

A dock supplies mains power, so letting the screen time out there saves nothing
and makes the remote useless to glance at. While charging, the display is held on
at the lowest readable backlight; any touch or button raises it for
`bright_seconds` and it fades back.

```yaml
dock_display:
  enabled: true
  dim_level: 0.02         # 0..1, idle on the dock
  bright_level: 0.6       # 0..1, after a touch
  bright_seconds: 20
  devices:
    nightstand: { dim_level: 0.01, bright_seconds: 15 }
```

Implemented with `FLAG_KEEP_SCREEN_ON` and a **window** brightness override
rather than a wake lock and the system brightness setting. Both are scoped to the
window, so neither can outlive the activity and strand a remote at full
brightness — which on a device whose only recovery is a USB cable is the failure
mode that matters.

Two ordering traps if you reimplement this. Apply it **after** the layout
reload, or the first resume following an update decides using the previous
config — and for a newly added setting that means deciding it is absent and never
revisiting, because a screen that sleeps produces no second `onResume`. And an
activity that was already resumed when the display slept never gets another
`onResume` at all, so re-establish the mode on `ACTION_SCREEN_ON` as well.

## One layout, a different start page per remote

Several remotes can share a single layout while opening on their own room. The
config server keys the start page off the requesting client, and a page may carry
its own `overlay:` block instead of only the global one.

If you add page-scoped config of this kind, remember to teach the entity
collector about it. `EntityRefs.collect()` walked only the global overlay, so an
entity referenced solely by a per-page overlay was never subscribed and its state
stayed null forever — a feature that silently could not fire.

## Nothing may be sent before `auth_ok`

Home Assistant's websocket accepts exactly one kind of first message. Send a
`call_service` in the window between `onOpen` and `auth_ok` and HA answers
`Auth message incorrectly formatted: extra keys not allowed @ data['domain']`
and closes the socket — it does not skip the stray frame and carry on. So an
early send does not merely arrive early, it **poisons the handshake**, and the
app lands in `AUTH_FAILED` holding a perfectly valid token.

This is easy to hit in practice: a card that writes on startup, racing a
reconnect after an HA restart. `send()` therefore queues while unauthenticated
and flushes after `subscribe()`. The queue is bounded and drops oldest — a socket
that stays down must not replay a minute of stale commands at a thermostat when
it finally comes back.

`AUTH_FAILED` also retries, once a minute, rather than being terminal. A rejected
token will be rejected again, so giving up looks reasonable — but "auth failed"
is equally what the app reports when the handshake is merely disturbed, and a
wall-mounted remote that has to be relaunched by hand is the worst possible place
to be permanently wrong about it.
