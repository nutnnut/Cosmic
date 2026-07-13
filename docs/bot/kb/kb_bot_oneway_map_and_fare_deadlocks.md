# Two stuck-bot classes (2026-07-09): one-way-map ticket trap + fare-blocked job errand

Both found from the live pile-ups at Leafre dock 240000110 (~36 bots) and Amherst 1000000 (~37 bots).
Fixed on dev: `3230e69e7` (ferry) + `d3c8c599e` (job errand).

## Class 1: one-way map + consumable-gated exit (Leafre dock 240000110)

- The dock's WZ exits are ALL scripted (`dracoout` west00 back to 240000100; `reundodraco` arrivals),
  so the world graph's only outbound edge was the LEAFRE_TO_ORBIS ferry edge — which needs a 30k
  ticket sold on the PREVIOUS map. A ticketless bot inside had literally no routable way out, with
  10-100M meso in the wallet.
- `tickBoarding` had no ticketless leg: at the usher with no ticket it fails the hop, the re-plan
  re-picks the ferry edge from the same boarding map (boardingMapIds "plan forward"), forever.
- Bots entered via the plain 240000100 east00 portal (break/chill wander, follow, arrivals) — entry
  is unconditional, exit was conditional. Anything that lost the plan inside = permanently trapped,
  accumulating across restarts (bots save position there). Level gap vs crewmates grows while stuck.
- Fix: `SCRIPTED_ENTRANCES += (240000110, "west00", 240000100)` (the Lith-anchored symmetry audit
  missed it because the ferry edge "reaches back") + a generic ticketless walk-back-and-rebuy leg in
  `tickBoarding` (previous chain map, `adjacentOrScriptedPortal`-aware).
- **Audit rule**: a map whose only graph exit is fare/ticket/consumable-gated is a trap for any bot
  that can't pay AT THAT MOMENT. Symmetry audits must treat conditional edges as absent.
- Verified all other station/platform maps (Orbis 200000110-151, Ludi 220000110, Ariant, Mu Lung)
  have plain walk-back portals — only Leafre needed the entrance row.
- NPC 2082003 on the dock (Temple of Time travel) has NO script in the repo — travel not coded
  server-side; deliberately not modeled (owner: don't code it yet).

### Instance 2 (2026-07-09 late): Nett's Pyramid hub 926010000 "Pyramid Dunes" (`636f89325`)
- Entry: plain desert portal `piramid00` on 260020500 (script `nets_in`) warps in unconditionally
  and saves the "MIRROR" location — bots wandering/patrolling the Ariant desert stumble in. The
  Dimensional Mirror NPC path is NOT implemented (only `9010022_old.js` has it), so the field
  portal is the sole vector.
- Exit: the hub's only exit `out00` (script `nets_out`, warps to the saved MIRROR = 260020500) is
  scripted -> invisible to the base WZ scan (tm=999999999 skipped at BotWorldGraph ~:741). Dozens
  of bots piled up with "no reachable grind spot" (incl. 113M-meso bots — not a fare problem).
- Fix: `SCRIPTED_ENTRANCES += (926010000, "out00", 260020500)`. No GRAPH_VERSION/cache bump —
  the world-graph disk cache is pure-WZ and scripted entrances are re-applied in-memory on load.
- Latent sibling (NOT fixed, not currently reachable by wanderers): ARPQ room 980010020 out00 ->
  980010000 which has returnMap=999999999 and only scripted exits. Only PQ-internal; audit it if
  bot ARPQ participation is ever built.

## Class 2: fare-blocked job errand + no-prediction abstract grind (Amherst 1000000)

- Deadlock triangle: (a) job errand targets a Victoria instructor, Shanks fare = 150 meso, bot has
  0; (b) errand policy (JOB_CHANGE_FALLBACK_ANYWHERE off) suppresses grinding forever while
  committed — exactly the income that would unblock it; (c) standing on a map with NO advisor
  prediction, LOD1 abstract grind emits 0 kills (`calibratedKillsPerHour`=0 -> 5s idle re-check
  loop), so meso stays 0. Dozens of lv8-30 bots were observed ERROR-spamming the log.
- Fix: `fareBlockedRoute` (route null with current meso but non-null with MAX meso) -> pause the
  errand 4-8 min jittered (`jobErrandFareRetryAtMs`, gate in `beginJobErrand`) and release the tick
  to grind; the retry re-checks the wallet. Genuinely unroutable instructors keep stay-committed.
- **Open (Stage 3 slice 2 adjacent)**: LOD1 abstract grind on a map without a committed advisor
  prediction does nothing (0 kph -> 0 kills -> no exp/meso/drops). Self-heals once travel works, but
  any future state that parks a "grinding" LOD1 bot off its committed map silently earns nothing.
