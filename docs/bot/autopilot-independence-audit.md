# Autopilot Independence Audit — ETC bag jam + maker/storage economy

**Started:** 2026-06-13 (overnight autonomous session)
**Goal:** make autopilot/@botparty as maintenance-free as possible; fix the "ETC bag stuck full, bot won't sell" case; add maker skill as a real gear/material lifecycle (craft equips, use stimulators/crystals, disassemble-for-materials); auto-use storage by item value.

This doc is the running log + the decisions I need **you** to confirm. Each section tagged
**[DONE]**, **[IN PROGRESS]**, **[PROPOSED — needs your OK]**, or **[DECISION]**.

---

## 1. Diagnosis of the reported bug (evidence-backed)

**Symptom:** Bowgurl (char 29, lvl 64 job 320) has a 96/96-full ETC bag, lots of which looks like
sellable mob-drop trash, yet "nobody went to sell" / bot says "restocked, going back to grind"
and the bag stays full.

**What I checked (live DB `cosmic` + WZ + server logs):**

1. **The sell pipeline is NOT broken.** On 2026-06-12 the audit log shows 291 `bot-sell:` lines —
   bots Leroy/John sold the *exact same ETC mob-drops* in Bowgurl's bag (4003004 Stiff Feather,
   4003005 Soft Feather, 4000048 Jr. Yeti Skin, 4000021 Leather). Detection (`shouldAutoSellTrash`),
   collection (`collectSellTrash{Equips,UseItems,EtcItems}`), and the sell tail
   (`startSellTrashSequence`, run at the end of *every* shop visit — `BotShopManager.java:399` SSOT)
   all work and cover EQUIP/USE/ETC.

2. **Bowgurl's ETC bag is dominated by MAKER MATERIALS, which are kept forever.** Of her ~97 ETC
   stacks, the bulk are: magic powders `4007000-4007007` (qty 752/422/387/927/416/292/608/510 — 8
   slots), ores `4010xxx`, plates `4011xxx`, jewel ores `4020xxx`, jewels `4021xxx`, stat crystals
   `4004/4005xxx`, crafting manuals `4130xxx` (143/298/260…), monster crystals `4260xxx` (206/445).
   Every one of these is kept by `BotInventoryManager.isMakerMaterial(...)` **unconditionally** —
   `collectSellTrashEtcItems` returns `false` for them, so they are never sold, converted, or
   deposited. They accumulate without bound and jam the bag (~70 of 96 slots).

3. **The few sellable mob-drops DO have NPC value** (4000041=60, 4000070=50, 4000365=70 mesos, etc.,
   all common 20-60% drops, tradeable) — so they *are* collected as trash and *do* sell whenever a
   shop visit happens.

**Root cause (certain):** maker materials have **no exit path** (never sold/used/stored). Once they
fill the bag, new loot can't be picked up and the bag reads "full" forever.

**Contributing factor (likely):** the bag-full sell trigger and the pot/ammo resupply errand share
one cooldown. `BotAutopilotManager.requestResupplyErrand` no-ops (returns `true`) during
`ERRAND_COOLDOWN_MS = 5 min`. A well-supplied bot rarely needs a pot errand, and the *only* proactive
town triggers are low-pot/low-ammo. So mob-drop trash only clears when a pot errand happens to
coincide; between errands it lingers. (Reactive bag-full trigger exists at `BotPotionManager.java:367`
but it also calls `requestResupplyErrand`, so it's starved by the same cooldown.)

**Open question I could NOT resolve from data:** whether Bowgurl was in autopilot/@botparty mode at
the moment observed (autopilot state is in-memory, not in DB). Every auto-sell trigger is gated on
`isActive` (autopilotMapId != -1). The "restocked, going back to grind" wording implies she *was*
doing errands (which require autopilot), so I'm treating autopilot-active as true. If she was merely
following/manual, no auto-sell fires by design — worth a sanity check on your end.

---

## 2. Fix plan (slices)

| # | Slice | Risk | Status |
|---|-------|------|--------|
| 2 | Decouple bag-full sell from the pot-errand 5-min cooldown | low | [PENDING] |
| 4 | Autonomous leftover→crystal + disassemble-trash when cramped (reuse existing BotMakerManager) | low | [PENDING] |
| 3 | Bound maker-material keep: per-category reserve cap, sell/convert/store the excess | med | [PENDING] |
| 6 | Auto-storage deposit by value when bags need slots | med | [PENDING] |
| 5 | Autonomous equip crafting via Maker (gear progression, stimulators, crystals) | high | [PENDING] |

Slices ordered safest-first. Higher-risk slices ship behind conservative defaults and are flagged
below for your approval.

---

## 3. Decisions I need from you

> You already answered some of these in chat; captured here so they're durable.

- **[DECISION — answered]** Selling maker materials is allowed, *but* gated on whether they'll be
  used to craft equips later. → Implemented as per-category reserve: keep enough for plausible
  crafting, sell/store only the excess.
- **[DECISION — answered]** Auto-use storage by item value / which bag needs slots. → Slice 6.
- **[DECISION — needs your OK]** Reserve sizes for maker materials. Proposed defaults (per distinct
  item id) below in §4. These are guesses tuned to "enough to craft a few pieces"; adjust to taste.
- **[DECISION — needs your OK]** Autonomous equip crafting (slice 5): should the bot spend mesos +
  consume materials to craft gear on its own while you're away? Default proposal: only craft when the
  result is a *clear* equip upgrade by the existing optimizer valuation, maker level + char level
  permit, and it keeps a meso floor. Off by default until you confirm.

---

## 4. Proposed maker-material reserve policy (slice 3) — NOT YET IMPLEMENTED

(Will fill in concrete numbers + rationale here before shipping. Placeholder so you can see the shape.)

- Reagents (powders/ores/plates/jewels/crystals): keep a per-category reserve sufficient for N crafts;
  sell or store excess.
- Manuals/stimulators (`413xxxx`): one-shot useful; keep a small count each, sell/store duplicates.
- Monster crystals (`426xxxx`): maker reagent; keep a reserve, convert leftovers up to it, sell beyond.

---

## 5. Change log (this session)

- 2026-06-13: Diagnosis complete (this doc). No code changes yet.
