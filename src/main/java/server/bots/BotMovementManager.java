package server.bots;

import client.Character;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import server.bots.combat.BotMobHitboxProvider;
import server.life.Monster;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Rope;
import tools.PacketCreator;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

class BotMovementManager {
    enum ActionType {
        IDLE,
        WALK,
        CROUCH,
        JUMP,
        CLIMB_UP,
        CLIMB_DOWN
    }

    record MoveAction(ActionType type, int stepX) {
        private static final MoveAction IDLE = new MoveAction(ActionType.IDLE, 0);
        private static final MoveAction CROUCH = new MoveAction(ActionType.CROUCH, 0);
        private static final MoveAction CLIMB_UP = new MoveAction(ActionType.CLIMB_UP, 0);
        private static final MoveAction CLIMB_DOWN = new MoveAction(ActionType.CLIMB_DOWN, 0);

        static MoveAction idle() {
            return IDLE;
        }

        static MoveAction walk(int stepX) {
            return new MoveAction(ActionType.WALK, stepX);
        }

        static MoveAction crouch() {
            return CROUCH;
        }

        static MoveAction jump(int stepX) {
            return new MoveAction(ActionType.JUMP, stepX);
        }

        static MoveAction climbUp() {
            return CLIMB_UP;
        }

        static MoveAction climbDown() {
            return CLIMB_DOWN;
        }
    }

    static final class JumpLanding {
        private final Point point;
        private final Foothold foothold;

        JumpLanding(Point point, Foothold foothold) {
            this.point = point;
            this.foothold = foothold;
        }

        Point point() {
            return point;
        }

        Foothold foothold() {
            return foothold;
        }
    }

    static class Config extends BotPhysicsEngine.Config {
        public int STOP_DIST = 30;
        public int FOLLOW_DIST = 80;
        public int GRIND_EDGE_MARGIN = 40; // keep bot this many px from foothold edge while grinding
        public int MOB_AVOID_LOOKAHEAD_STEPS = 3;
        // Per-grounded-tick chance to actually commit a legal dodge jump when a mob blocks the walk
        // lane. 1.0 = always commit the dodge (no humanlike miss); lower it (< 1.0) to add reaction
        // jitter so dodges aren't frame-perfect — the bot re-rolls each grounded tick the mob stays in
        // the lane, so even a low value still dodges eventually.
        public double MOB_AVOID_REACTION_CHANCE = 1.0;

        public int JUMP_Y_THRESH = 30;
        // Within-map "hopelessly far -> teleport to target" fallback. Big maps legitimately exceed
        // smaller values during normal travel, which made bots teleport to the target when they were
        // not actually stuck; 8000 covers the large fields. (Out-of-bounds recovery uses the tighter
        // OOB_TELEPORT_DIST below, gated on the bot being provably outside the map's VR rect.)
        public int TELEPORT_DIST = 8000;
        // Tighter teleport trigger when the bot has slipped outside the map's VR rectangle.
        // Long falls below VRBottom never collide with anything and otherwise wait until the
        // 4000 Manhattan threshold; this lets us recover sooner once we know the bot is OOB.
        public int OOB_TELEPORT_DIST = 600;
        public int FOLLOW_Y_CAP = 200; // max vertical distance for Y-snapped follow target
    }

    static Config cfg = bindConfig(new Config());

    private static Config bindConfig(Config config) {
        BotPhysicsEngine.cfg = config;
        return config;
    }

    static int tickDown(int remainingMs) {
        if (remainingMs <= 0) {
            return 0;
        }
        return Math.max(0, remainingMs - BotPhysicsEngine.cfg.TICK_MS);
    }

    static int delayAfterCurrentTick(int durationMs) {
        if (durationMs <= 0) {
            return 0;
        }
        return Math.max(0, durationMs - BotPhysicsEngine.cfg.TICK_MS);
    }

    static int walkStep(MapleMap map) {
        return BotPhysicsEngine.walkStep(map);
    }

    static int walkStep(MapleMap map, BotMovementProfile profile) {
        return BotPhysicsEngine.walkStep(map, profile);
    }

    static int velocityFromDeltaX(double deltaX) {
        return BotPhysicsEngine.velocityFromDeltaX(deltaX);
    }

    static void stopGroundMotion(BotEntry entry) {
        BotPhysicsEngine.stopGroundMotion(entry);
    }

    static JumpLanding simulateJumpLanding(MapleMap map, Point from, int stepX) {
        return wrapLanding(BotPhysicsEngine.simulateJumpLanding(map, from, stepX));
    }

    static JumpLanding simulateJumpLanding(MapleMap map, Point from, int stepX, BotMovementProfile profile) {
        return wrapLanding(BotPhysicsEngine.simulateJumpLanding(map, from, stepX, profile));
    }

    static JumpLanding simulateRopeJumpLanding(MapleMap map, Point from, int stepX) {
        return wrapLanding(BotPhysicsEngine.simulateRopeJumpLanding(map, from, stepX));
    }

    static JumpLanding simulateRopeJumpLanding(MapleMap map, Point from, int stepX, BotMovementProfile profile) {
        return wrapLanding(BotPhysicsEngine.simulateRopeJumpLanding(map, from, stepX, profile));
    }

    static boolean canReachRopeFromGround(MapleMap map, Point from, Rope rope) {
        return BotPhysicsEngine.canReachRopeFromGround(map, from, rope);
    }

    static boolean canReachRopeFromGround(MapleMap map, Point from, Rope rope, BotMovementProfile profile) {
        return BotPhysicsEngine.canReachRopeFromGround(map, from, rope, profile);
    }

    static boolean refreshMovementProfile(BotEntry entry) {
        BotMovementProfile updated = BotMovementProfile.fromCharacter(entry.bot);
        if (updated.equals(entry.movementProfile)) {
            return false;
        }

        MapleMap map = entry.bot != null ? entry.bot.getMap() : null;
        if (map != null
                && map.getFootholds() != null
                && BotNavigationGraphProvider.peekGraph(map, updated) == null) {
            BotNavigationGraphProvider.warmGraphAsync(map, updated);
        }

        entry.movementProfile = updated;
        clearNavigationState(entry);
        return true;
    }

    static void resetEntryState(BotEntry entry) {
        BotPhysicsEngine.resetMotion(entry, entry.bot.getPosition());
        clearTransientState(entry);
    }

    static void resetEntryStateAfterTeleport(BotEntry entry) {
        clearTransientState(entry);
    }

    private static void clearTransientState(BotEntry entry) {
        entry.grindTarget = null;
        entry.nextGrindTargetSearchAtMs = 0L;
        entry.attackCooldownMs = 0;
        entry.graphWarmupFallback = false;
        entry.observedOwnerStepX = 0;
        entry.observedOwnerStepY = 0;
        BotFidgetManager.clear(entry);
        clearNavigationState(entry);
        entry.movementBroadcastValid = false;
    }

    static void clearNavigationState(BotEntry entry) {
        entry.navTargetPos = null;
        entry.navEdge = null;
        entry.navJumpLaunchEdge = null;
        entry.navJumpLaunchX = Integer.MIN_VALUE;
        entry.navJumpLaunchDelaySteps = Integer.MIN_VALUE;
        entry.navTargetRegionId = -1;
        entry.navFootholdDetourEdge = null;
        entry.navFootholdDetourTarget = null;
        entry.navPreciseTarget = false;
        // NOTE: navBlockedPosTicks is deliberately NOT reset here, for the same reason as
        // committedRoute below: incidental clears between AI ticks were zeroing the blocked-pos
        // counter every ~2 ticks while the committed route kept re-serving the same unexecutable
        // hop, so the ~300-500ms give-up never fired (12min freeze, KB oscillation ledger #15).
        // The counter self-resets in trackBlockedPositionGate on any non-blocked tick or on
        // leaving the drift radius, and is consumed by the give-up itself.
        // NOTE: committedRoute is deliberately NOT cleared here. clearNavigationState fires on many
        // incidental ticks — notably tryExecuteCommittedEdgeAfterGroundMovement the instant a jump
        // completes on landing — and wiping the route there degraded "commit one route and follow it"
        // back into "recompute per landing", reviving the position-dependent r45<->r42 ping-pong
        // (pathlog-Sunset). The route self-invalidates in nextCommittedRouteEdge (goal-region change or
        // knocked off-route); it is cleared explicitly only on a real replan: graph swap (stale edge
        // instances) and the stale-edge give-up, both in BotNavigationManager.resolveTarget.
    }

    static void tickClimbing(BotEntry entry, Point targetPos, boolean runAiTick) {
        long startedAt = System.nanoTime();
        try {
            Character bot = entry.bot;
            // Null rope is handled inside advanceClimb/holdClimb — they call beginFall internally.
            BotPhysicsEngine.tickMotionTimers(entry);
            Point botPos = bot.getPosition();
            int dy = targetPos.y - botPos.y;
            int dxOwner = targetPos.x - entry.climbRope.x();

            // If not navigating, allow jumping off when the target is far away horizontally and
            // deeper than the rope reaches — but only once the descent is spent (near the rope
            // bottom). Dismounting the moment the bot attaches at the rope top launches it back
            // onto the entry platform for zero descent, and the fallback steering walks it right
            // back to the rope forever (pathlog-BishopDemo-2026-07-03, 551000000 rope@x=200).
            if (runAiTick && entry.navEdge == null
                    && Math.abs(dxOwner) > cfg.FOLLOW_DIST
                    && entry.climbRope.bottomY() < targetPos.y
                    && botPos.y >= entry.climbRope.bottomY() - cfg.STOP_DIST) {
                jumpOffRope(entry, bot, dxOwner);
                return;
            }

            boolean climbIdle = shouldHoldClimbIdle(entry, dy, dxOwner);
            if (climbIdle) {
                BotPhysicsEngine.holdClimb(entry, bot);
                broadcastMovement(entry);
                return;
            }

            if (shouldSnapToClimbTarget(entry, targetPos, dy)) {
                BotPhysicsEngine.attachToRope(entry, bot, entry.climbRope, targetPos.y);
                broadcastMovement(entry);
                return;
            }

            if (!runAiTick && entry.navEdge == null) {
                // No committed nav edge → no AI-decided climb intent. On non-AI ticks the
                // navDirective falls through to the raw follow target (resolveTarget can't run
                // findNextEdge here), and using its dy to choose a direction can dismount the bot
                // off the rope-top onto the foothold above — pathlog-Preston-2026-05-07 oscillation.
                // Integrate the cached intent instead; the next AI tick will refresh direction.
                if (entry.climbVerticalDir == 0) {
                    BotPhysicsEngine.holdClimb(entry, bot);
                } else {
                    BotPhysicsEngine.advanceClimb(entry, bot);
                }
                broadcastMovement(entry);
                return;
            }

            // Committed climb edges must reach the exact launch anchor so execution can hand off.
            MoveAction action = dy < 0
                    ? MoveAction.climbUp()
                    : dy > 0 ? MoveAction.climbDown() : MoveAction.idle();
            applyClimbAction(entry, bot, action);
        } finally {
            BotPerformanceMonitor.record("move-climb", System.nanoTime() - startedAt);
        }
    }

    static void jumpOffRope(BotEntry entry, Character bot, int dx) {
        int airVelX = resolveAirVelocityX(entry, bot.getMap(), entry.movementProfile, dx);
        BotPhysicsEngine.beginJumpOffRope(entry, bot, airVelX);
        broadcastMovement(entry);
    }

    static void jumpToRope(BotEntry entry, Character bot, int dx) {
        Rope sourceRope = entry.climbRope;
        int airVelX = resolveAirVelocityX(entry, bot.getMap(), entry.movementProfile, dx);
        BotPhysicsEngine.beginRopeTransferJump(entry, bot, sourceRope, airVelX);
        broadcastMovement(entry);
    }

    private static void applyClimbAction(BotEntry entry, Character bot, MoveAction action) {
        entry.climbVerticalDir = switch (action.type()) {
            case CLIMB_UP -> -1;
            case CLIMB_DOWN -> 1;
            default -> 0;
        };

        if (entry.climbVerticalDir == 0) {
            BotPhysicsEngine.holdClimb(entry, bot);
        } else {
            BotPhysicsEngine.advanceClimb(entry, bot);
        }
        broadcastMovement(entry);
    }

    static boolean shouldHoldClimbIdle(BotEntry entry, int dy, int dxOwner) {
        if (entry.navEdge != null) {
            return false;
        }
        return !entry.grinding
                && Math.abs(dy) < cfg.STOP_DIST
                && Math.abs(dxOwner) < cfg.FOLLOW_DIST * 2;
    }

    static boolean shouldSnapToClimbTarget(BotEntry entry, Point targetPos, int dy) {
        if (entry == null || !entry.climbing || entry.climbRope == null || targetPos == null || dy == 0) {
            return false;
        }
        if (!entry.navPreciseTarget) {
            return false;
        }
        if (targetPos.x != entry.climbRope.x()) {
            return false;
        }
        // Allow target == bottomY: rope-exit launch anchors can be authored at the rope bottom
        // (pathlog-Leroy/John). The exclusive guard rejected those anchors, leaving the bot
        // grinding the climb integrator against a fixed-step overshoot — every step landed
        // past bottomY, beginFall(0,0) detached, repeat. Top step-off keeps its strict guard
        // because dismount there is driven by physics top-boundary detach, not snap.
        if (targetPos.y <= entry.climbRope.topY() || targetPos.y > entry.climbRope.bottomY()) {
            return false;
        }
        return Math.abs(dy) < BotPhysicsEngine.climbStepPerTick();
    }

    static void tickAirborne(BotEntry entry, Point targetPos) {
        long startedAt = System.nanoTime();
        try {
            entry.swimming = false;
            BotPhysicsEngine.tickMotionTimers(entry);

            Character bot = entry.bot;
            Point botPos = bot.getPosition();

            if (successfullyGrabbedRope(entry, bot, botPos)) {
                return;
            }

            // Set air steering intent. If fidget manager already set moveDir (non-zero),
            // preserve it. Committed nav trajectories (fixedAirArc, JUMP/DROP/CLIMB-launch
            // edges) instead fly with the LAUNCH key held — like the real player performing
            // the hop: held input is a CalcFloat no-op above the 8.93 x fs px/s input band
            // (keeps vx constant, matching the graph's constant-stepX arc sim) and suppresses
            // the no-input air drag that free flight gets.
            if (entry.moveDir == 0) {
                if (shouldApplyAirSteering(entry)) {
                    if (targetPos != null) {
                        int dx = targetPos.x - botPos.x;
                        entry.moveDir = Math.abs(dx) > BotPhysicsEngine.cfg.SWIM_ARRIVAL_RADIUS_PX
                                ? Integer.signum(dx) : 0;
                    }
                } else {
                    entry.moveDir = Integer.signum(entry.airVelX);
                }
            }

            BotPhysicsEngine.AirborneStepResult result = BotPhysicsEngine.stepAirborne(entry, bot);
            if (result == BotPhysicsEngine.AirborneStepResult.WALL) {
                if (successfullyGrabbedRope(entry, bot, bot.getPosition())) {
                    return;
                }
                broadcastMovement(entry);
                return;
            }
            if (result == BotPhysicsEngine.AirborneStepResult.CEILING) {
                broadcastMovement(entry);
                return;
            }
            if (result == BotPhysicsEngine.AirborneStepResult.LANDED) {
                entry.jumpCooldownMs = 0;
                broadcastMovement(entry);
                return;
            }

            // CONTINUE — position advanced, check for rope grab at new position
            if (successfullyGrabbedRope(entry, bot, bot.getPosition())) {
                return;
            }
            if (entry.flashJumpFired) {
                Point now = bot.getPosition();
                broadcastFlashJump(entry, now.x - botPos.x, now.y - botPos.y);
                entry.flashJumpFired = false;
            } else {
                broadcastMovement(entry);
            }
        } finally {
            BotPerformanceMonitor.record("move-air", System.nanoTime() - startedAt);
        }
    }

    private static boolean successfullyGrabbedRope(BotEntry entry, Character bot, Point botPos) {
        if (!entry.climbUpIntent) {
            return false;
        }

        for (Rope rope : bot.getMap().getRopes()) {
            if (sameRope(entry.blockedRopeGrab, rope)) {
                continue;
            }
            // A nav rope-jump targets one specific rope; never grab a different one the arc happens to
            // pass (co-located ropes at the launch otherwise hijack the jump). Recovery jumps leave the
            // target null and may grab any reachable rope.
            if (entry.climbIntentRope != null && !sameRope(entry.climbIntentRope, rope)) {
                continue;
            }
            if (Math.abs(rope.x() - botPos.x) > BotPhysicsEngine.cfg.ROPE_GRAB_X) {
                continue;
            }
            if (botPos.y < rope.topY() || botPos.y > rope.bottomY() + 2) {
                continue;
            }

            BotPhysicsEngine.attachToRope(entry, bot, rope, botPos.y);
            broadcastMovement(entry);
            return true;
        }

        return false;
    }

    static boolean sameRope(Rope left, Rope right) {
        return left != null && right != null
                && left.x() == right.x()
                && left.topY() == right.topY()
                && left.bottomY() == right.bottomY()
                && left.isLadder() == right.isLadder();
    }

    private static boolean shouldApplyAirSteering(BotEntry entry) {
        if (entry.fixedAirArc) {
            return false;
        }
        if (entry.downJumpGracePeriodMS != 0L) {
            return false;
        }
        if (entry.navEdge == null) {
            return true;
        }
        return entry.navEdge.type != BotNavigationGraph.EdgeType.JUMP
                && entry.navEdge.type != BotNavigationGraph.EdgeType.DROP
                && !(entry.navEdge.type == BotNavigationGraph.EdgeType.CLIMB
                && entry.navEdge.launchStepX != 0);
    }

    static void tickSwimming(BotEntry entry, Point targetPos) {
        long startedAt = System.nanoTime();
        try {
            BotPhysicsEngine.tickMotionTimers(entry);
            computeSwimIntents(entry, targetPos);
            BotPhysicsEngine.applySwimMotion(entry);
            broadcastMovement(entry);
        } finally {
            BotPerformanceMonitor.record("move-swim", System.nanoTime() - startedAt);
        }
    }

    /**
     * Translate a nav target into the discrete swim controls the real client exposes:
     * steer L/R (continuous), JUMP burst (one-shot), UP/DOWN held.
     * No continuous velocity steering — physics integrates the intents.
     */
    private static void computeSwimIntents(BotEntry entry, Point targetPos) {
        // Capture last vertical hold for hysteresis. Without sticky-middle,
        // a target sinking faster than the bot's UP-terminal sink rate causes
        // dy to oscillate across the LEVEL_BAND boundary every tick — bot
        // alternates UP-hold (slow sink) and free-sink, visibly stuttering.
        int prevVerticalHold = entry.swimVerticalHold;

        // Default to "no input": bot drifts under swim gravity.
        entry.swimMoveDir = 0;
        entry.swimVerticalHold = 0;
        entry.swimJumpRequested = false;

        // Player can't dispatch movement input (strafe/jump/up/down) while
        // CUserLocal::IsAttacking is true. Mirror that here: during animation
        // lock the integrator still ticks (drag + gravity, collision) but no
        // intent is set, so the bot just floats in place.
        if (entry.attackCooldownMs > 0) {
            return;
        }

        if (targetPos == null) {
            // Idle in water — hold UP so the bot doesn't sink endlessly.
            entry.swimVerticalHold = -1;
            return;
        }

        Point pos = entry.bot.getPosition();
        int dx = targetPos.x - pos.x;
        int dy = targetPos.y - pos.y;

        // Horizontal steer.
        int hRadius = BotPhysicsEngine.cfg.SWIM_ARRIVAL_RADIUS_PX;
        if (dx >  hRadius) entry.swimMoveDir =  1;
        else if (dx < -hRadius) entry.swimMoveDir = -1;

        // Arrival band: bot is essentially on top of the target both axes.
        // Hold UP just to maintain altitude, no burst, no horizontal push —
        // prevents the jump/sink oscillation when bot overshoots target by a
        // few px (was: any dy<0 fired a 1000+ px/s burst, then bot fell back
        // through level, repeat).
        int levelBand = BotPhysicsEngine.cfg.SWIM_LEVEL_BAND_PX;
        if (Math.abs(dx) <= hRadius && Math.abs(dy) <= levelBand) {
            entry.swimMoveDir = 0;
            entry.swimVerticalHold = -1;
            return;
        }

        // Vertical intent with hysteresis around band boundaries. The middle
        // band (LEVEL < dy <= DOWN) is "sticky" — we keep whichever hold was
        // active last tick so the bot doesn't flip-flop between UP and free
        // sink as dy crosses LEVEL_BAND each frame while chasing a target
        // that sinks faster than UP-terminal.
        long now = System.currentTimeMillis();
        int jumpTrigger = BotPhysicsEngine.cfg.SWIM_JUMP_TRIGGER_DY_PX;
        int downBand = BotPhysicsEngine.cfg.SWIM_DOWN_BAND_PX;
        if (dy <= -jumpTrigger && now >= entry.swimNextJumpAtMs) {
            entry.swimJumpRequested = true;
            entry.swimNextJumpAtMs = now + BotPhysicsEngine.cfg.SWIM_JUMP_COOLDOWN_MS;
            entry.swimVerticalHold = -1;
        } else if (dy <= levelBand) {
            entry.swimVerticalHold = -1;        // clearly above target → UP
        } else if (dy > downBand) {
            entry.swimVerticalHold = 1;         // clearly far below → DOWN
        } else {
            // Middle band: persist last hold to avoid stutter. If we were
            // sinking (free or DOWN), keep that — UP would just slow our
            // descent and let target pull further away. If we were UP-holding
            // and now drifted past LEVEL, switch to free sink so we catch up.
            entry.swimVerticalHold = prevVerticalHold > 0 ? 1 : 0;
        }

        // Wall-escape: physics flagged a wall hit last tick while we were steering toward the target.
        // The greedy dy-based vertical above would pin us against the wall when the target sits at or
        // below our level behind it. UP-hold alone can't rise (SWIM_UP_THRUST < SWIM_GRAVITY), so fire
        // a cooldown-gated JUMP burst — the only source of real upward momentum — to clear the obstacle,
        // holding UP between bursts to soften the sink.
        if (entry.swimWallBlocked && entry.swimMoveDir != 0) {
            if (now >= entry.swimNextJumpAtMs) {
                entry.swimJumpRequested = true;
                entry.swimNextJumpAtMs = now + BotPhysicsEngine.cfg.SWIM_JUMP_COOLDOWN_MS;
            }
            entry.swimVerticalHold = -1;
        }
    }

    /**
     * SSOT settle for "no movement intent this tick". A mode handler that consumes its tick with
     * {@code return true} but never steps physics leaves the last WALK packet standing, and clients
     * extrapolate it into walk-in-place. Idling the ground physics with a null target decays the
     * leftover walk velocity/stance to STAND and emits one stop packet (the broadcast dedups, so the
     * steady-state standing ticks send nothing). Air/climb states settle through their own physics
     * ticks, so they're left alone; on a swim map a resting bot floats (inAir) and is likewise skipped —
     * its SWIM stance never extrapolates as a walk. Called once per tick from the common tick.
     */
    static void settleIdle(BotEntry entry) {
        if (entry == null || entry.bot == null || entry.inAir || entry.climbing) {
            return;
        }
        tickGrounded(entry, null);
    }

    static void tickGrounded(BotEntry entry, Point targetPos) {
        long startedAt = System.nanoTime();
        try {
            entry.swimming = false;
            Character bot = entry.bot;

            BotPhysicsEngine.tickMotionTimers(entry);

            Foothold currentFh = BotPhysicsEngine.syncAndDetectGround(entry, bot);
            if (currentFh == null) {
                broadcastMovement(entry);
                return;
            }

            Point botPos = bot.getPosition();
            if (entry.ropeEntryPending) {
                performTopRopeEntry(entry);
                return;
            }
            if (entry.downJumpPending) {
                performDownJump(entry);
                return;
            }

            targetPos = adjustGrindingTargetPosition(entry, currentFh, targetPos);
            if (entry.graphWarmupFallback && targetPos != null) {
                if (BotFallbackMovementManager.tryImmediateAction(entry, botPos, targetPos)) {
                    return;
                }
                targetPos = BotFallbackMovementManager.resolveSteeringTarget(entry, botPos, targetPos);
            }
            MoveAction action = planGroundAction(entry, currentFh, botPos, targetPos);
            applyGroundAction(entry, currentFh, action);
        } finally {
            BotPerformanceMonitor.record("move-ground", System.nanoTime() - startedAt);
        }
    }

    /**
     * Stop-distance used when navPreciseTarget is true.
     * WALK edges use 4px to absorb terrain micro-bumps on sloped footholds.
     * JUMP and straight down-jump DROP edges use 0px because the bot must walk INTO the
     * authored launch window, not stop just outside it. Other precise edge types
     * (CLIMB, PORTAL, non-windowed fallback cases) use 1px to reach the exact anchor.
     */
    static int preciseNavStopDist(BotNavigationGraph.Edge navEdge) {
        if (navEdge != null
                && (navEdge.type == BotNavigationGraph.EdgeType.JUMP
                || (navEdge.type == BotNavigationGraph.EdgeType.DROP && navEdge.launchStepX == 0))) {
            // Bot must walk INTO the launch window, not just near it. The launch window checks
            // are strict, so stopDist=1 can halt the bot exactly 1px before the valid range.
            return 0;
        }
        if (navEdge != null && navEdge.type != BotNavigationGraph.EdgeType.WALK) {
            return 1;
        }
        return 4;
    }

    static Point adjustGrindingTargetPosition(BotEntry entry, Foothold currentFh, Point targetPos) {
        if (!entry.grinding || entry.navEdge != null || currentFh == null || targetPos == null) {
            return targetPos;
        }

        MapleMap map = entry.bot.getMap();
        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, entry.movementProfile);
        if (graph == null) {
            BotNavigationGraphProvider.warmGraphAsync(map, entry.movementProfile);
            return targetPos;
        }
        Point botPos = entry.bot.getPosition();
        int currentRegionId = BotNavigationManager.resolveCurrentRegionId(graph, entry, map, botPos);
        int targetRegionId = BotNavigationManager.resolveTargetRegionId(graph, entry, map, targetPos);
        if (currentRegionId < 0 || currentRegionId != targetRegionId) {
            return targetPos;
        }

        BotNavigationGraph.Region currentRegion = graph.getRegion(currentRegionId);
        if (currentRegion == null || currentRegion.isRopeRegion) {
            return targetPos;
        }

        int safeLeft = currentRegion.minX + cfg.GRIND_EDGE_MARGIN;
        int safeRight = currentRegion.maxX - cfg.GRIND_EDGE_MARGIN;
        if (safeLeft >= safeRight) {
            return targetPos;
        }

        int clampedX = Math.max(safeLeft, Math.min(safeRight, targetPos.x));
        return currentRegion.pointAt(clampedX);
    }

    private static MoveAction planGroundAction(BotEntry entry, Foothold currentFh, Point botPos, Point targetPos) {
        boolean directionalDrop = isDirectionalDropEdge(entry.navEdge);
        boolean footholdDetour = entry.navFootholdDetourTarget != null;
        int stopDist = directionalDrop || footholdDetour ? 0
                : entry.navPreciseTarget ? preciseNavStopDist(entry.navEdge) : cfg.STOP_DIST;
        // No hysteresis when navigating to an edge — always move toward the waypoint. FOLLOW_DIST
        // hysteresis exists to stop owner-follow spacing jitter; a grind-wander/objective target must be
        // reached, so it restarts at stopDist (else the bot parks within 80px of its goal and never
        // closes the gap — pathlog-duiuganda: stalled 49px short with nav=same-region edge=none).
        int followDist = directionalDrop ? 0
                : (entry.navEdge != null || entry.navPreciseTarget) ? stopDist
                : entry.grinding ? stopDist
                : cfg.FOLLOW_DIST;
        int stepX = resolveGroundStepX(entry, botPos, targetPos, stopDist, followDist);
        if (stepX == 0) {
            return MoveAction.idle();
        }
        boolean canWalkStep = BotPhysicsEngine.canWalkGroundStep(entry.bot.getMap(), botPos, stepX);
        if (!canWalkStep) {
            boolean blockedByWall = BotPhysicsEngine.isGroundStepBlockedByWall(entry.bot.getMap(), botPos, stepX);
            // Swim maps bypass the nav graph (no JUMP/DROP edges), so a grounded bot blocked by a wall
            // toward its target has no authored way off the platform — it would idle forever. Launch into
            // the water ourselves; once airborne, tickSwimming steers it over the obstacle.
            if (blockedByWall && entry.bot.getMap().isSwim()) {
                return MoveAction.jump(stepX);
            }
            if (!blockedByWall
                    && ((directionalDrop && Integer.signum(stepX) == Integer.signum(entry.navEdge.launchStepX))
                    || BotFallbackMovementManager.shouldWalkOffLedge(entry, botPos, targetPos, stepX))) {
                // Walk-off drops should keep walking in the authored direction until physics
                // detects lost ground and transitions into a fall with preserved momentum.
                return MoveAction.walk(stepX);
            }
            // Wall-blocked nav edges are stale or invalid. Clear them so the next AI tick can
            // replan instead of holding a walk stance into the wall.
            if (blockedByWall && entry.navEdge != null) {
                clearNavigationState(entry);
            } else if (entry.navEdge != null && entry.navEdge.type == BotNavigationGraph.EdgeType.WALK) {
                clearNavigationState(entry);
            }
            return MoveAction.idle();
        }
        if (shouldJumpToAvoidMob(entry, currentFh, botPos, stepX)) {
            return MoveAction.jump(stepX);
        }
        return MoveAction.walk(stepX);
    }

    private static boolean shouldJumpToAvoidMob(BotEntry entry, Foothold currentFh, Point botPos, int stepX) {
        if (entry == null || entry.bot == null || currentFh == null || botPos == null || stepX == 0) {
            return false;
        }
        // Mode gate: dodge applies to autopilot-driven ground locomotion (following or grinding, which
        // also covers travel — BotAutopilotManager resumes travel with grinding=true). It must NOT fire
        // while a non-WALK edge is committed (JUMP/DROP/CLIMB/PORTAL have launch windows a dodge would
        // wreck) nor while steering to a precise nav target. A committed WALK edge is itself plain
        // ground walking toward a region exit, so dodging across it is safe: simulatedJumpLandsInCurrentRegion
        // below guarantees the bot lands in the same region and does not derail the path.
        boolean traveling = entry.followTravelTargetMapId != -1;
        boolean parkingIdle = entry.idleLeech
                || entry.hpResting
                || System.currentTimeMillis() < entry.breakUntilMs;
        if (!dodgeModeAllowed(entry.following, entry.grinding, traveling, entry.navEdge, entry.navPreciseTarget)) {
            return false;
        }
        if (parkingIdle) {
            return false;
        }

        // Humanlike reaction: don't dodge with perfect reflexes. Checked BEFORE the mob-lane scan
        // (it's a mob-independent roll) so we skip that scan on the ~40% of ticks it rejects. The bot
        // is grounded only between jumps, so airborne spacing already prevents per-tick spam; this just
        // adds a little imperfection so dodges aren't frame-perfect.
        if (ThreadLocalRandom.current().nextDouble() >= cfg.MOB_AVOID_REACTION_CHANCE) {
            return false;
        }

        Monster blockingMob = firstBlockingMobInWalkLane(entry, currentFh, botPos, stepX);
        if (blockingMob == null) {
            return false;
        }

        return simulatedJumpLandsInCurrentRegion(entry, currentFh, botPos, stepX);
    }

    /**
     * Pure mode predicate for the walk-lane mob dodge (separated for unit testing without nav/graph
     * state). Dodge is allowed only during autopilot-driven ground locomotion (following or grinding;
     * travel resumes with grinding=true), and only when not steering to a precise nav target and not on
     * a committed non-WALK edge. A committed WALK edge is still plain ground walking, so dodging across
     * it is safe; JUMP/DROP/CLIMB/PORTAL edges have launch windows a dodge would wreck.
     */
    static boolean dodgeModeAllowed(boolean following, boolean grinding, boolean traveling,
            BotNavigationGraph.Edge navEdge, boolean navPreciseTarget) {
        // traveling: autopilot map-to-map travel walks long ground stretches to a portal where neither
        // following nor grinding is reliably set yet — so it never dodged blocking mobs. A travel WALK
        // edge is plain ground walking like the others, so allow the same dodge SSOT there.
        if (!following && !grinding && !traveling) {
            return false;
        }
        if (navPreciseTarget) {
            return false;
        }
        return navEdge == null || navEdge.type == BotNavigationGraph.EdgeType.WALK;
    }

    private static Monster firstBlockingMobInWalkLane(BotEntry entry, Foothold currentFh, Point botPos, int stepX) {
        MapleMap map = entry.bot.getMap();
        int direction = Integer.signum(stepX);
        int lookahead = Math.max(Math.abs(stepX),
                BotPhysicsEngine.walkStep(map, entry.movementProfile) * Math.max(1, cfg.MOB_AVOID_LOOKAHEAD_STEPS));
        int laneEndX = botPos.x + direction * lookahead;
        Rectangle lane = inclusiveRectangle(
                Math.min(botPos.x, laneEndX),
                botPos.y - BotCombatManager.cfg.MOB_TOUCH_SWEEP_HEIGHT,
                Math.max(botPos.x, laneEndX),
                botPos.y);

        Monster nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (Monster mob : map.getAllMonsters()) {
            if (!mob.isAlive() || !isMobInCurrentGroundRegion(entry, currentFh, mob)) {
                continue;
            }

            Rectangle bounds = BotMobHitboxProvider.getInstance().getMobBounds(mob);
            if (bounds == null) {
                bounds = inclusiveRectangle(mob.getPosition().x, mob.getPosition().y, mob.getPosition().x, mob.getPosition().y);
            }
            if (!lane.intersects(bounds) && !lane.contains(mob.getPosition())) {
                continue;
            }

            int mobEdgeX = direction > 0 ? bounds.x : bounds.x + bounds.width;
            int distance = Math.max(0, direction > 0 ? mobEdgeX - botPos.x : botPos.x - mobEdgeX);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = mob;
            }
        }
        return nearest;
    }

    private static boolean isMobInCurrentGroundRegion(BotEntry entry, Foothold currentFh, Monster mob) {
        Foothold mobFoothold = BotPhysicsEngine.findGroundFoothold(entry.bot.getMap(), mob.getPosition());
        if (mobFoothold != null && mobFoothold.getId() == currentFh.getId()) {
            return true;
        }

        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(entry.bot.getMap(), entry.movementProfile);
        if (graph == null) {
            return false;
        }

        int currentRegionId = BotNavigationManager.resolveCurrentRegionId(
                graph, entry, entry.bot.getMap(), entry.bot.getPosition());
        int mobRegionId = BotNavigationManager.resolveTargetRegionId(
                graph, entry, entry.bot.getMap(), mob.getPosition());
        return currentRegionId >= 0 && currentRegionId == mobRegionId;
    }

    private static boolean simulatedJumpLandsInCurrentRegion(BotEntry entry, Foothold currentFh, Point botPos, int stepX) {
        MapleMap map = entry.bot.getMap();
        int airVelX = resolveAirVelocityX(entry, map, entry.movementProfile, stepX);
        JumpLanding landing = simulateJumpLanding(map, botPos, airVelX, entry.movementProfile);
        if (landing == null || landing.point() == null || landing.foothold() == null) {
            return false;
        }

        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, entry.movementProfile);
        if (graph == null) {
            return landing.foothold().getId() == currentFh.getId();
        }

        int currentRegionId = BotNavigationManager.resolveCurrentRegionId(graph, entry, map, botPos);
        int landingRegionId = BotNavigationManager.resolveTargetRegionId(graph, entry, map, landing.point());
        return currentRegionId >= 0 && currentRegionId == landingRegionId;
    }

    private static Rectangle inclusiveRectangle(int left, int top, int right, int bottom) {
        return new Rectangle(left, top, Math.max(1, right - left + 1), Math.max(1, bottom - top + 1));
    }

    private static boolean isDirectionalDropEdge(BotNavigationGraph.Edge navEdge) {
        return navEdge != null
                && navEdge.type == BotNavigationGraph.EdgeType.DROP
                && navEdge.launchStepX != 0;
    }

    static int resolveGroundStepX(BotEntry entry, Point botPos, Point targetPos, int stopDist, int followDist) {
        if (entry == null || entry.bot == null || botPos == null || targetPos == null) {
            return 0;
        }
        if (entry.graphWarmupFallback) {
            int localStopDist = Math.min(stopDist, 12);
            return updateStepX(entry, entry.bot.getMap(), botPos.x, targetPos.x, localStopDist, localStopDist);
        }
        return updateStepX(entry, entry.bot.getMap(), botPos.x, targetPos.x, stopDist, followDist);
    }

    private static void applyGroundAction(BotEntry entry, Foothold currentFh, MoveAction action) {
        Character bot = entry.bot;
        entry.moveDir = switch (action.type()) {
            case WALK, JUMP -> Integer.compare(action.stepX(), 0);
            default -> 0;
        };

        if (action.type() == ActionType.CROUCH) {
            BotPhysicsEngine.queueDownJump(entry, bot);
            broadcastMovement(entry);
            return;
        }
        if (action.type() == ActionType.JUMP) {
            initiateFixedArcJump(entry, bot, action.stepX());
            return;
        }

        BotPhysicsEngine.GroundMotion motion =
                BotPhysicsEngine.applyGroundMotion(entry, bot, currentFh);
        if (motion.lostGround()) {
            broadcastMovement(entry);
            return;
        }

        if (motion.stepX() == 0) {
            applyIdleOrInPlaceMotion(entry, action);
            return;
        }

        broadcastMovement(entry);
    }

    private static void applyIdleOrInPlaceMotion(BotEntry entry, MoveAction action) {
        // Preserve ground momentum while still trying to walk/jump toward a nav target.
        // Otherwise subpixel uphill/transition movement gets zeroed every tick and the bot
        // can stall forever short of a valid launch window.
        if (entry.movementVelX == 0 && action.type() == ActionType.IDLE) {
            BotPhysicsEngine.idleOnGround(entry, entry.bot);
        }
        broadcastMovement(entry);
    }

    private static void performDownJump(BotEntry entry) {
        BotPhysicsEngine.beginDownJump(entry, entry.bot);
        broadcastMovement(entry);
    }

    private static void performTopRopeEntry(BotEntry entry) {
        BotPhysicsEngine.beginTopRopeEntry(entry, entry.bot);
        broadcastMovement(entry);
    }

    static int calcStepX(MapleMap map, int botX, int targetX, boolean wasMovingX) {
        return calcStepX(map, BotMovementProfile.base(), botX, targetX, wasMovingX, cfg.STOP_DIST, cfg.FOLLOW_DIST);
    }

    static int calcStepX(MapleMap map, int botX, int targetX, boolean wasMovingX, int stopDist, int followDist) {
        return calcStepX(map, BotMovementProfile.base(), botX, targetX, wasMovingX, stopDist, followDist);
    }

    static int calcStepX(MapleMap map, BotMovementProfile profile, int botX, int targetX, boolean wasMovingX, int stopDist, int followDist) {
        int dx = targetX - botX;
        int absDx = Math.abs(dx);
        if (absDx <= stopDist) {
            return 0;
        }
        if (!wasMovingX && absDx <= followDist) {
            return 0;
        }
        return Math.min(absDx, BotPhysicsEngine.walkStep(map, profile)) * (dx >= 0 ? 1 : -1);
    }

    static int updateStepX(BotEntry entry, MapleMap map, int botX, int targetX) {
        return updateStepX(entry, map, botX, targetX, cfg.STOP_DIST, cfg.FOLLOW_DIST);
    }

    static int updateStepX(BotEntry entry, MapleMap map, int botX, int targetX, int stopDist, int followDist) {
        int stepX = calcStepX(map, entry.movementProfile, botX, targetX, entry.wasMovingX, stopDist, followDist);
        if (stepX == 0) {
            entry.wasMovingX = false;
            return 0;
        }
        entry.wasMovingX = true;
        // Bang-bang approach on slippery ground: only push toward the target while the bot
        // can still brake to a stop inside the remaining distance; otherwise counter-strafe
        // (or coast) so the bot arrives able to stop in the window/radius instead of sliding
        // past it (pathlog-Preston-2026-06-12T083326). Plain passthrough on fs=1 maps.
        // Directional walk-off drops are exempt: they leave the platform with momentum on
        // purpose, so braking short of the ledge would break the edge.
        if (isDirectionalDropEdge(entry.navEdge)) {
            return stepX;
        }
        int approachDir = BotPhysicsEngine.slipperyApproachDir(map, entry.movementProfile, entry.hspeed,
                targetX - botX, launchWindowOvershootSlackPx(entry, botX, targetX));
        return approachDir == Integer.signum(stepX) ? stepX : approachDir;
    }

    /**
     * Extra overshoot allowance (px) past the steering target before the slippery approach
     * controller must brake. Anywhere inside a committed edge's launch window is executable,
     * so a pulse projected to land between the target and the window's far edge is arrival,
     * not overshoot. Without it a tight window (2px on El Nath fs=0.2) can be unreachable
     * from rest: the smallest legal 50ms pulse travels farther than the distance to the
     * target pixel and the controller refuses to accelerate at all
     * (pathlog-Leroy-2026-06-12T140609).
     */
    private static int launchWindowOvershootSlackPx(BotEntry entry, int botX, int targetX) {
        BotNavigationGraph.Edge edge = entry.navEdge;
        if (edge == null) {
            return 0;
        }
        boolean windowed = edge.type == BotNavigationGraph.EdgeType.JUMP
                || (edge.type == BotNavigationGraph.EdgeType.DROP && edge.launchStepX == 0);
        if (!windowed || !edge.containsLaunchX(targetX)) {
            return 0;
        }
        int dir = Integer.signum(targetX - botX);
        if (dir == 0) {
            return 0;
        }
        int slack = dir > 0 ? edge.launchMaxX - targetX : targetX - edge.launchMinX;
        // JUMP execution additionally requires |x - launchX| <= walkStep around the selected
        // launch point — never allow sliding deeper into a wide window than that gate accepts.
        return Math.clamp(slack, 0, BotPhysicsEngine.walkStep(entry.bot.getMap(), entry.movementProfile));
    }

    static void initiateJump(BotEntry entry, Character bot, int dx) {
        BotPhysicsEngine.beginGroundJump(entry, bot, resolveAirVelocityX(entry, bot.getMap(), entry.movementProfile, dx));
        broadcastMovement(entry);
    }

    private static void initiateFixedArcJump(BotEntry entry, Character bot, int dx) {
        initiateJump(entry, bot, dx);
        entry.fixedAirArc = true;
    }

    /**
     * Fires a random recovery action when the bot has been stuck in the same spot.
     * Clears the nav edge so A* replans on the next AI tick.
     */
    static void tickUnstuck(BotEntry entry) {
        Character bot = entry.bot;
        int walkStep = BotPhysicsEngine.walkStep(bot.getMap(), entry.movementProfile);
        switch (ThreadLocalRandom.current().nextInt(2)) {
            case 0 -> BotPhysicsEngine.beginGroundJump(entry, bot, -walkStep); // jump left
            default -> BotPhysicsEngine.beginGroundJump(entry, bot, walkStep); // jump right
        }
        clearNavigationState(entry);
        entry.unstuckCooldownMs = delayAfterCurrentTick(5000);
        broadcastMovement(entry);
    }

    static void initiateRopeJump(BotEntry entry, Character bot, int dx, Rope targetRope) {
        BotPhysicsEngine.beginClimbUpJump(entry, bot, resolveAirVelocityX(entry, bot.getMap(), entry.movementProfile, dx));
        // Aim the mid-air grab at THIS rope only (set after launch — launchAirborne cleared it). Without
        // a target, successfullyGrabbedRope grabs whatever rope the arc passes, so a rope co-located at
        // the launch X hijacks a jump meant for a farther rope (Nautilus rope[6] stealing a jump at
        // rope[7]) → grab/exit oscillation.
        entry.climbIntentRope = targetRope;
        broadcastMovement(entry);
    }

    private static int resolveAirVelocityX(BotEntry entry, MapleMap map, BotMovementProfile profile, int dx) {
        if (dx == 0) {
            // No direction held at takeoff: the client carries the CURRENT ground hspeed into
            // the air (packet-verified standing jumps 0->0, 3->3, 9->10, 29->29 px/s). Only
            // meaningful on slippery ground where a no-input bot can still be sliding; on
            // fs=1 maps hspeed without input is ~0, so behavior there is exactly as before.
            return entry != null && BotPhysicsEngine.slipperyGround(map) && !entry.climbing
                    ? BotPhysicsEngine.carriedAirVelX(map, entry)
                    : 0;
        }
        // Full walk step always: intent-based, like holding the arrow key through a jump.
        // This is also the packet-true client launch rule: jumping with a direction held
        // snaps vx to +-walkSpeed instantly regardless of current ground speed (even from a
        // slow icy start, -34 -> -124 px/s at takeoff) — see Config.AIR_CONTROL_ACCEL_PXSS.
        // Graph jump edges are calibrated at ±walkStep of their OWN profile, so this matches
        // the simulated arc as long as planning and execution share a graph — which
        // resolveTarget's navGraph identity check now guarantees (a stale cross-profile edge,
        // e.g. stepX=-6 executed at walkStep 9, used to overfly its landing forever).
        int walkStep = BotPhysicsEngine.walkStep(map, profile);
        return dx > 0 ? walkStep : -walkStep;
    }

    static void broadcastMovement(BotEntry entry) {
        if (!BotPerformanceMonitor.enabled()) {
            doBroadcastMovement(entry);
            return;
        }

        long startedAt = System.nanoTime();
        try {
            doBroadcastMovement(entry);
        } finally {
            BotPerformanceMonitor.record("broadcast-move", System.nanoTime() - startedAt);
        }
    }

    private static void doBroadcastMovement(BotEntry entry) {
        entry.broadcastedThisTick = true; // movement state reconciled this tick (even if deduped below)
        Character bot = entry.bot;
        // No human/GM in the map -> nobody renders this move. Skip BEFORE building the packet so we
        // also avoid the per-tick allocation, not just the send. Invalidate the dedup cache so the
        // first tick after a player enters re-broadcasts a fresh state (spawn covers the static pose).
        // ponytail: O(chars) scan per tick per bot; make MapleMap track a non-bot count if it ever shows up hot.
        if (!bot.getMap().isObservedByPlayer()) {
            entry.movementBroadcastValid = false;
            return;
        }
        int x = bot.getPosition().x;
        int y = bot.getPosition().y;
        BotPhysicsEngine.MovementSnapshot snapshot = BotPhysicsEngine.movementSnapshot(entry);
        int fhId = resolveBroadcastFhId(entry, bot);

        if (entry.movementBroadcastValid
                && entry.lastBroadcastX == x
                && entry.lastBroadcastY == y
                && entry.lastBroadcastVelX == snapshot.velX()
                && entry.lastBroadcastVelY == snapshot.velY()
                && entry.lastBroadcastStance == snapshot.stance()
                && entry.lastBroadcastFh == fhId) {
            return;
        }

        entry.movementBroadcastValid = true;
        entry.lastBroadcastX = x;
        entry.lastBroadcastY = y;
        entry.lastBroadcastVelX = snapshot.velX();
        entry.lastBroadcastVelY = snapshot.velY();
        entry.lastBroadcastStance = snapshot.stance();
        entry.lastBroadcastFh = fhId;
        sendMovementPacket(bot, snapshot, fhId);
    }

    // Real clients report the foothold ID they're standing on in every move packet; the
    // client uses it to pick the render z-layer. Without it, bots draw on the top layer
    // (in front of tiles/walls). While airborne, clients keep sending the last-known
    // ground fh, so cache it on the bot entry.
    private static int resolveBroadcastFhId(BotEntry entry, Character bot) {
        Foothold fh = BotPhysicsEngine.findGroundFoothold(bot.getMap(), bot.getPosition());
        if (fh != null) {
            entry.lastGroundFhId = fh.getId();
        }
        return entry.lastGroundFhId;
    }

    private static void sendMovementPacket(Character bot, BotPhysicsEngine.MovementSnapshot snapshot, int fhId) {
        byte[] data = new byte[15];
        data[0] = 1;
        int x = bot.getPosition().x;
        int y = bot.getPosition().y;
        data[2] = (byte) (x & 0xFF);
        data[3] = (byte) (x >> 8);
        data[4] = (byte) (y & 0xFF);
        data[5] = (byte) (y >> 8);
        data[6] = (byte) (snapshot.velX() & 0xFF);
        data[7] = (byte) (snapshot.velX() >> 8);
        data[8] = (byte) (snapshot.velY() & 0xFF);
        data[9] = (byte) (snapshot.velY() >> 8);
        data[10] = (byte) (fhId & 0xFF);
        data[11] = (byte) (fhId >> 8);
        data[12] = (byte) snapshot.stance();
        data[13] = (byte) (BotPhysicsEngine.cfg.TICK_MS & 0xFF);
        data[14] = (byte) (BotPhysicsEngine.cfg.TICK_MS >> 8);
        InPacket packet = new ByteBufInPacket(Unpooled.wrappedBuffer(data));
        Packet movePacket = PacketCreator.movePlayer(bot.getId(), packet, data.length);
        bot.getMap().broadcastMessage(bot, movePacket, false);
    }

    /** Broadcast a teleport so other clients render a BLINK instead of a glide. Captured client
     *  teleport packets (logs/monitored-packets-teleport*) carry 4@origin then 3@dest, followed by
     *  an ordinary absolute landing fragment so observers settle at the arrival side immediately. */
    static void broadcastTeleport(BotEntry entry, Point origin, Point dest) {
        Character bot = entry.bot;
        BotPhysicsEngine.MovementSnapshot snapshot = BotPhysicsEngine.movementSnapshot(entry);
        int fhId = resolveBroadcastFhId(entry, bot);
        byte[] data = buildTeleportMovementData(origin, dest, snapshot, fhId);
        InPacket packet = new ByteBufInPacket(Unpooled.wrappedBuffer(data));
        Packet movePacket = PacketCreator.movePlayer(bot.getId(), packet, data.length);
        bot.getMap().broadcastMessage(bot, movePacket, false);
        // Pin the dedup cache at the landing state so the next normal broadcast doesn't re-glide origin->dest.
        entry.broadcastedThisTick = true;
        entry.movementBroadcastValid = true;
        entry.lastBroadcastX = dest.x;
        entry.lastBroadcastY = dest.y;
        entry.lastBroadcastVelX = snapshot.velX();
        entry.lastBroadcastVelY = snapshot.velY();
        entry.lastBroadcastStance = snapshot.stance();
        entry.lastBroadcastFh = fhId;
    }

    static byte[] buildTeleportMovementData(Point origin,
                                            Point dest,
                                            BotPhysicsEngine.MovementSnapshot snapshot,
                                            int fhId) {
        byte[] data = new byte[35];
        int i = 0;
        data[i++] = 3; // teleport origin, teleport destination, landing settle
        i = putTeleportFrag(data, i, (byte) 4, origin.x, origin.y, snapshot.stance());
        i = putTeleportFrag(data, i, (byte) 3, dest.x, dest.y, snapshot.stance());
        putAbsoluteFrag(data, i, dest.x, dest.y, snapshot.velX(), snapshot.velY(), fhId, snapshot.stance());
        return data;
    }

    private static int putTeleportFrag(byte[] data, int i, byte cmd, int x, int y, int stance) {
        data[i++] = cmd;
        data[i++] = (byte) (x & 0xFF);
        data[i++] = (byte) (x >> 8);
        data[i++] = (byte) (y & 0xFF);
        data[i++] = (byte) (y >> 8);
        data[i++] = 0; // xwobble
        data[i++] = 0;
        data[i++] = 0; // ywobble
        data[i++] = 0;
        data[i++] = (byte) stance;
        return i;
    }

    private static int putAbsoluteFrag(byte[] data, int i, int x, int y, int velX, int velY, int fhId, int stance) {
        data[i++] = 0;
        data[i++] = (byte) (x & 0xFF);
        data[i++] = (byte) (x >> 8);
        data[i++] = (byte) (y & 0xFF);
        data[i++] = (byte) (y >> 8);
        data[i++] = (byte) (velX & 0xFF);
        data[i++] = (byte) (velX >> 8);
        data[i++] = (byte) (velY & 0xFF);
        data[i++] = (byte) (velY >> 8);
        data[i++] = (byte) (fhId & 0xFF);
        data[i++] = (byte) (fhId >> 8);
        data[i++] = (byte) stance;
        data[i++] = (byte) (BotPhysicsEngine.cfg.TICK_MS & 0xFF);
        data[i++] = (byte) (BotPhysicsEngine.cfg.TICK_MS >> 8);
        return i;
    }

    /** Broadcast a flash jump so observers render the dash animation instead of a plain air-glide. The
     *  client plays the flash-jump action only for movement command type 6 ("fj", a RelativeLifeMovement:
     *  see AbstractMovementPacketHandler). The bot's normal per-tick type-0 absolute move conveys position
     *  but not the FJ action. Fired once at the apex impulse; the arc's remaining type-0 ticks carry the
     *  rest of the trajectory. Mirrors {@link #broadcastTeleport}.
     *
     *  <p>The fj fragment MUST be preceded, in the SAME path, by an absolute fragment. Verified against the
     *  v83 client (CMovePath::Decode @ 0x0068a33c): a type-6 fragment does NOT read x/y from the packet —
     *  the client sets its position to the PREVIOUS fragment's position and stores the two shorts into the
     *  velocity slots. The "previous position" register is seeded with packet-header garbage, so a LONE
     *  fj fragment renders the bot off-screen for one frame until the next absolute tick snaps it back.
     *  Every real flash-jump capture leads with an absolute cmd-0 (logs/monitored-packets-flashjump*). */
    static void broadcastFlashJump(BotEntry entry, int relDx, int relDy) {
        Character bot = entry.bot;
        BotPhysicsEngine.MovementSnapshot snapshot = BotPhysicsEngine.movementSnapshot(entry);
        int stance = snapshot.stance(); // JUMP stance while airborne
        int fhId = resolveBroadcastFhId(entry, bot);
        int x = bot.getPosition().x;
        int y = bot.getPosition().y;
        int dur = BotPhysicsEngine.cfg.TICK_MS;
        byte[] data = new byte[23];
        int i = 0;
        data[i++] = 2;                       // two commands: absolute anchor + fj
        data[i++] = 0;                       // cmd 0 — absolute, anchors the fj fragment's position
        data[i++] = (byte) (x & 0xFF);
        data[i++] = (byte) (x >> 8);
        data[i++] = (byte) (y & 0xFF);
        data[i++] = (byte) (y >> 8);
        data[i++] = (byte) (snapshot.velX() & 0xFF);
        data[i++] = (byte) (snapshot.velX() >> 8);
        data[i++] = (byte) (snapshot.velY() & 0xFF);
        data[i++] = (byte) (snapshot.velY() >> 8);
        data[i++] = (byte) (fhId & 0xFF);
        data[i++] = (byte) (fhId >> 8);
        data[i++] = (byte) stance;
        data[i++] = (byte) (dur & 0xFF);
        data[i++] = (byte) (dur >> 8);
        data[i++] = 6;                       // cmd 6 "fj" — RelativeLifeMovement (plays the dash action)
        data[i++] = (byte) (relDx & 0xFF);
        data[i++] = (byte) (relDx >> 8);
        data[i++] = (byte) (relDy & 0xFF);
        data[i++] = (byte) (relDy >> 8);
        data[i++] = (byte) stance;
        data[i++] = 0;                       // fj duration 0 — matches real captures (no extrapolation)
        data[i++] = 0;
        InPacket packet = new ByteBufInPacket(Unpooled.wrappedBuffer(data));
        Packet movePacket = PacketCreator.movePlayer(bot.getId(), packet, data.length);
        bot.getMap().broadcastMessage(bot, movePacket, false);
        // Pin the dedup cache at the post-impulse state so this tick isn't re-sent as a redundant type-0.
        entry.broadcastedThisTick = true;
        entry.movementBroadcastValid = true;
        entry.lastBroadcastX = bot.getPosition().x;
        entry.lastBroadcastY = bot.getPosition().y;
        entry.lastBroadcastVelX = snapshot.velX();
        entry.lastBroadcastVelY = snapshot.velY();
        entry.lastBroadcastStance = stance;
        entry.lastBroadcastFh = fhId;
    }


    private static JumpLanding wrapLanding(BotPhysicsEngine.JumpLanding landing) {
        if (landing == null) {
            return null;
        }
        return new JumpLanding(landing.point(), landing.foothold());
    }
}
