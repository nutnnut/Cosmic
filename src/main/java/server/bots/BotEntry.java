package server.bots;

import client.Character;
import client.inventory.Item;
import server.Trade;
import server.life.Monster;
import server.maps.Foothold;
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

    // Movement intent
    boolean climbUpIntent = false;
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

    // Skill cache
    int cachedSkillJob = -1;
    int cachedSkillLevel = -1;
    int cachedSkillSignature = 0;
    final List<Integer> attackSkillIds = new ArrayList<>();
    int attackSkillId = 0;
    int aoeSkillId = 0;
    int aoeSkillMobs = 1;
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
    boolean degenAttackDone = false; // force retreat after an accidental close-range hit
    long retreatHoldUntilMs = 0L; // hysteresis: lock the local retreat goal for a short window
    Point retreatHoldPos = null;  // the locked retreat target — reused while hold is active
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
    Point shopStuckCheckPos = null;
    long shopStuckCheckAtMs = 0L;
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
    int followTravelBestDist = Integer.MAX_VALUE; // closest manhattan to the hop portal so far; resets the deadline on progress
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
    // Party autopilot cohesion: while in transit a follower rides the regular follow pipeline
    // behind the leader bot (formation offsets, legal portal-follow, warp catch-up for free)
    // instead of traveling independently; grind mode is restored on arrival. The leader's
    // straggler check is rate-limited and its last verdict cached between checks.
    boolean autopilotTransitFollow = false;
    // Party level-gap idle-leech: this (higher-level) member stops dealing damage and idles so the
    // lower cohort members become the damage-dealers and keep getting full exp share. Hysteresis
    // state (BotAutopilotManager.updateIdleLeech); cleared when the gap closes.
    volatile boolean idleLeech = false;
    // A personal idle spot picked once when idle-leech begins and held, so leechers settle at distinct
    // points instead of re-wandering into each other / stacking. Cleared when leech ends.
    java.awt.Point leechIdleAnchor = null;
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
    // Ad-hoc party-up (BotSocialManager): throttles how often a solo bot considers offering to party,
    // and stops a bot just offered-to from immediately re-offering.
    long nextSocialAtMs = 0L;
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
    long questErrandStartedAtMs = 0L;    // abort the errand if it can't reach the NPC in time
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
    long jobErrandStartedAtMs = 0L;    // abort the walk if it can't reach the instructor in time

    // Gachapon errand (BotGachaponManager): autopilot-only. When the bot has spare account NX (from
    // looted NX cards), it picks the best-EV reachable gachapon town, travels to the NPC, buys
    // tickets (abstracted cash-shop purchase) and rolls. gachaErrandMapId = -1 when no trip is
    // active. Reset in clearGachaErrand() (called from BotAutopilotManager.clear).
    int gachaErrandMapId = -1;
    int gachaErrandNpcId = 0;
    long gachaErrandStartedAtMs = 0L;    // abort the trip if it can't reach the NPC in time
    int gachaTicketsThisTrip = 0;        // capped by GACHA_TICKETS_PER_TRIP
    long gachaNextRollAtMs = 0L;         // humanlike pacing between rolls
    long nextGachaScanAtMs = 0L;
    long gachaNextRareChatAtMs = 0L;     // rate-limit the "got <rare>!" shout

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

    // Foothold index, rebuilt on map change
    int lastMapId = -1;
    Map<Integer, Foothold> fhIndex = new HashMap<>();

    // Human-like spacing and stagger — assigned at registration based on bot index
    int followOffsetX = 0;
    int skipDelayMs = ThreadLocalRandom.current().nextInt(0, 501);
    // Login-loading pause: bot idles 2-7s after first spawn before acting (emulates client load).
    // One-shot — ticked to 0 in tickCore, never reset by map-change teleports, so it fires once.
    int spawnWarmupMs = 2_000 + ThreadLocalRandom.current().nextInt(0, 5_001);
    int aiTickAccumulatorMs = 0;

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

    // Movement packet cache so repeated no-op packets are suppressed
    boolean movementBroadcastValid = false;
    int lastBroadcastX = 0;
    int lastBroadcastY = 0;
    int lastBroadcastVelX = 0;
    int lastBroadcastVelY = 0;
    int lastBroadcastStance = 0;
    int lastBroadcastFh = 0;
    int lastGroundFhId = 0;

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
