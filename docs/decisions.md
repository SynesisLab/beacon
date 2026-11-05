# Beacon decisions (append-only ADR log; agent.md §16.7)

## ADR-0001 — Typed settings via kotlinx-serialization DataStore (not Proto)

*Date:* 2026-11-01 · *Milestone:* M0 · *Supersedes:* agent.md §3 persistence row

**Decision.** `ServerSettings` is persisted through `DataStore<Typed>` with a
kotlinx-serialization `Serializer` (`core/data/SettingsStore.kt`), not Proto DataStore.

**Why.** agent.md pinned the stack to kotlinx-serialization for the entire protocol layer;
Proto adds the protobuf gradle plugin + protoc codegen for exactly one settings type. One
serializer stack, zero native codegen, corruption-tolerant decode (server config is
server-wins at M1+ anyway). Proto can be reintroduced when Room M1 lands if a schema
contract with tooling becomes worth the cost.

**Consequences.** No proto wire compat guarantees; the `ServerSettings` shape must stay
additive (defaults for new fields) — same discipline as proto anyway.

## ADR-0002 — Cleartext http permitted app-wide in M0

*Date:* 2026-11-01 · *Milestone:* M0

**Decision.** `android:usesCleartextTraffic="true"` until M4.

**Why.** The primary M0/M1 audience connects to self-hosted instances on the LAN
(`http://192.168.x.x:8080`) and over-HTTPS reverse proxies where certificates are user
managed. Per-origin cleartext tightening arrives with ADR-0004's per-server settings at M4
(`networkSecurityConfig` with dynamic origins or pinning).

**Consequences.** Between now and M4, credentials could transit plaintext LAN links —
same exposure as browsing the instance from any LAN browser. Document in settings.

## ADR-0003 — Hand-rolled `SecureStore` (Keystore AES-GCM), no security-crypto lib

*Date:* 2026-11-01 · *Milestone:* M0

**Decision.** Secrets (API key, JWT) go through `core/data/SecureStore.kt`: keys generated
inside `AndroidKeyStore` (AES-256/GCM, randomized EncryptionRequired defaults off), random
IV per encryption, ciphertext files under `filesDir/beacon/secrets/`.

**Why.** androidx.security:security-crypto (EncryptedSharedPreferences /
EncryptedFile) — the library agent.md named first — was deprecated upstream, and hand-
rolling for exactly two secret types is small, auditable, and matches the GPL-fresh-code
posture (§16.5). Hardware-backed key per alias; invalidate/reset path handled (read
returns null → treated as signed out).

**Consequences.** No Keystore-backed user-auth (biometric unlock) yet — app-lock is §9.3/M3
and will switch the generator to `setUserAuthenticationRequired` variants. No STRONGBOX
attempt yet (mid-tier devices fail it).

## ADR-0004 — `trustUserCa` + client rebuild timing

*Date:* 2026-11-01 · *Milestone:* M0

**Decision.** The `OkHttpClient` is a singleton at graph-build; `trustUserCa:false` is the
M0 default. The opt-in user-CA trust path is a settings flag read from `ServerSettings`
but applied **on next process start** in M0.

**Why.** Runtime TrustManager swap forces rebuilding *every* derived call site (OkHttp
client identity is load-bearing for SSE pooling later). Accepting a restart to apply is
honest and simple in M0; M4's multi-server/multi-profile work introduces a client factory.

**Consequences.** Settings screen copy says "takes effect after restart" until M4.

## ADR-0005 — `/api/config`, not `/api/v1/configs/`, gates client features

*Date:* 2026-11-01 · *Milestone:* M0 · *Updates:* agent.md §5.3

**Decision.** Feature visibility for the client comes from `GET /api/config` (public,
optional auth). Proof and the server-side split live in `docs/protocol-notes.md`.
agent.md §5.3 was corrected in the same commit per §16.7.

**Why.** Cloned source shows `routers/configs.py` is the admin import/export surface;
`main.py:2289` is what the Svelte UI consumes pre-auth (login buttons, signup, ldap) and
post-auth (folders/channels/notes/web-search gates).

## ADR-0006 — compileSdk 37.0, targetSdk 36 (blueprint's 35/36 superseded)

*Date:* 2026-11-01 · *Milestone:* M0 · *Updates:* agent.md §3 table

**Decision.** `compileSdk = 37` + `compileSdkMinor = 0` (targets the installed
minor-versioned platform `android-37.0`); `targetSdk = 36` (int); `minSdk 29` unchanged.

**Why.** The Nov-2026 androidx stack (Compose BOM 2026.09 / ui 1.12.1, lifecycle 2.11,
okhttp 5.5.1) hard-requires compileSdk ≥37. Android platforms are minor-versioned now —
`platforms;android-37` does not exist as an installable package; the AGP 9 DSL is
`compileSdk` (int) + `compileSdkMinor` (int, verified in gradle-api 9.4.1
`CommonExtension`), not a string form. targetSdk 36 keeps edge-to-edge enforced behavior
(agent.md's targetSdk 35 would under-specify current runtime semantics).

**Consequences.** Play policy: new releases must target ≥35 (currently satisfied); keep
targetSdk current at every ship-prep milestone (M5 re-checks).

## ADR-0007 — Robolectric compose screenshot infra: `debugImplementation` for ui-test-manifest

*Date:* 2026-11-01 · *Milestone:* M0

**Decision.** `debugImplementation("androidx.compose.ui:ui-test-manifest")` +
`testOptions.unitTests.isIncludeAndroidResources = true`; compose screenshot tests use
`createComposeRule()` under Robolectric 4.17 NATIVE graphics.

**Why.** Robolectric ≥4.12 (follow-up to robolectric/robolectric#4489, workaround in
#4736) no longer auto-registers unknown activities: launching `ComponentActivity` for
compose tests requires the activity to reach the merged manifest that Robolectric reads.
`testImplementation` of ui-test-manifest does NOT reach it for application modules — the
declaration must merge via `debugImplementation` (takahirom's checklist; verified in
merged `packaged_manifests/processDebugUnitTestManifest/AndroidManifest.xml` after the
fix).

**Consequences.** Any future Robolectric screenshot test that launches a real app
Activity must keep this entry; pure-state-hoisted screens (the M0 pattern) don't launch
app activities, which also avoids Hilt test setup for now.

## ADR-0011 — Password fields: Compose autofill contentType + platform commit

*Date:* 2026-11-01 · *Milestone:* M1 · *Requested:* by Anthony after first device run

**Decision.** The password-mode fields carry autofill hints via
`Modifier.contentType(ContentType.EmailAddress / ContentType.Password)` (Compose UI
1.12.1 `androidx.compose.ui.autofill`, verified present in the resolved artifact).
After a successful password sign-in, the screen calls the platform
`android.view.autofill.AutofillManager.commit()` to trigger the password-manager
save prompt (Google Password Manager on GMS devices). No androidx.credentials
dependency for the save path; failures are swallowed (never block sign-in).

**Why.** User request: email/password sign-in must trigger Android's password
manager prompting — both the fill prompt on field focus and the save offer on
success. The compose `contentType` modifier is the platform integration for fills;
`commit()` is the standard deterministic save trigger. (The abandoned
`LocalAutofillManager` symbol could not be located in the resolved artifact's class
inventory; the platform manager route reaches the same framework without it.)

**Consequences.** Save prompting requires the fields to have been served by the
framework (autofilled or typed into hint-decorated fields) — verify on device with
Google Password Manager in manual matrix. API-key auth never gets password-save
treatment (not a password).
## ADR-0012 — Custom headers editor: values in Keystore, Authorization reserved

*Date:* 2026-11-04 · *Milestone:* M4 remainder · *Requested:* by Anthony (M4 remainder pick: headers editor + proxy-cookie)

**Decision.** The Settings → Advanced → Custom headers editor writes the whole
header map through one SecureStore (Keystore AES/GCM) blob under the
`custom_headers` alias; the `customHeaders` field is REMOVED from
`ServerSettings` (the plain settings JSON must never carry gateway-key
credential values — §11 "credentials in Keystore only"). The map is cached hot
in `SessionStore._customHeaders` (loaded off-main at construction, updated on
write, wiped on sign-out); `AuthInterceptor` reads the snapshot, never disk.

Rules (editor validate() AND `attachInstanceAuth` belt-and-braces; Conduit
api_auth_interceptor.dart parity, read-as-spec):
- field names are RFC 9110 tokens (letters/digits/`!#$%&'*+.^_`|~-`);
- values with CR/LF/NUL are dropped before they reach OkHttp (OkHttp throws —
  a dropped header must never crash a request, §11 origin-first rule);
- `Authorization` is reserved in any case — Beacon's sign-in owns the
  credential; a custom header may never override it;
- `Cookie` is intentionally allowed: a pasted reverse-proxy session cookie is
  the simple form of proxy-cookie auth. The full WebView capture flow
  (Conduit proxy_auth_page.dart: capture cookies + JWT on the instance origin)
  remains an M4+ follow-up, not done here.

**Why.** The M0 scaffolding pre-placed `customHeaders` on `ServerSettings`, but
its documented contract ("no secrets — those live in SecureStore only") makes
gateway keys un-store-able there; an editor writing values would have shipped
the first real violation. Settings JSON keeps `ignoreUnknownKeys`, so the
removed field degrades to ignored on read — no migration needed (the field was
never populated: no UI wrote it).

**Consequences.** `SettingsViewModel` combines the settings flow with the new
`session.customHeaders` StateFlow for the count display; the headers editor
screen (`HeadersEditorScreen` + `HeadersViewModel`, pure `HeadersEditorState`
rules) routes at `settings/headers`. Values render as plain text fields
(single user, own device, own secrets — same trade as ADR-0009's MFA manual
key). Screenshot tests cover populated + error states in both schemes;
`CustomHeadersTest`/`HeadersEditorStateTest`/`InterceptorRulesTest` cover the
rules tables.

## ADR-0013 â€” HtmlCard inline rendering: WebView-per-card documents (M2-2)

*Date:* 2026-11-04 · *Milestone:* M2-2 · *Requested:* by Anthony ("inline rendering system for things like frontier-ui within the chat window")

**Decision.** The `assets/vendor/shell.html` approach is REPLACED by
`CardDocument` (core/render, pure + JVM-tested) — the document for every
`ChatPart.HtmlCard` is built in Kotlin and loaded per card:

- **Artifacts/Embeds** (model ```html fences, raw div blobs, tool `embeds`):
  the card's own HTML stays the document; we inject (a) the admin
  `ui.iframe_csp` right after `<head>` when the server sends one (`GET
  /api/config.ui.iframe_csp`, stores/index.ts:437-446 — first-CSP-wins, csp.ts
  parity), (b) vendor script tags ONLY for content the HTML references
  (mermaid 12.1.0 / chart.js 4.6.1 / KaTeX 0.19.1 — assets, never CDN),
  (c) the bootstrap (height shim + vendor init) before `</body>`. Fragments
  (no `<head>`) get a charset/viewport/transparent-bg prepend.
  Bridge = **height-only** (model/server HTML gets no clipboard/link API).
- **Synthetics** (segmenter-built mermaid/chart): our document — escaped
  diagram text, the chart JSON riding an `application/json` script tag
  (`</`-escaped; the old direct `new Chart(..., config)` interpolation died),
  warm light/dark palette by `LocalBeaconDark`. Bridge = FULL (height/copy/
  link) because the content is ours.
- **srcUrl cards**: resolved by FullHeightIframe rules (`//` → https:,
  instance-relative "/" + origin; else placeholder).
- **SandboxLevel = WebView settings** (no iframe): UI → DOM storage off;
  MEDIA/TRUSTED (server-provenanced) → DOM storage on. JS always (even UI:
  §7.2 "UI = JS + styles").
- **Popups**: `setSupportMultipleWindows` + `WebChromeClient.onCreateWindow`
  with a throwaway WebView — user-gesture popups open external unless
  instance-origin (loads back in the card); automatic `window.open` dies
  in-place (§7.2 says "user-activated").

**Why.** The shipped shell.html embedded the card into the shell document
under `script-src 'self' data: blob:` — that CSP blocked the shell's own
inline bootstrap (no height reporting, no vendor rendering, ever) while ALSO
giving model HTML direct bridge access + no isolation. The web's two-surface
model (Artifacts sandbox iframe; FullHeightIframe) measures differently on
mobile: no `<iframe>` inside Compose — the per-card WebView IS the sandbox;
height works through the same-document bridge; `iframe:height` senders are
tolerated but unnecessary.

**Consequences.** `ChatPart.HtmlCard` gains `kind: CardKind {Artifact,
Mermaid, Chart, Embed}` (persistence is derivational — no partsJson yet —
so legacy rows default Artifact and render correctly); segmenter changes:
mermaid code text-escaped, chart config JSON-scripted, math wrapping
REMOVED (spans leaked literal HTML into MiniMarkdown bubbles — compose math
renderer is a separate M5 follow-up). `HtmlCardPane` uses the real density
for px→dp (the old hardcoded /2.5 was wrong), a reload guard (height events
must not re-load — infinite loop risk), and the real instance origin for
navigation policy (the old code passed null — instance-origin links wrongly
opened external). AC (agent.md §15 M2): frontier card + chart + mermaid
render inline on the dev instance — pending device verification.
