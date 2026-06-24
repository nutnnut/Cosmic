---
name: feedback-owner-features-gated-not-removed
description: "Owner-anchored bot features (item priority, supply sharing, walk-to-owner) are intended and must stay - gated on owner being a real ONLINE player, never removed"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a05517cc-fbd8-4558-83bc-a6266fec5fbc
---

When fixing "needless owner dependencies" in bot behavior (2026-06-13 user instruction): the
goal is NOT to strip owner features. Several owner anchors are deliberate design - the online
owner gets higher priority to items, carries less burden in supply sharing, and walk-to-owner
emergencies are fine. Keep them, but gate each behind the owner being a REAL ONLINE PLAYER:
`owner != null && owner != bot && owner.isLoggedinWorld()`.

**Why:** the fork supports supervised play (owner online, bots assist them - owner perks
intended) AND independent play (autopilot, @botme self-owned, owner offline) with the same
code; only the absent/self/offline cases should fall back to independent behavior (errands,
random-portal wander, stay-put), never the online-owner case.

**How to apply:** before removing/guarding any entry.owner read, classify it: owner perk
(keep, gate on real-online) vs wrong anchor for independent bots (guard or replace). When
reviewing agent work on this area, check it did not strip online-owner privileges.

Related: [[project_bot_independence_infra]].
