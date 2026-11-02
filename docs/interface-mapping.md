# Beacon ↔ Claude-app interface map → Open WebUI features

Reference captures: Anthony's Pixel 11 Pro, Claude app `com.anthropic.claude`
(home / drawer / composer / "Select model" sheet / live transcript — 2026-11-02).
Each interface element maps to its OWUI protocol counterpart Beacon implements or
schedules (see also CLAUDE.md's coverage audit).

| Claude-app element (observed) | Beacon interface (shipped) | OWUI feature/endpoint behind it |
| --- | --- | --- |
| Time-greeting hero: ✳ + "Evening, Anthony" (serif) | empty-chat hero: ✳ + "Hello, I'm <model name>." (user-voiced request) | the model name via `/api/v1/models/list` `info.meta`; the user's name via `GET /api/v1/auths/` (auths.py:178, adopted at onboarding → `ServerSettings.userName`) |
| Drawer: Chats/Projects/Code/Artifacts + Recents | Home chat list (M1) + folders UX (M4); Projects/Code/Artifacts = NOT OWUI surfaces (no equivalent) | `/api/v1/chats/{list,pinned}` + `/folders/` (M4); recents = paginated list rows |
| Composer: one rounded container; "Type or hold to talk"; ＋; mic; dark wave-circle (voice/open-interpreter) | Composer container (landed): ＋ attach (docs), draft, mic (both engines), coral send/stop; model pill in-bar next | attachments → `POST /api/v1/files/` multipart (files.py:276); mic tap = `SpeechRecognizer` on-device; hold = server WAV → `POST /api/v1/audio/transcriptions` (audio:1456); the voice-wave circle = realtime voice → P2 |
| "Select model" sheet: names + blue plan chips + descriptions + ✓; "Effort: Recommended/Medium" row; "More models >" | Select-model bottom sheet w/ name + `info.meta.description` rows + the checkmark; params/Effort editor = M4 params sheet; More models = M4 paging/workspace | models list rows (models.py:227-241; description verified models/models.py:144-146); reasoning `effort` = request `params.reasoning_effort`; paging = `/list?page=` |
| Response footer icons: copy / share / speaker / 👍 / 👎 / ↻ | Icon action row (landed, own stroke icons) | copy = clipboard; share = Android chooser (the OWUI share = link-sharing, M3/M4); speaker = `POST /api/v1/audio/speech` (audio:762); 👍/👎 = `POST /api/v1/evaluations/feedback {type:'rating'}`; ↻ = sibling-branch regeneration (`regeneration_message_id` on the wire) |
| "Claude is AI and can make mistakes." | "AI can make mistakes. Your server does the thinking." | client copy; no server field |
| Thinking starburst while generating | pulsing ✳ | the SSE fold state (`busy`), server stream events |
| Artifacts (Code/Artifacts top-level) | HtmlCard WebView panes + File viewers (M2) — inline cards, expand-full-screen | tool embeds (`message.embeds`, middleware.py:730-840), display_file (tools/builtin.py), terminal proxy (`/api/v1/terminals/{id}/files/read`) |
| Title edit (✎) + ⋮ menu in the chat top bar | title via `fetchServerTree` (Room); rename + ⋮ menu = M4 chat-actions sheet | `POST /api/v1/chats/{id}` (update, chats.py:1415) + pin/archive/delete (1751/2048/1677) |
| Search in the drawer | sidebar search (§9.3) → M4 (search endpoint verified chats.py:900) | `GET /api/v1/chats/search?q=` |
| Projects (grouped chats) | OWUI "folders" (the closest analog) | `folders.py` (M4) |
| Memory/About | memories screen = M4 (audit) | `memories.py` + `memories.enable` gate |
| Plan/Upgrade pill | NOT in scope (self-hosted has no plans) | — (deliberately no equivalent) |

Not copied knowingly: Anthropic's fonts (Copernicus/Tiempos — the platform serif stands
in), their logo/starburst asset (glyph text ✳), account/billing surfaces.