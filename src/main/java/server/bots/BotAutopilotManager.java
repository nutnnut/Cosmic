package server.bots;

import client.Character;
import constants.game.GameConstants;
import server.bots.BotGrindPlanner.GearProspect;
import server.bots.BotGrindPlanner.MobCandidate;
import server.bots.BotGrindPlanner.Recommendation;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * Owner-ordered independent play ("go grind somewhere"): pick a grind map with the advisor
 * (restricted to maps the bot can legally walk to), announce the plan, travel there through
 * portals, grind, and re-decide every so often. Any follow/stop/grind/move command from the
 * owner cancels it — party scope: the bot is only independent when ordered to be, so testing
 * stays under oversight. Death is handled by the normal respawn flow; the destination
 * survives it, so the bot revives in town and walks back on its own.
 *
 * <p>Autopilot never warps: when no walkable route exists it picks somewhere else (or waits
 * out the travel give-up window and retries).
 */
final class BotAutopilotManager {

    // Autopilot may roam farther than follow-travel: the trip is deliberate, nobody is waiting.
    static final int MAX_TRAVEL_HOPS = 8;
    private static final long DECISION_INTERVAL_MS = 12 * 60_000L;
    private static final long DECISION_JITTER_MS = 6 * 60_000L; // de-syncs many bots' re-decides

    private static final List<String> NO_SPOT_REPLIES = List.of(
            "can't find anywhere worth grinding that i can walk to, staying put",
            "hmm, nowhere walkable looks good rn");

    // Test seams: the real advisor needs WZ/DB; replies go through the owner's chat channel.
    @FunctionalInterface
    interface Advisor {
        Recommendation recommend(BotEntry entry, Character bot, int fromMapId, int maxHops);
    }

    static Advisor advisor = (entry, bot, fromMapId, maxHops) -> {
        Set<Integer> reachable = BotWorldGraph.reachableWithin(fromMapId, maxHops);
        return BotGrindAdvisor.recommend(entry, bot, reachable::contains);
    };
    static BiConsumer<BotEntry, String> reply =
            (entry, text) -> BotManager.getInstance().botReply(entry, text);

    private BotAutopilotManager() {}

    static boolean isActive(BotEntry entry) {
        return entry.autopilotMapId != -1;
    }

    static void clear(BotEntry entry) {
        entry.autopilotMapId = -1;
        entry.autopilotNextDecisionAtMs = 0L;
        entry.autopilotDestinationName = "";
        entry.autopilotObjectiveSummary = "";
        entry.autopilotArrivalAnnounced = false;
    }

    /** Owner ordered independent play: decide, announce, head out. */
    static void start(BotEntry entry, Character bot) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return;
        }
        Recommendation rec = decide(entry, bot);
        if (rec == null) {
            reply.accept(entry, BotManager.randomReply(NO_SPOT_REPLIES));
            return;
        }
        // issueGrind sets the active-combat baseline (pot-share, self-buff, ammo fallback all
        // gate on grinding) and clears any previous autopilot state — set the destination AFTER.
        BotManager.getInstance().issueGrind(entry);
        installPlan(entry, rec, bot.getMapId());
        entry.autopilotNextDecisionAtMs = nextDecisionAt();
        announcePlan(entry, rec, bot.getMapId());
    }

    /**
     * One autopilot tick, between the map-change rebuild and the follow sync. Returns true
     * when the tick was consumed (walking a travel hop); false to continue the normal
     * grind/combat flow.
     */
    static boolean tick(BotEntry entry, Character bot, boolean runAiTick) {
        if (!isActive(entry) || bot.getMap() == null) {
            return false;
        }
        if (bot.getMapId() == entry.autopilotMapId) {
            announceArrival(entry);
            maybeRedecide(entry, bot);
            return false; // on site: normal grind flow runs this tick
        }
        if (entry.shopVisitPending) {
            return false; // resupply detour en route; travel resumes once it's done
        }
        if (BotTravelManager.tickTravel(entry, bot, entry.autopilotMapId, MAX_TRAVEL_HOPS, runAiTick)) {
            return true;
        }
        // No legal progress right now (route gone, or a hop failed and travel is in its
        // give-up window). Never warp — grind whatever is here and re-decide on the timer;
        // travel retries by itself once the window passes.
        maybeRedecide(entry, bot);
        return false;
    }

    private static void maybeRedecide(BotEntry entry, Character bot) {
        long now = System.currentTimeMillis();
        if (now < entry.autopilotNextDecisionAtMs) {
            return;
        }
        entry.autopilotNextDecisionAtMs = nextDecisionAt();
        Recommendation rec = decide(entry, bot);
        if (rec == null || rec.pick().mapId() == entry.autopilotMapId) {
            return; // current spot is still the call
        }
        installPlan(entry, rec, bot.getMapId());
        announcePlan(entry, rec, bot.getMapId());
    }

    private static Recommendation decide(BotEntry entry, Character bot) {
        try {
            return advisor.recommend(entry, bot, bot.getMapId(), MAX_TRAVEL_HOPS);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void announcePlan(BotEntry entry, Recommendation rec, int fromMapId) {
        MobCandidate pick = rec.pick();
        String line = pick.mapId() == fromMapId
                ? "this map works - "
                : "heading to " + destinationName(pick) + " to ";
        reply.accept(entry, line + objectiveSummary(rec));
    }

    private static void announceArrival(BotEntry entry) {
        if (entry.autopilotArrivalAnnounced) {
            return;
        }
        entry.autopilotArrivalAnnounced = true;
        String spot = entry.autopilotDestinationName == null || entry.autopilotDestinationName.isEmpty()
                ? ("map " + entry.autopilotMapId)
                : entry.autopilotDestinationName;
        String objective = entry.autopilotObjectiveSummary == null || entry.autopilotObjectiveSummary.isEmpty()
                ? "grind"
                : entry.autopilotObjectiveSummary;
        reply.accept(entry, "arrived at " + spot + ", entering grind mode to " + objective);
    }

    private static void installPlan(BotEntry entry, Recommendation rec, int fromMapId) {
        MobCandidate pick = rec.pick();
        entry.autopilotMapId = pick.mapId();
        entry.autopilotDestinationName = destinationName(pick);
        entry.autopilotObjectiveSummary = objectiveSummary(rec);
        // Already on the picked map: announcePlan's "this map works" covers it — a separate
        // "arrived" line right after would be redundant chatter.
        entry.autopilotArrivalAnnounced = pick.mapId() == fromMapId;
    }

    private static String destinationName(MobCandidate pick) {
        return pick.mapName().isEmpty() ? ("map " + pick.mapId()) : pick.mapName();
    }

    private static String objectiveSummary(Recommendation rec) {
        MobCandidate pick = rec.pick();
        if (rec.gearFocused() && rec.wantedGear() != null) {
            GearProspect want = rec.wantedGear();
            return "farm " + want.itemName() + " from " + pick.mobName()
                    + " (+" + Math.round(want.dpsGainFraction() * 100) + "% dps for me)";
        }
        return "grind " + pick.mobName() + ", ~"
                + GameConstants.numberWithCommas((int) Math.round(rec.expPerHour())) + " exp/hr";
    }

    private static long nextDecisionAt() {
        return System.currentTimeMillis() + DECISION_INTERVAL_MS
                + ThreadLocalRandom.current().nextLong(DECISION_JITTER_MS);
    }
}
