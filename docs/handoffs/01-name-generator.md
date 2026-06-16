# 01 — Procedural MMO name generator

**Status:** design ready, not started. Isolated (new class; minimal wiring). Read `README.md` first.

## Goal
A procedural generator that produces humanlike MMO IGNs for auto-spawned ownerless bots, combining
(and not limited to) these styles — mix freely, the permutations are endless:
- prefix/suffix flavor: `xXSarahXx`, `__Drake__`, `Sarah_xD`
- class-themed roots: `GodArcher`, `KingOfBlade`, `FrostSage`
- leetspeak / char substitution: `B4ndiT` (a→4, e→3, i→1/!, o→0, s→5/$, t→7)
- random / alternating capitalization: `yOuRfAtHeR`, `ooMANAoo`
- number suffixes: `david124`, `Mage999`
- doubled edge chars: `ooMANAoo`, `xxBladexx`
- unisex / male / female roots
Include a chunk of **plain/boring** names too (real players aren't all stylized) — e.g. ~40-50% pass
through with at most light styling.

## Hard constraints (MUST satisfy)
- Name charset/length: regex `[a-zA-Z0-9]{3,12}` — see `client/Character.java:~1073`.
- DB `characters.name` is `VARCHAR(13)` → **12 char max** after styling.
- Validate every candidate with `Character.canCreateChar(name)` (`Character.java:1066-1074`): it checks
  the regex, the `BLOCKED_NAMES` blacklist, AND **uniqueness** (DB). Regenerate on rejection (cap retries,
  e.g. 20, then fall back to a plain `Root + number`).
- ASCII only (already implied by the regex).

## Design (recommended)
New class `server.bots.BotNameGenerator` (package-private static API):
```
static String generate();              // random job-agnostic name
static String generate(Job firstJob);  // class-themed roots biased to the rolled class (optional)
```
Internals:
1. **Root pools** — `Map<Job, List<String>>` of class-flavored stems + a shared unisex/male/female
   pool. Keep ~20-30 roots per class. Examples per class:
   - WARRIOR: Drake, Blade, Axe, Knight, Guard, Crusade, Warlord, Iron
   - MAGICIAN: Mage, Arcane, Frost, Inferno, Sage, Spell, Rune, Hex
   - BOWMAN: Arrow, Hunter, Ranger, Snipe, Marks, Strider, Hawk
   - THIEF: Shadow, Night, Rogue, Phantom, Stealth, Venom, Dusk
   - PIRATE: Cannon, Corsair, Storm, Bullet, Buccaneer, Outlaw, Salt
2. **Transform pipeline** — a list of independent transforms; pick a random subset/one per name so most
   names get 0-2 transforms (readability): PrefixSuffix, Leet, AltCaps, NumberSuffix, DoubleEdge.
   Compose, then **truncate to 12** and validate.
3. **Randomness** — `ThreadLocalRandom` (matches existing style, e.g. `BotBuildManager.weightedPick`).
   Do NOT use `Math.random()`/`new Date()` patterns flagged elsewhere; `ThreadLocalRandom` is fine.

## Wiring
- The generator is the deliverable unit. The natural caller is bot auto-creation:
  `client.creator.BotCreator.createCharacter(Client, name)` — when invoked for an auto-spawn with no
  chosen name, call `BotNameGenerator.generate(rolledJob)`.
- The rolled job for ownerless bots comes from `BotBuildManager.pickWeightedJob(Job.BEGINNER)` — but
  note BotCreator makes a **Beginner** (job is chosen later at lv10). So either pass a pre-rolled class
  for naming flavor, or just use `generate()` (job-agnostic) at creation. **Recommend job-agnostic at
  creation** (the bot's class isn't decided yet) unless you also move the class roll earlier.
- A population command (`@spawnbot count N autopilot`) that loops N generated names is a **separate,
  optional** follow-up — not required for this item. If you add it, see `SpawnBotCommand` and
  `BotManager.spawnOwnerlessBot`.

## Tests
- `BotNameGeneratorTest` (WZ-free): every generated name matches `^[a-zA-Z0-9]{3,12}$`; transforms
  produce expected shapes; uniqueness/retry path falls back without throwing. Mock/avoid the DB
  uniqueness check in unit tests (test the styling + regex/length; `canCreateChar`'s DB hit is
  integration-only).

## Out of scope
- The population/mass-spawn command. The class-themed-name↔actual-job coupling (SoloMapling proves
  names are normally class-agnostic; only do class flavor if you move the job roll to creation).
