# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Current state: blueprint-only

This repo currently contains **only `agent.md`** — a build blueprint / operating manual for "Beacon", a native Android (Kotlin + Jetpack Compose) client for Open WebUI. There is no Gradle project, no source code, no CI, and no git history yet. The first real work is milestone **M0** (§15): scaffold the Gradle build, Hilt, Compose theme, and the onboarding/settings screens.

**`agent.md` is the authority.** Read it before planning anything. Do not re-derive product decisions; §1 defines scope (including what is explicitly removed: direct provider connections, Hermes agents, anything iOS). Do not invent commands or structure beyond what §3, §14, and §15 specify.

## Commands

Until M0 scaffolding exists, there are no build commands. Once it does:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest   # must be green before ANY commit (agent.md §16.4)
```

- Windows host: use `.\gradlew.bat` (PowerShell) — there is no `./gradlew` shell script invocation here.
- Run a single test class: `./gradlew :app:testDebugUnitTest --tests "dev.beacon.core.sse.SseFrameScannerTest"`.
- Screenshots (Roborazzi) are required before any UI commit (§16.4); Compose module layout lands at M3 (`:app` single-module at M0).

Reference repos to clone before writing code (§2) — **read as spec, never copy code** (Conduit is GPL-3.0; see License boundary below):

```bash
git clone --depth 1 https://github.com/cogwheel0/conduit       # behavior spec
git clone --depth 1 https://github.com/open-webui/open-webui   # protocol ground truth
```

`agent.md` §2 maps protocol topics (transport classification, SSE parsing, message tree, request bodies, embed policy) to the exact Conduit files to consult.

## Architecture

- **One backend abstraction: `OwuiServer`.** Because direct provider connections are removed (§1), *all* completions go through `POST /api/chat/completions`. No multi-provider routing.
- **Layering is strict and downstream-only** (§4): `feature` → `chat engine` → `{api, render}` → `{network, speech}` → `data`. The chat engine (session orchestration, transports, reconciler, branches) is the heart of the app.
- **Unidirectional state:** the UI never mutates the stream pipeline; it sends intents (`SendMessage`, `Stop`, `Retry`, `SetBranch`) and `ChatEngine` owns transport/reconcile logic, exposing `StateFlow<ChatUiModel>`.
- **Messages are a tree, not an array.** `Chat.history: Map<id, Msg>` with `parentId`/`childrenIds` forms branches; the visible thread is the path to the current leaf. Regenerate/edit create sibling branches. This is the `/api/v1/chats/{id}` shape from the OWUI server.
- **The render-part model (`ChatPart`) is the central contract** (§4) — sealed interface (Markdown / Reasoning / ToolCall / HtmlCard / File / Citations / Status / GeneratedImage / FollowUps). The segmenter converts streaming markdown into these parts; the chat engine resolves the active branch into a flat part list; every UI component, Room serialization, protocol parser, and screenshot test hangs off this type. Design changes here ripple everywhere — treat it as an architectural decision, not a detail.
- **Transport classification** (§6.1): inspect response `Content-Type` — `text/event-stream` → httpStream; JSON with `task_id` → taskSocket (stream rides Socket.IO, with poll-recovery); plain JSON → jsonCompletion.
- **Persistence is offline-first**: every screen reads Room, network refreshes it; `partsJson` stores the normalized render-model so the UI is a pure function of the DB (§10).

## Non-negotiable operating rules (agent.md §16)

1. **Milestone-sequential.** M0 through M5 in order (§15); don't start M(n) until M(n−1) acceptance criteria pass.
2. **Never invent an endpoint or field.** Verify request/response shapes against cloned open-webui / Conduit sources and record file:line proof in `docs/protocol-notes.md`. Deltas go in the same PR.
3. **Fixture-first protocol tests.** Real SSE transcripts captured from a dev instance are the test corpus (`core/sse/fixtures/*.sse` replayed through MockWebServer); every parser/rule gets its fixture or table-driven test in the same commit (§13). Byte-level torture cases matter: frames split mid-CRLF and mid-UTF-8.
4. **License boundary (§11):** Conduit is GPL-3.0. Read it for intent and behavior; write Beacon code as original implementations. Likewise the OWUI server is read for protocol only.
5. **Capability flags decide feature visibility.** Server config (`GET /api/v1/configs/`) gates channels/notes/workspace screens — hide, don't crash.
6. **Security invariants:** credentials in Keystore only; `Authorization` never sent cross-origin from WebView; `SandboxLevel` elevation requires server-side provenance; single origin-checked JS bridge (§7.2, §11).
7. Blocked >30 min on a protocol unknown → capture a real trace from a dev instance and extend fixtures. Evidence over speculation.

## Gotchas (pre-distilled in agent.md §17 — do not re-discover these)

- Empty SSE `data:` frames are **heartbeats** — never JSON-decode them; the frame scanner is byte-level.
- Reasoning deltas arrive under **three different keys** (`reasoning_content`/`reasoning`/`thinking`) depending on upstream.
- `output: [...]` arrays are authoritative **even when empty** — presence gates rendering.
- Some upstreams speak the Responses API; `response.*` events ride the same SSE — fold, don't reject.
- Server STT upload ceiling ≈ 20 MiB / 10 min at 16 kHz WAV — cap recording client-side.
- Servers <0.9 ran outlet filters twice — call `/api/chat/completed`, but honor inline-filter detection.
- Streams that survive backgrounding need a foreground-service lease; otherwise reconcile on resume — never silently drop live text.