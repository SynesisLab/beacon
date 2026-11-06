# Beacon Feature ↔ Code Map (2026-11-06)

Every user-facing surface, its screen/VM files, its wire endpoints, and the
data flow behind it. Read alongside `CLAUDE.md` (OWUI feature audit) and
`docs/protocol-notes.md` (wire shapes with proof). Paths are
`app/src/main/kotlin/dev/beacon/…`.

**UI authority = `beacon-ui-design.md` v2.0 "simplified" + `beacon-ui-mockup.html`.
§10 below is the control→wire mapping it points to; §11 is the complete
Open WebUI inventory.**

## 1. Navigation — v2.0 "simplified" (no drawer, four tabs)

One hand, no drawer. Start destination (when onboarded) = **Chats home**.

| Tab / route | v2 surface | Screens | Status in code |
| --- | --- | --- | --- |
| tab 1 `chats` (start) | Chats home: Pinned ✦ → Folders → date-grouped All; ⌕ search; ＋ new folder; FAB `+ New chat`; row ⋯ = row-actions sheet; 2FA nudge card when unenrolled | `feature/home/ChatsHomeScreen.kt` + `feature/home/ChatsHomeViewModel.kt` | v2 target (Stage B) |
| `chat/new`, `chat/{chatId}` | chat conversation (tab 1 stack; back → Chats home) | `feature/chat/ChatScreen.kt` + `ChatViewModel.kt` | exists; topbar/composer per §3/§10.2 |
| tab 2 `notes` | Notes list (title · snippet · updated · ✦ pin · ⋯) + editor | `feature/surfaces/SurfaceWriteScreens.kt` (NotesScreen/NoteEditorScreen) | editor exists; tab-level chrome pending |
| tab 3 `files` | ONE list, source chips: Uploads + each terminal server; mono breadcrumb; folders-first rows; contextual upload FAB → `FileViewer` route | `feature/terminal/TerminalScreen.kt` (merged with uploads list) | v2 merge pending (Stage C) |
| tab 4 `more` | everything else: Workspace › · Memories* › · Channels* › · Settings › · version caption | `feature/home/MoreScreen.kt` (new) | v2 target (Stage B) |
| `settings`, `settings/headers` | pushed; headers editor unchanged (ADR-0012) | `feature/settings/*` | exists |
| `folder/{id}/{name}/chats` | folder's chats | `feature/surfaces/FolderChatsScreen.kt` | exists |
| `note/{id}`, `note/new` | note editor | `SurfaceWriteScreens` | exists |
| `channel/{id}/{name}` | channel stream | `SurfaceWriteScreens` | exists |
| `file/{fileId}/{name}/{mime}?server&path` | native viewer + overflow (save/copy path) | `feature/chat/FileViewerScreen.kt` | exists |
| `surface/{key}` | remaining detail surfaces: memories / channels list / workspace / (notes+files pre-v2 keys) | `MainActivity.SurfaceRoute` :344 | exists (More rows navigate here) |

v2 deletions: **the drawer** (ChatsDrawer.kt — recents + its gated trio move to
`more`/Chats home), the old `chat`/`folders` tab pair (→ `chats`). Share-target
and the `dev.beacon.NEW_CHAT` shortcut still jump to `chat/new`
(`MainActivity.handleShareIntent`). Sheets everywhere: 24-top / 32×4 handle /
40% scrim / 260ms. Nav transitions: fade+slide forward, predictive-back scale
(MainActivity :130-157).

## 2. Onboarding & auth

**v2 target: ONE page, three micro-steps** (beacon-ui-design.md §4.1) — a card
stack on cream: Instance (URL + probe line) → Sign in → inline MFA when the
challenge lands (code cells + recovery-link on the same card). Model pick is
dropped (first model = default; change via the top chip). **2FA enroll defers**
to a one-time coral-tint nudge card on Chats home → enroll sheet → dismiss
forever. Current code keeps the multi-step flow (below) until the v2 page
lands:

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

1. New screens route in `MainActivity` + a `feature/<name>/` package; list surfaces reuse `surface/{key}` + gated rows in the drawer (v2: the **More** tab).
2. New wire shapes → **fixture + test first** (§13; real SSE transcripts under `app/src/test/**/fixtures/**`), file:line proof in `docs/protocol-notes.md`.
3. New render output → extend `ChatPart` + both producers (Segmenter + PartBuilder) + goldens, never a bubble-only branch.
4. Persistence stays derivational (Room mirrors the tree; partsJson normalized).
5. Gates first: any surface that needs a server flag reads `GET /api/config` state and hides when absent.

## 10. Control → wire authority (beacon-ui-design.md v2.0 / mockup)

Every visible control → what it does → where it lands on the wire. §10.0 =
what the v2 simplification MOVED; §10.1-10.13 = per-surface tables.

### §10.0 — v1→v2 amendments (what moved where)

| v1 | v2 | Wire effect |
| --- | --- | --- |
| Drawer (recents + gated trio) | DELETED — recents = Chats home sections; trio = More tab | none (same reads) |
| Tabs Chat / Folders | retitled **Chats** (home) | none |
| Composer chips Web/Image/Code (visible row) | ⊕ Add-to-chat sheet toggles | none — same flags in the request body |
| Suggested prompt cards (shimmer) | ≤3 compact chips, no theater | featured prompts from workspace (hidden when none) |
| Onboarding 5 steps | 1 page + inline MFA | same endpoints; enroll deferred to a home nudge (same enroll endpoints) |
| Turn footer (icon row) | `↻ · ‹n/N› · ⋯` (sheet contents) | same actions (rating/copy/share endpoints) |
| Files = segmented panes | ONE list, source chips | both sources' endpoints unchanged |
| Top bar hamburger/⋮ split | back + **context chip** (tap=model sheet, long-press=options menu — amended 2026-11-06: the chevron promises models) + dashed temp bubble | same actions |

### §10.0b — v2→v3 amendments ("sidebar", 2026-11-06)

| v2 | v3 | Wire effect |
| --- | --- | --- |
| Bottom tab bar (Chats/Notes/Files/More) | **DELETED** — navigation = the left slide-in sidebar (Claude drawer × OWUI web anatomy) | none |
| `More` tab list | sidebar NAV rows (Workspace/Memories*/Channels*/Settings — gated rows vanish) | same reads |
| Chat topbar `‹ back` | **hamburger bubble** top-left on every screen; system back pops routes (predictive) | none |
| Chat ⋮ menu | dies — chat options consolidate into the CONTEXT CHIP (tap = model sheet; long-press = options menu — amended 2026-11-06) | same actions |
| Recents | sidebar CHATS section mirrors the Chats home (pinned ✦ → folders ▸ → date groups; paginates `?page=N`) + shared row-actions sheet | same reads |

### §10.1 Chats home (`ChatsHomeScreen`; start destination)
- ⌕ search — client-side filter over Room rows (server search = §11 unified-search row, M4+).
- ＋ (header) → new-folder dialog → `POST /api/v1/folders/` (WorkspaceApis:120).
- FAB `+ New chat` → route `chat/new`.
- Section rows:
  - **Pinned ✦** = `chats.filter{pinned}` (Room; truth from `GET /api/v1/chats/pinned` merge).
  - **Folders** (name · count · updated · ⋯) → tap = `folder/{id}/{name}/chats`; ⋯ = rename (`POST /api/v1/folders/{id}/update`) / delete (`DELETE /api/v1/folders/{id}`) — VM: `SurfaceWriteViewModel`.
  - **All chats** date-grouped (Today/Yesterday/Prev 7) = Room rows, pagination via `GET /api/v1/chats/?page=N` (HomeViewModel.loadNextPage).
- Row ⋯ → row-actions sheet: Pin/Unpin (`/pin`), Archive (`/archive`), Share (`POST /api/v1/chats/{id}/share` → chooser), Export md/json (client-built), Move-to-folder (server truth `chat.folder_id` PATCH-family — §11 note), Delete (`DELETE /api/v1/chats/{id}`).
- Row tap → `chat/{id}`.
- 2FA-enroll nudge card (coral-tint) when session is enrolled=false; tap → enroll sheet (`/enroll/start`, `/enroll/confirm`); dismiss = forever (per-install flag).

### §10.2 Chat conversation (`ChatScreen`)
- Top bar: `‹ back` → pop to `chats`; **context chip** = auto-title/model ⌄ → tap = **model sheet** (listed models; long-press = the options menu — amended 2026-11-06); **dashed temp bubble** → temp toggle (client-ephemeral; M5).
- Detail-run `✓ Explored N ⌄` / "Exploring" — display-only (parts pipeline).
- `Thought for 4s ⌄` — reasoning expand (Room parts).
- Tool row ⌄ — pending → approval dialog (`POST /api/v1/chats/{id}/messages/{messageId}/resolve` w/ action approve/reject or ask_user answers).
- Inline graphics (§4.3b/4.3c) — see §3 cards.
- Citations `[n]` → display_file instance viewers (`GET files/{id}/content`).
- Turn footer: `↻` regenerate (branch fork) · `‹n/N›` siblings when present (SetBranch) · `⋯` → sheet: Copy (client) / Read aloud (`POST /api/v1/audio/speech`) / 👍👎 (`POST /api/v1/evaluations/feedback`) / Export turn (client).
- User-turn long-press → Edit inline (`POST /api/v1/chats/{id}/messages/{messageId}` edit — `updateMessageContent`) / Branch from here / Delete / Copy.
- Context rail under composer (2dp coral) — `GET /api/v1/chats/{id}` `context_usage`; tap = usage popover; Settings toggle-hides.

### §10.3 Composer + Add-to-chat
- Text field + send/mic/stop state pill (send `POST /api/chat/completions`; mic = voice overlay w/ hold-to-talk `POST /api/v1/audio/transcriptions`; stop = `POST /api/tasks/chat/{chatId}/stop`).
- ⊕ → **Add-to-chat sheet**: tiles Camera/Photos/Files → UploadQueue → `POST /api/v1/files/?process=false`; rows Attach Knowledge › / Attach Notes › (workspace pickers → file ids into the request body); divider; toggle rows **Web / Image / Code** — the SAME three flags as today (request `features.*`).
- Attachment chips above composer (✕ removes; queued = local).

### §10.4 Model sheet
Search · models (`GET /api/v1/models/list?page=1`, caps badges from the defaults chain) · current ✓ · per-chat default (hold = set app default).

### §10.5 Notes (tab 2)
Rows (title · snippet · updated · ✦ · ⋯ delete-confirm via swipe) → editor (debounced `POST /api/v1/notes/{id}/update` content-merge). `+` → `POST /api/v1/notes/create` → editor route.

### §10.6 Files (tab 3) — one list, source chips
- Source chips: `Uploads` + ONE PER TERMINAL (`GET /api/v1/terminals/`).
- Terminal source: mono breadcrumb (cwd) + ⌃ up + ↻ refresh; rows folders-first (`GET /api/v1/terminals/{id}/{path}?…` listing).
- Rows: folder → drill-in; file → FileViewer route; ⋯ = save / copy path.
- Upload FAB: uploads chip → `POST /api/v1/files/?process=false`; terminal chip → terminal upload (`?directory=` query — single-slash discipline).

### §10.7 More (tab 4)
Rows (gated hide): Workspace › (`surface/workspace`: `GET prompts/tools/knowledge/*`) · Memories* › (gated enable_memories) · Channels* › (gated enable_channels) · Settings ›. Footer: app + server version caption.

### §10.8–10.13
Memories cards/kebab/wipe (`/api/v1/memories/*`, typed-count confirm) · Channels (list + stream + `POST …/messages/post` + live refetch; create hidden unless admin, M5) · Settings three groups (instance re-probe `/api/version`, user row `GET /api/v1/auths/`, sign-out client-wipe; color trio, haptics, TTS `GET /api/v1/audio/models` + auto-read; Advanced: headers › + context-rail toggle; **Sidebar row toggles (2026-11-06)** — per-row switches for Notes/Files/Workspace/Memories/Channels/Settings → `ServerSettings.hiddenNavRows`; sidebar rows vanish exactly like gated rows) · Headers editor (ADR-0012 rules rows + add dialog + dead-state rejections + SecureStore caption) · Workspace detail sheets (prompt body / tool spec+valves / knowledge files → content, view-only caption) · Onboarding one-page (§2).

### §10.14 — Sidebar controls (v3 §3.4, mockup 03)

| Control | Action → wire |
| --- | --- |
| Wordmark `Beacon` + `v{app} · server v{ver}` | caption — versions from `/api/version` (probe) + the session store; tap = nothing |
| ⌕ Search chats | filters the CHATS section live (client-side over Room; server unified search = §11, M4+) |
| NAV `Chats` | route `chats` (the home list) |
| NAV `Notes` | route `notes` → `GET /api/v1/notes/` |
| NAV `Files` | route `files` → `GET /api/v1/terminals/` (source chips = Stage C) |
| NAV `Workspace` | route `surface/workspace` → `GET /api/v1/prompts|tools|knowledge/` |
| NAV `Memories*` | GATED `enable_memories` — row ABSENT without it; `GET /api/v1/memories/` |
| NAV `Channels*` | GATED `enable_channels` — `GET /api/v1/channels/` |
| NAV `Settings` | route `settings` (instance re-probe / user / sign-out) |
| CHATS `✦ pinned` rows | tap = close + `chat/{id}`; ⋯ = the shared row-actions sheet (pin/share/delete) |
| CHATS `▸ folder` rows (name · count) | tap = `surface/folders` drill (folder chips list; ＋ header = create → `POST /folders/`) |
| CHATS date rows ( Today › / Yesterday › / Prev 7 › ) | tap = the chat; ⋯ = sheet; the section paginates on scroll (`GET /chats/?page=N`) |
| Bottom identity bar: avatar initials · name · role badge | data = `GET /api/v1/auths/` (name/email/role, cached in ServerSettings) |
| Bottom `＋ New chat` black pill | close + route `chat/new` |
| Opening affordances | hamburger bubble (every screen's topbar) · left-edge swipe · NO system-back trap (back closes the drawer first, then pops routes) |
| Behavior | scrim 40% · panel `drawer` token · ≤320dp · 260ms spring · gates hide rows (never gray) · models stay on the top chip |

## 11. Complete Open WebUI inventory → Beacon (2026-11-06)

Authority mirror of CLAUDE.md's audit (+ releases audit). Landings marked ✓
have live code; MISSING rows are deliberately deferred to their milestone.

| OWUI feature (gate / route) | Beacon |
| --- | --- |
| Chats crud/pin/archive/search | ✓ (M1; search client-side; unified server search — §11 deltas) |
| Folders | ✓ (M4) |
| Chat completions + streaming/transports | ✓ (M1/M2; all three transport classifications) |
| Tool call parts + display/hide rules | ✓ (M2-1, web-parity table) |
| Inline HTML cards — Frontier-UI embeds channel | ✓ device-verified (`function_call_output.embeds` / `message.embeds`) |
| HtmlCard pane + policy + CSP | ✓ M2-2 (`CardDocument`, ADR-0013/0014; theme follows app 2026-11-05) |
| Terminal file browsing | ✓ M2-t (single-slash discipline) |
| Terminal interactive sessions | post-M5 |
| Composer feature toggles (features.*) | wire-ready; UI moves to ⊕ sheet (Stage C) |
| Message edit/branch/regenerate + version pager | ✓ (verified live) |
| Feedback/rating + read-aloud | ✓ M3-scheme (footer sheet) |
| Attachments/UPLOAD queue + share-target | upload queue ✓ basic; share-target ✓; Camera/Photos tiles = Stage C |
| Voice STT/TTS | ✓ M3 (overlay + hold-to-talk polish pending) |
| Auto-titling (chat:title) | ✓ (live title + room-paint) |
| Context usage | data ✓ shown when sent; v2 = context rail after send (Stage C) |
| Reconciler + flick-to-refetch | ✓ (+ hang rescue 2026-11-06: flick while busy AND fold-silent ≥10s aborts the dead transfer — stop route + partial kept — then re-syncs; live streams keep §9.1 immunity) |
| Chat share (share_id) + export md/json | ✓ client-built export |
| Shared chats w/ Allow-replies + reply streaming | MISSING — rides M3 share row (server flag) |
| Comment threads (ENABLE_COMMENTS) | M5+ |
| Notification inbox (notifications.*) | M4+ |
| Side chats (temp side, /side) | M5+ (gate-hidden; v2 dashed temp bubble = client-ephemeral M5) |
| MCP app frames + per-call confirm | ✓ embeds as Embed cards; confirm dialogs reuse resolve |
| 3D previews (GLB/STL…) | M5+ (file viewer) |
| Model controls knobs (params) | M4 params sheet |
| Prompt autocomplete task | M4+ |
| MFA verify/recovery/enroll | ✓ + deferred-enroll nudge (v2) |
| Custom headers editor | ✓ (ADR-0012) |
| Socket.IO transports/taskSocket | ✓ websocket-only; taskSocket stream = M4 legacy path + poll-recovery TODO |
| Workspace screens incl. tool-servers | ✓ list surfaces (read-only) |
| Memories | ✓ |
| Notes | ✓ (+ pages/tabs/presence = M5+) |
| Channels + E2EE | read/post/live ✓; E2EE decision row (MLS gate) open |
| Apps panel (apps.*) | M4+ |
| Calendar (calendar.*) | M4+ |
| Automations (automations.*) | M4+ |
| User notifications/targets | M4+ |
| Image generation (render + trigger) | render ✓ M2; trigger toggle = Stage C ⊕ |
| Realtime webrtc voice-call | P2 ✓-shaped (§3) |
| Evaluations/leaderboards | post-M5, only if requested |
| Model editor / custom model create | M4+ (view-only first) |
| Admin plane (scim/groups/analytics/pipelines/retrieval) | **DELIBERATELY web-only** (client, not console) |
| Unified workspace search (v0.12.3) | extends chat/notes search, M4+ |
| Skills lazy-load / skill ids on wire | ✓ (sends skill_ids) |
| Desktop tab bar (v0.13.0) | deliberately NOT mobile-targeted (tabs = app sections) |

### §11.3b — the Frontier-UI tool contract (v2.2/v3, the inline-rendering authority)

Read off the exported `render_frontier_ui` tool (v1.3.0; protocol-notes.md has
the file:line table). Beacon accounts for it with the EXISTING pipeline:

- **Channel**: the tool returns `(HTMLResponse inline, context)` → the server
  rows the html list as `function_call_output.embeds` (middleware.py:745-752 →
  :5844-5850; also `message.embeds` :3310) → `PartBuilder` folds CardKind.Embed
  MEDIA cards (`PartBuilderEmbedsTest`).
- **Pane**: `HtmlCardPane` — one WebView per card; theme seed + admin CSP
  (`ui.iframe_csp`) handled (ADR-0013/0014; theme-follows-app 2026-11-05).
- **Height**: the tool posts `iframe:height` postMessage; Beacon consumes the
  same-document beacon bridge + the CSP-proof host measure (CSS-px-as-dp; the
  stability loop). v2.2 target: cap ≈ 60% viewport → internal scroll w/ the
  chat's autoscroll-pause — today = full measured height (web parity).
- **Links**: web IFrames are sandboxed/COOP-hostile (the tool's copy-link
  workaround) — native is not: intercepted links → Custom Tabs / OS handler
  (EmbedPolicy) — the workaround is dormant here.
- **Composer hand-off**: `{type:'input:prompt:submit'}` = the card's
  action/follow-up chips ask the CLIENT to submit a prompt → Stage C: pre-fill
  the composer (never auto-send).
- **↔ oversize rule** (v2.2): wide blocks (tables / place-rows / galleries>3 /
  wide SVGs) scroll left↔right IN-PANE (`overflow-x` + scroll-snap rows +
  edge-fade mask), never shrink-to-fit; vertical drags keep handing off to the
  chat list; ⤢ = fullscreen.
- **Chrome: none** — tool cards borderless with a muted `tool · name` caption.
