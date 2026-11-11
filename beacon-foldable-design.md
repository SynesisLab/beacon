# Beacon UI Design Spec — v4.0 "FoldAware" amendment (2026-11-10)

Extends `beacon-ui-design.md` v3.0 "sidebar". Nothing in v3 is deleted;
this document adds a foldable-aware layout layer and defines the
**expanded mode** (unfolded / wide-screen). Wire authority stays with
`feature-map.md` §10/§11; this amendment adds **§10.15 (FoldAware
controls)** and **§11.4 (foldable posture contract)** to that file.

---

## 0. Summary of the change

- New: **posture system** — Beacon observes fold state + hinge bounds
  via Jetpack WindowManager (`WindowInfoTracker`, `WindowLayoutInfo`,
  `FoldingFeature`) and adapts layout, not just resizes.
- New: **three layout modes** — `phone` (v3 as-is), `book` (unfolded
  expanded mode: persistent two-pane), `tabletop` (half-opened, hinge
  horizontal). Device-agnostic: the same modes cover tablets/desktop
  width classes using a width breakpoint instead of a hinge.
- Unchanged: all tokens (§1), navigation semantics (§2 sidebar), chat
  anatomy (§4.3), sheets, motion language. FoldAware changes **where**
  panels live, never **what** they contain.

---

## 1. Posture model

| Posture | Source | Beacon mode |
|---|---|---|
| Flat, width < 600dp | size class | `phone` (v3 spec, unchanged) |
| Flat, width ≥ 600dp | size class | `book` (expanded mode) |
| Half-opened, hinge **horizontal** | FoldingFeature | `tabletop` |
| Half-opened, hinge **vertical** | FoldingFeature | `book` (treat hinge as pane divider) |
| FLIPPED / fully open flat | FoldingFeature | width-class decides (`phone`/`book`) |

- Hinge-aware, not device-specific: no OEM lists, no hardcoded Pixel
  Fold / Galaxy Fold dimensions. `occlusionType == FULL` → treat the
  hinge as a hard gap; `NONE` → soft divider.
- Posture is app state (single `BeaconPosture` flow, collected at each
  route root). Layout switches are **recompositions, not re-navigation**:
  the back stack, scroll positions, and composer draft survive unfold.
- `separations`/`bounds` are consumed as `WindowInsets`-style padding —
  content never renders under an occluded hinge.

---

## 2. Expanded mode — "book" (the foldable expand mode)

Unfolded = the sidebar becomes an **icon rail**, not a full pane. The
v3 drawer demotes from overlay to a slim resident rail.

```
┌ 600dp+ landscape / unfolded ──────────────────────────────────┐
│ ┌ rail 72dp ┐ ┌ content pane (rest) ─────────────────────────┐│
│ │ ◌ Chats  │ │ thread (+ artifact pane docked right ≥840dp)  ││
│ │ ▤ Notes  │ │                                               ││
│ │ ▥ Files  │ │                                               ││
│ │ ▦ Worksp.│ │                                               ││
│ │ ⚙ Sett.  │ │                                               ││
│ │  ⋮       │ │                                               ││
│ │ (AC)     │ │                                               ││
│ │  ＋/⫶    │ │                                               ││
│ └──────────┘ └───────────────────────────────────────────────┘│
└───────────────────────────────────────────────────────────────┘
```

### 2.1 Pane rules
- **Icon rail = fixed 72dp wide** (Material rail width), not a
  percentage of the window. Same drawer background (`#F3F3F0`), same
  entry order as v3 §2 — labels hidden: icons + active-dot only;
  long-press a rail icon for its label tooltip. Wordmark shrinks to
  the glyph, search collapses to a ⌕ icon (search opens as a popover
  over the rail), identity avatar stays at the bottom, ＋ New chat
  becomes an icon-only circle. Hamburger bubble stays gone; rail
  icons are the nav. The full 320–360dp drawer remains available on
  demand: tap the rail's glyph or the ⌕-popover header to expand the
  drawer over the content (same v3 anatomy), swipe it away. When the
  artifact pane is open, the rail is the only chrome left — the
  drawer stays collapsed.
- **Content pane = everything else**: single slot that swaps by
  route (Chats home, Notes, Files, Settings…). Within the chat route
  it splits further (see 2.2/2.3b). Non-chat routes (Settings,
  Headers editor) stay single-pane full-width.
- **Width split of the split**: below ~840dp window width, the chat
  collapses to a single slot (thread only); ≥ ~840dp the artifact
  pane docks alongside. The rail stays a fixed 72dp at every width —
  only the chat's internal split changes.
- **Selection model**: rail icon = route; recent-chats popover = chat
  selection. Tapping a chat loads it into the thread pane; the thread
  animates fade+slide (220ms, as §6). On `phone`, identical taps
  navigate — same list component, different container. This is the
  whole portability trick: **no new screens, only new containers.**

### 2.2 Chat thread in `book`
- Thread fills the detail pane (the 70%) with the same anatomy (§4.3):
  on-canvas assistant turns, white user bubbles, turn footer `↻ · ‹n/N› · ⋯`.
- The v3 chat-list master/detail split inside the content pane is
  **dropped**: with a persistent rail, three columns of chrome was
  overkill. Chat selection happens from the rail (chats icon → recent
  chats popover), and the thread owns the full 70%.
- Bubbles get a **max-width 720dp measure** centered in the pane —
  unfolded text must not become a typographic tragedy across 800dp.
- Inline synthetics (§4.3b) may use up to the 720dp measure, not the
  full pane; the ↔ oversize rule is unchanged.
- The rail's ＋ icon spawns a new conversation in the thread pane in
  place (no navigation).

### 2.3b Artifact side pane (book mode — renderings & file views)
- When unfolded, rich content opens **in a right-docked pane, not a
  fullscreen overlay**: Frontier-UI tool cards (from chat), file
  previews (FileViewer route), and ⤢-expanded inline synthetics all
  dock to the right, side-by-side with the thread.
- Thread keeps ~40% of window (min 300dp) of the 70% chat share; the
  artifact pane takes the rest (min 280dp). Below 480dp chat-share
  width the pane covers full-bleed of the 70% instead (fallback folds
  back to overlay behavior). The rail (fixed 72dp) never changes —
  the artifact pane eats only the chat's 70%.
- Pane anatomy: header `[title ⌄][✕ dock-close]`; body = the artifact
  rendered full-width (charts, Mermaid, KaTeX, HTML card, PDF/image
  viewer); footer = the card's action row (Copy / Export / Save).
- **Anchor ↔ pane sync**: tapping a rendered block in the thread scrolls
  / selects the matching pane content; tapping a pane card highlights
  the source turn. Multiple artifacts stack as a right-edge chip rail
  (small circular thumbnails); one pane shows the active one.
- Composer stays pinned under the **thread pane**, not the whole
  window — chatting and previewing never fight for the keyboard.
- The v3 inline behavior (block rendered in the message flow) is still
  the default for small media; the pane is for expand / preview / dock
  targets. Tap ⤢ on any inline block → pane, not fullscreen.


### 2.3 Sheets & chrome in `book`
- Sheets (model sheet, add-to-chat, row-actions) become **anchored
  popovers** when unfolded: model sheet anchors under the context chip;
  row ⋯ sheets anchor to their row. Sheets ≥ modal only for destructive
  confirms (delete → still a centered dialog with scrim).
- Banners (auth-expired, offline) span the **content pane only**; the
  sidebar stays clean.
- Onboarding (§4.1) always runs full-window single-pane regardless of
  posture — a sign-in form split by a hinge is a support ticket.

### 2.4 Companion pane — inline artifacts dock right (book ≥ 840dp)
This **replaces** any right-side slide-in panel. On `phone`, big inline
blocks (Frontier-UI cards, charts, `display_file` docs) open as a
full-screen route. On `book`, they instead **dock into a companion pane
on the right of the chat thread**:

```
no dock:  ┌ rail 72dp ┐┌ chat thread (rest) ┐
docked:   ┌ rail 72dp ┐┌ chat thread ┐┌ handle ┐┌ companion artifact pane ┐   ← rail stays resident
```

- **Triggers**: tapping an inline synthetic's ⤢, a tool card's ⤢, a
  generated image, or a file row → docks into the companion pane
  instead of navigating. Tapping another artifact swaps its content
  in-place (no pane churn).
- **Dismiss**: ✕ in the pane header, or drag the divider fully right (past
  the pane minimum) → content collapses back inline. The pane is never
  persistent-empty: no artifact open = no pane, thread gets the full width.
- **Width / the split (v4.1)**: the split lives in the CONTENT pane (right
  of the always-resident 72dp rail). Thread floor 320dp, pane min 280dp,
  default 45% of the content width, persisted per session. The divider is
  a 16dp hit area with a 3dp rounded pill handle (not a bare hairline):
  drag to resize (rightward drag shrinks the right pane), release SNAPS to
  the nearest of ⅓ / ½ / ⅔, double-tap toggles 50/50. Pane open/close is
  animated (~200ms) and the thread reflows with it. No artifact open = no
  pane; the thread takes the full content width — the rail stays a fixed
  72dp either way.
- **Rail stays resident while docked (v4.1)**: no auto-hide, no glyph chip —
  nav is always one tap away and the docked state is a stable three-pane
  layout (`rail | thread | pane`) instead of a mode switch. (v4.0 hid the
  rail to give the thread room; at book widths ≥840dp the 72dp rail fits
  comfortably: 840 − 72 − 280 = 488dp of thread at the narrowest.)
- **Composer stays put**: the thread's composer is untouched; the
  companion pane is read/interact-only (iframe panes still honor the
  Frontier-UI contract: `input:prompt:submit` prefills the thread
  composer; links → Custom Tabs).
- **↔ oversize rule** moves into the companion pane: wide tables/
  charts scroll there with the edge-fade mask, at full pane width —
  the inline preview keeps its collapsed form.
- **Multiple artifacts**: pane header gets ‹ › history arrows (last 10
  docked artifacts per chat, in-session) — not tabs, just back/forward.
- **Tabletop**: companion pane is unavailable (no width); artifacts
  open on the **top pane** full-width, composer stays bottom.
- **Lifecycle**: docking is view-state, not navigation — back button
  never closes the companion pane first in `book`; it pops chat routes.
  On fold back to `phone`, an open companion pane becomes the
  full-screen artifact route (state preserved, same content).

---

## 3. Tabletop mode (half-opened, horizontal hinge)

Laptop posture. Hinge splits screen into top (far) / bottom (near).

- **Chats home / lists**: list occupies the **top pane**, FAB ＋ New
  chat relocates to the **bottom pane's trailing edge** (thumb zone).
  Hinge-occluded band is excluded from both panes' scroll viewport.
- **Chat thread**: transcript in the **top pane** (scrolled content is
  naturally read far), **composer moves to the bottom pane** — typing
  against a reclined top half is borrowed from the best foldable apps
  and it's correct. A hairline "reading rail" survives on the bottom
  pane showing the last assistant turn if the top is scrolled away.
- **Video/inline graphics**: media blocks prefer the top pane (stable
  viewing); pinch/⤢ expand centers on the top pane.
- Sheets anchor to the bottom pane (reachable), never straddle the hinge.
- Rotation lock: in tabletop, Beacon never forces orientation; layout
  is hinge-driven.

---

## 4. Fold-aware details (cross-mode)

1. **Continuity**: unfold mid-conversation → same scroll offset, same
   composer draft, no flicker. Use `rememberSaveable` + the posture
   flow; never recreate the Activity for posture changes (opt out of
   config-driven recreation for hinge-only changes).
2. **Hinge gap**: `FoldingFeature.bounds` → padding on the split
   container. With `FULL` occlusion the panes read as two screens; with
   `NONE`, a 1dp `hairline` divider between panes.
3. **Predictive back**: only meaningful in `phone` (drawer) mode; in
   `book`, back pops detail-pane routes first (chat → home list stays).
4. **Touch targets**: 44dp minimum survives all modes; sidebar rows in
   `book` may relax to 48dp (more room, why not).
5. **Dark mode**: tokens unchanged; hinge divider uses `#35332F`.
6. **Goldens to add** (§9): `book` narrow split, `book` full three-pane,
   `tabletop` chat, `tabletop` home, hinge-occluded (`FULL`) variant,
   dark `book`.

---

## 5. Wire mapping additions (→ feature-map.md)

- **§10.15 FoldAware controls**: posture flow, pane selection events
  (sidebar row → detail), popover anchoring, tabletop composer swap.
  No new server endpoints — this is purely client layout. The only API
  touchpoint is that master/detail selection reuses the existing
  `chat/{id}` route resolution.
- **§11.4 posture contract**: `phone | book | tabletop` enum, source
  = WindowLayoutInfo + width class; every screen must declare which
  modes it supports (all support all three except Onboarding =
  single-pane-forced).

---

## 6. Non-goals

- No fold-state-specific features beyond layout (no "hinge widget".
  Maybe later. Flagged for veto like everything else.)
- No right slide-in artifact panel: artifacts dock in the §2.4
  companion pane instead. There is exactly one right-side surface and
  it lives inside the chat detail.
- No changes to the OWUI API surface, message tree model, or M0–M5
  milestone ordering; FoldAware lands as M4.5 (after Files, before
  polish pass) since it's container work only.
