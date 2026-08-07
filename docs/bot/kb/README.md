# Bot knowledge base

Durable, project-scoped notes for facts that are expensive to rediscover. These are incident and
subsystem references, not task status, handoffs, or release checklists. Prefer the specialized skills
for broad combat/navigation workflows and open a KB entry only for the specific failure mode at hand.
The only actionable backlog is [`../ROADMAP.md`](../ROADMAP.md).

Maintain entries as CURRENT TRUTH: when a fact changes, edit or replace the stale text in place —
never append dated "UPDATE:" blocks at the bottom that leave obsolete claims standing above them.
Every line of an entry must be true as written; git history (keep commit hashes as provenance where
useful) records how it evolved.

## Navigation and movement

- [`kb_bot_nav_oscillation_rootcauses.md`](kb_bot_nav_oscillation_rootcauses.md): recurring loop classes
  and the builder/executor mismatches behind them.
- [`kb_bot_map_partition_travel.md`](kb_bot_map_partition_travel.md): arrival-dependent reachability on
  split maps.
- [`kb_bot_swim_and_forbidfalldown.md`](kb_bot_swim_and_forbidfalldown.md) and
  [`kb_bot_slippery_ground_physics.md`](kb_bot_slippery_ground_physics.md): special physics.
- [`kb_bot_downjump_eligibility.md`](kb_bot_downjump_eligibility.md): down-jump evidence and limits.
- [`kb_bot_nav_selfloop_portal_unfollowable.md`](kb_bot_nav_selfloop_portal_unfollowable.md),
  [`kb_bot_empty_committed_route_replan.md`](kb_bot_empty_committed_route_replan.md), and
  [`kb_bot_nav_search_scaling.md`](kb_bot_nav_search_scaling.md): planner/cache edge cases.
- [`kb_bot_town_nav_airborne_target.md`](kb_bot_town_nav_airborne_target.md),
  [`kb_bot_logout_loiter_unreachable_anchor.md`](kb_bot_logout_loiter_unreachable_anchor.md), and
  [`kb_bot_sleepywood_trap_return_scroll.md`](kb_bot_sleepywood_trap_return_scroll.md): travel recovery.

Do not copy a graph version from a KB note. The current value and full schema history live only in
`BotNavigationGraphProvider.GRAPH_VERSION`.

## Travel and errands

- [`kb_bot_eventmanager_transit_rides.md`](kb_bot_eventmanager_transit_rides.md): scripted transit.
- [`kb_bot_oneway_map_and_fare_deadlocks.md`](kb_bot_oneway_map_and_fare_deadlocks.md): conditional-edge
  trap audit rules.
- [`kb_bot_npc_dwell_walk_in_place.md`](kb_bot_npc_dwell_walk_in_place.md),
  [`kb_bot_errand_dwell_clobber.md`](kb_bot_errand_dwell_clobber.md), and
  [`kb_bot_errand_progress_ssot.md`](kb_bot_errand_progress_ssot.md): dwell/progress ownership.
- [`kb_bot_npc_hop_hail_map_wide.md`](kb_bot_npc_hop_hail_map_wide.md) and
  [`kb_bot_job_errand_bagfull_ferry_deadlock.md`](kb_bot_job_errand_bagfull_ferry_deadlock.md): transport
  fallback and detour priority.
- [`kb_bot_job_change_walk_to_npc.md`](kb_bot_job_change_walk_to_npc.md) and
  [`kb_bot_fetch_quests.md`](kb_bot_fetch_quests.md): legal NPC/quest execution.

## Combat, skills, and progression

- [`kb_bot_cleric_heal_architecture.md`](kb_bot_cleric_heal_architecture.md): healing/buff path.
- [`kb_bot_aoe_cluster_target_bias.md`](kb_bot_aoe_cluster_target_bias.md) and
  [`kb_bot_aoe_reposition_before_fire.md`](kb_bot_aoe_reposition_before_fire.md): AoE selection.
- [`kb_bot_ranged_spacing_weapons.md`](kb_bot_ranged_spacing_weapons.md) and
  [`kb_bot_thief_claw_dagger_split.md`](kb_bot_thief_claw_dagger_split.md): ranged/no-ammo behavior.
- [`kb_bot_mage_cast_speed.md`](kb_bot_mage_cast_speed.md): binary-proven — magic casts ignore
  wand/staff speed (always Normal(6) + Booster); mage weapon scoring is speed-blind.
- [`kb_bot_quest_commitment_and_danger_targeting.md`](kb_bot_quest_commitment_and_danger_targeting.md):
  quest and survival biases.
- [`kb_bot_skill_classification.md`](kb_bot_skill_classification.md),
  [`kb_bot_missing_class_sp_builds.md`](kb_bot_missing_class_sp_builds.md), and
  [`kb_bot_passive_mp_hp_regen.md`](kb_bot_passive_mp_hp_regen.md): skill/build/recovery facts.
- [`kb_bot_alert_stance_emulation.md`](kb_bot_alert_stance_emulation.md): alert presentation.
- [`kb_bot_temple_of_time.md`](kb_bot_temple_of_time.md): the quest-gated Temple of Time corridor —
  the per-bot reachability overlay (`QUEST_GATED_ENTRANCES`/`unlockedTempleGates`) and the
  `BotTempleProgressionManager` questline driver (3500-3521).

## Items, economy, and trading

- [`kb_bot_equip_optimizer.md`](kb_bot_equip_optimizer.md) and
  [`kb_bot_grind_gear_valuation.md`](kb_bot_grind_gear_valuation.md): equipment decision seams.
- [`kb_bot_use_value_shelf.md`](kb_bot_use_value_shelf.md): USE inventory tiers.
- [`kb_bot_shop_search_need_coverage.md`](kb_bot_shop_search_need_coverage.md): two-ring, need-first shop
  discovery and return-scroll runway stocking.
- [`kb_bot_skill_books.md`](kb_bot_skill_books.md): fourth-job book demand, use, sharing, and market flow.
- [`kb_bot_market_price_discovery.md`](kb_bot_market_price_discovery.md): clearing-seeded consensus,
  hourly unsold/fast-sale pressure, and equilibrium damping.
- [`kb_bot_market_shout_haggle.md`](kb_bot_market_shout_haggle.md): `B>` criteria shouts and the S4
  trade-chat haggle protocol — floor-priced criteria, spoken-position parsing, structural commitments.
- [`kb_bot_market_stall_funnel.md`](kb_bot_market_stall_funnel.md): trip→publish funnel losses —
  permit NX economics, placement geometry (spacing, multi-floor strips), restart seeding.
- [`kb_bot_trade_dupe_loss_audit.md`](kb_bot_trade_dupe_loss_audit.md): open-trade concurrency hazards.
- [`kb_bot_market_event_time_tz.md`](kb_bot_market_event_time_tz.md): read the tape's `at` TIMESTAMP via
  `UNIX_TIMESTAMP` — `getTimestamp().getTime()` is skewed by the JVM-vs-MySQL timezone offset.

## Population, social, and operations

- [`kb_bot_player_party_social.md`](kb_bot_player_party_social.md): real-player party safeguards.
- [`kb_bot_chair_sit_and_shout_padding.md`](kb_bot_chair_sit_and_shout_padding.md): idle chair sitting
  (owned-via-gacha, sprite-size hog bias) and obnoxious `@@@` shout-bubble padding — the `obnoxiousness`
  / `sitAppetite` personality traits and the stand-before-acting invariant.
- [`kb_bot_self_owned_owner_assumptions.md`](kb_bot_self_owned_owner_assumptions.md): ownership audit.
- [`kb_bot_double_register_botpop_race.md`](kb_bot_double_register_botpop_race.md): population registry race.
- [`kb_bot_inert_autopilot_recovery.md`](kb_bot_inert_autopilot_recovery.md): leaked-off autopilot,
  recovery, and stuck classification.
- [`kb_bot_ops_console.md`](kb_bot_ops_console.md) and
  [`kb_bot_response_overlay.md`](kb_bot_response_overlay.md): operator/prompt surfaces.
- [`kb_bot_chat_charset_ascii_only.md`](kb_bot_chat_charset_ascii_only.md): client text encoding.

## Performance and persistence

- [`kb_save_throughput_and_deadlocks.md`](kb_save_throughput_and_deadlocks.md): save batching and the
  resolved InnoDB deadlock pattern.
- [`kb_bot_cold_decide_gc_storm.md`](kb_bot_cold_decide_gc_storm.md) and
  [`kb_bot_coldstart_queststatus_fullscan.md`](kb_bot_coldstart_queststatus_fullscan.md): boot warm-up.
- [`kb_bot_population_cpu_hotspots.md`](kb_bot_population_cpu_hotspots.md): steady-state CPU hot-spot
  classes at population scale (per-candidate A* probes, cache scans, per-tick WZ parses) and the
  JFR-vs-`/api/perf` profiling workflow that finds them.
- [`kb_live_values_view_cme_in_bot_ticks.md`](kb_live_values_view_cme_in_bot_ticks.md): unsafe live-view
  iteration.
- [`kb_bot_lod_abstract_grind_calibration.md`](kb_bot_lod_abstract_grind_calibration.md): the unobserved-map
  abstract grind measured a peak kill rate instead of a sustained one; signatures, the corrected
  estimator, and how to measure ground truth without starving the tick pool.

## Maintenance

- Keep one file per reusable fact or tightly coupled failure class.
- Remove commit ledgers, stale line numbers, completed checklists, live process state, and handoff text.
- Correct an existing entry instead of appending a chronological correction trail.
- Link stable symbols/files. Avoid duplicating large source tables or configuration defaults.
- Describe unresolved facts as known limitations, not promises. Only `../ROADMAP.md` defines tasks.
