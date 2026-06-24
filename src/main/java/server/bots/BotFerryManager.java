package server.bots;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import scripting.event.EventManager;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.*;
import java.util.ArrayList;
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
                      List<Integer> boardingMapIds, String eventName, String boardingPortal) {

        /** Convenience ctor for NPC/ferry rides (no scripted boarding portal). */
        FerryRoute(int destinationMapId, int ticketItemId, int ticketCost,
                   int ticketNpcId, int ticketNpcMapId,
                   int usherNpcId, int usherNpcMapId,
                   int guideNpcId, int guideMapId, int guideTargetMapId,
                   int waitingMapId, int deckMapId, int cabinMapId,
                   List<Integer> boardingMapIds, String eventName) {
            this(destinationMapId, ticketItemId, ticketCost, ticketNpcId, ticketNpcMapId,
                    usherNpcId, usherNpcMapId, guideNpcId, guideMapId, guideTargetMapId,
                    waitingMapId, deckMapId, cabinMapId, boardingMapIds, eventName, "");
        }
    }

    // Ellinia station 101000300: seller 1032007 (4031045, 5k) and usher 1032008 in one map.
    static final FerryRoute ELLINIA_TO_ORBIS = new FerryRoute(
            200000100, 4031045, 5000,
            1032007, 101000300,
            1032008, 101000300,
            0, -1, -1,
            101000301, 200090010, 200090011,
            List.of(101000300), "Boats");

    // Orbis: seller 2012000 + platform guide 2012006 in the hall 200000100; the guide warps
    // to walkway 200000110 (west00), a plain portal leads to the pier 200000111 with usher
    // 2012001.
    static final FerryRoute ORBIS_TO_ELLINIA = new FerryRoute(
            101000300, 4031047, 5000,
            2012000, 200000100,
            2012001, 200000111,
            2012006, 200000100, 200000110,
            200000112, 200090000, 200090001,
            List.of(200000100, 200000110, 200000111), "Boats");

    // The other Orbis-hub lines: SAME station model as ORBIS_TO_ELLINIA - Agatha (2012000) sells the
    // ticket in hall 200000100, the station guide 2012006 warps to platform 200000110+sel*10 (Ludibrium
    // sel1=...120, Leafre sel2=...130, Ariant sel4=...150), the usher sits one map past it (+1) and
    // boards into the waiting room (+2); a per-line EventManager (gate property "entry", like Boats)
    // then rides the single deck to the destination station. No Balrog cabin on these (cabin = deck).
    // Verified: scripts/npc/2012006,2012013,2012021,2012025 + scripts/event/Trains,Cabin,Genie +
    // each usher's host map (Map.wz life).
    static final FerryRoute ORBIS_TO_LUDIBRIUM = new FerryRoute(
            220000100, 4031074, 6000,
            2012000, 200000100,
            2012013, 200000121,
            2012006, 200000100, 200000120,
            200000122, 200090100, 200090100,
            List.of(200000100, 200000120, 200000121), "Trains");

    static final FerryRoute ORBIS_TO_LEAFRE = new FerryRoute(
            240000100, 4031331, 30000,
            2012000, 200000100,
            2012021, 200000131,
            2012006, 200000100, 200000130,
            200000132, 200090200, 200090200,
            List.of(200000100, 200000130, 200000131), "Cabin");

    static final FerryRoute ORBIS_TO_ARIANT = new FerryRoute(
            260000100, 4031576, 6000,
            2012000, 200000100,
            2012025, 200000151,
            2012006, 200000100, 200000150,
            200000152, 200090400, 200090400,
            List.of(200000100, 200000150, 200000151), "Genie");

    // Return legs to Orbis: a far-station seller (the Orbis ticket 4031045) + a dock usher, no guide.
    // Ludibrium/Leafre have seller and usher on adjacent maps (one "east00" portal apart, walked by
    // the no-guide boarding step below); Ariant's seller 2102002 and usher 2102000 share one map
    // (single-map boarding like Ellinia). Decks + the gate event from scripts/event/{Trains,Cabin,Genie}.
    static final FerryRoute LUDIBRIUM_TO_ORBIS = new FerryRoute(
            200000100, 4031045, 6000,
            2040000, 220000100,
            2041000, 220000110,
            0, -1, -1,
            220000111, 200090110, 200090110,
            List.of(220000100, 220000110), "Trains");

    static final FerryRoute LEAFRE_TO_ORBIS = new FerryRoute(
            200000100, 4031045, 30000,
            2082000, 240000100,
            2082001, 240000110,
            0, -1, -1,
            240000111, 200090210, 200090210,
            List.of(240000100, 240000110), "Cabin");

    static final FerryRoute ARIANT_TO_ORBIS = new FerryRoute(
            200000100, 4031045, 6000,
            2102002, 260000100,
            2102000, 260000100,
            0, -1, -1,
            260000110, 200090410, 200090410,
            List.of(260000100), "Genie");

    // Solo rides (ticketItemId 0 = pay meso to the boat NPC, no ticket/usher/gate). The NPC warps the
    // bot onto the ride map (waiting=deck=cabin), whose coded MapleMap onUserEnter timer delivers it to
    // the destination (FROM_*_EREVE / FROM_*_RIEN handlers - run for bots, not behind the client guard).
    // Verified: scripts/npc/{1100008,1100004,1200004,1200003} + MapleMap.java:2364+ + MapId arrival ids.
    static final FerryRoute ORBIS_TO_EREVE = new FerryRoute(
            130000210, 0, 1000,
            1100008, 200000161,
            0, -1, 0, -1, -1,
            200090020, 200090020, 200090020,
            List.of(200000161), "");

    static final FerryRoute EREVE_TO_ORBIS = new FerryRoute(
            200000161, 0, 1000,
            1100004, 130000210,
            0, -1, 0, -1, -1,
            200090021, 200090021, 200090021,
            List.of(130000210), "");

    static final FerryRoute LITH_TO_RIEN = new FerryRoute(
            140020300, 0, 800,
            1200004, 104000000,
            0, -1, 0, -1, -1,
            200090060, 200090060, 200090060,
            List.of(104000000), "");

    static final FerryRoute RIEN_TO_LITH = new FerryRoute(
            104000000, 0, 800,
            1200003, 140020300,
            0, -1, 0, -1, -1,
            200090070, 200090070, 200090070,
            List.of(140020300), "");

    // EventManager rides (subway/train/crane/elevator) the WZ scan can't see. Same stateless boarding/transit
    // machinery as the ferries; three boarding flavours:
    //   - ticket+gate (Subway): identical to the Orbis lines - buy ticket, hand to usher, ride the gate event.
    //   - NPC start-instance (KerningTrain forward / Hak): walk to the NPC, pay (if any), call em.startInstance.
    //   - portal start-instance/gated (Depart_ToKerning / elevator): walk the real scripted boarding portal and
    //     enter it; its own script runs the gate/startInstance/warp (boardingPortal != "").
    // Verified vs scripts/npc/{1052007,9201057,9201068,2090005} + scripts/event/{Subway,KerningTrain,Hak} +
    // scripts/portal/{elevator,Depart_ToKerning} + Map.wz portals/life.

    // Kerning City <-> New Leaf City subway (Subway event, ticket 4031711 KC / 4031713 NLC, 5k each way).
    static final FerryRoute KC_TO_NLC = new FerryRoute(
            600010001, 4031711, 5000,
            9201057, 103000100,
            1052007, 103000100,
            0, -1, -1,
            600010004, 600010005, 600010005,
            List.of(103000100), "Subway");

    static final FerryRoute NLC_TO_KC = new FerryRoute(
            103000100, 4031713, 5000,
            9201057, 600010001,
            9201068, 600010001,
            0, -1, -1,
            600010002, 600010003, 600010003,
            List.of(600010001), "Subway");

    // Kerning City <-> Kerning Square (KerningTrain event). KC->Square boards at the ticket gate 1052007
    // (free); Square->KC boards at the scripted portal out00 (Depart_ToKerning). Lands at the station, a
    // plain enter00 portal then reaches the Kerning Square town 103040000.
    static final FerryRoute KC_TO_KSQUARE = new FerryRoute(
            103000310, 0, 0,
            1052007, 103000100,
            0, -1, 0, -1, -1,
            103000301, 103000301, 103000301,
            List.of(103000100), "KerningTrain");

    static final FerryRoute KSQUARE_TO_KC = new FerryRoute(
            103000100, 0, 0,
            0, 103000310,
            0, -1, 0, -1, -1,
            103000302, 103000302, 103000302,
            List.of(103000310), "KerningTrain", "out00");

    // Orbis <-> Mu Lung (Hak crane event, 1500 meso). Orbis side boards at the cabin 200000141 (reached from
    // Orbis station 200000100 by plain portals 200000100->200000140->200000141); Mu Lung side boards at the
    // temple 250000100 (west00 -> Mu Lung town 250000000).
    static final FerryRoute ORBIS_TO_MULUNG = new FerryRoute(
            250000100, 0, 1500,
            2090005, 200000141,
            0, -1, 0, -1, -1,
            200090300, 200090300, 200090300,
            List.of(200000141), "Hak");

    static final FerryRoute MULUNG_TO_ORBIS = new FerryRoute(
            200000141, 0, 1500,
            2090005, 250000100,
            0, -1, 0, -1, -1,
            200090310, 200090310, 200090310,
            List.of(250000100), "Hak");

    // Helios Tower 2nd floor <-> 99th floor (Elevator event, no npc/ticket/meso). Boards by walking the
    // scripted elevator portal in00; its script warps onto the waiting map when the gate is open and the
    // event delivers to the far floor. Intra-tower convenience link.
    static final FerryRoute HELIOS_UP = new FerryRoute(
            222020200, 0, 0,
            0, 222020100,
            0, -1, 0, -1, -1,
            222020110, 222020111, 222020111,
            List.of(222020100), "Elevator", "in00");

    static final FerryRoute HELIOS_DOWN = new FerryRoute(
            222020100, 0, 0,
            0, 222020200,
            0, -1, 0, -1, -1,
            222020210, 222020211, 222020211,
            List.of(222020200), "Elevator", "in00");

    // Note: Orbis <-> El Nath (211000000) is NOT a ferry here, and doesn't need to be - El Nath is
    // walk-reachable via the Orbis Tower (El Nath 211000000 -> 211000200 -> Orbis Tower 200082100 -> ...
    // -> Orbis), so the bot graph reaches it by plain portals. The instant Orbis<->El Nath ship is just
    // an unmodeled shortcut. (Sharp Cliff I is gated behind Jeff's NPC warp - see BotWorldGraph TAXI_EDGES.)
    private static final List<FerryRoute> ROUTES = List.of(
            ELLINIA_TO_ORBIS, ORBIS_TO_ELLINIA,
            ORBIS_TO_LUDIBRIUM, ORBIS_TO_LEAFRE, ORBIS_TO_ARIANT,
            LUDIBRIUM_TO_ORBIS, LEAFRE_TO_ORBIS, ARIANT_TO_ORBIS,
            ORBIS_TO_EREVE, EREVE_TO_ORBIS, LITH_TO_RIEN, RIEN_TO_LITH,
            KC_TO_NLC, NLC_TO_KC, KC_TO_KSQUARE, KSQUARE_TO_KC,
            ORBIS_TO_MULUNG, MULUNG_TO_ORBIS, HELIOS_UP, HELIOS_DOWN);

    // A hub map (Orbis 200000100) carries SEVERAL ferry lines, so each boarding map maps to a LIST.
    private static final Map<Integer, List<FerryRoute>> BOARDING_MAP_TO_ROUTES = buildBoardingIndex();
    private static final Map<Integer, FerryRoute> TRANSIT_MAP_TO_ROUTE = buildTransitIndex();

    private static Map<Integer, List<FerryRoute>> buildBoardingIndex() {
        Map<Integer, List<FerryRoute>> byMap = new HashMap<>();
        for (FerryRoute route : ROUTES) {
            for (int mapId : route.boardingMapIds()) {
                byMap.computeIfAbsent(mapId, k -> new ArrayList<>()).add(route);
            }
        }
        byMap.replaceAll((k, v) -> List.copyOf(v));
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

    /** All ferry lines boardable from this map (a hub like Orbis has several); empty when none.
     *  Feeds the world-graph + travel-cost edges. */
    static List<FerryRoute> routesBoardingAt(int mapId) {
        return BOARDING_MAP_TO_ROUTES.getOrDefault(mapId, List.of());
    }

    /** One-line ferry-leg diagnostic for the path log. A ferry "deadline" give-up otherwise can't be
     *  told apart from a legitimate scheduled wait: this shows whether the bot is ON the ride (waiting
     *  for the boat), waiting at a CLOSED gate (legit — the deadline is being re-armed), or has fallen
     *  OFF the boarding chain entirely (the real can't-reach failure). */
    static String describeLeg(BotEntry entry, Character bot) {
        if (bot == null) {
            return "no-bot";
        }
        int map = bot.getMapId();
        FerryRoute transit = TRANSIT_MAP_TO_ROUTE.get(map);
        if (transit != null) {
            return "onRide(map=" + map + " -> " + transit.destinationMapId() + ") waiting for the boat";
        }
        FerryRoute route = findFerryEdge(map, entry.followTravelNextHopMapId);
        if (route == null) {
            return "OFF the boarding chain (map=" + map + " is not a boarding/transit map of this hop)"
                    + " -> can't-reach failure, not a wait";
        }
        boolean gateOpen = route.eventName().isEmpty() || gateCheck.entryOpen(bot, route.eventName());
        return "boardingMap=" + map + " gateOpen=" + gateOpen
                + (route.eventName().isEmpty() ? "" : " event=" + route.eventName())
                + (gateOpen ? "" : " [legit wait — deadline re-armed each tick]");
    }

    /** The ferry edge from one map to a specific destination, or null when no line sails that way. */
    static FerryRoute findFerryEdge(int fromMapId, int toMapId) {
        for (FerryRoute route : routesBoardingAt(fromMapId)) {
            if (route.destinationMapId() == toMapId) {
                return route;
            }
        }
        return null;
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
    interface StartInstanceAction {
        boolean start(Character bot, FerryRoute route);
    }

    @FunctionalInterface
    interface GateCheck {
        boolean entryOpen(Character bot, String eventName);
    }

    @FunctionalInterface
    interface ThreatCheck {
        boolean invaded(Character bot, String eventName);
    }

    @FunctionalInterface
    interface CrewLookup {
        java.util.List<BotEntry> matesOnMap(Character bot);
    }

    static CrewLookup crewLookup = bot -> BotManager.getInstance().crewMatesOnMap(bot);

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

    static StartInstanceAction startInstanceAction = (bot, route) -> {
        // Mirrors the NPC script: start a per-player event instance (KerningTrain/Hak), which warps the bot
        // onto the ride map and schedules the drop-off. Returns false when the lobby is full.
        EventManager em = bot.getClient().getChannelServer().getEventSM().getEventManager(route.eventName());
        return em != null && em.startInstance(bot);
    };

    static GateCheck gateCheck = (bot, eventName) -> "true".equals(eventProperty(bot, eventName, "entry"));

    static ThreatCheck threatCheck = (bot, eventName) -> {
        if ("true".equals(eventProperty(bot, eventName, "haveBalrog"))) { // only Boats sets this; others null
            return true;
        }
        MapleMap map = bot.getMap();
        return map != null && map.getAllMonsters().stream().anyMatch(server.life.Monster::isAlive);
    };

    private static String eventProperty(Character bot, String eventName, String key) {
        EventManager em = bot.getClient().getChannelServer().getEventSM().getEventManager(eventName);
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
        boolean movingForCabin = false;
        if (bot.getMapId() == route.deckMapId()
                && (threatCheck.invaded(bot, route.eventName()) || targetMapId == route.cabinMapId())) {
            enterAdjacentPortal(entry, bot, route.cabinMapId(), now, runAiTick);
            movingForCabin = true;
        } else if (bot.getMapId() == route.cabinMapId()
                && targetMapId == route.deckMapId() && !threatCheck.invaded(bot, route.eventName())) {
            enterAdjacentPortal(entry, bot, route.deckMapId(), now, runAiTick);
            movingForCabin = true;
        }
        if (!movingForCabin) {
            tickFerryStanding(entry, bot, now, runAiTick, null); // mill about the deck / waiting room
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

        // Scripted-portal boarding (Kerning Square return / Helios elevator): walk the real boarding portal
        // and enter it; the portal's own script runs the gate check / startInstance / warp onto the ride map.
        // Keep the travel deadline alive while we wait for the gate (the elevator cycles on a timer).
        if (!route.boardingPortal().isEmpty()) {
            Portal portal = bot.getMap() == null ? null : bot.getMap().getPortal(route.boardingPortal());
            if (portal == null) {
                return false;
            }
            if (entry.followTravelDeadlineMs < now + LEG_BUDGET_MS) {
                entry.followTravelDeadlineMs = now + LEG_BUDGET_MS;
            }
            return BotTravelManager.walkToPortalAndEnter(entry, bot, portal, now, runAiTick);
        }

        // Solo ride (ticketItemId 0): no ticket item, no usher, no gate. Walk to the boat NPC, pay the meso
        // fare, and board. Two delivery models: the Ereve/Rien sky-whales warp onto a ride map whose coded
        // onUserEnter timer delivers it; the KerningTrain/Hak rides (eventName set) start a per-player event
        // instance that warps the bot onto the ride map and schedules the drop-off.
        if (route.ticketItemId() == 0) {
            if (mapId != route.ticketNpcMapId() || bot.getMeso() < route.ticketCost()) {
                return false;
            }
            if (!route.eventName().isEmpty()) {
                return walkToNpcThenAct(entry, bot, route.ticketNpcId(), now, runAiTick, () -> {
                    if (!startInstanceAction.start(bot, route)) {
                        return false; // lobby full -> normal failed hop, the caller re-plans
                    }
                    if (route.ticketCost() > 0) {
                        bot.gainMeso(-route.ticketCost(), false);
                    }
                    return true;
                });
            }
            return walkToNpcThenAct(entry, bot, route.ticketNpcId(), now, runAiTick, () -> {
                MapleMap ride = bot.getClient().getChannelServer().getMapFactory().getMap(route.waitingMapId());
                if (ride == null) {
                    return false;
                }
                bot.gainMeso(-route.ticketCost(), false);
                bot.changeMap(ride);
                return true;
            });
        }

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
        // No-guide return station: ticket bought at the seller's map, dock usher on an adjacent map.
        // Walk the plain portal from here to the usher's map. (Same-map boardings skip this.)
        if (route.guideNpcId() == 0 && hasTicket && mapId != route.usherNpcMapId()
                && route.boardingMapIds().contains(mapId)) {
            return enterAdjacentPortal(entry, bot, route.usherNpcMapId(), now, runAiTick);
        }
        if (mapId == route.usherNpcMapId()) {
            if (!hasTicket) {
                return false; // ticket gone mid-walk — fall back, a re-plan walks back to the seller
            }
            if (!gateCheck.entryOpen(bot, route.eventName())) {
                // Wait near the gate until the boat docks; the wait is legitimate, so keep the travel
                // deadline from giving up under us. Mill about + fidget (crew holds formation) instead
                // of freezing on the usher's pixel; the board step below walks back when the gate opens.
                if (entry.followTravelDeadlineMs < now + LEG_BUDGET_MS) {
                    entry.followTravelDeadlineMs = now + LEG_BUDGET_MS;
                }
                Point usherPos = BotTravelManager.taxiNpcLocator.locate(bot.getMap(), route.usherNpcId());
                tickFerryStanding(entry, bot, now, runAiTick, usherPos);
                return true;
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
        boolean inRange = !entry.inAir && !entry.climbing
                && Math.abs(botPos.x - npcPos.x) + Math.abs(botPos.y - npcPos.y) <= NPC_TRIGGER_RADIUS_PX;
        // Stuck-near fallback (shop-visit SSOT): a ferry ticket/usher NPC can sit on a dock ledge the walk
        // can't stand exactly on — if the bot got near and stopped progressing, interact from here rather
        // than timing out on the pier (the "deadline" boarding failures from Ariant et al.).
        boolean stuckNearNpc = BotTravelManager.stuckNear(
                entry.travelApproachStuck, botPos, npcPos, now, NPC_TRIGGER_RADIUS_PX);
        // Last resort once the approach budget lapses: a ferry seller/usher is clickable map-wide in the
        // real client (the script just warps/sells on click), so rather than fail the ferry hop beside a
        // dock NPC the nav can't stand on (the Ariant Genie pier), interact from here. Mirrors the town-cab
        // hail in tickTaxiHop; ferry hops are exempt from the generic deadline give-up so this is reached.
        // ponytail: the walk above still runs the full budget first — hail only fires after it lapses.
        boolean deadlineHail = entry.followTravelDeadlineMs > 0 && now >= entry.followTravelDeadlineMs;
        if (inRange || stuckNearNpc || deadlineHail) {
            BotTravelManager.clearMoveTargetPin(entry);
            if (!BotManager.npcDwellReady(entry, BotManager.NPC_TALK_DELAY_MS, BotManager.NPC_TALK_JITTER_MS)) {
                return true; // pause a beat at the NPC before buying/boarding
            }
            return act.getAsBoolean();
        }
        BotManager.npcDwellReset(entry);
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

    // --- Humanlike standing while waiting for / riding a ferry -----------------------------------------
    // Bots don't freeze on the deck: each loiters at a random reachable spot and fidgets there on a
    // jittered, desynced timer (so a crowd doesn't twitch in unison). In a crew only the leader (lowest
    // char id on the map) picks a spot; the rest hold a stagger formation behind it, reusing the same
    // follow-formation offsets. Pure in-map movement — it never throws a bot off the boat.

    private static final int FERRY_STAND_SPREAD_PX = 110; // how far a loiter spot can wander from the anchor
    private static final int FERRY_ARRIVE_PX = 18;        // "settled at my spot" tolerance
    private static final int FERRY_REPICK_MIN_MS = 9_000; // wander to a new spot every ~9-22s, jittered
    private static final int FERRY_REPICK_MAX_MS = 22_000;

    /**
     * One idle tick of standing on a ferry (waiting platform or ride deck). Crew followers hold a stagger
     * formation behind the leader; the leader / a soloist loiters at a self-picked reachable spot and
     * fidgets there. {@code anchorPos} centres the loiter (the usher while waiting at the gate, else null
     * to centre on the bot's own footing on the deck).
     */
    static void tickFerryStanding(BotEntry entry, Character bot, long now, boolean runAiTick, Point anchorPos) {
        if (bot.getMap() == null) {
            return;
        }
        if (entry.ferryStandMapId != bot.getMapId()) { // crossed onto a new ferry map — old spot is stale
            entry.ferryStandSpot = null;
            entry.ferryStandRepickAtMs = 0L;
            entry.ferryStandMapId = bot.getMapId();
            BotFidgetManager.clear(entry);
        }
        if (stepCrewFormation(entry, bot, runAiTick)) {
            return; // a follower: mirror the leader, no spot of our own
        }

        Point center = anchorPos != null ? anchorPos : bot.getPosition();
        Point spot = entry.ferryStandSpot;
        if (runAiTick && (spot == null || now >= entry.ferryStandRepickAtMs)) {
            spot = BotTravelManager.pickReachableApproachPoint(entry, bot, center, FERRY_STAND_SPREAD_PX);
            entry.ferryStandSpot = spot;
            entry.ferryStandRepickAtMs = now + BotManager.randMs(FERRY_REPICK_MIN_MS, FERRY_REPICK_MAX_MS);
        }
        if (spot == null) {
            return;
        }

        Point botPos = bot.getPosition();
        if (Math.abs(botPos.x - spot.x) + Math.abs(botPos.y - spot.y) > FERRY_ARRIVE_PX) {
            BotTravelManager.pinMoveTarget(entry, spot);
            BotTravelManager.movementStep.step(entry, spot, runAiTick);
            return;
        }
        BotTravelManager.clearMoveTargetPin(entry);
        BotFidgetManager.tickStandingFidget(entry, spot, now, runAiTick);
    }

    /**
     * If {@code bot} is a non-leader member of a crew sharing this map, step it onto its stagger slot behind
     * the crew leader (lowest char id) and return true. Returns false for soloists and the leader itself,
     * which then loiter on their own.
     */
    private static boolean stepCrewFormation(BotEntry entry, Character bot, boolean runAiTick) {
        java.util.List<BotEntry> mates = crewLookup.matesOnMap(bot);
        if (mates.isEmpty()) {
            return false;
        }
        java.util.List<BotEntry> crew = new ArrayList<>(mates);
        crew.add(entry);
        crew.sort(java.util.Comparator.comparingInt(e -> e.bot.getId()));
        if (crew.get(0) == entry) {
            return false; // we're the leader — loiter normally
        }
        java.util.List<BotEntry> followers = crew.subList(1, crew.size());
        int slot = followers.indexOf(entry);
        int offsetX = BotManager.FormationState.defaultStagger().offsetFor(slot, followers.size());
        Point leaderPos = crew.get(0).bot.getPosition();
        Point target = new Point(leaderPos.x + offsetX, leaderPos.y);

        Point botPos = bot.getPosition();
        if (Math.abs(botPos.x - target.x) + Math.abs(botPos.y - target.y) > FERRY_ARRIVE_PX) {
            BotTravelManager.pinMoveTarget(entry, target);
            BotTravelManager.movementStep.step(entry, target, runAiTick);
        } else {
            BotTravelManager.clearMoveTargetPin(entry);
        }
        return true;
    }
}
