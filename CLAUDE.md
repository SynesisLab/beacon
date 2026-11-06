# CLAUDE.md

Guidance for coding agents working in this repository: **Beacon**, a native Android
(Kotlin + Jetpack Compose) client for Open WebUI.

## Current state

The app is built and in daily use on a physical device: onboarding/sign-in (incl. MFA),
chats with streaming, branches/regenerate/edit, folders, notes, memories, channels,
workspace (read-only), files + terminal file viewing, voice STT/TTS, inline HTML tool
cards, settings with a custom-headers editor, and a slide-in sidebar with per-row
visibility toggles. See `docs/feature-map.md` for the full surface → wire map and
`docs/decisions.md` for the ADR log.

## Commands

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:verifyRoborazziDebug   # green before ANY commit
```

- Windows host: use `.\gradlew.bat`.
- Single test class: `./gradlew :app:testDebugUnitTest --tests "dev.beacon.core.sse.SseFrameScannerTest"`.
- Screenshot goldens (Roborazzi): `./gradlew :app:recordRoborazziDebug` to re-record after
  intentional UI changes, `:app:verifyRoborazziDebug` to check. `chats_home` goldens have
  a documented ≤1% glyph-drift tolerance (`captureWithGlyphDriftTolerance` in
  `V3ScreensScreenshotTest`).
- CI (`.github/workflows/ci.yml`) runs assemble + unit tests + Roborazzi verify on push.

## Architecture

- **One backend abstraction: `OwuiServer`.** All completions go through
  `POST /api/chat/completions` against the user's own Open WebUI instance. No direct
  provider connections, no multi-provider routing.
- **Layering is strict and downstream-only**: `feature` → chat engine → `{api, render}` →
  `{network, speech}` → `data`. The chat engine (`core/chat/ChatEngine.kt`) owns
  transports, reconciliation, and branches, and exposes `StateFlow<ChatUiModel>`.
- **Unidirectional state:** the UI sends intents (`send`, `stop`, `regenerate`,
  `setBranch`); the engine owns the stream pipeline.
- **Messages are a tree, not an array.** The `/api/v1/chats/{id}` shape
  (`history.messages` with `parentId`/`childrenIds`); the visible thread is the path to
  the current leaf. Regenerate/edit create sibling branches.
- **The render-part model (`ChatPart`) is the central contract** — sealed interface
  (Markdown / Reasoning / ToolCall / HtmlCard / File / Citations / Status /
  GeneratedImage / FollowUps / Compaction). The segmenter converts streaming output into
  parts; every UI component, Room serialization, parser, and screenshot test hangs off
  this type.
- **Transport classification:** inspect response `Content-Type` — `text/event-stream` →
  HTTP stream; JSON with `task_id(s)` → task socket (stream rides Socket.IO, with
  refetch recovery); plain JSON → jsonCompletion.
- **Persistence is offline-first:** every screen reads Room; the network refreshes it;
  `partsJson`/`outputJson` store the normalized render model so the UI is a function of
  the DB. Pull-up at the bottom of a chat re-syncs from the server (and rescues a hung
  stream after 10 s of silence).

## Non-negotiable operating rules

1. **Never invent an endpoint or a field.** Verify request/response shapes against the
   `open-webui` server source and record `file:line` proof in `docs/protocol-notes.md`.
   Deltas go in the same commit as the code.
2. **Fixture-first protocol tests.** Real SSE transcripts captured from a dev instance
   are the test corpus (MockWebServer replay); every parser/rule gets a fixture or
   table-driven test in the same commit. Byte-level torture cases matter: frames split
   mid-CRLF and mid-UTF-8.
3. **License boundary:** Beacon is original code. The Open WebUI server is referenced
   for protocol shapes only — never copy server or third-party client code into the app.
4. **Capability flags decide feature visibility.** `GET /api/config` `features.*` gates
   sidebar rows and screens — hide, don't crash. Decode must tolerate schema velocity
   (e.g. per-language arrays in `default_prompt_suggestions`).
5. **Security invariants:** credentials in Android Keystore only; `Authorization` is
   reserved for the sign-in flow and never sent cross-origin from WebViews; single
   origin-checked JS bridge for HTML cards.
6. Blocked >30 min on a protocol unknown → capture a real trace from a dev instance and
   extend the fixtures. Evidence over speculation.

## Gotchas (do not re-discover these)

- Empty SSE `data:` frames are **heartbeats** — never JSON-decode them; the frame
  scanner is byte-level.
- Reasoning deltas arrive under **three different keys** (`reasoning_content`/`reasoning`/
  `thinking`) depending on upstream.
- `output: [...]` arrays are authoritative **even when empty** — presence gates rendering.
- Some upstreams speak the Responses API; `response.*` events ride the same SSE — fold,
  don't reject.
- Server STT upload ceiling ≈ 20 MiB / 10 min at 16 kHz WAV — cap recording client-side.
- Servers <0.9 ran outlet filters twice — call `/api/chat/completed`, but honor
  inline-filter detection.
- Streams that survive backgrounding need a foreground-service lease; otherwise
  reconcile on resume — never silently drop live text.
- `function_call_output` may wrap results in content-parts arrays
  (`[{type:'input_text', text:"<result>"}]`) — unwrap before parsing tool results.
- Timestamps from the server are epoch **seconds**; normalize to millis at every boundary.
- Surface clips to shape: bubble padding must ≥ corner radius or text clips at corners.
- `/api/v1/terminals//` (double slash) falls into the SPA history fallback and returns
  200 HTML — always single-slash; terminal entries have no `path` field (compose
  cwd+name); upload uses a `?directory=` query, not a header.
