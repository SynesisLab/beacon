# Beacon Feature ↔ Code Map (2026-11-06)

Every user-facing surface, its screen/VM files, its wire endpoints, and the
data flow behind it. Read alongside `CLAUDE.md` (OWUI feature audit) and
`docs/protocol-notes.md` (wire shapes with proof). Paths are
`app/src/main/kotlin/dev/beacon/…`.

## 1. Navigation (MainActivity.kt)

| Route | Surface | File |
| --- | --- | --- |
| `onboarding` | instance + sign-in + MFA + model pick | `feature/onboarding/OnboardingScreen.kt` + `OnboardingViewModel.kt` |
| `chat/new` | fresh chat (app start destination when onboarded) | `feature/chat/ChatScreen.kt` |
| `chat/{chatId}` | open chat (branch = current leaf) | `feature/chat/ChatScreen.kt` |
| `settings` | account, appearance, sign-out, Advanced row | `feature/settings/SettingsScreen.kt` + `SettingsViewModel.kt` |
| `settings/headers` | custom HTTP headers editor (ADR-0012) | `feature/settings/HeadersEditorScreen.kt` + `HeadersViewModel.kt` |
| `surface/{key}` | key = `notes` / `memories` / `channels` / `files` / `workspace` | `feature/surfaces/*` (dispatch in `MainActivity.SurfaceRoute` :344) |
| `folder/{id}/{name}/chats` | chats inside a folder | `feature/surfaces/FolderChatsScreen.kt` |
| `note/{id}`, `note/new` | note editor (read/merge update) | `feature/surfaces/SurfaceWriteScreens.kt` |
| `channel/{id}/{name}` | channel chat stream | `feature/surfaces/ChannelChatScreen.kt` |
| `file/{fileId}/{name}/{mime}?server&path` | native file viewer (+ terminal files) | `feature/chat/FileViewerScreen.kt` + `FileViewerViewModel.kt` |
| `terminal` | terminal surface (files tab, listing/CWD/read/upload) | `feature/terminal/TerminalScreen.kt` + `TerminalUi.kt` |

Entry chrome: `feature/chat/BeaconTabBar.kt` (bottom tabs Chat / Folders /
Notes / Files — Folders = chat home; Files tab routes to `terminal`),
`feature/chat/ChatsDrawer.kt` (recents + gated
drawer rows Memories/Channels + fixed trio Workspace/Terminal/Settings),
share-target & `dev.beacon.NEW_CHAT` shortcut → `chat/new`
(`MainActivity.handleShareIntent`, §9.3). Nav transitions: calm fade+slide,
predictive-back scale (MainActivity :130-157).

## 2. Onboarding & auth

Screen states: instance URL+version probe → sign-in → MFA challenge
(TOTP/recovery toggle) → enroll (QR svg data-URI via JS-off WebView, manual
key, recovery codes) → model pick.
- `GET /api/version`, `GET /api/config` (`OwuiApi.version/config`)
- `POST /api/v1/auths/signin` (`OwuiApi.signIn`) → `challenge_token` when MFA
- `POST /api/v1/auths/mfa/verify` `{challenge_token, code, recovery}` (`verifyMfa`, mfa.py:147)
- `POST /api/v1/auths/mfa/enroll/start` `{challenge_token}` → `{manual_key, qr_code}` (mfa.py:129-134)
- `POST /api/v1/auths/mfa/enroll/confirm` → session + one-time `recovery_codes` (auths.py:146-148)
- `GET /api/v1/auths/` (session user, `sessionUser`), `GET /api/v1/models/list?page=1` (`models`)
- MFA `/recover` = admin reset token only — deliberately a typed message.
- Sign-out = **client-side** secure wipe (`SessionStore.signOut`, SessionStore.kt:148) — no server call. Any credentialed 401 (with `/api/config` still 200) sets `session.authExpired` → banner → sign-out (`core/network/Interceptors.kt`).

## 3. Chat surface (the core)

`feature/chat/ChatScreen.kt` + `ChatViewModel.kt` → intents (`send`,
`stop`, `retry`, `regenerate`, `edit…`, `SetBranch`, `resolve`, `approve`) →
`core/chat/ChatEngine.kt` (owns transport + reconcile) → `StateFlow<ChatUiModel>`
→ `feature/chat/MessageBubble`-family render. UI never touches the stream.

- Streaming: `POST /api/chat/completions` (SSE via `core/sse/SseFrameScanner`
  + `OwuiStreamParser`; jsonCompletion fallback for plain-JSON bodies)
- Post-stream cleanup: `POST /api/chat/completed` (inline-filter aware)
- Stop: `POST /api/tasks/chat/{chatId}/stop`
- Edit message: `GET/POST /api/v1/chats/{id}/messages/{messageId}` (`updateMessageContent`, OwuiApi.kt:267)
- Rating: `POST /api/v1/evaluations/feedback` (`rate`, OwuiApi.kt:281)
- ask_user / tool approval resolve: `POST /api/v1/chats/{id}/messages/{messageId}/resolve` (`resolveToolCall`, OwuiApi.kt:336; tool_approval.py:16-20) — dialogs: `InputPromptDialog.kt`, `ToolApprovalDialog.kt`
- New chat create-first: `POST /api/v1/chats/new` (`ChatsApi.create`, Chat.svelte:4829-4847 parity); else chat:list discovery
- Tree fetch/save: `GET /api/v1/chats/{id}` (full tree), `POST /api/v1/chats/{id}` (full-tree save — the fork's parent-preserving save), `DELETE /api/v1/chats/{id}`
- Lists: `GET /api/v1/chats/?page=N`, `GET /api/v1/chats/pinned`, `POST /api/v1/chats/{id}/pin|archive` (`ChatsApi`)
- Share/export: `GET /api/v1/chats/{id}/share` (`shareChat` → `{origin}/s/{share_id}` + Android chooser; Markdown/JSON copy is client-built)
- Title/tags/context: room events (below) + `GET /api/v1/chats/{id}` `context_usage` → context meter
- Composer: chips (Web/Image/Code) from the defaults chain (`GET /api/v1/users/user/settings` → `defaultFeatureIds` own → `GET /api/v1/configs/models/defaults` → model caps; `OwuiApi.userSettings/globalDefaultFeatureIds`); mic → `core/speech/VoiceInput.kt` (`POST /api/v1/audio/transcriptions`, AudioApi.kt:87); TTS `core/speech/TtsPlayer.kt` (`POST /api/v1/audio/speech`, `GET /api/v1/audio/models`)
- Files/attachments: `core/data/UploadQueue.kt` + `FilesApi` (`GET /api/v1/files/?process=`, `GET /api/v1/files/{id}/content`, display_file instances → `FileViewerScreen`)

### Rendering pipeline (§4 contract)

`core/render/ChatPart.kt` — sealed `ChatPart` { Markdown, Reasoning, ToolCall,
HtmlCard(kind: Artifact/Mermaid/Chart/Embed), File, Citations, Status,
GeneratedImage, FollowUps } + `DetailRun` grouping. Sources: streaming
segmenter `core/render/Segmenter.kt` (markdown→parts; reasoning/code
`<details>` lift) and stored output folds `core/render/PartBuilder.kt`
(`function_call` / `function_call_output` + `embeds` sibling, paired outputs
by call_id, unwrap input_text parts, pending ask_user hidden, message.embeds).
Display: `feature/chat/PartsView.kt` (`RenderPart`, `DetailGroupRow` = web
parity "✓ Explored …" / "Exploring …" rows), bubbles `MessageBubble`+
`MiniMarkdown.kt`. Web display/hide rules table: protocol-notes.md (2026-11-04).

### Inline HTML cards (M2-2)

`core/render/CardDocument.kt` builds each card's WebView doc (foreign docs =
card HTML + theme seed at parse-position zero + `ui.iframe_csp`; synthetics =
mermaid/chart/KaTeX vendored docs; height shim beacon-bridge). Pane:
`feature/chat/HtmlCardPane.kt` — per-card WebView, touch hand-off (vertical
drags → chat scroll), popups→policy, height = CSS-px-as-dp + stability measure
loop, `isAlgorithmicDarkeningAllowed=false`, debug CDP enabled. Policy:
`core/render/policy/EmbedPolicy.kt` (+ ADR-0013/0014). Theme bake/probe:
`core/network/socket/ExecuteSandbox.kt` (`execute` event_call, bridge-resolved
answers, seeded scheme).

### Version tree / branches

`core/chat/BranchPath.kt` + engine (`regenerate` → sibling under same user
turn; pager `‹ n/N ›` beside ↻; flick-to-refetch reconciles via
`core/chat/ChatReconciler.kt`; foreground lease `StreamingLeaseService.kt`).
Persistence: `history.messages` Map<id, Msg{parentId, childrenIds}> — Room
tables mirror it (`core/data/local/ChatEntities.kt`, `ChatDao.kt`).

## 4. Drawer surfaces (M4)

Shared VM: `feature/surfaces/SurfaceViewModel.kt` (list load + gates) +
`SurfaceWriteScreens.kt` (write flows). Gate truth = `GET /api/config`
features flags (ui.* / channels / memories) — hide, don't crash.

| Surface | Reads | Writes |
| --- | --- | --- |
| Folders (chat home) | `GET /api/v1/folders/`, chats list | `POST /api/v1/folders/` (create), `/update`, `DELETE /api/v1/folders/{id}`, `GET /api/v1/chats/folder/{id}` (`WorkspaceApis`) |
| Notes | `GET /api/v1/notes/`, `GET /api/v1/notes/{id}` (NoteDoc) | `POST /api/v1/notes/create`, `/update` (content merge), `/delete`, `/pin` (WorkspaceApis:149-208; ns-timestamps) |
| Memories (gated) | `GET /api/v1/memories/` | `add`, `/{id}/update`, `DELETE /{id}`, `DELETE /delete/user` (wipe) |
| Channels (gated) | `GET /api/v1/channels/`, `GET /api/v1/channels/{id}/messages` | `POST /api/v1/channels/{id}/messages/post`; live `events:channel` room refetch (`OwuiSocket` + debounced quiet refetch) |
| Files | `GET /api/v1/files/?page=1&content=false` | open → native viewer / terminal server rows |
| Workspace | `GET /api/v1/prompts/`, `/tools/`, `/knowledge/` (read-only today; tool-servers listing) | — (model editor = M4+ view-only first) |
| Terminal | `GET /api/v1/terminals/` (server id) | `read/list/upload` via `TerminalsApi` (`/api/v1/terminals/{id}/{path}?…`, `?directory=` upload query; ALWAYS single-slash) |

## 5. Socket layer (M4-d3)

`core/network/socket/OwuiSocket.kt` — Socket.IO client, **websocket-only**
path `/ws/socket.io` (polls = 400), handshake `auth:{token}`, emitted
payloads MUST be org.json (nested-map toString gotcha).

- Rooms joined: `events:chat` (per open chat), `events:channel` (open channel), user-join (`emitUserJoin`)
- Server events folded (events + chat:* legacy): OpenAI-style `response.*` family (`response:completion`, `response.completed/failed/incomplete`, output-item snapshots incl. `function_call(_output)`, reasoning deltas) → `core/sse/OwuiStreamParser.kt`; `chat:message:follow_ups`, `chat:title`, `chat:tags`, `chat:list` (id discovery), `chat:message:error`, `chat:message:embeds`/`embeds`
- `event_call` envelope: `execute` → `ExecuteSandbox` (theme probes; replies in <2.5 s against the tool's 3 s window via the ClientExec bridge); `execute:python`/`execute:tool`/`request:*` answered not-supported; ack = `SocketEnvelope.reply`
- `ChatSocket.kt` — injectable engine-facing seam (no-op default → real socket when connected)

## 6. Settings & data

- `feature/settings/SettingsScreen.kt`: Appearance (System/Light/Dark → `ThemeMode`), sign-out, Advanced → headers
- `feature/settings/HeadersEditorScreen.kt` (+ CustomHeaders.kt rules: RFC tokens, Authorization reserved, CR/LF/NUL stripped, same-origin only — `attachInstanceAuth` in Interceptors) — blob in SecureStore alias `custom_headers`
- `core/data/SessionStore.kt` — instance creds in **Keystore** (SecureStore.kt) + DataStore settings; wipe on sign-out
- `core/data/ChatRepository.kt` — Room-first (§10): every screen reads Room; network writes through; partsJson normalized so UI = f(DB)

## 7. Design system & visual identity

- `ui/theme/{Color,Theme,Type,Paddings}.kt` — measured off the reference UI (bg #F9F9F7, ink #131313, coral #D97757, drawer #F3F3F0, icons #817F7A); `LocalBeaconDark` compositionLocal; scheme carried into drawer + cards
- `feature/chat/LandingCritter.kt` — pixel-grid critter (beacon-blue body, red heart) on the empty-chat hero; launcher icon = same mark
- `feature/chat/BeaconTabBar.kt` — bottom tab bar (user decision: Chat/Folders/Notes/Files; NOT the desktop tab bar)
- Screenshot goldens (Roborazzi): `app/src/test/kotlin/**/*ScreenshotTest.kt` → `recordRoborazziDebug` / `verifyRoborazziDebug`

## 8. Wire endpoint index (one page)

Auth/session: `/api/version`, `/api/config`, `/api/v1/auths/signin`,
`/api/v1/auths/mfa/{verify,enroll/start,enroll/confirm}`, `/api/v1/auths/`,
`/api/v1/users/user/settings`
Models/defaults: `/api/v1/models/list?page=1`, `/api/v1/configs/models/defaults`, `/api/v1/configs/` (gates)
Completions: `POST /api/chat/completions`, `POST /api/chat/completed`, `POST /api/tasks/chat/{id}/stop`
Messages: `GET,POST /api/v1/chats/{id}`, `POST …/messages/{messageId}` (edit), `…/resolve` (tool/ask), `POST /api/v1/evaluations/feedback`
Chats: `GET /api/v1/chats/?page=N`, `/pinned`, `POST /new`, `DELETE /{id}`, `/{id}/pin|archive|share`, `GET /chats/folder/{id}`
Files: `GET /api/v1/files/?page=1&content=false`, `/?process=`, `GET /{id}/content`
Audio: `GET /api/v1/audio/models`, `POST /speech`, `POST /transcriptions`
Workspaces: folders / notes / channels / memories / prompts / tools / knowledge (§4 table)
Sockets: `wss://…/ws/socket.io` (websocket-only) — rooms `events:chat`, `events:channel` (+user-join); `event_call` execute → sandbox

**Known gaps (deliberate, see CLAUDE.md audit):** composer feature toggles
wire-ready but no UI (M3), context meter = show-when-sent, follow-up task
autocomplete (M4+), workspace write flows (M4+ model editor), share "allow
replies" flag, MCP-app frames confirm dialogs (M5+), widget/app-lock/proxy
capture (M5 remainder).

## 9. Mapping rules for new UI work

1. New screens route in `MainActivity` + a `feature/<name>/` package; list surfaces reuse `surface/{key}` + gated rows in the drawer.
2. New wire shapes → **fixture + test first** (§13; real SSE transcripts under `app/src/test/**/fixtures/**`), file:line proof in `docs/protocol-notes.md`.
3. New render output → extend `ChatPart` + both producers (Segmenter + PartBuilder) + goldens, never a bubble-only branch.
4. Persistence stays derivational (Room mirrors the tree; partsJson normalized).
5. Gates first: any surface that needs a server flag reads `GET /api/config` state and hides when absent.

## 10. UI control → feature map (2026-11-06)

Companion to `beacon-ui-design.md` (v1.0 — look/geometry/behavior). This
section is the authoritative **visible control → owning feature + wire**
table. New UI controls must add a row here and, when they imply new wire
shapes, a fixture per §9.2. "M" milestones follow §8 known-gaps.

### 10.0 v2 amendments (simplified nav, 2026-11-06)

Per `beacon-ui-design.md` v2.0 — rows in 10.1–10.13 are superseded where
listed here:

| Change | v1 | v2 (authoritative) |
| --- | --- | --- |
| Tab bar (10.1) | Chat / Folders / Notes / Files | **Chats / Notes / Files / More** — `Chat` tab retired (new chat = FAB + share-target/shortcut); old Folders home *is* the start destination |
| Drawer (10.5, entire table) | ChatsDrawer | **deleted** — recents live once, on the Chats home; trio + gated rows = **More tab list** |
| ☰ hamburger bubble (10.1) | chat top-left | **removed** — top bar is `[‹ back] [title/model chip ⌄] [temp bubble]`; back → Chats home |
| Composer chips (10.2) | chip row under the input | **single-row composer**; Web/Image/Code toggles live only in the ⊕ sheet (10.4) — one state, one home |
| Landing suggested cards | 2-up cards | ≤3 compact chips, hidden when no featured prompts |
| Onboarding (10.13) | 5 steps | **one page** (connect + sign-in + inline MFA on the same card); model-pick step cut (first model = default, change via top chip); 2FA enroll deferred → one-time nudge card on Chats home → enroll sheet |
| Turn footer (10.3) | inline ↻ · pager · copy · 👍👎 · 🔊 | **`↻` · `‹n/N›` · `⋯`** — copy / read-aloud / rating / export / edit / delete behind `⋯` sheet (turn long-press = same sheet) |
| Files (10.10) | segmented panes Uploads\|Terminal | **one list + source chips** (`Uploads`, per-terminal chip); breadcrumb only when a terminal source is active |
| Settings (10.12) | 6 groups | **3 groups**: Account (incl. sign-out) / Appearance & voice / Advanced; language + font-size rows cut (follow system) |
| Inline HTML cards (10.3 `⤢`) | framed card pane w/ header | **Chart / Mermaid / KaTeX synthetics render borderless** at full column width (content scales, no inner scroll, tap = ⤢ fullscreen, long-press = copy source / export PNG); framed chrome (border·title·⋯) **only** for foreign `Embed` kind |
| **Tool inline cards · Frontier-UI pattern (§11.3b, v2.2)** | — | pane-first via HtmlCardPane + beacon-bridge: consumes `iframe:height` (height shim), `input:prompt:submit` → **pre-fills composer**, links → **Custom Tabs intent** (native = no sandbox → the tool's COOP copy-link workaround is dead weight) · **any block wider than the column scrolls left↔right in-pane** (`overflow-x` + `scroll-snap-x` for card/gallery rows, free-pan for wide canvases), edge-fade mask, ⤢ for fullscreen real estate · structured-`embeds` fallback = native block kit M5 |

### 10.1 Global chrome

| Control | Where | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- | --- |
| ☰ hamburger bubble | chat top-left | open ChatsDrawer | §1 ChatsDrawer.kt | live |
| (long-press ☰) | same | new chat | `chat/new` route §1 | live |
| context chip (model ⌄ / title) | chat top-center | Chat options sheet | §3 lists, chat:{id} | live |
| └ Chat options → title field | sheet | rename (client title save) | POST /api/v1/chats/{id} §3 | live |
| └ Chat options → model row | sheet | model picker sheet | GET /api/v1/models/list?page=1 §2 | live |
| └ Chat options → folder row | sheet | folder picker → group chat | folders list §4 (+ tree save §3) | live |
| └ Chat options → Share | sheet | share endpoint → Android chooser | GET /api/v1/chats/{id}/share §3 | live |
| └ Chat options → Export | sheet | client-built Markdown/JSON copy | §3 (client only) | live |
| └ Chat options → Pin/Archive/Delete | sheet | chat ops | POST /{id}/pin\|archive, DELETE /{id} §3 | live |
| dashed temp-chat bubble | chat top-right | temporary chat mode (ephemeral session, not persisted) | M5 gap §8; client-side only | M5 |
| **Tab bar: Chat** | bottom | chat/new | §1 BeaconTabBar | live |
| **Tab bar: Folders** | bottom | folders = chat home | §4 folders row | live |
| **Tab bar: Notes** | bottom | notes surface | §4 notes row | live |
| **Tab bar: Files** | bottom | files+terminal surface | §4 files / terminal rows | live |
| Re-auth banner action | under top bar on 401 | sign-out → sign-in step | session.authExpired §2/§6 Interceptors | live |
| Offline pill | under top bar | informative; surfaces read Room | §5 socket state | live |

### 10.2 Composer

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| text field + **send pill ↑** | send message | POST /api/chat/completions (SSE) §3 | live |
| send pill ⇄ **waveform** (text empty) | hold-to-talk / voice overlay | VoiceInput: POST /api/v1/audio/transcriptions §3 | live |
| 🎤 mic glyph | keyboard voice input | VoiceInput §3 | live |
| **stop square** (while streaming) | stop generation | POST /api/tasks/chat/{chatId}/stop §3 | live |
| ⊕ | Add-to-chat sheet (10.4) | UploadQueue + FilesApi §3 | live |
| **Web chip** | web-search feature toggle for next send | defaultFeatureIds chain §3; flag in completions payload | M3 (wire-ready, no UI) |
| **Image chip** | image-generation feature toggle | same chain §3 | M3 |
| **Code chip** | code-interpreter feature toggle | same chain §3 | M3 |
| **context rail + "18k/200k"** (appears after send) | usage display popover | GET /api/v1/chats/{id} `context_usage` §3 | show-when-sent (§8) |
| attachment chips ✕ | remove pending upload | UploadQueue §3 | live |

### 10.3 Message canvas

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| `✓ Explored N sources ⌄` row | expand/collapse detail run | client render (DetailRun §3) | live |
| `Thought for 4s ⌄` | collapse/expand reasoning | client render (Reasoning part §3) | live |
| tool-call row card | inline card | client render (ToolCall part §3) | live |
| HTML card **⤢** | fullscreen card | routes FileViewer-style fullscreen; pane §3 M2-2 | live |
| HTML card ⋯ (open externally where policy allows) | open in browser | EmbedPolicy ADR-0013/0014 §3 | live |
| generated image tap / long-press | viewer / save | FileViewer §1, GET /api/v1/files/{id}/content §3 | live |
| citation chip `[n]` | source peek | Citations part §3 (client) | live |
| follow-up chip | send as next message | same completions path §3 | live |
| **↻ retry** | regenerate → new sibling branch | core/chat engine §3 ver-tree | live |
| **‹ n/N › pager** | cycle sibling branches | BranchPath §3 | live |
| 👍/👎 | rate assistant turn | POST /api/v1/evaluations/feedback §3 | live |
| copy | clipboard (client-built md/plain) | client | live |
| 🔊 | read turn aloud | POST /api/v1/audio/speech + TtsPlayer §3 | live |
| ✎ edit (user turn, long-press sheet) | inline editor → save | GET/POST /api/v1/chats/{id}/messages/{messageId} §3 | live |
| branch-from-here (user long-press) | SetBranch → leaf refetch | engine §3 | live |
| delete turn (user long-press) | tree delete → full-tree save | DELETE/POST chat §3 | live |
| ask_user dialog **Submit** | answer tool prompt | POST /{id}/messages/{id}/resolve (ask) §3 | live |
| tool approval **Allow / Always / Deny** | resolve approval | POST …/resolve (tool_approval) §3 | live |
| Resume pill (stalled stream) | foreground lease resume | StreamingLeaseService §3 | live |

### 10.4 Add-to-chat sheet (⊕)

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| Camera tile | capture → upload → attach | POST /api/v1/files/ (UploadQueue §3) | live |
| Photos tile | picker → upload → attach | same | live |
| Files tile | SAF document upload | same | live |
| Attach Knowledge row | knowledge item → attached context | GET /api/v1/knowledge/ §4 | live |
| Attach Notes row | note → attached/reference context | GET /api/v1/notes/ §4 | live |
| Attach Webpage row | URL fetch → upload | FilesApi §3 | M5 |
| Add to folder row | pick folder for this chat | folders §4 + tree save §3 | live |
| Web/Image/Code toggles | same flags as 10.2 chips | §3 defaults chain | M3 |

### 10.5 Drawer (ChatsDrawer §1)

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| search field | filter cached chats | Room cache; server search M5 (§8) | partial |
| recents rows / date groups | open chat | GET /api/v1/chats/?page=N §3 | live |
| row **⋯ actions** | Pin / Archive / Move / Share / Export / Delete | pin\|archive §3, share §3 | live |
| Memories row (gated) | memories surface | GET /api/v1/memories/ §4; gate = /api/config §4 | live |
| Channels row (gated) | channels surface | GET /api/v1/channels/ §4; gate | live |
| **Workspace** row | workspace surface (read-only) | prompts/tools/knowledge §4 | live (read) |
| **Terminal** row | terminal surface | terminals §4 | live |
| **Settings** row | settings route | §6 | live |
| avatar row / role badge | account → settings | GET /api/v1/auths/ §2 | live |
| **+ New chat** pill | chat/new | §1 | live |

### 10.6 Folders (chat home) & folder chats

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| **+** header (new folder) | create dialog | POST /api/v1/folders/ §4 | live |
| folder row | open folder chats | GET /api/v1/chats/folder/{id} §4 | live |
| folder ⋯ | rename/update/delete | POST /update, DELETE /{id} §4 | live |
| pinned section rows | open pinned chats | GET /api/v1/chats/pinned §3 | live |
| chat row ⋯ | row-actions sheet (10.5 set) | §3 chat ops | live |
| **+ New chat FAB** | chat/new | §1 | live |

### 10.7 Notes

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| note row | open editor | GET /api/v1/notes/{id} §4 | live |
| **+** header / empty state | new note editor | note/new route §1 | live |
| editor save (debounced) | create or merge-update | POST /create / POST update §4 | live |
| pin star | pin note | POST /api/v1/notes/{id}/pin §4 | live |
| kebab copy/delete | client copy / POST delete §4 | §4 | live |

### 10.8 Channels (gated)

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| channel row | open stream | GET /api/v1/channels/{id}/messages §4 | live |
| 🟢 live dot | recent activity indicator | events:channel room §5 | live |
| channel composer send | post message | POST /api/v1/channels/{id}/messages/post §4 | live |
| header + (admin only) | create channel | gated; hide otherwise | M5 |

### 10.9 Memories (gated)

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| + add field | add memory | POST /api/v1/memories/add §4 | live |
| card ⋯ update/delete | per-item ops | /{id}/update, DELETE /{id} §4 | live |
| wipe-all row | destructive confirm | POST /api/v1/memories/delete/user §4 | live |

### 10.10 Files tab / Terminal / FileViewer

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| segmented [Uploads\|Terminal] | switch pane | files §4 / terminal §4 | live |
| uploads row tap | open viewer | GET /api/v1/files/{id}/content §1/§3 | live |
| uploads row ⋯ | open/save/delete | FilesApi §3 | live |
| **server chip** | pick terminal server | GET /api/v1/terminals/ §4 | live |
| breadcrumb cwd / ⌃ up / ↻ | navigate list | read/list paths (single-slash!) §4 | live |
| file row tap | read → viewer | GET /api/v1/terminals/{id}/read §4 | live |
| file row ⋯ | copy path / open | client | live |
| **Upload FAB** → Camera/Files sheet | upload to cwd | POST upload `?directory=` §4 | live |
| FileViewer overflow | save to device / copy path / open terminal | client | live |

### 10.11 Workspace (read-only)

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| segmented [Prompts\|Tools\|Knowledge] | swap list | GET /api/v1/{prompts,tools,knowledge}/ §4 | live |
| row tap | detail sheet (body/spec/files) | §4 | live |
| detail copy | clipboard | client | live |
| tool-servers footer section | listing | tool-servers read §4 | live |
| edit/create affordances | — deliberately absent | write flows M4+ (§8) | M4+ |

### 10.12 Settings & Headers editor

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| Account → instance URL row | re-probe instance | GET /api/version + /api/config §2 | live |
| Account → session row | show user info | GET /api/v1/auths/ §2 | live |
| Appearance → color mode trio | ThemeMode System/Light/Dark | ThemeMode §6 | live |
| font size row / haptics toggle | local prefs | DataStore §6 | live |
| Voice → TTS picker | select voice | GET /api/v1/audio/models §3 | live |
| auto-read toggle | TTS on completion | TtsPlayer §3 | live |
| language row | UI locale | client | live |
| Chat defaults → default model row | model sheet reuse | models chain §3 | live |
| Advanced → **Custom HTTP headers** | headers editor route | ADR-0012 §6 | live |
| header rule toggle / add / delete | edit rules (RFC-token, CR/LF/NUL strip, Authorization reserved, same-origin) | CustomHeaders.kt §6 | live |
| Save (editor) | persist blob | SecureStore `custom_headers` §6 | live |
| **Sign out** (confirm sheet) | client-side wipe only | SessionStore.signOut §2/§6 | live |
| About rows | versions/docs | version probe §2 | live |

### 10.13 Onboarding

| Control | Acts on | Feature § / wire | Milestone |
| --- | --- | --- | --- |
| URL field + Continue | probe instance | GET /api/version, GET /api/config §2 | live |
| Sign-in submit | authenticate | POST /api/v1/auths/signin §2 | live |
| OTP cells (TOTP) / recovery code link | verify MFA | POST /api/v1/auths/mfa/verify §2 | live |
| enroll QR (WebView svg) / manual key | start enroll | POST /api/v1/auths/mfa/enroll/start §2 | live |
| "I've saved them" + Done | confirm → session + codes | POST /api/v1/auths/mfa/enroll/confirm §2 | live |
| model pick list + Done (skippable) | set default model | GET /api/v1/models/list §2 | live |
| MFA /recover row | typed admin-reset message | §2 (intentional dead-end) | live |

### 10.13b v3 amendments (2026-11-06, sidebar nav — second pass)

| Change | v2 | v3 (authoritative) |
| --- | --- | --- |
| Navigation shell | `Chats/Notes/Files/More` tab bar + Chats home tab | **Tab bar removed** — primary nav = **left slide-in sidebar** (§10.14), mimicking Claude's drawer × OWUI's web sidebar. **Flagged for veto** (reverses the BeaconTabBar user decision of §7) |
| Chat top-left | `‹ back` | **hamburger bubble** again (every screen) — opens the sidebar; Android back still pops routes (predictive-back scale) |
| Drawer (10.5, deleted in v2) | — | **resurrected** with the richer sidebar anatomy: nav rows + pinned/folders/date-grouped recents + bottom identity bar (avatar · role badge · black ＋ New chat pill) |
| More tab / More list (v2) | everything-else list | **deleted** — its rows (Workspace / Memories* / Channels* / Settings) are sidebar rows now |
| Models in sidebar | — | deliberately absent: top context chip → model sheet stays the single source of truth for switching |

### 10.14 Sidebar (v3) — control → feature map

| Control | Acts on | Feature § / wire | Status |
| --- | --- | --- | --- |
| wordmark + version caption | informative; tap → re-probe instance | GET /api/version §2 | live |
| **search chats field** | live filter of cached chats; server search later | Room cache; GET /api/v1/chats/search §3 = M5 (§8) | partial |
| **Chats row** | chats home (start destination) | surface §4 folders + §3 chat lists | live |
| **Notes row** | notes surface | §4 notes | live |
| **Files row** | files surface (Uploads + terminal sources) | §4 files/terminal | live |
| **Workspace row** | workspace surface (read-only v1) | §4 prompts/tools/knowledge | live (read) |
| **Memories row** | memories surface | GET /api/v1/memories/ §4 | live (gated) |
| **Channels row** | channels surface | GET /api/v1/channels/ §4 | live (gated) |
| **Settings row** | settings (+ headers editor) | §6, ADR-0012 | live |
| pinned rows (coral ✦) | open pinned chats | GET /api/v1/chats/pinned §3 | live |
| folders rows (name · count) | open folder chats | GET /api/v1/chats/folder/{id} §4 | live |
| ＋ on Folders header | create folder | POST /api/v1/folders/ §4 | live |
| date-grouped recents | open chats, paged | GET /api/v1/chats/?page=N §3 | live |
| row ⋯ / long-press | shared row-actions sheet (pin/archive/move/share/export/delete) | §3 chat ops | live |
| **avatar row** (initials · name · role badge) | account → settings | GET /api/v1/auths/ §2 | live |
| **＋ New chat pill** (bottom-right, black) | chat/new | §1 | live |
| hamburger bubble / edge-swipe / scrim tap / back | open-close sidebars | §1 chrome; predictive-back scale | live |

## 11. Complete Open WebUI feature inventory → Beacon (v2, 2026-11-06)

> **v3 note:** §10.4–§11 "tab" phrasing now reads as routes/surfaces
> reached from the **left slide-in sidebar** (§10.14) — the v2 tab bar and
> the More list were removed. Wire endpoints, files and statuses are
> unaffected.

The full OWUI surface mapped. Where it lands in the v2 UI, the wire,
and status. `web-only` = deliberate native exclusion — the desktop UI
keeps it, the phone doesn't pretend. All endpoint shapes still obey
§9.2 (fixture + proof first).

### 11.1 Auth & session

| OWUI feature | Wire | Beacon v2 home | Status |
| --- | --- | --- | --- |
| Sign in (email/password) | POST /api/v1/auths/signin | onboarding page | live |
| OAuth provider buttons | `/api/config` oauth block | onboarding provider rows when present | M5 |
| MFA verify (TOTP / recovery toggle) | POST /auths/mfa/verify | inline cells on the sign-in card | live |
| MFA enroll (QR svg / manual key) | POST /auths/mfa/enroll/start+confirm | deferred enroll sheet, one-time Chats-home nudge | live (deferred) |
| Recovery codes (one-time) | enroll/confirm | same sheet, "I've saved them" gate | live |
| MFA reset | `/recover` = admin reset token | typed dead-end message | live |
| Session user / profile | GET /api/v1/auths/, users/user/settings | Settings account row | live |
| Roles | user.role | role badge chip (admin) | live |
| Sign out | OWUI server-side; Beacon = client wipe only | Settings danger row (+confirm sheet) | live |
| 401 expiry | interceptors → `session.authExpired` | auth-expired banner → Re-auth | live |
| Personal API keys | /api/v1/users/ keys UI | — | web-only |

### 11.2 Chats

| OWUI feature | Wire | Beacon v2 home | Status |
| --- | --- | --- | --- |
| Create (create-first) | POST /api/v1/chats/new | first send on empty chat; FAB/shortcut | live |
| Tree get/save | GET/POST /api/v1/chats/{id} | engine core §3 | live |
| List pages | GET /api/v1/chats/?page=N | Chats home date groups | live |
| Pin / Archive / Delete | /pin, /archive, DELETE | row-actions sheet (one shared sheet) | live |
| Server-side search | GET /api/v1/chats/search | home search field | M5 (Room filter today) |
| Tags |room chat:tags, chats/{id}/tags | chips under title + filters | M5 (title live) |
| Auto title | room chat:title | top context-chip text | live |
| Folders CRUD + folder chats | /api/v1/folders/*, /chats/folder/{id} | Chats home Folders section (+ header ＋) | live |
| Move to folder | folderId via full-tree save | row-actions "Move" | live |
| Share link (+allow replies) | GET /{id}/share → chooser | row-actions | live (allow-replies M5) |
| Export Markdown/JSON | client-built | row-actions | live |
| Export PDF/XLSX | web renderer | — | web-only |
| Import chat JSON | POST /api/v1/chats/ import | — | web-only |
| Temporary chat | client-ephemeral | dashed top-right bubble + "Temporary" chip | M5 |
| Context usage | GET {id} `context_usage` | composer rail (show-when-sent §8) | live |

### 11.3 Completions & message parts

| OWUI feature | Wire | Beacon v2 home | Status |
| --- | --- | --- | --- |
| Streaming completion | POST /api/chat/completions (SSE) | composer send | live |
| Plain-JSON fallback | jsonCompletion | engine | live |
| Post-stream hook | POST /api/chat/completed | engine (inline-filter aware) | live |
| Stop | POST /api/tasks/chat/{chatId}/stop | composer stop square | live |
| Feature flips (web/image/code) | defaultFeatureIds chain → payload flags | ⊕ sheet toggles | M3 (wire-ready) |
| Reasoning | reasoning deltas / details lift | "Thought for 4s" collapse | live |
| Tool calls + paired outputs | function_call(+output) + embeds, resolve | tool rows + approval dialog | live |
| ask_user | resolve (ask) | prompt dialog | live |
| Citations | sources blocks | `​[n]` chips, long-press source | live |
| Follow-ups | room follow_ups | chip row under last turn | live |
| Ratings | POST /api/v1/evaluations/feedback | 👍👎 in ⋯ turn sheet | live |
| Regenerate / sibling branches | engine + BranchPath | `↻` + `‹n/N›` pager | live |
| Edit message | GET/POST /messages/{messageId} | user-turn long-press → inline editor | live |
| **Inline HTML cards — synthetics (artifact/Chart/Mermaid/KaTeX)** | CardDocument + HtmlCardPane §3 M2-2 | **borderless, full column width, content scales (SVG 100% / Chart.js responsive), no box/header, no inner scroll · tap = ⤢ fullscreen · long-press = copy source / export PNG** | live (render rule v2.1) |
| Inline HTML cards — foreign Embed | iframe CSP policy ADR-0013/0014 | framed pane (border · title · ⤢ · ⋯) | live |
| **Tool inline cards — Frontier UI (§11.3b)** | tool returns `(HTMLResponse, summary)`; OWUI renders it in a sandboxed iframe (`allow-scripts`, no escape-sandbox → COOP link refusals → the card's copy-link sheet fallback) | HtmlCardPane + beacon-bridge: `​iframe:height` postMessage → pane height; `input:prompt:submit` → composer prefill; links → Custom Tabs (native, no COOP problem); theme = existing bake/probe | live (pane) · M3 (bridge verbs) |
| **Oversize rendering — ↔ rule** | card's own CSS: `.gal.scroll` / `.cards` / `.tbl` = `overflow-x:auto` + `scroll-snap-type:x mandatory` | **blocks wider than the column never shrink to unreadable — they scroll left↔right in-pane** (snap rows for galleries/place-cards, free-pan for wide tables/SVG), edge-fade mask, ⤢ fullscreen for real estate | live (in-card CSS) · M5 (native-table fallback) |
| Generated images | embeds/output images | image part → viewer/save | live |
| Attached files in stream | /files content + display_file | file part → FileViewer | live |
| Call mode (hands-free voice) | audio transcriptions+speech loop | waveform pill long-press → Voice overlay call UI | M5 |

### 11.3b Tool inline cards — the Frontier UI contract (source-read 2026-11-06)

Anthony exported the `render_frontier_ui` tool (v1.3.0) as the reference
for how OWUI does inline tool rendering. The wire truth, and what Beacon
adopts vs. ignores:

| Mechanism in the tool source | Beacon v2 handling |
| --- | --- |
| Tool returns `(HTMLResponse(html), "card id …")` — OWUI renders the HTML inline in a **sandboxed iframe**; on desktop, external links hit COOP refusals (`ERR_BLOCKED_BY_RESPONSE`) and the card degrades to a copy-link sheet | Render through the existing **HtmlCardPane** (§3 M2-2). Native WebView isn't a sandboxed browser tab: links go **straight to a Custom Tabs intent / OS handler** — Gmail, maps, calendars all resolve. The copy-link sheet stays dormant (it's web-hostage code) |
| `postMessage({type:'iframe:height'})` + `ResizeObserver(body)` — the parent sizes the iframe to content | **beacon-bridge height shim already speaks this** — pane height = reported, capped at ~60% viewport, then internal vertical scroll (autoscroll-pause mirrors the chat list) |
| Theme: `execute` handshake probe + parent-class MutationObserver (`data-theme` bake) | Existing ExecuteSandbox bake/probe — unchanged |
| Buttons post `{type:'input:prompt:submit'}` (Copy/Email/Export/Save actions + `followups`) → OWUI fills the chat input | bridge maps `input:prompt:submit` → **compose prefill** (+ affordance to send). Native actions (share sheet) can shadow the web ones later |
| Block kit: text/images/map/places/timeline/table/stats/chart/callout/compare/steps/hero/quote/budget/calculator/weather/stock/links/email/status/apps (+aliases) | **No native reimplementation needed** — the HTML renders itself. Beacon owns: pane sizing, ↔ scroll rule (below), link policy, composer hand-off. Native block-kit renderer only when the wire carries *structured* `embeds` JSON rather than tool HTML (M5; text/table/stats/chart first-class, chart obeys the borderless rule) |
| Oversize handling inside the card CSS: galleries >3 photos and place-card rows = `overflow-x:auto` + `scroll-snap:x mandatory`; `.tbl` tables = `overflow-x:auto` | Adopt as the app-wide **↔ rule**: content wider than the column scrolls left↔right in-pane, never scales to unreadable; edge-fade mask hints; ⤢ expands for real estate; vertical drags still hand off to the chat list (touch hand-off §3) |
| Interactive blocks (budget sliders, people stepper, safe-expression calculator, live weather/stock) | Pure client JS — works as-is in the WebView; no bridge verbs required |
| MapLibre + self-hosted planet tiles, day tabs, expand toggle, no-WebGL svg-sketch fallback | Let it run (Android WebView has WebGL); tiles host goes through the card's own CSP. Fallback sketch = graceful if GPU is blocklisted |
| CSP: card carries its own `default-src 'none'` meta + CDN/tiles hosts | Beacon honors `ui.iframe_csp` on foreign docs (§3); card CSP is compatible |

### 11.4 Voice & audio

| OWUI feature | Wire | Beacon v2 home | Status |
| --- | --- | --- | --- |
| STT transcription | POST /api/v1/audio/transcriptions | waveform pill hold-to-talk | live |
| TTS read-aloud | POST /api/v1/audio/speech (TtsPlayer) | ⋯ turn sheet 🔊 | live |
| TTS voices/models | GET /api/v1/audio/models | Settings voice row | live |
| Auto-read replies | client setting | Settings voice row toggle | live |

### 11.5 Workspace (native = read-only v1)

| OWUI feature | Wire | Beacon v2 home | Status |
| --- | --- | --- | --- |
| Featured prompts → suggestions | /api/v1/prompts/ | landing chips (≤3) + Workspace Prompts tab | live (read) |
| Prompts CRUD | /api/v1/prompts/* | — | web-only (write), M5+ read-edit |
| Models (presets) CRUD | /api/v1/models/* | Workspace Models list | M4+ view → write later |
| Tools list + valves | /api/v1/tools/ | Workspace Tools tab | live (read) |
| Tool servers (MCP/OpenAPI) | tool-servers listing | Workspace footer section | live (read) |
| Functions / pipes editor | /api/v1/functions/ | — | web-only |
| Knowledge CRUD + files | /api/v1/knowledge/* | Workspace Knowledge tab (read) + ⊕ attach | live (read) |

### 11.6 Surfaces

| OWUI feature | Wire | Beacon v2 home | Status |
| --- | --- | --- | --- |
| Notes CRUD + pin | /api/v1/notes/* | Notes tab + editor | live |
| Memories (gated) CRUD + wipe | /api/v1/memories/* | More ▸ Memories | live |
| Channels (gated) list/post/live | /api/v1/channels/* + events:channel | More ▸ Channels | live |
| Files manager | GET /api/v1/files/?page=1&content=false (+delete/open) | Files tab `Uploads` source | live |
| Terminals list/read/list/upload | /api/v1/terminals/* (single-slash) | Files tab terminal source chips + breadcrumb | live |
| File viewer | file/{id}/{name}/{mime} route | FileViewer (native mime views) | live |

### 11.7 Ambient / server-provided

| OWUI feature | Wire | Beacon v2 home | Status |
| --- | --- | --- | --- |
| Feature gates | GET /api/config features | rows/panes hide (§9.5) | live |
| Server announcements | /api/config banner | top banner card | M5 |
| Socket rooms | wss /ws/socket.io (events:chat, events:channel, user-join) | §5 | live |
| event_call → theme sandbox | execute / ExecuteSandbox | §3 M2-2 (bake/probe) | live |
| Share-target / NEW_CHAT shortcut | Android intents (§9.3) | chat/new | live |
| App lock / proxy capture / widgets | — | §8 remainder | M5 |
| MCP app-frame confirm dialogs | — | approval dialog family | M5+ |

### 11.8 Admin plane → deliberate web-only

Users & groups, permission editors, evaluation leaderboards/annotations,
all server configuration (connections/providers, audio, images,
documents/RAG, web-search engines, code interpreter, pipelines,
database, general UI), function editor, imports, API keys. The phone
consumes, the desktop administers. When `/api/config` reports an admin
role, Settings shows a caption row: "Server admin lives on web."

