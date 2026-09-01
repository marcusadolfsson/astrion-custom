# Using Claude Code with Home Assistant — a beginner's guide

> **About this file.** It is mirrored here from the private Home Assistant
> **configuration** repository these remotes are developed in, because people who
> find the Astrion app often also want to know how the whole setup behind it is
> built and maintained with Claude Code. Where it says "this repo" or "this
> house," it means that Home Assistant configuration and its real hardware — not
> the app source you are reading it in. The patterns are general; adopt them
> against your own Home Assistant.

This explains how to run [Claude Code](https://claude.com/claude-code) — an AI
coding assistant that works in your terminal — against a Home Assistant setup,
using the same patterns a real, actively-maintained Home Assistant config was
built with. It assumes you have never done this before.

The short version: Claude Code reads and edits the plain-text files that Home
Assistant is configured from, talks to Home Assistant over its API to check state
and reload changes, and keeps everything in git so nothing is ever lost. The
value is that you describe what you want in English ("make the porch light come
on at sunset, but only if someone's home") and it writes, validates, and applies
the YAML for you — and, just as importantly, it *remembers how your specific
house is wired* so it doesn't have to relearn it every time.

---

## 1. What you need first

- **A Home Assistant install you can reach on your network.** Anything works —
  Home Assistant OS on a Raspberry Pi, a Docker container on a NAS, etc. You need
  to know its address, e.g. `http://10.10.10.221:8123`.
- **Access to the config files.** Home Assistant is configured by text files
  (`configuration.yaml`, `automations.yaml`, and so on). Claude Code edits these
  directly, so it needs to see them on disk — either running on the same machine,
  or with the config folder shared/mounted where Claude Code can reach it.
- **Claude Code installed.** Follow the official install; then you start it by
  opening a terminal, `cd`-ing into your config folder, and running `claude`.
- **A long-lived access token** from Home Assistant (see §3). This is how Claude
  Code asks Home Assistant questions and applies changes without you clicking
  around the UI.

You do **not** need to know how to program. You do need to be willing to let it
make changes and to glance at what it did — treat it like a very capable
assistant who still benefits from a second pair of eyes.

### Which kind of Home Assistant do you have? (this matters)

A few commands differ depending on how Home Assistant is installed, so figure
this out once — it's the single most important thing to tell Claude Code about
your setup. There are two broad families:

- **Home Assistant OS (or Supervised)** — the all-in-one install on a Raspberry
  Pi or dedicated box, managed through the UI, with an **Add-on Store**. It has a
  built-in command called `ha` (e.g. `ha core restart`). This is the most common
  beginner setup.
- **Container (Docker) or Core** — Home Assistant running as a plain Docker
  container or a Python install. There is **no `ha`
  command**; you use `docker` commands instead.

Throughout this guide, wherever a command depends on this, both versions are
shown. **Tell Claude Code which one you have in your `CLAUDE.md` (see §5)** so it
never guesses.

One extra wrinkle for **Home Assistant OS specifically**: getting Claude Code to
*see* your config files usually means installing the **Advanced SSH & Web
Terminal** add-on (with "Protection mode" off so it can reach the host) or the
**Samba share** add-on, and running Claude Code there or on a machine that mounts
that share. On a Docker/Core install the files are just a folder on the host, so
this is simpler. Either way, the goal is the same: Claude Code needs to be able to
read and write your `configuration.yaml` and friends.

---

## 2. The one idea that makes this work: text files + git

Everything Home Assistant does that you'd set up in the UI can also be written as
YAML text. Claude Code lives in that text layer. Two habits keep it safe:

- **Everything is in git.** git is a "track every change, undo anything" system.
  This repo commits after each meaningful change, so a bad edit is one command to
  revert. If you take nothing else from this guide: **put your Home Assistant
  config in a git repository before you start.** Then no change Claude Code (or
  you) makes can be permanently lost.
- **Check before you apply.** Home Assistant can validate a config *without*
  restarting. Claude Code runs that check, sees the errors, and fixes them before
  anything goes live — so a typo becomes a caught mistake instead of a Home
  Assistant that won't start.

---

## 3. Giving Claude Code a way to talk to Home Assistant

Editing files is half of it. The other half is Claude Code *observing* your
system — reading the state of a light, listing your entities, reloading an
automation — so it can verify its own work instead of guessing.

**Create a long-lived access token:** in Home Assistant, click your user profile
(bottom-left), scroll to **Long-Lived Access Tokens**, and create one. Copy it
somewhere safe. This repo stores it in `secrets.yaml` (which is kept out of git —
see §6) under a name like `claude_api_token`, and reads it like this:

```bash
HA_TOKEN=$(grep claude_api_token secrets.yaml | cut -d' ' -f2)
```

With that token, Claude Code can ask Home Assistant anything over its REST API:

```bash
# What state is the living room light in?
curl -s -H "Authorization: Bearer $HA_TOKEN" \
  http://10.10.10.221:8123/api/states/light.living_room | jq

# Reload automations after editing automations.yaml (no full restart)
curl -s -X POST -H "Authorization: Bearer $HA_TOKEN" \
  http://10.10.10.221:8123/api/services/automation/reload
```

You don't have to write these — Claude Code does. But knowing they exist tells
you *how* it knows whether its change worked: it reads the result back.

Two deeper channels this repo also uses, for when the REST API isn't enough:

- **The WebSocket API** for things like renaming or removing entities in the
  registry — operations the REST API doesn't expose.
- **Restarting, validating, backing up, and reading logs of Home Assistant
  itself.** These are the commands that differ by hosting type:

  | Task | Home Assistant OS / Supervised | Container (Docker) / Core |
  |---|---|---|
  | Validate config | `ha core check` | `docker exec homeassistant python -m homeassistant --script check_config -c /config` |
  | Restart | `ha core restart` | `docker restart homeassistant` |
  | View logs | `ha core logs` | `docker logs --tail 100 -f homeassistant` |
  | Back up | `ha backup new` | snapshot/`tar` the config folder (git already covers text; this catches `.storage/`) |

  Reloading a single domain (`automation.reload`, `template.reload`, …) is done
  over the REST API and is **the same on both** — which is why reloading is
  preferred over a full restart wherever it's possible.

  Put the correct column for your setup into `CLAUDE.md` so Claude Code uses the
  right one without asking.

---

## 4. The safe workflow, every time

This is the loop this repo follows, and the one to ask Claude Code to follow:

1. **Edit** the YAML file.
2. **Validate** the config (`check_config`) — catch typos before they bite.
3. **Apply** — reload just the part that changed (automations, templates, etc.)
   if possible; a full restart only when necessary.
4. **Verify** by reading the entity/state back over the API.
5. **Commit** to git with a message saying what and why.

A few hard rules worth stating to Claude Code up front, all learned the painful
way:

- **Never edit the `.storage/` folder while Home Assistant is running** — those
  are internal registries; use the WebSocket API instead.
- **Never put `initial:` on a helper** (`input_number`, `input_boolean`, …) — it
  silently resets the value on every restart. Omit it and Home Assistant
  remembers the last value.
- **Reload the specific domain rather than full-restarting** when you can — it's
  faster and less disruptive.
- **Back up before risky changes** — with everything in git, that's a commit; you
  can also snapshot the config folder.

---

## 5. The part that makes it *good*, not just possible: teaching it your house

A fresh Claude Code knows Home Assistant in general. It does **not** know that
*your* kitchen fan is a Tuya device that lies about its state, or that *your* LED
wall must never be power-cycled a certain way. The difference between a frustrating
assistant and a great one is whether that hard-won knowledge is written down where
it gets loaded automatically. This repo uses three stores, and it's worth copying
the structure exactly:

### `CLAUDE.md` — the always-loaded briefing

A file named `CLAUDE.md` in your config folder is read **at the start of every
session, automatically.** Put the cross-cutting facts here — the ones every
conversation needs:

- Your Home Assistant address and version, and how it's hosted.
- How to restart/validate it (the exact commands for *your* setup).
- Where the API token lives and how to read it.
- Your naming conventions (this repo uses `{room}_{device}`, e.g.
  `fan.bedroom_2_ceiling_fan`).
- The handful of "never do this" landmines that span the whole system.

Keep it short. `CLAUDE.md` is loaded every time, so bloating it wastes the
assistant's attention. Anything subsystem-specific goes in a skill instead.

### `.claude/skills/` — on-demand subsystem manuals

A "skill" is a folder of notes about one area — lighting, your AV rack, your
cameras — that Claude Code loads **only when it's working on that area.** This
repo has skills like `lighting-picos`, `av-stack`, `tuya-fans`, each opening with
the traps that make that subsystem bite. This keeps `CLAUDE.md` lean while still
giving deep knowledge exactly when it's relevant. When you discover something
durable about a subsystem — "the Bond bridge gives no state feedback, so we track
position ourselves" — that's a skill note, not a `CLAUDE.md` line.

You don't have to build skills on day one. Start with `CLAUDE.md`; add a skill the
first time you find yourself re-explaining the same subsystem.

### Memory — how *you* like to work

Claude Code can also keep short memory notes about your preferences and
corrections ("I want you to check the config before every restart", "present
guesses as guesses, not facts"). When you correct it, ask it to remember the
correction. Over time it stops making the same mistakes.

**The golden rule:** when Claude Code learns something that will matter again,
make it write that down — in `CLAUDE.md`, a skill, or memory. A fact learned and
not recorded is a fact you'll pay for twice.

---

## 6. Keeping secrets out of git

Your token, passwords, and API keys live in `secrets.yaml`, and that file must
**never** be committed to git. Add it to `.gitignore`:

```
secrets.yaml
.storage/
*.log
```

Tell Claude Code this rule explicitly ("`secrets.yaml` is gitignored and must
stay that way; never write a secret into a tracked file"). This repo scans its
own changes for accidentally-included secrets before every push — a good habit to
ask for.

---

## 7. Pointing a brand-new Claude Code install at this repo

If you have this repository (or one like it) and want your own Claude Code to
learn from it, here's the whole process from zero:

1. **Get the files onto a machine Claude Code can reach**, ideally the same one
   your Home Assistant config lives on:
   ```bash
   git clone <your-repo-url> home-assistant-config
   cd home-assistant-config
   ```

2. **Start Claude Code from inside that folder:**
   ```bash
   claude
   ```
   Because you started it *in* the folder, it automatically finds and reads
   `CLAUDE.md` and knows the skills in `.claude/skills/` are available. You don't
   have to tell it to — that's the point of those files.

3. **Fill in your own secrets.** Copy the token you made in §3 into
   `secrets.yaml` under the name `CLAUDE.md` expects (here, `claude_api_token`),
   and update `CLAUDE.md` with *your* Home Assistant address, version, and how
   yours is hosted. The patterns transfer; the specific IPs and hostnames are
   yours to change.

4. **Tell it what you want, in plain English.** For example:
   > "Read CLAUDE.md and the dashboards skill, then rebuild my living room
   > dashboard to group the lights by room."

   Or simply:
   > "Before we start, read CLAUDE.md and tell me back how my system is set up,
   > so I know you've got it."

   That last one is a good first move: it confirms Claude Code has actually
   absorbed your setup before it touches anything.

5. **When it gets something wrong, correct it and ask it to remember.** "That
   restart command is for Home Assistant OS; mine is Docker — update CLAUDE.md."
   The system gets better specifically because you do this.

### Adapting the patterns to a *different* house

If you're using this repo as a template rather than running this exact house:

- Rewrite `CLAUDE.md` with your environment (address, hosting, restart commands,
  naming convention). Keep the *structure*; replace the *facts*.
- Delete the skills for hardware you don't own; keep the ones you do; add new ones
  as you go. A skill is just a Markdown file in `.claude/skills/<name>/`.
- Keep the safety rules in §4 — they're not house-specific, they're Home
  Assistant-specific, and every one of them was learned by something breaking.

---

## 8. A realistic first session

To make it concrete, a good first task that exercises the whole loop without much
risk:

> "I want a new automation: turn on `light.porch` at sunset and off at 11pm.
> Check the config before applying, reload automations rather than restarting,
> then show me it's loaded by reading its state. Commit it when it works."

Watch what it does: it edits `automations.yaml`, runs the config check, reloads,
reads the automation back, and commits. That single task teaches you the rhythm —
edit, validate, apply, verify, commit — that everything else follows.

Welcome aboard. The assistant is capable; your job is to tell it your house's
quirks once, insist it writes them down, and glance at what it changes. Do that
and it compounds — every session it knows a little more about your specific setup
and asks you a little less.
