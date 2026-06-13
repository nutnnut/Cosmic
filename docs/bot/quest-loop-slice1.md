# Bot quest loop - slice 1 (smallest legal quest loop)

The smallest LEGAL quest loop a companion bot runs, piggybacked on grinding.

## Files
- `server/bots/BotQuestIndex.java` - WZ-derived index of bot-runnable quests. Cached TSV under `cache/bot-quest/v1/`.
- `server/bots/BotQuestManager.java` - auto quests, piggyback scan, worthwhile check, errand state machine, `quests` status.
- Wiring: `BotManager.runCommonTickSystems` calls `BotQuestManager.tickScan`; `BotAutopilotManager.tick` calls `tickErrand` before grind-travel; `BotAutopilotManager.clear` calls `clearQuestErrand`. Config toggles `BotManager.cfg.AUTO_QUESTS` / `QUEST_PIGGYBACK` (default true). Chat `QUESTS_PATTERN` in `BotChatManager.handleChat`.
- BotEntry: `questErrandMapId/NpcId/QuestId/Phase/ReturnMapId`, `nextQuestScanAtMs`.

## Legality
- All actions through `Quest.start(chr,npc)` / `Quest.complete(chr,npc,null)` - the exact self-gating API real players hit (QuestActionHandler cases 1/2). NEVER force*/owner-mirror variants.
- Server does not enforce NPC proximity (the check is commented out). Legality here is BEHAVIOR: the bot walks within `NPC_TRIGGER_RADIUS_PX = 500` of the NPC before calling the API (cab/shop standard). Enforced in `BotQuestManager.tickErrand`.
- `start/complete` call `syncPartyBotsQuestStart/Complete` internally, but `getPartyBots(source)` returns empty when `source` is a `BotClient` - so a bot calling start/complete never mis-propagates to siblings. Verified safe.
- `complete(chr, npc, null)` (NOT -1) is the no-selection player path; -1 would mis-index a reward action.

## Index filter (BotQuestIndex.qualifies)
A quest qualifies (kill-and-turn-in shape) when:
- NOT scripted: no `scripts/quest/<id>.js` AND no `startscript`/`endscript` string in its Check.img node.
- COMPLETE reqs (Check.img node 1) are mob-only: only keys in `{mob, npc, lvmin, lvmax, job, quest, interval, normalAutoStart, infoNumber}` (the rest are re-checked by canComplete). Any `item/money/pop/pet/...` complete-req disqualifies.
- Has both a start NPC (node 0 `npc`) and end NPC (node 1 `npc`).

autoStart/autoComplete come from QuestInfo.img (`autoStart`, `autoComplete`/`autoPreComplete`) - the SSOT for `Quest.isAutoStart()/isAutoComplete()`. NOT the Check.img `normalAutoStart` requirement node. The auto-both set iterates QuestInfo.img (the set `Quest.loadAllQuests` walks), since NPC-less auto quests have no Check node.

## Index numbers (real WZ, verified by BotQuestIndexTest smoke)
- **273 runnable mob quests** indexed.
- **65 auto-both** quests (autoStart AND autoComplete/autoPreComplete). NOTE: the original scout claim of "29" was a miscount; the verified count from QuestInfo.img is 65.
- Example mob quests: 1019 (Green Snail x10, start NPC 2005 -> end 12100, 30 exp); 1037 (Green Snail x10, 2005 -> 2103, 60 exp); 1016 (Snail x5 + Blue Snail x5 + 120100 x3, 12100 -> 12100, 70 exp).

## Worthwhile check (rough; slice 2 = real advisor scoring)
`BotQuestManager.worthwhile(grindMapId, npcMapId, q, botLevel)`:
- NPC's map within `MAX_ERRAND_HOPS = 3` world-graph hops of the grind map.
- reward exp >= `botLevel * REWARD_EXP_FLOOR_PER_LEVEL (8)` (a once-good quest stops being worth a town trip as the bot out-levels it).
Constants are visible on purpose.

## Errand flow
Scan (autopilot-only; supervised bots skip) sets BotEntry errand state. `tickErrand` (from autopilot tick, before grind dest):
1. travel toward the NPC's map via `BotTravelManager.tickTravel` (own hop cap, aborts unreachable),
2. on the NPC map, walk within radius (taxi pattern: `pinMoveTarget` + `movementStep.step`), then call start/complete,
3. clear errand, autopilot resumes grinding the return map.
`resolveNpcMap` checks current map then return-map town for the NPC. Inventory precheck (`hasRoomForRewards` -> `bot.canHold`) before completing.

## Seams (unit-testable without WZ/DB)
`BotQuestManager.gate` (QuestGate: canStart/canComplete/start/complete/isStarted/currentProgress), `mapMobs`, `hopCount`, `reply`. Tests swap these. Index filter is a pure `qualifies(QuestMeta)` over synthetic records.

## Gachapon ticket 5220000 tradeability (report-only side note)
In this v83 WZ (`Item.wz/Cash/0522.img.xml`, node 05220000), the info node carries ONLY `cash=1` - no `tradeBlock`, no `only` (one-of-a-kind), no `notSale`/karma flag. So item-data flags do NOT block trading it; `ItemInformationProvider.isUntradeableRestricted` reads `info/tradeBlock` which is absent. Any restriction would come only from generic cash-inventory engine rules (`isCash`, `USE_ENFORCE_UNMERCHABLE_CASH`), not a per-item trade-block flag.

## Slice 2 should reuse
- `BotQuestIndex` (rewardExp, mob counts, start/end NPC) as the candidate source.
- Replace the rough `worthwhile` with the BotGrindAdvisor model: per-kill quest exp + reward exp vs real travel cost (BotTravelCost / world-graph), against the bot's actual per-minute grind exp on the map. Hook the same `hopCount`/`mapMobs` seams.
- Resolve NPC->map globally (slice 1 only checks current map + return-map town) for cross-region quests.
