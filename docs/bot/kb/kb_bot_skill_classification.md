---
name: kb_bot_skill_classification
description: "Bot skill classification SSOT predicates, the non-duration-buff infinite-rebuff fix, and the skill-classification export tool"
metadata: 
  node_type: memory
  type: project
  originSessionId: 97255fe0-8025-4287-97cf-cd919c27d163
---

How the bot buckets every learned skill (attack/AOE/heal/support-buff/passive). Classification SSOT lives in `BotCombatManager` (server.bots), driven by the real loaded `Skill`/`StatEffect` objects — never reimplement it in a parallel script (the `isBuff`/`action`/`skillType` derivation is hundreds of hardcoded ID overrides in `client.SkillFactory.loadFromData`).

**SSOT predicates** (`BotCombatManager`, now package-private so the exporter can reuse them):
- `isActiveAttackSkill` — requires `!isOverTime()` + `declaresOffense()` (hasDamage/hasMatk, or mobCount>1+bbox) + mp/hp cost or beginner. skillType 1/3 rejected.
- `isActiveSupportSkill` — requires `isOverTime()` + (action || skillType==2) + **`getDuration() > 0`** + **`getStatups()` non-empty** + **not `isSummonSkill`** (all data-driven gates). The statup gate distinguishes real self/party buffs (declare a caster statup: pad/pdd/mad/acc/speed/MORPH/…) from mob debuffs (Threaten/Slow/Seal/Doom/Ninja Ambush — carry mobCount+bbox, no caster statup) with no skill-id list, and subsumes the old NON_DAMAGE_ACTIVE_SKILL_IDS exclusion on the support path.
- `isSummonSkill(StatEffect)` — data-driven summon test: statups contain `BuffStat.SUMMON` or `BuffStat.PUPPET`. recompute routes these to **`BotEntry.summonSkillIds`** (own bucket, not rebuffed). 21 skills (Phoenix/Silver Hawk/Beholder/Puppet/Octopus/Bahamut/Cygnus fairies/…).
- `isHealSkill`/`isActiveHealSkill`, `BUFF_BLACKLIST` (Dark Sight only).
- recompute precedence: heal → attack(AOE if mobCount≥2) → (support, then blacklist exclusion) → passive.

**Infinite-rebuff root cause + fix (2026-05-30):** for skills, `StatEffect.duration = time*1000`; no WZ `time` key → `getDuration() == -1000`. The buff loop is timer-based: `castSupportSkill` only sets `nextBuffAt = now + dur*0.9` when `dur > 0`. A WZ-`isBuff`-flagged skill with no duration was classified SUPPORT_BUFF but never advanced its timer → recast every tick. Fix = the `getDuration() > 0` gate in `isActiveSupportSkill` (single classifier, so recompute + tickBuffs + exporter all agree). 9 skills were affected: Dispel, Resurrection, Big Bang ×3, Energy Drain, MP Recovery, Time Leap, GM Teleport (these now classify as `NON_DURATION_BUFF` = excluded). Big Bang/Energy Drain are really attacks but carry the buff flag (isOverTime), so the attack predicate also skips them — pre-existing gap, not a regression.

**Now also fixed (statup gate, 2nd commit):** mob debuffs Threaten/Slow/Seal/Doom/Ninja Ambush were `isBuff`-flagged with positive duration so the duration gate alone still let them through; the `getStatups()`-non-empty gate now excludes them (verified via WZ descriptions: all read "monsters/enemies around"). The bot has no mob-target cast path, only self/party SPECIAL_MOVE.

**Summons (3rd commit, framework only — no casting yet):** summon spawning lives in `StatEffect.applyTo` and only fires when `pos != null`; `SpecialMoveHandler` reads pos only when exactly 5 bytes remain. The bot's self-buff SPECIAL_MOVE sends no pos → creature never spawns, but `applyBuffEffect` still applies the SUMMON/PUPPET buffstat, so summons-as-buffs rebuffed forever for zero benefit. Now routed to `BotEntry.summonSkillIds` (own bucket) and excluded from buffs. **To actually use summons later:** add a `tickSummons` (place/condition-gated) that casts with a spawn position so `applyTo` spawns the creature — the buff-loop self-packet path will never work for them.

**Export/debug tool:** `src/test/java/server/bots/BotSkillClassificationExportTest.java`. Run `mvn test -Dtest=BotSkillClassificationExportTest`. Loads `SkillFactory.loadAllSkills()` + WZ String.img descriptions, scans `wz/Skill.wz/*.img.xml` for skill IDs (regex `<imgdir name="(\d{4,})">`), classifies each via the real predicates, writes `tmp/bot-skill-classification.{tsv,md}`. Excluded buckets are sub-labelled data-drivenly (no id lists): **MOB_DEBUFF** (duration, no statup), **CHARGED_ATTACK** (declares damage/matk, no duration — Big Bang/Energy Drain/Magic+Power Crash), **INSTANT_UTILITY** (one-shot — Dispel/Resurrection/MP Recovery/Time Leap/Teleport/Armor Crash). md report includes a desc column to eyeball each verdict. Always passes; it's a visual-debug dump, not a regression gate. Mock note: `BotCombatManagerTest.skillWithBuffAction` must stub `getDuration()>0` AND a non-empty `getStatups()` or the gates drop it. See [[kb_bot_cleric_heal_architecture]].
