<div align="center">

# △ Beacon

**A native Android client for Open WebUI — built for phones and foldables.**

Kotlin · Jetpack Compose · Material 3

[![CI](https://github.com/SynesisLab/beacon/actions/workflows/ci.yml/badge.svg)](https://github.com/SynesisLab/beacon/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%209%2B-green.svg)](#)
[![Server](https://img.shields.io/badge/for-Open%20WebUI-3AA76D.svg)](https://github.com/open-webui/open-webui)

</div>

Beacon connects to **your own, self-hosted Open WebUI server** and turns it into a
fast, purpose-built mobile chat experience — the full interface, rendered natively:
streaming, branches, raw files, artifacts, voice. Everything else stays where it
belongs: on your server.

> Nothing is sent anywhere except to the instance you configure, and there is no
> telemetry. Beacon is a client, and only a client.

---

## ✦ Features

<table>
<tr><td width="50%" valign="top">

### 🗨 Chat
- **Live streaming** over SSE / Socket.IO, with stop, regenerate, edit-and-resend, continue
- **Full message tree** — sibling branches per message, real `‹n/N›` version steppers
- **Markdown, reasoning traces, tool calls, citations, follow-ups, generated images**
- **Inline HTML artifacts** — sandboxed WebViews that size themselves, scroll wide content, hand links to the browser
- **Model control** — per-chat switching + reasoning effort (Default / Low / Medium / High)
- **Composer toggles** — web search, image generation, code interpreter (capability-gated)
- **Temporary chats** — ephemeral, never persisted
- **Attachments** — camera / photos / documents, offline upload queue

</td><td width="50%" valign="top">

### 🎙 Voice
- **Hold-to-talk** dictation — on-device STT or server Whisper
- **Read-aloud** for any assistant turn (server TTS) with voice selection

### ✉ Everything Open WebUI has
- **Chats home** — pins, folders, archive, date grouping, **server search** (offline fallback), tag chips
- **Files** — uploads + terminal browsing; native viewers: images (pinch-zoom), PDFs (paged), text/code, audio/video, HTML
- **Notes** — editor with merge-save, pins, swipe-delete
- **Memories · Channels · Workspace** — all capability-gated
- **Terminal access** through the server proxy

</td></tr>
</table>

---

<div align="center">

### ⌥ Designed for foldables

</div>

Beacon's layout is posture-aware and **adapts in place** — folding or rotating never
recreates the app. Navigation, scroll positions, and drafts all survive.

| Posture | Layout |
| --- | --- |
| **phone** (< 600dp) | classic single-pane, slide-in sidebar |
| **book** (unfolded / large) | resident icon rail + true multi-pane: chats and artifacts dock **side-by-side** with a draggable, snap-point divider |
| **tabletop** (half-opened) | transcript on the far pane, **composer on the near pane**, reading rail keeps the last answer in view |

---

## 🔒 Trust & privacy

<details>
<summary><b>How Beacon keeps your data yours</b></summary>

- **Offline-first** — every screen reads a local Room database; the network refreshes it. Pull up at the bottom of a chat to re-sync (it also rescues a hung stream).
- **Credentials live in the Android Keystore** — never in app data, never exposed to WebViews.
- **App lock** — require biometric or device-PIN authentication before the app opens.
- **Custom HTTP headers** — for gateways and reverse proxies; validated, stored encrypted.
- **Capability-gated UI** — feature visibility follows the server's `/api/config` flags; unknown fields never crash the app.
- **No third parties** — no direct connections to model providers, no analytics, no crash reporting.

</details>

---

## 📐 Architecture

```
        feature (screens, Compose)
           │   unidirectional state:
           │   UI → engine intents → StateFlow<ChatUiModel>
        chat engine
           │   transports · reconciliation · branches · version tree
        {api, render}
           │   OWUI REST + Socket.IO client
           │   render-part model · markdown segmenter · SSE parser
        {network, speech}
           │
        data
            Room (offline-first) · Keystore · DataStore
```

Messages are **a tree, not an array**: Open WebUI's `history.messages` is
reconciled as-is, and every edit or regenerate forks a branch that persists on the
server. The render-part model (`ChatPart`) is the one contract the segmenter, UI,
storage, and screenshot tests all hang off.

<details>
<summary><b>Repository map</b></summary>

| Path | What |
| --- | --- |
| `app/src/main/kotlin/dev/beacon/core/chat/` | chat engine: transports, stream fold, reconciler, version tree |
| `app/src/main/kotlin/dev/beacon/core/api/`, `core/network/` | Open WebUI REST + Socket.IO client |
| `app/src/main/kotlin/dev/beacon/core/render/`, `core/sse/` | render-part model, markdown segmenter, SSE parser |
| `app/src/main/kotlin/dev/beacon/feature/` | screens: chat, home/sidebar, onboarding, settings, surfaces, terminal |
| `docs/feature-map.md` | every surface → screen files → wire endpoints → status |
| `docs/protocol-notes.md` | verified request/response shapes with `file:line` proof |
| `docs/decisions.md` | ADR log |
| `docs/beacon-ui-design.md` | UI spec (layout, navigation, controls) |

</details>

---

## ⚙ Build

Requires JDK 21 and the Android SDK.

```bash
./gradlew :app:assembleDebug          # Windows: .\gradlew.bat :app:assembleDebug
./gradlew :app:testDebugUnitTest      # unit tests (protocol fixtures via MockWebServer)
./gradlew :app:verifyRoborazziDebug   # screenshot goldens
```

Install the debug APK (`app/build/outputs/apk/debug/app-debug.apk`), open the app,
point it at your Open WebUI instance URL, and sign in. CI runs the same build + test
+ golden checks on every push.

---

## 📜 Protocol honesty

Beacon speaks the Open WebUI HTTP/Socket.IO protocol as documented by the server's own
source (`open-webui` is the protocol reference; every shape in
[`docs/protocol-notes.md`](docs/protocol-notes.md) carries `file:line` proof). All
application code in this repository is original; no code is copied from the server or
from other clients.

---

<div align="center">

Released under the [MIT License](LICENSE) ·
[Open WebUI](https://github.com/open-webui/open-webui) is the property of its authors
and is not affiliated with this project.

</div>
