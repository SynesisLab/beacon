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

## Pending verification (needs a live dev instance)

- Exact 401 body shape for expired JWTs (refresh path at M4).
- `deployment_id` presence/absence variance (some proxies strip it).
- Whether `/api/v1/models/list` honors `view_option` values Beacon may want (M1).