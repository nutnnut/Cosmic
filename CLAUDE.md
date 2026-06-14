# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

## Project skills (read before editing the relevant area)

- `.claude/skills/bot-combat/SKILL.md` — bot attack pipeline, packet shapes (`0xBA`/`0xBB`/`0xBC`), `AttackRoute` selection, hitbox model, ammo/Shadow Partner gates, and the checklist for adding new bot attack skills. Read this before touching `server.bots.*` combat code or debugging bot attack packets.
- `.claude/skills/wz-data/SKILL.md` — WZ data (`wz/*.wz/*.img.xml`): file layout, XML node grammar, skill key meanings (`time`/`lt`/`mobCount`/…), the `DataProvider`/`DataTool` read path, and the gotchas for scanning the XML in scripts/tests. Read this before reading/parsing WZ data or writing tooling over it.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

## Project specific instructions
This fork's sole focus is AI companion bots. These rules govern all bot-related work.
1. Bots must share player code, not duplicate it. Bot should play legally, before implementing any bot behavior, check if a player counterpart exists. If it does, use it. If it's not accessible, extract/refactor it rather than reimplementing.
2. Avoid touching non-bot code unless necessary. Minimize upstream diff, make non-bot code changes only when required to expose/extract functionality for bots. Keep those changes minimal and focused.
3. Bot features should be smart, dynamical, and adaptable to situations, have humanlike behavior and some randomness factor like delay jitter. Emergent behaviors are more fun and preferred over scripted behaviors. Ex. user asked to make bot autopilot to find gear upgrades, instead of hardcoding items/locations, actual calculation are made based on location, potential stat gains, exp/hr, scrolls drops, shared party goals.
4. Skip navigation and graphbuilding tests unless touched, those take really long and dominate tests waiting time.
5. Whenever asked to write kb/doc as repo scope, should write directly in repo so it could be seamlessly shared across computers/git/agents, do not write to pc scope memory unless truly local scoped
- What tools are available locally -> local memory allowed
- Project specific documents -> write in project somewhere git will reach)
6. Uses SSOT whenever appropriate, avoid making parallel implementation (prime example being, scoring equipments/items value)