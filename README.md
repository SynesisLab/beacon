# Beacon

**Beacon is an experimental, unofficial mobile client for [Open WebUI](https://github.com/open-webui/open-webui).**

It is a native Android app (Kotlin + Jetpack Compose) that connects to **your own,
self-hosted Open WebUI server** and gives it a fast, phone-shaped chat experience —
the way a first-class native client should feel. All intelligence and all model
wiring stay on your server; Beacon is a client, and only a client. Nothing is sent
anywhere except to the instance you configure.

> Experimental: the UI and feature set are under active development and change
> frequently. Expect rough edges.

## What it does

- **Chat with streaming** — token-by-token over SSE / Socket.IO task streams, with
  stop, regenerate, edit, and sibling **branches** (the full Open WebUI message tree),
  including regeneration branches and per-message branching on the server tree.
- **Rich inline rendering** — full Markdown, reasoning traces, tool calls, citations,
  follow-up suggestions, generated images, and **inline HTML tool cards** (sandboxed
  WebView panes that size themselves, scroll wide content horizontally, and hand links
  to the OS). `display_file` outputs open in a native file viewer; HTML artifacts
  render in a sandboxed, pinch-zoomable WebView pane.
- **Works on phones and foldables** — a posture-aware layout: on large screens and
  unfolded devices the sidebar becomes a resident icon rail and chats/artifacts dock
  in true multi-pane layouts (responsive split, snap-drag dividers); on tablets and
  foldables the same single-pane screens reflow. No device-specific hacks — the
  layout adapts from the fold state and window size.
- **The whole surface** — chat list with folders, pins, archive, search; Notes;
  Memories; Channels; Files (uploads + terminal file browsing); Workspace (prompts,
  tools, knowledge, models); all gated by your server's `/api/config` feature flags,
  so screens the server doesn't expose simply don't appear.
- **Attachments** — camera / photos / files via the ⊕ sheet, with an upload queue.
- **Voice** — hold-to-talk speech-to-text and read-aloud (server TTS).
- **Offline-first** — everything reads from a local Room database; the network
  refreshes it. Pull up at the bottom of a conversation to re-sync (it also rescues a
  hung stream). LLM-generated titles, tags, and follow-ups work for app-started chats.
- **Private by construction** — credentials live in the Android Keystore; custom
  headers are validated and same-origin-only; there are no direct connections to
  third-party model providers and no telemetry. App lock (device-credential gate)
  keeps the conversation private on shared devices.

## Build

Requires JDK 21 and the Android SDK.

```bash
./gradlew :app:assembleDebug          # Windows: .\gradlew.bat :app:assembleDebug
./gradlew :app:testDebugUnitTest      # unit tests (protocol fixtures via MockWebServer)
./gradlew :app:verifyRoborazziDebug   # screenshot goldens
```

Install the debug APK on a device (`app/build/outputs/apk/debug/app-debug.apk`), open
the app, point it at your Open WebUI instance URL, and sign in. CI runs the same
build + test + golden checks on every push (`.github/workflows/ci.yml`).

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
source (`open-webui` is used as the protocol reference). All application code in this
repository is original; no code is copied from the server or from other clients.

## License

Experimental personal project — use at your own risk. Open WebUI remains the property
of its authors and is not affiliated with this project.
