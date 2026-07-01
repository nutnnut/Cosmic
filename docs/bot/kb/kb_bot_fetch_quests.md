---
name: kb_bot_fetch_quests
description: Bot fetch-quest support (obtain item -> deliver to NPC) + grab-quest-before-departing round-trip fix
metadata: 
  node_type: memory
  type: project
  originSessionId: 3a06dbb0-9f0c-48d6-a4bc-7b9aa9a28827
---

Fetch quests = walk to NPC, obtain required item (usually a mob drop the passive-loot tick collects while grinding), deliver. Added 2026-06-19 (commit 1a3e510e6).

Design (decided with user — "mob-droppable only" + "active routing like kill-quests"):
- **BotQuestIndex** indexes the fetch shape via `qualifiesFetch` (not scripted, both NPCs, >=1 required complete-item, completeReqKeys ⊆ allowed∪"item"). Required items captured on `QuestMeta.items` (id→count). Cache bumped to **v4**, which also now PERSISTS `scripted` (a v3 cache hardcoded scripted=false in parseRow — that was the failing `shouldIndexTheTutorialTalkQuests` bug). BotQuestIndex stays WZ-pure (no DB at build).
- **Mob-droppable scope is enforced at RUNTIME, not index time.** `MonsterInformationProvider.retrieveItemDroppers(itemId)` = cached `SELECT dropperid FROM drop_data WHERE itemid=?` (SSOT reverse lookup, mirrors WhoDropsCommand). `BotQuestManager.effectiveTargetMobs(q)` = q.mobs ∪ droppers(items); empty for bought/crafted items → overlap gate filters the quest out.
- `countsMet` also gates on delivery items being in the bag (seam `itemQuantity`). `activeQuestMobIds` folds in dropper mobs of not-yet-collected items → map-score + combat target bias steer toward them (same as kill quests). Seams: `itemDroppers`, `itemQuantity` (tests stub them DB-free in the init block).
- Looting + sell-guard already worked: `tickPassiveLoot` auto-collects, `isStaleQuestItem` returns false (never sell) while a using-quest is started. No new work needed there.

Round-trip fix (separate user report, same commit): bot did town→grind→town→grind because `pickStartable` only overlap-matched the CURRENT map, so a town-staged bot couldn't see that the destination satisfies a quest. Now overlaps against `entry.autopilotMapId` (the chosen grind destination) too — but a destination-only match is grabbed ONLY when the start NPC is on the bot's current map (strict freebie, no detour; avoids the 0-grind-baseline-in-town "everything looks free" trap). On the grind map dest==current so it's a no-op.

Related: [[kb_bot_quest_commitment_and_danger_targeting]] (activeQuestMobIds SSOT + map/target bias), [[kb_bot_use_value_shelf]] (sell hygiene).
