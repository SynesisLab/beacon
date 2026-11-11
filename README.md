# Beacon

**Beacon is a native Android client for [Open WebUI](https://github.com/open-webui/open-webui).**

Kotlin + Jetpack Compose, built for phones and foldables. Point it at your own
self-hosted Open WebUI server and it becomes a fast, purpose-built chat client with
the full Open WebUI surface — chat, files, notes, memories, channels, workspace — all
rendered natively. All intelligence and all model wiring stay on your server; Beacon
is a client, and only a client. Nothing is sent anywhere except to the instance you
configure, and there is no telemetry.

## Features

### Chat
- **Live streaming** over SSE and Socket.IO task streams, with stop, regenerate,
  edit-and-resend, and continue.
- **The full message tree** — sibling branches per message with `‹n/N›` steppers;
  regenerate and edits fork real branches that survive on the server.
- **Rich rendering** — Markdown, reasoning traces ("Thought for 4s" collapse),
  tool calls with results and approvals, citations, follow-up suggestions, and
  generated images.
- **Inline HTML artifacts** — sandboxed WebView panes that size themselves, scroll
  wide content horizontally with edge fades, prefill the composer from tool prompts,
  and route links to the browser.
- **Model control** — per-chat model switching from the top chip or the model sheet;
  reasoning-effort control (Default / Low / Medium / High) sent as the server's
  `params.reasoning_effort`.
- **Composer toggles** — web search, image generation, and code interpreter, gated by
  the server's declared capabilities.
- **Temporary chats** — client-side ephemeral conversations that are never persisted.
- **Attachments** — camera, photos, and documents via the ⊕ sheet, with an offline
  upload queue.

### Voice
- **Hold-to-talk** dictation (on-device STT or server Whisper transcription).
- **Read-aloud** for any assistant turn (server TTS), with voice selection.

### Designed for foldables
- **Posture-aware layout adapts in place** — phone, book (unfolded/large screens), and
  tabletop (half-opened) postures switch without recreating the app: navigation,
  scroll positions, and drafts all survive a fold or rotate.
- **Icon rail** — on wide screens the sidebar demotes to a slim resident rail; the
  full drawer stays available on demand.
- **Companion artifact pane** — files, cards, and generated content dock in a real
  split view beside the chat: draggable divider with snap points, history arrows, and
  full-height previews.
- **Tabletop mode** — the transcript reads on the far pane while the composer moves
  to the near pane, with a reading rail that keeps the last answer in view.

### Everything Open WebUI has
- **Chats home** — pinned, folders, archive, date grouping, server-side search with
  offline fallback, and tag chips.
- **Files** — uploads plus terminal file browsing; native viewers for images
  (pinch-zoom), PDFs (paged), text/code, audio/video, and HTML artifacts
  (sandboxed, pinch-zoomable WebView).
- **Notes** — list + editor with debounced merge-save, pin, and swipe-delete.
- **Memories, Channels, Workspace** — prompts, tools, knowledge, models; all
  capability-gated so screens your server doesn't enable simply don't appear.
- **Terminal access** — browse and read terminal files through the server proxy.

### Trust & privacy
- **Offline-first** — every screen reads a local Room database; the network refreshes
  it. Pull up at the bottom of a conversation to re-sync — it also rescues a hung
  stream instead of silently dropping it.
- **Credentials in the Android Keystore** — never in app data, never in WebViews.
- **App lock** — require biometric or device-PIN authentication before the app opens.
- **Custom HTTP headers** — an editor for instance-bound headers (gateways, proxies),
  validated and stored encrypted.
- **Capability-gated, not hardcoded** — feature visibility follows the server's
  `/api/config` flags; unknown fields never crash the app.
- **No third parties** — no direct connections to model providers, no analytics, no
  crash reporting.

## Build

Requires JDK 21 and the Android SDK.

```bash
./gradlew :app:assembleDebug          # Windows: .\gradlew.bat :app:assembleDebug
./gradlew :app:testDebugUnitTest      # unit tests (protocol fixtures via MockWebServer)
./gradlew :app:verifyRoborazziDebug   # screenshot goldens
```

Install the debug APK (`app/build/outputs/apk/debug/app-debug.apk`), open the app,
point it at your Open WebUI instance URL, and sign in. CI runs the same build + test
+ golden checks on every push (`.github/workflows/ci.yml`).

## Architecture

```
feature (screens, Compose)
   │        unidirectional state: UI → engine intents → StateFlow<ChatUiModel>
chat engine (transports, reconciliation, branches, version tree)
   │
{api, render} ── OWUI REST + Socket.IO client, render-part model, SSE parser
   │
{network, speech}
   │
data ── Room (offline-first), Keystore, DataStore
```

Messages are a tree, not an array: the app reconciles Open WebUI's
`history.messages` shape and treats every edit/regenerate as a branch. The render-part
model (`ChatPart`) is the central contract the segmenter, UI, storage, and screenshot
tests all hang off.

## Repository map

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

## Protocol honesty

Beacon speaks the Open WebUI HTTP/Socket.IO protocol as documented by the server's own
source (`open-webui` is the protocol reference; every shape in `docs/protocol-notes.md`
carries `file:line` proof). All application code in this repository is original; no
code is copied from the server or from other clients.

## License

Personal project — use at your own risk. Open WebUI remains the property of its
authors and is not affiliated with this project.
