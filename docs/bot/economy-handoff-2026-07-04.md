# Economy work handoff — 2026-07-04 (Fable → Opus)

Continuation instructions for the living-economy / scroll work on branch `dev-economy`.
Written for a fresh session with no context. Follow the reading order; do not re-derive.

## Read first (in this order — ~10 min, everything else is pointers)

1. `docs/bot/kb/project_living_economy_build.md` — THE build ledger + resume pointer. Commit
   map, implementation insights that cost real debugging (tick-thread book ownership, undercut
   repricing, per-tick-stable decisions, trade-safety locking), pending list.
2. `docs/bot/kb/kb_bot_scroll_special_scrolls_and_market_feedback.md` — the 2026-07-04 scroll
   audit: WZ/DB facts for special scrolls, gaps 1-6 FIXED same day, gaps 7-8 open.
3. `docs/bot/living-economy-design.md` — design of record (esp. §5 pricing, §8.5 negotiation,
   §9 traits, §14 slices). `docs/bot/living-economy.html` is the human-readable overview.
4. CLAUDE.md project rules — especially: share player code (rule 1), skip nav tests (rule 4),
   SSOT (rule 6), root-cause fixes (rule 9), WZ worktree hazard (rule 7).

## State as of this handoff

- Branch `dev-economy`, latest economy commits: `573ba3d05` (scroll audit fixes: shared
  ItemConstants.canScroll, consensus-blended scrollPriceMeso, scroll WTP in maybeBargainBuy,
  applyCostMeso zero for tradeBlocked, bestRoleWorth in marketStatValue, obtainable-only
  scrollsByCategory) and `2a4bb3773` (living-economy.html + self-scrolling.html refresh).
- 76/76 targeted tests green. NOTHING from today is live yet — **server restart required**.
- CAUTION: at handoff time the working tree had UNRELATED uncommitted movement/physics edits
  (BotPhysicsEngine, BotMovementManager, BotPathLogger + tests) from a parallel session. Never
  `git add -A`; stage economy files explicitly.

## Task 1 — live verify after restart (do this before building anything)

Watch via the web endpoints (`docs/bot/web-endpoints.md`; port 8089) + chat:

1. **S3 shout trades (never yet observed live):** human `S> <equip name> <price>` near a bot
   that wants the slot → invite + priced trade within ~1 min, no dupe/loss; FM rooms show bot
   `S>` emissions and bot↔bot completions (tape `bot_market_event` kind=0 TRADE, kind=5 SHOUT).
2. **Scroll bargain-buys (new):** a stall listing a scroll under its combat/replacement ceiling
   gets bought by a browsing bot with NO prior belief ("grabbed a deal at someone's shop").
   Tape shows STALL_SALE for the scroll; `/api/market/bot?name=` books move.
3. **Consensus-priced apply costs (new):** chat a bot `scroll debug` → logs/bot-scroll/
   scroll-debug-<name>.txt; apply-cost column should track consensus once the tape has scroll
   clearings (cold market: unchanged shop/farm numbers). A tradeBlocked scroll (e.g. [4yrAnniv])
   must show apply-cost 0 while its reproduction table keeps full price.
4. **Accessory scrolls:** a bot owning a 20492xx scroll + a ring/pendant/belt with slots and a
   fallback piece should now propose it (boom-gated). Also re-check the earlier S2/S4 verifies
   still hold (stalls open, Fredrick collects, Hall Staff band ranks by matk).
Fix root-cause anything broken (rule 9); append findings to the build ledger KB.

## Task 2 — S4 second half (the next build slice; design §8.5 + §9)

In rough order, each its own commit + targeted tests:

1. **BotTradeNegotiator** — counters/rude-cancel/trait scaling over the existing
   BotShoutTradeManager accept-at-ask flow. CRITICAL safety insight (ledger, don't relearn):
   never lock before verifying the counter-stage; completion vs cancel are both getTrade()==null,
   disambiguated by whether THIS side locked (`shoutTradeLocked`). Settle price BEFORE staging.
2. **BotPersonality traits** — haggleStance, mesoFocus, buffSpend, collectorTaste (bot_config
   blob, no schema change; see design §9). Wire mesoFocus into use-vs-sell tilt; haggleStance
   into the negotiator; humanizeAsk style is a natural future personality field.
3. **Grind-advisor market term** — farm-target bias toward demand-spiked items (mesoFocus-gated).
4. **Flip `SCROLL_FOR_PROFIT_ENABLED`** (BotScrollPlanner:114) — scroll-to-sell pass; only after
   listings/verify show the market can actually absorb scrolled equips.
5. **Retire placeholders** — SCROLL_OPPORTUNITY_MARGIN/FRACTION, SCROLL_CEILING_PER_EV,
   AMMO_CEILING_*, Chaos/White 10M floor → belief/market-derived equivalents. Do this LAST;
   each is an anchor for tests/calibration.

Then (later slices, see ledger Pending): S3 deferred (consumable shout trading — needs non-equip
WTP/WTS; PC> replies; B> emission; approach-walk; auto-equip bought upgrades), S5 (gossip, ammo/
buff buying, /api/market* routes — update web-endpoints.md, rule 8), P3 (FARM_MESO_PER_SECOND →
own observed meso/hr EMA), audit gaps 7 (reqRUC) + 8 (survival-stat gain axis — design decision,
ask the owner first).

## How to work (distilled from feedback memories — these are owner expectations)

- **Build/test:** commands in `~/.claude/projects/D--GameServers-Maplestory-Cosmic/memory/
  reference_build_tools.md` (JDK 21 Corretto, mvn.cmd via `cmd //c`). ALWAYS targeted
  `-Dtest=...`; never the full suite (nav/graph tests dominate). Current economy set:
  `ItemConstantsTest,BotScrollPlannerTest,BotScrollValuerTest,BotScrollManagerTest,
  BotScrollOfferTest,BotFarmingCostModelTest,BotMarketMathTest,BotMarketSimTest,
  BotAssetViewTest,BotFreeMarketManagerTest`.
- **DB truth:** mysql MCP (`mcp__mcp_server_mysql__mysql_query`, db `cosmic`) to verify
  drop_data/shopitems; WZ per `.claude/skills/wz-data/SKILL.md` (files are one giant line —
  regex-slice, never Read whole).
- **Threading iron rules (ledger):** heavy valuation NEVER on the bot tick thread (DECIDE_POOL +
  perf tag); books are tick-thread-owned; per-tick functions must return per-tick-STABLE
  decisions (bank random rolls at commit time); bus offers consumed at claim.
- **Subagent tiering** (owner preference): delegate mechanical/lesser tasks to sonnet, moderate
  to opus; review the diff yourself — today an opus agent introduced a unit-vs-bundle price bug
  that review caught (compare PER-UNIT asks, psi.getPrice() is the whole bundle).
- **KB upkeep:** update `docs/bot/kb/` (repo scope, never PC memory for project knowledge) as
  you learn/fix; keep the build ledger's Pending section current; new/changed web routes →
  `docs/bot/web-endpoints.md`.
- Commits: small, per-fix, on `dev-economy`; docs + code that belong together may share one.
