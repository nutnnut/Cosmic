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
    int breakoutDirection = 0;    // -1/+1 committed escape side while surrounded, 0 = not breaking out
    long breakoutUntilMs = 0L;    // hard safety timeout for the surround-breakout commitment
    Point aoeRepositionAnchor = null; // committed AoE sweet-spot to walk to before firing, null = not repositioning
    long aoeRepositionDeadlineMs = 0L; // bounded-chase timeout for the AoE reposition commitment
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
    Point followTravelMoveTarget = null; // the exact moveTarget instance travel pinned (identity-checked on clear)
    int followTravelTaxiNpcId = 0;       // != 0: current hop is a cab ride — walk to this NPC, pay, warp
    Point followTravelTaxiPos = null;    // cab NPC position (static, cached at hop start)
    boolean followTravelFerry = false;   // current hop is a ferry boarding leg (BotFerryManager)

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
    // Owner said "sail away": the bot may board cross-continent ferries while the owner is
    // online. Owner-offline autopilot never needs it. Reset in BotAutopilotManager.clear().
    boolean autopilotFerryApproved = false;
    // Resupply errand: temporary detour destination (return-map town) when supplies run low
    // mid-grind. -1 = none. The shop visit triggers on arrival; afterwards travel resumes
    // toward autopilotMapId. NextErrandAtMs rate-limits errands (survives clear()).
    int autopilotErrandMapId = -1;
    long autopilotNextErrandAtMs = 0L;
    long autopilotOwnerSupplyGraceUntilMs = 0L;
    boolean autopilotReturningFromErrand = false;
    long autopilotLastDeathAtMs = 0L;
    // Party autopilot cohesion: while in transit a follower rides the regular follow pipeline
    // behind the leader bot (formation offsets, legal portal-follow, warp catch-up for free)
    // instead of traveling independently; grind mode is restored on arrival. The leader's
    // straggler check is rate-limited and its last verdict cached between checks.
    boolean autopilotTransitFollow = false;
    long autopilotNextStragglerCheckAtMs = 0L;
    boolean autopilotWaitingForStragglers = false;
    // True while an async advisor pass for this entry is running (re-decides only) — stops
    // the tick from stacking decisions while one is still computing.
    volatile boolean autopilotDecisionInFlight = false;

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

    // Job advancement prompts
    int jobPromptSent = 0;
    int lastKnownLevel = -1;

    // AP/SP builds
    BotBuildManager.ApBuild apBuild = null;
    boolean apPromptSent = false;
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
    int aiTickAccumulatorMs = 0;

    // "Move here" target — bot navigates to this fixed point, then idles until cleared
    Point moveTarget = null;
    boolean moveTargetPrecise = false; // true when triggered by "move here" — uses tight stop dist
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
