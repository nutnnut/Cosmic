package server.bots;

import client.Character;
import constants.id.MapId;
import server.maps.Foothold;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

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
    // Pre-warp pause at the portal (humanlike): keep nudging toward the portal centre while it
    // elapses, so the bot steps through from the middle, not the tolerance edge. 0.5s + up to 1s.
    private static final int PORTAL_ENTER_DELAY_MIN_MS = 500;
    private static final int PORTAL_ENTER_DELAY_MAX_MS = 1_500;
    // Walk budget scales with distance to the portal (climb/jump detours make straight-line
    // estimates optimistic), then the bot stops being stubborn and warps.
    private static final long TRAVEL_BUDGET_BASE_MS = 10_000L;
    private static final long TRAVEL_BUDGET_PER_PX_MS = 15L;
    private static final long TRAVEL_BUDGET_MAX_MS = 45_000L;
    private static final int TRAVEL_BUDGET_ROUTE_COST_MULTIPLIER = 2;
    private static final int TRAVEL_PROGRESS_MOVE_PX = 24;
    // tm of spawn points / doors (mirrors BotWorldGraph.NO_TARGET_MAPID): a positive sentinel, NOT
    // a real map — must be excluded from cross-map portal candidates or the wander targets dead ends.
    private static final int NO_DESTINATION_MAPID = 999999999;
    // enterPortal fired but the map change lands asynchronously; if it never lands the
    // portal was blocked (e.g. closed mid-walk) and the warp fallback takes over.
    private static final long PORTAL_LAND_GRACE_MS = 2_000L;
    private static final long ROUTE_RECHECK_INTERVAL_MS = 1_000L;
    // After a failed attempt, don't immediately retry the same doomed walk — warp directly
    // (the legacy behavior) for this long.
    private static final long GIVE_UP_WARP_WINDOW_MS = 45_000L;
    private static final long PORTAL_USE_COOLDOWN_MS = 250L; // matches BotNavigationManager
    // Collision portals warp the instant the client sees the character's position inside their trigger
    // box. v83 client truth (CUserLocal::CheckPortal_Collision @0x94dac6 -> CPortalList::FindPortal_
    // Collision @0x712b61): the check runs every update with no ground/air/swim gate, over portal types
    // 3 ("pc", plain warp via tm/tn) and 9 ("pcs", touch fires the portal script — e.g. the undodraco
    // floor strip of the Leafre<->Temple flight maps); the box is pos ± hRange/2 x ± vRange/2 with WZ
    // defaults hRange = vRange = 100 (PORTAL.nHRange/nVRange). The server-side Portal never loads
    // hRange/vRange, so the default half-box applies to every portal.
    private static final int COLLISION_PORTAL_TYPE = 3;
    private static final int SCRIPTED_COLLISION_PORTAL_TYPE = 9;
    private static final int COLLISION_ENTER_X = 50;
    private static final int COLLISION_ENTER_Y = 50;
    // Walk at most this many portal hops to reach the owner; anything farther warps. Keeps a
    // party bot from being minutes behind when the owner taxis across the world, while the
    // common "owner walked a couple of maps ahead" case stays fully legal.
    private static final int MAX_FOLLOW_TRAVEL_HOPS = 4;
    // The cab NPC doesn't need a precise approach — anywhere near it counts as "talked to it".
    private static final int TAXI_TRIGGER_RADIUS_PX = 500;
    // Spread radius for the shared "pick a reachable spot near a target" de-stacker: sample footholds
    // within this manhattan distance of the NPC. Kept small so bots cluster around the NPC but on
    // distinct, reachable ground rather than piling on the exact (often off-floor) sprite pixel.
    static final int APPROACH_SPREAD_PX = 150;

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
        List<Integer> route(int fromMapId, int toMapId, int maxHops, BotWorldGraph.RouteOptions options,
                            java.util.function.IntPredicate blocked);
    }

    @FunctionalInterface
    interface PartitionRouteLookup {
        List<BotWorldPartitionRouter.Node> route(
                java.util.function.IntFunction<BotMapPartition> partitionProvider,
                int fromMapId,
                List<BotMapPartition.PortalRef> startExits,
                int toMapId,
                int maxHops,
                java.util.function.IntPredicate blocked);
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
            (entry, targetPos, runAiTick) -> {
                if (!BotManager.getInstance().recoverTeleportDistance(entry, entry.bot, targetPos)) {
                    BotManager.getInstance().stepMovementCore(entry, targetPos, runAiTick);
                }
            };
    static EnRouteAttack enRouteAttack =
            (entry, bot) -> BotManager.getInstance().tryEnRouteOpportunityAttack(entry, bot);
    static RouteLookup routeLookup = BotWorldGraph::route;
    static PartitionRouteLookup partitionRouteLookup = BotWorldPartitionRouter::route;
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
        int destMapId = edge.toMapId();
        boolean spinel = edge.npcId() == 9000020;
        boolean shrineReturn = spinel && bot.getMapId() == BotWorldGraph.MUSHROOM_SHRINE;
        if (shrineReturn) {
            // Spinel sends the bot back to where it boarded (saved WORLDTOUR), consuming it — exactly
            // what the script does. No saved origin -> the script's Lith Harbor fallback. Reusing the
            // player's saved-location store keeps this stateful across restart.
            int origin = bot.getSavedLocation("WORLDTOUR");
            destMapId = BotAutopilotManager.worldTourReturnOrFallback(origin);
        }
        MapleMap dest = bot.getClient().getChannelServer().getMapFactory().getMap(destMapId);
        if (dest == null) {
            return false;
        }
        bot.gainMeso(-edge.fare(), false);
        if (spinel && destMapId == BotWorldGraph.MUSHROOM_SHRINE) {
            bot.saveLocation("WORLDTOUR"); // inbound ride: remember the origin for the return leg
        }
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
        // Dragon flight is a scripted transport whose intermediate maps are not part of the normal
        // portal graph. Let its approximate flight controller own the hop until it reaches the real
        // destination portal; this must run before the ordinary taxi/ferry and portal state gates.
        if (BotDragonFlightManager.tick(entry, bot, targetMapId, runAiTick)) {
            return true;
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
        // Give-up is scoped to the destination that failed: only block a fast retry of the SAME map.
        // A different consumer traveling somewhere else (e.g. autopilot to a grind map while a quest
        // errand's NPC map is doomed) must not inherit that cooldown — it was poisoning legit travel.
        if (now < entry.followTravelGiveUpUntilMs && targetMapId == entry.followTravelGiveUpTargetMapId) {
            // ponytail: a `deadline` give-up keeps the bot moving via the wander-escape (BotAutopilotManager),
            // which commits a different cross portal into the followTravel* fields during the window. Clearing
            // here every tick would wipe that and thrash it between portals — giveUp() already cleared the
            // failed hop when the window was armed, so the re-clear is only needed for the parked reasons.
            if (!"deadline".equals(entry.followTravelGiveUpReason)) {
                clear(entry);
            }
            return false;
        }

        boolean active = entry.followTravelTargetMapId != -1;
        if (active && (entry.followTravelTargetMapId != targetMapId
                || entry.followTravelFromMapId != bot.getMapId())) {
            // Owner moved on to another map (or we landed off-plan) — re-plan from here.
            clear(entry);
            active = false;
        }
        if (active && entry.followTravelEnteredAtMs > 0) {
            if (now - entry.followTravelEnteredAtMs > PORTAL_LAND_GRACE_MS) {
                giveUp(entry, now, "warp-no-land");
                return false;
            }
            return true; // warp is in flight — hold still
        }

        // Reachability inputs (split maps): consult BotNavigationGraph.canReach (the SSOT for intra-map
        // reachability), shared by the commit-revalidation below and the fresh-plan branch. Use the bot's
        // active movement profile, not the base reference graph: fast/jump-geared bots may only have their
        // exact profile graph warm when travel plans this hop.
        BotNavigationGraph navGraph = BotNavigationGraphProvider.peekBestGraph(map, entry.movementProfile);
        if (BotNavigationGraphProvider.peekGraph(map, entry.movementProfile) == null) {
            BotNavigationGraphProvider.warmGraphAsync(map, entry.movementProfile);
        }
        int botRegion = navGraph != null ? navGraph.findRegionId(map, bot.getPosition()) : -1;
        boolean canCheck = navGraph != null && botRegion >= 0;
        boolean useGroundReachability = canCheck && !map.isSwim();

        if (active && entry.followTravelEnteredAtMs == 0L && !entry.followTravelFerry
                && entry.followTravelTaxiNpcId == 0 && now >= entry.followTravelRouteRecheckAtMs) {
            java.util.function.IntPredicate blocked = BotAutopilotManager.routeBlockFor(bot);
            List<Integer> currentRoute = resolveRoute(bot, map, navGraph, botRegion,
                    useGroundReachability, targetMapId, maxHops, allowFerry, blocked);
            entry.followTravelRouteRecheckAtMs = now + ROUTE_RECHECK_INTERVAL_MS;
            if (currentRoute != null && !currentRoute.isEmpty()
                    && currentRoute.get(0) != entry.followTravelNextHopMapId) {
                clear(entry);
                active = false;
            }
        }

        // Revalidate a committed cross-map portal once the graph is warm. An earlier hop may have pinned it
        // while the graph was cold (canCheck false, canReach unavailable); if this platform actually can't
        // walk to it, drop the pin and re-plan THIS tick instead of walking at an unreachable portal until
        // the deadline trips (the split-map stall — Dead Man's Gorge R6 pinned to top-left U5_1).
        if (active && useGroundReachability && !entry.followTravelFerry && entry.followTravelTaxiNpcId == 0) {
            Portal committed = map.getPortal(entry.followTravelPortalId);
            if (committed != null && BotMapPartition.isTravelCrossMapPortal(committed, map.getId())
                    && !navGraph.canReach(botRegion, navGraph.findRegionId(map, committed.getPosition()), 0)) {
                clear(entry);
                active = false;
            }
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
            refreshTravelDeadlineOnProgress(entry, bot, map, portalApproachTarget(map, portal), now);
            // LOD1 execution does not walk toward the portal: it deliberately holds position for the
            // modeled hop dwell, then enters the real portal below. Applying the physical walk deadline
            // first can expire during that dwell (25s portal price), abandon the correct hop, and send
            // the bot wandering through a different exit forever. Script/landing failures still give up
            // in lod1TimedWarp; only the inapplicable approach deadline is skipped.
            boolean lod1TimedHop = entry.lod == BotEntry.Lod.LOD1
                    && BotManager.cfg.SIMPLIFY_UNOBSERVED_BOTS_TRAVEL;
            if (!lod1TimedHop && now > entry.followTravelDeadlineMs) {
                giveUp(entry, now, "deadline");
                return false;
            }
        } else {
            // Direct hop when the owner's map is adjacent; otherwise take the first hop of the
            // shortest world-graph route. Each landing re-plans, so only the next hop matters.
            int nextHopMapId = targetMapId;
            // navGraph/botRegion/canCheck are computed above (shared with the commit-revalidation): we never
            // commit a cross-map portal this platform can't reach, and route AROUND when the only direct exit
            // is stranded on another platform.

            portal = adjacentOrScriptedPortal(map, targetMapId, bot.getPosition());
            if (portal != null && useGroundReachability && BotMapPartition.isTravelCrossMapPortal(portal, map.getId())
                    && !navGraph.canReach(botRegion, navGraph.findRegionId(map, portal.getPosition()), 0)) {
                portal = null; // direct exit exists but this platform can't reach it — must route around
            }
            if (portal == null) {
                java.util.function.IntPredicate blocked = BotAutopilotManager.routeBlockFor(bot);
                List<Integer> route = resolveRoute(bot, map, navGraph, botRegion,
                        useGroundReachability, targetMapId, maxHops, allowFerry, blocked);
                if (route == null || route.isEmpty()) {
                    return false; // too far or unreachable by walking — warp fallback
                }
                routePrewarm.prewarm(entry, bot, route);
                nextHopMapId = route.get(0);
                if (useGroundReachability) {
                    // When we can decide reachability, NEVER fall back to an unfiltered pick — that could
                    // re-select the very portal canReach just rejected and collapse the split-map fix.
                    portal = findReachableAdjacentPortal(map, navGraph, botRegion, nextHopMapId, bot.getPosition());
                    if (portal == null) {
                        portal = reachableScriptedEntrance(map, navGraph, botRegion, nextHopMapId);
                    }
                } else {
                    portal = adjacentOrScriptedPortal(map, nextHopMapId, bot.getPosition());
                }
                if (portal == null) {
                    return tryConsumableHop(entry, bot, map, targetMapId, nextHopMapId, now, runAiTick);
                }
            }
            entry.followTravelTargetMapId = targetMapId;
            entry.followTravelNextHopMapId = nextHopMapId;
            entry.followTravelFromMapId = bot.getMapId();
            entry.followTravelPortalId = portal.getId();
            entry.followTravelBestDist = Integer.MAX_VALUE; // fresh hop — first walk tick seeds progress
            entry.followTravelBestRouteCost = Integer.MAX_VALUE;
            entry.followTravelProgressPos = null;
            entry.followTravelDeadlineMs = now + travelBudgetMs(manhattan(bot.getPosition(), portal.getPosition()));
            entry.followTravelRouteRecheckAtMs = now + ROUTE_RECHECK_INTERVAL_MS;
        }

        // Progress-aware deadline: refresh from committed-route progress, not raw portal distance.
        // Legal routes can initially move away from the portal in screen space (Ludibrium station climb).
        refreshTravelDeadlineOnProgress(entry, bot, map, portalApproachTarget(map, portal), now);
        return walkToPortalAndEnter(entry, bot, portal, now, runAiTick);
    }

    /** Resolve with the same partition-first policy used when a fresh hop is committed. */
    private static List<Integer> resolveRoute(Character bot, MapleMap map, BotNavigationGraph navGraph,
                                              int botRegion, boolean useGroundReachability,
                                              int targetMapId, int maxHops, boolean allowFerry,
                                              java.util.function.IntPredicate blocked) {
        BotWorldGraph.RouteOptions options = new BotWorldGraph.RouteOptions(
                returnScrollCount.applyAsInt(bot) > 0, bot.getMeso(), allowFerry, bot.getJob().getId() == 0,
                bot.getLevel(), BotAutopilotManager.worldTourReturn(bot), BotAutopilotManager.fmReturn(bot),
                BotAutopilotManager.unlockedTempleGates(bot));
        List<Integer> route = null;
        client.Client botClient = bot.getClient();
        MapManager mapFactory = botClient != null && botClient.getChannelServer() != null
                ? botClient.getChannelServer().getMapFactory() : null;
        if (useGroundReachability && mapFactory != null) {
            List<BotMapPartition.PortalRef> reachableExits = reachableCrossMapExits(map, navGraph, botRegion);
            List<BotWorldPartitionRouter.Node> partitionRoute = partitionRouteLookup.route(
                    id -> BotMapPartitionProvider.forMapId(mapFactory, id), bot.getMapId(), reachableExits,
                    targetMapId, maxHops, blocked);
            if (partitionRoute != null && !partitionRoute.isEmpty()) {
                route = partitionRoute.stream().map(BotWorldPartitionRouter.Node::mapId)
                        .collect(Collectors.toList());
            }
        }
        return route != null ? route
                : routeLookup.route(bot.getMapId(), targetMapId, maxHops, options, blocked);
    }

    /**
     * Walk toward a plain portal and enter it once in range — the shared walk-and-enter step
     * used by the main hop flow above and the ferry legs (cabin door, station walkway).
     */
    /** Where to actually walk to enter {@code portal}. Normally the portal centre — but a collision
     *  (pt=3) portal fires on HITBOX overlap, and its warp point often floats beside a rope onto no
     *  foothold (e.g. Aqua Road 222000001 out00 at x=-31 while the rope is at x=-51). Targeting the
     *  unstandable centre wedges pathfinding; target a reachable surface (rope OR platform) inside the
     *  hitbox instead, and {@link #tickCollisionPortal} warps on overlap once the bot is in the box.
     *  Plain (non-collision) portals are unaffected — the centre is returned. */
    static Point portalApproachTarget(MapleMap map, Portal portal) {
        if (map == null || portal.getType() != COLLISION_PORTAL_TYPE) {
            return portal.getPosition();
        }
        Point reachable = BotPhysicsEngine.reachableApproachInBox(map, portal.getPosition(), COLLISION_ENTER_X, COLLISION_ENTER_Y);
        return reachable != null ? reachable : portal.getPosition();
    }

    static boolean walkToPortalAndEnter(BotEntry entry, Character bot, Portal portal, long now, boolean runAiTick) {
        // LOD1 (unobserved) travel (design §2.2): don't walk to the portal — dwell the modeled hop
        // seconds, then warp through the real portal. Hop planning (which portal) is unchanged upstream;
        // only execution is abstracted. The dwell uses the SSOT travel price so abstract time matches
        // what the planner charged.
        if (entry.lod == BotEntry.Lod.LOD1 && BotManager.cfg.SIMPLIFY_UNOBSERVED_BOTS_TRAVEL) {
            return lod1TimedWarp(entry, bot, portal, now, BotTravelCost.PORTAL_HOP_SECONDS);
        }
        Point portalPos = portalApproachTarget(bot.getMap(), portal);
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
            // Pause a beat before stepping through, and keep walking onto the portal centre while
            // the pause elapses so the bot enters from the middle, not the tolerance edge.
            if (!BotManager.dwellInstant && entry.portalEnterDwellUntilMs == 0L) {
                entry.portalEnterDwellUntilMs =
                        now + BotManager.randMs(PORTAL_ENTER_DELAY_MIN_MS, PORTAL_ENTER_DELAY_MAX_MS);
            }
            if (now < entry.portalEnterDwellUntilMs) {
                pinMoveTarget(entry, portalPos);
                movementStep.step(entry, portalPos, runAiTick);
                return true;
            }
            entry.portalEnterDwellUntilMs = 0L;
            clearMoveTargetPin(entry);
            int beforeMapId = bot.getMapId();
            String script = portal.getScriptName();
            boolean scripted = script != null && !script.isEmpty();
            entry.followTravelEnteredAtMs = now;
            entry.portalUseCooldownUntilMs = now + PORTAL_USE_COOLDOWN_MS;
            portal.enterPortal(bot.getClient());
            if (scripted && bot.getMapId() == beforeMapId) {
                giveUp(entry, now, "script-no-land");
                return false;
            }
            return true;
        }
        entry.portalEnterDwellUntilMs = 0L; // not at the portal yet — re-arm on the next arrival
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
     * LOD1 timed-warp of one hop (design §2.2): dwell {@code hopSeconds} (±20% jitter) then fire the
     * REAL {@link Portal#enterPortal} so destination addPlayer, portal scripts and map-change bookkeeping
     * all still run — only the walk is skipped. Mirrors the enter block of {@link #walkToPortalAndEnter}.
     */
    private static boolean lod1TimedWarp(BotEntry entry, Character bot, Portal portal, long now, double hopSeconds) {
        if (now < entry.portalUseCooldownUntilMs) {
            return true; // brief breather between portals
        }
        if (entry.lod1TravelDwellUntilMs == 0L) {
            double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4; // ±20%
            entry.lod1TravelDwellUntilMs = now + Math.max(1L, (long) (hopSeconds * 1000.0 * jitter));
            return true; // start the dwell
        }
        if (now < entry.lod1TravelDwellUntilMs) {
            return true; // still dwelling out the modeled hop time
        }
        entry.lod1TravelDwellUntilMs = 0L;
        int beforeMapId = bot.getMapId();
        String script = portal.getScriptName();
        boolean scripted = script != null && !script.isEmpty();
        entry.followTravelEnteredAtMs = now;
        entry.portalUseCooldownUntilMs = now + PORTAL_USE_COOLDOWN_MS;
        portal.enterPortal(bot.getClient());
        if (scripted && bot.getMapId() == beforeMapId) {
            giveUp(entry, now, "script-no-land");
            return false;
        }
        return true;
    }

    /**
     * Collision ("pc" pt=3, "pcs" pt=9) portals auto-warp a real player the instant their position
     * enters the trigger box — the CLIENT detects the collision and asks to change map (pt=3) or to
     * run the portal script (pt=9); a bot has no client, so the server never fires it and the bot
     * just sits in the pit / on the rope / hovers over the warp strip. Emulate it here every tick,
     * INTENT-INDEPENDENT: whether the bot walked/climbed onto it or got KNOCKED into a pit,
     * overlapping the portal warps it. For pt=3 only plain warp portals (a scripted pt=3 may
     * gate/dialog); for pt=9 the script IS the warp (e.g. undodraco), so it is executed.
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
            boolean hasScript = script != null && !script.isEmpty();
            boolean touchWarp = portal.getType() == SCRIPTED_COLLISION_PORTAL_TYPE
                    ? hasScript
                    : portal.getType() == COLLISION_PORTAL_TYPE
                            && portal.getTargetMapId() != NO_DESTINATION_MAPID
                            && !hasScript;
            if (!touchWarp || !portal.getPortalStatus()) {
                continue;
            }
            Point pp = portal.getPosition();
            if (Math.abs(pos.x - pp.x) <= COLLISION_ENTER_X && Math.abs(pos.y - pp.y) <= COLLISION_ENTER_Y) {
                entry.portalUseCooldownUntilMs = now + PORTAL_USE_COOLDOWN_MS;
                portal.enterPortal(bot.getClient());
                BotMovementManager.resetEntryState(entry);
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
        BotWorldGraph.TaxiEdge taxi = BotWorldGraph.findTaxiEdge(bot.getMapId(), nextHopMapId, bot.getJob().getId() == 0,
                bot.getLevel());
        if (taxi != null && bot.getMeso() >= taxi.fare()) {
            if (BotDragonFlightManager.isDragonEdge(taxi)
                    && bot.getMapId() != BotDragonFlightManager.LEAFRE_DOCK_MAP_ID) {
                if (!BotDragonFlightManager.beginFromTemple(entry, bot, taxi)) {
                    return false;
                }
                entry.followTravelTargetMapId = targetMapId;
                entry.followTravelNextHopMapId = nextHopMapId;
                entry.followTravelFromMapId = bot.getMapId();
                entry.followTravelPortalId = -1;
                entry.followTravelTaxiNpcId = taxi.npcId();
                entry.followTravelTaxiPos = null;
                entry.followTravelBestDist = Integer.MAX_VALUE;
                entry.followTravelBestRouteCost = Integer.MAX_VALUE;
                entry.followTravelProgressPos = null;
                entry.followTravelDeadlineMs = now + TRAVEL_BUDGET_MAX_MS;
                return BotDragonFlightManager.tick(entry, bot, targetMapId, runAiTick);
            }
            Point npcPos = taxiNpcLocator.locate(map, taxi.npcId());
            if (npcPos == null) {
                return false; // cab NPC missing from the live map — warp fallback
            }
            entry.followTravelTargetMapId = targetMapId;
            entry.followTravelNextHopMapId = nextHopMapId;
            entry.followTravelFromMapId = bot.getMapId();
            entry.followTravelPortalId = -1;
            entry.followTravelTaxiNpcId = taxi.npcId();
            // Reachable spot near the cab NPC, not the exact pixel: warp NPCs (e.g. Shanks at
            // Southperry) are major chokepoints where every bot piles on the same spot and freezes.
            entry.followTravelTaxiPos = pickReachableApproachPoint(entry, bot, npcPos, APPROACH_SPREAD_PX);
            entry.followTravelBestDist = Integer.MAX_VALUE; // fresh hop — tickTaxiHop seeds progress
            entry.followTravelBestRouteCost = Integer.MAX_VALUE;
            entry.followTravelProgressPos = null;
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
        if (entry.dragonFlightTargetMapId != -1
                && entry.followTravelTaxiNpcId == BotDragonFlightManager.DRAGON_NPC_ID
                && bot.getMapId() == BotDragonFlightManager.LEAFRE_DOCK_MAP_ID) {
            return BotDragonFlightManager.tick(entry, bot, entry.followTravelTargetMapId, runAiTick);
        }
        Point npcPos = entry.followTravelTaxiPos;
        Point botPos = bot.getPosition();
        if (npcPos == null) {
            giveUp(entry, now, "taxi-npc-missing");
            return false;
        }
        int distToCab = manhattan(botPos, npcPos);
        // Progress-aware deadline, same as the portal hop: the walk/climb to a town cab can take longer than
        // the manhattan budget (Ellinia's rope-tree, Perion's cliffs), so push the deadline out while the bot
        // is still closing on the cab. Only NET progress resets it, so a genuinely stranded bot still times out.
        if (distToCab < entry.followTravelBestDist) {
            entry.followTravelBestDist = distToCab;
            entry.followTravelDeadlineMs = now + travelBudgetMs(distToCab);
        }
        // Normal: standing grounded within hailing range. Fallback: the cab sits atop a rope/ledge the walk
        // can reach but not stand exactly on (Ellinia tree), so once the bot stops progressing near it, hail
        // from here instead of timing out beside it (shop-visit SSOT).
        boolean inRangeGrounded = !entry.inAir && !entry.climbing && distToCab <= TAXI_TRIGGER_RADIUS_PX;
        boolean stuckNearCab = stuckNear(entry.travelApproachStuck, botPos, npcPos, now, TAXI_TRIGGER_RADIUS_PX);
        // Last resort once the progress-aware budget lapses: the bot got as close as the nav can place it
        // but the cab sits on a foothold it can't stand on, so it never goes grounded-in-range or stationary.
        // A real player clicks a town cab from anywhere on the map, so hail it from here instead of failing
        // the whole errand within sight of it. The walk above still runs for the full budget first.
        boolean deadlineHail = entry.followTravelDeadlineMs > 0 && now >= entry.followTravelDeadlineMs;
        if (inRangeGrounded || stuckNearCab || deadlineHail) {
            clearMoveTargetPin(entry);
            if (!BotManager.npcDwellReady(entry, BotManager.NPC_TALK_DELAY_MS, BotManager.NPC_TALK_JITTER_MS)) {
                return true; // pause a beat at the cab before paying the fare (common-tick settle stands it)
            }
            BotWorldGraph.TaxiEdge taxi =
                    BotWorldGraph.findTaxiEdge(entry.followTravelFromMapId, entry.followTravelNextHopMapId,
                            bot.getJob().getId() == 0, bot.getLevel());
            if (taxi == null) {
                giveUp(entry, now, "taxi-fare-fail");
                return false;
            }
            if (BotDragonFlightManager.isDragonEdge(taxi)) {
                if (!BotDragonFlightManager.boardFromLeafreDock(entry, bot, taxi)) {
                    giveUp(entry, now, "dragon-flight-board-fail");
                    return false;
                }
                return true;
            }
            if (!taxiRide.ride(bot, taxi)) {
                giveUp(entry, now, "taxi-fare-fail"); // fare spent elsewhere mid-walk — don't retry the same hop
                return false;
            }
            entry.followTravelEnteredAtMs = now;
            return true;
        }
        BotManager.npcDwellReset(entry);
        pinMoveTarget(entry, npcPos);
        movementStep.step(entry, npcPos, runAiTick);
        return true;
    }

    /**
     * Nearest open, non-door targeted portal leading directly to targetMapId; null when
     * the map has no such portal (multi-hop or unreachable — caller warps). Scripted portals
     * are allowed so their own script can decide whether entry succeeds.
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
                    returnScrollCount.applyAsInt(bot) > 0, bot.getMeso(), false, bot.getJob().getId() == 0,
                    bot.getLevel(), BotAutopilotManager.worldTourReturn(bot), BotAutopilotManager.fmReturn(bot),
                    BotAutopilotManager.unlockedTempleGates(bot));
            List<Integer> route = routeLookup.route(bot.getMapId(), targetMapId, maxHops, options,
                    BotAutopilotManager.routeBlockFor(bot)); // SSOT danger gate: no <15 route through Sleepywood
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

    /**
     * A plain adjacent portal to {@code targetMapId}, or — when none — the scripted hidden-street
     * entrance portal for that hop (a job-instructor map reachable only by a scripted portal; see
     * {@link BotWorldGraph#SCRIPTED_ENTRANCES}). From the bot's perspective the scripted portal is just
     * a normal portal to {@code targetMapId}: it walks to it and {@link Portal#enterPortal} runs the
     * portal's own warp script. Returns null when neither exists.
     */
    static Portal adjacentOrScriptedPortal(MapleMap map, int targetMapId, Point fromPos) {
        Portal portal = findAdjacentPortal(map.getPortals(), targetMapId, fromPos);
        if (portal != null) {
            return portal;
        }
        String scripted = BotWorldGraph.scriptedEntrancePortal(map.getId(), targetMapId);
        return scripted == null ? null : map.getPortal(scripted);
    }

    /** Like {@link #findAdjacentPortal} but skips portals whose platform the bot (standing in
     *  {@code botRegion}) can't physically walk to, per {@link BotNavigationGraph#canReach} (the SSOT) —
     *  so a split map never commits the bot to an exit stranded on another platform. Returns null when no
     *  reachable adjacent portal exists. */
    static Portal findReachableAdjacentPortal(MapleMap map, BotNavigationGraph navGraph, int botRegion,
                                              int targetMapId, Point fromPos) {
        Portal best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Portal portal : map.getPortals()) {
            if (portal.getTargetMapId() != targetMapId || !BotMapPartition.isTravelPortal(portal)
                    || !navGraph.canReach(botRegion, navGraph.findRegionId(map, portal.getPosition()), 0)) {
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

    /** The allowlisted scripted-entrance portal for this hop (its tm is the spawn sentinel, so
     *  {@link #findReachableAdjacentPortal} can't match it) — but only when the bot can physically reach
     *  it. Returns null otherwise, so the caller never commits to a portal off the bot's platform. */
    static Portal reachableScriptedEntrance(MapleMap map, BotNavigationGraph navGraph, int botRegion,
                                            int nextHopMapId) {
        String scriptedName = BotWorldGraph.scriptedEntrancePortal(map.getId(), nextHopMapId);
        if (scriptedName == null) {
            return null;
        }
        Portal sp = map.getPortal(scriptedName);
        if (sp != null && navGraph.canReach(botRegion, navGraph.findRegionId(map, sp.getPosition()), 0)) {
            return sp;
        }
        return null;
    }

    /** The plain cross-map exits the bot can actually walk to from {@code botRegion} (canReach SSOT). */
    static List<BotMapPartition.PortalRef> reachableCrossMapExits(MapleMap map, BotNavigationGraph navGraph,
                                                                  int botRegion) {
        List<BotMapPartition.PortalRef> out = new ArrayList<>();
        for (Portal p : map.getPortals()) {
            if (BotMapPartition.isTravelCrossMapPortal(p, map.getId())
                    && navGraph.canReach(botRegion, navGraph.findRegionId(map, p.getPosition()), 0)) {
                out.add(new BotMapPartition.PortalRef(p.getName(), p.getPosition(), p.getTargetMapId(), p.getTarget()));
            }
        }
        return out;
    }

    /** How many plain cross-map exits the map has at all (reachable or not) — used to detect when the
     *  bot's platform is constrained (fewer reachable than exist) and partition routing is worthwhile. */
    static int eligibleCrossMapExitCount(MapleMap map) {
        int n = 0;
        for (Portal p : map.getPortals()) {
            if (BotMapPartition.isTravelCrossMapPortal(p, map.getId())) {
                n++;
            }
        }
        return n;
    }

    static Portal findAdjacentPortal(Collection<Portal> portals, int targetMapId, Point fromPos) {
        Portal best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Portal portal : portals) {
            if (portal.getTargetMapId() != targetMapId || !BotMapPartition.isTravelPortal(portal)) {
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
            if (BotMapPartition.isTravelCrossMapPortal(portal, currentMapId)) {
                eligible.add(portal);
            }
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
        entry.followTravelRouteRecheckAtMs = 0L;
        entry.followTravelEnteredAtMs = 0L;
        entry.followTravelBestDist = Integer.MAX_VALUE;
        entry.followTravelBestRouteCost = Integer.MAX_VALUE;
        entry.followTravelProgressPos = null;
        entry.followTravelTaxiNpcId = 0;
        entry.followTravelTaxiPos = null;
        entry.followTravelFerry = false;
        entry.dragonFlightTargetMapId = -1;
        entry.dragonFlightMapId = -1;
        entry.travelApproachStuck.reset(); // fresh hop -> fresh "stuck near the transport NPC" tracking
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
        entry.followTravelGiveUpTargetMapId = -1;
        entry.followTravelGiveUpReason = null;
    }

    private static void giveUp(BotEntry entry, long now, String reason) {
        int failedDest = entry.followTravelTargetMapId; // capture before clear() wipes it
        // Snapshot the failed hop's shape too, so the stuck-bot log/pathlog can say WHICH leg failed
        // (a walk portal, a taxi the bot couldn't reach/afford, or a ferry that wouldn't board).
        String hop = "nextHop=" + entry.followTravelNextHopMapId
                + (entry.followTravelTaxiNpcId != 0 ? " viaTaxi=" + entry.followTravelTaxiNpcId : "")
                + (entry.followTravelFerry ? " viaFerry" : "")
                + (entry.followTravelPortalId > 0 ? " viaPortal=" + entry.followTravelPortalId : "")
                + " fromMap=" + entry.followTravelFromMapId
                // closest the bot got to the hop target: small = reached it but ran out of budget; large/absent
                // = never made progress (nav can't reach it), a deeper routing problem than a short deadline.
                + (entry.followTravelBestDist != Integer.MAX_VALUE ? " bestDist=" + entry.followTravelBestDist : "")
                + (entry.followTravelBestRouteCost != Integer.MAX_VALUE ? " bestRouteCost=" + entry.followTravelBestRouteCost : "");
        clear(entry);
        entry.followTravelGiveUpUntilMs = now + GIVE_UP_WARP_WINDOW_MS;
        entry.followTravelGiveUpTargetMapId = failedDest;
        entry.followTravelGiveUpReason = reason;
        entry.followTravelGiveUpHop = hop;
        entry.followTravelGiveUpAtMs = now;
    }

    /** Walk budget = give-up window for reaching a portal/NPC, scaled by manhattan distance. */
    private static long travelBudgetMs(int manhattanDist) {
        return Math.min(TRAVEL_BUDGET_MAX_MS, TRAVEL_BUDGET_BASE_MS + TRAVEL_BUDGET_PER_PX_MS * manhattanDist);
    }

    private static long travelRouteBudgetMs(int routeCostMs) {
        return Math.min(TRAVEL_BUDGET_MAX_MS,
                TRAVEL_BUDGET_BASE_MS + (long) TRAVEL_BUDGET_ROUTE_COST_MULTIPLIER * routeCostMs);
    }

    private static void refreshTravelDeadlineOnProgress(BotEntry entry, Character bot, MapleMap map,
                                                        Point targetPos, long now) {
        Point botPos = bot == null ? null : bot.getPosition();
        if (entry == null || botPos == null || targetPos == null) {
            return;
        }
        int dist = manhattan(botPos, targetPos);
        boolean closer = dist < entry.followTravelBestDist;
        if (dist < entry.followTravelBestDist) {
            entry.followTravelBestDist = dist;
        }
        int routeCost = remainingTravelRouteCost(entry, bot, map, botPos, targetPos);
        if (routeCost != Integer.MAX_VALUE) {
            if (routeCost < entry.followTravelBestRouteCost) {
                entry.followTravelBestRouteCost = routeCost;
                entry.followTravelProgressPos = new Point(botPos);
                entry.followTravelDeadlineMs = now + travelRouteBudgetMs(routeCost);
            }
            return;
        }

        // Fallback only: graph warming or a stale committed route can leave us without a route-cost
        // scalar for a tick. Physical movement keeps the hop alive briefly until the movement stack
        // commits a route, but the graph cost above is the normal watchdog signal.
        boolean moved = entry.followTravelProgressPos == null
                || manhattan(botPos, entry.followTravelProgressPos) >= TRAVEL_PROGRESS_MOVE_PX;
        if (!closer && !moved) {
            return;
        }
        if (closer) {
            entry.followTravelBestDist = dist;
        }
        entry.followTravelProgressPos = new Point(botPos);
        entry.followTravelDeadlineMs = now + travelBudgetMs(dist);
    }

    private static int remainingTravelRouteCost(BotEntry entry, Character bot, MapleMap map, Point botPos, Point targetPos) {
        if (bot == null || map == null || botPos == null || targetPos == null) {
            return Integer.MAX_VALUE;
        }
        // Non-blocking peek only: this is a per-tick watchdog scalar, not a navigation decision. The
        // blocking getGraph() would build this bot's exact-profile graph INLINE on the tick thread on a
        // cache miss (companions have unique speed/jump, so they always miss), stalling follow-travel for
        // the whole build. A null here is the designed fallback — refreshTravelDeadlineOnProgress keeps
        // the hop alive on physical progress until the warm completes.
        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, entry.movementProfile);
        if (graph == null) {
            return Integer.MAX_VALUE;
        }
        int startRegionId = BotNavigationManager.resolveCurrentRegionId(graph, entry, map, botPos);
        int targetRegionId = BotNavigationManager.resolveTargetRegionId(graph, entry, map, targetPos);
        return BotNavigationManager.committedRouteRemainingCost(graph, entry, botPos, startRegionId, targetRegionId, targetPos);
    }

    // "Got close, act from where you stand" — the SSOT robustness behind the shop visit, taxi, ferry and
    // instructor approaches. A cab atop Ellinia's rope-tree, a shopkeeper in a fenced booth, a ferry usher
    // on a ledge: the direct approach walk can get the bot near but not exactly onto the spot, so it would
    // otherwise stand within sight of the NPC and time out. Once the bot has stopped making progress
    // (moved <= 2px) for ~1s while within {@code fallbackDist} of the target, treat it as arrived.
    static final class ApproachStuck {
        private Point pos;
        private long atMs;
        void reset() { pos = null; atMs = 0L; }
    }

    private static final int APPROACH_STUCK_MOVE_PX = 2;
    private static final long APPROACH_STUCK_MS = 1_000L;

    static boolean stuckNear(ApproachStuck st, Point botPos, Point targetPos, long now, int fallbackDist) {
        if (st == null || targetPos == null || botPos == null) {
            return false;
        }
        if (st.pos == null
                || botPos.distanceSq(st.pos) > (long) APPROACH_STUCK_MOVE_PX * APPROACH_STUCK_MOVE_PX) {
            if (st.pos == null) {
                st.pos = new Point(botPos);
            } else {
                st.pos.setLocation(botPos);
            }
            st.atMs = now;
            return false;
        }
        if (now - st.atMs < APPROACH_STUCK_MS) {
            return false;
        }
        return manhattan(botPos, targetPos) <= fallbackDist;
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
     * SSOT no-progress deadline for any long-travel errand (job / quest / gacha). A NO-PROGRESS timer,
     * NOT a trip budget: {@link #record} refreshes it on every map hop and on any active-travel tick
     * (TRAVELING also covers ferry waits/legs and the dock-gate wait, which {@code tickTravel} surfaces
     * as TRAVELING / a {@code moved} hop). So a legal cross-continent route — even one with boat waits
     * longer than the deadline — never trips it; only a genuinely wedged single map accumulates. Each
     * errand owns one instance on {@link BotEntry}; the caller picks the threshold and what to do when
     * {@link #stalled} returns true.
     */
    static final class ErrandProgress {
        private int mapId = -1;
        private long lastProgressMs = 0L;

        /** Arm at errand start: the progress clock runs from {@code nowMs}. */
        void begin(long nowMs) {
            mapId = -1; // forces the first record() to latch the starting map as progress
            lastProgressMs = nowMs;
        }

        void clear() {
            mapId = -1;
            lastProgressMs = 0L;
        }

        /** True once the errand has gone {@code thresholdMs} with no progress. Pure read — feed it with
         *  {@link #record} each tick. Check this BEFORE doing travel work so a wedged errand drops early. */
        boolean stalled(long nowMs, long thresholdMs) {
            return nowMs - lastProgressMs > thresholdMs;
        }

        /** Feed one tick's outcome: a map change or {@code traveling} (a hop/ferry wait actively
         *  underway) refreshes the deadline; a wedged single map does not. */
        void record(Character bot, boolean traveling, long nowMs) {
            if ((bot != null && bot.getMapId() != mapId) || traveling) {
                if (bot != null) {
                    mapId = bot.getMapId();
                }
                lastProgressMs = nowMs;
            }
        }

        /** Mark the errand as actively progressing without a hop (e.g. rolling at the gachapon), so a
         *  legitimately-busy on-map phase can't time out. */
        void touch(long nowMs) {
            lastProgressMs = nowMs;
        }
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
                                          int maxHops, boolean runAiTick, boolean allowFerry, int radiusPx) {
        if (bot.getMapId() != targetMapId) {
            clearNpcApproach(entry); // not on the NPC's map yet — any cached spot is for another map
            // Propagate tickTravel's verdict: it returns false in its give-up window (no movement for
            // up to ~45s), and the caller must release the tick then so the bot grinds instead of
            // standing frozen until the errand's own timeout. (Pre-extraction tickErrand returned this.)
            boolean moved = tickTravel(entry, bot, targetMapId, maxHops, runAiTick, allowFerry);
            return moved ? ApproachStatus.TRAVELING : ApproachStatus.TRAVEL_YIELDED;
        }
        server.life.NPC npc = bot.getMap() == null ? null : bot.getMap().getNPCById(npcId);
        if (npc == null || npc.getPosition() == null) {
            clearNpcApproach(entry);
            return ApproachStatus.NPC_GONE;
        }
        Point npcPos = npc.getPosition();
        Point botPos = bot.getPosition();
        if (!entry.inAir && !entry.climbing && manhattan(botPos, npcPos) <= radiusPx) {
            clearMoveTargetPin(entry);
            clearNpcApproach(entry);
            return ApproachStatus.ARRIVED; // common-tick settle stands the bot at the NPC
        }
        // Walk to a reachable spot NEAR the NPC, not its exact (often off-floor) sprite pixel: that
        // de-stacks bots converging on one NPC and gives the movement a target the nav can actually
        // reach (the raw pos froze bots that couldn't path to it, then dropped the errand). Cached
        // per npcId so the random pick is stable across ticks.
        if (entry.npcApproachPos == null || entry.npcApproachNpcId != npcId) {
            // Search the close de-stack ring first, but widen up to the interaction radius for NPCs on
            // off-graph spots (high platforms/docks) so the bot stands at the nearest reachable foothold
            // within reach instead of looping at an unreachable sprite pixel.
            entry.npcApproachPos = pickReachableApproachPoint(entry, bot, npcPos, APPROACH_SPREAD_PX, radiusPx);
            entry.npcApproachNpcId = npcId;
        }
        Point walkTarget = entry.npcApproachPos != null ? entry.npcApproachPos : npcPos;
        // Stuck-near fallback (shop-visit SSOT): the NPC may sit behind a rail/ledge the approach walk can't
        // stand exactly on — if the bot got near and stopped progressing, count it as arrived rather than
        // looping at the obstacle until the errand times out.
        if (stuckNear(entry.npcApproachStuck, botPos, npcPos, System.currentTimeMillis(), radiusPx)) {
            clearMoveTargetPin(entry);
            clearNpcApproach(entry);
            return ApproachStatus.ARRIVED; // common-tick settle stands the bot at the NPC
        }
        pinMoveTarget(entry, walkTarget);
        movementStep.step(entry, walkTarget, runAiTick);
        return ApproachStatus.WALKING;
    }

    static void clearNpcApproach(BotEntry entry) {
        entry.npcApproachPos = null;
        entry.npcApproachNpcId = 0;
        entry.npcApproachStuck.reset();
    }

    /**
     * Shared de-stacker: a reachable, ground-snapped spot within {@code spreadPx} of {@code targetPos}
     * so bots converging on one NPC/point don't pile on the exact same pixel — and so the walk target
     * is a standable foothold the nav can actually reach, not an off-floor sprite anchor. Samples
     * footholds near the point, keeps only nav-reachable ones (graph permitting), and returns a random
     * survivor; falls back to {@code targetPos} when nothing better is found. SSOT for shop / quest /
     * job / taxi(warp) NPC approaches.
     */
    static Point pickReachableApproachPoint(BotEntry entry, Character bot, Point targetPos, int spreadPx) {
        return pickReachableApproachPoint(entry, bot, targetPos, spreadPx, spreadPx);
    }

    /**
     * As above, with a WIDEN step for NPCs that sit off the nav graph (high platforms, docks, treetops —
     * many do). {@code destackPx} = the close de-stack ring searched first (a random reachable foothold,
     * so converging bots don't pile on one pixel). When NOTHING is reachable within destackPx, widen the
     * hunt to {@code maxPx} and take the NEAREST reachable foothold (de-stacked within that nearest
     * cluster). Callers set maxPx to the NPC interaction radius so the bot still stops close enough to
     * interact — the missing piece that left bots looping "walking to the instructor" at unreachable
     * 2nd-job instructor platforms (e.g. 102040000 NPC 1072003: nearest reachable ~262px > the old 150px
     * ring, < the 500px trigger radius). The widen is one-shot per approach (result cached on the entry).
     */
    static Point pickReachableApproachPoint(BotEntry entry, Character bot, Point targetPos, int destackPx, int maxPx) {
        MapleMap map = bot.getMap();
        if (map == null || map.getFootholds() == null) {
            return targetPos;
        }
        BotMovementProfile profile = entry.movementProfile != null
                ? entry.movementProfile : BotMovementProfile.fromCharacter(bot);
        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, profile);
        Point botPos = bot.getPosition();
        int startRegionId = graph != null
                ? BotNavigationManager.resolveCurrentRegionId(graph, entry, map, botPos) : -1;
        // Reachability from the bot only depends on the candidate's REGION, and one pick probes many
        // candidates (often sharing a handful of regions) — memo the per-region A* verdict for this
        // pick so each distinct region is searched once, across both the close and widened rings.
        Map<Integer, Boolean> reachMemo = new HashMap<>();
        // Close de-stack ring first: a random reachable foothold within destackPx (unchanged behavior).
        List<Point> near = approachCandidates(entry, bot, map, graph, startRegionId, botPos, targetPos, destackPx,
                reachMemo);
        if (!near.isEmpty()) {
            return near.get(ThreadLocalRandom.current().nextInt(near.size()));
        }
        // Nothing reachable close — the NPC is off the graph. Widen to maxPx and take the NEAREST
        // reachable foothold (with a destack band around it) so the bot still lands within reach.
        if (maxPx > destackPx) {
            List<Point> wide = approachCandidates(entry, bot, map, graph, startRegionId, botPos, targetPos, maxPx,
                    reachMemo);
            if (!wide.isEmpty()) {
                long best = Long.MAX_VALUE;
                for (Point p : wide) {
                    best = Math.min(best, manhattan(p, targetPos));
                }
                long band = best + destackPx;
                List<Point> nearest = new ArrayList<>();
                for (Point p : wide) {
                    if (manhattan(p, targetPos) <= band) {
                        nearest.add(p);
                    }
                }
                return nearest.get(ThreadLocalRandom.current().nextInt(nearest.size()));
            }
        }
        return targetPos;
    }

    /** Standable footholds within {@code radiusPx} (Manhattan) of {@code targetPos}, restricted to
     *  nav-reachable ones when a graph + start region are available (else all within radius — the
     *  graph-less fallback the old code used). */
    private static List<Point> approachCandidates(BotEntry entry, Character bot, MapleMap map,
            BotNavigationGraph graph, int startRegionId, Point botPos, Point targetPos, int radiusPx,
            Map<Integer, Boolean> reachMemo) {
        List<Point> candidates = new ArrayList<>();
        for (Foothold fh : map.getFootholds().getAllFootholds()) {
            int fx1 = fh.getX1(), fy1 = fh.getY1(), fx2 = fh.getX2(), fy2 = fh.getY2();
            if (fx1 == fx2) {
                continue; // wall foothold — nothing to stand on
            }
            int xMin = Math.min(fx1, fx2), xMax = Math.max(fx1, fx2);
            int step = Math.max(1, (xMax - xMin) / 20);
            for (int x = xMin; x <= xMax; x += step) {
                double t = (double) (x - fx1) / (fx2 - fx1);
                int y = (int) (fy1 + t * (fy2 - fy1));
                if (Math.abs(x - targetPos.x) + Math.abs(y - targetPos.y) <= radiusPx) {
                    candidates.add(new Point(x, y));
                }
            }
        }
        if (graph == null || startRegionId < 0) {
            return candidates; // no graph to verify against -> all within radius
        }
        List<Point> reachable = new ArrayList<>();
        for (Point candidate : candidates) {
            int targetRegionId = BotNavigationManager.resolveTargetRegionId(graph, entry, map, candidate);
            if (targetRegionId < 0) {
                continue;
            }
            boolean ok = reachMemo.computeIfAbsent(targetRegionId, rid ->
                    startRegionId == rid
                            || !BotNavigationManager.findPathForApproachProbe(graph, map, botPos,
                                    startRegionId, rid, candidate).isEmpty());
            if (ok) {
                reachable.add(candidate);
            }
        }
        return reachable;
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
