---
name: wz-data
description: Use when reading, parsing, querying, or writing tooling against the game's WZ data (wz/*.wz/*.img.xml) — Skill/Item/Mob/Map/Npc/String data. Triggers include "what does WZ key X mean", "read skill/mob/item data from WZ", "scan Skill.wz", "parse the .img.xml files", "where does the server load <stat> from", "verify a value against WZ", or writing an export/debug script over the WZ tables.
---

# WZ data (`wz/*.wz/*.img.xml`)

How this repo stores and reads game data. The `wz/` tree is **not** the binary `.wz` format — it is a pre-extracted **XML dump**. Each `*.wz` is a directory; each `*.img` is a single `*.img.xml` file. Distilled from the bot skill-classification work (reading Skill.wz + String.wz to fix misclassification).

## When to use this skill

- "What does WZ key `time` / `lt` / `mobCount` mean?" → **Key reference** table.
- "Read/scan `<category>.wz` in a script or test" → **Reading WZ in tooling** (the gotchas bite every time).
- "Where does the server load stat X from?" → **Server read path** (DataProvider → DataTool).
- "Verify a value against the source data" → query the XML directly, or the live DB per `reference_mysql_mcp`.

## Layout & file naming

```
wz/
  Skill.wz/   200.img.xml 210.img.xml 1000.img.xml ...   (file = job/category prefix)
  String.wz/  Skill.img.xml  Mob.img.xml  Eqp.img.xml ...  (human-readable name/desc per id)
  Mob.wz/ Item.wz/ Map.wz/ Npc.wz/ Character.wz/ ...
```

- A `.wz` is a **directory**; a `.img` is a **file** named `<name>.img.xml`. Access by path *without* the `.xml` extension: `getData("210.img")`, `getData("Skill.img")`.
- **Skill.wz**: each file is a job prefix (e.g. `210.img.xml` holds I/L Wizard skills `2101xxx`/`2110xxx`). Inside: a `skill` node whose children are skill-id `imgdir`s. There are 76 skill files.
- **String.wz/Skill.img.xml**: parallel table of `name` / `desc` / `h1..hN` (level blurbs) keyed by skill id. This is the *only* place human-readable text lives — the numeric data files have none.
- WZ root is `"wz"` by default, overridable with the `-Dwz-path=...` JVM property (`provider.wz.WZFiles`). Tests/tools run from the project root, so the default `wz/` resolves.

## XML node grammar

Files are **minified onto a single line**. Node tag = type; `name` attr = key; `value`/`x`/`y` = payload (`provider.wz.XMLDomMapleData.getType`):

| Tag | DataType | Payload |
|-----|----------|---------|
| `imgdir` | PROPERTY | container (children); has `name`, no value |
| `int` / `short` | INT/SHORT | `value=` (via `GameConstants.parseNumber` → handles hex/float) |
| `float` / `double` | FLOAT/DOUBLE | `value=` |
| `string` | STRING | `value=` |
| `vector` | VECTOR | `x=` `y=` → `java.awt.Point` |
| `canvas` | CANVAS | sprite (icon); has child `vector name="origin"` etc. |
| `uol` | UOL | a *reference* to another node (string path) — resolve manually |
| `null` | IMG_0x00 | presence-only marker |

Example skill subtree (`2101005`, pretty-printed — real file is one line):

```xml
<imgdir name="2101005">
  <canvas name="icon" .../>            <!-- sprite, ignore for logic -->
  <string name="elemAttr" value="s"/>   <!-- element: i/f/l/s/h/p -->
  <imgdir name="level">
    <imgdir name="1">
      <string name="hs" value="h1"/>    <!-- String.img blurb key -->
      <int name="mpCon" value="10"/>
      <int name="mad" value="12"/>
      <int name="mastery" value="1"/>
      <int name="prop" value="31"/>
      <int name="time" value="4"/>
    </imgdir>
    <imgdir name="2"> ... </imgdir>      <!-- one node per skill level -->
  </imgdir>
</imgdir>
```

Other sibling nodes under a skill id (presence is semantically meaningful — see SkillFactory): `info`, `common`, `action`, `prepare/action`, `hit`, `ball`, `effect`.

## Key reference (skill data → `server.StatEffect.loadSkillEffectFromData`)

Per-level keys actually read by this codebase:

| Key | Meaning / how it's used |
|-----|----|
| `time` | duration in **seconds**; StatEffect stores `duration = time*1000`. **No `time` key → `-1000`** (the infinite-rebuff trap — see [[kb_bot_skill_classification]]). |
| `mpCon` / `hpCon` | MP / HP cost to cast. |
| `mad` / `pad` | magic / physical attack → `hasMatk()` / `hasDamage()`. |
| `damage` | % damage multiplier. |
| `mobCount` | max targets; `≥2` ⇒ AOE. |
| `lt` / `rb` | bounding-box left-top / right-bottom (`vector`) → the skill hitbox rect. |
| `range` | reach radius (px). |
| `prop` | proc probability %. |
| `mastery` | weapon mastery %. |
| `attackCount` | hits per target. |
| `bulletCount` / `bulletConsume` | projectiles fired / ammo consumed. |
| `skillType` | top-level (not per-level): `2` ⇒ buff; `1`/`3` ⇒ non-attack. |
| `elemAttr` | element char (`i`ce/`f`ire/`l`ightning/`s`/`h`oly/`p`oison). |

**Critical: `isBuff` is NOT a single WZ key.** `client.SkillFactory.loadFromData` derives it from a mix of (`skillType==2`) OR (`effect` present AND no `hit`/`ball`) OR an `alert2` action OR **a hundreds-of-cases hardcoded skill-id `switch`**. `StatEffect.isOverTime()` for skills returns this derived flag. **Never reimplement this** in a parallel script — load the real `Skill`/`StatEffect` and ask it.

## Server read path

`DataProviderFactory.getDataProvider(WZFiles.SKILL)` → `XMLWZFile` (lazy: parses each `.img.xml` on demand in `getData(path)`) → `XMLDomMapleData` (DOM wrapper). Navigate/read with:

- `data.getChildByPath("level/1/time")` — `/`-separated, `..` walks up, returns `null` if any segment missing.
- `data.getChildren()` / `for (Data d : data)` — iterate child nodes.
- `provider.DataTool.getInt(path, data, def)` / `getString` / `getPoint` / `getIntConvert` (the last also strips a trailing `%`). **Use these helpers** — they null-guard and type-coerce; raw `getData()` casts can throw.

WZ reading is documented in the source as "susceptible to give nulls on strenuous read scenarios" — always pass a default and tolerate `null`.

## Reading WZ in tooling (gotchas that bite)

When writing an export/scan script or test over the raw XML:

1. **Files are one giant line.** `Read`-ing a `.img.xml` dumps a single enormous line — useless. Extract a subtree instead (regex-slice + insert newlines on `><`), or go through the loaded `Skill`/`StatEffect` objects.
2. **Skill ids = `<imgdir name="(\d{4,})">`** (4+ digits). 3-digit `imgdir`s are job folders / level indices.
3. **`SkillFactory.getSkill(id)` returns null until `SkillFactory.loadAllSkills()` is called** — the map is populated lazily. Call it first in any test/exporter.
4. **String.img desc extraction needs a *tempered* regex** so a desc-less skill doesn't borrow the next skill's text:
   `<imgdir name="(\d{3,})">(?:(?!<imgdir name="\d{3,}).)*?<string name="desc" value="([^"]*)"`. Descriptions there 4-digit-pad short ids (`getSkillName` prepends `000` for len-4 ids).
5. **No `python` on this box.** Use PowerShell regex for quick slices, or a JUnit test for anything that needs the loaded objects (the existing `BotSkillClassificationExportTest` is the model — it reuses real predicates as SSOT, writes `tmp/*.{tsv,md}`, always passes; it's a debug dump, not a regression gate).
6. **Verify ids/values, don't guess** — cross-check against String.wz, the handbook, or the live DB.
7. **Per-level vs info-level scope.** Most numeric skill keys (`damage`, `range`, `mobCount`, `mpCon`, `attackCount`) are repeated under *each* `level/<n>` — there is no single info-level value, and they can change across levels (e.g. Slash Blast `range` 130→150). Read the specific level; a first-match slice silently grabs level 1. Melee weapon hit reach is **not** here — it's the per-action `lt`/`rb` in `Character.wz/Afterimage/<weapon>.img.xml`, bucketed by `reqLevel/10`.

## Cross-references

- [[kb_bot_skill_classification]] — the `time`→duration trap, `isOverTime`/`isBuff` SSOT, and the export tool that consumes this data.
- [[kb_bot_cleric_heal_architecture]] — WZ refs for heal/buff formulas.
- `bot-combat` skill — hitbox model consuming `lt`/`rb`/`range`/`mobCount`.
