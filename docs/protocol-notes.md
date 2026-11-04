# Beacon protocol notes

Verified request/response shapes for every endpoint Beacon touches. Rule (agent.md §16.2):
**never invent an endpoint or a field** — each row below must cite `file:line` in the
cloned reference source (`open-webui` clone is the ground truth; Conduit cites carry the
concept it verified originally). Update this file in the same PR as the code change.

Reference clones verified against: `open-webui` (BSD-3-style) + `conduit` (GPL, spec-only).

## Verification table (M0)

| Call | Request | Response (client-decoded shape) | Proof (open-webui clone) |
| --- | --- | --- | --- |
| `GET /api/version` | none | `{version, deployment_id}` | `backend/open_webui/main.py:2701-2706` |
| `GET /api/config` | optional `Authorization` (token or cookie accepted) | `{status, name, version, onboarding?, oauth{providers, auto_redirect}, features{…}, default_models, default_pinned_models, default_prompt_suggestions(+_i18n), user_count?, code{engine, interpreter_engine}, audio{realtime/tts/stt}, file{…}}` | `main.py:2289-2333` (auth/onboarding handling), `main.py:2339-2472` (features block), `main.py:2478-2530` (default_*/code/audio/file keys) |
| `features.*` gates (client subset) | — | `auth`, `auth_trusted_header`, `enable_signup`, `enable_login_form`, `enable_ldap`, `enable_websocket`, `enable_api_keys`, `enable_folders`, `enable_channels`, `enable_notes`, `enable_web_search`, `enable_image_generation`, `enable_title_generation`, `enable_tags_generation`, `enable_follow_up_generation`, `enable_direct_connections` | `main.py:2424-2500` |
| `POST /api/v1/auths/signin` | `{email, password}` (`SigninForm`) | success: `{token, token_type, id, name, email, role, profile_image_url, expires_at?, permissions?}`; MFA: `{next_step: enroll\|verify\|recover, challenge_token, expires_in}`; pending: `{next_step: 'pending'}` | `backend/open_webui/routers/auths.py:663-668`; `backend/open_webui/models/auths.py:96-104` (Token/SigninResponse/SessionUserResponse), `:121-128` (MfaChallengeResponse/PendingUserResponse), `:173-175` (SigninForm) |
| API-key auth | `Authorization: Bearer sk-…` | keys are issued as `sk-{…}` and recognized by `get_current_user` | `backend/open_webui/utils/auth.py:374-376` (create_api_key), `:415-431` (sk- dispatch) |
| `GET /api/v1/models/list` | `query/view_option/tag/order_by/direction/page` | `{items: [{id, name, …}], total}` (`ModelAccessListResponse`; per-item `profile_image_url` in meta is excluded server-side) | `backend/open_webui/routers/models.py:227-239`; `backend/open_webui/models/models.py:237-240` |
| `GET /api/v1/models` (bare) | — | OpenAI-compat `{data: [...]}` — *compat surface only; Beacon uses `/list`* | `main.py:934-935` |

## Corrections against agent.md (§16.7 log)

- **agent.md §5.3 claimed behavior flags come from `GET /api/v1/configs/`.** Wrong for the
  client: in current open-webui, `/api/v1/configs/` is the *admin* import/export surface
  (`routers/configs.py:100-134`); the surface the web UI (and Beacon) gates screens on is
  the public **`GET /api/config`** (`main.py:2289`). agent.md §5.3 updated in the same commit
  as this note.

## Deviations / open questions

- DataStore uses a kotlinx-serialization `Serializer` instead of Proto — see `docs/decisions.md` ADR-0001.
- Cleartext http allowed app-wide in M0 (LAN self-host instances); per-server tightening at M4 — ADR-0002.
- Secret storage is a hand-rolled Keystore AES-GCM `SecureStore` (androidx.security-crypto is deprecated) — ADR-0003.
- `trustUserCa` requires process restart to apply in M0 (client is fixed at graph-build) — ADR-0004.

## Verification table (M1 — chat completions & streaming)

| Call / shape | Verified behavior | Proof (open-webui clone) |
| --- | --- | --- |
| `POST /api/chat/completions` | streaming completion; body dict (client controls shape; server pops `model_item`, `background_tasks`, `params`) | `main.py:1146-1153` |
| Client request body | `stream`, `model`, `messages`, `params{model_controls, stop}`, `files`, `filter_ids`, `tool_ids`, `skill_ids`, `terminal_id`, `tool_servers`, `features`, `variables`(+`chat_variables`), `model_item`, `session_id`, `chat_id`, `folder_id`, `id`, `message_ids`, `parent_id`, `user_message`, conditionals `regeneration_prompt`/`regeneration_message_id`/`assistant_message_id`, `background_tasks{title_generation, tags_generation, follow_up_generation}` | `src/lib/components/chat/Chat.svelte:4420-4495` |
| Wire frame format | `data: {json}\n\n` (blank-line separated); mid-stream errors as ``\n\ndata: {"error": {"message": …}}\n\n`` then `data: [DONE]` | `middleware.py:6444-6445`, `:6527-6528` |
| Streaming content-types | `text/event-stream` OR `application/x-ndjson` both carry the same `data:`-frame protocol | `middleware.py:6545-6549` |
| Server-side fold (client must mirror) | `choices[].delta`: `content`; reasoning via `reasoning_content` \| `reasoning` \| `thinking` (all three); `finish_reason`; `usage`+`timings` snapshot; `response.*` events folded via `handle_responses_streaming_event` into `output[]` items (`reasoning`/`message` typed, immutable-list semantics) | `middleware.py:3441-3524` (reasoning keys at `:3474`), `utils/response.py:405+` |
| Envelope event types (client switch) | `status`, `context_compaction`, `chat:active`, `response:completion`/`chat:completion`, `chat:tasks:cancel`, `chat:message:delta`/`message`, `chat:message`/`replace`, `chat:message:files`/`files`, `chat:message:tasks`, `chat:message:embeds`/`embeds`, `chat:message:error`, `chat:message:follow_ups`, `chat:outlet`, `chat:message:favorite`, `chat:title`, `chat:tags`, `source`/`citation` (+ `data.type == 'code_execution'` subcase), `confirmation`, `input`, `notification`, `typing`, `chat:reload`, `chat:list`, `chat:presence` | `src/lib/components/chat/Chat.svelte:1573-1710` |
| Envelope payload shapes | status`{data:{content?,done?}}`; source`{data:<source>}`; follow_ups`{data:{follow_ups:[…]}}`; title`{data:"<title>"}`; tags`{data:[…]}`; chat:completion`{data:{done?, output?}}` | `middleware.py:1241`, `:3289`, `:3630-3641`, `:3694-3715`, `:3740-3747`, `:3336` |
| Stop (user-level) | `POST /api/tasks/chat/{chat_id}/stop` — the *user-owned* cancellation; `/api/tasks/stop/{task_id}` is ADMIN-only + task-scoped | `src/lib/apis/index.ts:335-336`; `main.py:2180-2186` |
| `/api/chat/completed` | still called by the web client; server marks it deprecated (outlet filters now run inline during completion) — call it, honor inline behavior later via config | `src/lib/apis/index.ts:231`; `main.py:2142-2160` |
| Socket routing | `metadata.session_id` (socket.io id) rides into backend routing when the client is connected | `utils/chat.py:60,86,137,356` |

## Pending verification (needs a live dev instance)

- Exact 401 body shape for expired JWTs (refresh path at M4).
- `deployment_id` presence/absence variance (some proxies strip it).
- Whether `/api/v1/models/list` honors `view_option` values Beacon may want (M1).
## M4-d3 socket findings (2026-11-03, ai.anthonymiles.xyz v0.12.5)

| Topic | Finding | Evidence |
| --- | --- | --- |
| Transport gate | `ENABLE_WEBSOCKET_SUPPORT` defaults True → server runs WEBSOCKET-ONLY: `transports=['websocket']`, `allow_upgrades=upgrades=false`; polling handshake answers 400 "Invalid transport" | env.py:473, socket/main.py:124-125 + live curl |
| Handshake auth | `auth:{token}` rides the socket.io CONNECT packet; on acceptance the session enters room `user:{id}` | socket/main.py:611-641; live user-join ack returned the user |
| Client wiring | socket.io-client-java 2.1.2 encodes nested kotlin/java maps via toString() — payloads must be org.json objects (the user-join was delivered as the STRING "{auth=…}" and silently dropped) | in-app j.u.l frame trace `20["user-join","{auth={…}"]` |
| Event envelope | all middleware events ride socket event `events` as `{chat_id, message_id, data:{type,…}}` to room `user:{id}`; the HTTP SSE is the RAW upstream passthrough (OpenAI frames, no envelopes) | socket/main.py:1626-1673 + captured frames |
| chat:list | emitted at chat creation/finish via the emitter (SOCKET-ONLY); M1-era "chat:list in SSE" does not hold for 0.12.5 | main.py:296-307, middleware.py:243-246 |
| New chat flow | the CLIENT creates the chat first: POST /api/v1/chats/new (title inside `chat` dict — models/chats.py:673-675); a chat-less send = a TEMP chat server-side, nothing saved | Chat.svelte:4817-4847 + Beacon 2026-11-03 trace |
| task_ids response | with session_id+chat_id: `POST /api/chat/completions` → `{status: true, task_ids: [...], chat_id}` and the stream rides socket events (chat:completion deltas) | main.py:1813-1930 |
| Terminal event | `chat:completion {done:true, output, title, usage}` (middleware.py:6341-6379) | clone + fixtures |
| ask_user | builtin tool (builtin.py:543-660) emits `request:user_input` via event_call; **the interactive call was NOT delivered to a correctly-authed first-party client (probe + Beacon) — the server resolved the tool internally**; Beacon auto-answers `{status:'cancelled', answers:{}}` so the turn completes | probe 2026-11-03 log; socket/main.py:1790-1810 |
| /api/chat/completed | reads `data['session_id']` unconditionally (utils/chat.py:353) and the outlet filters run INLINE on 0.12 (middleware.py:6377); Beacon sends session_id + skips the completed call for servers ≥0.10 | trace 400 "'session_id'" |

## Tool approval (2026-11-03, tool_approval.py)

| Topic | Finding | Proof |
| --- | --- | --- |
| Resolve form | `{call_id, action: 'approve'\|'reject'\|'answer', answers?, timed_out?}`; **approve is for NON-ask_user only** (ask_user → 400), reject sets status `rejected` + appends output "Error: tool call rejected by user.", answer is ask_user-only | utils/tool_approval.py:16-20, :119-160 |
| Resume | approve → resume `'tools'` (tools run next); reject (nothing pending) → tool_continuation + resume `'model'`; the resolve POST returns the RE-RUN completion response (SSE or `{task_ids}`) and a `chat:completion{output}` event rides sockets | tool_approval.py:162-185; main.py:1947-1952 |
| Pending shape | pending = function_call items with no function_call_output, not `approved:true`, status in `{pending,queued,requires_approval}` (in_progress = running) | tool_approval.py:23-31, :105-117 |
| Approval gate | the server asks for approval only when chat params `tool_approval_mode == 'ask'` (default `full` = run everything) | middleware.py:3136, :3341-3344, :5737-5744 |

## Memories surface (2026-11-03, memories.py)

| Call | Behavior | Proof |
| --- | --- | --- |
| `GET /api/v1/memories/` | `list[MemoryModel{id, user_id, type, path, content, meta, updated_at, created_at}]`; 404 when `memories.enable` off | routers/memories.py:31-42, :33-39, :55-63 |
| `POST /add` | `{content, type:'user'\|'context'=context, path?}` → MemoryModel; embedded 1-5s (managed sessions) | memories.py:71-74, :177-199 |
| `POST /{id}/update` | `{content?, type?, path?}`; none set → 400 | memories.py:546-571 |
| `DELETE /{id}` → bool | single-memory delete; the VECTOR row also goes | memories.py:610-631 |
| `DELETE /delete/user` → bool | wipes ALL the user's memories + the collection | memories.py:514-538 |
| timestamps | epoch SECONDS (`int(time.time())` — unlike the notes' nanoseconds; `epochToMillis` handles both by magnitude) | models/memories.py:61 |
| gate | `enable_memories` rides `/api/config` features = `memories.enable` | main.py:2486 |

The M4 "memories" item lands gated by `enable_memories`
(`MainActivity.kt` SurfaceRoute "memories"; drawer GatedRow).

## MFA sign-in (2026-11-03, mfa.py @ /api/v1/auths/mfa — main.py:890)

| Step | Behavior | Proof |
| --- | --- | --- |
| Sign-in challenge | `SigninResult` = SessionUserResponse \| `{next_step: enroll\|verify\|recover, challenge_token, expires_in}` \| `{next_step: 'pending'}` when MFA enabled; the challenge_token rides the FORM for API clients — the `mfa_challenge` cookie is the web-only leg | models/auths.py:121-128, :137-148, :150-171; utils/auth.py:665-691 |
| Verify | `POST /verify {challenge_token, code, recovery}` → SessionUserResponse (a later unverified call would 401 "Authenticator verification is required"); `recovery:true` consumes a one-time code | mfa.py:147-148; utils/auth.py:717-718; consume_factor mfa.py:283-285 |
| Enroll | `/enroll/start {challenge_token}` → `{manual_key, qr_code}` where qr_code = `data:image/svg+xml;base64,` (SvgPathImage); `/enroll/confirm {challenge_token, code, recovery}` → SessionUserResponse + `recovery_codes` | mfa.py:129-148; utils/mfa.py:259-281; auths.py:146-148 |
| Recover | `/recover {challenge_token, reset_token}` needs the ADMIN-issued reset token — Beacon keeps a typed message directing to the web UI | mfa.py:186-190; MfaRecoveryForm auths.py:166-168 |
| Rate limit | POST endpoints have an IP limiter (100/900s) — retries are user-visible, not silent | mfa.py:31, :41-48 |

## Channels live events (2026-11-03, channels.py + socket/main.py)

| Topic | Finding | Proof |
| --- | --- | --- |
| Post event | every message POST (and replies) re-emit `events:channel` to room `channel:{channel.id}`: `{channel_id, message_id, data: {type: 'message', data: {temp_id, ...message}}, user, channel}`; replies carry `message:reply` with the PARENT payload to the same room | channels.py:1440-1474 |
| Room membership | the user's session joins its channels' rooms during user-join — nothing extra for an API client | socket/main.py:670-675, :735-754 |
| Mute/active | `channel:mute` + member-active events ride the same event name to the acting user / room | channels.py:637-641 |
| Beacon fold | `SocketEnvelope` now carries `channel_id`; the channel screen quietly refetches (debounced 300ms) on `message`/`message:reply` | feature/surfaces |

## Terminal proxy correction (2026-11-03 device log + web client)

- The backend router is ONLY a list + catch-all proxy (terminals.py:4-5,
  :87-106, :112+). Every `files/*` verb lives on the UPSTREAM terminal server;
  shapes verified against the web client `src/lib/apis/terminal/index.ts`:
  `FileEntry {name, type:'file'|'directory', size?, modified?, writable?}`
  (:34-40 — **no path field**; clients compose cwd+name), list =
  `GET files/list?directory=` → `{entries, writable}` (:248-260), cwd GET
  (:227) / POST {path} (:619-639), read (:380 → `{content}`), view raw
  (:365/:432; preview=true only for pdf :452), upload = `POST
  files/upload?directory=` + multipart field `file` (:504-506 — the query, NOT
  a header; Beacon's X-Directory was wrong), mkdir POST {path} (:567-587),
  delete DELETE `?path=` (:595-611), move POST `{source, destination}`
  (:648-668), archive POST `{paths}` (:475-489). search/glob are extra
  features Beacon skips for now.
- Device evidence: `GET /api/v1/terminals// ← 200` with NO further calls = the
  connections list came back EMPTY for that account (ENABLE_TOOL_SERVERS off /
  no `terminal_server.connections` / group access not granted —
  terminals.py:90-106). The empty-state now names the admin lever.

## Feature defaults (2026-11-03, models-defaults chain)

| Topic | Finding | Proof |
| --- | --- | --- |
| Admin panel save | Admin → Settings → Models saves `{DEFAULT_MODELS, DEFAULT_PINNED_MODELS, MODEL_ORDER_LIST, DEFAULT_MODEL_METADATA{capabilities, defaultFeatureIds?, builtinTools?}, DEFAULT_MODEL_PARAMS}` — ADMIN-only route | routers/configs.py:772-794 (set), src/lib ModelDefaultsPanel.svelte:102-122 |
| USER read route | `GET /api/v1/configs/models/defaults` → `{DEFAULT_MODEL_METADATA}` — `get_verified_user`, NOT admin-gated | routers/configs.py:780-784 |
| Server-side merge | the global defaults merge into EVERY listed model's `info.meta` where the key is absent (per-model wins); capabilities merge is dict-union with per-model precedence | utils/models.py:369-388 |
| Chip semantics (web) | the composer chips start ON when the selected models' merged meta lists the feature id | Chat.svelte:1118 |
| Feature defaults (2026-11-03, models-defaults chain) | per the composer's truth chain — the caller's `ui.webSearch`/`ui.imageGeneration` tool-modes (`GET /api/v1/users/user/settings`, users.py:466-483; values that read as always-on: `always/enabled/on/true/1`/true/1 — Conduit chat_feature_defaults.dart:25-52 semantics, original code), the model's merged `defaultFeatureIds` (own meta → the USER-readable global `GET /api/v1/configs/models/defaults` when absent, configs.py:780-784), and the model's capabilities (top-level `meta` on /list items — ModelModel.meta, models/models.py:199-217); all gated by `/api/config` same-flags | users.py:466-483; configs.py:780-784; models.py:199-217,224-229; Chat.svelte:1118 |

## Streaming fold semantics (2026-11-03, device logs + Conduit cross-read)

| Topic | Finding | Evidence |
| --- | --- | --- |
| Delta events | task/socket completions deliver `response.output_text.delta` / `response.reasoning_text.delta` / `response.function_call_arguments.delta` under `chat:completion` batches or direct envelopes; payload = `{type, item_id, output_index, content_index, delta}` — item_id/index NOT always present (id-less deltas continue the last matching-family item) | middleware.py:5467-5498; middleware.py:4716-4758 |
| Fold model | accumulate ONE `output` item list: deltas APPEND into the item's content parts; every mutation = a fresh snapshot (the web's `applyResponseStreamEvent`; Conduit `openwebui_response_stream.dart` mirrors it) | src/lib + Conduit |
| Snapshot merge | snapshots (terminal `chat:completion` output, `response.completed`, `output_item.added/done`) MERGE by id, or call_id+same-type; unmatched previous items SURVIVE — earlier tool rounds and reasoning must not vanish; a function_call_output shares its call's call_id and must never replace the call | Conduit issue #751 note |
| Reasoning timing | the server stamps started_at/ended_at only at structural transitions; the client stamps locally so "Thought for N s" reads live | Conduit _stampReasoningTiming |
| Live delivery | the shared throttled `chat:completion {output}` (0.15s cadence) lands in room `chat:{id}` — clients must JOIN via socket `events:chat {chat_id, data:{type:'join'}}`; the user room carries mostly chat:list/chat:active only | socket/main.py:866-925, :1628-1675 |

## Chat share/export (2026-11-03, chats.py)

| Call | Behavior | Proof |
| --- | --- | --- |
| `POST /api/v1/chats/{id}/share` `{share_mode?: 'continue'\|null}` | → ChatResponse carrying `share_id`; requires the `chat.share` permission (401 otherwise); re-sharing RE-SNAPSHOTS the existing share; `DELETE /{id}/share` → bool (unshare + grants cleared) | chats.py:2082-2155; ShareChatForm/shared_chats.py |
| Public link | `${origin}/s/${share_id}` (a web route; the client shows/sends the URL) | src/lib ShareChatModal.svelte:35 |
| Export md/json | CLIENT-built from the chat tree — no server export endpoint (the stats/export routes are admin analytics only) | web ExportMenu; chats.py:622-720 |

Beacon protocol deltas (2026-11-03 device-verified round-trips): note read/write via
`data.pages[].content.{json,html,md}` (models/notes.py:156-220, :748-772, :357-367,
NoteForm rejects pages at :474); files list carries meta.content_type (files.py:54).

## Notes mutations (2026-11-03, notes.py)

| Call | Behavior | Proof |
| --- | --- | --- |
| `DELETE /api/v1/notes/{id}/delete` | → bool; requires write access (owner/admin/grant), 404 if unknown | routers/notes.py:718-741 |
| `POST /api/v1/notes/{id}/pin` | a **toggle** (`toggle_note_pinned_by_id`, models:700); needs only READ access (:688); response recomputes `is_pinned` for this user (:701-702) | routers/notes.py:675-710 |
| `GET /api/v1/notes/pinned` | list pinned rows (unused for now; list already carries `is_pinned` per user via pinned ids :135-141) | routers/notes.py:156-182 |
| timestamps in list/get/update | `created_at`/`updated_at` are epoch **nanoseconds** — `int(time.time_ns())` on insert (models/notes.py:553-554); stale "timestamp in epoch" comment at :445-446. Beacon normalizes ns→ms by magnitude (feature/surfaces `noteUpdatedAtMs`). | models/notes.py:553-554 |
| `GET /api/v1/notes/{id}` | also returns `is_pinned` + `write_access` (admin/owner/grant, :309-320) | routers/notes.py:278-326 |
