package server.bots;

import client.Character;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.*;
import java.util.ArrayList;
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
 *
 * <p>Two consumable hop kinds ride on top of portal hops, both planned by the world graph
 * and re-checked here at use time:
 * <ul>
 *   <li><b>Return scroll</b>: the next hop is this map's scroll-shortcut town and the bot
 *       carries a Nearest Town Scroll — use it on the spot, no walking.</li>
 *   <li><b>Taxi</b>: the next hop is a cab destination — walk to within
 *       {@link #TAXI_TRIGGER_RADIUS_PX} of the cab NPC, pay the fare, warp to portal 0,
 *       exactly what the NPC script charges a player.</li>
 * </ul>
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
    // tm of spawn points / doors (mirrors BotWorldGraph.NO_TARGET_MAPID): a positive sentinel, NOT
    // a real map — must be excluded from cross-map portal candidates or the wander targets dead ends.
    private static final int NO_DESTINATION_MAPID = 999999999;
    // enterPortal fired but the map change lands asynchronously; if it never lands the
    // portal was blocked (e.g. closed mid-walk) and the warp fallback takes over.
    private static final long PORTAL_LAND_GRACE_MS = 2_000L;
    // After a failed attempt, don't immediately retry the same doomed walk — warp directly
    // (the legacy behavior) for this long.
    private static final long GIVE_UP_WARP_WINDOW_MS = 45_000L;
    private static final long PORTAL_USE_COOLDOWN_MS = 250L; // matches BotNavigationManager
    // WZ portal type "pc" = collision portal: warps the instant the character's hitbox touches it
    // (pits that drop you to another map, rope-top transitions). Trigger box is a touch wider than the
    // intent-based enter tolerance so a knockback that lands the bot slightly off-centre still fires.
    private static final int COLLISION_PORTAL_TYPE = 3;
    private static final int COLLISION_ENTER_X = 30;
    private static final int COLLISION_ENTER_Y = 60;
    // Walk at most this many portal hops to reach the owner; anything farther warps. Keeps a
    // party bot from being minutes behind when the owner taxis across the world, while the
    // common "owner walked a couple of maps ahead" case stays fully legal.
    private static final int MAX_FOLLOW_TRAVEL_HOPS = 4;
    // The cab NPC doesn't need a precise approach — anywhere near it counts as "talked to it".
    private static final int TAXI_TRIGGER_RADIUS_PX = 500;

    // Test seams: stepMovementCore drags in the full physics/nav stack; the world graph's
    // default lookups trigger a WZ scan on first use; scroll/taxi/prewarm defaults touch
    // inventory, meso and the live map factory.
    @FunctionalInterface
    interface MovementStep {
        void step(BotEntry entry, Point targetPos, boolean runAiTick);
    }

    /** Fire-in-passing attack while walking a hop (a mob already in range), without diverting travel. */
    @FunctionalInterface
    interface EnRouteAttack {
        boolean attack(BotEntry entry, Character bot);
    }

    @FunctionalInterface
    interface RouteLookup {
        List<Integer> route(int fromMapId, int toMapId, int maxHops, BotWorldGraph.RouteOptions options);
    }

    @FunctionalInterface
    interface ScrollTargetLookup {
        int scrollTarget(int mapId);
    }

    @FunctionalInterface
    interface ReturnScrollUse {
        boolean use(Character bot);
    }

    @FunctionalInterface
    interface TaxiNpcLocator {
        Point locate(MapleMap map, int npcId);
    }

    @FunctionalInterface
    interface TaxiRide {
        boolean ride(Character bot, BotWorldGraph.TaxiEdge edge);
    }

    @FunctionalInterface
    interface RoutePrewarm {
        void prewarm(BotEntry entry, Character bot, List<Integer> route);
    }

    static MovementStep movementStep =
            (entry, targetPos, runAiTick) -> BotManager.getInstance().stepMovementCore(entry, targetPos, runAiTick);
    static EnRouteAttack enRouteAttack =
            (entry, bot) -> BotManager.getInstance().tryEnRouteOpportunityAttack(entry, bot);
    static RouteLookup routeLookup = BotWorldGraph::route;
    static ScrollTargetLookup scrollTargetLookup = mapId -> BotWorldGraph.get().scrollTarget(mapId);
    static java.util.function.ToIntFunction<Character> returnScrollCount = BotShopManager::countReturnScrolls;
    static ReturnScrollUse returnScrollUse = bot -> BotManager.getInstance().tryUseReturnScroll(bot);
    static TaxiNpcLocator taxiNpcLocator = (map, npcId) -> {
        var npc = map.getNPCById(npcId);
        return npc != null ? npc.getPosition() : null;
    };
    static TaxiRide taxiRide = (bot, edge) -> {
        if (bot.getMeso() < edge.fare()) {
            return false;
        }
        MapleMap dest = bot.getClient().getChannelServer().getMapFactory().getMap(edge.toMapId());
        if (dest == null) {
            return false;
        }
        bot.gainMeso(-edge.fare(), false);
        bot.changeMap(dest, dest.getPortal(0)); // cab scripts do cm.warp(dest, 0)
        return true;
    };
    static RoutePrewarm routePrewarm = BotTravelManager::prewarmRouteGraphs;

    // Look this many hops down a freshly-planned route and pre-warm those maps' nav graphs,
    // so the bot doesn't idle in graph-warmup fallback at every landing.
    private static final int ROUTE_PREWARM_MAPS = 3;

    /**
     * Default route prewarm: hand the next few hops to the nav-graph warmup executor.
     * MapManager.getMap loads the map from WZ on first touch, so nothing heavy runs here on
     * the tick thread. No client/channel (unit tests, disconnecting bot) — quietly skip.
     */
    private static void prewarmRouteGraphs(BotEntry entry, Character bot, List<Integer> route) {
        var client = bot.getClient();
        if (client == null || client.getChannelServer() == null) {
            return;
        }
        List<Integer> ahead = List.copyOf(route.subList(0, Math.min(ROUTE_PREWARM_MAPS, route.size())));
        BotNavigationGraphProvider.warmGraphsForRouteAsync(
                client.getChannelServer().getMapFactory(), ahead, entry.movementProfile);
    }

    private BotTravelManager() {}

    /**
     * Follow-mode wrapper: travel toward wherever the anchor currently is. No ferry hops —
     * a 15-minute boat ride is never the right way to catch up with a waiting owner, so
     * cross-sea follows keep the legacy warp fallback.
     */
    static boolean tickFollowTravel(BotEntry entry, Character bot, Character anchor, boolean runAiTick) {
        return tickTravel(entry, bot, anchor.getMapId(), MAX_FOLLOW_TRAVEL_HOPS, runAiTick, false);
    }

    /**
     * One tick of portal travel toward a target map. Returns true when the tick is consumed
     * (walking toward / entering / waiting on a portal); false when no legal progress can be
     * made right now (no route within maxHops, no live portal, walk failed/timed out) — the
     * caller decides the fallback (follow warps; autopilot waits or re-decides).
     */
    static boolean tickTravel(BotEntry entry, Character bot, int targetMapId, int maxHops,
                              boolean runAiTick, boolean allowFerry) {
        long now = System.currentTimeMillis();
        MapleMap map = bot.getMap();
        if (map == null) {
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
        // On a ferry the boat decides everything (even past give-up windows): wait it out,
        // hide from Balrogs in the cabin, follow the owner in/out of it.
        if (BotFerryManager.tickTransit(entry, bot, targetMapId, runAiTick)) {
            return true;
        }
        if (now < entry.followTravelGiveUpUntilMs) {
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
            giveUp(entry, now, "deadline");
            return false;
        }
        if (active && entry.followTravelEnteredAtMs > 0) {
            if (now - entry.followTravelEnteredAtMs > PORTAL_LAND_GRACE_MS) {
                giveUp(entry, now, "warp-no-land");
                return false;
            }
            return true; // warp is in flight — hold still
        }

        Portal portal;
        if (active) {
            if (entry.followTravelFerry) {
                BotFerryManager.FerryRoute ferry =
                        BotFerryManager.findFerryEdge(bot.getMapId(), entry.followTravelNextHopMapId);
                if (ferry == null || !BotFerryManager.tickBoarding(entry, bot, ferry, now, runAiTick)) {
                    giveUp(entry, now, "ferry-board-fail");
                    return false;
                }
                return true;
            }
            if (entry.followTravelTaxiNpcId != 0) {
                return tickTaxiHop(entry, bot, now, runAiTick);
            }
            portal = map.getPortal(entry.followTravelPortalId);
            if (portal == null || !portal.getPortalStatus()) {
                giveUp(entry, now, "portal-closed"); // our portal closed mid-walk
                return false;
            }
        } else {
            // Direct hop when the owner's map is adjacent; otherwise take the first hop of the
            // shortest world-graph route. Each landing re-plans, so only the next hop matters.
            int nextHopMapId = targetMapId;
            portal = findAdjacentPortal(map.getPortals(), targetMapId, bot.getPosition());
            if (portal == null) {
                BotWorldGraph.RouteOptions options = new BotWorldGraph.RouteOptions(
                        returnScrollCount.applyAsInt(bot) > 0, bot.getMeso(), allowFerry);
                List<Integer> route = routeLookup.route(bot.getMapId(), targetMapId, maxHops, options);
                if (route == null || route.isEmpty()) {
                    return false; // too far or unreachable by walking — warp fallback
                }
                routePrewarm.prewarm(entry, bot, route);
                nextHopMapId = route.get(0);
                portal = findAdjacentPortal(map.getPortals(), nextHopMapId, bot.getPosition());
                if (portal == null) {
                    return tryConsumableHop(entry, bot, map, targetMapId, nextHopMapId, now, runAiTick);
                }
            }
            entry.followTravelTargetMapId = targetMapId;
            entry.followTravelNextHopMapId = nextHopMapId;
            entry.followTravelFromMapId = bot.getMapId();
            entry.followTravelPortalId = portal.getId();
            entry.followTravelBestDist = Integer.MAX_VALUE; // fresh hop — first walk tick seeds progress
            entry.followTravelDeadlineMs = now + travelBudgetMs(manhattan(bot.getPosition(), portal.getPosition()));
        }

        // Progress-aware deadline: while the bot is still closing on the portal (a long multi-jump
        // climb counts), push the give-up deadline out. Only NET progress (a new closest distance)
        // resets it, so a bot that's genuinely stuck or oscillating in place still times out.
        int distToPortal = manhattan(bot.getPosition(), portal.getPosition());
        if (distToPortal < entry.followTravelBestDist) {
            entry.followTravelBestDist = distToPortal;
            entry.followTravelDeadlineMs = now + travelBudgetMs(distToPortal);
        }
        return walkToPortalAndEnter(entry, bot, portal, now, runAiTick);
    }

    /**
     * Walk toward a plain portal and enter it once in range — the shared walk-and-enter step
     * used by the main hop flow above and the ferry legs (cabin door, station walkway).
     */
    static boolean walkToPortalAndEnter(BotEntry entry, Character bot, Portal portal, long now, boolean runAiTick) {
        Point portalPos = portal.getPosition();
        Point botPos = bot.getPosition();
        // A portal at the top of (or on) a rope is only reachable by climbing - the bot arrives in the
        // climbing state, so blocking entry while climbing strands it hanging at the portal forever
        // (e.g. Henesys Hunting Ground I->II->III). Allow entry while climbing; still block mid-jump/fall
        // (inAir) so the bot doesn't trigger a portal it's only passing through. The X/Y tolerance below
        // ensures this only fires once actually at the portal.
        if (!entry.inAir
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
        // Opportunity attack on the way: only fires at a mob already in range (no chase/divert),
        // so the bot picks off mobs blocking its path while still walking to the portal.
        if (runAiTick) {
            enRouteAttack.attack(entry, bot);
        }
        movementStep.step(entry, portalPos, runAiTick);
        return true;
    }

    /**
     * Collision ("pc", WZ pt=3) portals auto-warp a real player the instant their hitbox overlaps them
     * — the CLIENT detects the collision and asks to change map; a bot has no client, so the server
     * never fires it and the bot just sits in the pit / on the rope. Emulate it here every tick,
     * INTENT-INDEPENDENT: whether the bot walked/climbed onto it or got KNOCKED into a pit, overlapping
     * a type-3 portal warps it. Plain warp portals only (skip scripted ones — those may gate/dialog).
     * Called from the common tick so it runs in every mode (grind, idle, follow, dead-knockback).
     */
    static boolean tickCollisionPortal(BotEntry entry, Character bot) {
        long now = System.currentTimeMillis();
        if (bot == null || now < entry.portalUseCooldownUntilMs) {
            return false;
        }
        MapleMap map = bot.getMap();
        Point pos = bot.getPosition();
        if (map == null || pos == null) {
            return false;
        }
        for (Portal portal : map.getPortals()) {
            String script = portal.getScriptName();
            if (portal.getType() != COLLISION_PORTAL_TYPE
                    || portal.getTargetMapId() == NO_DESTINATION_MAPID
                    || !portal.getPortalStatus()
                    || (script != null && !script.isEmpty())) {
                continue;
            }
            Point pp = portal.getPosition();
            if (Math.abs(pos.x - pp.x) <= COLLISION_ENTER_X && Math.abs(pos.y - pp.y) <= COLLISION_ENTER_Y) {
                entry.portalUseCooldownUntilMs = now + PORTAL_USE_COOLDOWN_MS;
                portal.enterPortal(bot.getClient());
                return true;
            }
        }
        return false;
    }

    /**
     * The route's next hop has no walkable portal — it came from a consumable graph edge.
     * Scroll hops fire on the spot; taxi hops start a walk toward the cab NPC. Returns false
     * (caller falls back) when neither applies after the use-time re-checks.
     */
    private static boolean tryConsumableHop(BotEntry entry, Character bot, MapleMap map,
                                            int targetMapId, int nextHopMapId, long now, boolean runAiTick) {
        if (nextHopMapId == scrollTargetLookup.scrollTarget(bot.getMapId())
                && returnScrollCount.applyAsInt(bot) > 0) {
            if (!returnScrollUse.use(bot)) {
                return false;
            }
            entry.followTravelTargetMapId = targetMapId;
            entry.followTravelNextHopMapId = nextHopMapId;
            entry.followTravelFromMapId = bot.getMapId();
            entry.followTravelPortalId = -1;
            entry.followTravelDeadlineMs = now + PORTAL_LAND_GRACE_MS;
            entry.followTravelEnteredAtMs = now;
            return true;
        }
        BotWorldGraph.TaxiEdge taxi = BotWorldGraph.findTaxiEdge(bot.getMapId(), nextHopMapId);
        if (taxi != null && bot.getMeso() >= taxi.fare()) {
            Point npcPos = taxiNpcLocator.locate(map, taxi.npcId());
            if (npcPos == null) {
                return false; // cab NPC missing from the live map — warp fallback
            }
            entry.followTravelTargetMapId = targetMapId;
            entry.followTravelNextHopMapId = nextHopMapId;
            entry.followTravelFromMapId = bot.getMapId();
            entry.followTravelPortalId = -1;
            entry.followTravelTaxiNpcId = taxi.npcId();
            entry.followTravelTaxiPos = new Point(npcPos);
            entry.followTravelDeadlineMs = now + travelBudgetMs(manhattan(bot.getPosition(), npcPos));
            return tickTaxiHop(entry, bot, now, runAiTick);
        }
        BotFerryManager.FerryRoute ferry = BotFerryManager.findFerryEdge(bot.getMapId(), nextHopMapId);
        if (ferry != null && BotFerryManager.tickBoarding(entry, bot, ferry, now, runAiTick)) {
            entry.followTravelTargetMapId = targetMapId;
            entry.followTravelNextHopMapId = nextHopMapId;
            entry.followTravelFromMapId = bot.getMapId();
            entry.followTravelPortalId = -1;
            entry.followTravelFerry = true;
            // Per-leg budget; map changes re-plan with a fresh one, legitimate waits at the
            // closed gate re-arm it from inside tickBoarding.
            entry.followTravelDeadlineMs = now + TRAVEL_BUDGET_MAX_MS;
            return true;
        }
        return false;
    }

    /** Walk toward the cab NPC; once close enough, pay and ride. */
    private static boolean tickTaxiHop(BotEntry entry, Character bot, long now, boolean runAiTick) {
        Point npcPos = entry.followTravelTaxiPos;
        Point botPos = bot.getPosition();
        if (npcPos == null) {
            giveUp(entry, now, "taxi-npc-missing");
            return false;
        }
        if (!entry.inAir && !entry.climbing && manhattan(botPos, npcPos) <= TAXI_TRIGGER_RADIUS_PX) {
            BotWorldGraph.TaxiEdge taxi =
                    BotWorldGraph.findTaxiEdge(entry.followTravelFromMapId, entry.followTravelNextHopMapId);
            clearMoveTargetPin(entry);
            if (taxi == null || !taxiRide.ride(bot, taxi)) {
                giveUp(entry, now, "taxi-fare-fail"); // fare spent elsewhere mid-walk — don't retry the same hop
                return false;
            }
            entry.followTravelEnteredAtMs = now;
            return true;
        }
        pinMoveTarget(entry, npcPos);
        movementStep.step(entry, npcPos, runAiTick);
        return true;
    }

    /**
     * Nearest open, unscripted, non-door portal leading directly to targetMapId; null when
     * the map has no such portal (multi-hop or unreachable — caller warps). Scripted portals
     * are skipped because their scripts can gate on quests/items and silently no-op or warp
     * somewhere else entirely.
     */
    /**
     * The position of the walkable portal this map's next hop toward {@code targetMapId} would
     * enter — the same portal {@link #tickTravel} resolves, minus any walking or entering. Used
     * by party cohesion to loiter the holding leader AT that portal instead of grind-wandering
     * the whole map, so the group reassembles there and hops together. Returns null when the
     * next hop isn't a plain walkable portal (consumable scroll / taxi / ferry, or no route /
     * no live portal) — the caller then just holds the map and grinds normally.
     */
    static Point nextHopPortalPosition(BotEntry entry, Character bot, int targetMapId, int maxHops) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return null;
        }
        Point botPos = bot.getPosition();
        Portal portal = findAdjacentPortal(map.getPortals(), targetMapId, botPos);
        if (portal == null) {
            BotWorldGraph.RouteOptions options = new BotWorldGraph.RouteOptions(
                    returnScrollCount.applyAsInt(bot) > 0, bot.getMeso(), false);
            List<Integer> route = routeLookup.route(bot.getMapId(), targetMapId, maxHops, options);
            if (route == null || route.isEmpty()) {
                return null;
            }
            portal = findAdjacentPortal(map.getPortals(), route.get(0), botPos);
            if (portal == null) {
                return null; // next hop is a scroll/taxi/ferry leg — nothing to stand next to
            }
        }
        return portal.getPosition();
    }

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

    /**
     * Pick a random open, unscripted, non-door portal that leads to a real other map — the same
     * legality filters {@link #findAdjacentPortal} uses, minus the fixed target and nearest bias.
     * Returns null when the map has no such portal. Random (not nearest) so a stranded autopilot
     * bot doesn't get pinned to the same dead-end portal every time; the {@code rng} seam lets
     * tests assert the choice isn't biased to the first/nearest portal.
     */
    static Portal pickRandomCrossMapPortal(Collection<Portal> portals, int currentMapId, java.util.Random rng) {
        List<Portal> eligible = new ArrayList<>();
        for (Portal portal : portals) {
            int target = portal.getTargetMapId();
            if (target <= 0
                    || target == NO_DESTINATION_MAPID
                    || target == currentMapId
                    || !portal.getPortalStatus()
                    || portal.getType() == Portal.DOOR_PORTAL
                    || (portal.getScriptName() != null && !portal.getScriptName().isEmpty())) {
                continue;
            }
            eligible.add(portal);
        }
        if (eligible.isEmpty()) {
            return null;
        }
        return eligible.get(rng.nextInt(eligible.size()));
    }

    private static final java.util.Random WANDER_RNG = new java.util.Random();

    /**
     * Stranded-autopilot fallback: no legal travel progress and not on the destination map, so
     * walk to a random legal cross-map portal and take it — autopilot re-plans from wherever it
     * lands. Pins the chosen portal in {@code followTravelPortalId} so the bot commits to one
     * portal instead of re-jittering every tick. Returns false (caller keeps grinding here) when
     * the map has no usable cross-map portal at all.
     */
    static boolean tickWanderToRandomPortal(BotEntry entry, Character bot, boolean runAiTick) {
        MapleMap map = bot.getMap();
        if (map == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Portal portal = entry.followTravelPortalId > 0 ? map.getPortal(entry.followTravelPortalId) : null;
        if (portal == null || !portal.getPortalStatus() || portal.getTargetMapId() == bot.getMapId()) {
            portal = pickRandomCrossMapPortal(map.getPortals(), bot.getMapId(), WANDER_RNG);
            if (portal == null) {
                entry.followTravelPortalId = -1;
                return false;
            }
            entry.followTravelPortalId = portal.getId();
        }
        return walkToPortalAndEnter(entry, bot, portal, now, runAiTick);
    }

    static void clear(BotEntry entry) {
        clearMoveTargetPin(entry);
        entry.followTravelTargetMapId = -1;
        entry.followTravelNextHopMapId = -1;
        entry.followTravelPortalId = -1;
        entry.followTravelFromMapId = -1;
        entry.followTravelDeadlineMs = 0L;
        entry.followTravelEnteredAtMs = 0L;
        entry.followTravelBestDist = Integer.MAX_VALUE;
        entry.followTravelTaxiNpcId = 0;
        entry.followTravelTaxiPos = null;
        entry.followTravelFerry = false;
        // NOTE: followTravelGiveUpUntilMs is intentionally NOT reset here — the internal retry loop
        // calls clear() every tick during the give-up window and must keep that cooldown. Deliberate
        // mode changes use resetForModeChange() to also drop the cooldown.
    }

    /**
     * Full reset for a deliberate owner/mode change (follow / grind / move-here / stop): clears the
     * in-flight hop AND the give-up cooldown. Without this a stale 45s give-up window silently gates
     * the bot's travel even after it's been re-commanded, so follow/move-here appear to "do nothing"
     * (the stuck-state bug). Routed through BotAutopilotManager.clear(), the single mode-change hub.
     */
    static void resetForModeChange(BotEntry entry) {
        clear(entry);
        entry.followTravelGiveUpUntilMs = 0L;
        entry.followTravelGiveUpReason = null;
    }

    private static void giveUp(BotEntry entry, long now, String reason) {
        clear(entry);
        entry.followTravelGiveUpUntilMs = now + GIVE_UP_WARP_WINDOW_MS;
        entry.followTravelGiveUpReason = reason;
    }

    /** Walk budget = give-up window for reaching a portal/NPC, scaled by manhattan distance. */
    private static long travelBudgetMs(int manhattanDist) {
        return Math.min(TRAVEL_BUDGET_MAX_MS, TRAVEL_BUDGET_BASE_MS + TRAVEL_BUDGET_PER_PX_MS * manhattanDist);
    }

    /** Outcome of one {@link #tickApproachNpc} step. */
    enum ApproachStatus {
        TRAVELING,      // still hopping toward the NPC's map (tick consumed)
        TRAVEL_YIELDED, // travel gave up this tick (give-up window) — tick NOT consumed, let grind resume
        WALKING,        // on the NPC's map, walking within radius (tick consumed)
        ARRIVED,        // within radius of the NPC this tick (caller acts; tick NOT consumed)
        NPC_GONE        // NPC isn't on the resolved map (caller aborts; tick NOT consumed)
    }

    /**
     * Shared "travel to a map, then walk within {@code radiusPx} of an NPC" stepper, the SSOT
     * for the errand approach loop both {@link BotQuestManager#tickErrand} and
     * {@link BotStarterKitManager#tickJobErrand} drive. Pure routing/positioning — it does NOT
     * decide WHAT to do at the NPC (the caller acts on {@link ApproachStatus#ARRIVED}) nor own any
     * timeout/announce (caller's concern). Reuses {@link #tickTravel} for cross-map hops and
     * {@link #movementStep}/{@link #pinMoveTarget} for the on-map walk — no reimplemented travel.
     */
    static ApproachStatus tickApproachNpc(BotEntry entry, Character bot, int targetMapId, int npcId,
                                          int maxHops, boolean runAiTick, int radiusPx) {
        if (bot.getMapId() != targetMapId) {
            // Propagate tickTravel's verdict: it returns false in its give-up window (no movement for
            // up to ~45s), and the caller must release the tick then so the bot grinds instead of
            // standing frozen until the errand's own timeout. (Pre-extraction tickErrand returned this.)
            boolean moved = tickTravel(entry, bot, targetMapId, maxHops, runAiTick, false);
            return moved ? ApproachStatus.TRAVELING : ApproachStatus.TRAVEL_YIELDED;
        }
        server.life.NPC npc = bot.getMap() == null ? null : bot.getMap().getNPCById(npcId);
        if (npc == null || npc.getPosition() == null) {
            return ApproachStatus.NPC_GONE;
        }
        Point npcPos = npc.getPosition();
        Point botPos = bot.getPosition();
        if (!entry.inAir && !entry.climbing && manhattan(botPos, npcPos) <= radiusPx) {
            clearMoveTargetPin(entry);
            return ApproachStatus.ARRIVED;
        }
        pinMoveTarget(entry, npcPos);
        movementStep.step(entry, npcPos, runAiTick);
        return ApproachStatus.WALKING;
    }

    // moveTarget makes the movement stack treat the portal as a precise destination (exact
    // approach, stuck detection, fidget suppression). Pin/clear by instance identity so a
    // moveTarget issued by a player command is never clobbered.
    static void pinMoveTarget(BotEntry entry, Point portalPos) {
        if (entry.moveTarget != null && entry.moveTarget == entry.followTravelMoveTarget
                && entry.moveTarget.equals(portalPos)) {
            return;
        }
        entry.followTravelMoveTarget = new Point(portalPos);
        entry.moveTarget = entry.followTravelMoveTarget;
        entry.moveTargetPrecise = true;
        entry.moveTargetSource = "travel-pin";
    }

    static void clearMoveTargetPin(BotEntry entry) {
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
