# Beacon UI Design Spec — v3.0 "sidebar" (2026-11-06)

> **v4.0 "FoldAware" amendment (2026-11-10)** lives at the repo root:
> `beacon-foldable-design.md` (spec) + `beacon-foldable-mockup.html`
> (frames F1–F5). It is ADDITIVE to v3: a posture system
> (`phone | book | tabletop` from WindowLayoutInfo + width class) where
> unfolded/book demotes the sidebar to a resident 72dp icon rail, artifacts
> dock into a right companion pane, and tabletop splits at the hinge.
> v3 is unchanged on phones (<600dp); goldens `V4/*` cover the new modes.

Native Android Open WebUI client. Claude-Android visual language (cream
canvas, floating chrome, black pills, coral accent) + Open WebUI anatomy.
**v3 change: the bottom tab bar is gone. Primary navigation is a left
slide-in sidebar**, lifted straight from the two apps it mimics — Claude's
drawer (wordmark, nav rows, recents, avatar bottom + New chat pill) and
the Open WebUI web sidebar (New Chat, Search, Notes, Workspace, gated
rows, date-grouped Chats, avatar with status). Chat screens get the
hamburger bubble back in the top-left, exactly like Claude.

Superseded: v2's `Chats/Notes/Files/More` tab bar and its "More" list.
Flagged for veto like every prior call. Tokens and parts pipeline are
untouched. `feature-map.md` §10.0 (amendments) + **§10.14 (sidebar
control table)** + §11 own the wire mapping.

---

## 1. Design tokens — unchanged from v2

Light: `bg #F9F9F7` · `surface #FFFFFF` · `drawer #F3F3F0` (sidebar bg, fills) ·
`ink #131313` · `ink-soft #56544E` · `muted #817F7A` · `faint #B5B2A9` ·
`coral #D97757` (interactive state only) · `coral-tint #F6EBE4` ·
`beacon-blue #3565F2` (critter/launcher only) · `hairline #E8E5DF` ·
`danger #B3313B` · `live #3AA76D`.
Dark: `#1C1B19` bg · `#2A2825` surface · `#EFEEE8` ink · `#8A8781` muted ·
`#E29177` coral · `#3A2E27` tint · `#35332F` hairline.
Type: serif (Georgia-class) wordmark + page titles; sans elsewhere.
Greeting 24 · titles 19–20 · body 15/22–24 · labels 12 · meta 11.5.
Spacing 4/8/12/16/20/28/40 · pills round · cards 16 · bubbles 18 (flat
br-corner 6) · sheets 24 top · inputs 14 · chat touch ≥44dp. Only
floating bubbles carry shadow; lists/cards stay hairline-flat.

---

## 2. Navigation — sidebar primary (v3)

```
onboarding (1 page + deferred 2FA) ─► CHATS HOME (start destination)

SIDEBAR (any screen):  hamburger bubble (top-left) · left-edge swipe ·
                       scrim tap / back to dismiss · predictive-back scale

┌ Sidebar 86% (≤320dp) ────────────┐
│ Beacon (serif) · version         │
│ ⌕ Search chats                   │
│ NAV   Chats · Notes · Files      │
│       Workspace · Memories*      │
│       Channels* · Settings       │      * rows hide without /api/config gates
│ CHATS  ✦ pinned / ▸ folders /    │
│        Today ▸ Yesterday ▸ 7 days│
│ ──────────────────────────────── │
│ (AC) Anthony [admin]    ＋New chat│
└──────────────────────────────────┘
```

- Model switching stays out of the sidebar on purpose (single source of
  truth: top context chip → model sheet).
- No bottom tab bar anywhere. Chat screen chrome = `[☰][title chip ⌄][temp]`.
  Android system back pops routes; the sidebar never traps it.

---

## 3. Global chrome

### 3.1 Top bar (56dp, every screen)
- **Left — hamburger bubble**: 44dp white circle, 3-line glyph → sidebar.
- **Center — context chip**: model name (new chat) ⇄ auto title (open
  chat). Tap = **model sheet** (amended 2026-11-06 — the chevron promises
  models; the options-on-tap layout read as "the dropdown doesn't list the
  models"). Long-press = chat options menu (rename · model · folder ·
  share · export · pin · archive · delete · temp note).
- **Right — temp bubble**: dashed glyph, coral when temporary (M5).
- Banners under bar when truth demands (auth-expired, offline, 2FA nudge).

### 3.2 Composer — one row (unchanged v2)
`( ⊕ )  Message {model}…   ( ↑ / ⌁ / ■ )` — ⊕ → Add-to-chat sheet (the
Web/Image/Code toggles' only home); pill = send / hold-to-talk waveform /
stop; context rail (coral, "18.2k/200k") after send.

### 3.3 Landing (unchanged) — critter + time-aware greeting + ≤3 ✦ prompt chips.

### 3.4 Sidebar anatomy & behavior
- Scrim 40% ink; panel `drawer`; radius 0 right edge; slide 260ms spring.
- Rows: 40dp glyphs muted, labels 15sp; *hover/press tint*; active row ink.
- **Chats section** mirrors the home list: pinned (coral ✦) → folders
  (▸ name · count, header ＋ = create) → date groups; row ⋯ = shared
  row-actions sheet; long-press = same.
- Long lists scroll within the panel; recents page on scroll (`?page=N`).
- Bottom bar pinned: avatar initials + name + role badge · **black
  `＋ New chat` pill** (Claude pattern) → `chat/new`.
- Gates: no flag → row absent, list reflows; never gray.

### 3.5 Sheets/banners/system states — unchanged from v2.

---

## 4. Screens

### 4.1 Onboarding — one page (unchanged: connect + sign-in + inline MFA;
2FA enroll deferred to a one-time Chats-home nudge → enroll sheet).

### 4.2 Chats home (start destination, via sidebar "Chats")
Serif **Chats** · ⌕ · **＋** (folder) · Pinned / Folders (name · count ·
updated · ⋯) / All chats date-grouped · row ⋯ = action sheet · **FAB
＋ New chat** (black pill). Empty = critter ghost.

### 4.3 Chat conversation (unchanged)
Assistant on-canvas, user white bubble; detail-run row · reasoning
collapse · tool row · inline graphic · generated image · citations ·
follow-ups · **turn footer `↻ · ‹n/N› · ⋯`** (⋯ sheet = copy / read-aloud /
rate / export; turn long-press = edit / branch / delete).

### 4.3b Inline rendering — borderless synthetics (unchanged v2.1)
Chart / Mermaid / KaTeX: no box, no header; full column width, content
scales, measured-stable height, tap = ⤢ (ghost glyph on tap), long-press
= copy source / export PNG. Foreign `Embed` keeps the framed pane.

### 4.3c Tool inline cards — Frontier-UI contract (unchanged v2.2)
Tool HTMLResponse → HtmlCardPane; `iframe:height` → pane height (cap
60% viewport); `input:prompt:submit` → composer prefill; links → Custom
Tabs; borderless chrome; **↔ oversize rule**: wide blocks scroll
left↔right in-pane (`overflow-x` + snap rows, edge-fade mask), never
shrink. Vertical drags hand off to the chat list.

### 4.4 Model sheet (unchanged) · 4.5 Add-to-chat sheet (unchanged).

### 4.6 Notes (via sidebar) — list + editor, debounced merge-save,
pin ✦, swipe-delete.

### 4.7 Files (via sidebar) — one list, source chips (`Uploads` +
per-terminal), terminal breadcrumb (single-slash), contextual Upload FAB,
FileViewer route.

### 4.8 Memories / Channels / Workspace / Settings — unchanged; reached
from the sidebar (gated rows hide). Headers editor (ADR-0012) unchanged.
Workspace stays read-only M4.

---

## 5. System states · 6. Motion & haptics — unchanged (auth banner,
offline pill, critter ghosts, Resume pill; fade+slide 220ms, sheets 260ms,
drawer slide 260ms spring, predictive back, light haptic ticks).

## 7. Components — as v2 index, minus tab bar, plus sidebar kit
(sidebar row, section header, bottom identity bar, gated-row treatment).

## 8. Mapping → `feature-map.md`: §10.0 (amendments incl. v3 rows) ·
§10.1–§10.13 (surviving tables; "tab" phrasing reads as sidebar-reached
routes) · **§10.14 sidebar controls** · §11 complete inventory · §11.3b
Frontier-UI contract.

## 9. Goldens — sidebar (open + gated-off state), chats home, chat parts,
model sheet, add-to-chat, notes, files (both sources), settings, headers
editor (+error), onboarding, dark variants.
