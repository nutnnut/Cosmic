package server.bots;

import client.Character;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;

/**
 * Bot-side execution of the Leafre Dragon flight.
 *
 * <p>The player route is scripted: NPC 2082003 sends the player to 200090500, the player flies
 * across 200090500/200090510, and the portal scripts land at Leafre or 270000100. The client has
 * special fly physics for these maps; bots reuse the existing swim integrator as a deliberately
 * approximate controller until a dedicated dragon model exists.
 */
final class BotDragonFlightManager {

    static final int DRAGON_NPC_ID = 2082003;
    static final int LEAFRE_DOCK_MAP_ID = 240000110;
    static final int TEMPLE_ARRIVAL_MAP_ID = 270000100;
    static final int LEAFRE_FLIGHT_MAP_ID = 200090500;
    static final int TEMPLE_FLIGHT_MAP_ID = 200090510;

    private static final int FLIGHT_PORTAL_X_TOLERANCE = 18;
    private static final int FLIGHT_PORTAL_Y_TOLERANCE = 60;
    private static final int HIGH_FLIGHT_Y = -450;
    private static final long FLIGHT_BUDGET_MS = 45_000L;

    private BotDragonFlightManager() {
    }

    static boolean isDragonEdge(BotWorldGraph.TaxiEdge edge) {
        return edge != null
                && edge.npcId() == DRAGON_NPC_ID
                && ((edge.fromMapId() == LEAFRE_DOCK_MAP_ID && edge.toMapId() == TEMPLE_ARRIVAL_MAP_ID)
                || (edge.fromMapId() == TEMPLE_ARRIVAL_MAP_ID && edge.toMapId() == LEAFRE_DOCK_MAP_ID));
    }

    /** Begin the outbound flight after the bot has approached NPC 2082003. */
    static boolean boardFromLeafreDock(BotEntry entry, Character bot, BotWorldGraph.TaxiEdge edge) {
        if (!isDragonEdge(edge) || bot.getMapId() != LEAFRE_DOCK_MAP_ID
                || edge.toMapId() != TEMPLE_ARRIVAL_MAP_ID) {
            return false;
        }
        MapleMap flight = map(bot, LEAFRE_FLIGHT_MAP_ID);
        if (flight == null || flight.getPortal(0) == null) {
            return false;
        }
        entry.dragonFlightTargetMapId = TEMPLE_ARRIVAL_MAP_ID;
        entry.followTravelDeadlineMs = System.currentTimeMillis() + FLIGHT_BUDGET_MS;
        bot.changeMap(flight, flight.getPortal(0));
        return true;
    }

    /** True while the current scripted flight owns this travel tick. */
    static boolean tick(BotEntry entry, Character bot, int targetMapId, boolean runAiTick) {
        if (entry.dragonFlightTargetMapId == -1) {
            return false;
        }
        if (entry.followTravelDeadlineMs > 0
                && System.currentTimeMillis() > entry.followTravelDeadlineMs) {
            // Let BotTravelManager's normal no-progress path release the hop; its caller then applies
            // the existing direct-warp fallback used for failed taxi/portal travel.
            entry.dragonFlightTargetMapId = -1;
            return false;
        }
        if (bot.getMapId() == targetMapId && targetMapId == entry.dragonFlightTargetMapId) {
            entry.dragonFlightTargetMapId = -1;
            return false;
        }

        boolean outbound = entry.dragonFlightTargetMapId == TEMPLE_ARRIVAL_MAP_ID;
        boolean inbound = entry.dragonFlightTargetMapId == LEAFRE_DOCK_MAP_ID;
        if (!outbound && !inbound) {
            entry.dragonFlightTargetMapId = -1;
            return false;
        }

        int mapId = bot.getMapId();
        if (mapId == TEMPLE_ARRIVAL_MAP_ID && inbound) {
            return walkIntoScriptedPortal(entry, bot, "out00", runAiTick);
        }
        if (mapId == LEAFRE_FLIGHT_MAP_ID && inbound) {
            return flyToScriptedPortal(entry, bot, "minar00", new Point(-700, -214), runAiTick);
        }
        if (mapId == TEMPLE_FLIGHT_MAP_ID && outbound) {
            return flyToScriptedPortal(entry, bot, "in00", new Point(571, -227), runAiTick);
        }
        if (mapId == LEAFRE_FLIGHT_MAP_ID && outbound) {
            return flyAcross(entry, bot, new Point(2700, HIGH_FLIGHT_Y), runAiTick);
        }
        if (mapId == TEMPLE_FLIGHT_MAP_ID && inbound) {
            return flyAcross(entry, bot, new Point(-2700, HIGH_FLIGHT_Y), runAiTick);
        }

        // The map-change tick will reconcile the entry after the real portal script lands. Keep the
        // state alive on the two flight maps only; anything else is a failed scripted hop.
        if (mapId != LEAFRE_FLIGHT_MAP_ID && mapId != TEMPLE_FLIGHT_MAP_ID) {
            entry.dragonFlightTargetMapId = -1;
        }
        return false;
    }

    private static boolean flyAcross(BotEntry entry, Character bot, Point target, boolean runAiTick) {
        entry.inAir = true;
        // The flight maps contain a low foothold strip. Stay in the high lane while crossing so the
        // approximate swim integrator does not settle onto it; descend toward the real portal only
        // for the final approach.
        Point steeringTarget = Math.abs(bot.getPosition().x - target.x) > 500
                ? new Point(target.x, HIGH_FLIGHT_Y) : target;
        BotMovementManager.tickSwimming(entry, steeringTarget);
        return true;
    }

    private static boolean flyToScriptedPortal(BotEntry entry, Character bot, String portalName,
                                               Point target, boolean runAiTick) {
        MapleMap map = bot.getMap();
        Portal portal = map == null ? null : map.getPortal(portalName);
        if (portal == null || !portal.getPortalStatus()) {
            return false;
        }
        Point pos = bot.getPosition();
        Point portalPos = portal.getPosition();
        if (Math.abs(pos.x - portalPos.x) <= FLIGHT_PORTAL_X_TOLERANCE
                && Math.abs(pos.y - portalPos.y) <= FLIGHT_PORTAL_Y_TOLERANCE) {
            entry.portalUseCooldownUntilMs = System.currentTimeMillis() + 500L;
            entry.dragonFlightTargetMapId = -1;
            portal.enterPortal(bot.getClient());
            return true;
        }
        entry.inAir = true;
        BotMovementManager.tickSwimming(entry, target);
        return true;
    }

    private static boolean walkIntoScriptedPortal(BotEntry entry, Character bot, String portalName,
                                                  boolean runAiTick) {
        Portal portal = bot.getMap() == null ? null : bot.getMap().getPortal(portalName);
        if (portal == null) {
            return false;
        }
        return BotTravelManager.walkToPortalAndEnter(entry, bot, portal,
                System.currentTimeMillis(), runAiTick);
    }

    private static MapleMap map(Character bot, int mapId) {
        return bot.getClient() == null || bot.getClient().getChannelServer() == null
                ? null
                : bot.getClient().getChannelServer().getMapFactory().getMap(mapId);
    }
}
