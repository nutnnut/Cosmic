package server.bots;

import client.Character;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * Follow-mode cross-map travel: when the owner is within a few portal hops, walk to the
 * next portal on the route and enter it legally — exactly what a trailing player would do —
 * instead of warping straight to the owner. Routes come from {@link BotWorldGraph}; each hop
 * reuses the same walk-and-enter machinery, re-planning from whatever map the bot lands in.
 * Routes beyond {@link #MAX_FOLLOW_TRAVEL_HOPS}, maps with no usable portal (boat rides,
 * scripted gates), and walks that fail or time out all fall back to the legacy warp in
 * {@code BotManager.syncFollowMap}.
 */
final class BotTravelManager {

    // Enter readiness mirrors BotNavigationManager's PORTAL edge readiness
    // (EDGE_READY_X_TOLERANCE / JUMP_Y_THRESH * 2).
    private static final int ENTER_X_TOLERANCE = 14;
    private static final int ENTER_Y_TOLERANCE = 60;
    // Walk budget scales with distance to the portal (climb/jump detours make straight-line
    // estimates optimistic), then the bot stops being stubborn and warps.
    private static final long TRAVEL_BUDGET_BASE_MS = 10_000L;
    private static final long TRAVEL_BUDGET_PER_PX_MS = 15L;
    private static final long TRAVEL_BUDGET_MAX_MS = 45_000L;
    // enterPortal fired but the map change lands asynchronously; if it never lands the
    // portal was blocked (e.g. closed mid-walk) and the warp fallback takes over.
    private static final long PORTAL_LAND_GRACE_MS = 2_000L;
    // After a failed attempt, don't immediately retry the same doomed walk — warp directly
    // (the legacy behavior) for this long.
    private static final long GIVE_UP_WARP_WINDOW_MS = 45_000L;
    private static final long PORTAL_USE_COOLDOWN_MS = 250L; // matches BotNavigationManager
    // Walk at most this many portal hops to reach the owner; anything farther warps. Keeps a
    // party bot from being minutes behind when the owner taxis across the world, while the
    // common "owner walked a couple of maps ahead" case stays fully legal.
    private static final int MAX_FOLLOW_TRAVEL_HOPS = 4;

    // Test seams: stepMovementCore drags in the full physics/nav stack; the world graph's
    // default lookup triggers a WZ scan on first use.
    @FunctionalInterface
    interface MovementStep {
        void step(BotEntry entry, Point targetPos, boolean runAiTick);
    }

    @FunctionalInterface
    interface RouteLookup {
        List<Integer> route(int fromMapId, int toMapId, int maxHops);
    }

    static MovementStep movementStep =
            (entry, targetPos, runAiTick) -> BotManager.getInstance().stepMovementCore(entry, targetPos, runAiTick);
    static RouteLookup routeLookup = BotWorldGraph::route;

    private BotTravelManager() {}

    /** Follow-mode wrapper: travel toward wherever the anchor currently is. */
    static boolean tickFollowTravel(BotEntry entry, Character bot, Character anchor, boolean runAiTick) {
        return tickTravel(entry, bot, anchor.getMapId(), MAX_FOLLOW_TRAVEL_HOPS, runAiTick);
    }

    /**
     * One tick of portal travel toward a target map. Returns true when the tick is consumed
     * (walking toward / entering / waiting on a portal); false when no legal progress can be
     * made right now (no route within maxHops, no live portal, walk failed/timed out) — the
     * caller decides the fallback (follow warps; autopilot waits or re-decides).
     */
    static boolean tickTravel(BotEntry entry, Character bot, int targetMapId, int maxHops, boolean runAiTick) {
        long now = System.currentTimeMillis();
        MapleMap map = bot.getMap();
        if (map == null || now < entry.followTravelGiveUpUntilMs) {
            clear(entry);
            return false;
        }
        // Map just changed and the map-change tick (foothold rebuild, physics reset) hasn't
        // run yet — don't drive movement on stale footholds. Landing in the target map is
        // handled by syncFollowMap before this is called, so reaching here mid-change means
        // the portal dropped us somewhere unexpected: let the warp fallback recover.
        if (entry.lastMapId != bot.getMapId()) {
            clear(entry);
            return false;
        }

        boolean active = entry.followTravelTargetMapId != -1;
        if (active && (entry.followTravelTargetMapId != targetMapId
                || entry.followTravelFromMapId != bot.getMapId())) {
            // Owner moved on to another map (or we landed off-plan) — re-plan from here.
            clear(entry);
            active = false;
        }
        if (active && now > entry.followTravelDeadlineMs) {
            giveUp(entry, now);
            return false;
        }
        if (active && entry.followTravelEnteredAtMs > 0) {
            if (now - entry.followTravelEnteredAtMs > PORTAL_LAND_GRACE_MS) {
                giveUp(entry, now);
                return false;
            }
            return true; // warp is in flight — hold still
        }

        Portal portal;
        if (active) {
            portal = map.getPortal(entry.followTravelPortalId);
            if (portal == null || !portal.getPortalStatus()) {
                giveUp(entry, now); // our portal closed mid-walk
                return false;
            }
        } else {
            // Direct hop when the owner's map is adjacent; otherwise take the first hop of the
            // shortest world-graph route. Each landing re-plans, so only the next hop matters.
            int nextHopMapId = targetMapId;
            portal = findAdjacentPortal(map.getPortals(), targetMapId, bot.getPosition());
            if (portal == null) {
                List<Integer> route = routeLookup.route(bot.getMapId(), targetMapId, maxHops);
                if (route == null || route.isEmpty()) {
                    return false; // too far or unreachable by walking — warp fallback
                }
                nextHopMapId = route.get(0);
                portal = findAdjacentPortal(map.getPortals(), nextHopMapId, bot.getPosition());
                if (portal == null) {
                    return false; // graph edge exists but no live usable portal (closed/scripted at runtime)
                }
            }
            entry.followTravelTargetMapId = targetMapId;
            entry.followTravelNextHopMapId = nextHopMapId;
            entry.followTravelFromMapId = bot.getMapId();
            entry.followTravelPortalId = portal.getId();
            long budget = Math.min(TRAVEL_BUDGET_MAX_MS, TRAVEL_BUDGET_BASE_MS
                    + TRAVEL_BUDGET_PER_PX_MS * manhattan(bot.getPosition(), portal.getPosition()));
            entry.followTravelDeadlineMs = now + budget;
        }

        Point portalPos = portal.getPosition();
        Point botPos = bot.getPosition();
        if (!entry.inAir && !entry.climbing
                && Math.abs(botPos.x - portalPos.x) <= ENTER_X_TOLERANCE
                && Math.abs(botPos.y - portalPos.y) <= ENTER_Y_TOLERANCE) {
            if (now < entry.portalUseCooldownUntilMs) {
                return true; // brief breather between portals, same as nav portal edges
            }
            clearMoveTargetPin(entry);
            entry.followTravelEnteredAtMs = now;
            entry.portalUseCooldownUntilMs = now + PORTAL_USE_COOLDOWN_MS;
            portal.enterPortal(bot.getClient());
            return true;
        }

        pinMoveTarget(entry, portalPos);
        movementStep.step(entry, portalPos, runAiTick);
        return true;
    }

    /**
     * Nearest open, unscripted, non-door portal leading directly to targetMapId; null when
     * the map has no such portal (multi-hop or unreachable — caller warps). Scripted portals
     * are skipped because their scripts can gate on quests/items and silently no-op or warp
     * somewhere else entirely.
     */
    static Portal findAdjacentPortal(Collection<Portal> portals, int targetMapId, Point fromPos) {
        Portal best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Portal portal : portals) {
            if (portal.getTargetMapId() != targetMapId
                    || !portal.getPortalStatus()
                    || portal.getType() == Portal.DOOR_PORTAL
                    || (portal.getScriptName() != null && !portal.getScriptName().isEmpty())) {
                continue;
            }
            int dist = manhattan(fromPos, portal.getPosition());
            if (dist < bestDist) {
                bestDist = dist;
                best = portal;
            }
        }
        return best;
    }

    static void clear(BotEntry entry) {
        clearMoveTargetPin(entry);
        entry.followTravelTargetMapId = -1;
        entry.followTravelNextHopMapId = -1;
        entry.followTravelPortalId = -1;
        entry.followTravelFromMapId = -1;
        entry.followTravelDeadlineMs = 0L;
        entry.followTravelEnteredAtMs = 0L;
    }

    private static void giveUp(BotEntry entry, long now) {
        clear(entry);
        entry.followTravelGiveUpUntilMs = now + GIVE_UP_WARP_WINDOW_MS;
    }

    // moveTarget makes the movement stack treat the portal as a precise destination (exact
    // approach, stuck detection, fidget suppression). Pin/clear by instance identity so a
    // moveTarget issued by a player command is never clobbered.
    private static void pinMoveTarget(BotEntry entry, Point portalPos) {
        if (entry.moveTarget != null && entry.moveTarget == entry.followTravelMoveTarget
                && entry.moveTarget.equals(portalPos)) {
            return;
        }
        entry.followTravelMoveTarget = new Point(portalPos);
        entry.moveTarget = entry.followTravelMoveTarget;
        entry.moveTargetPrecise = true;
    }

    private static void clearMoveTargetPin(BotEntry entry) {
        if (entry.moveTarget != null && entry.moveTarget == entry.followTravelMoveTarget) {
            entry.moveTarget = null;
            entry.moveTargetPrecise = false;
        }
        entry.followTravelMoveTarget = null;
    }

    private static int manhattan(Point a, Point b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }
}
