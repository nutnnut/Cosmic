# Post-compact continuation prompt

Paste the block below as the prompt after compacting. (Saved here so it survives compaction.)

---

You are continuing AI-companion-bot work in the MapleStory v83 Cosmic fork at
`D:\GameServers\Maplestory\Cosmic`, branch `experimental`. The conversation history was compacted;
everything you need is in the repo docs. Worktrees are allowed but **NEVER delete a worktree** (a `wz`
junction inside makes recursive delete wipe the real `wz/` — CLAUDE.md rule #7).

**Task:** implement the open bot backlog — handoff items **06, 07, 08, 09** — per the project rules.

**Read first, in order:**
1. `CLAUDE.md` (project rules) and `docs/handoffs/README.md` (build/test/commit conventions, ownerless
   model, the SSOT table, conflict notes, and what already shipped — don't redo any of it).
2. Each item doc: `docs/handoffs/06-summoning-rock.md`, `07-party-level-gap-leech.md`,
   `08-scroll-opportunity-cost.md`, `09-proactive-scroll-offer.md`.
3. For 08 (CENTRAL, owner-reviewed): `docs/bot/scroll-opportunity-cost.md` — the owner chose **ACTIVE
   farmable steering**. Implement that design.

**How to execute:** you may spawn one subagent per item (worktree or main tree). Conflict notes: `08` and
`09` both touch `BotScrollManager`/`BotInventoryManager` — sequence them or don't run in the same worktree.
`06` and `07` both read combat/skill code — coordinate if parallel. Each item: implement → compile with the
README build command (exit 0) → WZ-free unit tests on the decision seams (NO nav/graph tests, rule #4) →
commit on `experimental` with the required footer. Reuse the SSOTs named in each doc (rule #6) — do not
reinvent valuation/damage/accuracy/hit-chance/farmable-gear math.

**Order suggestion:** 06 (rock use+request) and 07 (party leech) are self-contained quick wins; do them
first. Then 08 (the central scroll model — take the effort) and 09 (scroll offer), sequenced.

**Known follow-up (optional, note don't silently skip):** warrior/mage/thief 2nd-job builds likely share
the SP under-allocation leak the crossbowman had — fix each with its class-appropriate 1st-job filler
(like FOCUS for archer) so banked SP isn't dumped into the next job tier. `BotCombatManagerTest` has 6
pre-existing WZ-data failures unrelated to this work — ignore them.

When all four are done (compiling, tests green), summarize what shipped per item with commit hashes.

---

## Status at hand-off (for reference)
- DONE this session (on `experimental`): items 01–05, plus quest commitment, combat self-preservation
  (with anti-freeze give-up), job-advance-walks-to-NPC, accuracy-aware grinding (map kill-time + target
  penalty + AP DEX floor), grind-target commitment, off-thread scroll valuation, crossbowman SP-leak fix,
  WZ-worktree-hazard rule. See README "Also shipped later in the session".
- OPEN: 06, 07, 08, 09 (this continuation).
