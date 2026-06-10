package server.bots;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import scripting.event.EventManager;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-continent ferry travel, the exact player path: buy the ticket from the seller NPC,
 * hand it to the boarding usher while the gate is open, wait on the platform, ride the boat,
 * and get warped off at the destination station by the Boats event — no warps of our own
 * except the ones the NPC scripts themselves perform. Route data is hardcoded and verified
 * against {@code scripts/npc} / {@code scripts/event/Boats.js} / the station WZ life data.
 *
 * <p>Boarding is stateless: every travel re-plan looks at (current map, ticket in bag,
 * gate open) and runs the right leg, so map changes, deaths, owner interruptions and
 * restarts all self-heal. Riding is also stateless: the waiting room, deck and cabin are
 * "transit" maps where the bot simply waits for the event — except a Crimson Balrog
 * invasion on deck, which sends the bot through the cabin door like any sane passenger.
 */
final class BotFerryManager {

    // Same approach radius as the cab NPCs: anywhere near the NPC counts as talking to it.
    private static final int NPC_TRIGGER_RADIUS_PX = 500;
    // Re-armed while waiting legitimately (gate closed); walking legs use it as a fresh budget.
    private static final long LEG_BUDGET_MS = 60_000L;

    /**
     * One direction of a ferry line. {@code guideNpcId == 0} means the station has no
     * platform-guide leg (Ellinia boards in one map). {@code boardingMapIds} are the maps
     * that carry the world-graph edge — every map of the boarding chain, so a bot standing
     * anywhere along it keeps planning forward instead of looping back to the start.
     */
    record FerryRoute(int destinationMapId, int ticketItemId, int ticketCost,
                      int ticketNpcId, int ticketNpcMapId,
                      int usherNpcId, int usherNpcMapId,
                      int guideNpcId, int guideMapId, int guideTargetMapId,
                      int waitingMapId, int deckMapId, int cabinMapId,
                      List<Integer> boardingMapIds) {
    }

    // Ellinia station 101000300: seller 1032007 (4031045, 5k) and usher 1032008 in one map.
    static final FerryRoute ELLINIA_TO_ORBIS = new FerryRoute(
            200000100, 4031045, 5000,
            1032007, 101000300,
            1032008, 101000300,
            0, -1, -1,
            101000301, 200090010, 200090011,
            List.of(101000300));

    // Orbis: seller 2012000 + platform guide 2012006 in the hall 200000100; the guide warps
    // to walkway 200000110 (west00), a plain portal leads to the pier 200000111 with usher
    // 2012001.
    static final FerryRoute ORBIS_TO_ELLINIA = new FerryRoute(
            101000300, 4031047, 5000,
            2012000, 200000100,
            2012001, 200000111,
            2012006, 200000100, 200000110,
            200000112, 200090000, 200090001,
            List.of(200000100, 200000110, 200000111));

    private static final List<FerryRoute> ROUTES = List.of(ELLINIA_TO_ORBIS, ORBIS_TO_ELLINIA);

    private static final Map<Integer, FerryRoute> BOARDING_MAP_TO_ROUTE = buildBoardingIndex();
    private static final Map<Integer, FerryRoute> TRANSIT_MAP_TO_ROUTE = buildTransitIndex();

    private static Map<Integer, FerryRoute> buildBoardingIndex() {
        Map<Integer, FerryRoute> byMap = new HashMap<>();
        for (FerryRoute route : ROUTES) {
            for (int mapId : route.boardingMapIds()) {
                byMap.put(mapId, route);
            }
        }
        return Map.copyOf(byMap);
    }

    private static Map<Integer, FerryRoute> buildTransitIndex() {
        Map<Integer, FerryRoute> byMap = new HashMap<>();
        for (FerryRoute route : ROUTES) {
            byMap.put(route.waitingMapId(), route);
            byMap.put(route.deckMapId(), route);
            byMap.put(route.cabinMapId(), route);
        }
        return Map.copyOf(byMap);
    }

    /** The ferry boardable from this map, or null. Feeds the world-graph edges. */
    static FerryRoute routeBoardingAt(int mapId) {
        return BOARDING_MAP_TO_ROUTE.get(mapId);
    }

    /** The ferry edge from one map to a destination, or null when no line sails that way. */
    static FerryRoute findFerryEdge(int fromMapId, int toMapId) {
        FerryRoute route = BOARDING_MAP_TO_ROUTE.get(fromMapId);
        return route != null && route.destinationMapId() == toMapId ? route : null;
    }

    // Test seams: ticket/meso/board/guide touch inventory and the live map factory; gate and
    // threat state live in the Boats EventManager.
    @FunctionalInterface
    interface TicketCheck {
        boolean hasTicket(Character bot, int ticketItemId);
    }

    @FunctionalInterface
    interface TicketShop {
        boolean buy(Character bot, FerryRoute route);
    }

    @FunctionalInterface
    interface BoardAction {
        boolean board(Character bot, FerryRoute route);
    }

    @FunctionalInterface
    interface GuideAction {
        boolean warpToPlatform(Character bot, FerryRoute route);
    }

    @FunctionalInterface
    interface GateCheck {
        boolean entryOpen(Character bot);
    }

    @FunctionalInterface
    interface ThreatCheck {
        boolean invaded(Character bot);
    }

    static TicketCheck ticketCheck = (bot, ticketItemId) -> bot.haveItem(ticketItemId);

    static TicketShop ticketShop = (bot, route) -> {
        // Mirrors the seller script: meso + inventory space check, then pay and receive.
        if (bot.getMeso() < route.ticketCost()
                || !InventoryManipulator.checkSpace(bot.getClient(), route.ticketItemId(), 1, "")) {
            return false;
        }
        bot.gainMeso(-route.ticketCost(), false);
        InventoryManipulator.addById(bot.getClient(), route.ticketItemId(), (short) 1);
        return true;
    };

    static BoardAction boardAction = (bot, route) -> {
        // Mirrors the usher script: take the ticket, warp into the waiting room.
        MapleMap waiting = bot.getClient().getChannelServer().getMapFactory().getMap(route.waitingMapId());
        if (waiting == null) {
            return false;
        }
        InventoryManipulator.removeById(bot.getClient(), InventoryType.ETC, route.ticketItemId(), 1, true, false);
        bot.changeMap(waiting);
        return true;
    };

    static GuideAction guideAction = (bot, route) -> {
        // Mirrors the platform guide script: cm.warp(walkway, "west00").
        MapleMap walkway = bot.getClient().getChannelServer().getMapFactory().getMap(route.guideTargetMapId());
        if (walkway == null) {
            return false;
        }
        bot.changeMap(walkway, walkway.getPortal("west00"));
        return true;
    };

    static GateCheck gateCheck = bot -> "true".equals(boatsProperty(bot, "entry"));

    static ThreatCheck threatCheck = bot -> {
        if ("true".equals(boatsProperty(bot, "haveBalrog"))) {
            return true;
        }
        MapleMap map = bot.getMap();
        return map != null && map.getAllMonsters().stream().anyMatch(server.life.Monster::isAlive);
    };

    private static String boatsProperty(Character bot, String key) {
        EventManager em = bot.getClient().getChannelServer().getEventSM().getEventManager("Boats");
        return em != null ? em.getProperty(key) : null;
    }

    private BotFerryManager() {}

    /**
     * Ride phase, stateless by map: consumes the tick on the waiting room, deck and cabin
     * (the Boats event moves everyone). On deck a Balrog invasion — or a follow target
     * already inside — sends the bot through the cabin door; a follow target back on a
     * quiet deck brings it out. Returns false when the bot isn't on a ferry transit map.
     */
    static boolean tickTransit(BotEntry entry, Character bot, int targetMapId, boolean runAiTick) {
        FerryRoute route = TRANSIT_MAP_TO_ROUTE.get(bot.getMapId());
        if (route == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (bot.getMapId() == route.deckMapId()
                && (threatCheck.invaded(bot) || targetMapId == route.cabinMapId())) {
            enterAdjacentPortal(entry, bot, route.cabinMapId(), now, runAiTick);
        } else if (bot.getMapId() == route.cabinMapId()
                && targetMapId == route.deckMapId() && !threatCheck.invaded(bot)) {
            enterAdjacentPortal(entry, bot, route.deckMapId(), now, runAiTick);
        }
        return true; // the boat decides when this ends, not the travel deadline
    }

    /**
     * One tick of the boarding chain, dispatched on the current map: buy the ticket, take
     * the platform guide, walk the walkway, hand the ticket to the usher (waiting beside
     * them while the gate is closed). Returns false when boarding can't proceed from here —
     * the caller falls back exactly like any failed hop.
     */
    static boolean tickBoarding(BotEntry entry, Character bot, FerryRoute route, long now, boolean runAiTick) {
        int mapId = bot.getMapId();
        boolean hasTicket = ticketCheck.hasTicket(bot, route.ticketItemId());

        if (mapId == route.ticketNpcMapId() && !hasTicket) {
            if (bot.getMeso() < route.ticketCost()) {
                return false;
            }
            return walkToNpcThenAct(entry, bot, route.ticketNpcId(), now, runAiTick,
                    () -> ticketShop.buy(bot, route));
        }
        if (route.guideNpcId() != 0 && mapId == route.guideMapId()) {
            return walkToNpcThenAct(entry, bot, route.guideNpcId(), now, runAiTick,
                    () -> guideAction.warpToPlatform(bot, route));
        }
        if (route.guideNpcId() != 0 && mapId == route.guideTargetMapId()) {
            return enterAdjacentPortal(entry, bot, route.usherNpcMapId(), now, runAiTick);
        }
        if (mapId == route.usherNpcMapId()) {
            if (!hasTicket) {
                return false; // ticket gone mid-walk — fall back, a re-plan walks back to the seller
            }
            if (!gateCheck.entryOpen(bot)) {
                // Stand at the gate until the boat docks; the wait is legitimate, so keep
                // the travel deadline from giving up under us.
                if (entry.followTravelDeadlineMs < now + LEG_BUDGET_MS) {
                    entry.followTravelDeadlineMs = now + LEG_BUDGET_MS;
                }
                return walkToNpcThenAct(entry, bot, route.usherNpcId(), now, runAiTick, () -> true);
            }
            return walkToNpcThenAct(entry, bot, route.usherNpcId(), now, runAiTick,
                    () -> boardAction.board(bot, route));
        }
        return false;
    }

    /** Walk within talking range of the NPC, then run the interaction (once in range). */
    private static boolean walkToNpcThenAct(BotEntry entry, Character bot, int npcId,
                                            long now, boolean runAiTick, java.util.function.BooleanSupplier act) {
        MapleMap map = bot.getMap();
        Point npcPos = BotTravelManager.taxiNpcLocator.locate(map, npcId);
        if (npcPos == null) {
            return false;
        }
        Point botPos = bot.getPosition();
        if (!entry.inAir && !entry.climbing
                && Math.abs(botPos.x - npcPos.x) + Math.abs(botPos.y - npcPos.y) <= NPC_TRIGGER_RADIUS_PX) {
            BotTravelManager.clearMoveTargetPin(entry);
            return act.getAsBoolean();
        }
        BotTravelManager.pinMoveTarget(entry, npcPos);
        BotTravelManager.movementStep.step(entry, npcPos, runAiTick);
        return true;
    }

    /** Walk to and enter the plain portal leading to the target map (cabin door, walkway). */
    private static boolean enterAdjacentPortal(BotEntry entry, Character bot, int targetMapId,
                                               long now, boolean runAiTick) {
        Portal portal = BotTravelManager.findAdjacentPortal(
                bot.getMap().getPortals(), targetMapId, bot.getPosition());
        if (portal == null) {
            return false;
        }
        return BotTravelManager.walkToPortalAndEnter(entry, bot, portal, now, runAiTick);
    }
}
