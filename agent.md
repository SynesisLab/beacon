# agent.md — "Beacon": a Claude-Android-style native Android client for Open WebUI

**Document type:** build blueprint / operating manual for an autonomous coding agent (and humans pairing with it).
**Reference client:** [cogwheel0/conduit](https://github.com/cogwheel0/conduit) (GPL-3.0, Flutter, iOS+Android) — used **as a spec**, not as source code to copy.
**Target:** one native Android app, Kotlin + Jetpack Compose. No Flutter, no React Native, no WebView-shell-of-a-website.

---

## 0. Product definition (one paragraph)

Beacon is to Open WebUI what the official Claude Android app is to Claude: a fast, native, single-purpose chat app. You sign in to **your own Open WebUI instance**, pick a model, and chat with token-by-token streaming, folders, pinned chats, attachments, voice, and — the killer differentiator — **inline rendering of every rich tool output the server produces** (`render_frontier_ui` HTML cards, `display_file` file previews, code-execution blocks, reasoning traces, citations). All intelligence lives on the server. The app is a client, and only a client.

## 1. Scope: what this app is, and what is explicitly REMOVED

The Conduit feature matrix is the baseline, minus two entire subsystems that Beacon will **not** contain:

| Conduit feature | Beacon |
| --- | --- |
| Open WebUI server connection (chats, folders, notes, channels, workspace, tools, web search, image gen) | ✅ Port — this is the product |
| **Direct Connections** (client-side OpenAI/Ollama/OpenRouter/Azure profiles, API keys, Apple On-Device / Apple PCC providers) | ❌ **Removed.** No provider credentials ever enter the app. The OWUI server holds all model wiring (`models` endpoint returns only server-exposed models). Delete-on-port: `packages/conduit_core/lib/features/direct_connections/**`, `lib/features/direct_connections/**` |
| **Hermes agents** (self-hosted agent service, schedules, approvals) | ❌ **Removed.** No agent tab, no Hermes client code. Delete-on-port: `packages/conduit_core/**/hermes*`, `lib/features/hermes/**` |
| Voice (input STT, TTS read-aloud, voice-call mode) | ✅ STT + TTS are P0 (see §7). Realtime webrtc voice-call is P2/optional | 
| Notes, Channels, Workspace screens (models/knowledge/prompts/tools) | ✅ Port behind server-capability flags — hide screens the server doesn't expose (same rule as Conduit) |
| Home-screen widgets, quick actions, share-target ingestion | ✅ Port (M3+) |
| Terminal feature | ✅ **M2: inline terminal-file viewing** (user request 2026-11-02 — files read via the OWUI proxy `/api/v1/terminals/{server_id}/files/…`; the interactive terminal surface itself stays post-M5) |
| iOS / Cupertino anything | ❌ Android only |

**Non-negotiable design consequence of the removals:** there is exactly one backend abstraction (`OwuiServer`). No routing between multiple completion transports by provider type; all completions go through `/api/chat/completions`.

## 2. Reference material — clone these before writing code

```bash
git clone --depth 1 https://github.com/cogwheel0/conduit      # behavior spec (READ; do NOT copy code, see §14 licensing)
git clone --depth 1 https://github.com/open-webui/open-webui  # ground truth for API/protocol, esp. src/lib/components/chat/Chat.svelte
```

Key	Conduit files to consult as **spec documents** (paths verified in the repo clone):

| Topic | File |
| --- | --- |
| Completion request/transport classification (httpStream / taskSocket / jsonCompletion) | `packages/conduit_core/lib/services/api_service_chat_completions.dart` |
| SSE parsing incl. heartbeats, Responses-API passthrough, reasoning keys | `packages/conduit_core/lib/services/openwebui_stream_parser.dart`, `sse_frame_scanner.dart` |
| Post-completion outlet filters | same file → `sendChatCompleted()` → `POST /api/chat/completed` |
| Request body fields (chat_id, session_id, features, tool_ids, background_tasks…) | `packages/conduit_core/lib/features/chat/providers/chat_openwebui_requests.dart` |
| Socket.IO (`chat-events`, channel events, task sockets) | `packages/conduit_core/lib/services/socket_service.dart` |
| Chat history = message tree (`parentId` / `childrenIds`) | `packages/conduit_core/lib/services/conversation_parsing.dart` |
| Server STT/TTS | `packages/conduit_core/lib/features/chat/server_speech/openwebui_server_speech.dart` |
| VAD + dictation pipeline (16 kHz, silence timeouts, ≤20 MiB upload) | `lib/features/chat/services/voice_input_service.dart` |
| HTML/SVG in WebView: nav & popup policy | `packages/conduit_core/lib/features/web_embed/web_embed_policy.dart` |
| Markdown segmentation (reasoning blocks, tool-call blocks, citations, mentions) | `packages/conduit_markdown/lib/src/*` |
| Streaming that survives backgrounding (foreground-service lease) | `lib/core/services/background_streaming_handler.dart` |

Endpoints harvested from all `api_service_*.dart` files (the canonical surface to cover):

```
/api/version/                              /api/chat/completions     /api/chat/completed
/api/tasks/chat/…  /api/tasks/stop/  /api/tasks/title/  /api/tasks/tags/  /api/tasks/active/  /api/tasks/moa/
/api/v1/auths/…  /api/v1/users/…  /api/v1/configs/
/api/v1/chats/… (incl. /search, /archive, /pinned, /all)   /api/v1/folders/…   /api/v1/notes/…   /api/v1/channels/…
/api/v1/models/…   /api/v1/files/…   /api/v1/knowledge/…   /api/v1/tools/…  /api/v1/functions/…
/api/v1/images/…   /api/v1/audio/…   /api/v1/memories/…   /api/v1/evaluations/…   /api/v1/prompts/…  /api/v1/skills/…  /api/v1/groups/…
```

Rule for the implementing agent: **never invent an endpoint or a field**. If a request shape isn't in §5/§6, open `open-webui` backend source (or Conduit's spec files) and verify, then note it in `docs/protocol-notes.md` with the file:line you verified against.

## 3. Tech stack & libraries (pin in `gradle/libs.versions.toml`)

| Concern | Choice | Notes |
| --- | --- | --- |
| Language / runtime | Kotlin 2.1+, JDK 21 toolchain, AGP 8.13+ | minSdk **29** (WebView+API coverage), targetSdk 36, compileSdk 37 (`compileSdkMinor` for minor platforms; ADR-0006) |
| UI | Jetpack Compose (BOM latest stable), **Material 3**, dynamic color optional, `androidx.navigation:navigation-compose` | Claude-app feel: edge-to-edge, predictive back, bottom-sheet model switcher |
| DI | Hilt | Constructor-injected services; no service locators |
| Async | Coroutines + Flow (`kotlinx-coroutines`) | Every network/streaming call suspend; UI collects with `repeatOnLifecycle` |
| HTTP | **OkHttp 5.x** + `kotlinx-serialization-json` | One client for REST + SSE; no Retrofit needed (surface is small and irregular) |
| SSE | **Own `SseFrameScanner`** port of the *concept* from Conduit's `sse_frame_scanner.dart` | Byte-chunk-safe frame splitting, split-CRLF, split-UTF8, empty `data:` heartbeat frames ignored, `[DONE]` sentinel. ~150 lines, fully unit-tested |
| Socket.IO | `io.socket:socket.io-client:2.1.x` | Only for task sockets / `chat-events` / channels; M4 |
| Persistence | **Room 2.7 (KSP)** for chats/messages/parts cache; **DataStore (Proto)** for settings | Offline-first: every screen reads Room, network refreshes it |
| Images | **Coil 3** | Model logos, file previews, zoomable (`net.engawapg.lib:zoomable`) |
| Media | **Media3 / ExoPlayer** | TTS playback, uploaded audio/video preview |
| Markdown | `com.mikepenz:multiplatform-markdown-renderer-m3` + custom block/inline extensions | Custom extensions handle: reasoning blocks (collapsible), tool-call blocks, citations, mentions, HTML-block routing (below) |
| Code highlight / Mermaid / LaTeX | **Bundled `highlight.js`, `mermaid.js`, `KaTeX` inside WebView blocks** (assets shipped in APK, no CDN) | One renderer path for code/md-HTML; matches Conduit's approach (it bundles `chartjs.min.js`, `flutter_tex.js`, mermaid as native submodules) |
| PDF | `androidx.pdf` / `PdfRenderer` | Native page rendering to bitmaps; no JS PDF viewer |
| Files | ActivityResult `OpenDocument`/`GetContent`, Photo Picker, `OpenDocumentTree` for saves | |
| STT | `android.speech.SpeechRecognizer` (on-device, default) + `AudioRecord`→WAV→`POST /api/v1/audio/transcriptions` (server mode) | §7 |
| Permissions | `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_DATA_SYNC` (streams), `SET_ALARM`-class quick actions | |
| Background | WorkManager (upload queue, sync), **foreground service with a stream "lease"** for while-app-backgrounded streaming (port Conduit's `background_streaming_handler` concept) | |
| Auth UX | `androidx.biometric`, OkHttp `CookieJar` (persisted) for reverse-proxy flows | |
| Diagnostics | Timber, LeakCanary (debug), StrictMode (debug), Baseline Profiles (M5) | |
| Tests | JUnit, Turbine, MockK, OkHttp **MockWebServer** (SSE fixture replay), Robolectric, Compose UI tests, **Roborazzi** screenshots, macrobenchmark | |

**Deliberately excluded:** any on-device LLM, any provider SDK, Compose multiplatform, KMP.

## 4. Architecture

Single-module `:app` at M0 → split into Gradle modules by M3. Layering is strict, downstream-only:

```
feature (Compose screens, ViewModels)
   ▼
chat engine (session orchestration, transports, reconciler, branches)      ← the heart
   ▼
api (OwuiApi endpoints)  ──  render (markdown→parts pipeline, sandboxed HTML pane, file viewers)
   ▼                                ▼
network (OkHttp/SSE/Socket.IO, auth)   speech (native STT, server transcription)
   ▼
data (Room cache, DataStore, file cache)
```

- **Unidirectional state**: `ChatEngine` exposes `StateFlow<ChatUiModel>`; the UI never mutates the stream pipeline directly — it sends intents (`SendMessage`, `Stop`, `Retry`, `SetBranch`), the engine owns transport/reconcile logic. This mirrors Conduit's transport-dispatch/controller split (`chat_transport_dispatch.dart`, `chat_comparison_controller.dart`) without copying its code.
- **Message model = the OWUI tree**, not an array. A `Chat` carries `history: Map<id, Msg>` where `Msg.parentId`/`childrenIds` forms branches; the visible thread = path to the current leaf. Regenerate/edit ⇒ sibling branch. `ChatUiModel` resolves the active path into a flat list of **render parts**.

### Render-part model (the contract between engine and UI — everything else hangs off this)

```kotlin
sealed interface ChatPart {
    data class Markdown(val md: String, val streaming: Boolean) : ChatPart          // includes inline md artifacts
    data class Reasoning(val md: String, val collapsedByDefault: Boolean = true)     // <details> + reasoning_content
    data class ToolCall(val id: String?, val name: String, val args: String, val status: ToolStatus,
                        val result: ToolResult?) : ChatPart                          // collapsible; result may contain parts
    data class HtmlCard(val id: String, val html: String?, val srcUrl: String?,       // ← frontier-UI cards, chart embeds, artifacts
                        val title: String?, val sandboxLevel: SandboxLevel,
                        val minHeightDp: Int, val interactive: Boolean) : ChatPart
    data class File(val fileId: String, val name: String, val mime: String,           // ← display_file, uploads, generated images
                    val viewer: FileViewer) : ChatPart
    data class Citations(val items: List<Source>) : ChatPart
    data class Status(val text: String, val done: Boolean) : ChatPart                  // "Searching the web…" pills
    data class GeneratedImage(val url: String, val prompt: String?) : ChatPart
    data class FollowUps(val items: List<String>) : ChatPart
}
```

## 5. Auth & connection layer

1. **API key path (default, first-class):** user generates a key in OWUI *Settings → Account*; app stores it in `EncryptedSharedPreferences`/Keystore; sent as `Authorization: Bearer <key>`. This is the whole story for ~90% of self-hosted users.
2. **Username/password:** `POST /api/v1/auths/signin` → JWT stored in Keystore; auto-refresh on 401. (Conduit additionally supports SSO/LDAP/proxy-cookie capture — see `docs/` and its auth screens; port proxy-cookie support only in M4+, gated to "advanced".)
3. `GET /api/version` at connect; persist and surface it. Behavior flags come from `GET /api/config` (optional auth; the web UI's own pre-auth config) — gates channels/notes/images/web-search screens exactly like Conduit hides permission-less sections. `/api/v1/configs/` is the admin import/export surface, not the client gate. Verified: open-webui `main.py:2289` (config), `routers/configs.py:100-134` (admin surface); see `docs/protocol-notes.md`.
4. Custom headers (org-routing, `X-API-Key` behind gateways): settings screen field, appended by an OkHttp interceptor.
5. TLS: trust-user-CAs **off** by default; provide an opt-in advanced toggle for self-signed labs, clearly marked.

## 6. Chat engine — the protocol, concretely

### 6.1 Sending (verified against Conduit `api_service_chat_completions.dart` + `chat_openwebui_requests.dart`)

`POST /api/chat/completions` (streaming), body ≈:

```jsonc
{
  "model": "<model id>",
  "messages": [ /* full visible-history projection of the current branch */ ],
  "stream": true,
  "chat_id": "<chat id>",
  "id": "<assistant message id generated client-side>",
  "session_id": "<socket.io session id, when socket connected>",
  "features": { "image_generation": false, "web_search": false, "code_interpreter": false },
  "tool_ids": [ /* enabled tools/functions */ ],
  "filter_ids": [ /* enabled global filters */ ],
  "skill_ids": [],
  "background_tasks": { "title_generation": true, "tags_generation": true, "follow_up_generation": true },
  "params": { /* model params from model settings */ }
}
```
Verify each field against the open-webui source you cloned (backend `routers`/Chat.svelte) before implementing; note deltas in `docs/protocol-notes.md`.

**Transport classification (same as Conduit):** inspect response `Content-Type` —
- `text/event-stream` → **httpStream** (the 99% case; §6.2);
- JSON object with `task_id` (`/api/tasks/...` shape) → **taskSocket**: stream arrives over Socket.IO `chat-events`; wait for socket bind, with **poll-recovery** = `GET /api/v1/chats/{chat_id}` on timeout (Conduit `chat_transport_dispatch.dart` lines ~80–250 describe this exactly);
- plain JSON → **jsonCompletion** (tiny models, some pipelines) — render whole payload.

### 6.2 SSE contract (encode the parser, then never touch it again)

Every `data:` frame is one JSON object; frames may be split across TCP chunks; empty-data frames are **heartbeats** — never JSON-decode them; `data: [DONE]` ends the stream. Payload precedence (verified in `openwebui_stream_parser.dart`):

1. `event: {type, data}` — OWUI event-emitter envelope from tools/filters. Types to handle natively:
   `status` (progress pill), `source`/`citation` (→ Citations part), `details` (title/desc per message), `files` (attach), `confirmation` / `input` (**native dialog; await user response; send reply per socket/task protocol**), `notification`, `chat:title` / `chat:tags` (update list rows live).
2. `error: {...}` → typed error bubble with retry.
3. `type: "response.*"` — Responses-API passthrough events; fold `response.output_text.delta` etc. onto the output-item list, harvest usage at `response.completed`.
4. `choices[0].delta` — OpenAI-style deltas; `content` → markdown append; reasoning via **`reasoning_content`** / `reasoning` / `thinking` (all three keys; provider-dependent, verified).
5. `output: [...]` — structured output snapshot; presence check even when empty (same as Chat.svelte): parse items into `ToolCall` / `Reasoning` / code-interpreter blocks.
6. `sources`, `usage`, `selected_model_id`, `content` snapshot — meta fields.

### 6.3 Completion & side-effects

- On done: `POST /api/chat/completed` with `{model, messages, chat_id, id, session_id, …}` — backend runs **outlet filters**; response *may contain modified messages* → merge into local state before final render (Conduit documents this; servers ≥0.9 run filters inline, so the call can be skipped when `_runsOutletFiltersInline` is detected — detect via version/config).
- Title/tags/follow-ups arrive via background-task events or `/api/tasks/title|tags`; update the Room row + UI chip. **Regenerate title** affordance in the chat menu (Claude-app parity).
- **Stop:** cancel request + `POST /api/tasks/stop/{task_id}` (or socket stop) → keep partial text, mark message `interrupted`.
- **Streaming resilience:** foreground-service lease while streaming (kind: `chat`); if the OS kills the SSE, on next foreground/resume run the reconciler (§9.2) instead of showing stale text forever.

### 6.4 History correctness

`GET /api/v1/chats/{id}` returns the full message tree → upsert Room `chats` + `messages` tables keyed by message id with `parentId`/`childrenIds`; current branch pointer = per-chat `currentMessageId` (stored in chat `history.currentId` on web; persist locally and sync on write). All mutations (edit/regenerate/rate) mirror OWUI endpoints before optimistic-local-application.

## 7. Inline rich rendering — **the** differentiator

The web UI renders tool outputs as live HTML; Beacon must too, natively. Two renderer paths, one pipeline.

### 7.1 Pipeline

Stream markdown accumulates in the `Markdown` part → a **segmenter** (port of the *concept* of `conduit_markdown`: `reasoning_parser`, `tool_calls_parser`, `details_block_syntax`, `citation_parser`, `embed_utils`) splits it into `ChatPart`s *before* the streaming render:

- fenced ` ```html ` blocks (or `<div`…raw-HTML blobs) with an artifact title/id → `HtmlCard`;
- `<think>`/reasoning markers → `Reasoning`;
- tool JSON blocks (function_call / function_call_output in `output` items) → `ToolCall` with parsed args/result;
- `![[file:...]]` / file refs, chart/mermaid blocks, katex `$…$`/`$$…$$` → respective parts;
- partial (still-streaming) HTML: render live-preview once the fence opens with ≥200 chars, finalize on close — Claude-app-style.
- `display_file` tool results: parse the referenced file (id/name/mime from the tool result JSON emitted on the stream; verify shape from a real instance capture) → **never dump it as JSON text**; produce `ChatPart.File`.

### 7.2 `HtmlCard` = sandboxed inline WebView pane (Compose interop)

- `compose AndroidView { WebView }` inside the chat list item; height = intrinsic (`scrollHeight`) via one-shot JS bridge report + `onPageFinished`; grows with content while streaming (cap at viewport, “expand” affordance → full-screen modal, Claude-artifact style).
- Loaded via `loadDataWithBaseURL(base = instance origin)` so relative URLs resolve; intercept resource requests (`shouldInterceptRequest`) to attach auth headers only for same-origin file refs (never leak `Authorization` cross-origin).
- **Policy engine (port concepts from `web_embed_policy.dart`, write our own):** JS on in-card; network navigations *automatic* load in-frame; user-activated links/popups open outside (Custom Tabs) unless target is the instance; `file://`, `content://`, `intent://` blocked; no ambient file access; SafeBrowsing on. `SandboxLevel`: `UI` (default, JS+styles), `MEDIA` (JS + same-origin fetch), `TRUSTED` (server-admin-issued embeds only) — never a level the HTML didn't earn.
- Dark mode: `prefers-color-scheme` respected; algorithmic darkening fallback for cards that hard-code white.
- Performance: pool + reuse WebViews for offscreen cards; hard cap ~8 live panes, snapshot-to-bitmap the rest (Lottie of the decade, nobody scrolls 50 live WebViews).

### 7.3 `display_file` → native file viewers

`GET /api/v1/files/{id}/content` streaming to cache:

| mime | Viewer |
| --- | --- |
| `image/*` | Coil + pinch-zoom, save/share |
| `application/pdf` | PdfRenderer pages → bitmap pager (lazy, cache bitmaps) |
| `text/*`, code, json, md | syntax-highlighted viewer (highlight.js WebView block, same infra as §7.2) with copy + “send as context” |
| `audio/*`, `video/*` | Media3 player card |
| office/other | Native card (icon, size, name) + **Open with…**/download intents — no shame, web can't read your .docx either |

`display_file` also presents as an inline card in the transcript (file chip with open affordance) — matching what the web UI shows, not a JSON dump.

### 7.4 Everything else rich

- Code blocks: language chip, copy, wrap toggle, highlight.js render (WebView block) — inside markdown, not separate panes.
- Mermaid: bundled `mermaid.js` in a `HtmlCard`; chart.js likewise (Conduit ships `chartjs.min.js` bundled — same call).
- Citations: pill row inline, sheet with source list, deep-links via Custom Tabs.
- Reasoning: `<think>`-styled collapsible with animated chevron + elapsed-time chip while streaming.
- Follow-ups: chip row under the latest assistant part, tap → send.

## 8. Voice input (native STT) — P0, two engines

1. **On-device (default):** `SpeechRecognizer` with partial results (`RecognizerIntent.EXTRA_PARTIAL_RESULTS`), streaming into the composer live; on-device flag requested where available (API 33+ `EXTRA_AUDIO_SOURCE` streaming, else classic). Language list from `SpeechRecognizer.getVoiceResults`/`OnDeviceRecognizer` capabilities; user locale picker.
2. **Server (OWUI admin's Whisper/OpenAI STT):** `AudioRecord` @16 kHz mono → WAV (cap **10 min / 20 MiB** at 16 kHz — fits the server limit, verified in Conduit `voice_input_service.dart`), `multipart` POST `/api/v1/audio/transcriptions` with optional `language`; response text insertion policy selectable: *replace composer | append | send immediately*.
3. UX: mic button in composer — **tap = dictate with live partials**, long-press-lock = hands-free; haptic on record start/stop; audio focus + `AudioAttributes(VOICE_COMMUNICATION)`; waveform meter native. VAD auto-pause is an M4 stretch (port the *parameters*, not the Silero plugin: 16 kHz, 512-sample frames, 0.6/0.35 thresholds, 0.5 s end-pad).
4. TTS read-aloud (P1): `POST /api/v1/audio/speech` → Media3 playback; only if server exposes engines (`GET /api/v1/audio/models`).
5. Permission denial = graceful disabled state (mic button hidden + explainer), never a crash loop — test this.

## 9. Quality-of-life features (Claude-app parity + asks)

### 9.1 Flick-to-refetch (explicitly requested)

Gesture: list is at bottom (no older content in view) + user **flicks upward** ≥ 40% of viewport within 300 ms ⇒ silent `POST /api/v1/chats/{id}`-sync → reconciler diff → if changed, subtle haptic + "Updated" pill fading in; if unchanged, nothing (no spinner theater). Implement via `NestedScrollConnection` velocity probe; debounce 1 s; disabled mid-stream (stream is the source of truth then). Also bind a fallback affordance: "Latest ⇅" pill pinned above composer whenever `viewState.isSyncingPossible`.

### 9.2 Reconciliation (`ChatReconciler`)

Runs on: resume/foreground, flick-to-refetch, post-stream crash recovery, socket reconnect. Idempotent diff rules: server message ≠ local → replace unless local has *newer user edits* (edit timestamp guard); assistant partial + server final → adopt server; branch pointer → follow server `currentId` unless user explicitly browsed branches locally (then keep + show "server updated" badge).

### 9.3 The rest of the list

Streaming controls: stop (visible at composer, Claude-style), regenerate, **edit-and-retry creating a branch**, copy (md + plain), rate, continue. Composer: attachments (files/photo-picker/multi-paste from clipboard), slash-commands for server prompts/tools, mention-knowledge (`@knowledge`), model switch sheet with params, temporary-chat toggle. Sidebar: search (`/api/v1/chats/search`), folders, pinned, archived, tags; pagination via `/api/v1/chats/?page=`. Read-aloud, share-target ingestion (“share → new chat”), app shortcuts (new chat/mic/camera), home-screen widget (M4), pull-to-refresh at top of *chat list* (list is paginated; transcript itself is a tree, not paged), biometric app-lock, per-chat haptics minimal + tasteful.

## 10. Persistence & offline

Room schema: `servers`, `chats(chatId, folderId, pinned, archived, updatedAt, title…, currentMessageId)`, `messages(messageId PK, chatId F, parentId, childrenIds JSON, role, partsJson BLOB, timestamp, meta…)` — parts are the normalized render-model JSON (so UI is a pure function of DB). `filesCache(fileId, mime, size, path…)` with LRU eviction. All lists render from Room; network actions write-through. Uploads/edits queue in WorkManager with retry + backoff; conflict = server-wins except queued-user-writes-replay. Cold start = Room-first paint, background refresh (no splash screen purgatory).

## 11. Security & privacy

Credentials: Keystore-backed (never Preferences, never logs). Auth header attachment limited to instance origin (verified in OkHttp `AuthInterceptor` + per-host allowlist). WebView: no ambient file access, no `setAllowFileAccessFromFileURLs`, SafeBrowsing on, JS bridge = one method (`Beacon.link(url)` / `Beacon.height(px)` / `Beacon.copy(text)`), origin-checked. HTML cards default `SandboxLevel.UI`; elevation to `MEDIA/TRUSTED` requires server-side provenance tag (verified in protocol capture), never message-content sniffing alone. Crash logs strip message content. No third-party analytics; local-only crash reporting (export button in settings).

**License note:** Conduit is **GPL-3.0**. This project's agent must treat Conduit as a spec/reading reference only — *no code transcription* (including its SSE scanner) — or Beacon must become GPL too. The OWUI server itself (BSD-3-style with branding terms) is read for protocol only; porting is to Beacon's original code.

## 12. Performance & battery targets

- Stream frame→pixels: < 16 ms p95 for text-only; markdown re-render incremental (diff parts; only the live part recomposes).
- Chat with 2 000 rendered parts: first frame < 300 ms from Room; scroll jank < 1%.
- WebView pane budget (§7.2) enforced; bitmap snapshot at cap.
- Streaming FGS uses data-min notification; killed-network resync < 2 s.
- APK < 35 MB; cold start p95 < 900 ms; baseline profile shipped at M5.

## 13. Testing strategy

- **Fixture-first protocol tests:** capture real SSE transcripts from a dev instance (`curl -N` with `--data-binary` request blobs) into `core/sse/fixtures/*.sse`; MockWebServer replays them against the parser + engine (golden: part-tree JSON). Same for taskSocket (record socket events), errors, heartbeats, split-chunk torture tests (bytes cut mid-UTF8/mid-CRLF).
- Unit: reconciler diff rules table-driven; segmenter over nasty markdown corpus; policy engine (nav/popups) pure-function tests.
- Screenshot (Roborazzi): every `ChatPart` variant light/dark at 3 widths; HTML cards pixel-diff with frozen content.
- Compose UI tests: composer flows, flick-to-refetch gesture (MotionEvent injection), attachment queue.
- Manual matrix each milestone: API 29/33/36 × one OEM skin; airplane-mode mid-stream; backgrounded-stream kill-and-restore; permission-denied paths.

## 14. Repo layout (target)

```
beacon/
├── agent.md                         ← this file
├── docs/
│   ├── protocol-notes.md            ← verified field/endpoint deltas as-built
│   ├── decisions.md                 ← ADR-log, append-only
│   └── fixtures-capture.md          ← how we recorded server fixtures
├── gradle/libs.versions.toml
├── app/
│   └── src/main/kotlin/dev/beacon/
│       ├── BeaconApp.kt / MainActivity.kt / di/
│       ├── core/
│       │   ├── network/   (auth interceptors, cookie jar, TLS toggle)
│       │   ├── sse/       (SseFrameScanner, StreamParser → typed updates; /test/fixtures/*.sse)
│       │   ├── socket/    (Socket.IO: chat-events, task bind/recovery)
│       │   ├── api/       (OwuiApi: chats, models, files, audio, tasks, workspace, configs)
│       │   ├── chat/      (ChatEngine, transports, ChatReconciler, branches, background lease)
│       │   ├── render/    (Segmenter, HtmlCardPane, policy engine, file viewers, highlight assets)
│       │   ├── speech/    (NativeStt, ServerStt, TtsPlayer)
│       │   └── data/      (Room, DataStore, caches, WorkManager queues)
│       └── feature/
│           ├── onboarding/  (instance URL → auth → model pick; “just works” bar is API-key + password)
│           ├── chat/        (transcript list + parts, composer, dialogs, model sheet)
│           ├── sidebar/     (chats/ folders/ pinned/ search)
│           ├── settings/    (server, headers, voice, appearance, about)
│           └── workspace/   (models, knowledge, prompts, tools — capability-gated)
```

## 15. Milestones (build order is mandatory; each ends with the stated Acceptance Criteria)

- **M0 — Skeleton:** Gradle + toml + Hilt + Compose + theme; onboarding (URL, API key *or* password); `GET /version /configs /models /auths/signin`; settings screen; encrypted storage. **AC:** real-instance sign-in, model list rendered, key rotates to Keystore, app survives airplane mode start.
- **M1 — Chat that doesn't embarrass anyone:** chat list (Room-backed, pagination), create/delete/pin/archive, send message → classify transport → httpStream SSE parse → streamed markdown w/ code blocks + copy, Stop, JSON fallback, error surfaces, `/api/chat/completed` + filter merge, title/tags updates. **AC:** 30-min chat at phone-typing speed, zero dupes after 20 background-resume cycles; parse fixtures pass.
- **M2 — Rich rendering:** full part pipeline (reasoning, tool calls, status, citations, follow-ups), `HtmlCard` sandbox pane (height, links, dark), `display_file` viewers (image/pdf/text/code/audio/video), **inline terminal-file viewing (promoted from stretch, 2026-11-02 — via the terminals proxy)**, mermaid + KaTeX + chart.js bundles, image-generation rendering, expand-to-fullscreen. **AC:** on a dev OWUI instance with `display_file` + a frontier-UI-style HTML card + chart + mermaid: all four render inline, correct height, links open externally, dark mode sane; screenshot tests green.
- **M3 — QoL wave:** flick-to-refetch + reconciler, streaming FGS lease + background survival, branches (edit/regenerate/rate), attachments + upload queue, share-target, app shortcuts, biometric lock, server-TTS read-aloud, **native STT both engines**, temp chat. **AC:** each §9 item demos; mid-stream background-kill restores via reconciler; STT end-to-end on-device *and* server.
- **M4 — Deep integration:** Socket.IO: taskSocket, confirmations/input dialogs native, live title/status events; folders UX; workspace screens (models/knowledge/prompts/tools) behind capability flags; channels+notes; notes offline; widget; custom headers + proxy-cookie advanced auth. **AC:** feature flags verified by toggling server config — hidden where absent.
- **M5 — Ship prep:** baseline profiles, macrobenchmark gates, Roborazzi suite, leak/perf burn-down, i18n (en + zh-Hant + zh-Hans), FGS policies audit, signing + Play metadata, LICENSE + notice file. **AC:** perf targets §12 met on a mid-tier device; no StrictMode whines in debug runs.

## 16. Operating rules for the implementing agent

1. Milestone-sequential. Do not start M(n) until M(n−1) ACs pass in CI.
2. Protocol changes → `docs/protocol-notes.md` with the clone file:line of proof. Never guess a field name.
3. Every parser/rule gets a fixture or table-driven test **in the same commit**.
4. `./gradlew :app:assembleDebug :app:testDebugUnitTest` must be green before any commit; screenshots before any UI commit.
5. Read Conduit for intent; write Beacon code as original implementations (GPL boundary, §11).
6. Capability flags from the server decide feature visibility — hide, don't crash (the Conduit rule).
7. Log decisions to `docs/decisions.md`; if a rule in this file proves wrong against the cloned source, update both places in the same PR and flag it.
8. When blocked > 30 min on a protocol unknown: capture a real trace from the dev instance and extend fixtures — evidence over speculation.

## 17. Appendix — gotchas pre-distilled from Conduit's source (do not re-discover these)

- Empty SSE `data:` frames are heartbeats — must not be JSON-decoded. Frames can be split mid-CRLF and mid-UTF-8; the scanner is byte-level.
- Reasoning deltas arrive under **three different key names** (`reasoning_content`/`reasoning`/`thinking`) depending on upstream.
- `output: [...]` arrays are authoritative **even when empty** — presence gates rendering (as Chat.svelte does).
- Some upstreams speak the Responses API; those `response.*` events ride the same SSE untouched — fold them, don't reject.
- Server <0.9 ran outlet filters twice if you call both the deprecated path *and* `/api/chat/completed` — call the endpoint, but honor inline-filter detection.
- Server STT upload ceiling ≈ **20 MiB / 10 min** at 16 kHz WAV — cap recording before the server rejects it.
- Chat history is a **tree**, not a list; naive parent-array UI breaks branches forever.
- Server-dependent surfaces must be config-gated (`/api/v1/configs/`), never assumed.
- Streams must survive backgrounding only with an FGS lease; otherwise reconcile on resume — never silently drop live text.