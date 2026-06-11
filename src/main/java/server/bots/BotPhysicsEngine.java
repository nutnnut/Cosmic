package server.bots;

import client.Character;
import constants.game.CharacterStance;
import server.maps.Foothold;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class BotPhysicsEngine {
    private static final double CLIENT_GROUND_STEP_MS = 8.0;
    private static final double CLIENT_GROUND_STEP_S = CLIENT_GROUND_STEP_MS / 1000.0;
    private static final int REGION_STITCH_GAP_PX = 2;
    private static final int SYNTHETIC_MAP_BOUND_SIZE = 1 << 18;
    // Max horizontal gap between adjacent foothold endpoints that the bot can walk across.
    // Shared with BotNavigationGraphProvider so walk-edge generation and physics agree.
    static final int WALK_GAP_PX = 12;

    static class Config {
        public int TICK_MS = 50;

        // Values below calibrated against real-client CP_USER_MOVE packet captures
        // (monitored-packets logs for speed=100/jump=100 walk, long-fall terminal
        // velocity, and rope climb/jump). Old values kept in comments for reference.
        // Ground-truth source: wz/Map.wz/Physics.img.xml (loaded by v83 client into
        // physcfg @ *[0xbebfa0]+8). Field name listed inline where applicable.
        public int WALK_VEL = 125;                  // Physics.img walkSpeed
        public float GRAVITY_PXS2 = 2000.0f;        // Physics.img gravityAcc
        public float JUMP_SPEED_PXS = 555.0f;       // Physics.img jumpSpeed
        public float JUMP_DOWN_PXS = 196.0f;        // measured -196 px/s down-jump kick (not in Physics.img)
        public float JUMP_ROPE_PXS = 375.0f;        // rope-jump finding (NOT applied): real client kick = (±162, -277)
        public float MAX_FALL_PXS = 670.0f;         // Physics.img fallSpeed
        public double HFORCE_PXS = 16.667;          // was 20.0 (yields 125 px/s walk via hF*GROUNDSLIP/(FRICTION+SLOPEFACTOR))
        public double GROUNDSLIP = 3.0;
        public double FRICTION = 0.3;
        public double SLOPEFACTOR = 0.1;
        public double AIR_STEER_ACCEL = 0.5;   // px/tick added per tick toward target
        public double AIR_STEER_MAX   = 1.5;  // cap on air-steering speed (px/tick)

        public float CLIMB_SPEED_PXS = 100.0f;
        public int ROPE_GRAB_X = 8;
        public int MAX_SNAP_DROP = 16;
        public int MAX_SLOPE_UP = 26;
        public int DOWN_JUMP_GRACE_MS = 350;

        // Swim physics. Bot ticks at 50ms (TICK_MS); constants are in px/s and
        // px/s² so they're tick-rate independent. Ground-truth sources:
        //   - wz/Map.wz/Physics.img.xml (named doubles: swimSpeed, swimForce,
        //     swimSpeedDec, floatDrag1, floatDrag2, floatCoefficient, etc.)
        //   - Angel.idb: CalcFloat@CVecCtrl @ 0x9b2c3c (swim integrator),
        //     JustJump@CVecCtrl @ 0x9b1d3d (swim jump impulse)
        public float SWIM_VEL_PXS = 140.0f;          // Physics.img swimSpeed
        // Constants below are output of an automated least-squares fitter
        // (`tools/swim_fit.py`) — not hand-tuned. Decodes all swim packet
        // logs (burst, burst-upheld, upheld, downheld) into 34 (vy0, dur,
        // vy_end) tuples and fits a linear-drag model dv/dt = g_eff - k·v
        // via scipy differential_evolution. Quadratic drag term tested but
        // converged to zero. Re-run after collecting more packet captures.
        //
        // Packet terminal sinks from monitored-packets-swim{,-upheld,-downheld}.log:
        //   no-key  = 140 px/s, UP-held = 42 px/s, DOWN-held = 210 px/s.
        // Keep these as explicit caps; the fitted acceleration/drag model only
        // controls how quickly the bot approaches the packet-observed terminal.
        public float SWIM_GRAVITY_PXS2 = 590.0f;
        public float SWIM_FRICTION_HZ = 4.21f;
        public float SWIM_ACCEL_PXS2 = 600.0f;       // horizontal accel (not yet calibrated)
        public float SWIM_MAX_SPEED_PXS = 800.0f;
        public int SWIM_ARRIVAL_RADIUS_PX = 8;

        public float SWIM_JUMP_BURST_PXS = 1000.0f;
        public float SWIM_UP_THRUST_PXS2 = 412.0f;
        public float SWIM_DOWN_THRUST_PXS2 = 295.0f;
        public float SWIM_FREE_MAX_SINK_PXS = 140.0f; // observed no-key terminal
        public float SWIM_DOWN_MAX_SPEED_PXS = 210.0f; // observed DOWN-held terminal
        public float SWIM_UP_MAX_SINK_PXS = 42.0f;     // observed UP-held terminal
        // Cooldown reflects observed swim-jump cadence. Jump@CVecCtrl @
        // 0x9b2202 calls JustJump with no engine cooldown, but in practice
        // the swim-burst animation gates the next effective jump to ~500ms.
        public int SWIM_JUMP_COOLDOWN_MS = 500;
        public int SWIM_LEVEL_BAND_PX = 30;          // |dy| <= this = "same level" → UP hold
        public int SWIM_DOWN_BAND_PX = 120;          // dy in (level, this] = free sink; > this = DOWN hold
        public int SWIM_JUMP_TRIGGER_DY_PX = 100;    // dy <= -this px = trigger JUMP burst (with cooldown)
    }

    record GroundMotion(int stepX, boolean lostGround) {
    }

    record GroundTravelState(double physX, double hspeed, double carryMs) {
    }

    record GroundStepResult(Point point,
                            Foothold foothold,
                            GroundTravelState state,
                            int stepX,
                            int velocityX,
                            boolean lostGround) {
    }

    private record GroundRegionSample(Point point, Foothold foothold) {
    }

    private record GroundStepPreview(int baseY, Point point, Foothold foothold, boolean lostGround, boolean blocked) {
    }

    private record WalkRegionLookup(int mapId,
                                    Map<Integer, BotNavigationGraph.Region> regionsById,
                                    Map<Integer, Integer> regionIdByFootholdId,
                                    Map<Integer, Foothold> footholdsById) {
    }

    record MovementSnapshot(int velX, int velY, int stance) {
    }

    static final class JumpLanding {
        private final Point point;
        private final Foothold foothold;
        private final double incomingDeltaX;
        private final double incomingDeltaY;
        private final int ticks;

        JumpLanding(Point point, Foothold foothold) {
            this(point, foothold, 0.0, 0.0, 0);
        }

        JumpLanding(Point point, Foothold foothold, double incomingDeltaX, double incomingDeltaY) {
            this(point, foothold, incomingDeltaX, incomingDeltaY, 0);
        }

        JumpLanding(Point point, Foothold foothold, double incomingDeltaX, double incomingDeltaY, int ticks) {
            this.point = point;
            this.foothold = foothold;
            this.incomingDeltaX = incomingDeltaX;
            this.incomingDeltaY = incomingDeltaY;
            this.ticks = ticks;
        }

        Point point() {
            return point;
        }

        Foothold foothold() {
            return foothold;
        }

        double incomingDeltaX() {
            return incomingDeltaX;
        }

        double incomingDeltaY() {
            return incomingDeltaY;
        }

        int timeMs() {
            return ticks * cfg.TICK_MS;
        }
    }

    record PostLandingJump(JumpLanding landing,
                           Point finalPoint,
                           Foothold finalFoothold,
                           boolean lostGround) {
    }

    record WalkOffLanding(Point launchPoint,
                          int launchStepX,
                          JumpLanding landing,
                          int travelTimeMs) {
    }

    private enum AirCollisionType {
        NONE,
        WALL,
        CEILING,
        LAND
    }

    private record AirCollision(AirCollisionType type, Point point, Foothold foothold, double progress) {
        static AirCollision none() {
            return new AirCollision(AirCollisionType.NONE, null, null, Double.POSITIVE_INFINITY);
        }
    }

    private record RopeGrabResult(Point point, int ticks) {}

    enum AirborneStepResult {
        WALL,
        CEILING,
        LANDED,
        CONTINUE
    }

    static Config cfg = new Config();
    private static final ThreadLocal<WalkRegionLookup> ACTIVE_BUILD_WALK_REGION_LOOKUP = new ThreadLocal<>();
    private static final Map<Integer, Map<Integer, Foothold>> FOOTHOLDS_BY_ID_BY_MAP_ID = new ConcurrentHashMap<>();

    private BotPhysicsEngine() {
    }

    private static BotMovementProfile profileOrBase(BotMovementProfile profile) {
        return profile != null ? profile : BotMovementProfile.base();
    }

    static float tickS() {
        return cfg.TICK_MS / 1000f;
    }

    static float maxFallPerTick() {
        return cfg.MAX_FALL_PXS * tickS();
    }

    static float jumpForcePerTick() {
        return cfg.JUMP_SPEED_PXS * tickS();
    }

    static float jumpForcePerTick(BotMovementProfile profile) {
        return profileOrBase(profile).jumpSpeedPxs() * tickS();
    }

    static float downJumpForcePerTick() {
        return cfg.JUMP_DOWN_PXS * tickS();
    }

    static float ropeJumpForcePerTick() {
        return cfg.JUMP_ROPE_PXS * tickS();
    }

    static float ropeJumpForcePerTick(BotMovementProfile profile) {
        return profileOrBase(profile).ropeJumpSpeedPxs() * tickS();
    }

    static int climbStepPerTick() {
        return Math.max(1, Math.round(cfg.CLIMB_SPEED_PXS * tickS()));
    }

    static float gravityPerTick() {
        float t = tickS();
        return cfg.GRAVITY_PXS2 * t * t;
    }

    static int walkStep(MapleMap map) {
        return walkStep(map, BotMovementProfile.base());
    }

    static int walkStep(MapleMap map, BotMovementProfile profile) {
        double step = maxHSpeedPerClientStep(profile) * cfg.TICK_MS / CLIENT_GROUND_STEP_MS;
        return Math.max(1, (int) Math.round(step));
    }

    /**
     * Distance (px) needed for a bot starting from a standstill to reach (near) max walk speed
     * before walking off a ledge. O(1) derivation from config constants — used by graphgen to
     * place directional-drop launch points without simulating the runway tick by tick.
     *
     * <p>Friction model: dv/dt = hF*slip - (friction+slope)*v. Terminal v_max = hF*slip/(friction+slope).
     * Time to ~95% terminal ≈ 3/(friction+slope). We use a fixed multiple of walkStep that comfortably
     * exceeds the 95% mark for the calibrated constants without iterating.
     */
    static int launchRunwayPx(MapleMap map, BotMovementProfile profile) {
        int step = walkStep(map, profile);
        // Slippery fields stretch time-to-terminal by 1/fs — the runway grows with it.
        return (int) Math.max(40, Math.round(step * 6 / mapGroundSlipScale(map)));
    }

    static int velocityFromDeltaX(double deltaX) {
        return (int) Math.round(deltaX * (1000.0 / cfg.TICK_MS));
    }

    static void syncGroundPosition(BotEntry entry, int x) {
        if (entry.hspeed == 0.0 && (int) Math.round(entry.physX) != x) {
            entry.physX = x;
        }
    }

    /**
     * Syncs ground position, then returns the foothold the bot is standing on.
     * If no foothold is found the bot has walked off the edge — physics starts a fall and this
     * returns null. Movement must check for null and return early without applying ground actions.
     */
    static Foothold syncAndDetectGround(BotEntry entry, Character bot) {
        syncGroundPosition(entry, bot.getPosition().x);
        Foothold fh = findGroundFoothold(bot.getMap(), bot.getPosition());
        if (fh == null) {
            beginFall(entry, bot, 0);
        }
        return fh;
    }

    static Foothold findGroundFoothold(MapleMap map, Point position) {
        if (map == null || map.getFootholds() == null || position == null) {
            return null;
        }

        Foothold exact = findBelowIndexed(map, position);
        Foothold offset = findBelowIndexed(map, new Point(position.x, position.y - cfg.MAX_SLOPE_UP));
        if (exact == null) return offset;
        if (offset == null) return exact;

        // On sloped footholds, integer truncation of the interpolated Y can make the foothold's
        // computed Y fall 1px above the player's stored position, causing findBelow to skip it
        // and return a distant platform instead. Mirror findGroundPoint: pick the closer result.
        Point exactGround = pointBelowIndexed(map, position);
        Point offsetGround = pointBelowIndexed(map, new Point(position.x, position.y - cfg.MAX_SLOPE_UP));
        if (exactGround == null) return offset;
        if (offsetGround == null) return exact;
        return Math.abs(offsetGround.y - position.y) < Math.abs(exactGround.y - position.y) ? offset : exact;
    }

    static Point findGroundPoint(MapleMap map, Point position) {
        if (map == null || position == null) {
            return null;
        }

        Point exactGround = pointBelowIndexed(map, position);
        Point offsetGround = pointBelowIndexed(map, new Point(position.x, position.y - cfg.MAX_SLOPE_UP));
        if (exactGround == null) {
            return offsetGround;
        }
        if (offsetGround == null) {
            return exactGround;
        }

        int exactDistance = Math.abs(exactGround.y - position.y);
        int offsetDistance = Math.abs(offsetGround.y - position.y);
        return offsetDistance < exactDistance ? offsetGround : exactGround;
    }

    // Canonical walk-connectivity rule shared by graph region merging and runtime ground traversal.
    // If two foothold endpoints form a walkable local step, they must be in the same walk region.
    static boolean canWalkAcrossFootholds(Foothold first, Foothold second) {
        if (first == null || second == null || first.isWall() || second.isWall()) {
            return false;
        }

        EndpointConnection connection = sharedEndpointConnection(first, second);
        if (connection == null) {
            connection = closestEndpointConnection(first, second);
            if (connection == null
                    || (Math.abs(connection.to().x - connection.from().x)
                    + Math.abs(connection.to().y - connection.from().y)) > 2) {
                return false;
            }
        }

        int dx = Math.abs(connection.to().x - connection.from().x);
        int dy = connection.to().y - connection.from().y;
        if (!isWalkableEndpointStep(dx, dy)) {
            return false;
        }

        return true;
    }

    private record EndpointConnection(Point from, Point to) {
    }

    private static Point[] endpoints(Foothold fh) {
        return new Point[]{new Point(fh.getX1(), fh.getY1()), new Point(fh.getX2(), fh.getY2())};
    }

    private static EndpointConnection closestEndpointConnection(Foothold first, Foothold second) {
        EndpointConnection best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Point from : endpoints(first)) {
            for (Point to : endpoints(second)) {
                int distance = Math.abs(to.x - from.x) + Math.abs(to.y - from.y);
                if (distance < bestDistance) {
                    best = new EndpointConnection(from, to);
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static EndpointConnection sharedEndpointConnection(Foothold first, Foothold second) {
        for (Point from : endpoints(first)) {
            for (Point to : endpoints(second)) {
                if (from.equals(to)) {
                    return new EndpointConnection(from, to);
                }
            }
        }
        return null;
    }

    static Point findWalkRegionGroundPoint(MapleMap map, Foothold foothold, int x, int referenceY) {
        GroundRegionSample sample = findWalkRegionGroundSample(map, foothold, x, referenceY);
        return sample == null ? null : sample.point();
    }

    static boolean canWalkGroundStep(MapleMap map, Point currentPos, int stepX) {
        if (map == null || currentPos == null) {
            return false;
        }
        Foothold foothold = findGroundFoothold(map, currentPos);
        GroundStepPreview preview = previewGroundStep(map, currentPos, foothold, currentPos.x + stepX);
        return preview != null && !preview.lostGround() && !preview.blocked();
    }

    static boolean isGroundStepBlockedByWall(MapleMap map, Point currentPos, int stepX) {
        if (map == null || currentPos == null || stepX == 0) {
            return false;
        }
        Foothold foothold = findGroundFoothold(map, currentPos);
        GroundStepPreview preview = previewGroundStep(map, currentPos, foothold, currentPos.x + stepX);
        return preview != null && preview.blocked();
    }

    static boolean isGroundRunwayBlockedByWall(MapleMap map, Point from, Point to) {
        return findGroundWallCollision(map, from, to).type() == AirCollisionType.WALL;
    }

    static boolean isGroundFarBelow(MapleMap map, Point position) {
        if (map == null || position == null) {
            return true;
        }
        Point ground = findGroundPoint(map, position);
        return ground == null || ground.y > position.y + cfg.MAX_SNAP_DROP;
    }

    private static boolean hasWalkRegion(MapleMap map, Foothold foothold) {
        if (map == null || foothold == null) {
            return false;
        }
        WalkRegionLookup lookup = resolveWalkRegionLookup(map);
        if (lookup == null) {
            return false;
        }
        return lookup.regionIdByFootholdId().getOrDefault(foothold.getId(), -1) >= 0;
    }

    private static GroundRegionSample findWalkRegionGroundSample(MapleMap map, Foothold foothold, int x, int referenceY) {
        if (map == null || foothold == null) {
            return null;
        }

        WalkRegionLookup lookup = resolveWalkRegionLookup(map);
        if (lookup == null) {
            return null;
        }
        int regionId = lookup.regionIdByFootholdId().getOrDefault(foothold.getId(), -1);
        BotNavigationGraph.Region region = lookup.regionsById().get(regionId);
        if (region == null || region.isRopeRegion) {
            return null;
        }

        BotNavigationGraph.Segment bestSegment = null;
        Point bestPoint = null;
        int bestScore = Integer.MAX_VALUE;
        boolean foundContainingSegment = false;
        for (BotNavigationGraph.Segment segment : region.segments) {
            if (segment.containsX(x)) {
                foundContainingSegment = true;
                break;
            }
        }

        for (BotNavigationGraph.Segment segment : region.segments) {
            int dx = distanceToSegmentX(segment, x);
            boolean containsX = segment.containsX(x);
            if (!containsX && (foundContainingSegment || dx > REGION_STITCH_GAP_PX)) {
                continue;
            }

            Point candidate = segment.pointAt(x);
            int dy = candidate.y - referenceY;
            if (dy > cfg.MAX_SNAP_DROP || dy < -cfg.MAX_SLOPE_UP) {
                continue;
            }

            int score = dx * 1000 + Math.abs(dy);
            if (bestPoint == null
                    || score < bestScore
                    || (score == bestScore && candidate.y > bestPoint.y)) {
                bestSegment = segment;
                bestPoint = candidate;
                bestScore = score;
            }
        }

        if (bestSegment == null) {
            return null;
        }

        Foothold bestFoothold = lookup.footholdsById().get(bestSegment.footholdId);
        if (bestFoothold == null) {
            return null;
        }
        return new GroundRegionSample(bestPoint, bestFoothold);
    }

    static void setBuildWalkRegionLookup(MapleMap map,
                                         Map<Integer, BotNavigationGraph.Region> regionsById,
                                         Map<Integer, Integer> regionIdByFootholdId,
                                         Map<Integer, Foothold> footholdsById) {
        if (map == null || regionsById == null || regionIdByFootholdId == null || footholdsById == null) {
            ACTIVE_BUILD_WALK_REGION_LOOKUP.remove();
            return;
        }
        ACTIVE_BUILD_WALK_REGION_LOOKUP.set(new WalkRegionLookup(map.getId(), regionsById, regionIdByFootholdId, footholdsById));
    }

    static void clearBuildWalkRegionLookup() {
        ACTIVE_BUILD_WALK_REGION_LOOKUP.remove();
    }

    private static WalkRegionLookup resolveWalkRegionLookup(MapleMap map) {
        if (map == null) {
            return null;
        }

        WalkRegionLookup activeLookup = ACTIVE_BUILD_WALK_REGION_LOOKUP.get();
        if (activeLookup != null && activeLookup.mapId() == map.getId()) {
            return activeLookup;
        }

        BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map);
        if (graph == null) {
            return null;
        }

        return new WalkRegionLookup(map.getId(), graph.regionsById, graph.regionIdByFootholdId, footholdsById(map));
    }

    private static Map<Integer, Foothold> footholdsById(MapleMap map) {
        if (map == null || map.getFootholds() == null) {
            return Map.of();
        }

        return FOOTHOLDS_BY_ID_BY_MAP_ID.computeIfAbsent(map.getId(), ignored -> {
            Map<Integer, Foothold> footholdsById = new HashMap<>();
            for (Foothold foothold : map.getFootholds().getAllFootholds()) {
                footholdsById.put(foothold.getId(), foothold);
            }
            return footholdsById;
        });
    }

    private static GroundStepPreview previewGroundStep(MapleMap map, Point currentPos, Foothold foothold, int nextX) {
        if (map == null || currentPos == null) {
            return null;
        }

        boolean constrainToWalkRegion = hasWalkRegion(map, foothold);
        Point standingPoint = constrainToWalkRegion
                ? findWalkRegionGroundPoint(map, foothold, currentPos.x, currentPos.y)
                : null;
        if (standingPoint == null) {
            standingPoint = findGroundPoint(map, currentPos);
        }

        int baseY = standingPoint != null
                && Math.abs(standingPoint.y - currentPos.y) <= cfg.MAX_SLOPE_UP
                ? standingPoint.y
                : currentPos.y;

        AirCollision wall = findGroundWallCollision(map, currentPos, new Point(nextX, baseY));
        if (wall.type() == AirCollisionType.WALL) {
            return new GroundStepPreview(baseY, currentPos, foothold, false, true);
        }

        Point snappedPoint;
        Foothold snappedFoothold;
        boolean lostGround;
        if (constrainToWalkRegion) {
            GroundRegionSample snappedSample = findWalkRegionGroundSample(map, foothold, nextX, baseY);
            snappedPoint = snappedSample == null ? null : snappedSample.point();
            snappedFoothold = snappedSample == null ? null : snappedSample.foothold();
            lostGround = snappedPoint == null || snappedPoint.y > baseY + cfg.MAX_SNAP_DROP;
        } else {
            int probeY = Math.max(currentPos.y, baseY + 1);
            snappedPoint = findGroundPoint(map, new Point(nextX, probeY));
            lostGround = snappedPoint == null || snappedPoint.y > baseY + cfg.MAX_SNAP_DROP;
            snappedFoothold = snappedPoint == null || map.getFootholds() == null
                    ? null
                    : findBelowIndexed(map, new Point(nextX, snappedPoint.y + 1));
        }

        return new GroundStepPreview(baseY, snappedPoint, snappedFoothold, lostGround, false);
    }

    private static int distanceToSegmentX(BotNavigationGraph.Segment segment, int x) {
        if (segment.containsX(x)) {
            return 0;
        }
        return x < segment.minX ? segment.minX - x : x - segment.maxX;
    }

    static void stopGroundMotion(BotEntry entry) {
        entry.hspeed = 0.0;
    }

    static void resetMotion(BotEntry entry, Point position) {
        clearMovementState(entry, position);
        syncCharacterState(entry);
    }

    static void teleportTo(BotEntry entry, Character bot, Point position) {
        bot.setPosition(position);
        clearMovementState(entry, position);
        syncCharacterState(entry);
    }

    static void markDead(BotEntry entry, Character bot) {
        clearMovementState(entry, bot.getPosition());
        syncCharacterState(entry);
    }

    static void idleOnGround(BotEntry entry, Character bot) {
        Point position = bot.getPosition();
        entry.inAir = false;
        entry.climbing = false;
        entry.climbRope = null;
        entry.crouching = false;
        entry.climbUpIntent = false;
        clearRopeEntryIntent(entry);
        entry.velY = 0f;
        entry.airVelX = 0;
        entry.airSteerVelX = 0.0;
        entry.fixedAirArc = false;
        entry.moveDir = 0;
        entry.physX = position.x;
        entry.physY = position.y;
        stopGroundMotion(entry);
        setMovementVelocity(entry, 0, 0);
        syncCharacterState(entry);
    }

    static void proneOnGround(BotEntry entry, Character bot) {
        idleOnGround(entry, bot);
        entry.crouching = true;
        entry.downJumpPending = false;
        syncCharacterState(entry);
    }

    static void queueDownJump(BotEntry entry, Character bot) {
        idleOnGround(entry, bot);
        entry.downJumpPending = true;
        entry.crouching = true;
        syncCharacterState(entry);
    }

    static void queueTopRopeEntry(BotEntry entry, Character bot, Rope rope, int y) {
        idleOnGround(entry, bot);
        entry.ropeEntryPending = true;
        entry.ropeEntryRope = rope;
        entry.ropeEntryY = y;
        syncCharacterState(entry);
    }

    static void beginGroundJump(BotEntry entry, Character bot, int airVelX) {
        entry.blockedRopeGrab = null;
        // In swim maps, physics owns horizontal motion — drop any committed
        // airVelX/fixedAirArc the caller passed so swim integrator can steer.
        // Movement layer expresses *intent only* in water.
        if (bot.getMap() != null && bot.getMap().isSwim()) {
            airVelX = 0;
            // Ground jump in water uses the regular jump impulse, but once the
            // character leaves the foothold the value is in swim px/s units.
            // Do not route this through launchAirborne's packet velocity
            // conversion: that path expects px/tick and emits a huge one-tick
            // velocity when given swim px/s.
            Point position = bot.getPosition();
            entry.climbing = false;
            entry.climbRope = null;
            entry.inAir = true;
            entry.swimming = true;
            entry.crouching = false;
            entry.physX = position.x;
            entry.physY = position.y;
            entry.velY = -profileOrBase(entry.movementProfile).jumpSpeedPxs();
            stopGroundMotion(entry);
            entry.climbUpIntent = false;
            entry.airVelX = 0;
            entry.airSteerVelX = 0.0;
            entry.fixedAirArc = false;
            entry.downJumpPending = false;
            entry.swimJumpRequested = false;
            // The first impulse off a foothold is the small ground jump. A
            // mid-water swim burst may follow later, but not on the next swim
            // tick just because the steering target is still above the bot.
            entry.swimNextJumpAtMs = System.currentTimeMillis() + cfg.SWIM_JUMP_COOLDOWN_MS;
            setMovementVelocity(entry, 0, Math.round(entry.velY));
            syncCharacterState(entry);
            return;
        }
        launchAirborne(entry, bot, bot.getPosition(), -jumpForcePerTick(entry.movementProfile), airVelX, false);
    }

    static void beginClimbUpJump(BotEntry entry, Character bot, int airVelX) {
        entry.blockedRopeGrab = null;
        launchAirborne(entry, bot, bot.getPosition(), -jumpForcePerTick(entry.movementProfile), airVelX, true);
    }

    static void beginJumpOffRope(BotEntry entry, Character bot, int airVelX) {
        entry.blockedRopeGrab = null;
        launchAirborne(entry, bot, bot.getPosition(), -ropeJumpForcePerTick(entry.movementProfile), airVelX, false);
    }

    static void beginRopeTransferJump(BotEntry entry, Character bot, Rope sourceRope, int airVelX) {
        entry.blockedRopeGrab = sourceRope;
        launchAirborne(entry, bot, bot.getPosition(), -ropeJumpForcePerTick(entry.movementProfile), airVelX, true);
    }

    static void beginDownJump(BotEntry entry, Character bot) {
        if (!canStartDownJump(bot.getMap(), bot.getPosition())) {
            entry.downJumpPending = false;
            entry.downJumpGracePeriodMS = 0L;
            entry.crouching = false;
            syncCharacterState(entry);
            return;
        }
        entry.blockedRopeGrab = null;
        launchAirborne(entry, bot, bot.getPosition(), -downJumpForcePerTick(), 0, false);
        entry.downJumpGracePeriodMS = cfg.DOWN_JUMP_GRACE_MS;
    }

    static void beginTopRopeEntry(BotEntry entry, Character bot) {
        Rope rope = entry.ropeEntryRope;
        int ropeY = entry.ropeEntryY;
        clearRopeEntryIntent(entry);
        if (rope == null || bot == null) {
            syncCharacterState(entry);
            return;
        }
        Point position = bot.getPosition();
        if (position == null || Math.abs(position.x - rope.x()) > cfg.ROPE_GRAB_X) {
            syncCharacterState(entry);
            return;
        }
        attachToRope(entry, bot, rope, ropeY);
    }

    /** Called by navigation when a DROP edge is executed — bot intentionally walks off a ledge. */
    static void executeDrop(BotEntry entry, Character bot, int airVelX) {
        beginFall(entry, bot, airVelX);
    }

    private static void beginFall(BotEntry entry, Character bot, int airVelX) {
        beginFall(entry, bot, bot.getPosition(), airVelX);
    }

    private static void beginFall(BotEntry entry, Character bot, Point position, int airVelX) {
        entry.blockedRopeGrab = null;
        bot.setPosition(new Point(position));
        launchAirborne(entry, bot, position, 0f, airVelX, false);
    }

    static void beginKnockback(BotEntry entry, Character bot, Point position, float initialVelY, int airVelX) {
        int preservedFacingDir = entry.facingDir;
        bot.setPosition(position);
        entry.blockedRopeGrab = null;
        launchAirborne(entry, bot, position, initialVelY, airVelX, true);
        entry.facingDir = preservedFacingDir;
        syncCharacterState(entry);
    }

    static void applyAirKnockback(BotEntry entry, Character bot, int airVelX) {
        int preservedFacingDir = entry.facingDir;
        Point position = bot.getPosition();
        entry.inAir = true;
        entry.climbing = false;
        entry.climbRope = null;
        entry.crouching = false;
        entry.physX = position.x;
        entry.physY = position.y;
        stopGroundMotion(entry);
        entry.climbUpIntent = true;
        entry.airVelX = airVelX;
        entry.airSteerVelX = 0.0;
        entry.fixedAirArc = false;
        entry.downJumpPending = false;
        entry.blockedRopeGrab = null;
        setMovementVelocity(entry, velocityFromDeltaX(airVelX), velocityFromAirStep(entry.velY));
        entry.facingDir = preservedFacingDir;
        syncCharacterState(entry);
    }

    private static void landOnGround(BotEntry entry, Character bot, Point position) {
        landOnGround(entry, bot, position, null, 0.0, 0.0);
    }

    private static void landOnGround(BotEntry entry,
                             Character bot,
                             Point position,
                             Foothold foothold,
                             double incomingDeltaX,
                             double incomingDeltaY) {
        // Fall distance = descent from peak-air-point down to landing point.
        // `fallPeakPhysY` was maintained in advanceAirbornePosition for this airborne period.
        double fallDistance = Double.isFinite(entry.fallPeakPhysY)
                ? Math.max(0.0, position.y - entry.fallPeakPhysY)
                : 0.0;
        bot.setPosition(position);
        entry.inAir = false;
        entry.climbing = false;
        entry.climbRope = null;
        entry.crouching = false;
        entry.climbUpIntent = false;
        entry.velY = 0f;
        entry.airVelX = 0;
        entry.airSteerVelX = 0.0;
        entry.fixedAirArc = false;
        entry.physX = position.x;
        entry.physY = position.y;
        clearRopeEntryIntent(entry);
        entry.downJumpPending = false;
        entry.downJumpGracePeriodMS = 0L;
        entry.groundPhysicsCarryMs = 0.0;
        entry.blockedRopeGrab = null;
        entry.hspeed = landingGroundHSpeed(bot.getMap(), foothold, incomingDeltaX, incomingDeltaY, entry.movementProfile);
        setMovementVelocity(entry, velocityFromDeltaX(tickDeltaFromGroundHSpeed(bot.getMap(), entry.hspeed, entry.movementProfile)), 0);
        syncCharacterState(entry);

        // Fall-damage check triggers exactly once, at the landing transition, using the
        // peak-to-landing descent distance. Below-threshold landings are no-ops (no packet).
        // Reset peak AFTER the check so the next airborne period starts fresh.
        BotCombatManager.applyFallDamage(entry, bot, (float) fallDistance);
        entry.fallPeakPhysY = Double.POSITIVE_INFINITY;
    }

    static void attachToRope(BotEntry entry, Character bot, Rope rope, int y) {
        int ropeY = Math.clamp(y, firstClimbableY(rope), rope.bottomY());
        entry.climbVerticalDir = 0;
        setClimbPosition(entry, bot, rope, ropeY);
    }

   /**
     * Intent-driven climb integrator. Reads {@link BotEntry#climbVerticalDir} for vertical
     * direction (-1=up, 0=idle, +1=down). Movement layer sets intent before calling.
     */
    static void advanceClimb(BotEntry entry, Character bot) {
        Rope rope = entry.climbRope;
        if (rope == null) {
            beginFall(entry, bot, 0);
            return;
        }

        int climbDir = Integer.compare(entry.climbVerticalDir, 0);
        if (climbDir == 0) {
            holdClimb(entry, bot);
            return;
        }

        int nextY = bot.getPosition().y + climbDir * climbStepPerTick();
        if (resolveClimbBoundary(entry, bot, rope, nextY)) {
            return;
        }

        setClimbPosition(entry, bot, rope, nextY);
    }

    static void holdClimb(BotEntry entry, Character bot) {
        Rope rope = entry.climbRope;
        if (rope == null) {
            beginFall(entry, bot, 0);
            return;
        }
        if (resolveClimbBoundary(entry, bot, rope, bot.getPosition().y)) {
            return;
        }

        setMovementVelocity(entry, 0, 0);
        syncCharacterState(entry);
    }

    static void tickMotionTimers(BotEntry entry) {
        if (entry.downJumpGracePeriodMS > 0L) {
            entry.downJumpGracePeriodMS = Math.max(0L, entry.downJumpGracePeriodMS - cfg.TICK_MS);
        }
    }

    static boolean canLand(BotEntry entry) {
        return entry.downJumpGracePeriodMS == 0L;
    }

  /**
     * Intent-driven ground integrator. Reads {@link BotEntry#moveDir} for horizontal
     * steer direction (-1/0/+1). Physics owns velocity integration via force/friction model.
     * Movement layer sets intent before calling; physics never returns velocity to movement.
     */
    static GroundMotion applyGroundMotion(BotEntry entry, Character bot, Foothold foothold) {
        MapleMap map = bot.getMap();
        Point currentPos = bot.getPosition();
        int desiredDir = entry.moveDir;
        GroundStepResult step = simulateGroundMotion(map, currentPos, foothold, desiredDir,
                new GroundTravelState(entry.physX, entry.hspeed, entry.groundPhysicsCarryMs), entry.movementProfile);

        // Snap-up to a *different* foothold means the bot walked off the edge and a separate
        // platform happens to be within MAX_SLOPE_UP above. That is not an uphill slope of the
        // current foothold - the bot should fall, not jump up to the unconnected platform.
        if (step.lostGround()) {
            beginFall(entry, bot, step.point(), step.stepX());
            return new GroundMotion(step.stepX(), true);
        }

        Point position = step.point();
        bot.setPosition(position);
        entry.inAir = false;
        entry.climbing = false;
        entry.climbRope = null;
        entry.crouching = false;
        entry.climbUpIntent = false;
        entry.velY = 0f;
        entry.airVelX = 0;
        entry.airSteerVelX = 0.0;
        entry.fixedAirArc = false;
        entry.physX = position.x;
        entry.physY = position.y;
        entry.hspeed = step.state().hspeed();
        entry.groundPhysicsCarryMs = step.state().carryMs();
        entry.downJumpPending = false;
        setMovementVelocity(entry, step.velocityX(), 0);
        syncCharacterState(entry);
        return new GroundMotion(step.stepX(), false);
    }

    static GroundTravelState initialGroundTravelState(Point position) {
        return new GroundTravelState(position.x, 0.0, 0.0);
    }

    static GroundStepResult simulateGroundMotion(MapleMap map,
                                                 Point currentPos,
                                                 Foothold foothold,
                                                 int desiredDir,
                                                 GroundTravelState state,
                                                 BotMovementProfile profile) {
        if (map == null || currentPos == null || foothold == null || state == null) {
            return new GroundStepResult(currentPos, foothold, state, 0, 0, true);
        }

        GroundTravelState displaced = applyGroundDisplacement(map, foothold, desiredDir, state, profile);
        int newX = (int) Math.round(displaced.physX());
        int stepX = newX - currentPos.x;
        GroundStepPreview preview = previewGroundStep(map, currentPos, foothold, newX);
        if (preview == null) {
            return new GroundStepResult(currentPos, foothold, state, 0, 0, true);
        }

        if (preview.blocked()) {
            return new GroundStepResult(currentPos, foothold, initialGroundTravelState(currentPos), 0, 0, false);
        }

        if (preview.lostGround()) {
            return new GroundStepResult(new Point(newX, preview.baseY()), foothold, displaced,
                    stepX, velocityFromDeltaX(displaced.physX() - currentPos.x), true);
        }

        return new GroundStepResult(preview.point(), preview.foothold() != null ? preview.foothold() : foothold, displaced,
                stepX, velocityFromDeltaX(displaced.physX() - currentPos.x), false);
    }

    static WalkOffLanding simulateWalkOffLanding(MapleMap map,
                                                 Point from,
                                                 int desiredDir,
                                                 BotMovementProfile profile) {
        return simulateWalkOffLanding(map, from, desiredDir, initialGroundTravelState(from), profile);
    }

    static WalkOffLanding simulateWalkOffLanding(MapleMap map,
                                                 Point from,
                                                 int desiredDir,
                                                 GroundTravelState initialState,
                                                 BotMovementProfile profile) {
        if (map == null || from == null || desiredDir == 0 || initialState == null) {
            return null;
        }

        Foothold foothold = findGroundFoothold(map, from);
        if (foothold == null) {
            return null;
        }

        Point cursor = new Point(from);
        Foothold currentFoothold = foothold;
        GroundTravelState state = initialState;
        int elapsedMs = 0;
        for (int i = 0; i < 256; i++) {
            GroundStepResult step = simulateGroundMotion(map, cursor, currentFoothold, desiredDir, state, profile);
            if (step.lostGround()) {
                if (step.stepX() == 0) {
                    return null;
                }
                JumpLanding landing = simulateFallLanding(map, step.point(), step.stepX());
                if (landing == null) {
                    return null;
                }
                return new WalkOffLanding(new Point(step.point()), step.stepX(), landing,
                        elapsedMs + estimateFallLandingTimeMs(map, step.point(), step.stepX()));
            }

            cursor = step.point();
            currentFoothold = step.foothold();
            state = step.state();
            elapsedMs += cfg.TICK_MS;
        }
        return null;
    }

    static PostLandingJump simulatePostLandingGroundTicks(MapleMap map,
                                                          JumpLanding landing,
                                                          int desiredDir,
                                                          BotMovementProfile profile,
                                                          int ticks) {
        if (landing == null) {
            return null;
        }
        if (map == null || landing.point() == null || landing.foothold() == null || ticks <= 0) {
            return new PostLandingJump(landing,
                    landing.point() == null ? null : new Point(landing.point()),
                    landing.foothold(), false);
        }

        double landingHSpeed = landingGroundHSpeed(map, landing.foothold(),
                landing.incomingDeltaX(), landing.incomingDeltaY(), profile);
        GroundTravelState state = new GroundTravelState(landing.point().x, landingHSpeed, 0.0);
        Point cursor = new Point(landing.point());
        Foothold currentFoothold = landing.foothold();
        for (int i = 0; i < ticks; i++) {
            GroundStepResult step = simulateGroundMotion(map, cursor, currentFoothold, desiredDir, state, profile);
            if (step.lostGround()) {
                return new PostLandingJump(landing, step.point(), step.foothold(), true);
            }
            cursor = step.point();
            currentFoothold = step.foothold();
            state = step.state();
        }
        return new PostLandingJump(landing, cursor, currentFoothold, false);
    }

    private static Point roundedAirPosition(BotEntry entry) {
        return new Point((int) Math.round(entry.physX), (int) Math.round(entry.physY));
    }

    /**
     * Intent-driven swim integrator. Mirrors wasm Physics::move_swimming, but
     * the only inputs are discrete intents on {@link BotEntry}:
     *   swimMoveDir       — -1/0/+1 horizontal steer
     *   swimVerticalHold  — -1 (UP slow sink) / 0 (free sink) / +1 (DOWN fast sink)
     *   swimJumpRequested — one-shot upward burst (consumed here)
     *
     * Movement layer never writes velY/hspeed directly — the engine owns physics.
     * On contact with a foothold floor the bot transitions out of swim mode
     * (entry.inAir = false) so the next tick routes through tickGrounded and the
     * bot walks normally on the platform. This matches the real client behavior
     * where SWIMMING physics applies only while airborne underwater.
     */
    static void applySwimMotion(BotEntry entry) {
        Character bot = entry.bot;
        MapleMap map = bot.getMap();
        Point pos = bot.getPosition();
        double t = tickS();

        // First tick after entering swim mode: rebase the integrator on the bot's
        // authoritative position and discard any committed-airborne state from
        // the launch (airVelX, fixedAirArc) — swim physics owns motion now.
        if (!entry.swimming) {
            entry.physX = pos.x;
            entry.physY = pos.y;
            entry.airVelX = 0;
            entry.airSteerVelX = 0.0;
            entry.fixedAirArc = false;
            entry.downJumpPending = false;
            entry.downJumpGracePeriodMS = 0L;
            // Preserve velY/hspeed from launch — a jump-off-platform should
            // still arc upward under swim physics (matches wasm: NORMAL kick
            // immediately followed by SWIMMING integration).
        } else if (Math.abs(entry.physX - pos.x) > 2 || Math.abs(entry.physY - pos.y) > 2) {
            // External teleport (mob-touch knockback, !warp, position correction)
            // moved the authoritative position out from under the integrator.
            // Without rebase, the next sweep starts from a stale physX/physY and
            // can advance through a foothold without registering the floor —
            // bot tunnels straight through the platform. Resync to the truth.
            entry.physX = pos.x;
            entry.physY = pos.y;
        }

        double vx = entry.hspeed;
        double vy = entry.velY;

        // --- Vertical control ---
        if (entry.swimJumpRequested) {
            // Swim-jump impulse scales with character SPEED stat, not jump stat.
            // Per JustJump@CVecCtrl @ 0x9b1d3d swim branch:
            //   swim_jump = stat[+0x6c] × physcfg[+0x48] × speedScale × 5.0
            // stat[+0x6c] is the speed-related field (cf. walk path stat[+0x84]
            // for jump). At base 100 stat both are 1.0; jumpMultiplier would be
            // wrong here for any speed-buffed/jump-buffed character.
            float burst = cfg.SWIM_JUMP_BURST_PXS;
            if (entry.movementProfile != null) {
                burst *= (float) entry.movementProfile.speedMultiplier();
            }
            vy = -burst;
            entry.swimJumpRequested = false;
        }

        // --- Horizontal control ---
        if (entry.swimMoveDir != 0) {
            double accelStep = cfg.SWIM_ACCEL_PXS2 * t * Integer.signum(entry.swimMoveDir);
            vx += accelStep;
        }

        // Symmetric water drag.
        double dragRetention = Math.max(0.0, 1.0 - cfg.SWIM_FRICTION_HZ * t);
        vx *= dragRetention;
        vy *= dragRetention;

        // Apply gravity (always full strength).
        vy += cfg.SWIM_GRAVITY_PXS2 * t;

        // Continuous UP/DOWN thrust matches the v83 vForce model: pressing UP
        // doesn't just lower the sink cap, it continuously accelerates the
        // character upward. Without this the bot's burst trajectory falls
        // far short of a real player's "burst + hold UP" reach.
        if (entry.swimVerticalHold < 0) {
            vy -= cfg.SWIM_UP_THRUST_PXS2 * t;
        } else if (entry.swimVerticalHold > 0) {
            vy += cfg.SWIM_DOWN_THRUST_PXS2 * t;
        }

        // Horizontal cap.
        vx = Math.max(-cfg.SWIM_MAX_SPEED_PXS, Math.min(cfg.SWIM_MAX_SPEED_PXS, vx));
        if (entry.swimMoveDir != 0) {
            double cap = cfg.SWIM_VEL_PXS;
            if (vx >  cap && entry.swimMoveDir > 0) vx =  cap;
            if (vx < -cap && entry.swimMoveDir < 0) vx = -cap;
        }
        // Vertical sink cap — discrete intent picks the terminal velocity.
        // Upward velocity (vy < 0) is unaffected so jump bursts still arc up.
        double sinkCap = switch (Integer.signum(entry.swimVerticalHold)) {
            case -1 -> cfg.SWIM_UP_MAX_SINK_PXS;
            case  1 -> cfg.SWIM_DOWN_MAX_SPEED_PXS;
            default -> cfg.SWIM_FREE_MAX_SINK_PXS;
        };
        vy = Math.max(-cfg.SWIM_MAX_SPEED_PXS, Math.min(sinkCap, vy));

        double nextX = entry.physX + vx * t;
        double nextY = entry.physY + vy * t;

        // Use the same sweep-based collision resolution as airborne physics:
        // resolveAirCollision handles wall segments and scans every pixel along
        // the horizontal span for floor crossings, preventing tunnelling through
        // slopes and thin platforms.
        boolean landed = false;
        Foothold landingFoothold = null;
        double landingDeltaX = 0.0;
        double landingDeltaY = 0.0;
        Point prevPt = new Point((int) Math.round(entry.physX), (int) Math.round(entry.physY));
        {
            Point nextPt = new Point((int) Math.round(nextX), (int) Math.round(nextY));
            AirCollision collision = resolveAirCollision(map, prevPt, nextPt);
            if (collision.type() == AirCollisionType.LAND) {
                nextX = collision.point().x;
                nextY = collision.point().y;
                vy = 0.0;
                landed = true;
                landingFoothold = collision.foothold();
                landingDeltaX = nextX - prevPt.x;
                landingDeltaY = nextY - prevPt.y;
            } else if (collision.type() == AirCollisionType.WALL) {
                nextX = collision.point().x;
                vx = 0.0;
            }
        }

        // Facing follows horizontal intent (or current vx if coasting).
        if (entry.swimMoveDir > 0) entry.facingDir = 1;
        else if (entry.swimMoveDir < 0) entry.facingDir = -1;

        if (landed) {
            // Hand off to grounded physics. Must go through landOnGround so
            // landingGroundHSpeed converts swim-scale vx (px/s, up to
            // SWIM_MAX_SPEED_PXS=250) into ground hspeed units. Skipping the
            // conversion caused a 500+ px jerk forward on the first grounded
            // tick because raw vx was treated as ground hspeed and then
            // multiplied by stepsPerTick (50/8 = 6.25).
            entry.swimming = false;
            landOnGround(entry, bot,
                    new Point((int) Math.round(nextX), (int) Math.round(nextY)),
                    landingFoothold, landingDeltaX, landingDeltaY);
            return;
        }

        entry.hspeed = vx;
        entry.velY = (float) vy;
        entry.physX = nextX;
        entry.physY = nextY;
        entry.crouching = false;
        entry.movementVelX = (int) Math.round(vx);
        entry.movementVelY = (int) Math.round(vy);
        entry.swimming = true;
        entry.inAir = true;

        bot.setPosition(new Point((int) Math.round(nextX), (int) Math.round(nextY)));
    }

    private static double clampMagnitude(double value, double maxAbs) {
        if (value > maxAbs) {
            return maxAbs;
        }
        if (value < -maxAbs) {
            return -maxAbs;
        }
        return value;
    }

    /** Apply air steering acceleration based on discrete steer direction. */
    private static void applyAirSteering(BotEntry entry, int steerDir) {
        if (steerDir == 0) return;
        double accel = steerDir > 0 ? cfg.AIR_STEER_ACCEL : -cfg.AIR_STEER_ACCEL;
        entry.airSteerVelX = Math.clamp(entry.airSteerVelX + accel, -cfg.AIR_STEER_MAX, cfg.AIR_STEER_MAX);
        // Client jump stance follows the held steering direction, not the preserved horizontal
        // launch momentum. Updating facing here makes airborne debug output line up with what the
        // client is visually trying to do, even before the net X velocity changes sign.
        entry.facingDir = steerDir > 0 ? 1 : -1;
    }

    private static Point advanceAirbornePosition(BotEntry entry, Character bot) {
        entry.physX += entry.airVelX + entry.airSteerVelX;
        float gravity = gravityPerTick();
        entry.physY += entry.velY + 0.5f * gravity;
        entry.velY = Math.min(entry.velY + gravity, maxFallPerTick());
        if (entry.physY < entry.fallPeakPhysY) {
            entry.fallPeakPhysY = entry.physY;
        }

        return roundedAirPosition(entry);
    }

    private static void applyAirbornePosition(BotEntry entry, Character bot, Point position) {
        bot.setPosition(position);
        entry.inAir = true;
        entry.climbing = false;
        entry.climbRope = null;
        entry.crouching = false;
        // Preserve facing set by air steering (moveDir intent) - setMovementVelocity would
        // overwrite it based on momentum velocity, which is wrong for airborne steering.
        int facingDir = entry.facingDir;
        setMovementVelocity(entry, velocityFromDeltaX(entry.airVelX), velocityFromAirStep(entry.velY));
        entry.facingDir = facingDir;
        syncCharacterState(entry);
    }

  /**
     * Intent-driven airborne integrator. Reads {@link BotEntry#moveDir} for horizontal
     * air steering (-1/0/+1). Movement layer gates steering for committed nav trajectories
     * (fixedAirArc, JUMP/DROP edges) by setting moveDir=0.
     *
     * One physics step: apply air steering from intent, advance position, resolve collision, apply result.
     * All collision outcome methods are private — movement must not call them directly.
     */
    static AirborneStepResult stepAirborne(BotEntry entry, Character bot) {
        // Apply air steering from intent. Movement sets moveDir=0 for committed trajectories.
        if (entry.moveDir != 0) {
            applyAirSteering(entry, entry.moveDir);
        }

        Point previousPos = roundedAirPosition(entry);
        Point nextPos = advanceAirbornePosition(entry, bot);
        AirCollision collision = resolveAirCollision(bot.getMap(), previousPos, nextPos);
        if (collision.type() == AirCollisionType.WALL) {
            collideWithAirWall(entry, bot, collision.point());
            return AirborneStepResult.WALL;
        }
        if (collision.type() == AirCollisionType.CEILING) {
            collideWithAirCeiling(entry, bot, collision.point());
            return AirborneStepResult.CEILING;
        }
        if (collision.type() == AirCollisionType.LAND && (canLand(entry) || forbidFallDownLanding(collision))) {
            landOnGround(entry, bot, collision.point(), collision.foothold(),
                    nextPos.x - previousPos.x, nextPos.y - previousPos.y);
            return AirborneStepResult.LANDED;
        }
        applyAirbornePosition(entry, bot, nextPos);
        return AirborneStepResult.CONTINUE;
    }

    private static void collideWithAirWall(BotEntry entry, Character bot, Point collisionPoint) {
        entry.airVelX = 0;
        entry.airSteerVelX = 0.0;
        entry.fixedAirArc = false;
        entry.physX = collisionPoint.x;
        entry.physY = collisionPoint.y;
        bot.setPosition(collisionPoint);
        entry.inAir = true;
        entry.climbing = false;
        entry.climbRope = null;
        entry.crouching = false;
        setMovementVelocity(entry, 0, velocityFromAirStep(entry.velY));
        syncCharacterState(entry);
    }

    private static void collideWithAirCeiling(BotEntry entry, Character bot, Point collisionPoint) {
        entry.velY = 0f;
        entry.fixedAirArc = false;
        entry.physX = collisionPoint.x;
        entry.physY = collisionPoint.y;
        bot.setPosition(collisionPoint);
        entry.inAir = true;
        entry.climbing = false;
        entry.climbRope = null;
        entry.crouching = false;
        setMovementVelocity(entry, velocityFromDeltaX(entry.airVelX), 0);
        syncCharacterState(entry);
    }

    static MovementSnapshot movementSnapshot(BotEntry entry) {
        int stance = resolveStance(entry);
        if (entry.bot != null && entry.bot.getStance() != stance) {
            entry.bot.setStance(stance);
        }
        // Broadcast-only alert substitution. The server-side Character.stance above keeps the
        // logical stance (STAND/WALK/etc.); only the wire byte gets ALERT when the alert timer
        // is active. Mirrors maplestory-wasm CharLook.cpp substituting Stance::ALERT for STAND1/2
        // while TimedBool alerted is set_for(5000).
        return new MovementSnapshot(entry.movementVelX, entry.movementVelY, broadcastStance(entry, stance));
    }

    private static int broadcastStance(BotEntry entry, int baseStance) {
        if (System.currentTimeMillis() >= entry.alertedUntilMs) {
            return baseStance;
        }
        if (baseStance == CharacterStance.STAND_RIGHT_STANCE) {
            return CharacterStance.ALERT_RIGHT_STANCE;
        }
        if (baseStance == CharacterStance.STAND_LEFT_STANCE) {
            return CharacterStance.ALERT_LEFT_STANCE;
        }
        return baseStance;
    }

    static int resolveStance(BotEntry entry) {
        Character bot = entry.bot;
        if (bot != null && bot.getHp() <= 0) {
            return resolveDeadStance(entry);
        }
        if (entry.climbing) {
            return entry.climbRope != null && entry.climbRope.isLadder()
                    ? CharacterStance.LADDER_STANCE
                    : CharacterStance.ROPE_STANCE;
        }
        if (entry.swimming) {
            return entry.facingDir >= 0 ? CharacterStance.SWIM_RIGHT_STANCE : CharacterStance.SWIM_LEFT_STANCE;
        }
        if (entry.crouching) {
            return entry.facingDir >= 0 ? CharacterStance.PRONE_RIGHT_STANCE : CharacterStance.PRONE_LEFT_STANCE;
        }
        if (entry.inAir) {
            return entry.facingDir >= 0 ? CharacterStance.JUMP_RIGHT_STANCE : CharacterStance.JUMP_LEFT_STANCE;
        }
        if (entry.moveDir > 0) {
            return CharacterStance.WALK_RIGHT_STANCE;
        }
        if (entry.moveDir < 0) {
            return CharacterStance.WALK_LEFT_STANCE;
        }
        return resolveIdleGroundStance(entry);
    }

    static int resolveIdleGroundStance(BotEntry entry) {
        return entry.facingDir >= 0 ? CharacterStance.STAND_RIGHT_STANCE : CharacterStance.STAND_LEFT_STANCE;
    }

    static int resolveDeadStance(BotEntry entry) {
        return entry.facingDir >= 0 ? CharacterStance.DEAD_RIGHT_STANCE : CharacterStance.DEAD_LEFT_STANCE;
    }

    static boolean isStandingStance(int stance) {
        return CharacterStance.isStanding(stance);
    }

    static void syncCharacterState(BotEntry entry) {
        Character bot = entry.bot;
        if (bot == null) {
            return;
        }
        bot.setStance(resolveStance(entry));
    }

    static float calculateMaxJumpHeight() {
        return calculateMaxJumpHeight(BotMovementProfile.base());
    }

    static float calculateMaxJumpHeight(BotMovementProfile profile) {
        float jumpForce = jumpForcePerTick(profile);
        return jumpForce * jumpForce / (2 * gravityPerTick());
    }

    static int maxJumpHorizontalTravel(MapleMap map) {
        return maxJumpHorizontalTravel(map, BotMovementProfile.base());
    }

    static int maxJumpHorizontalTravel(MapleMap map, BotMovementProfile profile) {
        return maxHorizontalTravel(map, profile, jumpForcePerTick(profile));
    }

    static int maxRopeJumpHorizontalTravel(MapleMap map) {
        return maxRopeJumpHorizontalTravel(map, BotMovementProfile.base());
    }

    static int maxRopeJumpHorizontalTravel(MapleMap map, BotMovementProfile profile) {
        return maxHorizontalTravel(map, profile, ropeJumpForcePerTick(profile));
    }

    static int maxRopeGrabSimulationHorizontalTravel(MapleMap map, BotMovementProfile profile) {
        int maxTicks = Math.max(1, 1500 / cfg.TICK_MS);
        return walkStep(map, profile) * maxTicks;
    }

    static Point simulateRopeJumpGrab(MapleMap map, Point from, int stepX, Rope targetRope) {
        return simulateRopeJumpGrab(map, from, stepX, targetRope, BotMovementProfile.base());
    }

    static Point simulateRopeJumpGrab(MapleMap map, Point from, int stepX, Rope targetRope, BotMovementProfile profile) {
        return simulateRopeGrab(map, from, -ropeJumpForcePerTick(profile), stepX, targetRope, 0L);
    }

    static Point simulateGroundJumpRopeGrab(MapleMap map, Point from, int stepX, Rope targetRope) {
        return simulateGroundJumpRopeGrab(map, from, stepX, targetRope, BotMovementProfile.base());
    }

    static Point simulateGroundJumpRopeGrab(MapleMap map, Point from, int stepX, Rope targetRope, BotMovementProfile profile) {
        return simulateRopeGrab(map, from, -jumpForcePerTick(profile), stepX, targetRope, 0L);
    }

    static Point simulateDownJumpRopeGrab(MapleMap map, Point from, Rope targetRope) {
        return simulateRopeGrab(map, from, -downJumpForcePerTick(), 0, targetRope, cfg.DOWN_JUMP_GRACE_MS);
    }

    static boolean canReachRopeFromGround(MapleMap map, Point from, Rope rope) {
        return canReachRopeFromGround(map, from, rope, BotMovementProfile.base());
    }

    static boolean canReachRopeFromGround(MapleMap map, Point from, Rope rope, BotMovementProfile profile) {
        int dx = Math.abs(rope.x() - from.x);
        if (dx <= cfg.ROPE_GRAB_X && from.y >= firstClimbableY(rope) && from.y <= rope.bottomY()) {
            return true;
        }
        if (rope.topY() >= from.y) {
            return false;
        }

        int jumpReach = (int) Math.ceil(calculateMaxJumpHeight(profile));
        return rope.bottomY() >= from.y - jumpReach
                && dx <= maxJumpHorizontalTravel(map, profile);
    }

    static boolean canStartDownJump(MapleMap map, Point from) {
        Foothold foothold = findGroundFoothold(map, from);
        return foothold != null && !foothold.isForbidFallDown();
    }

    /** forbidFallDown footholds are never pass-through — they stay solid even inside a
     *  down-jump grace window (matches the client; the grace only skips normal platforms). */
    private static boolean forbidFallDownLanding(AirCollision collision) {
        return collision.foothold() != null && collision.foothold().isForbidFallDown();
    }

    static JumpLanding simulateJumpLanding(MapleMap map, Point from, int stepX) {
        return simulateJumpLanding(map, from, stepX, BotMovementProfile.base());
    }

    static JumpLanding simulateJumpLanding(MapleMap map, Point from, int stepX, BotMovementProfile profile) {
        return simulateLanding(map, from, -jumpForcePerTick(profile), stepX, 0L);
    }

    static PostLandingJump simulateJumpLandingWithPostLandingTicks(MapleMap map,
                                                                   Point from,
                                                                   int stepX,
                                                                   BotMovementProfile profile,
                                                                   int postLandingTicks) {
        JumpLanding landing = simulateJumpLanding(map, from, stepX, profile);
        if (landing == null) {
            return null;
        }
        return simulatePostLandingGroundTicks(map, landing, Integer.compare(stepX, 0), profile, postLandingTicks);
    }

    // The client only allows a down-jump when a landing foothold exists within a bounded probe
    // below the player — most "can't fall here" platforms carry NO forbidFallDown flag (e.g.
    // Orbis tower rims, 860px above the next floor).
    // CONFIRMED against Angel.idb: CUserLocal::FallDown @ 0x0094c4f8 is the gate. At +0x160
    // (0x0094c658) it does `add [probeY], 0x12c` (= player Y + 300) then GetFootholdUnderneath/
    // GetFootholdAbove (CWvsPhysicalSpace2D @ 0xa45585 / 0xa4549d) bracketing that point, and
    // bails if the only foothold found is the current one (±5px). So the real probe distance is
    // exactly 300px. (NB: the 0x1e/×0.8 probe near 0x0094e6fd belongs to TryDoingTeleport, not
    // the tiny TryDoingFallDown @ 0x0094e692 which only sets the fall-request flag.)
    static final int DOWN_JUMP_MAX_DROP_PX = 300;

    static JumpLanding simulateDownJumpLanding(MapleMap map, Point from) {
        if (!canStartDownJump(map, from)) {
            return null;
        }
        JumpLanding landing = simulateLanding(map, from, -downJumpForcePerTick(), 0, cfg.DOWN_JUMP_GRACE_MS);
        if (landing == null || landing.point().y - from.y > DOWN_JUMP_MAX_DROP_PX) {
            return null;
        }
        return landing;
    }

    static JumpLanding simulateFallLanding(MapleMap map, Point from, int stepX) {
        return simulateLanding(map, from, 0f, stepX, 0L);
    }

    static JumpLanding simulateRopeJumpLanding(MapleMap map, Point from, int stepX) {
        return simulateRopeJumpLanding(map, from, stepX, BotMovementProfile.base());
    }

    static JumpLanding simulateRopeJumpLanding(MapleMap map, Point from, int stepX, BotMovementProfile profile) {
        return simulateLanding(map, from, -ropeJumpForcePerTick(profile), stepX, 0L);
    }

    static int estimateJumpLandingTimeMs(MapleMap map, Point from, int stepX) {
        return estimateJumpLandingTimeMs(map, from, stepX, BotMovementProfile.base());
    }

    static int estimateJumpLandingTimeMs(MapleMap map, Point from, int stepX, BotMovementProfile profile) {
        return estimateLandingTimeMs(map, from, -jumpForcePerTick(profile), stepX, 0L);
    }

    static int estimateDownJumpLandingTimeMs(MapleMap map, Point from) {
        return estimateLandingTimeMs(map, from, -downJumpForcePerTick(), 0, cfg.DOWN_JUMP_GRACE_MS);
    }

    static int estimateFallLandingTimeMs(MapleMap map, Point from, int stepX) {
        return estimateLandingTimeMs(map, from, 0f, stepX, 0L);
    }

    static int estimateRopeJumpLandingTimeMs(MapleMap map, Point from, int stepX) {
        return estimateRopeJumpLandingTimeMs(map, from, stepX, BotMovementProfile.base());
    }

    static int estimateRopeJumpLandingTimeMs(MapleMap map, Point from, int stepX, BotMovementProfile profile) {
        return estimateLandingTimeMs(map, from, -ropeJumpForcePerTick(profile), stepX, 0L);
    }

    static int estimateGroundJumpRopeGrabTimeMs(MapleMap map, Point from, int stepX, Rope targetRope) {
        return estimateGroundJumpRopeGrabTimeMs(map, from, stepX, targetRope, BotMovementProfile.base());
    }

    static int estimateGroundJumpRopeGrabTimeMs(MapleMap map, Point from, int stepX, Rope targetRope, BotMovementProfile profile) {
        return estimateRopeGrabTimeMs(map, from, -jumpForcePerTick(profile), stepX, targetRope, 0L);
    }

    static int estimateDownJumpRopeGrabTimeMs(MapleMap map, Point from, Rope targetRope) {
        return estimateRopeGrabTimeMs(map, from, -downJumpForcePerTick(), 0, targetRope, cfg.DOWN_JUMP_GRACE_MS);
    }

    static int estimateRopeJumpGrabTimeMs(MapleMap map, Point from, int stepX, Rope targetRope) {
        return estimateRopeJumpGrabTimeMs(map, from, stepX, targetRope, BotMovementProfile.base());
    }

    static int estimateRopeJumpGrabTimeMs(MapleMap map, Point from, int stepX, Rope targetRope, BotMovementProfile profile) {
        return estimateRopeGrabTimeMs(map, from, -ropeJumpForcePerTick(profile), stepX, targetRope, 0L);
    }

    private static AirCollision resolveAirCollision(MapleMap map, Point previousPos, Point nextPos) {
        if (map == null || map.getFootholds() == null || previousPos == null || nextPos == null) {
            return AirCollision.none();
        }
        AirCollision wall = findWallCollision(map, previousPos, nextPos);
        AirCollision ceiling = findCeilingCollision(map, previousPos, nextPos);
        AirCollision landing = findGroundCollision(map, previousPos, nextPos);
        AirCollision best = AirCollision.none();
        if (wall.type() != AirCollisionType.NONE) {
            best = wall;
        }
        if (ceiling.type() != AirCollisionType.NONE && ceiling.progress() < best.progress()) {
            best = ceiling;
        }
        if (landing.type() != AirCollisionType.NONE && landing.progress() < best.progress()) {
            best = landing;
        }
        return best;
    }

    private static void launchAirborne(BotEntry entry,
                                       Character bot,
                                       Point position,
                                       float initialVelY,
                                       int airVelX,
                                       boolean climbUpIntent) {
        entry.climbing = false;
        entry.climbRope = null;
        entry.inAir = true;
        entry.crouching = false;
        entry.physX = position.x;
        entry.physY = position.y;
        entry.velY = initialVelY;
        stopGroundMotion(entry);
        entry.climbUpIntent = climbUpIntent;
        clearRopeEntryIntent(entry);
        entry.airVelX = airVelX;
        entry.airSteerVelX = 0.0;
        entry.fixedAirArc = false;
        entry.downJumpPending = false;
        // Clear ground movement intent when going airborne - unified moveDir serves both
        // ground and air, so ground walk direction must not bleed into air steering.
        // Movement manager will set moveDir for air steering if shouldApplyAirSteering allows.
        entry.moveDir = 0;
        setMovementVelocity(entry, velocityFromDeltaX(airVelX), velocityFromAirStep(initialVelY));
        syncCharacterState(entry);
    }

    private static boolean resolveClimbBoundary(BotEntry entry, Character bot, Rope rope, int candidateY) {
        if (candidateY <= rope.topY()) {
            Point landing = findTopLandingPoint(bot, rope, candidateY);
            if (landing != null) {
                landOnGround(entry, bot, landing);
            } else {
                // Top of a rope always connects to a foothold in valid map data.
                // If none is found, clamp to topY and hold rather than falling — the bot will
                // recover on re-path. Falling here would cause the oscillation bug where the bot
                // climbs to the top, falls, re-grabs the rope, and loops indefinitely.
                setClimbPosition(entry, bot, rope, firstClimbableY(rope));
            }
            return true;
        }
        if (candidateY > rope.bottomY()) {
            beginFall(entry, bot, 0);
            return true;
        }
        return false;
    }

    private static Point findTopLandingPoint(Character bot, Rope rope, int candidateY) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return null;
        }

        int probeY = Math.min(candidateY, rope.topY()) - 3;
        Point ground = pointBelowIndexed(map, new Point(rope.x(), probeY));
        if (ground == null) {
            return null;
        }

        return ground.y <= rope.topY() + climbStepPerTick() + 2 ? ground : null;
    }

    static int firstClimbableY(Rope rope) {
        return Math.min(rope.bottomY(), rope.topY() + 1);
    }

    private static void setClimbPosition(BotEntry entry, Character bot, Rope rope, int y) {
        Point position = new Point(rope.x(), y);
        bot.setPosition(position);
        entry.climbing = true;
        entry.climbRope = rope;
        entry.inAir = false;
        entry.crouching = false;
        entry.climbUpIntent = false;
        entry.velY = 0f;
        entry.airVelX = 0;
        entry.airSteerVelX = 0.0;
        entry.fixedAirArc = false;
        entry.physX = position.x;
        entry.physY = position.y;
        clearRopeEntryIntent(entry);
        entry.downJumpPending = false;
        stopGroundMotion(entry);
        setMovementVelocity(entry, 0, 0);
        syncCharacterState(entry);
    }

    private static void clearRopeEntryIntent(BotEntry entry) {
        entry.ropeEntryPending = false;
        entry.ropeEntryRope = null;
        entry.ropeEntryY = 0;
    }

    private static void clearMovementState(BotEntry entry, Point position) {
        entry.inAir = false;
        entry.climbing = false;
        entry.climbRope = null;
        entry.crouching = false;
        entry.velY = 0f;
        entry.hspeed = 0.0;
        entry.physX = position.x;
        entry.physY = position.y;
        entry.groundPhysicsCarryMs = 0.0;
        entry.airVelX = 0;
        entry.airSteerVelX = 0.0;
        entry.fixedAirArc = false;
        entry.wasMovingX = false;
        entry.moveDir = 0;
        entry.climbUpIntent = false;
        entry.blockedRopeGrab = null;
        entry.ropeGrabCooldownMs = 0;
        entry.downJumpPending = false;
        entry.downJumpGracePeriodMS = 0L;
        clearRopeEntryIntent(entry);
        setMovementVelocity(entry, 0, 0);
    }

    private static void setMovementVelocity(BotEntry entry, int velX, int velY) {
        entry.movementVelX = velX;
        entry.movementVelY = velY;
        if (velX != 0) {
            entry.facingDir = velX > 0 ? 1 : -1;
        }
    }

    private static int velocityFromAirStep(float airVelPerTick) {
        return Math.round(airVelPerTick * (1000f / cfg.TICK_MS));
    }

    private static GroundTravelState applyGroundDisplacement(MapleMap map,
                                                             Foothold foothold,
                                                             int desiredDir,
                                                             GroundTravelState state,
                                                             BotMovementProfile profile) {
        GroundStepCounter counter = groundPhysicsSteps(state.carryMs(), map);
        if (counter.steps() == 0) {
            return state;
        }

        double physX = state.physX();
        double hspeed = state.hspeed();
        double slipScale = mapGroundSlipScale(map);
        for (int i = 0; i < counter.steps(); i++) {
            hspeed = applyGroundPhysicsStep(hspeed, foothold, desiredDir, profile, slipScale);
            physX += hspeed;
        }
        return new GroundTravelState(physX, hspeed, counter.carryMs());
    }

    private record GroundStepCounter(int steps, double carryMs) {
    }

    private static GroundStepCounter groundPhysicsSteps(double carryMs, MapleMap map) {
        double nextCarryMs = carryMs + cfg.TICK_MS;
        int steps = (int) (nextCarryMs / CLIENT_GROUND_STEP_MS);
        nextCarryMs -= steps * CLIENT_GROUND_STEP_MS;
        return new GroundStepCounter(steps, nextCarryMs);
    }

    private static double applyGroundPhysicsStep(double hspeed, Foothold foothold, int desiredDir,
                                                 BotMovementProfile profile, double slipScale) {
        double hforce = desiredDir * maxHForcePerClientStep(profile);
        if (hforce == 0.0 && Math.abs(hspeed) < 0.1) {
            return 0.0;
        }

        double inertia = hspeed / cfg.GROUNDSLIP;
        double slope = clampedSlope(foothold);
        double drag = (cfg.FRICTION + cfg.SLOPEFACTOR * (1.0 + slope * -inertia)) * inertia;
        // Slippery fields scale force and friction together: terminal speed is unchanged
        // (hforce == drag there), only the approach to it slows — slow starts, long slides.
        return hspeed + (hforce - drag) * slipScale;
    }

    private static double clampedSlope(Foothold foothold) {
        if (foothold == null) {
            return 0.0;
        }
        return Math.clamp(foothold.slope(), -0.5, 0.5);
    }

    // True if a step between two endpoint positions is physically walkable (same criteria as
    // graph walk-edge generation, so physics and graph agree on which transitions are valid).
    static boolean isWalkableEndpointStep(int dx, int dy) {
        return dx <= WALK_GAP_PX
                && dy <= cfg.MAX_SNAP_DROP
                && dy >= -cfg.MAX_SLOPE_UP;
    }

    /**
     * WZ {@code info/fs} is the field's SLIPPERINESS factor (El Nath snow = 0.2), not a speed
     * multiplier: the client scales walk force AND friction by it, so top speed is unchanged
     * while acceleration and braking take 1/fs as long — slow starts, long slides. (It was
     * previously misread as a ground-speed scale, which made El Nath bots crawl at 20% speed.)
     */
    private static double mapGroundSlipScale(MapleMap map) {
        float fs = map != null ? map.getFootholdSpeed() : 0.0f;
        return fs > 0.0f && fs < 1.0f ? fs : 1.0;
    }

    private static double maxHForcePerClientStep(BotMovementProfile profile) {
        return profileOrBase(profile).hForcePxs() * CLIENT_GROUND_STEP_S;
    }

    private static double maxHSpeedPerClientStep(BotMovementProfile profile) {
        return maxHForcePerClientStep(profile) * cfg.GROUNDSLIP / (cfg.FRICTION + cfg.SLOPEFACTOR);
    }

    private static int maxHorizontalTravel(MapleMap map, BotMovementProfile profile, float launchSpeedPerTick) {
        int airtimeTicks = Math.max(1, (int) Math.ceil((2 * launchSpeedPerTick) / gravityPerTick()));
        return walkStep(map, profile) * airtimeTicks;
    }

    private static AirCollision findGroundCollision(MapleMap map, Point previousPos, Point nextPos) {
        if (nextPos.y < previousPos.y) {
            return AirCollision.none();
        }

        int startX = previousPos.x;
        int endX = nextPos.x;
        int dir = Integer.compare(endX, startX);
        if (dir == 0) {
            return landingAtX(map, previousPos, nextPos, endX, 1.0);
        }

        int steps = Math.abs(endX - startX);
        for (int i = 0; i <= steps; i++) {
            int x = startX + dir * i;
            double progress = i / (double) steps;
            AirCollision landing = landingAtX(map, previousPos, nextPos, x, progress);
            if (landing.type == AirCollisionType.LAND) {
                return landing;
            }
        }
        return AirCollision.none();
    }

    private static AirCollision findCeilingCollision(MapleMap map, Point previousPos, Point nextPos) {
        if (map == null || map.getFootholds() == null || nextPos.y >= previousPos.y) {
            return AirCollision.none();
        }

        AirCollision best = AirCollision.none();
        for (Foothold foothold : collisionIndex(map).collidableFromBelow()) {
            AirCollision collision = ceilingCollision(foothold, previousPos, nextPos);
            if (collision.type() == AirCollisionType.CEILING && collision.progress() < best.progress()) {
                best = collision;
            }
        }
        return best;
    }

    private static AirCollision findWallCollision(MapleMap map, Point previousPos, Point nextPos) {
        return findWallCollision(map, previousPos, nextPos, false);
    }

    private static AirCollision findGroundWallCollision(MapleMap map, Point previousPos, Point nextPos) {
        return findWallCollision(map, previousPos, nextPos, true);
    }

    private static AirCollision findWallCollision(MapleMap map,
                                                  Point previousPos,
                                                  Point nextPos,
                                                  boolean allowWalkableGroundEndpoint) {
        if (map == null || map.getFootholds() == null) {
            return AirCollision.none();
        }
        if (previousPos.x == nextPos.x) {
            return AirCollision.none();
        }

        AirCollision best = mapSideBoundaryCollision(map, previousPos, nextPos);
        for (Foothold foothold : collisionIndex(map).collidableWalls()) {
            AirCollision collision = wallCollision(foothold, previousPos, nextPos, allowWalkableGroundEndpoint);
            if (collision.type() == AirCollisionType.WALL && collision.progress() < best.progress()) {
                best = collision;
            }
        }
        return best;
    }

    private static AirCollision mapSideBoundaryCollision(MapleMap map, Point previousPos, Point nextPos) {
        Rectangle area = map.getMapArea();
        if (area == null || area.width <= 0 || area.height <= 0 || previousPos.x == nextPos.x) {
            return AirCollision.none();
        }

        int dir = Integer.compare(nextPos.x, previousPos.x);
        int boundaryX = dir > 0 ? effectiveRightBoundaryX(map, area) : effectiveLeftBoundaryX(map, area);
        if (dir > 0 && (previousPos.x > boundaryX || nextPos.x <= boundaryX)) {
            return AirCollision.none();
        }
        if (dir < 0 && (previousPos.x < boundaryX || nextPos.x >= boundaryX)) {
            return AirCollision.none();
        }

        double progress = (boundaryX - previousPos.x) / (double) (nextPos.x - previousPos.x);
        if (progress < 0.0 || progress > 1.0) {
            return AirCollision.none();
        }

        double yAtBoundary = previousPos.y + (nextPos.y - previousPos.y) * progress;
        return new AirCollision(AirCollisionType.WALL,
                new Point(boundaryX, (int) Math.round(yAtBoundary)),
                null,
                progress);
    }

    private static int effectiveLeftBoundaryX(MapleMap map, Rectangle area) {
        if (!hasUsableFootholdXBounds(map)) {
            return area.x;
        }
        int footholdMinX = map.getFootholds().getMinDropX();
        // Synthetic (absurdly large) bounds: tighten inward to the foothold extent.
        if (isSyntheticMapArea(area)) {
            return footholdMinX;
        }
        // Real bounds: never let a walkable foothold pixel sit outside the side-collision
        // wall. A foothold tip that overhangs area.x (e.g. map 261020500 fh#12 left tip at
        // x=-712 while area.x=-711) would otherwise be a trap: a bot pushed onto that pixel
        // is past mapSideBoundaryCollision's guard and falls into the floor-less void.
        return Math.min(area.x, footholdMinX);
    }

    private static int effectiveRightBoundaryX(MapleMap map, Rectangle area) {
        if (!hasUsableFootholdXBounds(map)) {
            return area.x + area.width;
        }
        int footholdMaxX = map.getFootholds().getMaxDropX();
        if (isSyntheticMapArea(area)) {
            return footholdMaxX;
        }
        return Math.max(area.x + area.width, footholdMaxX);
    }

    private static boolean isSyntheticMapArea(Rectangle area) {
        return area.width >= SYNTHETIC_MAP_BOUND_SIZE && area.height >= SYNTHETIC_MAP_BOUND_SIZE;
    }

    private static boolean hasUsableFootholdXBounds(MapleMap map) {
        return map.getFootholds() != null
                && map.getFootholds().getMinDropX() < map.getFootholds().getMaxDropX();
    }

    /**
     * Pre-filtered collision footholds per foothold tree. The airborne collision checks run
     * every physics tick of every simulation; before this index they recomputed the full
     * wall/from-below classification over ALL footholds per tick whenever the nav graph
     * wasn't cached yet — which is exactly the case DURING graph building, making graphgen
     * ~50x slower than the math itself (Ellinia: 107s). Keyed by tree identity (weak), so
     * per-id synthetic test maps and instanced map copies can never poison each other.
     */
    private static final int GROUND_BUCKET_SHIFT = 6; // 64px columns
    private static final Foothold[] NO_FOOTHOLDS = new Foothold[0];

    private record FootholdCollisionIndex(java.util.List<Foothold> collidableWalls,
                                          java.util.List<Foothold> collidableFromBelow,
                                          int bucketMinX,
                                          Foothold[][] groundBuckets) {
        Foothold[] groundBucketAt(int x) {
            int b = (x - bucketMinX) >> GROUND_BUCKET_SHIFT;
            return b < 0 || b >= groundBuckets.length ? NO_FOOTHOLDS : groundBuckets[b];
        }
    }

    private static final java.util.Map<server.maps.FootholdTree, FootholdCollisionIndex> COLLISION_INDEX =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    // Sentinel for trees that can't be indexed (Mockito tree/map stubs in tests return null
    // foothold lists) — callers fall back to the original tree/map query so stubbed seams keep
    // working exactly as before.
    private static final FootholdCollisionIndex UNINDEXABLE = new FootholdCollisionIndex(
            java.util.List.of(), java.util.List.of(), 0, new Foothold[0][]);

    private static FootholdCollisionIndex collisionIndex(MapleMap map) {
        server.maps.FootholdTree tree = map != null ? map.getFootholds() : null;
        if (tree == null) {
            return UNINDEXABLE;
        }
        // The UNINDEXABLE verdict is cached too: getAllFootholds() rebuilds the whole list on
        // every call (recursive collect), so it must never run outside this computeIfAbsent.
        return COLLISION_INDEX.computeIfAbsent(tree, t -> {
            java.util.List<Foothold> all = t.getAllFootholds();
            if (all == null) {
                return UNINDEXABLE;
            }
            java.util.Map<Integer, Foothold> byId = new java.util.HashMap<>(all.size());
            for (Foothold fh : all) {
                byId.put(fh.getId(), fh);
            }
            java.util.Set<Integer> fromBelowIds = BotNavigationGraphProvider.classifyCollidableFromBelowFootholds(byId);
            java.util.List<Foothold> walls = new java.util.ArrayList<>();
            java.util.List<Foothold> ground = new java.util.ArrayList<>();
            java.util.List<Foothold> fromBelow = new java.util.ArrayList<>();
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            for (Foothold fh : all) {
                if (fh.isWall()) {
                    if (Foothold.isCollidableWall(fh, byId)) {
                        walls.add(fh);
                    }
                    continue;
                }
                ground.add(fh);
                minX = Math.min(minX, Math.min(fh.getX1(), fh.getX2()));
                maxX = Math.max(maxX, Math.max(fh.getX1(), fh.getX2()));
                if (fromBelowIds.contains(fh.getId())) {
                    fromBelow.add(fh);
                }
            }

            Foothold[][] buckets;
            if (ground.isEmpty()) {
                buckets = new Foothold[0][];
                minX = 0;
            } else {
                int bucketCount = ((maxX - minX) >> GROUND_BUCKET_SHIFT) + 1;
                java.util.List<java.util.List<Foothold>> building = new java.util.ArrayList<>(bucketCount);
                for (int i = 0; i < bucketCount; i++) {
                    building.add(null);
                }
                for (Foothold fh : ground) {
                    int lo = (Math.min(fh.getX1(), fh.getX2()) - minX) >> GROUND_BUCKET_SHIFT;
                    int hi = (Math.max(fh.getX1(), fh.getX2()) - minX) >> GROUND_BUCKET_SHIFT;
                    for (int b = lo; b <= hi; b++) {
                        java.util.List<Foothold> bucket = building.get(b);
                        if (bucket == null) {
                            bucket = new java.util.ArrayList<>(4);
                            building.set(b, bucket);
                        }
                        bucket.add(fh);
                    }
                }
                buckets = new Foothold[bucketCount][];
                for (int i = 0; i < bucketCount; i++) {
                    java.util.List<Foothold> bucket = building.get(i);
                    if (bucket == null) {
                        buckets[i] = NO_FOOTHOLDS;
                        continue;
                    }
                    Foothold[] sorted = bucket.toArray(NO_FOOTHOLDS);
                    // Stable insertion sort with the tree's own comparator. Foothold.compareTo is a
                    // NON-TRANSITIVE partial order — TimSort (List.sort/Collections.sort on 32+
                    // elements) throws "comparison method violates its general contract" on it.
                    // The tree's findBelow only ever sorted tiny per-query lists, which land in
                    // TimSort's exception-free binary-insertion path; mirror that here per bucket.
                    insertionSort(sorted);
                    buckets[i] = sorted;
                }
            }
            return new FootholdCollisionIndex(java.util.List.copyOf(walls), java.util.List.copyOf(fromBelow),
                    minX, buckets);
        });
    }

    private static void insertionSort(Foothold[] footholds) {
        for (int i = 1; i < footholds.length; i++) {
            Foothold key = footholds[i];
            int j = i - 1;
            while (j >= 0 && footholds[j].compareTo(key) > 0) {
                footholds[j + 1] = footholds[j];
                j--;
            }
            footholds[j + 1] = key;
        }
    }

    /**
     * Bot-side drop-in for {@code FootholdTree.findBelow}: identical selection math (including
     * the original's trig-flavored slope interpolation and int truncation) over a per-column
     * bucket instead of a tree walk with per-query allocation and sorting. Graphgen and the
     * airborne integrator issue tens of millions of these probes on big maps.
     */
    static Foothold findBelowIndexed(MapleMap map, Point p) {
        if (map == null || map.getFootholds() == null) {
            return null;
        }
        FootholdCollisionIndex index = collisionIndex(map);
        if (index == UNINDEXABLE) {
            return map.getFootholds().findBelow(p); // stubbed tree — original query path
        }
        for (Foothold fh : index.groundBucketAt(p.x)) {
            if (fh.getX1() <= p.x && fh.getX2() >= p.x) {
                if (fh.getY1() != fh.getY2()) {
                    if (slopeYAt(fh, p.x) >= p.y) {
                        return fh;
                    }
                } else if (fh.getY1() >= p.y) {
                    return fh;
                }
            }
        }
        return null;
    }

    /** Bot-side drop-in for {@code MapleMap.getPointBelow} (calcPointBelow), same math. */
    static Point pointBelowIndexed(MapleMap map, Point initial) {
        if (map == null) {
            return null;
        }
        if (map.getFootholds() == null || collisionIndex(map) == UNINDEXABLE) {
            return map.getPointBelow(initial); // stubbed map/tree — original query path
        }
        Foothold fh = findBelowIndexed(map, initial);
        if (fh == null) {
            return null;
        }
        int dropY = fh.getY1() != fh.getY2() ? slopeYAt(fh, initial.x) : fh.getY1();
        return new Point(initial.x, dropY);
    }

    // Verbatim port of the foothold tree's slope interpolation — the trig chain reduces to
    // linear interpolation but is kept as-is so int truncation matches to the pixel.
    private static int slopeYAt(Foothold fh, int x) {
        double s1 = Math.abs(fh.getY2() - fh.getY1());
        double s2 = Math.abs(fh.getX2() - fh.getX1());
        double s4 = Math.abs(x - fh.getX1());
        double alpha = Math.atan(s2 / s1);
        double beta = Math.atan(s1 / s2);
        double s5 = Math.cos(alpha) * (s4 / Math.cos(beta));
        return fh.getY2() < fh.getY1() ? fh.getY1() - (int) s5 : fh.getY1() + (int) s5;
    }

    private static AirCollision landingAtX(MapleMap map,
                                           Point previousPos,
                                           Point nextPos,
                                           int x,
                                           double progress) {
        int yAtX = (int) Math.round(previousPos.y + (nextPos.y - previousPos.y) * progress);
        AirCollision landing = landingAtProbeY(map, previousPos, x, yAtX, progress, previousPos.y + 1, false);
        if (landing.type() == AirCollisionType.LAND) {
            return landing;
        }

        // Catch tangential landings at the jump apex when the next platform sits exactly at the
        // previous Y. Keep this forward-only so drops and down-jumps do not instantly re-land on
        // the takeoff foothold they just left.
        if (x != previousPos.x) {
            return landingAtProbeY(map, previousPos, x, yAtX, progress, previousPos.y, true);
        }

        return AirCollision.none();
    }

    private static AirCollision landingAtProbeY(MapleMap map,
                                                Point previousPos,
                                                int x,
                                                int yAtX,
                                                double progress,
                                                int probeY,
                                                boolean requireTangentFloor) {
        Point probe = new Point(x, probeY);
        Point floor = pointBelowIndexed(map, probe);
        if (floor == null) {
            return AirCollision.none();
        }

        int minY = Math.min(previousPos.y, yAtX);
        int maxY = Math.max(previousPos.y, yAtX);
        if (floor.y < minY || floor.y > maxY) {
            return AirCollision.none();
        }
        if (requireTangentFloor && floor.y != previousPos.y) {
            return AirCollision.none();
        }

        Foothold foothold = findBelowIndexed(map, probe);
        if (foothold == null) {
            return AirCollision.none();
        }

        return new AirCollision(AirCollisionType.LAND, new Point(x, floor.y), foothold, progress);
    }

    private static AirCollision wallCollision(Foothold wall,
                                              Point previousPos,
                                              Point nextPos,
                                              boolean allowWalkableGroundEndpoint) {
        int wallX = wall.getX1();
        int startX = previousPos.x;
        int endX = nextPos.x;
        if (startX == endX) {
            return AirCollision.none();
        }

        double progress = (wallX - startX) / (double) (endX - startX);
        // Ignore the wall exactly at the takeoff point; this is the ledge-edge case
        // where a drop would otherwise look like an immediate side collision.
        if (progress <= 0.0 || progress > 1.0) {
            return AirCollision.none();
        }

        double yAtWall = previousPos.y + (nextPos.y - previousPos.y) * progress;
        int minY = Math.min(wall.getY1(), wall.getY2());
        int maxY = Math.max(wall.getY1(), wall.getY2());
        if (yAtWall < minY || yAtWall > maxY) {
            return AirCollision.none();
        }
        if (allowWalkableGroundEndpoint && isWalkableGroundWallEndpoint(yAtWall, minY, maxY)) {
            return AirCollision.none();
        }

        int dir = Integer.compare(endX, startX);
        int safeX = wallX - dir;
        return new AirCollision(AirCollisionType.WALL,
                new Point(safeX, (int) Math.round(yAtWall)),
                wall,
                progress);
    }

    private static AirCollision ceilingCollision(Foothold foothold, Point previousPos, Point nextPos) {
        if (foothold.getY1() != foothold.getY2()) {
            return AirCollision.none();
        }

        int ceilingY = foothold.getY1();
        if (ceilingY > previousPos.y || ceilingY < nextPos.y) {
            return AirCollision.none();
        }

        double progress = (ceilingY - previousPos.y) / (double) (nextPos.y - previousPos.y);
        if (progress <= 0.0 || progress > 1.0) {
            return AirCollision.none();
        }

        double xAtCeiling = previousPos.x + (nextPos.x - previousPos.x) * progress;
        int minX = Math.min(foothold.getX1(), foothold.getX2());
        int maxX = Math.max(foothold.getX1(), foothold.getX2());
        if (xAtCeiling < minX || xAtCeiling > maxX) {
            return AirCollision.none();
        }

        return new AirCollision(
                AirCollisionType.CEILING,
                new Point((int) Math.round(xAtCeiling), ceilingY + 1),
                foothold,
                progress);
    }

    private static boolean isWalkableGroundWallEndpoint(double yAtWall, int minY, int maxY) {
        if (Math.abs(yAtWall - minY) < 0.001) {
            return true;
        }
        return Math.abs(yAtWall - maxY) < 0.001 && maxY - minY <= cfg.MAX_SLOPE_UP;
    }

    private static double landingGroundHSpeed(MapleMap map,
                                              Foothold foothold,
                                              double incomingDeltaX,
                                              double incomingDeltaY,
                                              BotMovementProfile profile) {
        double landingDeltaX = incomingDeltaX;
        if (foothold != null && !foothold.isWall() && foothold.slope() != 0.0) {
            double tangentX = foothold.getX2() - foothold.getX1();
            double tangentY = foothold.getY2() - foothold.getY1();
            double tangentLength = Math.hypot(tangentX, tangentY);
            if (tangentLength > 0.0) {
                double unitX = tangentX / tangentLength;
                double unitY = tangentY / tangentLength;
                double dot = incomingDeltaX * unitX + incomingDeltaY * unitY;
                landingDeltaX = unitX * dot;
            }
        }

        double maxDeltaPerTick = Math.max(1.0, walkStep(map, profile));
        landingDeltaX = Math.clamp(landingDeltaX, -maxDeltaPerTick, maxDeltaPerTick);
        return groundHSpeedFromTickDelta(map, landingDeltaX, profile);
    }

    private static double groundHSpeedFromTickDelta(MapleMap map, double deltaXPerTick, BotMovementProfile profile) {
        double stepsPerTick = Math.max(1.0, cfg.TICK_MS / CLIENT_GROUND_STEP_MS);
        return Math.clamp(deltaXPerTick / stepsPerTick, -maxHSpeedPerClientStep(profile), maxHSpeedPerClientStep(profile));
    }

    private static double tickDeltaFromGroundHSpeed(MapleMap map, double groundHSpeed, BotMovementProfile profile) {
        double stepsPerTick = Math.max(1.0, cfg.TICK_MS / CLIENT_GROUND_STEP_MS);
        double clampedHSpeed = Math.clamp(groundHSpeed, -maxHSpeedPerClientStep(profile), maxHSpeedPerClientStep(profile));
        return clampedHSpeed * stepsPerTick;
    }

    private static RopeGrabResult simulateRopeGrabCore(MapleMap map,
                                                       Point from,
                                                       float initialVelY,
                                                       int stepX,
                                                       Rope targetRope,
                                                       long landingGraceMs) {
        if (targetRope == null) {
            return null;
        }

        float velocityY = initialVelY;
        double physX = from.x;
        double physY = from.y;
        int previousIntY = from.y;
        long remainingLandingGraceMs = Math.max(0L, landingGraceMs);
        final float gravity = gravityPerTick();
        final float maxFall = maxFallPerTick();

        for (int tick = 0; tick < (1500 / cfg.TICK_MS); tick++) {
            Point current = new Point((int) Math.round(physX), (int) Math.round(physY));
            if (canGrabRopeAtPoint(current, targetRope)) {
                return new RopeGrabResult(new Point(targetRope.x(), current.y), tick);
            }

            if (remainingLandingGraceMs > 0L) {
                remainingLandingGraceMs = Math.max(0L, remainingLandingGraceMs - cfg.TICK_MS);
            }

            physX += stepX;
            physY += velocityY + 0.5f * gravity;
            velocityY = Math.min(velocityY + gravity, maxFall);

            int x = (int) Math.round(physX);
            int intY = (int) Math.round(physY);
            AirCollision collision = resolveAirCollision(map, new Point((int) Math.round(physX - stepX), previousIntY),
                    new Point(x, intY));
            if (collision.type() == AirCollisionType.WALL) {
                physX = collision.point().x;
                physY = collision.point().y;
                stepX = 0;
                previousIntY = collision.point().y;
                continue;
            }
            if (collision.type() == AirCollisionType.CEILING) {
                physX = collision.point().x;
                physY = collision.point().y;
                velocityY = 0f;
                previousIntY = collision.point().y;
                continue;
            }
            if (collision.type() == AirCollisionType.LAND
                    && (remainingLandingGraceMs == 0L || forbidFallDownLanding(collision))) {
                return null;
            }

            previousIntY = intY;
        }

        return null;
    }

    private static Point simulateRopeGrab(MapleMap map,
                                          Point from,
                                          float initialVelY,
                                          int stepX,
                                          Rope targetRope,
                                          long landingGraceMs) {
        RopeGrabResult result = simulateRopeGrabCore(map, from, initialVelY, stepX, targetRope, landingGraceMs);
        return result != null ? result.point() : null;
    }

    private static int estimateRopeGrabTimeMs(MapleMap map,
                                              Point from,
                                              float initialVelY,
                                              int stepX,
                                              Rope targetRope,
                                              long landingGraceMs) {
        RopeGrabResult result = simulateRopeGrabCore(map, from, initialVelY, stepX, targetRope, landingGraceMs);
        return result != null ? result.ticks() * cfg.TICK_MS : Integer.MAX_VALUE;
    }

    private static boolean canGrabRopeAtPoint(Point position, Rope rope) {
        return Math.abs(position.x - rope.x()) <= cfg.ROPE_GRAB_X
                && position.y >= firstClimbableY(rope)
                && position.y <= rope.bottomY();
    }

    private static JumpLanding simulateLanding(MapleMap map,
                                               Point from,
                                               float initialVelY,
                                               int stepX,
                                               long landingGraceMs) {
        float velocityY = initialVelY;
        double physX = from.x;
        double physY = from.y;
        int previousIntY = from.y;
        long remainingLandingGraceMs = Math.max(0L, landingGraceMs);
        final float gravity = gravityPerTick();
        final float maxFall = maxFallPerTick();

        for (int tick = 0; tick < (1500 / cfg.TICK_MS); tick++) {
            if (remainingLandingGraceMs > 0L) {
                remainingLandingGraceMs = Math.max(0L, remainingLandingGraceMs - cfg.TICK_MS);
            }

            physX += stepX;
            physY += velocityY + 0.5f * gravity;
            velocityY = Math.min(velocityY + gravity, maxFall);

            int x = (int) Math.round(physX);
            int intY = (int) Math.round(physY);
            Point previousPoint = new Point((int) Math.round(physX - stepX), previousIntY);
            Point nextPoint = new Point(x, intY);
            AirCollision collision = resolveAirCollision(map, previousPoint, nextPoint);
            if (collision.type() == AirCollisionType.WALL) {
                physX = collision.point().x;
                physY = collision.point().y;
                stepX = 0;
                previousIntY = collision.point().y;
                continue;
            }
            if (collision.type() == AirCollisionType.CEILING) {
                physX = collision.point().x;
                physY = collision.point().y;
                velocityY = 0f;
                previousIntY = collision.point().y;
                continue;
            }
            if (collision.type() == AirCollisionType.LAND
                    && (remainingLandingGraceMs == 0L || forbidFallDownLanding(collision))) {
                return new JumpLanding(collision.point(), collision.foothold(),
                        nextPoint.x - previousPoint.x, nextPoint.y - previousPoint.y, tick + 1);
            }

            previousIntY = intY;
        }

        return null;
    }

    private static int estimateLandingTimeMs(MapleMap map,
                                             Point from,
                                             float initialVelY,
                                             int stepX,
                                             long landingGraceMs) {
        JumpLanding landing = simulateLanding(map, from, initialVelY, stepX, landingGraceMs);
        return landing != null ? landing.timeMs() : Integer.MAX_VALUE;
    }
}
