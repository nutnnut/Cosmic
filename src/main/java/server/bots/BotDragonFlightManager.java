package server.bots;

import client.BuffStat;
import client.Character;
import server.ItemInformationProvider;
import server.StatEffect;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;

/**
 * Bot-side execution of the Leafre Dragon flight.
 *
 * <p>The player route is scripted: NPC 2082003 uses Dragon Scale (2210016) to morph the player into
 * a dragon, sends them to 200090500, the player flies across 200090500/200090510, and the exit
 * portal scripts ({@code templeenter}/{@code undodraco}) cancel the morph and land at 270000100 or
 * Leafre. The client has special fly physics for these two {@code fly=1} maps; bots have neither the
 * morph nor a fly integrator, so this manager reproduces both: it applies the same morph the NPC
 * does and reuses the swim integrator as a deliberately approximate flight controller until a
 * dedicated dragon model exists.
 *
 * <p>{@link #tickFlyMap} is the single per-tick entry for "bot is on a flight map", driven from the
 * common tick so it covers every way a bot lands there — autopilot travel boarding at the Leafre
 * dock, and a companion follow-warping in after its owner morphs and flies. It always ensures the
 * dragon morph; for a committed autopilot flight it yields steering to {@link #tick} (called from
 * {@link BotTravelManager}), and for a following/stray bot it steers the corridor itself.
 */
final class BotDragonFlightManager {

    static final int DRAGON_NPC_ID = 2082003;
    static final int LEAFRE_DOCK_MAP_ID = 240000110;
    static final int TEMPLE_ARRIVAL_MAP_ID = 270000100;
    static final int LEAFRE_FLIGHT_MAP_ID = 200090500;
    static final int TEMPLE_FLIGHT_MAP_ID = 200090510;

    // Dragon Scale: 30-min morph (morph=16). The Leafre NPC applies it via cm.useItem(2210016); the
    // two exit portal scripts cancel it. Applied/cancelled through the shared StatEffect path so other
    // players see the dragon exactly as they would for a real player.
    private static final int DRAGON_MORPH_ITEM = 2210016;

    private static final int FLIGHT_PORTAL_X_TOLERANCE = 18;
    private static final int FLIGHT_PORTAL_Y_TOLERANCE = 60;
    private static final int HIGH_FLIGHT_Y = -450;
    // The two flight maps chain to each other through collision (pt=3) portals along their far edges:
    // 200090500's east edge -> 200090510, and 200090510's west edge -> 200090500. Cruise to the edge
    // x so BotTravelManager.tickCollisionPortal (common tick) fires the hop; the scripted in00/minar00
    // exits are entered directly instead.
    private static final int FLY_EAST_EDGE_X = 2765;
    private static final int FLY_WEST_EDGE_X = -2735;
    // Following a same-map owner: cap the steer target above the low foothold strip (y ~= 145) so the
    // approximate swim controller never settles onto it and reverts to ground physics.
    private static final int FLY_FLOOR_CEILING_Y = 0;
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
        armFlight(entry, TEMPLE_ARRIVAL_MAP_ID, LEAFRE_FLIGHT_MAP_ID);
        bot.changeMap(flight, flight.getPortal(0));
        return true;
    }

    /** Begin the reverse flight before walking into Temple's {@code out00} script portal. */
    static boolean beginFromTemple(BotEntry entry, Character bot, BotWorldGraph.TaxiEdge edge) {
        if (!isDragonEdge(edge) || bot.getMapId() != TEMPLE_ARRIVAL_MAP_ID
                || edge.toMapId() != LEAFRE_DOCK_MAP_ID) {
            return false;
        }
        armFlight(entry, LEAFRE_DOCK_MAP_ID, TEMPLE_ARRIVAL_MAP_ID);
        return true;
    }

    /** True while the current scripted flight owns this travel tick. */
    static boolean tick(BotEntry entry, Character bot, int targetMapId, boolean runAiTick) {
        if (entry.dragonFlightTargetMapId == -1) {
            return false;
        }
        int mapId = bot.getMapId();
        if (isFlightMap(mapId) && entry.dragonFlightMapId != mapId) {
            // Each flight map is a full traversal leg. A single 45s corridor budget is shorter than
            // the two maps' combined horizontal distance at the 140px/s flight-controller speed.
            entry.dragonFlightMapId = mapId;
            entry.followTravelDeadlineMs = System.currentTimeMillis() + FLIGHT_BUDGET_MS;
        }
        if (entry.followTravelDeadlineMs > 0
                && System.currentTimeMillis() > entry.followTravelDeadlineMs) {
            // Let BotTravelManager's normal no-progress path release the hop; its caller then applies
            // the existing direct-warp fallback used for failed taxi/portal travel.
            clearFlight(entry);
            return false;
        }
        if (bot.getMapId() == targetMapId && targetMapId == entry.dragonFlightTargetMapId) {
            clearFlight(entry);
            return false;
        }

        boolean outbound = entry.dragonFlightTargetMapId == TEMPLE_ARRIVAL_MAP_ID;
        boolean inbound = entry.dragonFlightTargetMapId == LEAFRE_DOCK_MAP_ID;
        if (!outbound && !inbound) {
            clearFlight(entry);
            return false;
        }

        if (mapId == TEMPLE_ARRIVAL_MAP_ID && inbound) {
            return walkIntoScriptedPortal(entry, bot, "out00", runAiTick);
        }
        if (isFlightMap(mapId)) {
            return flyCurrentMap(entry, bot, outbound, runAiTick);
        }

        // The map-change tick will reconcile the entry after the real portal script lands. Keep the
        // state alive on the two flight maps only; anything else is a failed scripted hop.
        clearFlight(entry);
        return false;
    }

    static boolean isFlightMap(int mapId) {
        return mapId == LEAFRE_FLIGHT_MAP_ID || mapId == TEMPLE_FLIGHT_MAP_ID;
    }

    /**
     * Common-tick entry: while the bot is on either flight map, keep it a dragon and — for a
     * following/stray bot with no committed autopilot flight — steer the corridor. Returns true when
     * it owns the movement tick (so the caller skips the normal follow/grind dispatch); false lets a
     * committed autopilot flight keep steering through {@link #tick}, or lets normal ticks run once
     * the bot is off the flight maps.
     */
    static boolean tickFlyMap(BotEntry entry, Character bot, Character owner, boolean runAiTick) {
        if (!isFlightMap(bot.getMapId())) {
            // Left the corridor by any exit: if a non-scripted exit (a follow-warp) skipped the
            // portal's cancelItem, drop the morph now so the bot doesn't stay a dragon in town.
            if (bot.getBuffSource(BuffStat.MORPH) == DRAGON_MORPH_ITEM) {
                StatEffect fx = ItemInformationProvider.getInstance().getItemEffect(DRAGON_MORPH_ITEM);
                if (fx != null) {
                    bot.cancelEffect(fx, false, -1);
                }
            }
            return false;
        }
        ensureDragonMorph(bot);
        if (entry.dragonFlightTargetMapId != -1) {
            return false; // committed autopilot flight owns steering via BotTravelManager.tickTravel
        }
        // Follow-mode / stray: no committed flight. Fly toward the owner when sharing the map, else
        // toward the corridor exit on the owner's side (defaulting to the Temple exit).
        if (owner != null && owner.getMapId() == bot.getMapId()) {
            entry.inAir = true;
            Point op = owner.getPosition();
            BotMovementManager.tickSwimming(entry,
                    new Point(op.x, Math.min(op.y, FLY_FLOOR_CEILING_Y)));
            return true;
        }
        int ownerMapId = owner != null ? owner.getMapId() : -1;
        return flyCurrentMap(entry, bot, followOutbound(bot.getMapId(), ownerMapId), runAiTick);
    }

    /** Steer the current flight map toward its outbound (Temple-ward) or inbound (Leafre-ward) exit. */
    private static boolean flyCurrentMap(BotEntry entry, Character bot, boolean outbound, boolean runAiTick) {
        int mapId = bot.getMapId();
        if (mapId == LEAFRE_FLIGHT_MAP_ID) {
            return outbound
                    ? flyAcross(entry, bot, new Point(FLY_EAST_EDGE_X, HIGH_FLIGHT_Y), runAiTick)
                    : flyToScriptedPortal(entry, bot, "minar00", new Point(-700, -214), runAiTick);
        }
        if (mapId == TEMPLE_FLIGHT_MAP_ID) {
            return outbound
                    ? flyToScriptedPortal(entry, bot, "in00", new Point(571, -227), runAiTick)
                    : flyAcross(entry, bot, new Point(FLY_WEST_EDGE_X, HIGH_FLIGHT_Y), runAiTick);
        }
        return false;
    }

    /**
     * Which way a following bot should exit the flight corridor. The corridor is strictly linear —
     * Leafre dock (240000110) - 200090500 - 200090510 - Temple (270000100) — so the owner's position
     * on it, relative to the bot's flight map, gives the direction. Owner off-corridor or on the same
     * flight map (handled by the caller): default outbound toward Temple.
     */
    static boolean followOutbound(int botMapId, int ownerMapId) {
        int botIdx = corridorIndex(botMapId);
        int ownerIdx = corridorIndex(ownerMapId);
        if (ownerIdx >= 0 && ownerIdx != botIdx) {
            return ownerIdx > botIdx;
        }
        return true;
    }

    private static int corridorIndex(int mapId) {
        return switch (mapId) {
            case LEAFRE_DOCK_MAP_ID -> 0;
            case LEAFRE_FLIGHT_MAP_ID -> 1;
            case TEMPLE_FLIGHT_MAP_ID -> 2;
            case TEMPLE_ARRIVAL_MAP_ID -> 3;
            default -> -1;
        };
    }

    /** Apply the Dragon Scale morph if the bot isn't already wearing it — the same shared StatEffect
     *  path the Leafre NPC's {@code cm.useItem(2210016)} runs, so the transform is legal and visible. */
    private static void ensureDragonMorph(Character bot) {
        if (bot.getBuffSource(BuffStat.MORPH) == DRAGON_MORPH_ITEM) {
            return;
        }
        StatEffect fx = ItemInformationProvider.getInstance().getItemEffect(DRAGON_MORPH_ITEM);
        if (fx != null) {
            fx.applyTo(bot);
        }
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
            clearFlight(entry);
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

    private static void armFlight(BotEntry entry, int targetMapId, int mapId) {
        entry.dragonFlightTargetMapId = targetMapId;
        entry.dragonFlightMapId = mapId;
        entry.followTravelDeadlineMs = System.currentTimeMillis() + FLIGHT_BUDGET_MS;
    }

    private static void clearFlight(BotEntry entry) {
        entry.dragonFlightTargetMapId = -1;
        entry.dragonFlightMapId = -1;
    }
}
