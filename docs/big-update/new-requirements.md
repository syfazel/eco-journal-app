# New requirements recorded during Phase 2 (NOT forced into Phase 2 code)

Status: RECORD ONLY, except where noted. The freeze stays ON. No
live cron, bot, channel, or service was touched. No push, no cloud
build, no APK distribution happened in the Phase 2 pass.

These items arrived from Yasin during Phase 2. Items (a) and (b)
belong to later phases; item (c) is DESIGNED here and must NOT be
implemented until all phases complete. The cross-phase bar rule
(§0) IS implemented in Phase 2 and applies to every later phase.

## §0. Cross-phase rule — Red Alert / War Room categories (IMPLEMENTED in Phase 2)

Yasin's rule, recorded here so no later phase can drop it:

> Red Alert / War Room must have its OWN SEPARATE CATEGORIES
> inside the app — NOT a parallel service, NOT a fully separate
> tab. War-room/red-alert items AND energy-domain items get
> distinct category treatment with a yellow or red visual bar /
> strip on feed cards and reader headers, while staying inside
> the same feed/tabs system.

Implementation (Phase 2, the reference for later phases):

- Bar colours [SPEC DECISION — the Phase 2 spec fixes badge
  colours but no bar colours, so Phase 2 fixed them]:
  RED (#C62828) for war-room / red-alert items (every typed
  channel item, any format — the red-alert category);
  YELLOW (#F9A825) for energy-domain items (ordinary articles
  whose category is `energy`).
- A typed item is always RED even if its topic is energy: the
  red-alert category treatment outranks the domain colour.
- The bar label is distinct from ordinary categories, never
  colour alone: the format badge text for typed items, «انرژی»
  for energy articles.
- Surfaces: feed cards (TypedCard + ArticleCard), the typed
  reader (via the card header), and the ordinary article reader
  header. War Room itself is a top-level section (spec §4), not
  a feed tab — that placement is unchanged by this rule, and no
  separate service or separate tab was invented for red alerts.
- Code: `data/Phase2Domain.kt` (`DomainBars`, lines ~24–37),
  `ui/components/ChannelCard.kt` (`DomainBarStrip`, line ~60;
  typed-card use, line ~240), `ui/components/ArticleCard.kt`
  (energy bar, lines ~111, ~162–163, ~224–225),
  `ui/screens/ArticleScreen.kt` (reader header, lines ~219–221).
- Later phases MUST keep this behaviour on every surface they
  add or restyle (nightly edition pages, market cards that carry
  a category, analysis pages, any new card type). If a later
  phase restyles cards, the bar + label travel with the card;
  dropping the bar is a regression, not a redesign choice.

## (a) ECO JOURNAL wordmark in the app — small English handwritten / script font, like the blog logo

- Requirement: the app header wordmark should be a small
  English handwritten/script font resembling the blog's logo,
  header only. NEVER the NYT name or logo (the NYT app is a UX
  reference only, not a brand reference).
- Font fact: the blog logo font is Blacksword.
- LICENSING — blocking note, must be resolved before any
  implementation: Blacksword is PERSONAL-USE-ONLY licensed.
  Using it in the app (a distributed pilot, even a small
  pilot) is NOT covered by personal use on the face of that
  licence. Therefore, before any wordmark work:
  1. Check the Blacksword licence terms for the exact grant
     (personal use vs app embedding vs distribution), OR
  2. Buy / obtain a licence that covers app use, if Yasin
     wants Blacksword itself, OR
  3. (Default fallback, no licence risk) Use a freely-licensed
     OFL script font that resembles Blacksword (evaluate
     candidates at implementation time; do not name a lookalike
     as "Blacksword" and do not ship a lookalike under the
     Blacksword name).
- Placement rule (for the implementing phase): wordmark SMALL,
  header only (masthead). It replaces the current plain-text
  masthead treatment; it does not become a splash screen, an
  icon, or a watermark on article pages.
- Phase assignment: NOT Phase 2 (no wordmark code was added in
  Phase 2; the masthead still renders the plain `app_name`
  string). Assign to the phase that next restyles the feed
  header, or to a dedicated polish pass — Yasin decides. The
  licensing check is a precondition, not an afterthought.

## (b) Tabs system improved over the current one

- Requirement: the tab system should be smoother and better
  than the current one (the vNext front-page ScrollableTabRow +
  HorizontalPager with section-keyed scroll states).
- What "better" must mean at implementation time (acceptance
  shape, to be refined by the implementing phase, not invented
  now): no tab reordering on sync; scroll position preserved
  per tab; swipe and tap stay in sync; neighbouring pages stay
  composed for smoothness; the new War Room / Bundles tabs
  added in Phase 2 behave identically to the older tabs.
- NOT implemented in Phase 2: Phase 2 reuses the existing tab
  machinery unchanged and only adds two tabs to it. Any tab
  rewrite (animation, indicator, gesture handling, state
  restoration) belongs to a later phase and must be phone-tested
  against the current behaviour first, so "smoother" is measured
  against a baseline, not claimed.
- Interaction with §0: a tab rewrite must not move War Room
  into a feed tab or split red alerts into a separate tab
  system — the categories stay inside the same tabs system.

## (c) Helper modules AFTER all phases complete — DESIGN ONLY, DO NOT IMPLEMENT YET

Yasin's instruction: after every phase is complete, add 1–2
helper modules of the implementer's choice that make the UI
more comfortable and help reading news graphically. The two
modules below are DESIGNED here (scope, behaviour, acceptance
shape) and explicitly NOT implemented in Phase 2 or any earlier
phase. Implementing either one early would violate the
instruction; each module's own phase must phone-test it after
the full pipeline is live.

### Module H1 — Reading Comfort: "Reading Ruler + Calm Mode"

- Problem it solves: long briefs and SITREP briefings are hard
  to track on a phone; the current reader is one continuous
  scroll with no reading position aid and no low-glare mode
  for night reading (night law already suppresses notifications
  at night, but nothing helps the reader who IS reading at night).
- Behaviour (design):
  - A thin, draggable reading ruler: a translucent highlight
    band the reader can park on the current line; it follows
    scroll only when the reader drags it, never auto-scrolls.
  - Calm Mode toggle (reader only, persisted per device):
    warmer paper background, reduced contrast, larger line
    height; it changes NOTHING about content, wording, or
    order — pure presentation.
  - Both are presentation-only: no content is rewritten,
    summarised, or reordered; no reading behaviour leaves the
    device (telemetry rule unchanged: diagnostics-only, no
    reader-behaviour tracking).
- Acceptance shape (for its phase): on a real phone, the ruler
  parks and survives rotation; Calm Mode toggles without
  losing scroll position; neither feature appears on any
  non-reader surface; neither changes any byline, badge, bar,
  or format block.
- Why this one: it helps every surface Phase 2 built (feed
  cards are short; the reader is where length lives), and it
  is the lowest-risk comfort win — no new data, no new sync,
  no new notifications.

### Module H2 — Graphical Reading Aid: "Story Arc Strip"

- Problem it solves: a story that unfolds across FLASH →
  WATCH → SITREP → VERIFY is currently a text timeline; the
  reader cannot see at a glance how the story escalated, when
  the crisis level changed, or where the latest update sits.
- Behaviour (design):
  - A compact horizontal strip at the top of the story
    timeline (reader only): one node per timeline version, in
    chronological order, coloured by format badge colour
    (spec §3.2 colours, reused — no new colour vocabulary),
    with the crisis-level chip on nodes that carry a level.
  - Tapping a node opens that version; the latest node is
    marked «آخرین». The strip is a NAVIGATION aid over the
    existing timeline data — it introduces no new data, no
    scores, no probabilities, no level inference (levels shown
    are shipped levels only).
  - The strip never replaces the text timeline; it sits above
    it. If a story has one version, no strip renders (a
    single-node strip is noise).
- Acceptance shape (for its phase): on a real phone, a
  multi-version story shows nodes in chronological order with
  correct badge colours and shipped levels; tapping a node
  opens that version; a single-version story shows no strip;
  no node is ever drawn for a retracted version.
- Why this one: it is the graphical counterpart to H1 — it
  makes the war-room story structure (the heart of Phase 2)
  legible at a glance, using only data Phase 2 already ships.
- Explicit non-goals for H2: no charts of prices (Market tab
  history is Phase 4), no trend lines, no "escalation score",
  no prediction of any kind.

### Sequencing rule for (c)

- H1 and H2 are implemented only after Phases 1–7 are complete
  and phone-verified, in that order (H1 first: it touches no
  war-room logic; H2 second: it builds on the timeline).
- Each module gets its own phone test; neither module may
  change sync, notification, night-law, bar, badge, or bundle
  behaviour. A module that needs a new edge field is out of
  scope for that module — both designs above deliberately need
  none.
