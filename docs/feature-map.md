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
