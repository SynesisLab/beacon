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