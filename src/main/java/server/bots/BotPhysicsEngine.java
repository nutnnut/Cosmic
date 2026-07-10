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
    // Brake-to-stop sim bound for slippery landings: stop takes ~2.3/fs ticks from top
    // speed (11 at El Nath fs=0.2); 240 covers any fs >= ~0.01 with margin.
    private static final int POST_LANDING_BRAKE_TICK_CAP = 240;
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
        // Flash Jump (Hermit/NightWalker) mid-air impulse, calibrated from logs/monitored-packets-flashjump*:
        // a one-time set to (±550 horizontal, -350 vertical) px/s. 550 is a hard horizontal cap (did not
        // scale with 138% speed). No WZ distance keys exist for the skill — this is a client constant.
        public float FLASH_JUMP_H_PXS = 550.0f;
        public float FLASH_JUMP_V_PXS = -350.0f;
        public double HFORCE_PXS = 16.667;          // was 20.0 (yields 125 px/s walk via hF*GROUNDSLIP/(FRICTION+SLOPEFACTOR))
        public double GROUNDSLIP = 3.0;
        public double FRICTION = 0.3;
        public double SLOPEFACTOR = 0.1;
        // Slippery ground (fs<1) is a KINETIC regime in the client: constant accel/decel,
        // linear velocity ramps, hard cap at walkSpeed. Fitted to El Nath packet captures
        // (logs/monitored-packets-elnath-slippery-walk-left-right-spd{100,110}.log, fit
        // residuals <= 3.1 px/s) and consistent with client constants walkForce 140000 /
        // mass 100 and walkDrag 80000 / mass / 2 (client halves friction when fs<1).
        public double SLIP_WALK_ACCEL_PXSS = 1400.0;  // x fs -> px/s^2 while a direction is held
        public double SLIP_GLIDE_DECEL_PXSS = 400.0;  // x fs -> px/s^2 while gliding (no input)
        // Airborne horizontal control — CONFIRMED in disassembly (Angel.idb,
        // CVecCtrl::CalcFloat @ 0x9b2c3c) and packet-fitted (logs/monitored-packets-
        // elnath-tricky-jumps-spd100v2.log fs=0.2; logs/monitored-packets -
        // 100speedjumpmovement.log fs=1.0):
        //   - input held: ApplyForce(force=input*2*fs*10000, mass=100,
        //     vmax=(walkSpeed125/walkForce140000)*fs*10000 = 8.93 x fs px/s) =>
        //     accel 200 x fs px/s^2 toward the input, applied ONLY while the
        //     velocity component in the input direction is below 8.93 x fs px/s;
        //     hard clamp to that band edge on overshoot; NO-OP (no accel, no
        //     clamp) when already moving faster in the input direction. A
        //     counter-strafe therefore decelerates at 200 x fs straight through
        //     zero and pins at 8.93 x fs in the new direction (fs=1 packets:
        //     -103 -> -79 px/s over 120 ms = exactly +200 px/s^2); same-direction
        //     input adds ~nothing once moving. There is NO walkSpeed cap in the
        //     air — the observed <= 125 px/s is the ground cap carried in by the
        //     launch snap.
        //   - no input: drag toward 0 of 1 x fs px/s^2, switching to
        //     100 x fs px/s^2 while falling AT terminal velocity (vy = fallSpeed
        //     670); zero-cross clamped.
        //   - jump launch with a direction held snaps vx to +-walkSpeed instantly
        //     regardless of ground speed (62 -> 125 px/s within 30 ms at fs=1;
        //     El Nath -34 -> -124 where ground accel could only reach -44);
        //     with no input the current ground hspeed carries into the air.
        public double AIR_CONTROL_ACCEL_PXSS = 200.0;  // x fs -> px/s^2 toward held input
        public double AIR_INPUT_BAND_DIVISOR = 14.0;   // band = walkSpeed/14 x fs = 8.93 x fs px/s (walkSpeed/walkForce*10000)
        public double AIR_DRAG_PXSS = 1.0;             // x fs -> px/s^2 toward 0, no input
        public double AIR_DRAG_TERMINAL_PXSS = 100.0;  // x fs -> px/s^2 toward 0, no input at terminal fall

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

    // Fall integrators (simulateLanding / simulateRopeGrabCore) run until they land/grab OR the bot
    // falls past the map floor ({@link #mapFloorY}). This big tick cap is only a runaway backstop (a
    // wall/ceiling bounce that never makes vertical progress); normal termination is the map-bound
    // early-exit. Sized large so a tall in-bounds shaft (the ~2100px Ellinia tree) always reaches the
    // floor — the old flat 1500ms (~1000px at terminal velocity) cut long single-falls short, so no
    // landing was found and no DROP/JUMP/ROPE edge was generated, leaving the bot unable to descend.
    private static final int FALL_SIM_TICK_CAP = 2000;

    /** Lowest Y a fall can keep going before it has certainly left the map (VR bottom + slack for the
     *  odd foothold placed just under the boundary). {@code Integer.MAX_VALUE} when the map has no VR
     *  bounds, so the tick cap alone backstops. */
    private static int mapFloorY(MapleMap map) {
        java.awt.Rectangle area = map == null ? null : map.getMapArea();
        return area != null && area.height > 0 ? area.y + area.height + 600 : Integer.MAX_VALUE;
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

    /** Flash Jump horizontal dash, px/tick (the ±550 px/s impulse converted via the tick rate). */
    static float flashJumpHPerTick() {
        return cfg.FLASH_JUMP_H_PXS * tickS();
    }

    /** Flash Jump vertical boost, px/tick (the -350 px/s impulse; negative = upward). */
    static float flashJumpVPerTick() {
        return cfg.FLASH_JUMP_V_PXS * tickS();
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
     *
     * <p>Slippery ground is kinetic (see {@link #applySlipperyGroundStep}): the exact distance
     * to top speed is v_max&sup2;/(2*accel*fs) — ~28 px at El Nath fs=0.2 — added on top of the
     * fs=1 heuristic instead of the old 1/fs stretch (which over-reserved 180 px and starved
     * snow maps of launch anchors).
     */
    static int launchRunwayPx(MapleMap map, BotMovementProfile profile) {
        int step = walkStep(map, profile);
        double fs = mapGroundSlipScale(map, profile);
        if (fs >= 1.0) {
            return Math.max(40, step * 6);
        }
        double vmaxPxs = maxHSpeedPerClientStep(profile) / CLIENT_GROUND_STEP_S;
        double accelDistPx = vmaxPxs * vmaxPxs / (2 * cfg.SLIP_WALK_ACCEL_PXSS * fs);
        return (int) Math.max(40, Math.round(step * 6 + accelDistPx));
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
        Foothold fh = findContinuityGroundFoothold(entry, bot.getMap(), bot.getPosition());
        if (fh == null) {
            fh = findGroundFoothold(bot.getMap(), bot.getPosition());
        }
        if (fh == null) {
            beginFall(entry, bot, 0);
        }
        return fh;
    }

    private static Foothold findContinuityGroundFoothold(BotEntry entry, MapleMap map, Point position) {
        if (entry == null || entry.lastRegionId < 0 || map == null || position == null) {
            return null;
        }
        GroundRegionSample sample = findWalkRegionGroundSample(map, entry.lastRegionId, position.x, position.y);
        return sample == null ? null : sample.foothold();
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

    /**
     * SSOT for teleport position resolution: the nav layer supplies only an INTENT (a direction), and
     * physics resolves the legal landing — or null when blocked (no platform to snap to). Mirrors the
     * client's directional teleport so navigation can never cheat into an impossible spot:
     *  - HORIZONTAL ({@code dirX}=±1, dirY=0): move {@code range} px; snap to the platform CLOSEST
     *    VERTICALLY to the origin within ±{@code ySnap}. A same-level platform beats a higher/lower
     *    diagonal one (the bug this was written to fix). null if none in the band.
     *  - UP ({@code dirY}<0): the FURTHEST platform within {@code range} directly above. null if none.
     *  - DOWN ({@code dirY}>0): the FURTHEST platform within {@code range} directly below (symmetric
     *    with UP — a down-teleport is a full-range downward blink, not a short prone hop). Never a rope
     *    (pointBelowIndexed is foothold-only). null if none.
     */
    static Point teleportLanding(MapleMap map, Point origin, int dirX, int dirY, int range, int ySnap) {
        if (map == null || origin == null) {
            return null;
        }
        if (dirX != 0) {
            int tx = origin.x + Integer.signum(dirX) * range;
            return closestPlatformWithin(map, tx, origin.y, ySnap);
        }
        if (dirY < 0) { // up: furthest within range
            Point up = pointBelowIndexed(map, new Point(origin.x, origin.y - range));
            return (up != null && up.y < origin.y && origin.y - up.y <= range) ? up : null;
        }
        if (dirY > 0) { // down: FURTHEST platform within range below (walk platform-by-platform, keep the last in range)
            Point best = null;
            Point probe = pointBelowIndexed(map, new Point(origin.x, origin.y + 1));
            while (probe != null && probe.y > origin.y && probe.y - origin.y <= range) {
                best = probe;
                probe = pointBelowIndexed(map, new Point(origin.x, probe.y + 1));
            }
            return best;
        }
        return null;
    }

    /** Platform at x whose surface is closest vertically to {@code referenceY} within ±{@code ySnap}
     *  (same-level priority); null if none. Compares the at/just-below candidate against the highest
     *  candidate in the upper band so a same-level platform always beats a higher diagonal one. */
    private static Point closestPlatformWithin(MapleMap map, int x, int referenceY, int ySnap) {
        Point below = pointBelowIndexed(map, new Point(x, referenceY));         // at / just below referenceY
        Point above = pointBelowIndexed(map, new Point(x, referenceY - ySnap)); // highest within the upper band
        Point best = null;
        int bestD = Integer.MAX_VALUE;
        for (Point c : new Point[]{below, above}) {
            if (c == null) {
                continue;
            }
            int d = Math.abs(c.y - referenceY);
            if (d <= ySnap && d < bestD) {
                best = c;
                bestD = d;
            }
        }
        return best;
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

    static Foothold findWalkRegionGroundFoothold(MapleMap map, int regionId, int x, int referenceY) {
        GroundRegionSample sample = findWalkRegionGroundSample(map, regionId, x, referenceY);
        return sample == null ? null : sample.foothold();
    }

    static boolean canWalkGroundStep(MapleMap map, Point currentPos, int stepX) {
        if (map == null || currentPos == null) {
            return false;
        }
        Foothold foothold = findGroundFoothold(map, currentPos);
        return canWalkGroundStep(map, currentPos, foothold, stepX);
    }

    static boolean canWalkGroundStep(MapleMap map, Point currentPos, Foothold foothold, int stepX) {
        if (map == null || currentPos == null) {
            return false;
        }
        GroundStepPreview preview = previewGroundStep(map, currentPos, foothold, currentPos.x + stepX);
        return preview != null && !preview.lostGround() && !preview.blocked();
    }

    static boolean isGroundStepBlockedByWall(MapleMap map, Point currentPos, int stepX) {
        if (map == null || currentPos == null || stepX == 0) {
            return false;
        }
        Foothold foothold = findGroundFoothold(map, currentPos);
        return isGroundStepBlockedByWall(map, currentPos, foothold, stepX);
    }

    static boolean isGroundStepBlockedByWall(MapleMap map, Point currentPos, Foothold foothold, int stepX) {
        if (map == null || currentPos == null || stepX == 0) {
            return false;
        }
        GroundStepPreview preview = previewGroundStep(map, currentPos, foothold, currentPos.x + stepX);
        return preview != null && preview.blocked();
    }

    /**
     * Client truth (v83 CVecCtrl::CalcWalk/CollisionDetectWalk + live test on 600020100 x=-1315):
     * ground walking does NO wall scan at all — a walker follows its standing foothold's prev/next
     * chain, and the only "wall stop" is the chain itself ending at a vertical link (which the
     * region model already encodes: regions end there). So a ground runway is blocked only by the
     * map side boundary. Maps with no region model (mocked trees) keep the old any-wall stop —
     * their hand-built footholds have no chain/region data to walk by.
     */
    static boolean isGroundRunwayBlockedByWall(MapleMap map, Point from, Point to) {
        AirCollision collision = findGroundWallCollision(map, from, to, MOVER_ZMASS_ALL);
        if (collision.type() != AirCollisionType.WALL) {
            return false;
        }
        if (collision.foothold() == null) {
            return true; // map side boundary
        }
        return resolveWalkRegionLookup(map) == null; // no region model — conservative legacy stop
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

    /** True when {@code candidateFootholdId} is the standing foothold or its direct prev/next link —
     *  i.e. a foothold you reach by continuing to walk along the chain (not by dropping onto a fork arm). */
    private static boolean isChainStep(Foothold foothold, int candidateFootholdId) {
        return candidateFootholdId == foothold.getId()
                || candidateFootholdId == foothold.getNext()
                || candidateFootholdId == foothold.getPrev();
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
        return findWalkRegionGroundSample(lookup, regionId, foothold, x, referenceY);
    }

    private static GroundRegionSample findWalkRegionGroundSample(MapleMap map, int regionId, int x, int referenceY) {
        WalkRegionLookup lookup = resolveWalkRegionLookup(map);
        if (lookup == null) {
            return null;
        }
        return findWalkRegionGroundSample(lookup, regionId, null, x, referenceY);
    }

    private static GroundRegionSample findWalkRegionGroundSample(WalkRegionLookup lookup,
                                                                 int regionId,
                                                                 Foothold foothold,
                                                                 int x,
                                                                 int referenceY) {
        BotNavigationGraph.Region region = lookup.regionsById().get(regionId);
        if (region == null || region.isRopeRegion) {
            return null;
        }

        BotNavigationGraph.Segment bestSegment = null;
        Point bestPoint = null;
        int bestScore = Integer.MAX_VALUE;
        boolean bestChainStep = false;
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

            // Follow the standing foothold's prev/next chain across a fork, like the real client
            // (which tracks the SN foothold). At a joined fork two arms overlap in x; the lower arm
            // wins the raw dx/dy score and the walk snaps DOWN onto it, but you can only get onto a
            // non-chain arm by an explicit down-jump, not by walking. So a chain-step (this foothold
            // or its prev/next) always beats a non-chain segment; the dx/dy score only breaks ties
            // within the same chain class. Crossing footholds that share no chain link keep the old
            // behaviour, so a ramp crossing flat ground can still be walked down.
            boolean chainStep = foothold == null || isChainStep(foothold, segment.footholdId);
            int score = dx * 1000 + Math.abs(dy);
            boolean better = bestSegment == null
                    || (chainStep && !bestChainStep)
                    || (chainStep == bestChainStep
                    && (score < bestScore || (score == bestScore && candidate.y > bestPoint.y)));
            if (better) {
                bestSegment = segment;
                bestPoint = candidate;
                bestScore = score;
                bestChainStep = chainStep;
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

    /** Package-visible accessor for nav within-region foothold-chain routing (cached id->Foothold). */
    static Map<Integer, Foothold> footholdsByIdFor(MapleMap map) {
        return footholdsById(map);
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

        // Client ground walking scans NO walls (CVecCtrl::CalcWalk — chain traversal only; the
        // region model encodes the chain, so region-constrained sampling below IS the junction
        // stop). Only the map side boundary still blocks. Maps without a region model keep the
        // legacy any-wall stop: their walkers snap by raw findBelow and would otherwise cross
        // through structures.
        AirCollision wall = constrainToWalkRegion
                ? mapSideBoundaryCollision(map, currentPos, new Point(nextX, baseY))
                : findGroundWallCollision(map, currentPos, new Point(nextX, baseY), MOVER_ZMASS_ALL);
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

    // === LOD1 motion plan (unobserved-map movement, docs/bot/living-server-design.md) ===
    // Pure helpers: a LOD1 bot's position is not physics-integrated; it lerps (from->to) over a
    // duration derived from the SAME ground speed physics uses, so abstract time ~= real time.

    /** Pixel path length along a committed route's edge chain: each edge's start->end segment plus the
     *  gap between consecutive edges. 0 for a null/empty route. The "distance without live A*" the
     *  motion-plan duration uses (design §2.1) — the route was already planned; we only measure it. */
    static double routePixelLength(java.util.List<BotNavigationGraph.Edge> route) {
        if (route == null || route.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        Point cursor = null;
        for (BotNavigationGraph.Edge e : route) {
            if (e == null) {
                continue;
            }
            if (cursor != null) {
                total += cursor.distance(e.startPoint);
            }
            total += e.startPoint.distance(e.endPoint);
            cursor = e.endPoint;
        }
        return total;
    }

    /** Straight-line distance with the §2.1 slack factor, used when no committed route exists. */
    static double straightLinePixelLength(Point from, Point to) {
        return from == null || to == null ? 0.0 : from.distance(to) * 1.3;
    }

    /** Motion-plan traversal time (ms) for {@code pixelDistance} at the bot's real ground speed (px/s,
     *  the same {@link BotMovementProfile#walkVelocityPxs()} physics integrates), with ±10% humanlike
     *  jitter. Never below 1ms so arrival is always strictly after departure. */
    static long motionDurationMs(double pixelDistance, double groundPxPerSec, java.util.Random rng) {
        if (pixelDistance <= 0 || groundPxPerSec <= 0) {
            return 0L;
        }
        double seconds = pixelDistance / groundPxPerSec;
        double jitter = rng == null ? 1.0 : 1.0 + (rng.nextDouble() - 0.5) * 0.2; // ±10%
        return Math.max(1L, Math.round(seconds * 1000.0 * jitter));
    }

    /** Lerp the plan position by wall clock. Before depart -> from; after arrive -> to. Y holds at
     *  {@code holdY} (the foothold Y) the whole way; exact Y is only reconciled at the LOD0
     *  transition foothold snap (§4). Returns null only when both endpoints are null. */
    static Point motionLerp(Point from, Point to, long departMs, long arriveMs, int holdY, long nowMs) {
        if (from == null && to == null) {
            return null;
        }
        if (from == null) {
            return new Point(to.x, holdY);
        }
        if (to == null || nowMs <= departMs || arriveMs <= departMs) {
            return new Point(from.x, holdY);
        }
        if (nowMs >= arriveMs) {
            return new Point(to.x, holdY);
        }
        double t = (nowMs - departMs) / (double) (arriveMs - departMs);
        int x = (int) Math.round(from.x + (to.x - from.x) * t);
        return new Point(x, holdY);
    }

    /** Min drop (px) below the portal landing before a map-change spawn falls by gravity instead of
     *  snapping. Below this the floor is effectively at the spawn point — snap (no visible drop). */
    static final int SPAWN_FALL_MIN_DROP_PX = 12;

    /** Settle a bot that just changed map. When it lands on/near the floor, snap as before. But when the
     *  destination portal drops it meaningfully ABOVE the floor, leave it at the spawn point, zero its
     *  velocity, and enter the AIR so the normal physics ticks ease it down under gravity — the way a real
     *  player spawns at a portal and falls, instead of hard-snapping its Y (which reads as a position
     *  warp). The fall resolves in the following ticks when a foothold catches it. */
    static void spawnIntoMap(BotEntry entry, Character bot) {
        Point cur = bot.getPosition();
        Point ground = findGroundPoint(bot.getMap(), new Point(cur.x, cur.y - 1));
        if (ground != null && ground.y - cur.y > SPAWN_FALL_MIN_DROP_PX) {
            teleportTo(entry, bot, cur); // stay at the portal landing...
            entry.inAir = true;          // ...and let gravity carry it down to the floor
            entry.velY = 0f;
        } else {
            teleportTo(entry, bot, ground != null ? ground : cur);
        }
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
        entry.groundBrakeDir = 0;
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
        entry.climbIntentRope = null; // knockback recovery may grab any rope it reaches
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
        // NOTE: we deliberately do NOT counter-strafe-brake here. At the landing tick entry.moveDir
        // still holds stale AIRBORNE steering, not the ground continuation direction, so the old
        // "opposite held key zeroes hspeed" brake fired on noise: it killed the landing momentum and,
        // because setMovementVelocity only re-derives facing when velX != 0, left facingDir stuck at
        // the air-steer direction — the bot visibly faced backwards on landing even when continuing.
        // Keep the halved landing momentum (set above) so the bot rides it and faces its travel
        // direction; the next ground tick's applyGroundMotion brakes on the REAL planned direction
        // (and slipperyStopDir still prevents sliding off an icy ledge).
        entry.groundBrakeDir = 0;
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
        if (desiredDir == 0) {
            // Standing intent while still sliding on slippery ground: glide like a player,
            // counter-strafe brake only when the slide would carry the bot off the platform.
            desiredDir = slipperyStopDir(map, entry.movementProfile, currentPos, foothold,
                    new GroundTravelState(entry.physX, entry.hspeed, entry.groundPhysicsCarryMs));
        }
        // Counter-strafe: the held key opposes the slide. A real player visibly faces the
        // held direction while sliding the other way; record it so facing/stance follow the
        // INPUT instead of the velocity-derived slide direction.
        boolean braking = desiredDir != 0 && entry.hspeed * desiredDir < 0.0;
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
        // Slippery ground keeps the fractional x (position stays the rounded broadcast
        // pixel). One 50ms input pulse from rest moves well under a pixel; truncating
        // physX to the int position every tick discarded that progress and made tight
        // launch windows unreachable from rest (pathlog-Leroy-2026-06-12T140609: parked
        // 2-3px short of a 2px jump window forever on fs=0.2). Normal ground keeps the
        // int snap - bit-exact with prior behavior.
        entry.physX = mapGroundSlipScale(map, entry.movementProfile) < 1.0
                ? step.state().physX()
                : position.x;
        entry.physY = position.y;
        entry.hspeed = step.state().hspeed();
        entry.groundPhysicsCarryMs = step.state().carryMs();
        entry.downJumpPending = false;
        // Ground facing follows the effective held key ONLY, and only on ticks with actual
        // displacement: a player cannot turn in place without moving (no stationary
        // moonwalk/flip-flop from a dithering controller), and with no key held the LAST
        // pressed direction persists - a slide never turns the character into the slide.
        // setMovementVelocity derives facing from velocity sign - override it here.
        int preMoveFacing = entry.facingDir;
        boolean movedThisTick = position.x != currentPos.x;
        setMovementVelocity(entry, step.velocityX(), 0);
        entry.groundBrakeDir = braking && movedThisTick ? desiredDir : 0;
        if (movedThisTick && desiredDir != 0) {
            entry.facingDir = desiredDir; // the held (or stop-policy emulated) key
        } else {
            entry.facingDir = preMoveFacing;
        }
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

    /**
     * The live launch state at a walk-off lip is not unique: the bot arrives with an arbitrary
     * fractional physX phase, sub-step carryMs, and hspeed (standing start at the anchor vs
     * walking through at full speed), which shifts the exact dismount pixel and the seeded air
     * drift by a rounding step (e.g. 6 vs 7 px/tick at 105% speed). Returns the walk-off outcome
     * for a spread of those launch states — baseline (the historical single sim: phase 0,
     * standing start) FIRST — so graphgen can check the landing is stable across all of them
     * before authoring an edge from just one. Entries may be null (that variant found no landing).
     */
    static java.util.List<WalkOffLanding> walkOffLandingVariants(MapleMap map,
                                                                 Point from,
                                                                 int desiredDir,
                                                                 BotMovementProfile profile) {
        java.util.List<WalkOffLanding> outcomes = new java.util.ArrayList<>();
        double terminalHSpeed = maxHSpeedPerClientStep(profile) * desiredDir;
        double[] physXPhases = {0.0, -0.4, 0.4};
        double[] carryPhases = {0.0, CLIENT_GROUND_STEP_MS / 2.0};
        double[] launchHSpeeds = {0.0, terminalHSpeed};
        for (double phase : physXPhases) {
            for (double carryMs : carryPhases) {
                for (double hspeed : launchHSpeeds) {
                    outcomes.add(simulateWalkOffLanding(map, from, desiredDir,
                            new GroundTravelState(from.x + phase, hspeed, carryMs), profile));
                }
            }
        }
        return outcomes;
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
        if (mapGroundSlipScale(map, profile) < 1.0) {
            // Slippery landing: stop-policy parity with the live tick (slipperyStopDir) —
            // glide when the glide-out stays on ground, counter-strafe brake when it would
            // slide off. Validity is "can the bot land here and come to a stop"; the policy
            // is deterministic, so the graph gains no extra state.
            for (int i = 0; i < POST_LANDING_BRAKE_TICK_CAP; i++) {
                int dir = slipperyStopDir(map, profile, cursor, currentFoothold, state);
                if (dir == 0) {
                    // Stopped/sub-residual, or the glide-out was projected to stay on
                    // ground — either way this landing is stable.
                    return new PostLandingJump(landing, cursor, currentFoothold, false);
                }
                GroundStepResult step = simulateGroundMotion(map, cursor, currentFoothold, dir, state, profile);
                if (step.lostGround()) {
                    return new PostLandingJump(landing, step.point(), step.foothold(), true);
                }
                cursor = step.point();
                currentFoothold = step.foothold();
                state = step.state();
            }
            return new PostLandingJump(landing, cursor, currentFoothold, true); // never stopped
        }
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
     * Intent-driven swim integrator fitted to client behavior.
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
            // still arc upward under swim physics (a NORMAL kick
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
            AirCollision collision = resolveAirCollision(map, prevPt, nextPt, moverZMassFor(entry, map));
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
                // Wall hit while actively steering: flag it so the next computeSwimIntents rises to
                // clear the obstacle instead of greedily pressing into it (swim "jump over the wall").
                entry.swimWallBlocked = entry.swimMoveDir != 0;
            } else {
                entry.swimWallBlocked = false;
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

    /**
     * Apply air steering from discrete input direction. Disasm-true model (CVecCtrl::CalcFloat
     * @ 0x9b2c3c, see {@link Config#AIR_CONTROL_ACCEL_PXSS}): accel 200 x fs px/s^2 toward the
     * input, applied ONLY while the velocity component in the input direction is below the
     * input band (walkSpeed/14 x fs = 8.93 x fs px/s); hard clamp to the band edge on
     * overshoot; no-op (no accel, no clamp) when already moving faster in the input direction.
     * A counter-strafe decelerates straight through zero and pins at the band edge in the new
     * direction — mid-air input can never rebuild walk speed.
     */
    private static void applyAirSteering(BotEntry entry, MapleMap map, int steerDir) {
        if (steerDir == 0) return;
        double t = tickS();
        double fs = mapGroundSlipScale(map, entry.movementProfile);
        double band = walkSpeedPerTick(entry.movementProfile) * fs / cfg.AIR_INPUT_BAND_DIVISOR;
        double total = entry.airVelX + entry.airSteerVelX;
        double inDir = total * steerDir; // velocity component along the input direction
        if (inDir < band) {
            inDir = Math.min(band, inDir + cfg.AIR_CONTROL_ACCEL_PXSS * fs * t * t);
            entry.airSteerVelX = inDir * steerDir - entry.airVelX;
        }
        // else: ApplyForce no-op — neither accelerates nor clamps an over-band velocity
        // in the input direction (an over-cap knockback launch keeps its speed too).
        // Client jump stance follows the held steering direction, not the preserved horizontal
        // launch momentum. Updating facing here makes airborne debug output line up with what the
        // client is visually trying to do, even before the net X velocity changes sign.
        entry.facingDir = steerDir > 0 ? 1 : -1;
    }

    /**
     * No-input airborne drag (CalcFloat @ 0x9b2c3c): vx decays toward 0 at 1 x fs px/s^2,
     * switching to 100 x fs px/s^2 while falling AT terminal velocity; clamped at zero-cross.
     * NOT applied to committed nav arcs (fixedAirArc): those model the launch direction key
     * held for the whole flight — held input suppresses drag and is a no-op above the input
     * band, keeping vx constant exactly like the graph's constant-stepX arc simulation.
     */
    private static void applyAirDrag(BotEntry entry, MapleMap map) {
        double total = entry.airVelX + entry.airSteerVelX;
        if (total == 0.0) return;
        double t = tickS();
        double fs = mapGroundSlipScale(map, entry.movementProfile);
        boolean terminalFall = entry.velY >= maxFallPerTick();
        double drag = (terminalFall ? cfg.AIR_DRAG_TERMINAL_PXSS : cfg.AIR_DRAG_PXSS) * fs * t * t;
        double next = total > 0.0 ? Math.max(0.0, total - drag) : Math.min(0.0, total + drag);
        entry.airSteerVelX = next - entry.airVelX;
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
     * air steering (-1/0/+1). Movement layer holds the LAUNCH key for committed nav
     * trajectories (fixedAirArc, JUMP/DROP edges) — a no-op above the input band that
     * keeps vx constant — and leaves moveDir=0 for free no-input flight, which gets
     * the CalcFloat drag instead.
     *
     * One physics step: apply air steering/drag from intent, advance position, resolve collision, apply result.
     * All collision outcome methods are private — movement must not call them directly.
     */
    static AirborneStepResult stepAirborne(BotEntry entry, Character bot) {
        // Apply air steering from intent. Movement holds the launch key for committed
        // trajectories (input no-op above the band — vx constant); free flight with no
        // input gets the CalcFloat drag instead. fixedAirArc additionally guards direct
        // stepAirborne callers that bypass the movement layer's key-hold emulation.
        if (entry.moveDir != 0) {
            applyAirSteering(entry, bot.getMap(), entry.moveDir);
        } else if (!entry.fixedAirArc) {
            applyAirDrag(entry, bot.getMap());
        }

        // Flash Jump: one-time mid-air impulse fired at apex (velY crosses to >= 0). Overrides this
        // tick's velocity with the dash (±550, -350 px/s) and pins the arc so steering/drag leave it
        // alone (applyAirSteering is a no-op above the input band; fixedAirArc skips drag).
        if (entry.pendingFlashJump && entry.velY >= 0f) {
            int dir = entry.airVelX != 0 ? Integer.signum(entry.airVelX) : (entry.facingDir >= 0 ? 1 : -1);
            entry.airVelX = Math.round(dir * flashJumpHPerTick());
            entry.airSteerVelX = 0.0;
            entry.velY = flashJumpVPerTick();
            entry.fixedAirArc = true;
            entry.pendingFlashJump = false;
            entry.flashJumpFired = true; // signal tickAirborne to broadcast the type-6 "fj" dash this tick
        }

        Point previousPos = roundedAirPosition(entry);
        Point nextPos = advanceAirbornePosition(entry, bot);
        AirCollision collision = resolveAirCollision(bot.getMap(), previousPos, nextPos,
                moverZMassFor(entry, bot.getMap()));
        if (collision.type() == AirCollisionType.WALL) {
            collideWithAirWall(entry, bot, collision.point());
            return AirborneStepResult.WALL;
        }
        if (collision.type() == AirCollisionType.CEILING) {
            collideWithAirCeiling(entry, bot, collision.point());
            return AirborneStepResult.CEILING;
        }
        if (collision.type() == AirCollisionType.LAND && landingResolves(canLand(entry), collision)) {
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
        // is active. Mirrors the client substituting Stance::ALERT for STAND1/2
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
        if (entry.groundBrakeDir != 0) {
            // Counter-strafe brake without walk intent (slipperyStopDir): render the held
            // opposite key as a walk stance so observers see the counter-strafe.
            return entry.groundBrakeDir > 0 ? CharacterStance.WALK_RIGHT_STANCE : CharacterStance.WALK_LEFT_STANCE;
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
        // The bot can grab the rope anywhere down to its climbable bottom; if that hangs below the
        // launch ledge it keeps drifting sideways through the descent, so the horizontal reach must
        // count that extra airtime (otherwise mid-rope grabs from an adjacent ledge are missed).
        int dropToRopeBottom = Math.max(0, rope.bottomY() - from.y);
        return rope.bottomY() >= from.y - jumpReach
                && dx <= maxHorizontalTravelWithDrop(map, profile, jumpForcePerTick(profile), dropToRopeBottom);
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

    /** SSOT landing-accept rule shared by the live integrator and both offline fall simulators: a LAND
     *  collision resolves into a real landing once the down-jump grace has expired, OR immediately when
     *  the foothold forbids fall-down (those stay solid even mid-grace). */
    private static boolean landingResolves(boolean graceExpired, AirCollision collision) {
        return graceExpired || forbidFallDownLanding(collision);
    }

    static JumpLanding simulateJumpLanding(MapleMap map, Point from, int stepX) {
        return simulateJumpLanding(map, from, stepX, BotMovementProfile.base());
    }

    static JumpLanding simulateJumpLanding(MapleMap map, Point from, int stepX, BotMovementProfile profile) {
        return simulateLanding(map, from, -jumpForcePerTick(profile), stepX, 0L);
    }

    private record FlashImpulse(int hStep, float velY) {}

    static JumpLanding simulateFlashJumpLanding(MapleMap map, Point from, int stepX) {
        return simulateFlashJumpLanding(map, from, stepX, BotMovementProfile.base());
    }

    /** Flash-jump trajectory: a normal jump launch with the apex impulse injected (mirrors execution).
     *  {@code stepX} sets the launch direction; the apex dash magnitude is the client ±550 px/s cap. */
    static JumpLanding simulateFlashJumpLanding(MapleMap map, Point from, int stepX, BotMovementProfile profile) {
        int dashStep = Math.round(Integer.signum(stepX) * flashJumpHPerTick());
        return simulateLanding(map, from, -jumpForcePerTick(profile), stepX, 0L,
                new FlashImpulse(dashStep, flashJumpVPerTick()));
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

    // A down-jump falls through the platform and keeps falling until it lands — NO drop-distance
    // cap. An earlier 300px cap (attributed to a CUserLocal::FallDown probe) was empirically wrong:
    // it refused down-jumps players can perform in-game (e.g. descending the Orbis station tower)
    // and stranded bots on islanded platforms. The exact client eligibility rule is still unknown
    // (it's neither a 300px probe nor purely the forbidFallDown flag) — until it's pinned, generate
    // the edge wherever a real landing exists below and let execution abandon any that prove illegal.
    static JumpLanding simulateDownJumpLanding(MapleMap map, Point from) {
        if (!canStartDownJump(map, from)) {
            return null;
        }
        return simulateLanding(map, from, -downJumpForcePerTick(), 0, cfg.DOWN_JUMP_GRACE_MS);
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

    /**
     * Mover zMass of a live bot: the group of the region the bot last resolved to. Reuses the nav
     * layer's chain-continuity tracking ({@link BotEntry#lastRegionId}) — the client equivalent is
     * CVecCtrl::m_lZMass, set from the attached foothold and KEPT while airborne. A region is one
     * foothold chain, and WZ chains never cross zMass groups, so any segment's foothold carries the
     * region's group. Unknown region → {@link #MOVER_ZMASS_UNKNOWN} (base-group walls only, same as
     * a fresh client mover).
     */
    static int moverZMassFor(BotEntry entry, MapleMap map) {
        if (entry == null) {
            return MOVER_ZMASS_UNKNOWN;
        }
        return regionZMass(map, entry.lastRegionId);
    }

    static int regionZMass(MapleMap map, int regionId) {
        if (regionId < 0) {
            return MOVER_ZMASS_UNKNOWN;
        }
        WalkRegionLookup lookup = resolveWalkRegionLookup(map);
        if (lookup == null) {
            return MOVER_ZMASS_UNKNOWN;
        }
        BotNavigationGraph.Region region = lookup.regionsById().get(regionId);
        if (region == null || region.isRopeRegion || region.segments.isEmpty()) {
            return MOVER_ZMASS_UNKNOWN;
        }
        Foothold foothold = lookup.footholdsById().get(region.segments.get(0).footholdId);
        return foothold == null ? MOVER_ZMASS_UNKNOWN : foothold.getZMass();
    }

    /** Mover zMass for a sim launched at {@code from}: the group of the foothold under the launch
     *  point when it is effectively standing there, else unknown. Where two chains overlap at the
     *  same pixel (shared ground) the chain-blind lookup can pick either group — acceptable: both
     *  grounds coincide there and the mis-pick only re-enables that structure's own walls. */
    private static int moverZMassAt(MapleMap map, Point from) {
        if (map == null || from == null) {
            return MOVER_ZMASS_UNKNOWN;
        }
        Foothold foothold = findGroundFoothold(map, from);
        if (foothold == null) {
            return MOVER_ZMASS_UNKNOWN;
        }
        Point ground = findGroundPoint(map, from);
        if (ground == null || Math.abs(ground.y - from.y) > cfg.MAX_SLOPE_UP) {
            return MOVER_ZMASS_UNKNOWN; // airborne start — last-stood group unknown to the sim
        }
        return foothold.getZMass();
    }

    private static AirCollision resolveAirCollision(MapleMap map, Point previousPos, Point nextPos, int moverZMass) {
        if (map == null || map.getFootholds() == null || previousPos == null || nextPos == null) {
            return AirCollision.none();
        }
        AirCollision wall = findWallCollision(map, previousPos, nextPos, moverZMass);
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
        entry.climbIntentRope = null; // nav rope-jumps re-set this to their target rope right after launch
        clearRopeEntryIntent(entry);
        entry.airVelX = airVelX;
        entry.airSteerVelX = 0.0;
        entry.fixedAirArc = false;
        entry.downJumpPending = false;
        entry.pendingFlashJump = false; // FLASH_JUMP execution re-sets this right after launch
        entry.flashJumpFired = false;
        // Clear ground movement intent when going airborne - unified moveDir serves both
        // ground and air, so ground walk direction must not bleed into air steering.
        // Movement manager will set moveDir for air steering if shouldApplyAirSteering allows.
        entry.moveDir = 0;
        entry.groundBrakeDir = 0;
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
        entry.groundBrakeDir = 0;
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
        double slipScale = mapGroundSlipScale(map, profile);
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

        if (slipScale < 1.0) {
            return applySlipperyGroundStep(hspeed, desiredDir, profile, slipScale);
        }
        double inertia = hspeed / cfg.GROUNDSLIP;
        double slope = clampedSlope(foothold);
        double drag = (cfg.FRICTION + cfg.SLOPEFACTOR * (1.0 + slope * -inertia)) * inertia;
        return hspeed + (hforce - drag) * slipScale;
    }

    /**
     * Slippery ground (fs&lt;1) is kinetic, not the force/drag model: packet captures show
     * LINEAR velocity ramps — constant accel {@code SLIP_WALK_ACCEL_PXSS x fs} while a
     * direction is held (hard-capped at the profile's walk speed; the cap scales with the
     * speed stat, the accel does not) and constant decel {@code SLIP_GLIDE_DECEL_PXSS x fs}
     * while gliding. El Nath fs=0.2: 0 to 125 px/s in ~0.45 s, ~1.6 s / ~98 px to slide out.
     * Slope is deliberately ignored here (captures are flat ground; snow maps mostly are).
     */
    private static double applySlipperyGroundStep(double hspeed, int desiredDir,
                                                  BotMovementProfile profile, double fs) {
        if (desiredDir != 0) {
            double dv = cfg.SLIP_WALK_ACCEL_PXSS * fs * CLIENT_GROUND_STEP_S * CLIENT_GROUND_STEP_S;
            double cap = maxHSpeedPerClientStep(profile);
            return Math.clamp(hspeed + desiredDir * dv, -cap, cap);
        }
        double dv = cfg.SLIP_GLIDE_DECEL_PXSS * fs * CLIENT_GROUND_STEP_S * CLIENT_GROUND_STEP_S;
        return hspeed - Math.copySign(Math.min(Math.abs(hspeed), dv), hspeed);
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

    /** Like {@link #mapGroundSlipScale(MapleMap)}, but snowshoes (worn-shoe WZ fs >= 1, see
     *  BotMovementProfile) cancel the field's slipperiness — normal physics on snow. */
    private static double mapGroundSlipScale(MapleMap map, BotMovementProfile profile) {
        return profileOrBase(profile).snowShoes() ? 1.0 : mapGroundSlipScale(map);
    }

    /**
     * Counter-strafe braking: on slippery ground a held opposite input sheds speed at
     * {@code SLIP_WALK_ACCEL_PXSS x fs} — 3.5x the passive glide — exactly like a player
     * counter-strafing to stop on a small platform. Returns the brake direction while the
     * bot slides faster than one brake tick can cancel, else 0 (the residual glide-out is
     * under ~1 px). Always 0 on non-slippery ground or with snowshoes.
     */
    static int counterStrafeBrakeDir(MapleMap map, BotMovementProfile profile, double hspeed) {
        double fs = mapGroundSlipScale(map, profile);
        if (fs >= 1.0) {
            return 0;
        }
        double brakePerTick = cfg.SLIP_WALK_ACCEL_PXSS * fs * CLIENT_GROUND_STEP_S * CLIENT_GROUND_STEP_S
                * Math.max(1.0, cfg.TICK_MS / CLIENT_GROUND_STEP_MS);
        return Math.abs(hspeed) > brakePerTick ? (hspeed > 0 ? -1 : 1) : 0;
    }

    /**
     * Stop policy while a bot intends to stand on slippery ground: GLIDE (the packet-true
     * 80*fs px/s^2 — what real players look like when they let go) unless the projected
     * input-free glide-out would slide off the ground, in which case counter-strafe brake —
     * a player saving themselves near an edge. Projection reuses the live ground sim so
     * ledge/wall semantics stay identical; re-evaluated every tick, so a brake releases the
     * moment the remaining slide is safe.
     */
    static int slipperyStopDir(MapleMap map, BotMovementProfile profile, Point position,
                               Foothold foothold, GroundTravelState state) {
        int brakeDir = counterStrafeBrakeDir(map, profile, state.hspeed());
        if (brakeDir == 0) {
            return 0;
        }
        GroundTravelState s = state;
        Point cursor = position;
        Foothold fh = foothold;
        for (int i = 0; i < POST_LANDING_BRAKE_TICK_CAP && Math.abs(s.hspeed()) > 0.0; i++) {
            GroundStepResult step = simulateGroundMotion(map, cursor, fh, 0, s, profile);
            if (step.lostGround()) {
                return brakeDir;
            }
            cursor = step.point();
            fh = step.foothold();
            s = step.state();
        }
        return 0;
    }

    /**
     * Bang-bang approach controller for walking toward a target x on slippery ground.
     * On normal ground (fs &gt;= 1 or snowshoes) this is a plain {@code sign(dx)} passthrough.
     * On slippery ground it accelerates toward the target only while the projected travel
     * of one more accelerating tick PLUS the stop-out from the resulting speed (counter-
     * strafe brake down to the release threshold, then the residual glide) still fits
     * inside the remaining distance; otherwise it counter-strafes against the slide, and
     * once the slide is below one brake tick it holds (0) and creeps back in on later
     * ticks. Re-evaluated every tick, so the bot arrives at the target able to stop
     * instead of sliding past it and off the platform
     * (pathlog-Preston-2026-06-12T083326: full-slide approach to the r17-&gt;r14 launch
     * window at x=59 overran the platform's left edge and fell).
     */
    static int slipperyApproachDir(MapleMap map, BotMovementProfile profile, double hspeed, int dxToTarget) {
        return slipperyApproachDir(map, profile, hspeed, dxToTarget, 0);
    }

    /**
     * {@code overshootSlackPx}: extra distance past the target that still counts as arrival
     * instead of overshoot — callers steering into a launch WINDOW pass the room between the
     * target pixel and the window's far edge. Without it the smallest legal 50ms pulse from
     * rest (~1.6px at fs=0.2) can exceed the remaining 1-2px to the target pixel and the
     * controller refuses to accelerate at all, parking the bot just outside a tight window
     * forever (pathlog-Leroy-2026-06-12T140609).
     */
    static int slipperyApproachDir(MapleMap map, BotMovementProfile profile, double hspeed, int dxToTarget,
                                   int overshootSlackPx) {
        int towardDir = Integer.signum(dxToTarget);
        double fs = mapGroundSlipScale(map, profile);
        if (towardDir == 0 || fs >= 1.0) {
            return towardDir;
        }

        double dvBrake = cfg.SLIP_WALK_ACCEL_PXSS * fs * CLIENT_GROUND_STEP_S * CLIENT_GROUND_STEP_S;
        double dvGlide = cfg.SLIP_GLIDE_DECEL_PXSS * fs * CLIENT_GROUND_STEP_S * CLIENT_GROUND_STEP_S;
        double cap = maxHSpeedPerClientStep(profile);
        // ceil: a real tick integrates TICK_MS/8 client steps plus carry, so overestimating
        // the accel tick keeps the projection conservative (brakes a step early, never late).
        int stepsPerTick = Math.max(1, (int) Math.ceil(cfg.TICK_MS / CLIENT_GROUND_STEP_MS));
        double brakeReleaseSpeed = dvBrake * stepsPerTick; // counterStrafeBrakeDir's release threshold

        // Project in the same per-client-step quanta as applySlipperyGroundStep: one tick of
        // accelerating toward the target, then brake to releasable, then glide out the rest.
        double v = hspeed * towardDir; // speed component toward the target, px per client step
        double traveled = 0.0;
        for (int i = 0; i < stepsPerTick; i++) {
            v = Math.min(v + dvBrake, cap);
            traveled += v;
        }
        while (v > brakeReleaseSpeed) {
            v -= dvBrake;
            traveled += v;
        }
        while (v > 0.0) {
            v -= dvGlide;
            traveled += Math.max(v, 0.0);
        }

        if (traveled < Math.abs(dxToTarget) + Math.max(0, overshootSlackPx)) {
            return towardDir; // the post-accel stop-out still fits: keep accelerating
        }
        // Too hot to keep pushing: counter-strafe while the slide carries meaningful speed,
        // else hold and let slipperyStopDir manage the residual glide.
        return counterStrafeBrakeDir(map, profile, hspeed);
    }

    /** True when this map's ground is slippery for bots WITHOUT snowshoes (fs &lt; 1). */
    static boolean slipperyGround(MapleMap map) {
        return mapGroundSlipScale(map) < 1.0;
    }

    private static double maxHForcePerClientStep(BotMovementProfile profile) {
        return profileOrBase(profile).hForcePxs() * CLIENT_GROUND_STEP_S;
    }

    private static double maxHSpeedPerClientStep(BotMovementProfile profile) {
        return maxHForcePerClientStep(profile) * cfg.GROUNDSLIP / (cfg.FRICTION + cfg.SLOPEFACTOR);
    }

    /** Profile walk speed in px/tick. There is NO walkSpeed cap in the air (CalcFloat
     *  @ 0x9b2c3c) — the observed |vx| <= walkSpeed is the ground cap carried in by the
     *  launch snap. Used to derive the airborne input band (walkSpeed/14 x fs). */
    private static double walkSpeedPerTick(BotMovementProfile profile) {
        return maxHSpeedPerClientStep(profile) * Math.max(1.0, cfg.TICK_MS / CLIENT_GROUND_STEP_MS);
    }

    /**
     * Ground momentum carried into the air when jumping with NO direction held, in air px/tick
     * units (packet-verified: standing/no-input jumps carry the current hspeed — 0 -> 0, 3 -> 3,
     * 9 -> 10, 29 -> 29 px/s — while a held direction snaps to +-walkSpeed instead).
     */
    static int carriedAirVelX(MapleMap map, BotEntry entry) {
        return (int) Math.round(tickDeltaFromGroundHSpeed(map, entry.hspeed, entry.movementProfile));
    }

    private static int maxHorizontalTravel(MapleMap map, BotMovementProfile profile, float launchSpeedPerTick) {
        int airtimeTicks = Math.max(1, (int) Math.ceil((2 * launchSpeedPerTick) / gravityPerTick()));
        return walkStep(map, profile) * airtimeTicks;
    }

    // Horizontal reach of a jump that is allowed to keep falling `dropPx` BELOW its launch height
    // before the move ends — e.g. grabbing a rope whose climbable span hangs below the ledge.
    // maxHorizontalTravel only counts the arc back to launch height; a rope that extends lower lets
    // the bot drift sideways through the entire descent, reaching meaningfully farther (this is the
    // common "stand by a ladder, jump and catch it" maneuver). Reduces to maxHorizontalTravel at
    // dropPx == 0.
    private static int maxHorizontalTravelWithDrop(MapleMap map, BotMovementProfile profile,
                                                   float launchSpeedPerTick, int dropPx) {
        float g = gravityPerTick();
        float tUp = launchSpeedPerTick / g;
        float apex = launchSpeedPerTick * launchSpeedPerTick / (2f * g);
        float tDown = (float) Math.sqrt(2f * (apex + Math.max(0, dropPx)) / g);
        int airtimeTicks = Math.max(1, (int) Math.ceil(tUp + tDown));
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

    /** Mover zMass sentinel: collide with EVERY wall regardless of group (legacy/conservative paths
     *  that predate the zMass model — mocked trees and no-region maps). */
    static final int MOVER_ZMASS_ALL = Integer.MIN_VALUE;
    /** Mover zMass sentinel: group unknown (fresh mover, never attached). Client inits CVecCtrl the
     *  same way (CUser::GetZMass returns -1) — only base-group walls collide. */
    static final int MOVER_ZMASS_UNKNOWN = -1;

    /**
     * Client wall-collidability rule (v83 CVecCtrl::CollisionDetectFloat @0x9b36bc, field names from
     * the v95 PDB): a vertical wall is collision-tested only when its m_lZMass equals the map's
     * m_nBaseZMass or the mover's current m_lZMass (the group of the foothold last stood on —
     * CVecCtrl::OnAttachedObjectChanged). Walls of unrelated structures are invisible, which is why
     * a floor mover passes 600020100's x=-1315 tower wall both walking and airborne (live-verified).
     * Walls with no parsed group (synthetic test maps) collide for everyone.
     */
    private static boolean wallCollidesForMover(int baseZMass, int wallZMass, int moverZMass) {
        if (moverZMass == MOVER_ZMASS_ALL || wallZMass < 0 || baseZMass < 0) {
            return true;
        }
        return wallZMass == baseZMass || wallZMass == moverZMass;
    }

    /** Build-time variant for the graph builder: does {@code wall} collide for a mover launching
     *  from region {@code from}? (Mover zMass = the launch region's foothold group.) */
    static boolean wallCollidesForLaunch(MapleMap map, Foothold wall, BotNavigationGraph.Region from) {
        int moverZMass = from == null || from.isRopeRegion || from.segments.isEmpty()
                ? MOVER_ZMASS_UNKNOWN
                : footholdZMass(map, from.segments.get(0).footholdId);
        return wallCollidesForMover(collisionIndex(map).baseZMass(), wall.getZMass(), moverZMass);
    }

    private static int footholdZMass(MapleMap map, int footholdId) {
        Foothold foothold = footholdsById(map).get(footholdId);
        return foothold == null ? MOVER_ZMASS_UNKNOWN : foothold.getZMass();
    }

    private static AirCollision findWallCollision(MapleMap map, Point previousPos, Point nextPos, int moverZMass) {
        return findWallCollision(map, previousPos, nextPos, false, moverZMass);
    }

    private static AirCollision findGroundWallCollision(MapleMap map, Point previousPos, Point nextPos, int moverZMass) {
        return findWallCollision(map, previousPos, nextPos, true, moverZMass);
    }

    private static AirCollision findWallCollision(MapleMap map,
                                                  Point previousPos,
                                                  Point nextPos,
                                                  boolean allowWalkableGroundEndpoint,
                                                  int moverZMass) {
        if (map == null || map.getFootholds() == null) {
            return AirCollision.none();
        }
        if (previousPos.x == nextPos.x) {
            return AirCollision.none();
        }

        FootholdCollisionIndex index = collisionIndex(map);
        AirCollision best = mapSideBoundaryCollision(map, previousPos, nextPos);
        for (Foothold foothold : index.walls()) {
            if (!wallCollidesForMover(index.baseZMass(), foothold.getZMass(), moverZMass)) {
                continue;
            }
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

    private record FootholdCollisionIndex(java.util.List<Foothold> walls,
                                          java.util.List<Foothold> collidableFromBelow,
                                          int baseZMass,
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
            java.util.List.of(), java.util.List.of(), -1, 0, new Foothold[0][]);

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
            // Client truth (v95 PDB CWvsPhysicalSpace2D::Load @ v83 0xa44c7c): m_nBaseZMass is the
            // smallest zMass group value that has footholds in the map. Airborne wall collision
            // (CollisionDetectFloat) tests a wall only when its zMass equals the base group or the
            // mover's current group — see wallCollidesForMover.
            int baseZMass = -1;
            for (Foothold fh : all) {
                if (fh.getZMass() >= 0 && (baseZMass < 0 || fh.getZMass() < baseZMass)) {
                    baseZMass = fh.getZMass();
                }
                if (fh.isWall()) {
                    walls.add(fh);
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
                    baseZMass, minX, buckets);
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

    // cos(alpha)/cos(beta) depend only on the foothold's endpoints, not the query x — cache them
    // per foothold instead of recomputing atan/cos on every probe (graphgen + the airborne
    // integrator issue tens of millions of these). Footholds never override equals/hashCode, so
    // identity keying is correct; WeakHashMap mirrors the COLLISION_INDEX lifetime pattern above.
    private static final java.util.Map<Foothold, double[]> SLOPE_COS_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    // Verbatim port of the foothold tree's slope interpolation — the trig chain reduces to
    // linear interpolation but is kept as-is so int truncation matches to the pixel.
    private static int slopeYAt(Foothold fh, int x) {
        double s4 = Math.abs(x - fh.getX1());
        double[] cosAlphaBeta = SLOPE_COS_CACHE.computeIfAbsent(fh, f -> {
            double s1 = Math.abs(f.getY2() - f.getY1());
            double s2 = Math.abs(f.getX2() - f.getX1());
            double alpha = Math.atan(s2 / s1);
            double beta = Math.atan(s1 / s2);
            return new double[] { Math.cos(alpha), Math.cos(beta) };
        });
        double s5 = cosAlphaBeta[0] * (s4 / cosAlphaBeta[1]);
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

        // Client landing rule (packet-verified at fs=1 AND fs=0.2 — logs/monitored-packets
        // -elnath-tricky-jumps-spd100v2.log, "monitored-packets - 100speedjumpmovement.log"):
        // touchdown HALVES the horizontal velocity (125 -> 62, -104 -> -52, 26 -> 13, 9 -> 4);
        // the walk/glide regime then takes over from that seed. (Counter-strafe landings zero
        // it outright — handled by the caller, which knows the input state.)
        landingDeltaX *= 0.5;

        // Do NOT clamp to walk speed: a fast landing legitimately exceeds it. A flash-jump dash
        // (~550 px/s) lands at ~275 and the force/drag ground integrator decays it (capture
        // monitored-packets-flashjumpspeed100jump100: 550->275 then 251->227->204...). The old
        // clamp to walkStep flattened that to walk speed -- the "stuttery 0-momentum" FJ landing.
        // Normal jumps land at/below walk (halved 125->62, -104->-52, ...), so only fast dashes
        // change; this is bit-identical for them (halved delta < walkStep => was never clamped).
        double stepsPerTick = Math.max(1.0, cfg.TICK_MS / CLIENT_GROUND_STEP_MS);
        return landingDeltaX / stepsPerTick;
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
        final int floorY = mapFloorY(map);
        final int moverZMass = moverZMassAt(map, from);

        for (int tick = 0; tick < FALL_SIM_TICK_CAP; tick++) {
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
                    new Point(x, intY), moverZMass);
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
                    && landingResolves(remainingLandingGraceMs == 0L, collision)) {
                return null;
            }

            if (intY > floorY) {
                return null; // fell past the map floor — no rope to grab below
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

    /** Coarse vertical pre-filter: can a bot at {@code botPos} plausibly reach {@code rope} to start
     *  climbing, given its direction of travel? {@code descending} = the bot needs a rope below it
     *  (drop onto it); otherwise it needs a rope above (jump up to it). Uses the same MAX_SNAP_DROP /
     *  MAX_SLOPE_UP gates as direct attachment. Whether the rope actually advances toward a goal is a
     *  selection concern that stays in the caller. */
    static boolean ropeWithinReach(Point botPos, Rope rope, boolean descending) {
        if (descending) {
            return rope.bottomY() > botPos.y + cfg.MAX_SNAP_DROP
                    && rope.topY() <= botPos.y + cfg.MAX_SLOPE_UP;
        }
        return rope.topY() < botPos.y - cfg.MAX_SNAP_DROP
                && rope.bottomY() >= botPos.y - cfg.MAX_SNAP_DROP;
    }

    /** A rope/ladder the bot could cling to at this exact point, or null. A point on a rope legitimately
     *  floats above the floor (you stand by climbing, not on a foothold) — so callers reasoning about
     *  "standable" must treat this as standable, not midair. */
    static Rope climbableAtPoint(MapleMap map, Point position) {
        if (map == null || position == null) {
            return null;
        }
        for (Rope rope : map.getRopes()) {
            if (canGrabRopeAtPoint(position, rope)) {
                return rope;
            }
        }
        return null;
    }

    /** A reachable climb point inside the (±dx,±dy) box around {@code center}, or null. Used to approach a
     *  collision portal whose warp point floats BESIDE a rope (common WZ layout): the portal fires on
     *  hitbox overlap, and a rope threading that box is a reachable surface the bot can climb to the
     *  portal's height — unlike the exact centre, which sits on no foothold. Returns the rope's x at the
     *  centre's height, clamped to the rope's climbable span. */
    static Point ropeApproachInBox(MapleMap map, Point center, int dx, int dy) {
        if (map == null || center == null) {
            return null;
        }
        for (Rope rope : map.getRopes()) {
            if (Math.abs(rope.x() - center.x) <= dx
                    && firstClimbableY(rope) <= center.y + dy
                    && rope.bottomY() >= center.y - dy) {
                int y = Math.clamp(center.y, firstClimbableY(rope), rope.bottomY());
                return new Point(rope.x(), y);
            }
        }
        return null;
    }

    /** A reachable standable/climbable point inside the (±dx,±dy) box around {@code center}, or null —
     *  the general form of {@link #ropeApproachInBox}. A collision portal fires on hitbox overlap, but its
     *  warp point can float onto no surface; pathfinding needs a real target inside the box. Prefers a rope
     *  (climb to height), else the nearest foothold platform whose surface the box overlaps (covers an
     *  offset platform, not just an offset rope). Returns the point nearest the centre's x. */
    static Point reachableApproachInBox(MapleMap map, Point center, int dx, int dy) {
        if (map == null || center == null) {
            return null;
        }
        Point rope = ropeApproachInBox(map, center, dx, dy);
        if (rope != null) {
            return rope;
        }
        Point best = null;
        long bestDx = Long.MAX_VALUE;
        for (int sx = -dx; sx <= dx; sx += 10) {        // sample columns across the box for a platform
            Point ground = findGroundPoint(map, new Point(center.x + sx, center.y - dy));
            if (ground == null || Math.abs(ground.y - center.y) > dy) {
                continue;                                // no floor, or floor is outside the box vertically
            }
            long d = (long) sx * sx;
            if (d < bestDx) {
                bestDx = d;
                best = new Point(center.x + sx, ground.y);
            }
        }
        return best;
    }

    private static JumpLanding simulateLanding(MapleMap map,
                                               Point from,
                                               float initialVelY,
                                               int stepX,
                                               long landingGraceMs) {
        return simulateLanding(map, from, initialVelY, stepX, landingGraceMs, null);
    }

    /** {@code flashImpulse} non-null models Flash Jump: a one-time apex (velocityY>=0) override of
     *  (hStep, velY) that mirrors the {@link #stepAirborne} injection, so generated FLASH_JUMP edges
     *  land where execution will actually fly. */
    private static JumpLanding simulateLanding(MapleMap map,
                                               Point from,
                                               float initialVelY,
                                               int stepX,
                                               long landingGraceMs,
                                               FlashImpulse flashImpulse) {
        float velocityY = initialVelY;
        double physX = from.x;
        double physY = from.y;
        int previousIntY = from.y;
        long remainingLandingGraceMs = Math.max(0L, landingGraceMs);
        final float gravity = gravityPerTick();
        final float maxFall = maxFallPerTick();
        final int floorY = mapFloorY(map);
        final int moverZMass = moverZMassAt(map, from);
        boolean flashInjected = false;

        for (int tick = 0; tick < FALL_SIM_TICK_CAP; tick++) {
            if (remainingLandingGraceMs > 0L) {
                remainingLandingGraceMs = Math.max(0L, remainingLandingGraceMs - cfg.TICK_MS);
            }

            if (flashImpulse != null && !flashInjected && velocityY >= 0f) {
                flashInjected = true;
                stepX = flashImpulse.hStep();
                velocityY = flashImpulse.velY();
            }

            physX += stepX;
            physY += velocityY + 0.5f * gravity;
            velocityY = Math.min(velocityY + gravity, maxFall);

            int x = (int) Math.round(physX);
            int intY = (int) Math.round(physY);
            Point previousPoint = new Point((int) Math.round(physX - stepX), previousIntY);
            Point nextPoint = new Point(x, intY);
            AirCollision collision = resolveAirCollision(map, previousPoint, nextPoint, moverZMass);
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
                    && landingResolves(remainingLandingGraceMs == 0L, collision)) {
                return new JumpLanding(collision.point(), collision.foothold(),
                        nextPoint.x - previousPoint.x, nextPoint.y - previousPoint.y, tick + 1);
            }

            if (intY > floorY) {
                return null; // fell past the map floor — no foothold exists below
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
