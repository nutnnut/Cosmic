package server.bots;

import client.Character;
import client.inventory.WeaponType;
import server.bots.BotGrindPlanner.MobCandidate;
import server.bots.BotGrindPlanner.PartyPlan;
import server.bots.BotGrindPlanner.Recommendation;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntToLongFunction;

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
    static final long UPGRADE_REDECIDE_DELAY_MS = 25_000L; // re-ask stay-or-leave soon after a roll lands
    private static final long DEATH_STATUS_WINDOW_MS = 5 * 60_000L;

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
        Recommendation recommend(BotEntry entry, Character bot, int fromMapId, int maxHops, boolean withFerry);
    }

    static Advisor advisor = (entry, bot, fromMapId, maxHops, withFerry) -> {
        BotWorldGraph.RouteOptions options = travelOptions(bot, withFerry);
        Set<Integer> reachable = BotWorldGraph.reachableWithin(fromMapId, maxHops, options);
        return BotGrindAdvisor.recommend(entry, bot, reachable::contains,
                travelWeight(bot, fromMapId, maxHops, options));
    };

    @FunctionalInterface
    interface FarmAdvisor {
        Recommendation recommend(BotEntry entry, Character bot, int itemId, int fromMapId, int maxHops,
                                 boolean withFerry);
    }

    static FarmAdvisor farmAdvisor = (entry, bot, itemId, fromMapId, maxHops, withFerry) -> {
        BotWorldGraph.RouteOptions options = travelOptions(bot, withFerry);
        Set<Integer> reachable = BotWorldGraph.reachableWithin(fromMapId, maxHops, options);
        return BotGrindAdvisor.recommendFarmItem(entry, bot, itemId, reachable::contains,
                travelWeight(bot, fromMapId, maxHops, options));
    };

    /** Ferries need the owner's green light ("sail away") while the owner is around; with the
     *  owner absent/offline nobody is waiting, so the bot may sail on its own judgment.
     *  Self-owned bots (@botme) have no human owner at all — same rule. */
    static boolean ferryAllowed(BotEntry entry) {
        return entry.autopilotFerryApproved || entry.owner == null || entry.owner == entry.bot
                || !entry.owner.isLoggedinWorld();
    }

    /** What the bot can spend on travel right now: scrolls if carried, taxis per meso,
     *  ferries per the caller's owner-permission gate ({@link #ferryAllowed}). */
    private static BotWorldGraph.RouteOptions travelOptions(Character bot, boolean withFerry) {
        return new BotWorldGraph.RouteOptions(BotShopManager.countReturnScrolls(bot) > 0, bot.getMeso(), withFerry);
    }

    /**
     * SSOT travel-time penalty: ONE {@link BotTravelCost} flood from the bot's current map per
     * decision pass (heavy-ish — only ever runs on DECIDE_POOL), turned into the per-map score
     * multiplier the planner applies. Ferry time reads the runtime-mutable travelrate through
     * the bot's world at query time, never cached.
     */
    private static IntToDoubleFunction travelWeight(Character bot, int fromMapId, int maxHops,
                                                    BotWorldGraph.RouteOptions options) {
        IntToLongFunction transportationTime = ms -> bot.getWorldServer().getTransportationTime(ms);
        Map<Integer, Double> seconds = BotTravelCost.floodSeconds(fromMapId, maxHops, options, transportationTime);
        return mapId -> BotTravelCost.scoreWeight(seconds, mapId);
    }

    @FunctionalInterface
    interface PartyDecider {
        PartyPlan decide(List<BotEntry> members);
    }

    static PartyDecider partyDecider = members -> {
        // The shared map must be walkable for EVERY member (they can start scattered), and
        // each member pays its own travel penalty from wherever it stands.
        Set<Integer> common = null;
        List<IntToDoubleFunction> weights = new ArrayList<>(members.size());
        for (BotEntry member : members) {
            BotWorldGraph.RouteOptions options = travelOptions(member.bot, ferryAllowed(member));
            Set<Integer> reachable = BotWorldGraph.reachableWithin(
                    member.bot.getMapId(), MAX_TRAVEL_HOPS, options);
            weights.add(travelWeight(member.bot, member.bot.getMapId(), MAX_TRAVEL_HOPS, options));
            if (common == null) {
                common = new HashSet<>(reachable);
            } else {
                common.retainAll(reachable);
            }
        }
        Set<Integer> allowed = common == null ? Set.of() : common;
        List<List<MobCandidate>> perMember = new ArrayList<>(members.size());
        for (BotEntry member : members) {
            perMember.add(BotGrindAdvisor.candidatesFor(member, member.bot, allowed::contains));
        }
        return BotGrindPlanner.planPartyBest(perMember, weights, ThreadLocalRandom.current());
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
        entry.autopilotObjectiveReason = "";
        entry.autopilotArrivalAnnounced = false;
        entry.autopilotParty = false;
        entry.autopilotFarmItemId = 0;
        entry.autopilotFerryApproved = false;
        entry.autopilotErrandMapId = -1;
        entry.autopilotReturningFromErrand = false;
        entry.autopilotTransitFollow = false;
        entry.autopilotWaitingForStragglers = false;
        entry.autopilotNextStragglerCheckAtMs = 0L;
        entry.autopilotDecisionInFlight = false;
        BotQuestManager.clearQuestErrand(entry); // a canceled autopilot abandons any quest detour
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
        boolean ferryApprovedBefore = entry.autopilotFerryApproved;
        decisionRunner.run(() -> decide(entry, bot), result -> {
            Decision decision = (Decision) result;
            Recommendation rec = decision != null ? decision.rec() : null;
            if (entry.activityEpoch != epoch || bot.getMap() == null) {
                return; // a newer owner directive won while we were thinking
            }
            if (rec == null) {
                reply.accept(entry, BotManager.randomReply(NO_SPOT_REPLIES));
                maybeTeaseFerry(entry, decision);
                return;
            }
            // issueGrind sets the active-combat baseline (pot-share, self-buff, ammo fallback
            // all gate on grinding) and clears any previous autopilot state — destination AFTER.
            BotManager.getInstance().issueGrind(entry);
            entry.autopilotFerryApproved = ferryApprovedBefore; // the clear() in issueGrind
            // must not revoke the permission this very plan was decided with.
            installPlan(entry, rec, bot.getMapId());
            entry.autopilotNextDecisionAtMs = nextDecisionAt();
            announcePlan(entry, rec, bot.getMapId());
            maybeTeaseFerry(entry, decision);
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
                return farmAdvisor.recommend(entry, bot, itemId, bot.getMapId(), MAX_TRAVEL_HOPS,
                        ferryAllowed(entry));
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
        // Quest piggyback errand takes precedence over the grind destination: detour to a quest NPC
        // to start/turn in a mob quest, then resume grinding. Its own state, not autopilotErrandMapId
        // (which is hardwired to the shop visit). Consumes the tick while traveling/walking to the NPC.
        if (entry.questErrandMapId != -1 && BotQuestManager.tickErrand(entry, bot, runAiTick)) {
            return true;
        }
        int destination = entry.autopilotErrandMapId != -1 ? entry.autopilotErrandMapId : entry.autopilotMapId;
        if (bot.getMapId() == destination) {
            if (entry.autopilotTransitFollow) {
                // Arrived with the group: swap the follow pipeline back out for grind combat.
                entry.autopilotTransitFollow = false;
                BotManager.getInstance().resumeAutopilotGrind(entry);
            }
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
        if (entry.autopilotParty && entry.autopilotErrandMapId == -1) {
            Boolean cohesion = tickPartyCohesion(entry, bot);
            if (cohesion != null) {
                return cohesion;
            }
        }
        if (BotTravelManager.tickTravel(entry, bot, destination, MAX_TRAVEL_HOPS, runAiTick,
                ferryAllowed(entry))) {
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
        // Stranded off the destination map with no route: rather than anchor to the owner,
        // wander to a random legal cross-map portal and take it, then re-plan from there
        // (BotTravelManager.tickWanderToRandomPortal). Only when truly off-site — on the
        // grind map an empty target is just "mobs cleared", which the normal grind-wander
        // handles. No usable portal here -> fall through and grind whatever is around.
        if (BotTravelManager.tickWanderToRandomPortal(entry, bot, runAiTick)) {
            return true;
        }
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
        reply.accept(entry, resupplyErrandMessage(entry, bot));
        // No explicit scroll use here: scroll-to-town is a world-graph edge now, so the
        // travel tick takes it whenever it beats walking (BotTravelManager consumable hops).
        return true;
    }

    static String resupplyErrandMessage(BotEntry entry, Character bot) {
        List<String> reasons = resupplyErrandReasons(entry, bot);
        if (reasons.isEmpty()) {
            return "supplies low, popping back to town real quick";
        }
        return String.join(", ", reasons) + " - popping back to town real quick";
    }

    private static List<String> resupplyErrandReasons(BotEntry entry, Character bot) {
        List<String> reasons = new ArrayList<>();
        try {
            int[] pots = BotPotionManager.countPotions(bot);
            if (pots[0] < BotManager.cfg.POT_STOP) {
                reasons.add("low HP pots (" + pots[0] + " left)");
            }
            if (pots[1] < BotManager.cfg.POT_STOP) {
                reasons.add("low MP pots (" + pots[1] + " left)");
            }
        } catch (RuntimeException ignored) {
            // Some tests and edge states use partial character mocks; keep the errand alive.
        }

        try {
            WeaponType wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
            int ammo = BotCombatManager.countAmmo(bot, wt);
            if (ammo <= 0) {
                String ammoName = switch (wt) {
                    case BOW -> "arrows";
                    case CROSSBOW -> "bolts";
                    case CLAW -> "throwing stars";
                    case GUN -> "bullets";
                    default -> null;
                };
                if (ammoName != null) {
                    reasons.add("out of " + ammoName);
                }
            }
        } catch (RuntimeException ignored) {
            // Ammo is best-effort diagnostic text; route even if it cannot be inspected.
        }

        try {
            if (BotShopManager.shouldAutoSellTrash(entry, bot)) {
                reasons.add("bags are full enough to sell junk");
            }
        } catch (RuntimeException ignored) {
            // Same as above: never make a chat detail block the town errand.
        }
        return reasons;
    }

    static String statusReport(BotEntry entry, Character bot) {
        String currentMap = currentMapName(bot);
        if (entry == null || bot == null) {
            return "not sure where i am rn";
        }
        if (!isActive(entry)) {
            String activity = nonAutopilotActivity(entry);
            return activity.isEmpty()
                    ? "im at " + currentMap + ", idle rn"
                    : "im at " + currentMap + ", " + activity;
        }

        String destination = entry.autopilotDestinationName == null || entry.autopilotDestinationName.isBlank()
                ? ("map " + entry.autopilotMapId)
                : entry.autopilotDestinationName;
        String objective = presentObjective(entry.autopilotObjectiveSummary);
        boolean onDestination = bot.getMapId() == entry.autopilotMapId;
        boolean dead = bot.getHp() <= 0 || entry.deadUntil > System.currentTimeMillis();
        boolean recentDeath = entry.autopilotLastDeathAtMs > 0
                && System.currentTimeMillis() - entry.autopilotLastDeathAtMs <= DEATH_STATUS_WINDOW_MS;

        if (dead) {
            return "im at " + currentMap + ", i died, respawning soon then heading back to "
                    + destination + " to " + objective;
        }
        if (onDestination) {
            return objective + " at " + currentMap + reasonSuffix(entry);
        }
        if (entry.shopVisitPending) {
            return objective + " at " + destination + " - at " + currentMap + ", restocking at the shop";
        }
        if (entry.autopilotErrandMapId != -1) {
            return objective + " at " + destination + " - at " + currentMap + ", going back to town to resupply";
        }
        if (entry.autopilotReturningFromErrand) {
            return objective + " at " + destination + " - at " + currentMap + ", resupplied and omw back";
        }
        if (recentDeath) {
            return objective + " at " + destination + " - at " + currentMap + ", i died and omw back";
        }
        if (entry.autopilotTransitFollow) {
            return objective + " at " + destination + " - at " + currentMap + ", moving with the party";
        }
        if (entry.autopilotWaitingForStragglers) {
            return objective + " at " + destination + " - at " + currentMap + ", waiting for the party to catch up";
        }
        return "im at " + currentMap + ", heading to " + destination + " to " + objective + reasonSuffix(entry);
    }

    private static String reasonSuffix(BotEntry entry) {
        String reason = entry.autopilotObjectiveReason;
        return reason == null || reason.isBlank() ? "" : " - " + reason;
    }

    private static String currentMapName(Character bot) {
        if (bot == null) {
            return "unknown map";
        }
        MapleMap map = bot.getMap();
        if (map != null && map.getMapName() != null && !map.getMapName().isBlank()) {
            return map.getMapName();
        }
        return "map " + bot.getMapId();
    }

    private static String presentObjective(String summary) {
        if (summary == null || summary.isBlank()) {
            return "grinding";
        }
        String normalized = summary.trim();
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("farm ")) {
            return "farming " + normalized.substring(5);
        }
        if (lower.startsWith("grind ")) {
            return "grinding " + normalized.substring(6);
        }
        return normalized;
    }

    private static String nonAutopilotActivity(BotEntry entry) {
        if (entry.following) {
            return "following you";
        }
        if (entry.patrolRegionId >= 0) {
            return "patrolling here";
        }
        if (entry.farmAnchor != null) {
            return "farming this spot";
        }
        if (entry.grinding) {
            return "grinding here";
        }
        if (entry.moveTarget != null) {
            return "moving";
        }
        return "";
    }

    /**
     * The bot just equipped a looted upgrade mid-autopilot: the roll landed, so the expected
     * improvement of staying here collapsed (or didn't) RIGHT NOW — pull the next decision
     * forward instead of waiting out the regular interval. Only ever moves the timer earlier.
     * Skipped for owner-ordered "farm &lt;item&gt;" (the objective is pinned; a re-decide only
     * re-picks the site, which the roll doesn't affect) and for party autopilot (re-decides
     * are leader-driven on a group clock — pulling one member's timer is future work).
     */
    static void noteGearUpgraded(BotEntry entry) {
        if (entry == null || !isActive(entry) || entry.autopilotParty || entry.autopilotFarmItemId != 0) {
            return;
        }
        long at = System.currentTimeMillis() + UPGRADE_REDECIDE_DELAY_MS;
        if (entry.autopilotNextDecisionAtMs > at) {
            entry.autopilotNextDecisionAtMs = at;
        }
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
            Decision decision = (Decision) result;
            if (entry.activityEpoch != epoch || !isActive(entry) || entry.autopilotParty) {
                return;
            }
            maybeTeaseFerry(entry, decision);
            Recommendation rec = decision != null ? decision.rec() : null;
            if (rec == null || rec.pick().mapId() == entry.autopilotMapId) {
                return; // current spot is still the call
            }
            installPlan(entry, rec, bot.getMapId());
            announcePlan(entry, rec, bot.getMapId());
        });
    }

    /** A decision pass's outcome: the plan plus at most ONE ferry teaser line (when the owner
     *  is around, ferries are off, and the world across the sea is clearly better). */
    record Decision(Recommendation rec, String ferryTeaser) {}

    /** Overseas plan must beat the local one by this factor before the bot asks to sail. */
    static final double FERRY_TEASER_SCORE_RATIO = 1.5;

    /** Farm-item override keeps the objective and only re-picks the SITE; otherwise the
     *  general gear-first advisor decides. When ferries are owner-gated off, a second
     *  ferry-enabled pass feeds the "say 'sail away'" teaser. */
    private static Decision decide(BotEntry entry, Character bot) {
        try {
            boolean withFerry = ferryAllowed(entry);
            Recommendation local = recommendOnce(entry, bot, withFerry);
            if (withFerry) {
                return new Decision(local, null);
            }
            return new Decision(local, ferryTeaser(local, recommendOnce(entry, bot, true)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Recommendation recommendOnce(BotEntry entry, Character bot, boolean withFerry) {
        if (entry.autopilotFarmItemId != 0) {
            return farmAdvisor.recommend(entry, bot, entry.autopilotFarmItemId,
                    bot.getMapId(), MAX_TRAVEL_HOPS, withFerry);
        }
        return advisor.recommend(entry, bot, bot.getMapId(), MAX_TRAVEL_HOPS, withFerry);
    }

    /** The one-line "can i take the boat?" ask, or null when overseas isn't clearly better. */
    private static String ferryTeaser(Recommendation local, Recommendation overseas) {
        if (overseas == null || (local != null && overseas.pick().mapId() == local.pick().mapId())) {
            return null;
        }
        boolean worthIt;
        if (local == null) {
            worthIt = true; // nothing walkable at all, but the ferry opens somewhere
        } else if (overseas.gearFocused() != local.gearFocused()) {
            // The ferry pool is a superset of the local one, so a mode flip means the only
            // attainable gear is across the sea — gear-first says that wins outright.
            worthIt = overseas.gearFocused();
        } else {
            worthIt = overseas.score() >= local.score() * FERRY_TEASER_SCORE_RATIO;
        }
        if (!worthIt) {
            return null;
        }
        return "way better grind across the sea at " + destinationName(overseas.pick())
                + " - say 'sail away' if i can take the boat";
    }

    private static void maybeTeaseFerry(BotEntry entry, Decision decision) {
        if (decision != null && decision.ferryTeaser() != null) {
            reply.accept(entry, decision.ferryTeaser());
        }
    }

    /** Owner said "sail away": ferries are allowed and the wider horizon is worth a fresh
     *  decision right away — the next on-site tick's {@link #maybeRedecide} picks it up. */
    static void approveFerry(BotEntry entry) {
        entry.autopilotFerryApproved = true;
        entry.autopilotNextDecisionAtMs = 0L;
    }

    // ---- party internals ----

    // Cohesion: followers ride the regular follow pipeline behind the leader (formation
    // offsets, legal portal-follow, warp catch-up) instead of traveling independently, and
    // the leader holds a map when somebody falls this many portal hops behind.
    static final int STRAGGLER_WAIT_HOPS = 2;
    private static final long STRAGGLER_CHECK_INTERVAL_MS = 3_000L;
    private static final List<String> WAIT_REPLIES = List.of(
            "waiting up for the others",
            "hold on, letting the others catch up",
            "ill hold here a bit for the group");

    // Test seams: member enumeration touches the live registry/party; hop distance the world graph.
    @FunctionalInterface
    interface PartyMembersLookup {
        List<BotEntry> members(BotEntry entry);
    }

    static PartyMembersLookup partyMembers = BotAutopilotManager::defaultPartyMembers;

    @FunctionalInterface
    interface HopDistance {
        int hops(int fromMapId, int toMapId);
    }

    static HopDistance hopDistance = BotAutopilotManager::walkingHops;

    /**
     * Active party-autopilot members, leader first. The game party is the source of truth
     * when present (a @botme group spans owners); bots without a game party fall back to
     * the owner's bot list, the pre-party behavior.
     */
    private static List<BotEntry> defaultPartyMembers(BotEntry entry) {
        List<BotEntry> members = new ArrayList<>();
        for (BotEntry e : BotManager.getInstance().partyBotEntries(entry.bot)) {
            if (e.autopilotParty && isActive(e) && e.bot.getMap() != null) {
                members.add(e);
            }
        }
        if (!members.isEmpty()) {
            return members;
        }
        Character owner = entry.owner;
        if (owner == null) {
            return List.of();
        }
        for (BotEntry e : BotManager.getInstance().getBotEntries(owner.getId())) {
            if (e.autopilotParty && isActive(e) && e.bot != null && e.bot.getMap() != null) {
                members.add(e);
            }
        }
        return members;
    }

    private static int walkingHops(int fromMapId, int toMapId) {
        if (fromMapId == toMapId) {
            return 0;
        }
        List<Integer> route = BotWorldGraph.route(fromMapId, toMapId, STRAGGLER_WAIT_HOPS + 1,
                new BotWorldGraph.RouteOptions(false, 0, false));
        return route == null ? Integer.MAX_VALUE : route.size();
    }

    /**
     * One in-transit tick of party cohesion. Returns the tick() result to use, or null when
     * this bot is the leader with the group in tow — it travels normally this tick.
     */
    private static Boolean tickPartyCohesion(BotEntry entry, Character bot) {
        List<BotEntry> members = partyMembers.members(entry);
        if (members.size() < 2) {
            exitTransitFollow(entry); // group dissolved — travel on alone
            return null;
        }
        BotEntry leader = members.get(0);
        if (leader == entry) {
            exitTransitFollow(entry); // just promoted mid-transit: stop following, lead
            if (waitingForStragglers(entry, bot, members)) {
                return false; // hold this map (grind flow runs) until the group closes up
            }
            return null;
        }
        if (entry.owner == null) {
            // Owner offline strips `following` every tick and the follow anchor can't resolve,
            // so the follow pipeline is dead — travel independently until the owner returns.
            exitTransitFollow(entry);
            return null;
        }
        return enterTransitFollow(entry, leader);
    }

    /**
     * Flip a follower into transit-follow behind the leader. Returns true exactly on the
     * transition tick: the tick's follow anchor was resolved before autopilot ran, so acting
     * on it now would chase the stale anchor — consume the tick and let the next one follow.
     */
    private static boolean enterTransitFollow(BotEntry entry, BotEntry leader) {
        int leaderId = leader.bot.getId();
        if (entry.autopilotTransitFollow && entry.following && entry.followTargetId == leaderId) {
            return false; // already in formation — the follow pipeline owns the rest of the tick
        }
        entry.autopilotTransitFollow = true;
        entry.grinding = false;
        entry.grindTarget = null;
        entry.grindLootTarget = null;
        entry.followTargetId = leaderId;
        entry.following = true;
        BotMovementManager.clearNavigationState(entry);
        return true;
    }

    private static void exitTransitFollow(BotEntry entry) {
        if (entry.autopilotTransitFollow) {
            entry.autopilotTransitFollow = false;
            BotManager.getInstance().resumeAutopilotGrind(entry);
        }
    }

    /** Leader-side hold: true while any member is more than {@link #STRAGGLER_WAIT_HOPS}
     *  portal hops behind. Rate-limited; the cached verdict rides between checks. */
    private static boolean waitingForStragglers(BotEntry entry, Character bot, List<BotEntry> members) {
        long now = System.currentTimeMillis();
        if (now < entry.autopilotNextStragglerCheckAtMs) {
            return entry.autopilotWaitingForStragglers;
        }
        entry.autopilotNextStragglerCheckAtMs = now + STRAGGLER_CHECK_INTERVAL_MS;
        boolean waiting = false;
        for (BotEntry member : members) {
            if (member == entry || member.bot == null || member.bot.getMap() == null) {
                continue;
            }
            if (hopDistance.hops(member.bot.getMapId(), bot.getMapId()) > STRAGGLER_WAIT_HOPS) {
                waiting = true;
                break;
            }
        }
        if (waiting && !entry.autopilotWaitingForStragglers) {
            reply.accept(entry, BotManager.randomReply(WAIT_REPLIES));
        }
        entry.autopilotWaitingForStragglers = waiting;
        return waiting;
    }

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
        List<BotEntry> members = partyMembers.members(entry);
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

        // One follow-up line listing the others' gear goals, attributed to their beneficiary
        // ("farm <item> from <mob> for <member>") since the leader speaks for the group.
        List<String> hopes = new ArrayList<>();
        for (int i = 1; i < members.size() && hopes.size() < 3; i++) {
            Recommendation rec = plan.perMember().get(i);
            if (rec != null && rec.gearFocused() && rec.wantedGear() != null) {
                hopes.add("farm " + rec.wantedGear().itemName() + " from " + rec.pick().mobName()
                        + " for " + members.get(i).bot.getName());
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
        entry.autopilotObjectiveReason = objectiveReason(entry, rec);
        // Already on the picked map: announcePlan's "this map works" covers it — a separate
        // "arrived" line right after would be redundant chatter.
        entry.autopilotArrivalAnnounced = pick.mapId() == fromMapId;
    }

    private static String destinationName(MobCandidate pick) {
        return pick.mapName().isEmpty() ? ("map " + pick.mapId()) : pick.mapName();
    }

    /** Plain words only — never exp/hr, drop-rate or dps numbers (they read like a bot). */
    private static String objectiveSummary(BotEntry entry, Recommendation rec) {
        MobCandidate pick = rec.pick();
        if (rec.wantedGear() != null && (entry.autopilotFarmItemId != 0 || rec.gearFocused())) {
            return "farm " + rec.wantedGear().itemName() + " from " + pick.mobName();
        }
        return "grind " + pick.mobName();
    }

    private static final List<String> EXP_REASONS = List.of(
            "good exp", "solid exp for me", "fast levels here");
    private static final List<String> GEAR_REASONS = List.of(
            "could be a real upgrade for me", "i really want that drop", "best gear odds i can reach");

    /** Why this plan won, same plain-words rule. Empty when the objective says it all
     *  (owner-pinned "farm <item>" orders). */
    private static String objectiveReason(BotEntry entry, Recommendation rec) {
        if (entry.autopilotFarmItemId != 0) {
            return ""; // the owner picked the goal, "farm <item> from <mob>" needs no excuse
        }
        if (rec.wantedGear() != null && rec.gearFocused()) {
            return BotManager.randomReply(GEAR_REASONS);
        }
        if (rec.wantedGear() != null) {
            return BotManager.randomReply(EXP_REASONS) + " plus a shot at " + rec.wantedGear().itemName();
        }
        return BotManager.randomReply(EXP_REASONS);
    }

    private static long nextDecisionAt() {
        return System.currentTimeMillis() + DECISION_INTERVAL_MS
                + ThreadLocalRandom.current().nextLong(DECISION_JITTER_MS);
    }
}
