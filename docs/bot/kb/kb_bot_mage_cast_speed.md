---
name: kb_bot_mage_cast_speed
description: "v83 client fact (binary-proven): magic spell casts ignore the wand/staff speed tier and always cast as Normal(6); only the Booster buff shifts it, Speed Infusion does NOT. So mage weapon scoring is speed-blind (rank on MATK), and magic attack timing uses grade 6 + booster, not the weapon's tier."
metadata:
  node_type: memory
  type: reference
---

**Mage weapon speed does NOT affect cast speed.** In the v83 client a magician always casts as if
holding a **Normal(6)** weapon, regardless of the equipped wand/staff's `attackSpeed` tier. Only the
**Booster** buff (Spell Booster) changes it; **Speed Infusion does not affect magic**. This is a
client-truth fact, not a guess — it was reverse-engineered from the actual binary.

## Binary proof (Angel.idb, v83 client)

- `get_attack_speed_degree` @ `0x00765066` computes the attack-speed grade from `weaponSpeed + booster
  (+ partyBooster/Speed-Infusion)`. Its full xref list is **melee, shoot, normal-attack, and
  prepare/keydown** paths — **`TryDoingMagicAttack` @ `0x0095571f` is NOT a caller.**
- `TryDoingMagicAttack` @ `0x00955b24` instead does `add eax, 6` — the base grade is a **hardcoded 6
  (Normal)** plus only the `nBooster` stat (`SecondaryStat+0x204`, the same slot Spell Booster writes),
  clamped `[0,12]`. That grade feeds both the cast animation (`SetAttackAction`) and the outgoing
  MagicAttack packet's speed nibble. The equipped weapon's speed field (`avatar+0x474`, which melee
  reads) is never touched on the magic path.
- Melee/shoot/prepare additionally pass Speed Infusion (`SecondaryStat+0xcc4`) as the party-booster arg;
  **magic adds only `nBooster`**, so Speed Infusion has no effect on magic casts.
- **Only exception:** Big Bang (`2121001`) is the sole magic skill with a `keydown`/`prepare` node, so
  its charge animation goes through the prepare pipeline and IS weapon-speed-dependent. Irrelevant to
  normal bot grinding.

Corroborated by community sources (Southperry "Weapon speed on mages"; Nexon "Are Mages affected by
attack speed?"). NiaMeowDB Classic hedged "may be one fixed speed" — the disassembly confirms it is.

## What this means in code

- **Equip scoring is speed-blind for mages.** `BotEquipManager.magicScore` ranks mage weapons on magic
  power (INT + MATK) alone; do NOT fold an attack-speed / cast-cycle factor into the mage branch (a
  faster wand does NOT out-DPS a higher-MATK staff for a mage). The physical `weaponCycleMs`
  DPS-normalization deliberately returns early for mage jobs. See
  [[kb_bot_grind_gear_valuation.md]] / `kb_bot_equip_optimizer.md`.
- **Combat timing uses grade 6 + booster for magic.** `BotAttackExecutionProvider.resolveSkillEffectiveAttackSpeed`
  routes magic-attack skills (`isMagicAttackSkill`, job family 2/12/22) to `resolveMagicAttackSpeed`
  (`MAGIC_ATTACK_SPEED_GRADE = 6` + `BuffStat.BOOSTER`, no weapon speed, no Speed Infusion). Melee,
  ranged, and basic weapon attacks still read the real weapon speed via `resolveWeaponAttackSpeed`
  (the client's `TryDoingNormalAttack` basic path IS a `get_attack_speed_degree` caller, so basic mage
  swings correctly keep weapon speed — only skill casts are grade-6).

RE tool: `D:\ReverseEngineer\` (`reference_reverse_engineering_toolkit`). Run dumps with
`py -3.9 dump_func.py <name>` (ignore the harmless `section class not implemented: id2` stderr line).
