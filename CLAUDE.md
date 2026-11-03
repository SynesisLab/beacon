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

## Feature coverage vs Open WebUI (audit 2026-11-02, user-directed)

Evidence base: routers listed in the cloned source + `/api/config` feature gates +
docs.openwebui.com/features. Anything here marked **MISSING** is intentionally deferred
until its milestone — add the gate/route when the milestone lands.

| OWUI feature (gate / route) | Beacon plan |
| --- | --- |
| Chats/crud/pins/archive/folders/search | M1 done / folders M4 |
| Chat completions + SSE/tool-call/output parts | M1–M2 (M2-1 landed) |
| display_file instance files → viewers | M2-3 (landed) |
| Terminal (`Open Terminal`) file inline view (`terminals.py`; proxy `/api/v1/terminals/{id}/files/read`) | **Landed M2-t** (files/* shapes verified vs `src/lib/apis/terminal/index.ts`; Files TAB opens the terminal surface 2026-11-03; gotcha: `/api/v1/terminals//` (double slash) falls into the SPA history-fallback = 200 HTML — always single-slash; entries have NO path field — compose cwd+name; upload = `?directory=` query, not a header) |
| Terminal interactive sessions / commands | post-M5 (not planned — revisit) |
| HtmlCard WebView pane + policy + bundles (mermaid/KaTeX/chart) | M2-2 (next) |
| Composer feature toggles (web search / image gen / code interpreter) (`features.*` in the request) | **MISSING — add in M3** (request field exists; no UI) |
| Message feedback/rating, continue | M3 (per agent.md §9.3) |
| Attachments/upload queue, share-target | M3 |
| Voice STT/TTS engines | M3 (agent.md §8) |
| Branches: edit/regenerate/fork/clone | M3 (fork/clone = chats.py:1783/1905) |
| Chat/share/export (share_id, md/json export) | **MISSING — add in M3** |
| Context-usage display (`context_usage` in GET /{id}) | **MISSING — add in M3** (data verified in the response) |
| Reconciler + flick-to-refetch | M3 |
| Prompt-autocomplete task (`task.autocomplete.enable`) | **MISSING — add M4+** |
| MFA sign-in flows (`mfa.py`; SigninResult MFA branch) | M4 ✓ (verify + recovery-code + enroll landed; `recover` keeps a typed message — admin reset token only) |
| Socket.IO transports (taskSocket/chat-events/confirmations) | M4 |
| Workspace screens (models/knowledge/prompts/tools/TOOL SERVERS/skills) — capability-gated | M4 (+ tool-servers listing) |
| **Memories** (`memories.py`; `memories.enable`) — user memory view/edit | M4 ✓ (view/add/edit/delete + wipe, gated `enable_memories`) |
| **Notes** (`notes.py`; enable_notes) | M4 ✓ (+ delete & pin-toggle landed M4; note rows carry ns-timestamp normalization) |
| Socket.IO transports (taskSocket/chat-events/confirmations) | M4 |
| Workspace screens (models/knowledge/prompts/tools/TOOL SERVERS/skills) — capability-gated | M4 (+ tool-servers listing) |
| **Channels** incl. `channels.encryption.enable` E2EE | M4 (read/post; live `events:channel` folds landed M4 — E2EE: hide the flag where enabled? decide then) |
| **Apps panel** (`apps.py`; `apps.enable`) | **MISSING — add M4+** |
| **Calendar** (`calendar.py`; `calendar.enable`) | **MISSING — add M4+** |
| **Automations** (`automations.py`; `automations.enable`) | **MISSING — add M4+** (server-side scheduled agent tasks — distinct from removed Hermes) |
| **User notifications/targets** (`notifications.py`) | **MISSING — add M4+** |
| Image generation (render + trigger) | M2 (render) / M3 (toggle) |
| Realtime webrtc voice-call | P2 ✓ |
| Evaluations/leaderboards (`evaluations.py`) | post-M5, only if requested |
| Model editor/custom-model create (workspace) | M4+ (view-only first) |
| Admin-surface (scim/groups/analytics/pipelines/retrieval config) | Out of scope (client, not console) |
| Subagents gate (`enable_subagents`) | Watch M4 — likely part of workspace/automations |
| User-webhooks gate (`ui.enable_user_webhooks`) | post-M5, only if requested |

## Releases audit (2026-11-03, against the GitHub releases v0.11.4→v0.13.0 — server updated to 0.13.0)

New feature rows this audit adds (not in the older table):

| OWUI feature (release / gate) | Beacon plan |
| --- | --- |
| Unified workspace SEARCH: chats + shared chats + channels/DMs + notes (v0.12.3) | Extends the M4 search row — one search surface over the four lists |
| Shared chats with "Allow replies" + live reply streaming (v0.12.0) | Rides the M3 share/export row (share_id; replies flag on the create-share API) |
| Comment threads on chats/notes/models/knowledge/prompts/tools (`ENABLE_COMMENTS`, v0.13.0) | NEW row — M5+ |
| Notification INBOX aggregating comments/mentions/automations/calendar (`notifications.*`, v0.13.0) | The audit's user-notifications row already tracks the target surface; inbox = its read shape |
| Side chats — temp side conversation via "/side" `/btw` (gated permission, off by default, v0.13.0) | NEW row — M5+, gate-hidden |
| MCP app frames rendered inside chat with per-call confirmation (v0.13.0) | The apps row's render-side: tool embeds policy already exists (M2-2); confirmations reuse the resolve/confirm dialog |
| 3D model previews GLB/glTF/STL/OBJ/PLY (v0.13.0) | NEW row — file-viewer surface, M5+ |
| Model CONTROLS knobs (named controls like Thinking: L/M/H; v0.12.0) | The M4 params sheet's shape: `model_controls` already rides `params` on the wire |
| Tools ON/OFF switches + tool creation from chat; skills versions/export | Workspace M4+ (view-only first, per the model-editor row) |
| Notes multi-page + tabs + live presence (v0.12.5) | Beacon reads/edits the MAIN page today (NoteDoc round-trip); pages + presence socket = M5+ |
| Channel typed @/# mentions auto-resolve + mentions-only mute (v0.12.1) | Channels M4 polish |
| MLS E2EE DM/group channels (`ENABLE_ENCRYPTED_CHANNELS`, v0.12.3) | The existing Channels-E2EE decision row (websocket-only + ≤16 KiB messages constraint noted) |
| Skills lazy-load (`ENABLE_SKILL_LAZY_LOADING`), terminal skills in menus (v0.11.4/v0.12.4) | Server-side; Beacon sends skill_ids as adopted (2026-11-03 fix) — no client change needed |
| Chat tabbed workspace = desktop tab bar (v0.13.0) | Deliberately NOT mobile-targeted: Beacon's tab bar = Chat/Folders/Notes/Files (user decision) |

## Gotchas (pre-distilled in agent.md §17 — do not re-discover these)

- Empty SSE `data:` frames are **heartbeats** — never JSON-decode them; the frame scanner is byte-level.
- Reasoning deltas arrive under **three different keys** (`reasoning_content`/`reasoning`/`thinking`) depending on upstream.
- `output: [...]` arrays are authoritative **even when empty** — presence gates rendering.
- Some upstreams speak the Responses API; `response.*` events ride the same SSE — fold, don't reject.
- Server STT upload ceiling ≈ 20 MiB / 10 min at 16 kHz WAV — cap recording client-side.
- Servers <0.9 ran outlet filters twice — call `/api/chat/completed`, but honor inline-filter detection.
- Streams that survive backgrounding need a foreground-service lease; otherwise reconcile on resume — never silently drop live text.
- `function_call_output` may wrap results in content-parts arrays (`[{type:'input_text', text:"<result>"}]`) — unwrap before parsing tool results (seen on-device).
- Timestamps from the server are epoch **seconds**; normalize to millis at every boundary (Chat sort bug 2026-11-02).
- Surface clips to shape: bubble padding must ≥ corner radius or text clips at corners.