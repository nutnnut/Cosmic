package server.bots;

import client.Character;
import constants.game.GameConstants;
import server.bots.BotGrindPlanner.GearProspect;
import server.bots.BotGrindPlanner.MobCandidate;
import server.bots.BotGrindPlanner.PartyPlan;
import server.bots.BotGrindPlanner.Recommendation;

import java.util.ArrayList;
import java.util.HashSet;
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
    private static final long ERRAND_COOLDOWN_MS = 5 * 60_000L; // min spacing between resupply trips
    private static final long OWNER_SUPPLY_GRACE_MS = 20_000L;

    private static final List<String> NO_SPOT_REPLIES = List.of(
            "can't find anywhere worth grinding that i can walk to, staying put",
            "hmm, nowhere walkable looks good rn");
    private static final List<String> BACK_REPLIES = List.of(
            "back",
            "back, continuing",
            "back, resuming");

    // Test seams: the real advisor needs WZ/DB; replies go through the owner's chat channel.
    @FunctionalInterface
    interface Advisor {
        Recommendation recommend(BotEntry entry, Character bot, int fromMapId, int maxHops);
    }

    static Advisor advisor = (entry, bot, fromMapId, maxHops) -> {
        Set<Integer> reachable = BotWorldGraph.reachableWithin(fromMapId, maxHops, travelOptions(bot));
        return BotGrindAdvisor.recommend(entry, bot, reachable::contains);
    };

    @FunctionalInterface
    interface FarmAdvisor {
        Recommendation recommend(BotEntry entry, Character bot, int itemId, int fromMapId, int maxHops);
    }

    static FarmAdvisor farmAdvisor = (entry, bot, itemId, fromMapId, maxHops) -> {
        Set<Integer> reachable = BotWorldGraph.reachableWithin(fromMapId, maxHops, travelOptions(bot));
        return BotGrindAdvisor.recommendFarmItem(entry, bot, itemId, reachable::contains);
    };

    /** What the bot can spend on travel right now: scrolls if carried, taxis and ferries per meso. */
    private static BotWorldGraph.RouteOptions travelOptions(Character bot) {
        return new BotWorldGraph.RouteOptions(BotShopManager.countReturnScrolls(bot) > 0, bot.getMeso(), true);
    }

    @FunctionalInterface
    interface PartyDecider {
        PartyPlan decide(List<BotEntry> members);
    }

    static PartyDecider partyDecider = members -> {
        // The shared map must be walkable for EVERY member (they can start scattered).
        Set<Integer> common = null;
        for (BotEntry member : members) {
            Set<Integer> reachable = BotWorldGraph.reachableWithin(
                    member.bot.getMapId(), MAX_TRAVEL_HOPS, travelOptions(member.bot));
            if (common == null) {
                common = new HashSet<>(reachable);
            } else {
                common.retainAll(reachable);
            }
        }
        Set<Integer> allowed = common == null ? Set.of() : common;
        List<List<MobCandidate>> perMember = new ArrayList<>(members.size());
        for (BotEntry member : members) {
            List<MobCandidate> mine = new ArrayList<>();
            for (MobCandidate c : BotGrindAdvisor.candidatesFor(member, member.bot)) {
                if (allowed.contains(c.mapId())) {
                    mine.add(c);
                }
            }
            perMember.add(mine);
        }
        return BotGrindPlanner.planPartyBest(perMember, ThreadLocalRandom.current());
    };

    static BiConsumer<BotEntry, String> reply =
            (entry, text) -> BotManager.getInstance().botReply(entry, text);

    /**
     * Decision scheduling: compute on the advisor pool (a full pass iterates every known mob
     * and can take seconds — on a tick/timer thread that's a visible server freeze), apply
     * back on the bot scheduler. Tests swap this for a synchronous runner.
     */
    @FunctionalInterface
    interface DecisionRunner {
        void run(java.util.function.Supplier<Object> compute, java.util.function.Consumer<Object> apply);
    }

    static DecisionRunner decisionRunner = (compute, apply) -> BotGrindAdvisor.DECIDE_POOL.execute(() -> {
        Object result;
        try {
            result = compute.get();
        } catch (RuntimeException e) {
            result = null;
        }
        Object applied = result;
        BotManager.after(0L, () -> apply.accept(applied));
    });

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
        entry.autopilotParty = false;
        entry.autopilotFarmItemId = 0;
        entry.autopilotErrandMapId = -1;
        entry.autopilotReturningFromErrand = false;
        entry.autopilotDecisionInFlight = false;
        // autopilotNextErrandAtMs deliberately survives: it rate-limits errands, not the mode.
        // autopilotOwnerSupplyGraceUntilMs also survives: player trade grace is supply state,
        // not a combat-mode destination.
    }

    /** Owner ordered independent play: decide (off-thread), announce, head out. */
    static void start(BotEntry entry, Character bot) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return;
        }
        int epoch = entry.activityEpoch;
        decisionRunner.run(() -> decide(entry, bot), result -> {
            Recommendation rec = (Recommendation) result;
            if (entry.activityEpoch != epoch || bot.getMap() == null) {
                return; // a newer owner directive won while we were thinking
            }
            if (rec == null) {
                reply.accept(entry, BotManager.randomReply(NO_SPOT_REPLIES));
                return;
            }
            // issueGrind sets the active-combat baseline (pot-share, self-buff, ammo fallback
            // all gate on grinding) and clears any previous autopilot state — destination AFTER.
            BotManager.getInstance().issueGrind(entry);
            installPlan(entry, rec, bot.getMapId());
            entry.autopilotNextDecisionAtMs = nextDecisionAt();
            announcePlan(entry, rec, bot.getMapId());
        });
    }

    /** Owner ordered the whole group out together: ONE shared map, per-member objectives. */
    static void startParty(Character owner, List<BotEntry> entries) {
        if (owner == null || entries == null) {
            return;
        }
        List<BotEntry> members = new ArrayList<>();
        for (BotEntry e : entries) {
            if (e != null && e.bot != null && e.bot.getMap() != null) {
                members.add(e);
            }
        }
        if (members.isEmpty()) {
            return;
        }
        int[] epochs = members.stream().mapToInt(m -> m.activityEpoch).toArray();
        decisionRunner.run(() -> decideParty(members), result -> {
            PartyPlan plan = (PartyPlan) result;
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).activityEpoch != epochs[i]) {
                    return; // somebody got a newer directive mid-decision — drop the stale plan
                }
            }
            if (plan == null) {
                reply.accept(members.get(0), "can't find a spot we can all reach that's worth it");
                return;
            }
            applyPartyPlan(members, plan);
            announceParty(members, plan);
        });
    }

    /** Owner ordered "farm <item>": same autopilot, objective pinned to the item. */
    static void startFarmItem(BotEntry entry, Character bot, int itemId, String itemName) {
        if (entry == null || bot == null || bot.getMap() == null) {
            return;
        }
        int epoch = entry.activityEpoch;
        decisionRunner.run(() -> {
            try {
                return farmAdvisor.recommend(entry, bot, itemId, bot.getMapId(), MAX_TRAVEL_HOPS);
            } catch (RuntimeException e) {
                return null;
            }
        }, result -> {
            Recommendation rec = (Recommendation) result;
            if (entry.activityEpoch != epoch || bot.getMap() == null) {
                return;
            }
            if (rec == null) {
                reply.accept(entry, "can't farm " + itemName + " - nothing i can reach drops it");
                return;
            }
            BotManager.getInstance().issueGrind(entry);
            entry.autopilotFarmItemId = itemId; // before installPlan: the objective text keys on it
            installPlan(entry, rec, bot.getMapId());
            entry.autopilotNextDecisionAtMs = nextDecisionAt();
            announcePlan(entry, rec, bot.getMapId());
        });
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
        int destination = entry.autopilotErrandMapId != -1 ? entry.autopilotErrandMapId : entry.autopilotMapId;
        if (bot.getMapId() == destination) {
            if (entry.autopilotErrandMapId != -1) {
                if (entry.shopVisitPending) {
                    return false; // shopping; the visit flow owns the tick
                }
                // The auto shop visit (triggered by the map change) is over or never fired —
                // errand done either way, head back to the grind map.
                entry.autopilotErrandMapId = -1;
                entry.autopilotReturningFromErrand = true;
                reply.accept(entry, "restocked, heading back");
                return false;
            }
            if (entry.autopilotReturningFromErrand) {
                entry.autopilotReturningFromErrand = false;
                reply.accept(entry, BotManager.randomReply(BACK_REPLIES));
            }
            announceArrival(entry);
            maybeRedecide(entry, bot);
            return false; // on site: normal grind flow runs this tick
        }
        if (entry.shopVisitPending) {
            return false; // resupply detour en route; travel resumes once it's done
        }
        if (BotTravelManager.tickTravel(entry, bot, destination, MAX_TRAVEL_HOPS, runAiTick, true)) {
            return true;
        }
        // No legal progress right now (route gone, or a hop failed and travel is in its
        // give-up window). Never warp — grind whatever is here and re-decide on the timer;
        // travel retries by itself once the window passes.
        if (entry.autopilotErrandMapId != -1) {
            entry.autopilotErrandMapId = -1; // unreachable errand: forget it, the cooldown gates retries
            entry.autopilotReturningFromErrand = false;
        }
        maybeRedecide(entry, bot);
        return false;
    }

    static void noteLowSupplyPartyRequest(BotEntry entry) {
        if (entry != null && isActive(entry)) {
            entry.autopilotOwnerSupplyGraceUntilMs = System.currentTimeMillis() + OWNER_SUPPLY_GRACE_MS;
            int retryMs = BotMovementManager.delayAfterCurrentTick(
                    (int) OWNER_SUPPLY_GRACE_MS + BotManager.cfg.POT_CHECK_RETRY_SOON_MS);
            if (entry.potCheckTimerMs <= 0 || entry.potCheckTimerMs > retryMs) {
                entry.potCheckTimerMs = retryMs;
            }
        }
    }

    /**
     * Supplies ran low mid-grind ({@code BotPotionManager}): detour to the return-map town,
     * let the auto shop visit restock/sell there, then walk back. Returns false when an
     * errand can't help (not autopiloting, no distinct return map) — caller falls back to
     * the legacy walk-to-owner.
     */
    static boolean requestResupplyErrand(BotEntry entry, Character bot) {
        if (!isActive(entry) || bot.getMap() == null) {
            return false;
        }
        if (entry.autopilotErrandMapId != -1
                || System.currentTimeMillis() < entry.autopilotNextErrandAtMs) {
            return true; // already handling it / just tried — don't bounce to the owner
        }
        if (System.currentTimeMillis() < entry.autopilotOwnerSupplyGraceUntilMs) {
            return true; // party request just went out; give owner trade a short chance to land
        }
        var returnMap = bot.getMap().getReturnMap();
        if (returnMap == null || returnMap.getId() == bot.getMapId()) {
            return false;
        }
        entry.autopilotErrandMapId = returnMap.getId();
        entry.autopilotNextErrandAtMs = System.currentTimeMillis() + ERRAND_COOLDOWN_MS;
        reply.accept(entry, "running low on supplies, popping back to town real quick");
        // No explicit scroll use here: scroll-to-town is a world-graph edge now, so the
        // travel tick takes it whenever it beats walking (BotTravelManager consumable hops).
        return true;
    }

    private static void maybeRedecide(BotEntry entry, Character bot) {
        long now = System.currentTimeMillis();
        if (now < entry.autopilotNextDecisionAtMs || entry.autopilotDecisionInFlight) {
            return;
        }
        entry.autopilotNextDecisionAtMs = nextDecisionAt();
        if (entry.autopilotParty) {
            redecideParty(entry, bot);
            return;
        }
        entry.autopilotDecisionInFlight = true;
        int epoch = entry.activityEpoch;
        decisionRunner.run(() -> decide(entry, bot), result -> {
            entry.autopilotDecisionInFlight = false;
            Recommendation rec = (Recommendation) result;
            if (entry.activityEpoch != epoch || !isActive(entry) || entry.autopilotParty) {
                return;
            }
            if (rec == null || rec.pick().mapId() == entry.autopilotMapId) {
                return; // current spot is still the call
            }
            installPlan(entry, rec, bot.getMapId());
            announcePlan(entry, rec, bot.getMapId());
        });
    }

    /** Farm-item override keeps the objective and only re-picks the SITE; otherwise the
     *  general two-lens advisor decides. */
    private static Recommendation decide(BotEntry entry, Character bot) {
        try {
            if (entry.autopilotFarmItemId != 0) {
                return farmAdvisor.recommend(entry, bot, entry.autopilotFarmItemId,
                        bot.getMapId(), MAX_TRAVEL_HOPS);
            }
            return advisor.recommend(entry, bot, bot.getMapId(), MAX_TRAVEL_HOPS);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- party internals ----

    private static PartyPlan decideParty(List<BotEntry> members) {
        try {
            return partyDecider.decide(members);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void applyPartyPlan(List<BotEntry> members, PartyPlan plan) {
        String destination = "";
        for (Recommendation rec : plan.perMember()) {
            if (rec != null) {
                destination = destinationName(rec.pick());
                break;
            }
        }
        long baseDecisionAt = nextDecisionAt();
        for (int i = 0; i < members.size(); i++) {
            BotEntry member = members.get(i);
            BotManager.getInstance().issueGrind(member); // combat baseline + clears old autopilot
            Recommendation rec = plan.perMember().get(i);
            if (rec != null) {
                installPlan(member, rec, member.bot.getMapId());
            } else {
                // Nothing worthwhile for this member there — they still come along.
                member.autopilotMapId = plan.mapId();
                member.autopilotDestinationName = destination;
                member.autopilotObjectiveSummary = "back the party up";
                member.autopilotArrivalAnnounced = true;
            }
            member.autopilotParty = true;
            // Leader (first member) re-decides for the group; trailing offsets keep member
            // timers from ever firing first.
            member.autopilotNextDecisionAtMs = baseDecisionAt + i * 2_000L;
        }
    }

    /** Leader-only group re-decide (off-thread); members just wait for the leader's next call. */
    private static void redecideParty(BotEntry entry, Character bot) {
        Character owner = entry.owner;
        if (owner == null) {
            return;
        }
        List<BotEntry> members = new ArrayList<>();
        for (BotEntry e : BotManager.getInstance().getBotEntries(owner.getId())) {
            if (e.autopilotParty && isActive(e) && e.bot != null && e.bot.getMap() != null) {
                members.add(e);
            }
        }
        if (members.isEmpty() || members.get(0) != entry) {
            return;
        }
        entry.autopilotDecisionInFlight = true;
        int[] epochs = members.stream().mapToInt(m -> m.activityEpoch).toArray();
        decisionRunner.run(() -> decideParty(members), result -> {
            entry.autopilotDecisionInFlight = false;
            PartyPlan plan = (PartyPlan) result;
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).activityEpoch != epochs[i] || !members.get(i).autopilotParty) {
                    return; // group composition changed mid-decision — wait for the next cycle
                }
            }
            if (plan == null || plan.mapId() == entry.autopilotMapId) {
                return; // current spot is still the call for the group
            }
            applyPartyPlan(members, plan);
            announceParty(members, plan);
        });
    }

    private static void announceParty(List<BotEntry> members, PartyPlan plan) {
        BotEntry leader = members.get(0);
        Recommendation leaderRec = plan.perMember().get(0);
        String spot = leaderRec != null
                ? destinationName(leaderRec.pick())
                : (leader.autopilotDestinationName.isEmpty() ? ("map " + plan.mapId()) : leader.autopilotDestinationName);
        String line = leader.bot.getMapId() == plan.mapId()
                ? "party plan: stay here at " + spot
                : "party plan: " + spot;
        line += leaderRec != null ? " - " + objectiveSummary(leader, leaderRec) : " - good spot for the group";
        reply.accept(leader, line);

        // One follow-up line listing what the others are hoping for (gear goals only).
        List<String> hopes = new ArrayList<>();
        for (int i = 1; i < members.size() && hopes.size() < 3; i++) {
            Recommendation rec = plan.perMember().get(i);
            if (rec != null && rec.gearFocused() && rec.wantedGear() != null) {
                hopes.add(members.get(i).bot.getName() + " wants " + rec.wantedGear().itemName());
            }
        }
        if (!hopes.isEmpty()) {
            reply.accept(leader, String.join(", ", hopes));
        }
    }

    private static void announcePlan(BotEntry entry, Recommendation rec, int fromMapId) {
        MobCandidate pick = rec.pick();
        String line = pick.mapId() == fromMapId
                ? "this map works - "
                : "heading to " + destinationName(pick) + " to ";
        reply.accept(entry, line + objectiveSummary(entry, rec));
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
        entry.autopilotObjectiveSummary = objectiveSummary(entry, rec);
        // Already on the picked map: announcePlan's "this map works" covers it — a separate
        // "arrived" line right after would be redundant chatter.
        entry.autopilotArrivalAnnounced = pick.mapId() == fromMapId;
    }

    private static String destinationName(MobCandidate pick) {
        return pick.mapName().isEmpty() ? ("map " + pick.mapId()) : pick.mapName();
    }

    private static String objectiveSummary(BotEntry entry, Recommendation rec) {
        MobCandidate pick = rec.pick();
        if (entry.autopilotFarmItemId != 0 && rec.wantedGear() != null) {
            return "farm " + rec.wantedGear().itemName() + " from " + pick.mobName()
                    + " (" + dropRateText(rec.wantedGearPerHour()) + ")";
        }
        if (rec.gearFocused() && rec.wantedGear() != null) {
            GearProspect want = rec.wantedGear();
            return "farm " + want.itemName() + " from " + pick.mobName()
                    + " (+" + Math.round(want.dpsGainFraction() * 100) + "% dps for me)";
        }
        return "grind " + pick.mobName() + ", ~"
                + GameConstants.numberWithCommas((int) Math.round(rec.expPerHour())) + " exp/hr";
    }

    private static String dropRateText(double itemsPerHour) {
        if (itemsPerHour >= 1.0) {
            return "~" + Math.round(itemsPerHour) + "/hr";
        }
        if (itemsPerHour > 0.0) {
            return "~" + Math.max(1, Math.round(1.0 / itemsPerHour)) + " hrs per drop";
        }
        return "rare";
    }

    private static long nextDecisionAt() {
        return System.currentTimeMillis() + DECISION_INTERVAL_MS
                + ThreadLocalRandom.current().nextLong(DECISION_JITTER_MS);
    }
}
