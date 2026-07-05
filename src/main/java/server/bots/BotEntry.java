package server.bots;

import client.Character;
import client.inventory.Item;
import server.Trade;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.Rope;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

public class BotEntry {
    static final class ScrollReactionStreakState {
        int streak = 0;
        boolean lastWasSuccess = false;
        long lastOutcomeAtMs = 0L;
    }

    final Character bot;
    volatile Character owner;
    // TODO(party-autopilot-stage4): `following` + `grinding` (line ~114) + the autopilot sub-flags
    // (autopilotTransitFollow/autopilotCohortMember/autopilotWaitAnchor) are a scattered boolean soup
    // (~70 sites, 10 files incl. combat/movement hot paths). High reward (this shape caused the
    // sentry-mode grinding=false regression) but DEFERRED: do NOT enum-ify blind — those hot paths have
    // no test coverage. Sequencing: add mode-interaction characterization tests FIRST, then refactor.
    // Note the flags are semi-orthogonal (follow without grind), so the target may be a small state
    // object / named-state set, not one mutually-exclusive enum. Full plan: docs/bot/party-autopilot-redesign.md.
    volatile boolean following = false;
    volatile int followTargetId = 0; // 0 = owner
    volatile boolean airshowActive = false;
    volatile long airshowLastTrailAtMs = 0L;
    final ScheduledFuture<?> task;
    BotMovementProfile movementProfile = BotMovementProfile.base();

    // Physics
    float velY = 0f;
    double hspeed = 0.0;
    double physX = 0.0;
    double physY = 0.0;
    double groundPhysicsCarryMs = 0.0;
    // Peak (min-y = highest point) reached during current airborne period. Used by landOnGround
    // to compute fall distance for fall-damage. Positive infinity when grounded / uninitialised;
    // first airborne-tick lowers it to physY and subsequent ticks keep tracking the peak.
    double fallPeakPhysY = Double.POSITIVE_INFINITY;
    boolean inAir = false;
    int jumpCooldownMs = 0;
    int movementVelX = 0;
    int movementVelY = 0;
    int facingDir = 1;
    boolean crouching = false;
    boolean swimming = false;

    // Swim intent — set by movement layer, consumed by physics engine. Movement
    // expresses "what the bot is trying to do"; physics integrates accordingly.
    // Mirrors how the real client only exposes discrete inputs (steer L/R,
    // jump-burst, hold UP/DOWN) — no continuous velocity overrides.
    int swimMoveDir = 0;                 // -1 left, 0 none, +1 right
    int swimVerticalHold = 0;            // -1 = UP held (slow sink), 0 = none, +1 = DOWN held (fast sink)
    boolean swimJumpRequested = false;   // one-shot upward burst
    long swimNextJumpAtMs = 0L;          // cooldown gate
    boolean swimWallBlocked = false;     // physics hit a wall while steering; rise to clear it next tick

    // Movement intent — set by movement/fidget layer, consumed by physics engine.
    // Maps to the same left/right key hold used by the real client for both
    // ground walking and air steering. Physics reads this in the active mode:
    //   - Ground: applyGroundMotion() integrates through force/friction model
    //   - Airborne: stepAirborne() applies air steering / no-input drag (gated by fixedAirArc)
    // Mutually exclusive by state (inAir vs grounded), so one field suffices.
    int moveDir = 0;                     // -1 left, 0 none, +1 right

    // Counter-strafe brake on slippery ground (set by physics each ground tick): the held
    // direction OPPOSING the slide, 0 when not braking. Drives facing/stance so observers
    // see the counter-strafe (walk-left stance while sliding right) instead of the
    // velocity-derived slide direction.
    int groundBrakeDir = 0;              // -1 left, 0 none, +1 right

    // Rope climbing
    boolean climbing = false;
    Rope climbRope = null;
    Rope blockedRopeGrab = null;

    // Climb intent — set by movement layer, consumed by physics engine.
    int climbVerticalDir = 0;            // -1 up, 0 idle, +1 down

    // Horizontal movement hysteresis
    boolean wasMovingX = false;

    // Committed horizontal step while airborne (set at launch, never changed mid-air)
    int airVelX = 0;
    // Accumulated air-steering correction (gradually adjusted toward target each tick)
    double airSteerVelX = 0.0;
    boolean fixedAirArc = false;
    // Flash Jump: set when committing a FLASH_JUMP edge; consumed once mid-air at apex to inject the dash impulse.
    boolean pendingFlashJump = false;
    // Set at the FJ apex impulse; consumed by tickAirborne to broadcast the type-6 "fj" dash visual that tick.
    boolean flashJumpFired = false;
    // Intra-region express (teleport/flash-jump along a platform): earliest wall-clock time the next blink may fire.
    long skillHopReadyAtMs = 0L;

    // Movement intent
    boolean climbUpIntent = false;
    // The specific rope a nav rope-jump intends to grab. While airborne with climbUpIntent, the bot
    // grabs ONLY this rope (not any rope the arc passes), so a co-located rope at the launch X can't
    // hijack a jump aimed at a farther rope. Null for recovery/knockback jumps (grab whatever is reached).
    Rope climbIntentRope = null;
    int ropeGrabCooldownMs = 0;

    // Down-jump: true when crouch was shown last tick, jump fires this tick
    boolean downJumpPending = false;
    long downJumpGracePeriodMS = 0;
    boolean ropeEntryPending = false;
    Rope ropeEntryRope = null;
    int ropeEntryY = 0;

    // Grind mode
    volatile boolean grinding = false;
    Monster grindTarget = null;
    long nextGrindTargetSearchAtMs = 0L;
    // Stay committed to the chosen grind target until this (set when a target is adopted). Stops the
    // bot thrashing between far mobs while approaching one: re-scoring picks a different "best" each
    // retarget tick as the bot moves, so without this it never commits to reach any of them. Broken
    // early only if the target dies/vanishes. See BotManager.shouldSearchForGrindTarget.
    long grindTargetCommitUntilMs = 0L;
    int attackCooldownMs = 0;
    int moveWindowMs = 0;    // movement-only gap after attack animation; attacks blocked, walking allowed
    // AI-cadence attack planning (see Config.COMBAT_PLAN_EVERY_TICK): the plan built on the last AI tick,
    // reused on the interleaved physics tick instead of rebuilding it. Keyed by target identity so a
    // target switch always forces a fresh plan. Cleared when there is no target.
    Monster cadencedPlanTarget = null;
    BotCombatManager.AttackPlan cadencedPlan = null;

    // Skill cache
    int cachedSkillJob = -1;
    int cachedSkillLevel = -1;
    int cachedSkillSignature = 0;
    // COW: rebuilt rarely (level/gear signature change, tick thread) but ALSO iterated from the
    // DECIDE_POOL (BotGrindAdvisor.killProfile -> estimateBestSkillHitDamage) - a plain ArrayList
    // threw ConcurrentModificationException when a decide overlapped a skill-cache rebuild. A
    // torn read mid-rebuild (empty/partial for one estimate) is harmless; the crash was not.
    final List<Integer> attackSkillIds = new java.util.concurrent.CopyOnWriteArrayList<>();
    int attackSkillId = 0;
    int aoeSkillId = 0;
    int aoeSkillMobs = 1;
    // Damage-profile cache: a skill's damage range is a pure function of the bot's stats, yet it is
    // re-derived on every attack plan (~1.75 plans/tick per engaged bot). Stats only shift on
    // level/gear/buff changes, so memoize keyed by (skillId,skillLevel,route,weapon) and flush the
    // whole map when the cheap stat fingerprint moves. Single-threaded per entry (one tick at a time).
    final java.util.Map<Long, server.combat.CombatFormulaProvider.DamageProfile> dmgProfileCache = new java.util.HashMap<>();
    int dmgProfileStatSig = Integer.MIN_VALUE;
    int healSkillId = 0;
    List<Integer> buffSkillIds = new ArrayList<>();
    // Summon skills (Phoenix, Puppet, Beholder, ...) classified into their own bucket: they are
    // NOT rebuffable (the bot has no summon-cast path that sends a spawn position, so casting them
    // via the buff loop only burns MP without spawning the creature). Held here for a future
    // place/condition-gated summon caster; the generic rebuff loop ignores this list.
    final List<Integer> summonSkillIds = new ArrayList<>();
    final Map<Integer, Long> nextBuffAt = new HashMap<>();
    final Map<Integer, Long> nextSupportBuffAt = new HashMap<>();
    long nextSupportHealAt = 0L;
    boolean supportHealsEnabled = true;
    boolean skillBuffsEnabled = true;

    // Ammo
    boolean noAmmo = false;
    boolean ammoWarnSent = false;
    // INTENTIONAL humanlike behavior — DO NOT remove/simplify. A ranged bot that gets a mob inside the
    // point-blank degenerate band fires one close-range swing (emulating a player who reflexively
    // attacks when a mob is on top of them and can't range), then this flag forces a back-off the next
    // tick. It is character, not a bug or a chatter band-aid. Keep it through any spacing-retreat refactor.
    boolean degenAttackDone = false; // force retreat after a deliberate point-blank close-range hit
    boolean spacingRetreatActive = false; // hysteresis memory: are we mid spacing-retreat? (enter 80px / exit 140px)
    long retreatHoldUntilMs = 0L; // hysteresis: lock the local retreat goal for a short window
    Point retreatHoldPos = null;  // the locked retreat target — reused while hold is active
    // Cross-region retreat hold (SSOT with the committed-route layer): once a flee region/point is chosen,
    // reuse it instead of re-scanning every region (findPath per region) each tick. Cleared on cheap (no
    // pathfind) invalidation — arrival, mob out of projectile reach, flee region crowded, or timeout.
    long crossRetreatHoldUntilMs = 0L;
    Point crossRetreatHoldPos = null;
    int crossRetreatHoldRegionId = -1;
    long dangerRetreatUntilMs = 0L; // proactive self-preservation: keep disengaging a touch-dangerous mob until this expires (anti-flip-flop)
    // Anti-freeze watchdogs (shared logic, independent state per reason). A retreat that never opens
    // distance forces a fight window instead of looping forever. See RetreatGiveUp.
    final RetreatGiveUp dangerGiveUp = new RetreatGiveUp();
    final RetreatGiveUp spacingGiveUp = new RetreatGiveUp();
    int breakoutDirection = 0;    // -1/+1 committed escape side while surrounded, 0 = not breaking out
    long breakoutUntilMs = 0L;    // hard safety timeout for the surround-breakout commitment
    Point aoeRepositionAnchor = null; // committed AoE sweet-spot to walk to before firing, null = not repositioning
    long aoeRepositionDeadlineMs = 0L; // bounded-chase timeout for the AoE reposition commitment

    // Combat-decision debug snapshot — last grind-tick verdict, surfaced in the path log so a bot
    // that "never attacks" is diagnosable: which retreat (if any) closed the attack gate, and on what
    // mob. Set in BotManager.tickGrindMode; read-only telemetry, never drives behavior.
    long dbgCombatDecisionAtMs = 0L;     // when the snapshot below was last written (0 = none yet)
    boolean dbgAttackGateOpen = false;   // false = no shot/swing allowed this tick
    boolean dbgProactiveDangerRetreat = false; // touch-danger self-preservation retreat fired
    boolean dbgRangedSpacingRetreat = false;   // ranged weapon backing off a too-close mob
    boolean dbgInDegenBand = false;      // mob inside the degenerate close-range band (ranged wpn)
    boolean dbgCrossRegionRetreat = false; // retreat vantage is in another nav region (jump/edge away)
    boolean dbgRangedSpacingGaveUp = false; // anti-freeze fired: spacing never opened, fighting in place
    boolean dbgRangedSpacingCrowded = false; // mob inside the ranged retreat band this tick (spacing want)
    long dbgAttackExecAtMs = 0L;      // last BotCombatManager.attackMonster executor attempt
    String dbgAttackExecResult = "";  // sent / sent:no-hp-change / blocked:<reason>
    int dbgAttackExecSkillId = 0;
    String dbgAttackExecRoute = "";
    int dbgAttackExecTargetId = 0;
    int dbgAttackExecTargetOid = 0;
    int dbgAttackExecTargetHpBefore = -1;
    int dbgAttackExecTargetHpAfter = -1;
    int dbgAttackExecDamage = 0;      // planned packet damage lines, before server-side caps/immunity
    int dbgAttackExecCooldownMs = 0;
    int dbgAttackExecMpBefore = -1;
    int dbgAttackExecMpAfter = -1;
    long dbgAttackSentAtMs = 0L;       // last executor attempt that reached the shared attack handler
    String dbgAttackSentResult = "";   // sent / sent:no-hp-change
    int dbgAttackSentSkillId = 0;
    String dbgAttackSentRoute = "";
    int dbgAttackSentTargetId = 0;
    int dbgAttackSentTargetOid = 0;
    int dbgAttackSentTargetHpBefore = -1;
    int dbgAttackSentTargetHpAfter = -1;
    int dbgAttackSentDamage = 0;
    int dbgAttackSentMpBefore = -1;
    int dbgAttackSentMpAfter = -1;
    int wanderDirection = 0;      // -1 left, +1 right, 0 = unset (picked when grind has no target)

    // Shop auto-buy (triggered once per map change)
    volatile boolean shopVisitPending = false;
    volatile Point shopNpcPos = null;
    volatile Point shopTargetPos = null;
    int shopApproachDelayMs = 0;
    boolean shopSequenceActive = false;
    long shopVisitStartedAtMs = 0L;
    long shopSequenceStartedAtMs = 0L;
    boolean shopSellTrashPending = false;
    // True once the shop approach point was picked WITH a nav graph (reachability-filtered).
    // Shop visits trigger on map change, racing the async graph warmup — an unvalidated pick
    // is re-done as soon as a graph is available.
    boolean shopTargetGraphChecked = false;
    // bumped whenever a new player directive resets scripted state (follow/stop/move/farm/patrol/grind);
    // background batches (Maker crafting / disassembly) capture it and self-interrupt when it changes
    volatile int activityEpoch = 0;
    // "stuck near the NPC -> act from where you stand" trackers (BotTravelManager.stuckNear SSOT): one per
    // independent approach so they don't reset each other (a travel hop runs while the instructor approach
    // is dormant, etc.). travelApproachStuck = taxi/ferry transport NPC; npcApproachStuck = the destination
    // instructor/quest/gacha NPC; shopApproachStuck = the shop counter.
    final BotTravelManager.ApproachStuck travelApproachStuck = new BotTravelManager.ApproachStuck();
    final BotTravelManager.ApproachStuck npcApproachStuck = new BotTravelManager.ApproachStuck();
    final BotTravelManager.ApproachStuck shopApproachStuck = new BotTravelManager.ApproachStuck();
    // Cached equip trade classification (BotInventoryManager): the reserve check is ~150ms for a
    // full bag, so a cramped bot re-classifying every tick melts a timer thread. Volatile so a
    // trade running on a timer thread and the bot tick read one consistent immutable holder.
    volatile BotInventoryManager.EquipTradeGroupsCache cachedEquipTradeGroups = null;

    // Follow-mode cross-map travel (BotTravelManager): instead of warping straight to the
    // owner, walk to a portal in the current map that leads to the owner's map and enter it
    // legally. targetMapId == -1 means inactive.
    int followTravelTargetMapId = -1;
    int followTravelNextHopMapId = -1;  // map the current portal hop leads to (== target on last hop)
    int followTravelPortalId = -1;
    int followTravelFromMapId = -1;     // map the walk started in — landing anywhere else re-plans
    long followTravelDeadlineMs = 0L;   // give up walking and warp once this passes
    long followTravelEnteredAtMs = 0L;  // enterPortal fired; waiting for the map change to land
    long followTravelGiveUpUntilMs = 0L; // after a failed attempt, warp directly for a while
    int followTravelGiveUpTargetMapId = -1; // the DESTINATION that failed: the give-up only blocks retravel to THIS map, so a doomed errand (e.g. an unreachable quest NPC) can't poison another consumer's travel to a different map
    String followTravelGiveUpReason = null; // why the last give-up fired (deadline/portal-closed/...) — path-log only
    String followTravelGiveUpHop = null; // the hop that failed (nextHop + taxi/ferry/portal + fromMap), for the stuck log/pathlog
    long followTravelGiveUpAtMs = 0L; // when the last give-up fired — so the log can show how recent it was
    int followTravelBestDist = Integer.MAX_VALUE; // closest manhattan to the hop portal so far; path-log only
    int followTravelBestRouteCost = Integer.MAX_VALUE; // lowest committed-route remaining cost seen for this portal hop
    Point followTravelProgressPos = null; // last fallback position that refreshed the travel deadline
    Point followTravelMoveTarget = null; // the exact moveTarget instance travel pinned (identity-checked on clear)
    int followTravelTaxiNpcId = 0;       // != 0: current hop is a cab ride — walk to this NPC, pay, warp
    Point followTravelTaxiPos = null;    // cab NPC position (static, cached at hop start)
    boolean followTravelFerry = false;   // current hop is a ferry boarding leg (BotFerryManager)
    // Errand NPC approach: a per-bot reachable spot NEAR the NPC (not the exact, possibly off-floor
    // sprite pos) so bots converging on one NPC don't stack on the same pixel and freeze. Cached so
    // it isn't re-rolled every tick; keyed by npcId, cleared on arrival/abort/map-change.
    Point npcApproachPos = null;
    int npcApproachNpcId = 0;

    // Autopilot (BotAutopilotManager): owner-ordered independent play. -1 = off.
    // Deliberately NOT cleared on death: the bot revives in town and walks back.
    int autopilotMapId = -1;            // chosen grind map; travel destination while != current map
    long autopilotNextDecisionAtMs = 0L; // when to re-run the grind advisor
    // Post-mortem for "idle in town overnight": the LAST decision the advisor resolved and when, so
    // @botstatus can self-diagnose a stranding after the fact (the live decision stream is no use for
    // something that happened at 3am). Set at every resolution point in BotAutopilotManager
    // (recordDecision): install-plan success and the "no reachable spot" branches. 0 = never decided.
    long autopilotLastDecisionAtMs = 0L;
    String autopilotLastDecisionReason = "";
    String autopilotDestinationName = "";
    String autopilotObjectiveSummary = "";
    // Why this plan won, in plain words ("good exp", "could be a real upgrade for me") —
    // picked once at install so repeated "what are you doing" answers stay consistent.
    String autopilotObjectiveReason = "";
    boolean autopilotArrivalAnnounced = false;
    // Party autopilot: this bot follows the group plan; the leader (first party-mode entry in
    // the owner's bot list) runs the shared re-decide for everyone.
    boolean autopilotParty = false;
    // Farm-item objective override ("farm <item>"): re-decides only re-pick the SITE for this
    // item instead of running the general advisor; the item is never sold as trash.
    int autopilotFarmItemId = 0;
    // Per-bot personality/behavior profile (schedule, farm/idle, breaks, sociability, risk, career).
    // Loaded at spawn from bot_config (BotPersonality.loadOrCreate); neutral defaults for non-managed
    // bots. Consumed by the break logic, the population scheduler, party formation, and chat.
    BotPersonality personality = BotPersonality.defaults();
    // Persistent-crew id (managed_bot.group_id), cached at spawn. Non-null = this bot is in a crew, so
    // it logs in with + freely shares gear/ammo/supplies with its crewmates (like an owned party). Null
    // = soloist / dynamic-party bot, which never trades with strangers. SSOT: BotManager.crewMatesOnMap.
    Integer crewGroupId = null;
    // Scroll<->farm coupling (item 08, layer 3): the better-base equip the autopilot is actively
    // steering to farm (gearFocused), so the off-thread scroll planner can HOLD scrolls instead of
    // burning them on the inferior base it currently wears in that slot. 0 = none; chance is the
    // per-kill drop chance used to gate "realistically obtainable". Set in installPlan, read in
    // BotScrollManager.shouldHoldForFarmableBase.
    volatile int wantedGearItemId = 0;
    volatile double wantedGearChancePerKill = 0.0;
    // Owner said "sail away": the bot may board cross-continent ferries while the owner is
    // online. Owner-offline autopilot never needs it. Reset in BotAutopilotManager.clear().
    boolean autopilotFerryApproved = false;
    // Resupply errand: temporary detour destination (return-map town) when supplies run low
    // mid-grind. -1 = none. The shop visit triggers on arrival; afterwards travel resumes
    // toward autopilotMapId. NextErrandAtMs rate-limits errands (survives clear()).
    int autopilotErrandMapId = -1;
    // Town-break trip (self-scroll bots): a break taken in a town instead of in place — travel out
    // (reusing the errand machinery), let the arrival shop visit sell trash + resupply, then linger for
    // a 10-30min rest window (grind-tick break-idle) while self-scrolling gear, then return to grind.
    // The in-town rest clock reuses breakUntilMs, started on arrival.
    boolean restErrand = false;
    long autopilotNextErrandAtMs = 0L;
    long autopilotLastErrandLogAtMs = 0L; // throttle for the "couldn't start errand" diagnostic log
    long sellBlockLogAtMs = 0L; // throttle for the cramped-bag "why isn't it selling" diagnostic log
    long autopilotOwnerSupplyGraceUntilMs = 0L;
    boolean autopilotReturningFromErrand = false;
    long autopilotLastDeathAtMs = 0L;
    // Death-loop breaker: consecutive deaths within a short window (rapid re-death = stuck on a
    // lethal route), and a per-bot map blacklist (mapId -> avoid-until ms) so the route flood prunes
    // maps the bot keeps dying on/through and re-decides a safer target. Both managed in respawnBot.
    int autopilotDeathStreak = 0;
    final java.util.Map<Integer, Long> autopilotAvoidMapUntilMs = new java.util.HashMap<>();

    // Operator RTS command (BotWorldGraphWebServer console): a temporary owner-override issued from the
    // web map. For its window the bot ignores autopilot/idle/follow and does exactly this; when the
    // window elapses (or "resume autopilot" is issued) it reverts to normal autopilot. Only managed /
    // self-owned bots are commandable. The HTTP thread sets the int/long fields first and the volatile
    // operatorCmd LAST (safe publication); the bot tick reads operatorCmd first and runs init on its own
    // thread (operatorCmdPending) so multi-field combat state is never mutated cross-thread.
    enum OperatorCmd { IDLE, FIDGET, MOVE, MOVE_ATTACK, FOLLOW, DANCE, JUMP, CHEER } // MOVE = quiet travel; MOVE_ATTACK fights en route
    volatile OperatorCmd operatorCmd = null;       // null = no operator command
    volatile boolean operatorCmdPending = false;   // set by HTTP thread; tick thread runs init then clears
    volatile long operatorCmdUntilMs = 0L;         // window deadline; >= this -> revert to autopilot
    volatile int operatorMoveMapId = -1;           // MOVE destination (already per-bot resolved by the server)
    volatile int operatorFollowTargetId = 0;       // FOLLOW target character id (0 = none)
    volatile Point operatorMovePos = null;         // "moveto": precise (x,y) on the bot's CURRENT map (debug + RTS); null = map-level MOVE
    boolean operatorStuck = false;                 // MOVE gave up (logged) -> idle for the rest of the window
    Point operatorSpot = null;                     // cached random idle/fidget spot (ferry SSOT)
    int operatorSpotMapId = -1;
    final BotTravelManager.ErrandProgress operatorMoveProgress = new BotTravelManager.ErrandProgress();
    // Party autopilot cohesion: while in transit a follower rides the regular follow pipeline
    // behind the leader bot (formation offsets, legal portal-follow, warp catch-up for free)
    // instead of traveling independently; grind mode is restored on arrival. The leader's
    // straggler check is rate-limited and its last verdict cached between checks.
    boolean autopilotTransitFollow = false;
    // Embark cohort gate (set in applyPartyPlan when the party heads out): true only for members that
    // were on the leader's map at embark. Off-cohort members travel INDEPENDENTLY to the shared
    // destination and the leader never waits for them — bots scatter for many reasons (resupply, job
    // advance, fresh login/restart), and a cross-map member must not stall the co-located cohort.
    // Re-snapshotted on each new party destination; off-cohort members reconverge at the destination.
    boolean autopilotCohortMember = false;
    // Party level-gap idle-leech: this (higher-level) member stops dealing damage and idles so the
    // lower cohort members become the damage-dealers and keep getting full exp share. Hysteresis
    // state (BotAutopilotManager.updateIdleLeech); cleared when the gap closes.
    volatile boolean idleLeech = false;
    // A personal idle spot picked once when idle-leech begins and held, so leechers settle at distinct
    // points instead of re-wandering into each other / stacking. Cleared when leech ends.
    java.awt.Point leechIdleAnchor = null;
    // HP snapshot taken when leechIdleAnchor is resolved. A drop below this while parked means a mob
    // reached the bot (wandered over, or a knockback shoved it into a danger region) -> drop the anchor
    // and re-resolve a safe spot rather than sitting there taking hits forever. -1 = unset.
    int idleAnchorHp = -1;
    // Low-HP rest (no-pot survival): a broke, out-of-pots bot parks and passive-regens until HP recovers
    // instead of grinding itself to death. Hysteresis flag + held safe anchor (like leechIdleAnchor).
    volatile boolean hpResting = false;
    java.awt.Point hpRestAnchor = null;
    // In-session breaks (BotBreakManager): a grinding bot periodically stops and idles for a while so
    // it isn't farming non-stop — realizing its personality's farm/idle ratio. breakUntilMs = when the
    // current break ends (0 = not on break); nextBreakRollAtMs gates the once-a-minute start roll;
    // breakIdleAnchor is the held idle spot (spread, like leechIdleAnchor).
    long breakUntilMs = 0L;
    long nextBreakRollAtMs = 0L;
    java.awt.Point breakIdleAnchor = null;
    // Pure-idle (inert autopilot) town destack: a held spread spot so idle bots don't stack on the
    // spawn portal (the NPC-approach loiter SSOT). Re-picked on map change.
    java.awt.Point idleDestackSpot = null;
    int idleDestackMapId = -1;
    // "Logged in to chill": rolled once at session start (BotScheduler). The bot heads to town and
    // lingers there the whole (half-length) session instead of grinding — near-zero tick cost.
    boolean chillSession = false;
    // Ad-hoc party-up (BotSocialManager): throttles how often a solo bot considers offering to party,
    // and stops a bot just offered-to from immediately re-offering.
    long nextSocialAtMs = 0L;
    // Flow 1 (ask-then-wait): a chatty bot asked this player to party and is waiting for an
    // affirmative reply until the deadline; the invite only fires once they say yes in-window.
    int pendingPartyAskPlayerId = 0;
    long pendingPartyAskUntilMs = 0L;
    // Flow 3 throttle: don't let one player spam "party" into a burst of invites from this bot.
    long nextPlayerPartyReplyAtMs = 0L;
    // A scheduled logout is mid-flight: the bot said goodbye + left its party and will disconnect after
    // a short delay. Guards against the scheduler re-triggering the goodbye sequence on the next sweep.
    volatile boolean loggingOut = false;
    // Graceful logout linger: a scheduled logout first retreats to a safe town and stands at a random
    // spot until this deadline, so the hub feels alive and the bot never vanishes mid-dungeon. The tick's
    // logout branch drives the town trip + loiter; logoutDisconnecting guards the goodbye/disconnect once.
    long logoutLingerUntilMs = 0L;
    java.awt.Point logoutAnchor = null;
    boolean logoutDisconnecting = false;
    long autopilotNextStragglerCheckAtMs = 0L;
    boolean autopilotWaitingForStragglers = false;
    // Why the LAST actual straggler RECOMPUTE decided to wait (tripping member + metric), or null
    // when it last decided NOT to wait. Pathlog-only: lets a capture show what the gate truly saw at
    // its last (<=3s-stale) check, which can disagree with a live re-mirror if members oscillate.
    String autopilotStragglerReason = null;
    // While the LEADER waits for stragglers DURING TRANSIT it loiters at the next-hop portal
    // (opportunity-attacking nearby mobs) instead of grind-wandering the whole map, so the
    // group reassembles there and hops together. null/-1 = not anchored; the mapId guard
    // self-clears it the moment the leader changes maps (e.g. on arrival).
    java.awt.Point autopilotWaitAnchor = null;
    int autopilotWaitAnchorMapId = -1;
    // True while an async advisor pass for this entry is running (re-decides only) — stops
    // the tick from stacking decisions while one is still computing.
    volatile boolean autopilotDecisionInFlight = false;

    // Quest piggyback errand (BotQuestManager): while grinding/autopiloting, detour to a quest
    // NPC to start or turn in a mob quest whose kills overlap what the bot already farms here.
    // questErrandMapId = -1 when no errand. Phase: START walks to the start NPC and calls
    // quest.start; TURNIN walks to the end NPC and calls quest.complete. nextQuestScanAtMs gates
    // the (cheap, jittered) scan. Reset alongside the autopilot errand state in clearQuestErrand().
    int questErrandMapId = -1;
    int questErrandNpcId = 0;
    int questErrandQuestId = 0;
    BotQuestManager.Phase questErrandPhase = BotQuestManager.Phase.NONE;
    int questErrandReturnMapId = -1;     // grind map to resume after the errand
    final BotTravelManager.ErrandProgress questErrandProgress = new BotTravelManager.ErrandProgress(); // abort if it can't reach the NPC
    long nextQuestScanAtMs = 0L;
    // Quests this bot has proven it can't finish — the upstream quest didn't register as completed even
    // after a legal complete() call (bugged data/script, e.g. complete() is a no-op), or its NPC is on
    // an unreachable map. Suppressed from all scans so the bot stops re-completing/re-announcing or
    // re-erranding the same doomed quest in a loop. See BotQuestManager.markQuestBugged.
    final Set<Integer> buggedQuestIds = new java.util.HashSet<>();
    // Mob ids the bot still needs to kill for any STARTED indexed quest (unmet counts). Refreshed on
    // the quest scan + on quest start/complete by BotQuestManager.refreshActiveQuestMobs. Read O(1) by
    // combat target selection (BotCombatManager) to PREFER quest mobs, so a bot commits to the quests
    // it accepted instead of drifting onto other mobs. volatile: written on the scan, read on the tick.
    volatile java.util.Set<Integer> activeQuestMobIds = java.util.Set.of();

    // Job-change errand (BotStarterKitManager): an autopilot bot at a 1st/2nd-job milestone WALKS to
    // its class-town instructor NPC and advances on arrival (instead of changing job instantly,
    // anywhere). Suppresses grinding en route so it doesn't over-level. jobErrandMapId = -1 / target
    // null when no errand. Reset in clearJobErrand() (called from BotAutopilotManager.clear).
    client.Job jobErrandTarget = null;
    int jobErrandNpcId = 0;
    int jobErrandMapId = -1;
    final BotTravelManager.ErrandProgress jobErrandProgress = new BotTravelManager.ErrandProgress();
    long jobErrandLastWarnMs = 0L;     // throttle the "can't reach instructor" error log while stuck

    // Gachapon errand (BotGachaponManager): autopilot-only. When the bot has spare account NX (from
    // looted NX cards), it picks the best-EV reachable gachapon town, travels to the NPC, buys
    // tickets (abstracted cash-shop purchase) and rolls. gachaErrandMapId = -1 when no trip is
    // active. Reset in clearGachaErrand() (called from BotAutopilotManager.clear).
    int gachaErrandMapId = -1;
    int gachaErrandNpcId = 0;
    final BotTravelManager.ErrandProgress gachaErrandProgress = new BotTravelManager.ErrandProgress(); // abort if it can't reach the NPC
    int gachaTicketsThisTrip = 0;        // diagnostic count of rolls this trip (budget is the real limiter)
    long gachaNextRollAtMs = 0L;         // humanlike pacing between rolls
    long nextGachaScanAtMs = 0L;
    long gachaNextRareChatAtMs = 0L;     // rate-limit the "got <rare>!" shout
    boolean gachaUpgradeDriven = false;  // trip chosen for gear upgrades (not resale) -> pivot when satisfied
    Point gachaStandSpot = null;          // per-bot jittered foothold near the machine (anti-stacking)
    long gachaTripBudgetNx = 0L;          // personality NX budget for this trip; replaces the flat ticket cap
    int gachaSpentThisTrip = 0;           // NX spent so far this trip (vs gachaTripBudgetNx)

    // Free-market session errand (BotFreeMarketManager): autopilot-only. During a rest break a bot
    // with sellable surplus (or a stall due for service) walks to an FM town, enters, opens/browses
    // stalls, and returns. fmErrandMapId = the target FM TOWN (-1 = no session); fmPhase drives
    // travel -> enter -> room -> setup -> browse -> exit. Reset in clearFmErrand().
    int fmErrandMapId = -1;
    int fmRoomMapId = -1;
    int fmPhase = 0;                     // BotFreeMarketManager.PHASE_*
    final BotTravelManager.ErrandProgress fmErrandProgress = new BotTravelManager.ErrandProgress();
    long fmPhaseDeadlineAtMs = 0L;       // per-phase watchdog
    long nextFmScanAtMs = 0L;            // scan cadence + post-trip satiation
    long nextStallServiceAtMs = 0L;      // when the live stall wants a service visit
    long fmBrowseUntilMs = 0L;           // humanlike browse dwell
    int fmPlaceTries = 0;                // bounded stall-spot attempts
    int fmBargainBuys = 0;               // bounded impulse purchases per trip
    Point fmStandSpot = null;            // chosen stall spot in the room
    int fmStandBestDist = Integer.MAX_VALUE; // walk watchdog: best distance to fmStandSpot so far
    long fmStandStuckSinceMs = 0L;       // walk watchdog: last time fmStandBestDist improved
    int fmFredrickState = 0;             // 0 unchecked, 1 retry on the way out, 2 done this trip
    boolean fmFredrickOnExit = false;    // current Fredrick stop is the exit-leg one
    long nextFredrickProbeAtMs = 0L;     // slow-cadence "does Fredrick hold my stuff" DB probe
    boolean fredrickPickupPending = false; // cached probe result; a pickup of its own is a trip reason
    volatile boolean fmPlanPending = false; // an off-thread listing plan is in flight (tickScan)
    volatile java.util.List<BotFreeMarketManager.ListingPlan> fmPlannedListings = java.util.List.of();
    volatile boolean fmLastTripWorthy = false; // cached off-thread verdict for cheap intent checks
    boolean fmVisitedMarket = false;     // trip reached the FM entrance (fizzles skip satiation)
    // Shout-sell stand (PHASE_SHOUT): the bot stands still at the FM entrance advertising surplus gear
    // so shoppers (human or bot) can click-invite to buy. Budget rides the break/chill session.
    long fmShoutUntilMs = 0L;            // when the stand dwell ends (0 = not yet armed)
    boolean fmShoutedThisTrip = false;   // exit-leg stand already taken/decided this trip
    long fmFidgetAtMs = 0L;              // next allowed humanlike fidget while standing
    volatile boolean fmHasShoutSurplus = false; // cached off-thread: has marketable equips to shout-sell
    // Browse loop: visit stalls one at a time as a real visitor (walk up, register, dwell, consider,
    // leave) instead of reading the whole room in one instant tick.
    long fmBrowseEndMs = 0L;             // overall browse budget for this room (0 = not yet armed)
    int[] fmBrowseOwners = null;         // shuffled stall owner-ids still to visit this browse
    int fmBrowseIdx = 0;                 // index into fmBrowseOwners
    int fmVisitOwnerId = -1;             // stall owner-id we're registered as a visitor to (-1 = none)

    // Supervised-mode quest AUTO-SUGGEST (Feature A): when the owner is online and the bot is at
    // their side, the bot occasionally SUGGESTS a standout nearby quest in chat (it never wanders
    // off to do it - that's autopilot's job). nextQuestSuggestAtMs is the multi-minute cooldown;
    // lastSuggestMapId rate-limits to one suggestion per map; suggestedQuestExpiry tracks ids
    // already suggested (or declined) with an expiry so the same quest isn't nagged repeatedly.
    long nextQuestSuggestAtMs = 0L;
    int lastQuestSuggestMapId = -1;
    final java.util.Map<Integer, Long> suggestedQuestExpiry = new java.util.HashMap<>();

    // Frozen-air watchdog (BotManager.doStuckDetection): airborne position must change every
    // tick (free fall); a bot wall-pinned at a map edge with no foothold below freezes here.
    int airStuckTicks = 0;
    int airStuckX = Integer.MIN_VALUE;
    int airStuckY = Integer.MIN_VALUE;

    // Damage taken
    long deadUntil = 0;
    int mobHitCooldownMs = 0;
    // Absolute time until which this bot may not take another portal (set on portal use).
    // Portal-only gate: does not block movement, attacks, or any other action.
    long portalUseCooldownUntilMs = 0L;
    // Pre-warp pause while standing on a travel portal before stepping through (set in
    // BotTravelManager.walkToPortalAndEnter); the bot keeps centring on the portal until it elapses.
    long portalEnterDwellUntilMs = 0L;
    // Grind-nav (intra-map) portal positional jitter: extra walk ticks remaining before the bot fires
    // a landed, in-range PORTAL edge. -1 = disarmed (airborne / not in range / not on a portal edge).
    int portalEnterReadyTicks = -1;
    // Human "settle after arriving" window: on any map change the bot stands a beat before it
    // resumes grinding/fighting (set via BotManager.armPostWarpQuiet). Travel hops never reach the
    // gated grind section, so multi-hop routes aren't slowed.
    long postWarpQuietUntilMs = 0L;
    // Supply-sharing settle window (pot/ammo/rock requests + donations): on a map change/spawn a whole
    // cohort can land at once, so all of them hold off sharing for ~5-10s instead of firing every
    // request in the same tick. Set via BotManager.armPostWarpQuiet; checked by supplySharingSettled.
    long shareGateUntilMs = 0L;
    // Reading/talking pause while standing at an NPC before the bot fires the interaction
    // (quest accept/turn-in, job advance, taxi/ferry edge). Armed on the first in-range tick,
    // re-armed fresh each approach via BotManager.npcDwellReady/npcDwellReset.
    long npcDwellUntilMs = 0L;
    // Client-side alert-stance emulation: when currentTimeMillis < alertedUntilMs the bot's
    // broadcast stance gets STAND→ALERT substituted so observers see the alert pose.
    // Mirrors CharLook::alerted (TimedBool, 5000ms) in maplestory-wasm. Absolute reset on each
    // trigger (attack/hit/heal/buff), never additive.
    long alertedUntilMs = 0L;
    // Debounce flag for the scheduled stance-reset callback in BotCombatManager.markAlerted.
    // Without this, when the bot stops moving while alerted (e.g. "stay" command), no new
    // movement snapshot ever fires — so the wire stance stays ALERT forever. The callback
    // pushes a fresh STAND broadcast once the timer expires.
    boolean alertResetScheduled = false;

    // Transient admin-debug commander binding: when a gm6 admin name-targets this bot (even one
    // they don't own, or an independent/self-owned bot), the bot interacts with the admin instead
    // of its real owner for a short window. Only reply delivery, follow-anchor, trade-with-owner,
    // and pendingAction confirmations honor this; autopilot/owner-afk/party logic ignore it. The
    // real owner issuing any command clears it (owner wins). Cleared implicitly on despawn since
    // the BotEntry object is discarded. 0 / 0L = unbound.
    volatile int debugCommanderId = 0;
    volatile long debugCommanderUntilMs = 0L;
    // True only after the admin issued an explicit FOLLOW command: a debug binding alone (set by
    // any admin interaction, e.g. a status question) redirects replies/trade to the admin but must
    // NOT hijack the bot's existing follow anchor - it keeps following its leader and just replies.
    volatile boolean debugCommanderFollow = false;

    // Set true when a REAL player put THIS character on autopilot via @botme / @botparty (never for
    // disposable population/botpop bots, which are also self-owned). These run the player's real gear
    // & meso with no human present, so they stay grind-focused: no breaks/gacha/chill/FM/auto-scroll,
    // only grind + grind-essential resupply/sell. See BotManager.isRealPlayerTakeover.
    volatile boolean commandAutopilot = false;

    // Most recent command the owner issued that handleChat actually matched.
    // Used by SituationBuilder to give the LLM context like "owner told you to
    // farm here 3 min ago" so 'what are you doing' answers stay coherent.
    public volatile String lastOwnerCommand = null;
    public volatile long lastOwnerCommandAtMs = 0L;

    public boolean isGrinding() { return grinding; }
    public boolean isFollowing() { return following; }
    public java.awt.Point getFarmAnchor() { return farmAnchor; }
    public int getFarmAnchorMapId() { return farmAnchorMapId; }
    Point lastMobTouchCheckPos = null;
    int lastMobTouchMapId = -1;

    // Loot and potions
    int potCheckTimerMs = 0;
    int mpRecoveryTimerMs = 0;
    int invFullWarnCooldownMs = 0;
    boolean potShareRequestedHp = false; // true once an HP pot-share request has been broadcast this episode
    boolean potShareRequestedMp = false; // reset when pot count recovers above POT_LOW_WARN
    boolean ammoShareRequested = false; // reset when arrow/bolt count recovers above AMMO_LOW_WARN
    boolean rockShareRequested = false; // reset when summoning/magic rock count recovers above ROCK_LOW_WARN

    // Job advancement prompts
    int jobPromptSent = 0;
    int lastKnownLevel = -1;

    // AP/SP builds
    BotBuildManager.ApBuild apBuild = null;
    boolean apPromptSent = false;
    // Aspirational grind target (level, avoidability) — the mob this bot would grind if accuracy
    // were free, produced by the off-thread grind pass (BotGrindAdvisor) and read by the on-thread
    // AP build resolver so the DEX accuracy floor aims at the map the bot wants, not the easy map
    // it's stuck on. avoid < 0 = unset (no grind pass yet) -> callers fall back to the current map.
    volatile int aspirationalMobLevel = 0;
    volatile int aspirationalMobAvoid = -1;
    // Aspirational mob's exp/kill (exp-rate scaled, same basis as MobProfile.exp()). 0 = unset.
    // Baseline for the en-route opportunity-attack "is this kill worth the exp" gate (BotCombatManager).
    volatile double aspirationalMobExp = 0.0;
    // Owner opted into self-managed AP ("auto" at the build prompt): the bot resolves + ratchets its
    // own AP exactly like an ownerless bot, and never re-prompts. Runtime-only (like apBuild itself).
    boolean apAuto = false;
    String spVariant = null;
    boolean spVariantPromptSent = false;

    // Reply channel — tracks the chat channel the last owner command arrived on.
    // Bot replies are routed to this channel until the next command changes it.
    volatile ReplyChannel replyChannel = ReplyChannel.MAP;

    // Pending two-step action
    String pendingAction = null;
    String pendingDropCategory = null;
    Item pendingLootOfferItem = null;
    int pendingLootOfferRecipientId = 0;
    long pendingLootOfferExpiresAt = 0L;
    int lootInhibitMs = 0;

    // Bot self-scrolling (companion scope; owner confirms each item). When enabled the bot proposes
    // worthwhile scroll plays on its own gear and chains to the next after each confirmed scroll.
    // The pending* refs hold the resolved equip + scroll while a "scroll_confirm" pendingAction is open.
    boolean selfScrollEnabled = false;
    Item pendingScrollEquip = null;
    Item pendingScrollScroll = null;
    // Next armed auto-scan time (0 = schedule on the next tick); declines push it out.
    volatile long nextSelfScrollScanAtMs = 0L;
    volatile boolean scrollPlanQueued = false;
    volatile boolean chaosPlanQueued = false;
    // Autocraft (Maker): armed by command, only proposes while a real owner is online (supervised).
    // pendingCraftPlan holds the proposal while a "craft_confirm" pendingAction is open.
    boolean craftEnabled = false;
    BotMakerPlanner.CraftPlan pendingCraftPlan = null;
    volatile long nextCraftScanAtMs = 0L;

    // Bot-initiated trade retry: when a pot-share / ammo-share / loot-offer is blocked
    // because the sender or recipient is already in a trade, the attempt is stored here
    // and re-fired once the sender's trade clears and the delay expires.
    Runnable pendingBotTradeRetry = null;
    int pendingBotTradeRetryMs = 0;

    // Market work that mutates inventory outside a Trade (staging stall stock into a
    // HiredMerchant, executing a merchant buy): a HiredMerchant is not a Trade, so the
    // getTrade() tick gates don't cover it. Set while such an operation is in flight to get
    // the same physics-only tick + passive-loot suppression a trade window gets.
    volatile boolean marketBusy = false;

    // This bot's private price book (living economy layer 2) - lazily loaded on first market
    // touch via BotMarketBook.of, self-flushed on the bot's own tick. Tick-thread-owned.
    BotMarketBook marketBook = null;

    // Trade queue
    String pendingTradeCategory = null;
    List<Item> pendingTradeItems = null;
    int pendingTradeRecipientId = 0;
    int pendingTradeMeso = 0;
    int pendingTradeIdx = 0;
    int pendingTradeTimerMs = 0;
    boolean pendingTradeMesoAdded = false;
    boolean pendingTradeAllAdded = false;
    boolean pendingTradeBotDone = false;
    boolean pendingTradeSingleBatch = false;
    boolean pendingTradeInviteAnnounced = false;
    String  pendingTradeCategoryMsg = null;
    int     pendingPotShareBudget = 0; // max total qty to donate; 0 = no cap (normal trades)
    Map<Item, Short> pendingTradeRestoreSlots = new IdentityHashMap<>();

    // Message queue
    final ArrayDeque<BotChatManager.QueuedMessage> msgQueue = new ArrayDeque<>();
    boolean msgSending = false;

    // Generic scripted task queue. Per-map scripts enqueue small primitives
    // (move, follow, grind, drop) and the shared manager executes them.
    final ArrayDeque<BotTask> scriptTasks = new ArrayDeque<>();
    BotTask activeScriptTask = null;

    // AFK detection
    Point ownerAfkPos = null;
    long ownerAfkSinceMs = 0;
    boolean ownerWasAfk = false;

    // Owner-offline-or-dead detection: after a sustained period (5 min) the bot
    // scrolls/warps to the nearest town and idles, instead of grinding pots dry
    // or death-looping with no anchor.
    long ownerOfflineOrDeadSinceMs = 0;
    boolean ownerReturnedToTown = false;
    boolean ownerAwaySafeMode = false;

    int lastMapId = -1;

    // Human-like spacing and stagger — assigned at registration based on bot index
    int followOffsetX = 0;
    int skipDelayMs = ThreadLocalRandom.current().nextInt(0, 501);
    // Login-loading pause: bot idles 2-7s after first spawn before acting (emulates client load).
    // One-shot — ticked to 0 in tickCore, never reset by map-change teleports, so it fires once.
    int spawnWarmupMs = 2_000 + ThreadLocalRandom.current().nextInt(0, 5_001);
    int aiTickAccumulatorMs = 0;
    // Counts common-tick passes; gates latency-insensitive opportunity scans down to ~5Hz (see runCommonTickSystems).
    int commonTickCounter = 0;

    // "Move here" target — bot navigates to this fixed point, then idles until cleared
    Point moveTarget = null;
    boolean moveTargetPrecise = false; // true when triggered by "move here" — uses tight stop dist
    // Short label of what last set moveTarget (cmd-moveto / farm-here / script-task / travel-pin /
    // fidget-return-origin). Surfaced in the path log so a spurious/airborne moveTarget that
    // hijacks the goal (it outranks follow-target) is traceable to its source without a guess.
    String moveTargetSource = null;
    // "Farm here" anchor — bot returns to this fixed point and only takes local attacks.
    Point farmAnchor = null;
    int farmAnchorMapId = -1;
    // Grind loot — nearest convenient drop, searched each AI tick, cleared when picked up.
    MapItem grindLootTarget = null;
    int ignoredGrindLootObjectId = 0;
    long ignoredGrindLootUntilMs = 0L;
    // "Patrol" region — bot wanders within this nav region and attacks opportunistically.
    int patrolRegionId = -1;    // BotNavigationGraph.Region id; -1 = inactive
    int patrolMapId = -1;
    Point patrolWanderTarget = null;

    // Buff consumables (toggleable; cheap = weakest buff of each type, max = strongest)
    boolean buffConsumablesEnabled = false;
    boolean buffCheapMode          = true;
    boolean proactiveUpgradeOffers = true;
    long    lastBuffScanMs         = 0;
    long    lastBuffActionAtMs     = 0L;
    String  lastBuffActionSummary  = "no buff scans yet";
    // Autopilot auto-buff: true while autopilot (not the owner) turned cheap buffs on for a tough
    // map, so it may turn its own enable back off on an easy map. A manual buff on/off clears it.
    boolean autoBuffEngaged        = false;
    long    lastAutoBuffEvalMs     = 0L;

    // Skill buff tracking (always enabled; tracks last decision for debug)
    long   lastSkillBuffScanMs        = 0L;
    long   lastSkillBuffActionAtMs    = 0L;
    String lastSkillBuffActionSummary = "no skill buff checks yet";

    // Party-quest state (one slot per PQ type; null = not in that PQ)
    public server.bots.pq.BotKpqState kpq = new server.bots.pq.BotKpqState();
    public BotScriptRuntime script = new BotScriptRuntime();

    // Equips received from the owner during the current trade session.
    // Cleared when that trade session finishes or is cancelled.
    Set<Item> ownerGivenItems = Collections.newSetFromMap(new IdentityHashMap<>());

    // Last reason an edge execution was blocked (for debug logs)
    String lastEdgeBlockReason = null;

    // Cached movement state shared across ticks
    Point navTargetPos = null;
    // The graph instance the committed nav state was planned against. Planning may run on a
    // closest-profile fallback while the exact graph builds; when the served instance changes,
    // committed edges (windows, launch steps) are stale and must be dropped.
    BotNavigationGraph navGraph = null;
    BotNavigationGraph.Edge navEdge = null;
    BotNavigationGraph.Edge navJumpLaunchEdge = null;
    int navJumpLaunchX = Integer.MIN_VALUE;
    // Launch variation: extra walk-steps to carry past the selected launch X before firing
    // (rolled once per approach; MIN_VALUE = not rolled yet). Keeps repeated attempts from
    // launching at the identical spot when an arc is borderline.
    int navJumpLaunchDelaySteps = Integer.MIN_VALUE;
    int navTargetRegionId = -1;
    // Last region the bot resolved to, for chain continuity across SHARED ground (two overlapping
    // foothold chains at the same coordinate). A coordinate-only lookup can flip between them every
    // tick; the client stays on the chain it walked in on, so resolveCurrentRegionId keeps this id
    // when the current point is shared with it. Reset on graph/map swap (region ids are per-graph).
    int lastRegionId = -1;
    BotNavigationGraph.Edge navFootholdDetourEdge = null;
    Point navFootholdDetourTarget = null;
    // Committed route: the full planned hop sequence to the current goal region. The bot follows it
    // hop-by-hop (sticking to ONE route) instead of re-deciding the next hop per region. The best
    // first hop OUT of a region is position-dependent (it depends on the bot's x within the region —
    // the walk cost to each candidate launch point), but the old per-region next-hop cache is keyed
    // (region,target,bucket) — position-blind — and never invalidated, so it serves a hop computed
    // for whatever bot/position first populated it. Two adjacent regions' cached hops (filled from
    // different positions) can then disagree (r45->r42 while r42->r45) and trap the bot ping-ponging
    // (pathlog-GearArrow). Committing one route planned from the bot's OWN current position is
    // internally consistent (acyclic) and keeps per-bot route diversity. Recomputed when the goal
    // region changes or the bot is knocked off the route.
    List<BotNavigationGraph.Edge> committedRoute = null;
    int committedRouteTargetRegionId = -1;
    Point committedRouteTargetPos = null;
    // Cursor into committedRoute. A* search states are (region, point, ...), so a route can legitimately
    // revisit a region at different points (jump-up/drop-down staircase). Following by region-match alone
    // aliased the bot's later visit onto an earlier hop and bounced it (pathlog-WeeklyCovert r66<->r67);
    // the cursor follows the hop SEQUENCE instead. Reset to 0 whenever the route is (re)computed.
    int committedRouteCursor = 0;
    boolean navPreciseTarget = false;
    // Stale-edge give-up: consecutive ticks spent parked against a committed edge's position
    // gate ("*-pos" block reason) without any movement. BotNavigationManager drops the edge
    // and replans once the count passes the jittered threshold (rolled per park spot).
    int navBlockedPosTicks = 0;
    int navBlockedPosGiveUpTicks = 0;
    int navBlockedPosX = Integer.MIN_VALUE;
    int navBlockedPosY = Integer.MIN_VALUE;
    boolean graphWarmupFallback = false;
    int observedOwnerStepX = 0;
    int observedOwnerStepY = 0;
    BotFidgetMode fidgetMode = BotFidgetMode.NONE;
    BotFidgetTrigger fidgetTrigger = BotFidgetTrigger.NONE;
    long fidgetUntilMs = 0L;
    long nextFidgetActionAtMs = 0L;
    long nextFidgetAtMs = 0L;
    long nextIdleFidgetRollAtMs = 0L;
    int fidgetAirSteerDir = 0;
    int fidgetJumpDir = 0;
    int fidgetMoveDir = 0;
    boolean fidgetSpamAirSteer = false;
    int fidgetActionBaseDelayMs = 0;
    long nextFidgetJumpAtMs = 0L;
    Point fidgetOriginPos = null;
    long nextFidgetVisualAtMs = 0L;
    Point ferryStandSpot = null;         // loitering spot while waiting/riding a ferry (leader/solo only)
    long ferryStandRepickAtMs = 0L;      // jittered timer to wander to a new ferry spot
    int ferryStandMapId = -1;            // map the loiter spot belongs to; reset on map change
    long nextGearSuggestionAt = 0L;
    boolean spawnUpgradeCheckDone = false;
    final Set<Integer> requestedUpgradeItemIds = ConcurrentHashMap.newKeySet();
    boolean pendingLootOfferBotRequesting = false; // true = bot asked for owner's item
    double recentScrollReactionLoad = 0.0;
    long lastScrollReactionObservedAtMs = 0L;
    long nextScrollReactionAtMs = 0L;
    final Map<Integer, ScrollReactionStreakState> scrollReactionStreaksByScroller = new HashMap<>();
    long nextScrollReactionStreakPruneAtMs = 0L;

    // Path logging (debug)
    BotPathLogger pathLogger = null;
    String lastNavDecision = "-";
    long pendingGearPromptAt = 0L;
    // Last known owner position (set each tick in BotManager, read by pathLogger)
    Point lastOwnerPos = null;
    boolean lastTickWasAi = false;
    long lastTickAtMs = 0L;
    long lastHeartbeatAtMs = 0L;
    long nextFollowIdleMovementCheckAtMs = 0L;
    int tickFailureCount = 0;
    long tickFailureWindowStartedAtMs = 0L;

    // Stuck detection & unstuck
    int stuckMs = 0;
    int unstuckCooldownMs = 0;
    int stuckCheckX = Integer.MIN_VALUE;
    int stuckCheckY = Integer.MIN_VALUE;

    // Manual trade: countdown before bot accepts an incoming trade invite (both owner and peer-bot)
    int manualTradeAcceptDelayMs = 0;
    Trade manualTradeRef = null;
    int manualTradeTimeoutMs = 0;

    // Shout-trade (living-economy S3): a priced equip<->meso swap triggered by a market shout. While
    // partnerId != -1 this bot owns its Trade window and the manual/queued trade ticks stand down.
    volatile int shoutTradePartnerId = -1;              // counterparty char id; -1 = no active deal
    BotMarketGrammar.Offer shoutTradeOffer;             // agreed terms (item, qty, price)
    boolean shoutTradeSelling;                          // role: true = I stage the equip, false = meso
    boolean shoutTradeInitiator;                        // true = I matched + invited (I log the tape)
    client.inventory.Equip shoutTradeSellEquip;        // seller only: the exact piece to hand over
    boolean shoutTradeInvited;                          // initiator has sent the invite
    boolean shoutTradeStaged;                           // my side of the window is staged
    boolean shoutTradeLocked;                           // I confirmed my side (completeTrade called)
    long shoutTradeDeadlineMs;                          // give-up wall clock
    long shoutTradeConfirmAtMs;                         // human "beat" before locking once terms are met
    long nextShoutEmitMs;                               // emission cooldown
    // Deliberation before acting on a heard shout (don't buy/sell the instant a match is seen —
    // bank the candidate, "think about it" 2-6s, then re-validate + claim). One pending at a time.
    long shoutBuyDecideAtMs;                            // 0 = nothing pending
    int shoutBuySpeakerId = -1;                         // the shout speaker we're deliberating over
    BotMarketGrammar.Offer shoutBuyOffer;               // the offer under consideration
    boolean shoutBuySelling;                            // our role if we act: true = we'd sell to a B>

    boolean shoutTradeActive() {
        return shoutTradePartnerId != -1;
    }

    // Movement packet cache so repeated no-op packets are suppressed
    boolean movementBroadcastValid = false;
    int lastBroadcastX = 0;
    int lastBroadcastY = 0;
    int lastBroadcastVelX = 0;
    int lastBroadcastVelY = 0;
    int lastBroadcastStance = 0;
    int lastBroadcastFh = 0;
    int lastGroundFhId = 0;
    // Set whenever a movement packet is (re)broadcast this tick; reset at the top of each tick.
    // The common tick uses it to settle a bot to STAND once if it consumed the tick without
    // moving (a stale WALK packet would otherwise extrapolate into walk-in-place).
    boolean broadcastedThisTick = false;

    BotEntry(Character bot, Character owner, ScheduledFuture<?> task) {
        this.bot = bot;
        this.owner = owner;
        this.task = task;
    }

    // Accessors for code outside the server.bots package (e.g. server.bots.llm).
    // Mutations stay package-private to preserve existing invariants.
    public Character getBot() { return bot; }
    public Character getOwner() { return owner; }
    public ReplyChannel getReplyChannel() { return replyChannel; }
}
