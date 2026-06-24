---
name: verify-ids-against-handbook
description: Always verify NPC/item/map IDs against handbook/*.txt instead of guessing from memory
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a9a36d47-f0f6-4900-80db-2bd8e3b197b3
---

Never assume an NPC, item, map, or skill ID from training-data recall. Always verify against the handbook reference files.

**Why:** I assumed SagaT (Ariant General Dealer) was npcid 2090003 and queried the wrong shop; 2090003 is actually "Dalsuk". SagaT is **2100004**. The user corrected me and pointed at the handbook.

**How to apply:** `handbook/NPC.txt` maps `id - name` (also expect Item/Map/Skill handbook txt files in `handbook/`). Grep the handbook to confirm any ID before using it in a DB query or code. Then cross-check live data with the MySQL MCP ([[reference_mysql_mcp]]) — `shops`/`shopitems` are keyed by npcid.
