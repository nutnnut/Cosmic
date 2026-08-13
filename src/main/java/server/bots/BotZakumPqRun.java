/*
    This file is part of the OdinMS Maple Story Server.
    Bot Zakum PQ in-instance run machine (AI companion feature).
*/
package server.bots;

import client.BotClient;
import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Reactor;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * SSOT in-instance driver for the Zakum PQ maze (maps 280010000-280011006). ONE machine serves both
 * run modes:
 * <ul>
 *   <li><b>Autonomous</b> ({@link BotZakumPrequestManager} errand): the bot party leader runs the
 *       LEADER duties (Giant Chest, Fire Ore, Aura turn-in) and sweeps its share of key rooms; crew
 *       members run the WORKER role.</li>
 *   <li><b>Player-led</b> ({@link server.bots.pq.BotPqHooks}, owner online): every bot runs the
 *       WORKER role for the human run leader — they break the chests, courier the keys to the
 *       player's feet, and a single spokesbot chat-hints each stage so a first-timer knows what to
 *       do (drop the 7-stack, take the ore to Aura, claim from Aura after the clear). The human does
 *       the leader duties; bots cannot do them for a player.</li>
 * </ul>
 *
 * <p>Workers sweep a stable partition of the 7 key rooms in parallel (room {@code i} belongs to
 * roster member {@code i % rosterSize}; roster = party bots inside the maze, sorted by char id, so
 * every member computes the same split with no coordination). Room completion is read straight off
 * each room's reactor through {@link EventInstanceManager#getMapInstance}, so the whole machine is
 * stateless per tick — a mid-run death, relog, or roster change just re-partitions.
 *
 * <p>Passive loot NEVER takes the PQ items (keys/ore/documents — see {@link BotLootEligibility});
 * this machine picks them up explicitly by role, which is what keeps a worker from vacuuming the
 * leader's delivered pile or the 7-stack pending under the Giant Chest.
 */
public final class BotZakumPqRun {

    private static final Logger log = LoggerFactory.getLogger(BotZakumPqRun.class);

    private BotZakumPqRun() {}

    // ---- geography (verified against ZakumPQ.js and wz/Map.wz placements) ----------------------

    static final int PQ_ENTRY_MAP = 280010000;             // hub; Aura (turn-in) lives here
    static final int PQ_CHEST_MAP = 280011005;             // Giant Chest room (also last key chest)
    private static final int PQ_MIN_MAP = 280010000, PQ_MAX_MAP = 280011006;
    static final int AURA = 2032002;                       // PQ turn-in NPC (280010000)
    private static final int REACTOR_CHEST = 2112014;      // opens on a 7-key drop in its box
    private static final int PQ_TURNIN_EXP = 12_000;       // exp Aura's no-documents turn-in grants

    /** One key chest: the maze room and its reactor. Fresh instances spawn all 7 (exactly the 7 keys
     *  the Giant Chest wants); order follows the hub's branch numbering for a sane walk. */
    record KeyRoom(int mapId, int reactorId) {}

    static final List<KeyRoom> KEY_ROOMS = List.of(
            new KeyRoom(280010041, 2112011),
            new KeyRoom(280010091, 2112004),
            new KeyRoom(280010110, 2112004),
            new KeyRoom(280010140, 2112004),
            new KeyRoom(280011002, 2112004),
            new KeyRoom(280011003, 2112004),
            new KeyRoom(280011005, 2112011));

    // ---- tuning --------------------------------------------------------------------------------

    /** Stand-off distance that counts as "at the reactor" (its trigger boxes span ~100px). */
    private static final int REACTOR_REACH_PX = 40;
    /** How long to stand at the Giant Chest waiting for the dropped keys to be consumed (the server
     *  schedules ActivateItemReactor 5s after the drop) before re-dropping is considered. */
    static final long CHEST_ACTIVATE_WAIT_MS = 12_000L;
    /** A worker hands keys over within this range of the run leader; keys already lying inside it
     *  are the leader's delivered pile and never count as sweep work. */
    private static final int DELIVER_RADIUS_PX = 80;
    private static final int DELIVER_ZONE_PX = 140;
    /** Keys resting this close to the Giant Chest are the pending 7-stack — never sweep work. */
    private static final int CHEST_ZONE_PX = 90;
    /** Idle stand-off while hovering near the leader between jobs. */
    private static final int HOVER_RADIUS_PX = 260;

    // ---- chat hints (player-led runs only; ASCII per the v83 client) ---------------------------

    private static final int HINT_ENTER = 1;
    private static final int HINT_DELIVER = 1 << 1;
    private static final int HINT_STACK = 1 << 2;
    private static final int HINT_ORE = 1 << 3;
    private static final int HINT_CLAIM = 1 << 4;

    /** Bot chat seam (tests swap it; production routes through the bot's channel). */
    static java.util.function.BiConsumer<BotEntry, String> reply =
            (entry, text) -> BotManager.getInstance().botReply(entry, text);

    public static boolean isPqMap(int mapId) {
        return mapId >= PQ_MIN_MAP && mapId <= PQ_MAX_MAP;
    }

    // ---- supervised (player-led) entry point ---------------------------------------------------

    /**
     * One tick of a companion bot inside the maze on a player-led run. Returns true while the run
     * machine owns the tick (always, on the PQ maps); false lets the normal companion flow run.
     * Never active for the autonomous errand — that path enters via {@link #tickWorker} directly.
     */
    public static boolean tickSupervised(BotEntry entry, Character bot, Character owner) {
        if (!isPqMap(bot.getMapId())) {
            entry.zakumHintMask = 0; // fresh hints for the next run
            return false;
        }
        if (BotZakumPrequestManager.active(entry)) {
            return false; // autonomous errand owns the maze tick (tickErrand disarms when supervised)
        }
        EventInstanceManager eim = bot.getEventInstance();
        if (eim == null) {
            warpToDoor(bot); // dead maze (relog race / event timeout) — leave, follow resumes outside
            return true;
        }
        Character leader = humanRunLeader(bot, owner);
        if (leader == null || (!isPqMap(leader.getMapId()) && !eim.isEventCleared())) {
            warpToDoor(bot); // the player left the run — walk out after them
            return true;
        }
        return tickWorker(entry, bot, leader, true, true);
    }

    /** The human this run works for: the party leader when that is a live human, else the owner. */
    private static Character humanRunLeader(Character bot, Character owner) {
        Party party = bot.getParty();
        if (party != null) {
            for (PartyCharacter pc : party.getMembers()) {
                Character ch = pc != null ? pc.getPlayer() : null;
                if (ch != null && ch.getId() == party.getLeaderId() && ch.isLoggedinWorld()
                        && !(ch.getClient() instanceof BotClient)) {
                    return ch;
                }
            }
        }
        return owner;
    }

    // ---- worker role (both modes) --------------------------------------------------------------

    /**
     * One tick of a WORKER: sweep my share of the key rooms, courier what I hold to the run leader
     * once my share is done, then hover near the leader; claim my Breath share and walk out once the
     * PQ clears. Always consumes the tick.
     */
    static boolean tickWorker(BotEntry entry, Character bot, Character leader, boolean leaderIsHuman,
                              boolean runAiTick) {
        EventInstanceManager eim = bot.getEventInstance();
        if (eim == null) {
            warpToDoor(bot);
            return true;
        }
        if (eim.isEventCleared()) {
            if (leaderIsHuman) {
                hintOnce(entry, bot, HINT_CLAIM,
                        "cleared! talk to aura - everyone in the party gets a breath of fire");
            }
            claimShareAndLeave(entry, bot);
            return true;
        }
        if (leaderIsHuman) {
            hintOnce(entry, bot, HINT_ENTER,
                    "we'll smash the key boxes and bring you the keys - "
                                + "wait by the giant chest in the deepest room");
            maybeStageHints(entry, bot, leader);
        }
        boolean workLeft = nextAssignedRoom(bot, leader, eim) != -1;
        int held = bot.getItemQuantity(BotZakumPrequestManager.ITEM_KEY, false);
        if (held > 0 && !workLeft) {
            return deliverKeys(entry, bot, leader, leaderIsHuman, held, runAiTick);
        }
        if (workLeft && sweepRooms(entry, bot, leader, eim, runAiTick)) {
            return true;
        }
        return hoverNear(entry, bot, leader.getPosition(), runAiTick);
    }

    /** Walk the held key stack to the run leader and set it down at their feet. */
    private static boolean deliverKeys(BotEntry entry, Character bot, Character leader,
                                       boolean leaderIsHuman, int held, boolean runAiTick) {
        if (bot.getMapId() != leader.getMapId()) {
            return travelInsidePq(entry, bot, leader.getMapId(), runAiTick);
        }
        if (!near(bot, leader.getPosition(), DELIVER_RADIUS_PX)) {
            walkTo(entry, bot, leader.getPosition(), runAiTick);
            return true;
        }
        if (!BotManager.npcDwellReady(entry, BotManager.NPC_READ_DELAY_MS, BotManager.NPC_READ_JITTER_MS)) {
            return true; // a beat before handing over
        }
        if (BotManager.getInstance().issueDropItem(entry, InventoryType.ETC,
                BotZakumPrequestManager.ITEM_KEY, (short) held)) {
            BotManager.npcDwellReset(entry);
            if (leaderIsHuman) {
                hintOnce(entry, bot, HINT_DELIVER, "keys at your feet - grab em");
            }
        }
        return true;
    }

    /** Tutorial nudges for the stages only the human can perform. */
    private static void maybeStageHints(BotEntry entry, Character bot, Character leader) {
        if (leader.getItemQuantity(BotZakumPrequestManager.ITEM_KEY, false)
                >= BotZakumPrequestManager.KEYS_NEEDED) {
            hintOnce(entry, bot, HINT_STACK,
                    "drop all 7 keys in ONE stack right under the giant chest to open it");
        }
        if (leader.getMapId() == PQ_CHEST_MAP && leader.getMap() != null
                && findDrop(leader.getMap(), BotZakumPrequestManager.ITEM_FIRE_ORE) != null) {
            hintOnce(entry, bot, HINT_ORE,
                    "grab the fire ore and take it to aura at the entrance");
        }
    }

    /** Say it once per run, and only from the spokesbot (lowest bot char id in the maze roster) so
     *  a full party doesn't chorus every hint. */
    private static void hintOnce(BotEntry entry, Character bot, int hintBit, String text) {
        if ((entry.zakumHintMask & hintBit) != 0) {
            return;
        }
        entry.zakumHintMask |= hintBit;
        List<Character> roster = mazeRoster(bot);
        if (!roster.isEmpty() && roster.get(0).getId() != bot.getId()) {
            return; // marked seen anyway: if the spokesbot missed it, nobody repeats it later
        }
        reply.accept(entry, text);
    }

    // ---- shared key-room sweep -----------------------------------------------------------------

    /**
     * One tick of partitioned key-collection. Loot-first on the current map (outside the delivery /
     * chest zones), else work the first assigned room that still has an active chest or a stray key.
     * Returns false when none of this bot's rooms have work left.
     */
    static boolean sweepRooms(BotEntry entry, Character bot, Character leader,
                              EventInstanceManager eim, boolean runAiTick) {
        MapItem key = wantedKeyOutsideZones(bot.getMap(), bot, leader);
        if (key != null && bot.canHold(BotZakumPrequestManager.ITEM_KEY, 1)) {
            stepTowardsAndPickUp(entry, bot, key, runAiTick);
            return true;
        }
        int roomIdx = nextAssignedRoom(bot, leader, eim);
        // TEMP-DIAG(zakumpq): remove once the key-drop pipeline is verified live.
        if (entry.zakumPqDiagAtMs + 5_000 < System.currentTimeMillis()) {
            entry.zakumPqDiagAtMs = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();
            for (KeyRoom r : KEY_ROOMS) {
                MapleMap m = eim.getMapInstance(r.mapId());
                Reactor c = m != null ? m.getReactorById(r.reactorId()) : null;
                sb.append(r.mapId() % 1000).append(c == null ? ":null" : ":s" + c.getState()
                        + "t" + c.getReactorType() + (c.isActive() ? "A" : "-")).append(' ');
            }
            log.info("zakumpq {} @{} keys={} nextRoom={} rooms[{}]", bot.getName(), bot.getMapId(),
                    bot.getItemQuantity(BotZakumPrequestManager.ITEM_KEY, false), roomIdx, sb.toString().trim());
        }
        if (roomIdx == -1) {
            return false;
        }
        KeyRoom room = KEY_ROOMS.get(roomIdx);
        if (bot.getMapId() != room.mapId()) {
            return travelInsidePq(entry, bot, room.mapId(), runAiTick);
        }
        Reactor chest = bot.getMap() != null ? bot.getMap().getReactorById(room.reactorId()) : null;
        if (chest == null || !chest.isActive()) {
            // Room already counted as work because of a floor key the zone filter skipped (e.g. it
            // sits in the leader's delivery pile on this map) — nothing for us here after all.
            return false;
        }
        Point chestPos = chest.getPosition();
        if (!near(bot, chestPos, REACTOR_REACH_PX)) {
            walkTo(entry, bot, chestPos, runAiTick);
            return true;
        }
        if (!BotManager.npcDwellReady(entry, BotManager.NPC_READ_DELAY_MS, BotManager.NPC_READ_JITTER_MS)) {
            return true; // humanlike beat before smashing the chest
        }
        hitReactor(bot, chest);
        entry.zakumErrandProgress.touch(System.currentTimeMillis());
        return true;
    }

    /**
     * The first KEY_ROOMS index assigned to this bot that still has work: an alive chest, or a key
     * lying on its floor outside the delivery/chest zones. -1 when this bot's share is done.
     * Room {@code i} belongs to roster member {@code i % rosterSize} — see {@link #mazeRoster}.
     */
    static int nextAssignedRoom(Character bot, Character leader, EventInstanceManager eim) {
        List<Character> roster = mazeRoster(bot);
        int myIdx = rosterIndex(roster, bot.getId());
        if (myIdx == -1) {
            return -1;
        }
        for (int i = 0; i < KEY_ROOMS.size(); i++) {
            if (!roomAssignedTo(i, myIdx, roster.size())) {
                continue;
            }
            KeyRoom room = KEY_ROOMS.get(i);
            MapleMap map = eim.getMapInstance(room.mapId());
            if (map == null) {
                continue;
            }
            Reactor chest = map.getReactorById(room.reactorId());
            if (chest != null && chest.isActive()) {
                return i;
            }
            if (wantedKeyOutsideZones(map, bot, leader) != null) {
                return i; // chest is down but its key still lies there — go fetch it
            }
        }
        return -1;
    }

    /** Pure partition rule (unit-tested): room i belongs to roster member i % count. */
    static boolean roomAssignedTo(int roomIdx, int workerIdx, int workerCount) {
        return workerCount > 0 && roomIdx % workerCount == workerIdx;
    }

    /** Party bots currently inside the maze, sorted by char id — the stable worker roster every
     *  member derives the same room partition (and spokesbot) from without coordination. */
    private static List<Character> mazeRoster(Character bot) {
        List<Character> roster = new ArrayList<>();
        Party party = bot.getParty();
        if (party == null) {
            roster.add(bot);
            return roster;
        }
        for (Character member : bot.getPartyMembersOnline()) {
            if (member != null && member.getClient() instanceof BotClient && isPqMap(member.getMapId())) {
                roster.add(member);
            }
        }
        if (roster.isEmpty()) {
            roster.add(bot);
        }
        roster.sort(java.util.Comparator.comparingInt(Character::getId));
        return roster;
    }

    private static int rosterIndex(List<Character> roster, int charId) {
        for (int i = 0; i < roster.size(); i++) {
            if (roster.get(i).getId() == charId) {
                return i;
            }
        }
        return -1;
    }

    /** Keys the run still counts on: held by roster bots, held by the leader, or on any maze floor.
     *  Below 7 with every chest down, the run is unrecoverable (a drop expired). */
    static int totalKeysInPlay(EventInstanceManager eim, Character bot, Character leader) {
        int total = leader != null ? leader.getItemQuantity(BotZakumPrequestManager.ITEM_KEY, false) : 0;
        for (Character member : mazeRoster(bot)) {
            if (leader == null || member.getId() != leader.getId()) {
                total += member.getItemQuantity(BotZakumPrequestManager.ITEM_KEY, false);
            }
        }
        for (KeyRoom room : KEY_ROOMS) {
            MapleMap map = eim.getMapInstance(room.mapId());
            if (map == null) {
                continue;
            }
            Reactor chest = map.getReactorById(room.reactorId());
            if (chest != null && chest.isActive()) {
                total++; // unbroken chest still owes its key
            }
            MapItem floorKey = findDrop(map, BotZakumPrequestManager.ITEM_KEY);
            if (floorKey != null) {
                total += floorKey.getItem() != null ? floorKey.getItem().getQuantity() : 1;
            }
        }
        return total;
    }

    // ---- leader duties (autonomous leader only; a human does these by hand) --------------------

    /** At the Giant Chest with 7 keys: drop the one 7-stack inside its trigger box, then hold still
     *  while the server's ActivateItemReactor (5s) consumes it and the chest spills the Fire Ore.
     *  Returns false when the run is unrecoverable (caller aborts). */
    static boolean tickGiantChest(BotEntry entry, Character bot, boolean runAiTick) {
        long now = System.currentTimeMillis();
        MapleMap map = bot.getMap();
        MapItem ore = findDrop(map, BotZakumPrequestManager.ITEM_FIRE_ORE);
        if (ore != null) {
            entry.zakumPqChestDropAtMs = 0L;
            stepTowardsAndPickUp(entry, bot, ore, runAiTick);
            return true;
        }
        Reactor chest = map != null ? map.getReactorById(REACTOR_CHEST) : null;
        if (chest == null || chest.getState() > 0) {
            // Chest already open but no ore on the floor (looted / drop expired): unrecoverable —
            // unless we are still inside the activation window waiting for the pickup to fire.
            return entry.zakumPqChestDropAtMs != 0L && now - entry.zakumPqChestDropAtMs <= CHEST_ACTIVATE_WAIT_MS;
        }
        if (entry.zakumPqChestDropAtMs != 0L) {
            if (now - entry.zakumPqChestDropAtMs <= CHEST_ACTIVATE_WAIT_MS) {
                entry.zakumErrandProgress.touch(now);
                return true; // standing by while the 5s item-reactor pickup fires
            }
            entry.zakumPqChestDropAtMs = 0L; // activation never came (drop bounced?) — re-approach
        }
        Point chestPos = chest.getPosition();
        // The trigger box is lt(-78,-67)..rb(29,25) around the reactor: stand basically on it.
        if (!near(bot, chestPos, 25)) {
            walkTo(entry, bot, chestPos, runAiTick);
            return true;
        }
        Item stack = bot.getInventory(InventoryType.ETC) != null
                ? bot.getInventory(InventoryType.ETC).findById(BotZakumPrequestManager.ITEM_KEY) : null;
        if (stack == null || stack.getQuantity() < BotZakumPrequestManager.KEYS_NEEDED) {
            return true; // couriers still inbound / stack split — hold at the chest and wait
        }
        if (!BotManager.npcDwellReady(entry, BotManager.NPC_READ_DELAY_MS, BotManager.NPC_READ_JITTER_MS)) {
            return true;
        }
        // searchItemReactors requires ONE drop of EXACTLY quantity 7 inside the box — drop the stack.
        if (BotManager.getInstance().issueDropItem(entry, InventoryType.ETC,
                BotZakumPrequestManager.ITEM_KEY, (short) BotZakumPrequestManager.KEYS_NEEDED)) {
            entry.zakumPqChestDropAtMs = now;
            entry.zakumErrandProgress.touch(now);
        }
        return true;
    }

    /** Reproduce Aura's turn-in dialog end to end (no-documents path): consume the ore, grant the
     *  party exp, clear the PQ, claim this bot's Breath share, and walk out. Bot leader only. */
    static void leaderTurnInToAura(BotEntry entry, Character bot, Runnable onFailure) {
        EventInstanceManager eim = bot.getEventInstance();
        if (eim == null) {
            return; // instance died under us — the stranded-map recovery handles the exit next tick
        }
        try {
            if (!eim.isEventCleared()) {
                if (!bot.haveItem(BotZakumPrequestManager.ITEM_FIRE_ORE)) {
                    return;
                }
                InventoryManipulator.removeById(bot.getClient(), InventoryType.ETC,
                        BotZakumPrequestManager.ITEM_FIRE_ORE, 1, true, false);
                eim.giveEventPlayersExp(PQ_TURNIN_EXP);
                eim.clearPQ();
            }
            claimShareAndLeave(entry, bot);
        } catch (RuntimeException e) {
            log.warn("Bot '{}' failed the Zakum PQ turn-in", bot.getName(), e);
            warpToDoor(bot);
            onFailure.run();
        }
    }

    /** Claim this bot's Breath of Fire off Aura's grid (each event member is entitled to one), then
     *  walk out. Shared by the bot leader's turn-in, crew members, and player-led companions. */
    static void claimShareAndLeave(BotEntry entry, Character bot) {
        EventInstanceManager eim = bot.getEventInstance();
        if (eim == null) {
            warpToDoor(bot);
            return;
        }
        try {
            if (eim.gridCheck(bot) == -1) {
                if (!bot.canHold(BotZakumPrequestManager.ITEM_BREATH_FIRE, 1)) {
                    return; // no bag room — retry next tick (ETC has room in practice: keys just freed)
                }
                InventoryManipulator.addById(bot.getClient(),
                        BotZakumPrequestManager.ITEM_BREATH_FIRE, (short) 1);
                eim.gridInsert(bot, 1);
                reply.accept(entry, "got the breath of fire");
            }
            warpToDoor(bot); // changedMap unregisters the bot and winds the instance down
        } catch (RuntimeException e) {
            log.warn("Bot '{}' failed the Zakum PQ grid claim", bot.getName(), e);
            warpToDoor(bot);
        }
    }

    // ---- movement / pickup helpers -------------------------------------------------------------

    /** One tick of cross-room travel inside the instance. Real portal hops only (the executor walks
     *  to the portal and {@code Portal.enterPortal} resolves the instance map); always consumes the
     *  tick. */
    static boolean travelInsidePq(BotEntry entry, Character bot, int targetMap, boolean runAiTick) {
        if (entry.zakumErrandMapId != -1) {
            entry.zakumErrandNpcId = 0;      // errand mode: keep the armed sentinel on the live target
            entry.zakumErrandMapId = targetMap;
        }
        boolean moved = BotTravelManager.tickTravel(
                entry, bot, targetMap, BotAutopilotManager.MAX_TRAVEL_HOPS, runAiTick, false);
        entry.zakumErrandProgress.record(bot, moved, System.currentTimeMillis());
        return true;
    }

    /** On-map walk toward {@code target} (shared move pin + movement step — no bespoke movement). */
    static void walkTo(BotEntry entry, Character bot, Point target, boolean runAiTick) {
        BotTravelManager.pinMoveTarget(entry, target);
        BotTravelManager.movementStep.step(entry, target, runAiTick);
    }

    /** Stand near {@code target}: close the distance, then idle (pin cleared). Consumes the tick. */
    static boolean hoverNear(BotEntry entry, Character bot, Point target, boolean runAiTick) {
        if (target != null && !near(bot, target, HOVER_RADIUS_PX)) {
            walkTo(entry, bot, target, runAiTick);
        } else {
            BotTravelManager.clearMoveTargetPin(entry);
        }
        entry.zakumErrandProgress.touch(System.currentTimeMillis());
        return true;
    }

    /** Walk toward a wanted drop and pick it up once in reach — the ItemPickupHandler distance
     *  discipline, driven by the machine instead of the passive loot tick. */
    static void stepTowardsAndPickUp(BotEntry entry, Character bot, MapItem drop, boolean runAiTick) {
        if (near(bot, drop.getPosition(), 30)) {
            BotTravelManager.clearMoveTargetPin(entry);
            bot.pickupItem(drop);
            entry.zakumErrandProgress.touch(System.currentTimeMillis());
            return;
        }
        walkTo(entry, bot, drop.getPosition(), runAiTick);
    }

    /** The nearest live key on {@code map} this bot may take as WORK — i.e. not the leader's
     *  delivered pile and not the 7-stack pending under the Giant Chest. */
    private static MapItem wantedKeyOutsideZones(MapleMap map, Character bot, Character leader) {
        if (map == null) {
            return null;
        }
        // The delivered-pile exclusion protects the LEADER'S keys from workers; the leader itself
        // must of course collect that pile, so the zone never applies to the leader's own sweep.
        Point leaderPos = leader != null && leader.getId() != bot.getId()
                && leader.getMapId() == map.getId() ? leader.getPosition() : null;
        Point chestPos = null;
        if (map.getId() == PQ_CHEST_MAP) {
            Reactor giant = map.getReactorById(REACTOR_CHEST);
            chestPos = giant != null ? giant.getPosition() : null;
        }
        MapItem best = null;
        long bestDist = Long.MAX_VALUE;
        for (MapItem drop : map.getDroppedItems()) {
            if (drop == null || drop.isPickedUp() || drop.getItem() == null
                    || drop.getItem().getItemId() != BotZakumPrequestManager.ITEM_KEY
                    || !drop.canBePickedBy(bot)) {
                continue;
            }
            if (leaderPos != null && within(drop.getPosition(), leaderPos, DELIVER_ZONE_PX)) {
                continue; // the leader's pile
            }
            if (chestPos != null && within(drop.getPosition(), chestPos, CHEST_ZONE_PX)) {
                continue; // the pending 7-stack
            }
            long d = manhattan(bot.getPosition(), drop.getPosition());
            if (d < bestDist) {
                bestDist = d;
                best = drop;
            }
        }
        return best;
    }

    /** The nearest live drop of {@code itemId} on {@code map} (no zone filtering — leader use). */
    static MapItem findDrop(MapleMap map, int itemId) {
        if (map == null) {
            return null;
        }
        for (MapItem drop : map.getDroppedItems()) {
            if (drop != null && !drop.isPickedUp() && drop.getItem() != null
                    && drop.getItem().getItemId() == itemId) {
                return drop;
            }
        }
        return null;
    }

    /** Hit a reactor through the shared player path ({@code ReactorHitHandler} shape) — proximity is
     *  enforced by our own walk-up since the vanilla handler is client-authoritative. */
    static void hitReactor(Character bot, Reactor reactor) {
        try {
            if (reactor.isActive()) {
                int before = reactor.getState();
                reactor.hitReactor(true, 0, (short) 0, 0, bot.getClient());
                // TEMP-DIAG(zakumpq): remove once the key-drop pipeline is verified live.
                log.info("zakumpq {} hit reactor {} state {} -> {} type {} active {}",
                        bot.getName(), reactor.getId(), before, reactor.getState(),
                        reactor.getReactorType(), reactor.isActive());
            }
        } catch (RuntimeException e) {
            log.warn("Bot '{}' reactor hit failed on {}", bot.getName(), reactor.getId(), e);
        }
    }

    /** The stranded-player exit every Zakum-side NPC dialog performs: warp to the Door to Zakum. */
    static void warpToDoor(Character bot) {
        warpTo(bot, BotZakumPrequestManager.DOOR_MAP);
    }

    static void warpTo(Character bot, int mapId) {
        MapleMap target = bot.getClient().getChannelServer().getMapFactory().getMap(mapId);
        if (target != null) {
            bot.changeMap(target, target.getPortal(0));
        }
    }

    static boolean near(Character bot, Point target, int dist) {
        Point p = bot.getPosition();
        return p != null && target != null && within(p, target, dist);
    }

    private static boolean within(Point a, Point b, int dist) {
        return a != null && b != null && Math.abs(a.x - b.x) <= dist && Math.abs(a.y - b.y) <= dist;
    }

    private static long manhattan(Point a, Point b) {
        return (long) Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
    }
}
