# Bot Skill Cache WZ Inspection

Date: 2026-05-12

This note documents the WZ shape used to classify bot skills for combat-cache selection.
The source inspected was `wz/Skill.wz/*.img.xml`, matching the XML data loaded by
`client.SkillFactory`.

## Scope

Inspected jobs and files:

- Magician / I/L Wizard / I/L Mage: `200.img.xml`, `220.img.xml`, `221.img.xml`
- Cleric: `230.img.xml`
- Archer / Hunter: `300.img.xml`, `310.img.xml`
- Rogue / Assassin: `400.img.xml`, `410.img.xml`
- Warrior / Spearman: `100.img.xml`, `130.img.xml`

## Findings

`effect.isOverTime()` is not enough to mean "actively castable buff". `MP Eater`
has a top-level `effect` node and no `hit` or `ball` node, so `SkillFactory`
loads it as over-time. Its level data is passive proc metadata: `prop` is proc
chance and `x` is absorbed monster max-MP percent. Example: `2200000` level 1 has
`prop=11` and `x=21`.

Many real attack skills do not have a top-level `action` node. Examples inspected:
`Magic Claw`, `Cold Beam`, `Thunderbolt`, `Double Shot`, `Arrow Blow`, `Lucky
Seven`, `Double Stab`, `Power Strike`, and `Slash Blast`. These are still active
client attacks; classifying attacks by `Skill.getAction()` will wrongly drop them
and make bots fall back to basic attacks.

WZ `skillType` is useful for passive families:

- `skillType=1`: mastery-style passive, such as `Bow Mastery`, `Claw Mastery`,
  `Spear Mastery`, `Polearm Mastery`.
- `skillType=2`: active booster-style buff, such as `Bow Booster`, `Claw Booster`,
  `Spear Booster`, `Polearm Booster`.
- `skillType=3`: final-attack passive, such as `Hunter Final Attack` and
  `Spearman Final Attack`.

Passive damage/proc metadata can look attack-like:

- `MP Eater`: over-time because of top-level `effect`, has `prop/x`, no cast action.
- `Critical Shot` / `Critical Throw`: has `damage/prop`, no MP/HP cost, no action.
- `Final Attack`: has `damage/prop`, often has `hit`, but `skillType=3`.

Active support buffs in the inspected jobs all had an action marker, commonly
`action/0=alert2`, or explicit `skillType=2`. Examples: `Magic Guard`, `Magic
Armor`, `Meditation`, `Slow`, `Bless`, `Invincible`, `Focus`, `Booster`, `Soul
Arrow`, `Dark Sight`, `Haste`, `Iron Body`, `Iron Will`, and `Hyper Body`.

## Cache Rule

Bot cache classification should be split by role:

- Attack skills: require non-over-time damage and exclude passive `skillType`
  values `1` and `3`. Because many real attacks lack a WZ action node, active
  attacks are accepted by client-paid cost (`mpCon` or `hpCon`) or beginner-skill
  status.
- Support/heal skills: require an active cast marker. For normal buffs this means
  over-time plus `Skill.getAction()` or `skillType=2`; for Heal, the special heal
  branch accepts the skill action marker.

This keeps `MP Eater`, crit passives, mastery passives, and final-attack passives
out of bot active caches while preserving real attacks such as `Magic Claw`,
`Thunderbolt`, `Double Shot`, and `Lucky Seven`.
