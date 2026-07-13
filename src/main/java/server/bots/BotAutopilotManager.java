package server.bots;

import client.Character;
import constants.id.MapId;
import client.inventory.WeaponType;
import server.bots.BotGrindPlanner.MobCandidate;
import server.bots.BotGrindPlanner.PartyPlan;
import server.bots.BotGrindPlanner.Recommendation;
import server.maps.MapFactory;
import server.maps.MapleMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;
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

    private static final Logger log = LoggerFactory.getLogger(BotAutopilotManager.class);
    // Diagnostic: how often (per bot) to log why a wanted resupply/sell errand could not start.
    // Throttled so a persistently-blocked bot doesn't flood the log every grind tick.
    private static final long ERRAND_BLOCK_LOG_THROTTLE_MS = 60_000L;

    // Autopilot may roam farther than follow-travel: the trip is deliberate, nobody is waiting. Set
    // high enough that the long intra-region WALKS are reachable (Orbis->El Nath ~16, Ludibrium->Omega
    // Sector ~26 portal hops) and the TRAVEL-TIME PENALTY (BotTravelCost: decays to a 0.25 floor over a
    // 1h horizon, x4 for low levels) is the judge of whether a far map is worth it - not a hard hop cap.
    // Trade-off: a larger reachable set means more candidate maps profiled per decision, but that runs
    // on the async DECIDE_POOL with warmed caches. Tune down if decisions get heavy.
    static final int MAX_TRAVEL_HOPS = 30;
    private static final long DECISION_INTERVAL_MS = 12 * 60_000L;
    private static final long DECISION_JITTER_MS = 6 * 60_000L; // de-syncs many bots' re-decides
    private static final long ERRAND_COOLDOWN_MS = 10 * 60_000L; // min spacing between resupply trips
    private static final long OWNER_SUPPLY_GRACE_MS = 20_000L;
    static final long UPGRADE_REDECIDE_DELAY_MS = 25_000L; // re-ask stay-or-leave soon after a roll lands
    private static final long DEATH_STATUS_WINDOW_MS = 5 * 60_000L;

    private static final List<String> NO_SPOT_REPLIES = List.of(
            "can't find anywhere worth grinding that i can walk to, staying put",
            "hmm, nowhere walkable looks good rn");
    // Stranded inside a region the bot is too low to walk out of (e.g. Sleepywood for a <15 bot).
    // It can't legally leave on its own - a passing player has to donate a return scroll. ASCII only.
    private static final List<String> STUCK_BEG_REPLIES = List.of(
            "im stuck in here and cant walk out - a return scroll would save me if anyones passing",
            "trapped in this dungeon, cant get out on my own. could use a return scroll",
            "cant find a way out of here. a return scroll would get me back to town");
    private static final List<String> SCROLL_ESCAPE_REPLIES = List.of(
            "a return scroll, thanks! getting out of here",
            "got a scroll, heading back to town",
            "thanks for the rescue, leaving this place");
    private static final List<String> BACK_REPLIES = List.of(
            "back",
            "back, continuing",
            "back, resuming");

    private static final List<String> LEECH_ENTER_MSGS = List.of(
            "ill chill and let you catch up",
            "taking a breather so you can level",
            "ill idle a bit, catch up");
    private static final List<String> LEECH_EXIT_MSGS = List.of(
            "ok were close enough, back in",
            "caught up, grinding again",
            "back to it");

    // Test seams: the real advisor needs WZ/DB; replies go through the owner's chat channel.
    @FunctionalInterface
    interface Advisor {
        Recommendation recommend(BotEntry entry, Character bot, int fromMapId, int maxHops, boolean withFerry);
    }

    static Advisor advisor = (entry, bot, fromMapId, maxHops, withFerry) -> {
        BotWorldGraph.RouteOptions options = travelOptions(bot, withFerry);
        IntPredicate gate = routeBlockFor(bot);
        Set<Integer> reachable = BotWorldGraph.reachableWithin(fromMapId, maxHops, options,
                m -> isAvoided(entry, m) || gate.test(m));
        return BotGrindAdvisor.recommend(entry, bot, reachable::contains,
                travelWeight(bot, fromMapId, maxHops, options, entry.activeQuestMobIds, rollWanderlust(entry)),
                BotOccupancy.extraCompetitors(bot, BotManager.cfg.CROWD_PENALTY_FACTOR));
    };

    @FunctionalInterface
    interface FarmAdvisor {
        Recommendation recommend(BotEntry entry, Character bot, int itemId, int fromMapId, int maxHops,
                                 boolean withFerry);
    }

    static FarmAdvisor farmAdvisor = (entry, bot, itemId, fromMapId, maxHops, withFerry) -> {
        BotWorldGraph.RouteOptions options = travelOptions(bot, withFerry);
        IntPredicate gate = routeBlockFor(bot);
        Set<Integer> reachable = BotWorldGraph.reachableWithin(fromMapId, maxHops, options,
                m -> isAvoided(entry, m) || gate.test(m));
        return BotGrindAdvisor.recommendFarmItem(entry, bot, itemId, reachable::contains,
                travelWeight(bot, fromMapId, maxHops, options, entry.activeQuestMobIds, rollWanderlust(entry)),
                BotOccupancy.extraCompetitors(bot, BotManager.cfg.CROWD_PENALTY_FACTOR));
    };

    // Death-loop breaker tuning. Deaths closer together than the window chain into a streak; once the
    // streak hits the threshold the bot is judged stuck on a lethal route and escapes (below).
    static final long DEATH_LOOP_WINDOW_MS = 3 * 60_000L;
    static final int  DEATH_LOOP_THRESHOLD = 3;
    static final long DEATH_LOOP_AVOID_MS  = 20 * 60_000L; // how long the lethal route map stays shunned

    /** True while {@code mapId} is on this bot's death-loop blacklist (expired entries read as clear). */
    static boolean isAvoided(BotEntry entry, int mapId) {
        Long until = entry.autopilotAvoidMapUntilMs.get(mapId);
        return until != null && System.currentTimeMillis() < until;
    }

    // Sleepywood (the Victoria dungeon area: every map id 105######) is a deadly maze a fragile bot
    // can't walk out of on its own - even real players need a rescue. Hard-block routing INTO any of
    // it below this level so the planner never sends a low bot there (prevention; the death-loop
    // breaker still teleports an already-stuck bot out to a hub town).
    static final int SLEEPYWOOD_REGION_MIN_LEVEL = 15;

    /** True for any map in the Sleepywood / Victoria-dungeon region (map ids 105######). */
    static boolean isSleepywoodRegion(int mapId) {
        return mapId / 1_000_000 == 105;
    }

    /** A region the bot is too low-level to safely traverse — pruned from route planning entirely. */
    static boolean isDangerRegionBlocked(Character bot, int mapId) {
        return bot.getLevel() < SLEEPYWOOD_REGION_MIN_LEVEL && isSleepywoodRegion(mapId);
    }

    /**
     * SSOT route gate for a bot: the maps a low bot must not be sent into — the danger region itself,
     * AND any map that dumps it there on death (returnMap). Every bot-aware route/reachable query funnels
     * through {@link #reachableForBot}/{@link #routeForBot} so no caller can forget it; a >=15 bot gets an
     * empty gate (no pruning).
     */
    static IntPredicate routeBlockFor(Character bot) {
        return m -> isDangerRegionBlocked(bot, m) || isDangerRegionBlocked(bot, BotWorldGraph.returnMapOf(m));
    }

    /** Maps reachable for THIS bot — like {@link BotWorldGraph#reachableWithin} but always applies the
     *  {@link #routeBlockFor} danger gate. Use this, never the raw graph call, for bot travel. */
    static Set<Integer> reachableForBot(Character bot, int fromMapId, int maxHops,
                                        BotWorldGraph.RouteOptions options) {
        return BotWorldGraph.reachableWithin(fromMapId, maxHops, options, routeBlockFor(bot));
    }

    /** Shortest route for THIS bot, danger-gated; null when the target is only reachable through a
     *  region this bot is walled out of (same as genuinely unreachable — caller stays put). */
    static List<Integer> routeForBot(Character bot, int fromMapId, int toMapId, int maxHops,
                                     BotWorldGraph.RouteOptions options) {
        return BotWorldGraph.route(fromMapId, toMapId, maxHops, options, routeBlockFor(bot));
    }

    /**
     * Last-ditch escape for a bot stranded inside a region it's too low to walk out of (the planner
     * found nothing reachable worth grinding AND the bot is standing in a walled-off region). The
     * only LEGAL way out is a return scroll — same as a real player, who'd need a rescue here too —
     * so we try one ({@link BotManager#tryUseReturnScroll}, which skips any that would re-trap it).
     * With no usable scroll the bot just asks for one and waits; a passing player donating a scroll
     * into its USE bag lets the next pass self-rescue. Returns true when the bot was trapped (handled
     * here, caller should stop), false when it's free to grind normally.
     */
    private static boolean escapeTrappedRegion(BotEntry entry, Character bot) {
        if (bot.getMap() == null || !isDangerRegionBlocked(bot, bot.getMapId())) {
            return false;
        }
        if (BotManager.getInstance().tryUseReturnScroll(bot)) {
            reply.accept(entry, BotManager.randomReply(SCROLL_ESCAPE_REPLIES));
            entry.autopilotNextDecisionAtMs = 0L; // re-plan from the town we just warped to, next tick
            return true;
        }
        reply.accept(entry, BotManager.randomReply(STUCK_BEG_REPLIES));
        return true;
    }

    /**
     * Death-loop escape. Blacklists the lethal route — the map the bot died on plus the grind target
     * it kept dying trying to reach — so {@link #isAvoided} prunes them from the route flood and the
     * next decide picks a safe, reachable target instead. Drops the current destination and returns
     * the nearest safe town to revive at (forced-return town) instead of the dungeon return map, or
     * -1 when no distinct town is known (caller keeps the normal return map). Resets the streak.
     */
    static int onDeathLoop(BotEntry entry, Character bot, long now) {
        long until = now + DEATH_LOOP_AVOID_MS;
        entry.autopilotAvoidMapUntilMs.put(bot.getMapId(), until);
        if (entry.autopilotMapId > 0) {
            entry.autopilotAvoidMapUntilMs.put(entry.autopilotMapId, until);
        }
        entry.autopilotMapId = -1;            // drop the pick; BotManager.maybeRecoverInertAutopilot re-decides
        entry.autopilotDestinationName = "";
        entry.autopilotErrandMapId = -1;
        // Clear the 12-min grind-redecide clock: maybeRecoverInertAutopilot reuses it as its backoff
        // gate, so leaving it at the last plan's `decide_time + DECISION_INTERVAL_MS` would block
        // recovery for up to 12 min after a death-loop — the bot idles inert that whole window. Mirror
        // escapeTrappedRegion/clear() which zero it to re-plan next tick. (Root cause of the town-idle
        // pile-up: every death-loop = up to 12 min forced idle. See kb_bot_inert_autopilot_recovery.)
        entry.autopilotNextDecisionAtMs = 0L;
        entry.autopilotDeathStreak = 0;       // gave it an escape; count fresh from here
        reply.accept(entry, "i keep dying getting there - heading to town to find somewhere safer");
        // Revive at the forced-return town, UNLESS that's itself in a region this bot is walled out of
        // (e.g. Sleepywood town, reachable only back through the blocked dungeon) or unset - then bail
        // to Henesys, a real hub from which a low bot has safe local grind spots.
        int town = bot.getMap().getForcedReturnId();
        boolean unusable = town <= 0 || town == 999999999 // MapId.NONE
                || town == bot.getMapId()
                || isDangerRegionBlocked(bot, town);
        return unusable ? MapId.HENESYS : town;
    }

    /** Ferries need the owner's green light ("sail away") while the owner is around; with the
     *  owner absent/offline nobody is waiting, so the bot may sail on its own judgment.
     *  Self-owned bots (@botme) have no human owner at all — same rule. */
    static boolean ferryAllowed(BotEntry entry) {
        return entry.autopilotFerryApproved || entry.owner == null || entry.owner == entry.bot
                || !entry.owner.isLoggedinWorld();
    }

    /** The bot's Spinel return target (saved WORLDTOUR origin) ONLY while it is anywhere in Zipangu
     *  (continent 8 — Mushroom Shrine 800000000, Showa, the fields), else -1. Continent 8 is an island:
     *  its sole entry is the Spinel ride (which saves WORLDTOUR) and death/relog keeps the bot in-continent,
     *  so any bot inside has a valid origin and route planning from the interior can include the Spinel exit
     *  (walk to shrine → ride out). Showa town death-returns to itself (not the shrine), so the gate is the
     *  CONTINENT, not the return-map. Off-continent (mainland) a stale save still gets -1, so it can't reopen
     *  the shrine as a through-shortcut to Lith Harbor. Mirrored by BotWorldGraph.expand/findTaxiEdge and
     *  BotTravelCost.floodSeconds. */
    static int worldTourReturn(Character bot) {
        boolean inZipangu = bot.getMapId() / 100000000 == BotWorldGraph.MUSHROOM_SHRINE / 100000000;
        if (!inZipangu) {
            return -1;
        }
        // Match scripts/npc/9000020.js: Spinel sends a player with no saved WORLDTOUR
        // location to Lith Harbor rather than leaving them in Mushroom Shrine.
        int saved = bot.peekSavedLocation("WORLDTOUR");
        return saved != -1 ? saved : MapId.LITH_HARBOUR;
    }

    /** The Free Market's per-bot exit edge (BotWorldGraph FM_ENTRANCE -> saved town): present only
     *  while the bot stands inside the FM maps — mirrors {@link #worldTourReturn} so the market can
     *  only ever be routed OUT of, never THROUGH. */
    static int fmReturn(Character bot) {
        return BotFreeMarketManager.isFmMap(bot.getMapId())
                ? BotFreeMarketManager.fmReturnTownMapId(bot) : -1;
    }

    /** What the bot can spend on travel right now: scrolls if carried, taxis per meso,
     *  ferries per the caller's owner-permission gate ({@link #ferryAllowed}). */
    static BotWorldGraph.RouteOptions travelOptions(Character bot, boolean withFerry) {
        return new BotWorldGraph.RouteOptions(BotShopManager.countReturnScrolls(bot) > 0, bot.getMeso(), withFerry,
                bot.getJob().getId() == 0, bot.getLevel(), worldTourReturn(bot), fmReturn(bot));
    }

    /**
     * SSOT travel-time penalty: ONE {@link BotTravelCost} flood from the bot's current map per
     * decision pass (heavy-ish — only ever runs on DECIDE_POOL), turned into the per-map score
     * multiplier the planner applies. Ferry time reads the runtime-mutable travelrate through
     * the bot's world at query time, never cached.
     */
    private static IntToDoubleFunction travelWeight(Character bot, int fromMapId, int maxHops,
                                                    BotWorldGraph.RouteOptions options, Set<Integer> questMobs) {
        return travelWeight(bot, fromMapId, maxHops, options, questMobs, 1.0);
    }

    private static IntToDoubleFunction travelWeight(Character bot, int fromMapId, int maxHops,
                                                    BotWorldGraph.RouteOptions options, Set<Integer> questMobs,
                                                    double travelDiscount) {
        IntToLongFunction transportationTime = ms -> bot.getWorldServer().getTransportationTime(ms);
        Map<Integer, Double> seconds = BotTravelCost.floodSeconds(fromMapId, maxHops, options, transportationTime);
        int level = bot.getLevel();
        // Quest commitment: boost maps that spawn a mob the bot still needs for a started quest, so it
        // goes to finish what it accepted instead of drifting to a richer grind. questMobs is the
        // tick-thread-refreshed BotEntry snapshot (volatile) - NOT recomputed here, since this runs on
        // DECIDE_POOL and iterating the live quest-progress map off-thread races the kill counter (CME).
        return mapId -> BotTravelCost.scoreWeight(seconds, mapId, level, travelDiscount)
                * BotQuestManager.questMapScoreBias(mapId, questMobs);
    }

    /**
     * Once in a while a bot abandons its local-grind travel bias and roams for a genuinely better spot,
     * even far away. Whether it fires and how hard it lifts the travel penalty are both trait-driven
     * ({@link BotPersonality#wanderlustChance}/{@link BotPersonality#wanderlustTravelDiscount}). Returns
     * the travel-time discount for this decision (1.0 = normal local bias). Hazard/level avoidance is
     * unaffected — it lives in the reachable-set prune, so a roaming bot still never routes into danger.
     */
    private static double rollWanderlust(BotEntry entry) {
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        return ThreadLocalRandom.current().nextDouble() < p.wanderlustChance()
                ? p.wanderlustTravelDiscount()
                : 1.0;
    }

    @FunctionalInterface
    interface PartyDecider {
        PartyPlan decide(List<BotEntry> members);
    }

    /**
     * The shared inputs to a party decision: the common reachable map set (walkable for EVERY
     * member — they can start scattered), each member's own travel-time penalty from wherever it
     * stands, and each member's candidate pool restricted to those maps. Assembled ONCE here so
     * the real decider and the autopilot-debug dump feed the planner the SAME numbers.
     */
    record PartyInputs(Set<Integer> allowed, List<IntToDoubleFunction> weights,
                       List<List<MobCandidate>> perMember) {}

    static PartyInputs partyInputs(List<BotEntry> members) {
        Set<Integer> common = null;
        List<IntToDoubleFunction> weights = new ArrayList<>(members.size());
        for (BotEntry member : members) {
            BotWorldGraph.RouteOptions options = travelOptions(member.bot, ferryAllowed(member));
            Set<Integer> reachable = reachableForBot(member.bot, member.bot.getMapId(), MAX_TRAVEL_HOPS, options);
            weights.add(travelWeight(member.bot, member.bot.getMapId(), MAX_TRAVEL_HOPS, options,
                    member.activeQuestMobIds));
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
        return new PartyInputs(allowed, weights, perMember);
    }

    static PartyDecider partyDecider = members -> {
        PartyInputs in = partyInputs(members);
        // Crowd surcharge from the cohort's shared perspective (own party already excluded); pick any
        // member to read world occupancy — they share a party so the exclusion is identical.
        IntToDoubleFunction crowd = members.isEmpty() ? mapId -> 0.0
                : BotOccupancy.extraCompetitors(members.get(0).bot, BotManager.cfg.CROWD_PENALTY_FACTOR);
        return BotGrindPlanner.planPartyBest(in.perMember(), in.weights(), crowd,
                currentPartyMap(members), grinderMask(members), ThreadLocalRandom.current());
    };

    /** The map the cohort is already grinding (most common autopilotMapId among members still in
     *  party-autopilot), or -1 on a fresh start when nobody has a party map yet. Feeds the planner's
     *  stay-put hysteresis so a join/leave re-decide doesn't relocate everyone. */
    private static int currentPartyMap(List<BotEntry> members) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (BotEntry m : members) {
            if (m.autopilotParty && m.autopilotMapId > 0) {
                counts.merge(m.autopilotMapId, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(-1);
    }

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
        long decideT0 = BotPerformanceMonitor.start();
        try {
            result = compute.get();
        } catch (RuntimeException e) {
            // Never swallow silently: a null decision idles the bot, so a hidden exception here used to
            // masquerade as a legitimate "no spot" with no trace. Log it; the caller still degrades.
            log.warn("bot autopilot decision threw on DECIDE_POOL", e);
            result = null;
        } finally {
            BotPerformanceMonitor.recordSince("autopilot-decide", decideT0);
        }
        Object applied = result;
        BotManager.after(0L, () -> apply.accept(applied));
    });

    private BotAutopilotManager() {}

    static boolean isActive(BotEntry entry) {
        return entry.autopilotMapId != -1;
    }

    static void clear(BotEntry entry) {
        // Drop the shared party plan when its owner (the leader) stands down; the next applyPartyPlan
        // recreates it. Followers clearing leave it alone — others may still be tracking it.
        if (entry.autopilotParty && entry.bot != null) {
            Integer key = partyStateKey(entry);
            PartyAutopilotState ps = key == null ? null : partyStates.get(key);
            if (ps != null && ps.leaderCharId == entry.bot.getId()) {
                partyStates.remove(key);
            }
        }
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
        entry.restErrand = false;
        entry.autopilotReturningFromErrand = false;
        entry.autopilotTransitFollow = false;
        entry.autopilotCohortMember = false;
        entry.autopilotWaitingForStragglers = false;
        entry.autopilotNextStragglerCheckAtMs = 0L;
        entry.autopilotWaitAnchor = null;
        entry.autopilotWaitAnchorMapId = -1;
        entry.autopilotDecisionInFlight = false;
        BotQuestManager.clearQuestErrand(entry); // a canceled autopilot abandons any quest detour
        BotGachaponManager.clearGachaErrand(entry); // ...and any gachapon trip
        BotFreeMarketManager.clearFmErrand(entry); // ...and any market session (if the bot is still
        // inside the FM, the stranded-exit recovery re-arms a bare exit walk next tick)
        BotStarterKitManager.clearJobErrand(entry); // ...and any job-change instructor walk
        BotTravelManager.resetForModeChange(entry); // drop the in-flight hop AND the give-up cooldown,
        // so a re-command (follow/grind/move) isn't silently gated by a stale travel give-up window.
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
                // Legitimate "nothing reachable worth grinding from here" (a non-null Decision with a
                // null rec). Debug, not warn — an actual exception was already warn'd in decide().
                log.debug("bot {} found no autopilot spot; will retry", bot.getName());
                recordDecision(entry, decision == null
                        ? "decide failed (see warn log)"
                        : "no reachable grind spot from " + currentMapName(bot));
                if (escapeTrappedRegion(entry, bot)) {
                    return; // walled into a danger region: used a donated scroll, or begged for one
                }
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
        // Quiesce every member while the shared decide runs off-thread: without this, a member's own
        // per-bot recovery/redecide (maybeRecoverInertAutopilot / maybeRedecide, both gated on this flag)
        // could fire mid-decide, call issueGrind, bump activityEpoch, and drop the whole party plan
        // (epoch-changed-mid-decide). Mirrors redecideParty. Cleared first thing in the callback below.
        for (BotEntry m : members) {
            m.autopilotDecisionInFlight = true;
        }
        decisionRunner.run(() -> decideParty(members), result -> {
            for (BotEntry m : members) {
                m.autopilotDecisionInFlight = false;
            }
            PartyPlan plan = (PartyPlan) result;
            for (int i = 0; i < members.size(); i++) {
                if (members.get(i).activityEpoch != epochs[i]) {
                    // Silent drop -> the party never engaged and stayed idle. Surface it so a
                    // command that keeps "not working" is diagnosable (see kb_bot_inert_autopilot_recovery).
                    Character mb = members.get(i).bot;
                    log.debug("party plan dropped: {} epoch changed mid-decide ({} members)",
                            mb != null ? mb.getName() : "?", members.size());
                    return; // somebody got a newer directive mid-decision — drop the stale plan
                }
            }
            if (plan == null) {
                // decideParty already logs the exception case; this covers a genuine no-reachable-spot.
                Character lb = members.get(0).bot;
                log.debug("party autopilot found no shared spot for {} ({} members)",
                        lb != null ? lb.getName() : "?", members.size());
                reply.accept(members.get(0), "can't find a spot we can all reach that's worth it");
                return;
            }
            applyPartyPlan(members, plan);
            announceParty(members, plan);
        });
    }

    /**
     * Owner ordered the whole cohort to a SPECIFIC map ("goto"): travel together via the party cohort
     * (leader routes, co-located members formation-follow), then settle there — pinned so the group does
     * NOT re-decide and wander off into a grind trip. Reuses {@link #applyPartyPlan}; the forced plan
     * carries no per-member grind objective (each idles / backs up on arrival, exactly like a party plan
     * that found nothing worthwhile there). Mobless maps idle; maps with mobs grind, same as the RTS move.
     */
    static void startPartyToMap(Character owner, List<BotEntry> entries, int mapId) {
        if (owner == null || entries == null || mapId <= 0) {
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
        List<Recommendation> per = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            per.add(null); // no grind objective: travel together, then idle / back the party up
        }
        applyPartyPlan(members, new PartyPlan(mapId, per));
        for (BotEntry m : members) {
            m.autopilotNextDecisionAtMs = Long.MAX_VALUE; // directed goto: stay put, never re-decide away
        }
    }

    /**
     * Group-synced break for a party cohort: only the leader (first cohort member) rolls, once a minute,
     * on the AVERAGE of the members' break traits. When it fires the whole cohort breaks together — each
     * member takes its own town-break (sell/resupply + rest + self-scroll) EXCEPT low-cluster members
     * ({@code >= PARTY_LEECH_GAP_TRIGGER} below the pack), which keep grinding to catch up. Returns true
     * when this member's break is group-managed (caller skips the per-member solo roll); false for a solo
     * bot (cohort &lt; 2), handled by the normal {@link BotBreakManager#maybeStartBreak}.
     * ponytail: calls members() per grind tick for party bots — cheap party iteration; revisit only if
     * the perf monitor flags it.
     */
    static boolean maybeStartGroupBreak(BotEntry entry, Character bot) {
        if (!entry.autopilotParty) {
            return false; // not party-grinding -> solo break path
        }
        List<BotEntry> cohort = partyMembers.members(entry);
        if (cohort.size() < 2) {
            return false;
        }
        if (cohort.get(0) != entry) {
            return true; // a follower: breaks only when the leader triggers the group (no self-roll)
        }
        long now = System.currentTimeMillis();
        if (BotBreakManager.onBreak(entry, now) || entry.restErrand || now < entry.nextBreakRollAtMs) {
            return true; // a group break is already running / just rolled
        }
        if (cohortTransitActive(cohort)) {
            return true; // do not strand portal waiters by starting a break mid-cohort travel
        }
        entry.nextBreakRollAtMs = now + 60_000L;
        double avgFreq = 0, avgIdle = 0;
        for (BotEntry m : cohort) {
            BotPersonality p = m.personality != null ? m.personality : BotPersonality.defaults();
            avgFreq += p.breakFreqPerHour();
            avgIdle += p.farmIdleRatio();
        }
        avgFreq /= cohort.size();
        avgIdle /= cohort.size();
        if (!BotBreakManager.startsBreak(avgFreq, avgIdle, ThreadLocalRandom.current().nextDouble())) {
            return true;
        }
        // Group break! Low-level catch-up members keep grinding; everyone else breaks together. The
        // leader picks ONE break spot (town or a nearby safe map) so the party rests as a group rather
        // than each member rolling its own destination and scattering.
        int dest = decideBreakDestination(entry, bot);
        for (BotEntry m : cohort) {
            if (m.bot == null || catchesUpThroughRest(m, cohort)) {
                continue; // behind the pack -> skip the break, grind solo to catch up
            }
            if (dest != -1 && dest != m.bot.getMapId()) {
                m.restErrand = true;
                m.autopilotErrandMapId = dest; // shared, pre-resolved -> member skips its own re-roll
            } else {
                BotBreakManager.startTownBreak(m, m.bot, now); // already at dest / in town -> rest in place
            }
        }
        return true;
    }

    /**
     * Whether {@code member} sits far enough below its cohort's pack to sit out the group's rest and keep
     * grinding to catch up. The single expression of that rule: the leader-triggered group break and the
     * crew-wide chill session both call it, so they can never disagree about who keeps grinding.
     * {@code cohort} is the party for a live group break, or a crew's live entries at session start
     * (before the party has formed) — the levels are the same set either way.
     */
    static boolean catchesUpThroughRest(BotEntry member, List<BotEntry> cohort) {
        if (member == null || member.bot == null || cohort == null || cohort.size() < 2) {
            return false;
        }
        int[] levels = cohort.stream().filter(m -> m.bot != null)
                .mapToInt(m -> m.bot.getLevel()).sorted().toArray();
        return BotBreakManager.catchUpSplit(member.bot.getLevel(), levels,
                BotManager.cfg.PARTY_LEECH_GAP_TRIGGER);
    }

    /**
     * True when this bot is grinding alone because its COHORT is resting — a crew-wide chill session or a
     * leader-triggered group break — and it is behind enough to catch up. Purely derived (no flag of its
     * own): a catch-up member is never marked {@code chillSession}, so the roster already buckets it as
     * grinding; this only names the reason it's out there by itself.
     */
    static boolean catchingUpWhileCohortRests(BotEntry entry) {
        if (entry == null || !entry.autopilotParty) {
            return false;
        }
        List<BotEntry> cohort = partyMembers.members(entry);
        if (!catchesUpThroughRest(entry, cohort)) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (BotEntry m : cohort) {
            if (m != entry && (m.chillSession || m.restErrand || now < m.breakUntilMs)) {
                return true;
            }
        }
        return false;
    }

    static boolean cohortTransitActive(List<BotEntry> cohort) {
        if (cohort == null) {
            return false;
        }
        for (BotEntry m : cohort) {
            if (m == null) {
                continue;
            }
            if (m.followTravelTargetMapId != -1
                    || m.autopilotTransitFollow
                    || m.autopilotWaitAnchor != null
                    || m.autopilotWaitingForStragglers) {
                return true;
            }
        }
        return false;
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
                if (escapeTrappedRegion(entry, bot)) {
                    return; // walled into a danger region: used a donated scroll, or begged for one
                }
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
     * A "detour" errand: a self-contained side-trip (walk to an NPC, do a thing, resume grinding)
     * that preempts the grind destination and consumes the tick while active. Each owns its own
     * BotEntry state field and its own manager. New detour errand = implement this + add it to
     * {@link #DETOUR_ERRANDS}; the tick loop drives them all uniformly, in order.
     *
     * NOTE: resupply and town-rest are deliberately NOT detours — they reuse the MAIN travel pipeline
     * (their town becomes autopilotErrandMapId, the tick's travel destination) rather than consuming
     * the tick with their own walk, so they live in the travel flow below, not here.
     */
    interface DetourErrand {
        /** Restart hook for an errand that should be in flight but isn't (a missed level-up edge, a
         *  relog mid-walk). No-op for most; the job advance uses it to re-arm an overdue advancement. */
        default void maybeStart(BotEntry entry, Character bot) {}
        /** True while this errand owns the bot (its destination field is set). */
        boolean active(BotEntry entry);
        /** One tick of the errand; true when it consumed the tick (the caller then returns true). */
        boolean tick(BotEntry entry, Character bot, boolean runAiTick);
        /** True to skip this errand's tick this cycle and fall through to the resupply flow below
         *  (still active — just not driving this tick). Lets a cramped bag preempt a detour that
         *  might need to buy something (a ferry/taxi fare item) it has no room for. */
        default boolean yieldForResupply(BotEntry entry, Character bot) { return false; }
    }

    // Detour errands in precedence order. Job advance first (must not over-level en route), then the
    // quest piggyback, then the gachapon trip. Behavior is identical to the old hardcoded if-chain;
    // the point is that a new detour errand is now one list entry, not another tick() branch.
    private static final List<DetourErrand> DETOUR_ERRANDS = List.of(
            new DetourErrand() { // job change: walk to the class-town instructor, advance on arrival
                @Override public void maybeStart(BotEntry entry, Character bot) {
                    if (entry.jobErrandMapId == -1) {
                        BotBuildManager.maybeStartOverdueJobAdvance(entry, bot);
                    }
                }
                @Override public boolean active(BotEntry entry) { return entry.jobErrandMapId != -1; }
                @Override public boolean tick(BotEntry entry, Character bot, boolean runAiTick) {
                    return BotStarterKitManager.tickJobErrand(entry, bot, runAiTick);
                }
                // A cross-continent leg (taxi/ferry) buys a fare item; a full bag fails that buy and
                // the job errand retries it forever (JOB_CHANGE_FALLBACK_ANYWHERE is off — it never
                // releases the tick to let a resupply trip run and free space). Yield so the resupply
                // flow below can sell trash first; job errand resumes once space frees up.
                @Override public boolean yieldForResupply(BotEntry entry, Character bot) {
                    return bagFull.bagFull(entry, bot);
                }
            },
            new DetourErrand() { // quest piggyback: detour to a quest NPC to start/turn in, then resume
                @Override public boolean active(BotEntry entry) { return entry.questErrandMapId != -1; }
                @Override public boolean tick(BotEntry entry, Character bot, boolean runAiTick) {
                    return BotQuestManager.tickErrand(entry, bot, runAiTick);
                }
            },
            new DetourErrand() { // free-market session: stall + browse trip during a rest break
                @Override public boolean active(BotEntry entry) { return entry.fmErrandMapId != -1; }
                @Override public boolean tick(BotEntry entry, Character bot, boolean runAiTick) {
                    return BotFreeMarketManager.tickErrand(entry, bot, runAiTick);
                }
                // Stall setup may need bag/CASH space for the permit; let a cramped bag resupply first.
                @Override public boolean yieldForResupply(BotEntry entry, Character bot) {
                    return bagFull.bagFull(entry, bot);
                }
            },
            new DetourErrand() { // gachapon trip: detour to a gacha NPC, buy + roll tickets, then resume
                @Override public boolean active(BotEntry entry) { return entry.gachaErrandMapId != -1; }
                @Override public boolean tick(BotEntry entry, Character bot, boolean runAiTick) {
                    return BotGachaponManager.tickErrand(entry, bot, runAiTick);
                }
            });

    /**
     * One autopilot tick, between the map-change rebuild and the follow sync. Returns true
     * when the tick was consumed (walking a travel hop); false to continue the normal
     * grind/combat flow.
     */
    static boolean tick(BotEntry entry, Character bot, boolean runAiTick) {
        if (!isActive(entry) || bot.getMap() == null) {
            return false;
        }
        // Party SSOT: pull this member's grind destination from the one shared plan before anything
        // reads autopilotMapId below. A follower can no longer drift onto a stale solo pick (the old
        // per-member-copy split-up bug); the leader owns the plan, followers track it every tick.
        syncFromPartyState(entry);
        // Any errand (quest / gachapon / resupply detour) supersedes a party transit-hold and runs
        // independently. Each of those branches returns or skips the cohesion section below, which is
        // the only thing that clears the wait anchor when the group regroups -- so a wait anchor pinned
        // during transit would otherwise survive into the errand and strand the bot loitering at the
        // old portal (BotManager.loiterAtAnchor) instead of traveling. Drop it up front. No-op when no
        // anchor is set; clearWaitAnchor restores grinding=true.
        if (entry.autopilotWaitAnchor != null
                && (entry.questErrandMapId != -1 || entry.gachaErrandMapId != -1
                    || entry.fmErrandMapId != -1
                    || entry.autopilotErrandMapId != -1 || entry.jobErrandMapId != -1)) {
            clearWaitAnchor(entry);
        }
        // A live operator command (RTS MOVE, or a companion `goto <map>`) pins the destination and
        // suppresses autopilot self-direction: no job/quest/gachapon detours, no resupply/rest-break
        // trips. The bot just travels there. Mirrors the same gate in maybeRedecide (no re-pick under a
        // command). Errand fields are left intact, so any deferred errand resumes once the command lapses.
        boolean operatorPinned = entry.operatorCmd != null;
        // Detour errands (job advance / quest piggyback / gachapon), in precedence order. maybeStart
        // re-arms an errand that should be running but isn't (e.g. job advance after a missed level-up
        // edge or a relog); an active errand that consumes the tick short-circuits the grind flow.
        if (!operatorPinned) {
            for (DetourErrand errand : DETOUR_ERRANDS) {
                errand.maybeStart(entry, bot);
                if (errand.active(entry) && !errand.yieldForResupply(entry, bot)) {
                    // An errand that travels must never do so parked in a break-time chair. The FM
                    // shout-stand is the one errand sub-state that deliberately sits, so leave it be.
                    if (!BotFreeMarketManager.isShoutStanding(entry)) {
                        BotChairManager.standIfSeated(bot);
                    }
                    if (errand.tick(entry, bot, runAiTick)) {
                        return true;
                    }
                }
            }
        }
        if (!operatorPinned && entry.restErrand && entry.autopilotErrandMapId == -1) {
            resolveTownRestDestination(entry, bot); // pick the rest town (or abort restErrand) before travel
        }
        int destination = (!operatorPinned && entry.autopilotErrandMapId != -1)
                ? entry.autopilotErrandMapId : entry.autopilotMapId;
        if (bot.getMapId() == destination) {
            if (entry.autopilotTransitFollow) {
                // Arrived with the group: swap the follow pipeline back out for grind combat.
                entry.autopilotTransitFollow = false;
                BotManager.getInstance().resumeAutopilotGrind(entry);
            }
            // A leader that arrived while portal-anchored (waiting) left grinding off — restore
            // it so the on-site grind flow runs. The wait anchor self-clears on the map change.
            clearWaitAnchor(entry);
            if (entry.autopilotErrandMapId != -1) {
                if (entry.shopVisitPending) {
                    return false; // shopping (sells trash + resupplies); the visit flow owns the tick
                }
                if (entry.restErrand) {
                    // Town-break: shop done -> linger to rest + self-scroll for the 10-30min window.
                    long nowRest = System.currentTimeMillis();
                    if (entry.breakUntilMs == 0L) {
                        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
                        entry.breakUntilMs = nowRest + BotBreakManager.townBreakDurationMs(p.laziness());
                        entry.breakIdleAnchor = null;
                    }
                    if (nowRest < entry.breakUntilMs) {
                        return false; // resting in town: grind-tick break-idle + self-scroll run this tick
                    }
                    if (entry.chillSession) {
                        // Logged in to chill: re-arm the rest window instead of resuming grind, so the bot
                        // lingers in town the whole (half-length) session. Scheduler logs it out at session end.
                        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
                        entry.breakUntilMs = nowRest + BotBreakManager.townBreakDurationMs(p.laziness());
                        entry.breakIdleAnchor = null;
                        return false;
                    }
                    entry.restErrand = false; // rest over -> head back to the grind map
                    entry.autopilotErrandMapId = -1;
                    entry.autopilotReturningFromErrand = true;
                    reply.accept(entry, BotManager.randomReply(BACK_REPLIES));
                    return false;
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
        // Pre-travel resupply: about to depart for a grind map we're not on yet, but supplies are
        // already below the reactive errand's threshold OR the bag is full enough to need a junk
        // dump — restock/unload FIRST instead of traveling out, bouncing straight back to town,
        // then traveling again. requestResupplyErrand picks the town and sets autopilotErrandMapId,
        // which flips this tick's destination + skips cohesion (line below) so the bot peels off
        // independently. The !returningFromErrand guard breaks the loop: after restocking, the
        // return trip must not re-trigger the pre-travel errand.
        // Affordability gate matches the reactive errand (BotPotionManager): a broke bot low on pots
        // shouldn't peel off to town to buy nothing. Bag-full (a SELL trip) stays ungated — it earns meso.
        boolean lowAndCanBuy = supplyLevel.lowOnSupplies(bot) && BotShopManager.canAffordPotResupply(bot);
        // Out of ammo is combat-blocking: a broke claw/gun bot peels to town to sell trash and refill
        // (otherwise it travels out, can't attack, and is stuck) - but only if the trip can actually
        // re-arm it. A truly-broke bot with nothing to sell keeps grinding (degenerate close-range
        // swing) to earn the meso first rather than bouncing to town forever.
        boolean ammoStranded = BotShopManager.isOutOfUsableAmmo(bot) && BotShopManager.canRecoverAmmo(entry, bot);
        boolean needsPreferredWeapon = BotShopManager.needsPreferredWeaponForCurrentJob(bot);
        if (!operatorPinned && entry.autopilotErrandMapId == -1 && !entry.autopilotReturningFromErrand
                && (lowAndCanBuy || ammoStranded || needsPreferredWeapon || bagFull.bagFull(entry, bot))) {
            requestResupplyErrand(entry, bot);
            if (entry.autopilotErrandMapId != -1) {
                destination = entry.autopilotErrandMapId; // head to town this tick, not the grind map
            }
        }
        if (entry.autopilotParty && !detachedFromPartyCohesion(entry)) {
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
            // A rest-errand whose town is UNREACHABLE must also drop restErrand. Otherwise line ~578
            // re-resolves it next tick (resolveTownRestDestination has no cooldown of its own), which
            // re-announces "heading to town for a breather" EVERY tick (dozens/sec of chat spam) and
            // re-pins destination on the unreachable town so the stranding-recovery below never runs.
            // Abort the rest and gate the next break roll; a stranded bot keeps grinding/recovers.
            if (entry.restErrand) {
                entry.restErrand = false;
                long now = System.currentTimeMillis();
                entry.nextBreakRollAtMs = Math.max(entry.nextBreakRollAtMs, now + 60_000L);
            }
        }
        maybeRedecide(entry, bot);
        // Stranded off the destination map with no route: rather than anchor to the owner,
        // wander to a random legal cross-map portal and take it, then re-plan from there
        // (BotTravelManager.tickWanderToRandomPortal). Only when truly off-site — on the
        // grind map an empty target is just "mobs cleared", which the normal grind-wander
        // handles. No usable portal here -> fall through and grind whatever is around.
        //
        // A `deadline` give-up means the route EXISTS but the committed portal is hard to physically
        // reach (e.g. a cross portal gated behind an in-map warp portal — iArroWLanE at Crystal Gorge).
        // Freezing for the full 45s window and re-failing the identical hop strands the bot in place;
        // instead let it wander to a DIFFERENT cross portal and re-plan from there — real movement + an
        // escape, not a 45s stop. tickTravel below skips its per-tick clear() during a deadline window so
        // the wander's committed portal survives and it converges instead of thrashing between portals.
        // Other give-up reasons (portal-closed, script/warp-no-land, ferry-board-fail) keep the cooldown
        // park and only resume wandering once the window passes.
        boolean deadlineHop = "deadline".equals(entry.followTravelGiveUpReason);
        if ((deadlineHop || System.currentTimeMillis() >= entry.followTravelGiveUpUntilMs)
                && BotTravelManager.tickWanderToRandomPortal(entry, bot, runAiTick)) {
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
    /** Pick the town for a self-scroll bot's town-break: a nearby shop-town (so the arrival visit sells
     *  trash + resupplies, same picker as a resupply errand) when reachable, else the return-map town.
     *  Sets autopilotErrandMapId; aborts restErrand when there's nowhere to go (already in town / none). */
    private static void resolveTownRestDestination(BotEntry entry, Character bot) {
        if (bot.getMap() == null) {
            entry.restErrand = false;
            return;
        }
        int dest = decideBreakDestination(entry, bot);
        if (dest == -1 || dest == bot.getMapId()) {
            entry.restErrand = false; // nowhere worth going -> keep grinding
            return;
        }
        entry.autopilotErrandMapId = dest;
        reply.accept(entry, "heading off for a breather");
    }

    /** Where this bot should take its break: usually a town (the arrival shop-visit sells trash +
     *  resupplies + self-scrolls), but a deep grind spot (many hops to walk back from town) often rests
     *  at a nearby safe map instead, to avoid wasting a full town round-trip. Returns the destination map
     *  id, or -1 when already in a town / nowhere to go. Shared by the solo rest-errand and the group
     *  break so a party decides one break spot together rather than each member rolling its own. */
    static int decideBreakDestination(BotEntry entry, Character bot) {
        if (bot.getMap() == null) {
            return -1;
        }
        Integer shopMap = BotShopManager.findNearestShopMap(entry, bot, !BotShopManager.needsToBuySupplies(entry, bot));
        int town = shopMap != null && shopMap != bot.getMapId()
                ? shopMap
                : (bot.getMap().getReturnMap() != null ? bot.getMap().getReturnMap().getId() : -1);
        if (town == -1 || town == bot.getMapId()) {
            return -1; // already in a town / nowhere worth going -> rest in place
        }
        int grindMap = entry.autopilotMapId != -1 ? entry.autopilotMapId : bot.getMapId();
        int townHops = BotBreakManager.hopsBack(town, grindMap);
        if (ThreadLocalRandom.current().nextDouble() < BotBreakManager.townBreakChance(townHops)) {
            // Trade break rides the normal break: with pending market intent, land the SAME break
            // trip at an FM-portal town when one is a small detour away (never a separate trek).
            Integer fmTown = BotFreeMarketManager.preferFmBreakTown(entry, bot, town,
                    System.currentTimeMillis());
            return fmTown != null ? fmTown : town;
        }
        int nearby = BotBreakManager.findNearbyBreakMap(grindMap, townHops);
        return nearby != -1 ? nearby : town; // no closer safe map -> town anyway (deep spots rest
                                             // nearby and skip the market until a townside break)
    }

    static boolean requestResupplyErrand(BotEntry entry, Character bot) {
        if (!isActive(entry) || bot.getMap() == null) {
            logErrandBlock(entry, bot, bot.getMap() == null ? "no-map" : "not-autopilot");
            return false;
        }
        if (entry.autopilotErrandMapId != -1) {
            return true; // already on a trip; nothing to diagnose
        }
        long now = System.currentTimeMillis();
        if (now < entry.autopilotNextErrandAtMs) {
            logErrandBlock(entry, bot, "errand-cooldown");
            return true; // just tried — don't bounce to the owner
        }
        if (now < entry.autopilotOwnerSupplyGraceUntilMs) {
            logErrandBlock(entry, bot, "owner-supply-grace");
            return true; // party request just went out; give owner trade a short chance to land
        }
        // Seek the nearest reachable SHOP that fits the need, not just "nearest town". A bot stranded
        // in a town hub whose own map has no shop NPC (Orbis 200000000 -> department store 200000002,
        // one portal away) used to bail here on "return map == self" and never sell/restock. Pots are
        // the only need that requires a specific (potion-stocking) shop; a full bag or low ammo is fine
        // at any shop. Falls back to the old return-map town when no shop is reachable in range.
        int targetMapId;
        boolean needsPreferredWeapon = BotShopManager.needsPreferredWeaponForCurrentJob(bot);
        Integer shopMapId = BotShopManager.findNearestShopMap(entry, bot, !BotShopManager.needsToBuySupplies(entry, bot));
        if (shopMapId != null && shopMapId != bot.getMapId()) {
            targetMapId = shopMapId;
        } else {
            if (needsPreferredWeapon) {
                entry.autopilotNextErrandAtMs = now + ERRAND_COOLDOWN_MS;
                logErrandBlock(entry, bot, "no-reachable-shop-with-preferred-weapon");
                return false;
            }
            var returnMap = bot.getMap().getReturnMap();
            if (returnMap == null || returnMap.getId() == bot.getMapId()) {
                // No errand to run, but ARM the cooldown anyway: findNearestShopMap above does an
                // uncached multi-hop BFS (loads maps, scans NPCs) on the bot tick. Without this, a
                // stranded bot (the exact case this path targets) re-floods that BFS every caller tick.
                entry.autopilotNextErrandAtMs = now + ERRAND_COOLDOWN_MS;
                logErrandBlock(entry, bot, shopMapId != null
                        ? "shop-on-current-map(" + bot.getMapId() + ")"
                        : "no-reachable-shop,return-map==self(" + bot.getMapId() + ")");
                return false;
            }
            targetMapId = returnMap.getId();
        }
        entry.autopilotErrandMapId = targetMapId;
        entry.autopilotNextErrandAtMs = now + ERRAND_COOLDOWN_MS;
        reply.accept(entry, resupplyErrandMessage(entry, bot));
        // No explicit scroll use here: scroll-to-town is a world-graph edge now, so the
        // travel tick takes it whenever it beats walking (BotTravelManager consumable hops).
        return true;
    }

    /** Diagnostic for "the bag is full but the bot never walked to a shop": records, throttled per
     *  bot, the gate that stopped a wanted resupply/sell errand from starting. Grep {@code bot-errand}
     *  in the server log to see which condition is blocking a given bot. */
    private static void logErrandBlock(BotEntry entry, Character bot, String reason) {
        if ("errand-cooldown".equals(reason)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < entry.autopilotLastErrandLogAtMs + ERRAND_BLOCK_LOG_THROTTLE_MS) {
            return;
        }
        entry.autopilotLastErrandLogAtMs = now;
        String name = bot != null ? bot.getName() : "?";
        log.info("bot-errand: {} wanted a town errand but couldn't start one: {} (grinding={}, active={})",
                name, reason, entry.grinding, isActive(entry));
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
            if (BotShopManager.needsPreferredWeaponForCurrentJob(bot)) {
                reasons.add("need a " + BotShopManager.preferredWeaponName(bot));
            }
        } catch (RuntimeException ignored) {
            // Gear-readiness text is diagnostic only; keep the errand alive.
        }

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

    /** Coarse activity bucket for the roster summary: {@code "chill"} for a whole-session chill login;
     *  {@code "break"} when otherwise resting/on a chore (in-session break, gacha/FM/quest/job/rest
     *  errand, idle-leech, or winding down to log off); {@code "idle"} for the inert-autopilot LEAK
     *  (autopilot off with NO rest reason — status "idle rn", a bug, NOT a break; see
     *  {@link #statusReport} and kb_bot_inert_autopilot_recovery); else {@code "grind"} (productive).
     *  The {@code idle} split lets the roster surface and filter to genuinely-stuck bots. */
    static String activityCategory(BotEntry entry, Character bot) {
        if (entry == null || bot == null) {
            return "grind";
        }
        if (entry.chillSession) {
            return "chill";
        }
        // Explained non-grind states -> "break" (resting or travelling on a chore, not stuck). Mirror
        // statusReport's gating so an errand-bound bot is never mislabelled as the leak.
        if (entry.loggingOut || entry.gachaErrandMapId != -1 || entry.fmErrandMapId != -1
                || entry.questErrandMapId != -1 || entry.jobErrandMapId != -1 || entry.restErrand
                || System.currentTimeMillis() < entry.breakUntilMs || entry.idleLeech) {
            return "break";
        }
        // Autopilot leaked OFF with no reason above = the inert-leak bug (status "idle rn").
        if (!isActive(entry)) {
            return "idle";
        }
        return "grind";
    }

    /**
     * Short reason when the bot is detectably WEDGED — conditions that are easy to detect but that
     * the bot cannot fix by itself (or only escapes slowly) — else null. Powers the roster's
     * "possibly stuck" counter/filter; strictly broader than the {@code "idle"} activity bucket,
     * which missed e.g. the Pyramid-Dunes trap bots because their chill/break state hid them.
     */
    static String stuckReason(BotEntry entry, Character bot) {
        if (entry == null || bot == null) {
            return null;
        }
        // Decide loop can't find anywhere to grind (one-way-map trap class: Pyramid Dunes, Leafre
        // dock). The LAST decision failed and no plan was installed since — a successful decide
        // overwrites the reason ("grind <dest> ..."), so a lingering failure = still failing.
        String reason = entry.autopilotLastDecisionReason;
        if (!isActive(entry) && reason != null
                && (reason.startsWith("no reachable grind spot") || reason.startsWith("decide failed"))) {
            return "can't find anywhere to grind";
        }
        // A committed job errand with no world route cannot make progress. This is distinct from a
        // transient travel give-up: tickJobErrand records the route-null verdict after its full route
        // check, so the web roster can surface the wedge without re-running world routing per poll.
        if (entry.jobErrandMapId != -1 && entry.jobErrandTarget != null && entry.jobErrandRouteUnreachable) {
            return "job advance route unreachable";
        }
        // A travel give-up cooldown is deliberately transient and cannot establish a wedge. The bot may
        // legally wait (ferry), retry, or take a different hop while the old destination-scoped cooldown
        // remains set. Live sampling showed those bots moving across maps while falsely flagged here.
        // Inert-autopilot leak (the original possibly-stuck bucket, kb_bot_inert_autopilot_recovery).
        if ("idle".equals(activityCategory(entry, bot))) {
            return "autopilot leaked off";
        }
        return null;
    }

    static String statusReport(BotEntry entry, Character bot) {
        String currentMap = currentMapName(bot);
        if (entry == null || bot == null) {
            return "not sure where i am rn";
        }
        // Scheduled session-end: the bot has retreated to town and is lingering before it disconnects
        // (autopilot was cleared, so without this it misreads as the inert-leak "idle rn"). Checked
        // first — it outranks every play state.
        if (entry.loggingOut) {
            return "im at " + currentMap + ", logging off for a bit";
        }
        // Long-travel errands: the bot is committed to walking to an NPC (grinding is suppressed en
        // route), so they outrank the grind/break states below.
        if (entry.jobErrandMapId != -1 && entry.jobErrandTarget != null) {
            BotStarterKitManager.JobChangeNpc instructor =
                    BotStarterKitManager.jobChangeNpcFor(entry.jobErrandTarget);
            String town = instructor != null ? instructor.townName() : ("map " + entry.jobErrandMapId);
            return bot.getMapId() == entry.jobErrandMapId
                    ? "im at " + currentMap + ", walking to the instructor to job advance"
                    : "im at " + currentMap + ", going to " + town + " to job advance";
        }
        if (entry.questErrandMapId != -1) {
            return bot.getMapId() == entry.questErrandMapId
                    ? "im at " + currentMap + ", talking to a quest npc"
                    : "im at " + currentMap + ", heading out for a quest";
        }
        if (entry.gachaErrandMapId != -1) {
            return bot.getMapId() == entry.gachaErrandMapId
                    ? "im at " + currentMap + ", at the gachapon"
                    : "im at " + currentMap + ", heading to the gachapon";
        }
        if (entry.fmErrandMapId != -1) {
            return constants.game.GameConstants.isFreeMarketRoom(bot.getMapId())
                    || bot.getMapId() == BotFreeMarketManager.FM_ENTRANCE
                    ? "im at the free market, doing some shopping"
                    : "im at " + currentMap + ", heading to the free market";
        }
        // Transient sub-states sit on top of grind mode (entry.grinding stays true), so report them
        // first — otherwise a town break or level-gap idle-leech misreads as "grinding here".
        if (System.currentTimeMillis() < entry.breakUntilMs) {
            return entry.chillSession
                    ? "im at " + currentMap + ", just chilling in town today, not really grinding"
                    : "im at " + currentMap + ", taking a break";
        }
        if (entry.idleLeech) {
            return "im at " + currentMap + ", idling while my party catches up";
        }
        if (!isActive(entry)) {
            String activity = nonAutopilotActivity(entry);
            String base = activity.isEmpty()
                    ? "im at " + currentMap + ", idle rn"
                    : "im at " + currentMap + ", " + activity;
            return base + lastDecisionSuffix(entry);
        }
        // Grinding on while the rest of the cohort chills/breaks — checked after the leak arm above so a
        // wedged catch-up bot still reports "idle rn" instead of claiming it's out there working.
        if (catchingUpWhileCohortRests(entry)) {
            return "im at " + currentMap + ", grinding to catch up with my group";
        }

        String destination = entry.autopilotDestinationName == null || entry.autopilotDestinationName.isBlank()
                ? mapName(entry.autopilotMapId)
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
            String where = entry.autopilotWaitAnchor != null ? " at the portal" : "";
            return objective + " at " + destination + " - at " + currentMap
                    + ", waiting" + where + " for the party to catch up";
        }
        return "im at " + currentMap + ", heading to " + destination + " to " + objective + reasonSuffix(entry);
    }

    /** Post-mortem tag for an idle bot: " (last decided 6h ago: no reachable grind spot ...)". */
    private static String lastDecisionSuffix(BotEntry entry) {
        if (entry.autopilotLastDecisionAtMs <= 0L) {
            return "";
        }
        long ms = System.currentTimeMillis() - entry.autopilotLastDecisionAtMs;
        String ago = ms < 60_000 ? (ms / 1000) + "s"
                : ms < 3_600_000 ? (ms / 60_000) + "m"
                : (ms / 3_600_000) + "h";
        return " (last decided " + ago + " ago: " + entry.autopilotLastDecisionReason + ")";
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
        return mapName(bot.getMapId());
    }

    /** Map name from String.wz by id (works for maps the bot isn't standing on); falls back to "map <id>". */
    private static String mapName(int mapId) {
        String name = MapFactory.loadPlaceName(mapId);
        return name != null && !name.isBlank() ? name : "map " + mapId;
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
        if (entry == null || !isActive(entry) || decidesAsGroupMember(entry) || entry.autopilotFarmItemId != 0) {
            return;
        }
        long at = System.currentTimeMillis() + UPGRADE_REDECIDE_DELAY_MS;
        if (entry.autopilotNextDecisionAtMs > at) {
            entry.autopilotNextDecisionAtMs = at;
        }
    }

    /**
     * The bot just leveled up mid-autopilot: grind value is level-relative, so the map that was
     * "best" at the old level may not be anymore (classic case: a freshly-spawned lv1 cohort glued
     * to the lv1 starter map well into the single digits). Pull the next decision forward so the
     * advisor re-looks — hysteresis (BotManager.Config) still decides whether to actually move, and
     * the planner's crowd surcharge disperses stacked bots once it does. Mirrors
     * {@link #noteGearUpgraded}. Gated to early levels (where map relevance shifts fastest) so a
     * whole cohort dinging at once doesn't redecide on every level forever; the per-bot jitter
     * de-syncs the cohort so they don't stampede onto the next map in lockstep.
     */
    static void noteLevelUp(BotEntry entry, int newLevel) {
        if (entry == null || !isActive(entry) || entry.autopilotFarmItemId != 0) return;
        if (newLevel > 15) return; // ponytail: early-only; past 15 the regular interval is fine
        if (decidesAsGroupMember(entry)) {
            entry.autopilotNextDecisionAtMs = 0L; // leader-driven redecide picks it up next tick
            return;
        }
        long at = System.currentTimeMillis() + UPGRADE_REDECIDE_DELAY_MS
                + ThreadLocalRandom.current().nextLong(20_000L); // de-sync cohort level-ups
        if (entry.autopilotNextDecisionAtMs > at) {
            entry.autopilotNextDecisionAtMs = at;
        }
    }

    /**
     * The single place the "who decides my grind destination" split is expressed: true when a cohort
     * LEADER decides for this bot (group autopilot), false when the bot runs its own decision pass.
     * Call sites read this instead of {@code autopilotParty} directly so the cohort model can grow a
     * new policy (e.g. player-led, Stage 5) without re-touching every decision branch. Today a group
     * member is exactly an {@code autopilotParty} bot; a soloist is its own (degenerate) cohort and
     * decides for itself — note that solo state never drifts (one bot = inherent SSOT), so it is
     * deliberately NOT folded into the party SSOT.
     */
    static boolean decidesAsGroupMember(BotEntry entry) {
        return entry.autopilotParty;
    }

    private static void maybeRedecide(BotEntry entry, Character bot) {
        if (entry.operatorCmd != null) {
            return; // an operator command pins the destination; never let the advisor re-pick under it
        }
        long now = System.currentTimeMillis();
        if (now < entry.autopilotNextDecisionAtMs || entry.autopilotDecisionInFlight) {
            return;
        }
        entry.autopilotNextDecisionAtMs = nextDecisionAt();
        redecide(entry, bot);
    }

    /** Decision-dispatch seam: a group member's destination is chosen by its cohort leader (shared
     *  plan via {@link #redecideParty}); a self-deciding bot runs its own advisor pass (grind /
     *  farm-item + ferry teaser, via {@link #redecideSolo}). TODO(bot-player-led-party): the
     *  player-led policy plugs in
     *  here — one branch — instead of being smeared across the decision call sites. */
    private static void redecide(BotEntry entry, Character bot) {
        if (decidesAsGroupMember(entry)) {
            redecideParty(entry, bot);
        } else {
            redecideSolo(entry, bot);
        }
    }

    /** A self-deciding bot's own decision pass: full solo policy (gear-first advisor or farm-item
     *  override, plus the owner-gated ferry teaser). Unchanged from the pre-seam inline solo branch. */
    private static void redecideSolo(BotEntry entry, Character bot) {
        entry.autopilotDecisionInFlight = true;
        int epoch = entry.activityEpoch;
        decisionRunner.run(() -> decide(entry, bot), result -> {
            entry.autopilotDecisionInFlight = false;
            Decision decision = (Decision) result;
            if (entry.activityEpoch != epoch || !isActive(entry) || decidesAsGroupMember(entry)) {
                return;
            }
            maybeTeaseFerry(entry, decision);
            Recommendation rec = decision != null ? decision.rec() : null;
            if (rec == null) {
                recordDecision(entry, decision == null
                        ? "decide failed (see warn log)"
                        : "no reachable grind spot from " + currentMapName(bot) + " (re-decide)");
                escapeTrappedRegion(entry, bot); // if walled into a danger region, scroll out / beg
                return;
            }
            if (rec.pick().mapId() == entry.autopilotMapId) {
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
            // Visible, not swallowed: distinguishes a real failure here from a legitimate null rec
            // (no reachable worthwhile spot), which returns a non-null Decision below. Each such throw
            // fails a recovery (null decide -> no plan), so the bot stays inert-leaked -> the whole
            // grind:idle ratio degrades over hours (see kb_bot_inert_autopilot_recovery). Log the map so
            // the failing site is narrowable even when the JVM strips the stack (fast-throw NPEs come
            // back stackless; relaunch with -XX:-OmitStackTraceInFastThrow for the exact line).
            log.warn("bot decide failed for {} (map {}){}", bot != null ? bot.getName() : "?",
                    bot != null ? bot.getMapId() : -1,
                    e.getStackTrace().length == 0 ? " [stackless fast-throw]" : "", e);
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
    // the leader holds when somebody falls behind. The straggler thresholds (portal hops and
    // same-map px, with hysteresis) live in BotManager.Config so they're tunable at runtime.
    private static final long STRAGGLER_CHECK_INTERVAL_MS = 3_000L;

    // Test seams: member enumeration touches the live registry/party; hop distance the world graph.
    @FunctionalInterface
    interface PartyMembersLookup {
        List<BotEntry> members(BotEntry entry);
    }

    static PartyMembersLookup partyMembers = BotAutopilotManager::defaultPartyMembers;

    // Party-plan SSOT (see PartyAutopilotState): one shared plan per game party, keyed by party id.
    // The leader writes it in applyPartyPlan; every member refreshes its per-tick destination cache
    // from it in tick() (syncFromPartyState). This is what makes follower drift impossible — there
    // is one destination, not one copy per member. Orphaned entries (party disbanded) are tiny and
    // pruned when the leader clears its autopilot (clear()); a stale entry can never be MIS-read
    // because lookup is keyed by the member's CURRENT party id.
    private static final Map<Integer, PartyAutopilotState> partyStates = new ConcurrentHashMap<>();

    /** Registry key for a cohort's shared plan: the game party id when there is one, else a synthetic
     *  key off the shared owner (owner's own bots can grind as a cohort with no formal party — see
     *  defaultPartyMembers). Null when no cohort identity exists (solo / unmocked test bot), in which
     *  case there is no SSOT and the bot just uses its own per-entry destination. */
    private static Integer partyStateKey(BotEntry entry) {
        if (entry == null || entry.bot == null) {
            return null;
        }
        if (entry.bot.getParty() != null) {
            return entry.bot.getParty().getId();
        }
        Character owner = entry.owner;
        return owner != null && owner != entry.bot ? -owner.getId() : null;
    }

    /** The shared plan for this bot's current cohort, or null when soloing / no plan yet. */
    static PartyAutopilotState partyStateFor(BotEntry entry) {
        Integer key = partyStateKey(entry);
        return key == null ? null : partyStates.get(key);
    }

    /** The cohort's shared plan, creating it on first publish. Null only when the cohort has no
     *  identity to key on (then the caller leaves members on their per-entry fields). */
    private static PartyAutopilotState partyStateOrCreate(BotEntry leader) {
        Integer key = partyStateKey(leader);
        return key == null ? null : partyStates.computeIfAbsent(key, k -> new PartyAutopilotState());
    }

    /** Refresh a FOLLOWER's cached destination from the party SSOT, so it can never travel to a
     *  stale per-bot pick. The leader is the writer, so it is skipped; soloists have no state. */
    private static void syncFromPartyState(BotEntry entry) {
        if (!entry.autopilotParty) {
            return;
        }
        PartyAutopilotState ps = partyStateFor(entry);
        if (ps == null || ps.mapId == -1 || entry.bot.getId() == ps.leaderCharId) {
            return;
        }
        entry.autopilotMapId = ps.mapId;
        entry.autopilotDestinationName = ps.destinationName;
        String objective = ps.objectiveByCharId.get(entry.bot.getId());
        if (objective != null) {
            entry.autopilotObjectiveSummary = objective;
        }
    }

    @FunctionalInterface
    interface HopDistance {
        int hops(int fromMapId, int toMapId);
    }

    static HopDistance hopDistance = BotAutopilotManager::walkingHops;

    /** Seam over the reactive low-supply predicate so the pre-travel gate stays WZ/DB-free in
     *  tests. Default reads the same HP/MP pot counts vs {@code POT_STOP} the grind-stop hook
     *  uses (BotPotionManager) — reuse, not duplication. */
    @FunctionalInterface
    interface SupplyLevel {
        boolean lowOnSupplies(Character bot);
    }

    static SupplyLevel supplyLevel = BotAutopilotManager::defaultLowOnSupplies;

    /** True when HP or MP pots are below {@code POT_STOP} — the same threshold the reactive
     *  resupply errand triggers on (BotPotionManager.tickPotionCheck). Exception-safe: partial
     *  character mocks break countPotions, so a failure reads as "not low" and lets travel run. */
    private static boolean defaultLowOnSupplies(Character bot) {
        try {
            int[] pots = BotPotionManager.countPotions(bot);
            return pots[0] < BotManager.cfg.POT_STOP || pots[1] < BotManager.cfg.POT_STOP;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Seam over the bag-full predicate so the pre-travel gate can divert to town to unload junk
     *  without coupling to inventory/WZ in tests. Default reuses the same cramped-tab-with-sellable
     *  check the reactive grind-stop trigger uses (BotShopManager.shouldAutoSellTrash) — reuse, not
     *  duplication. A full bag only routes to town when selling could actually free slots. */
    @FunctionalInterface
    interface BagFull {
        boolean bagFull(BotEntry entry, Character bot);
    }

    static BagFull bagFull = BotShopManager::shouldAutoSellTrash;

    /** Seam over the "is this item a real equip?" check installPlan uses to record a wanted-gear
     *  drop, so unit tests need neither ItemInformationProvider nor a DB pool (its &lt;clinit&gt;
     *  loads card data over a JDBC connection). Default delegates to the same SSOT every other gear
     *  path uses; tests swap it for a fixed predicate. */
    static java.util.function.IntPredicate equipStatsExist =
            itemId -> server.ItemInformationProvider.getInstance().getEquipStats(itemId) != null;

    /**
     * Pure hysteresis decision for party level-gap idle-leech. A member that has pulled at least
     * {@code trigger} levels above the lowest same-map cohort member stops dealing damage; once
     * leeching it resumes only after the gap closes to {@code release} or below. The lowest member
     * (gap 0) is never a leecher, so it stays the damage-dealer and keeps full exp share.
     */
    static boolean decideIdleLeech(boolean currentlyLeeching, int myLevel, int minPartyLevel,
                                   int trigger, int release) {
        int gap = myLevel - minPartyLevel;
        if (currentlyLeeching) {
            return gap > release;        // stay idling until the gap closes to <= release
        }
        return gap >= trigger;           // start idling once the gap reaches the trigger
    }

    /**
     * Planning-time view of {@link #decideIdleLeech}: which members will actually deal damage on the
     * shared grind map, so only they drive the party's map pick (an idle-leecher must not pull the
     * cohort onto a map tuned to its higher level that the lower members can barely hit). Mirrors the
     * live gate — current leech state for hysteresis, cohort-wide minimum level (the planner assumes
     * they converge on one map). Returns {@code null} ("everyone grinds") when leeching is off, the
     * party is too small, or nobody would leech, so the planner keeps its all-members path.
     */
    static boolean[] grinderMask(List<BotEntry> members) {
        if (!BotManager.cfg.PARTY_LEECH_ENABLED || members.size() < 2) {
            return null;
        }
        int minLevel = Integer.MAX_VALUE;
        for (BotEntry m : members) {
            if (m.bot != null) {
                minLevel = Math.min(minLevel, m.bot.getLevel());
            }
        }
        if (minLevel == Integer.MAX_VALUE) {
            return null;
        }
        boolean[] grind = new boolean[members.size()];
        boolean anyLeech = false;
        for (int i = 0; i < members.size(); i++) {
            BotEntry m = members.get(i);
            boolean leech = m.bot != null && decideIdleLeech(m.idleLeech, m.bot.getLevel(), minLevel,
                    BotManager.cfg.PARTY_LEECH_GAP_TRIGGER, BotManager.cfg.PARTY_LEECH_GAP_RELEASE);
            grind[i] = !leech;
            anyLeech |= leech;
        }
        return anyLeech ? grind : null;
    }

    /**
     * Recompute and store this member's idle-leech state from the live cohort levels on its current
     * map. Returns true when the bot should idle (do no damage) this tick so the lower bots become
     * the damage-dealers and get full exp. Only cohort autopilot bots with 2+ same-map members
     * participate; announces on transition (ASCII, jittered by the random reply pick).
     */
    static boolean updateIdleLeech(BotEntry entry, Character bot) {
        if (!BotManager.cfg.PARTY_LEECH_ENABLED || !isActive(entry) || bot == null) {
            entry.idleLeech = false;
            entry.leechIdleAnchor = null;
            return false;
        }
        int mapId = bot.getMapId();
        int minLevel = Integer.MAX_VALUE;
        int sameMap = 0;
        for (BotEntry m : defaultPartyMembers(entry)) {
            if (m.bot == null || m.bot.getMapId() != mapId) {
                continue;
            }
            sameMap++;
            minLevel = Math.min(minLevel, m.bot.getLevel());
        }
        if (sameMap < 2 || minLevel == Integer.MAX_VALUE) {
            entry.idleLeech = false;     // no cohort to wait for
            entry.leechIdleAnchor = null;
            return false;
        }
        boolean was = entry.idleLeech;
        boolean now = decideIdleLeech(was, bot.getLevel(), minLevel,
                BotManager.cfg.PARTY_LEECH_GAP_TRIGGER, BotManager.cfg.PARTY_LEECH_GAP_RELEASE);
        if (now != was) {
            entry.idleLeech = now;
            if (!now) {
                entry.leechIdleAnchor = null;   // resumed grinding: drop the held idle spot
            }
            reply.accept(entry, now
                    ? BotManager.randomReply(LEECH_ENTER_MSGS)
                    : BotManager.randomReply(LEECH_EXIT_MSGS));
        }
        return now;
    }

    private static final List<String> HP_REST_ENTER_MSGS = List.of(
            "low hp and no meso for pots, gonna rest a sec",
            "outta pots and broke, catching my breath",
            "resting up til my hp comes back");
    private static final List<String> HP_REST_EXIT_MSGS = List.of(
            "ok hp's back, grinding again",
            "rested enough, back to it",
            "good to go again");

    /**
     * Low-HP rest state (self-preservation for the strict no-pot spend tier): a broke bot that's out of
     * HP pots stops grinding and parks on a safe spot to passive-regen instead of chipping itself to
     * death. Only engages when neither autopot (no pots) nor an affordable resupply (broke) can help,
     * so a stocked/solvent bot never rests. Hysteresis on {@link BotManager.Cfg#HP_REST_ENTER}/EXIT.
     */
    static boolean updateHpRest(BotEntry entry, Character bot) {
        if (!isActive(entry) || bot == null) {
            entry.hpResting = false;
            entry.hpRestAnchor = null;
            return false;
        }
        int maxHp = bot.getCurrentMaxHp();
        // Cheap gate FIRST (this runs every grind tick): only a low-HP bot can be entering/continuing
        // rest, so a healthy bot returns here WITHOUT the USE-inventory pot scan below. Hysteresis:
        // compare against EXIT while resting, ENTER otherwise.
        double ratio = maxHp > 0 ? (double) bot.getHp() / maxHp : 1.0;
        double threshold = entry.hpResting ? BotManager.cfg.HP_REST_EXIT : BotManager.cfg.HP_REST_ENTER;
        if (ratio >= threshold) {
            return clearHpRest(entry); // recovered / healthy: stop resting if we were, no inventory scan
        }
        // HP is low enough to (keep) resting — only now do the heavier eligibility check. Rest only when
        // neither autopot (no HP pots) nor an affordable restock (broke) can help; a stocked/solvent bot
        // uses the normal HP path instead.
        boolean now = BotPotionManager.countPotions(bot)[0] < BotManager.cfg.POT_STOP
                && !BotShopManager.canAffordPotResupply(bot);
        if (now != entry.hpResting) {
            entry.hpResting = now;
            if (!now) {
                entry.hpRestAnchor = null;
            }
            reply.accept(entry, now
                    ? BotManager.randomReply(HP_REST_ENTER_MSGS)
                    : BotManager.randomReply(HP_REST_EXIT_MSGS));
        }
        return now;
    }

    /** Stop resting (if we were) and report the transition; returns false (not resting). */
    private static boolean clearHpRest(BotEntry entry) {
        if (entry.hpResting) {
            entry.hpResting = false;
            entry.hpRestAnchor = null;
            reply.accept(entry, BotManager.randomReply(HP_REST_EXIT_MSGS));
        }
        return false;
    }

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
        List<Integer> route = BotWorldGraph.route(fromMapId, toMapId,
                BotManager.cfg.STRAGGLER_WAIT_HOPS + 1,
                new BotWorldGraph.RouteOptions(false, 0, false));
        return route == null ? Integer.MAX_VALUE : route.size();
    }

    /**
     * A member is AHEAD of the leader — at or closer to the grind destination — when its hop
     * distance to the destination is strictly less than the leader's. Such a member isn't a
     * straggler: it has already advanced where the group is headed, so the leader must not hold
     * for it. This guards the leader-relative {@code hops(member, leader)} check, which routes
     * BACKWARD from a member sitting at/past the destination and exceeds the hop cap -> MAX_VALUE,
     * falsely flagging it (pathlog-Bowgurl 2026-06-14T11:25: a member parked at destMap while the
     * leader was still in town after a resupply). Conservative under the hop cap: when both sides
     * are farther than the cap they both read MAX, {@code MAX < MAX} is false, and the leader still
     * waits — fail-safe, never masking a genuine straggler. The rare case of a member ahead AND
     * beyond the cap reads as not-ahead (leader waits) but is non-regressive and transient.
     * {@code leaderHopsToDest} is passed precomputed so the caller hoists it out of its member loop.
     */
    static boolean aheadOfLeaderTowardDest(int memberMapId, int destMapId, int leaderHopsToDest) {
        return hopDistance.hops(memberMapId, destMapId) < leaderHopsToDest;
    }

    /**
     * True while this member is on a personal errand that should not hold or lead the party cohort.
     * The bot still keeps the shared party grind destination, but owns its travel until the errand is
     * done; remaining cohort members continue toward the party plan instead of waiting/chasing it.
     */
    private static boolean detachedFromPartyCohesion(BotEntry entry) {
        return entry.autopilotErrandMapId != -1 || entry.restErrand
                || entry.questErrandMapId != -1 || entry.gachaErrandMapId != -1
                || entry.jobErrandMapId != -1;
    }

    /**
     * The cohesion leader: the first party member NOT off on a personal errand. A detouring bot
     * handles its own side trip independently, so followers must anchor on the first member still
     * heading to the grind map, not on the absent leader. Null when every member is detached.
     */
    static BotEntry effectiveCohesionLeader(List<BotEntry> members) {
        for (BotEntry m : members) {
            if (!detachedFromPartyCohesion(m)) {
                return m;
            }
        }
        return null;
    }

    /** Count of members eligible for cohesion (not off on a personal errand). */
    private static int cohesionMemberCount(List<BotEntry> members) {
        int count = 0;
        for (BotEntry m : members) {
            if (!detachedFromPartyCohesion(m)) {
                count++;
            }
        }
        return count;
    }

    /**
     * One in-transit tick of party cohesion. Returns the tick() result to use, or null when
     * this bot is the leader with the group in tow — it travels normally this tick.
     */
    private static Boolean tickPartyCohesion(BotEntry entry, Character bot) {
        List<BotEntry> members = partyMembers.members(entry);
        // The cohesion leader is the first member NOT off on a resupply errand: a resupplying
        // bot independently handles its own town trip, so followers must not chase it. With
        // fewer than two non-resupplying members there is no group to keep together — dissolve
        // and let each remaining member travel itself.
        BotEntry leader = effectiveCohesionLeader(members);
        if (leader == null || cohesionMemberCount(members) < 2) {
            exitTransitFollow(entry); // group dissolved — travel on alone
            clearWaitAnchor(entry);  // ...and drop any portal hold, restoring grind/travel
            return null;
        }
        if (leader == entry) {
            exitTransitFollow(entry); // just promoted mid-transit: stop following, lead
            if (waitingForStragglers(entry, bot, members)) {
                anchorWaitAtNextHopPortal(entry, bot); // loiter at the portal, not the whole map
                return false; // hold this map (grind/loiter flow runs) until the group closes up
            }
            clearWaitAnchor(entry); // caught up — resume travel and enter the portal together
            return null;
        }
        if (!entry.autopilotCohortMember) {
            // Not co-located with the leader at embark: travel independently to the shared destination
            // rather than chasing the leader's transit position. Rejoins at the destination map, and
            // the next embark (new group destination) re-snapshots it into the cohort.
            exitTransitFollow(entry);
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
     * Leader is holding for stragglers IN TRANSIT: stand at the next-hop portal (combat keeps
     * opportunity-firing there via BotManager's loiter dispatch) instead of grind-wandering off,
     * so the group reassembles at the portal and hops together once everyone closes up. When the
     * next hop isn't a plain walkable portal (consumable/taxi/ferry leg, or no route/portal — and
     * on the destination map there is none), there's nothing to stand next to: fall back to the
     * plain "hold this map and grind" wait. grinding is turned off while anchored so the grind
     * seek can't pull the leader away and the same-map teleport-recovery guard stays disengaged.
     */
    private static void anchorWaitAtNextHopPortal(BotEntry entry, Character bot) {
        Point portalPos = BotTravelManager.nextHopPortalPosition(entry, bot, entry.autopilotMapId, MAX_TRAVEL_HOPS);
        if (portalPos == null) {
            clearWaitAnchor(entry); // arrived, or hop is non-walkable — plain hold + grind
            return;
        }
        // WZ portal positions sit at the sprite anchor, usually ABOVE the floor. Standing-wait must
        // be a real foothold: loiterAtAnchor only settles (idleOnGround) within 8px of the anchor on
        // BOTH axes and while grounded, so an above-floor anchor Y means a bot on the platform never
        // settles, and a bot that reaches the portal's airborne Y is stuck inAir at its own target —
        // frozen mid-air, passively loitering while mobs hit it. Snap to the ground below the portal.
        MapleMap map = bot.getMap();
        Point ground = map == null ? null
                : BotPhysicsEngine.findGroundPoint(map, new Point(portalPos.x, portalPos.y - 1));
        entry.autopilotWaitAnchor = ground != null ? ground : portalPos;
        entry.autopilotWaitAnchorMapId = bot.getMapId();
        entry.grinding = false;
    }

    /** Release the portal-anchored hold and restore the grind/travel flow. */
    private static void clearWaitAnchor(BotEntry entry) {
        if (entry.autopilotWaitAnchor != null) {
            entry.autopilotWaitAnchor = null;
            entry.autopilotWaitAnchorMapId = -1;
            entry.grinding = true; // travel resumes from grinding=true (BotManager.issueGrind baseline)
        }
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

    /** Leader-side hold: true while any member is more than {@code STRAGGLER_WAIT_HOPS}
     *  portal hops behind, OR on the same map but farther than the same-map px band. The
     *  same-map band has hysteresis: a fresh wait triggers past {@code SAME_MAP_STRAGGLER_PX}
     *  but only releases once everyone is back within the tighter {@code RESUME_PX}, so the
     *  leader doesn't stop-start flap at the boundary. Rate-limited; the cached verdict rides
     *  between checks. */
    static boolean waitingForStragglers(BotEntry entry, Character bot, List<BotEntry> members) {
        long now = System.currentTimeMillis();
        if (now < entry.autopilotNextStragglerCheckAtMs) {
            return entry.autopilotWaitingForStragglers;
        }
        entry.autopilotNextStragglerCheckAtMs = now + STRAGGLER_CHECK_INTERVAL_MS;
        // Hysteresis: while already holding, members must close to the tighter resume band
        // before the leader releases; otherwise a member hovering near the edge would make the
        // leader flap. Read the OLD aggregate verdict before overwriting it.
        boolean wasWaiting = entry.autopilotWaitingForStragglers;
        int sameMapBand = wasWaiting
                ? BotManager.cfg.SAME_MAP_STRAGGLER_RESUME_PX
                : BotManager.cfg.SAME_MAP_STRAGGLER_PX;
        Point leaderPos = bot.getPosition();
        // Hoisted out of the loop (member-independent): how far the leader itself is from the grind
        // destination, used to tell members that are AHEAD (already at/closer to dest) from ones
        // that are BEHIND. Cohesion only runs with no errand active (tick() line ~375), so the
        // destination is always autopilotMapId here.
        int destMapId = entry.autopilotMapId;
        int leaderHopsToDest = hopDistance.hops(bot.getMapId(), destMapId);
        boolean waiting = false;
        String reason = null; // captured for the pathlog: which member tripped the hold, and how
        for (BotEntry member : members) {
            if (member == entry || member.bot == null || member.bot.getMap() == null
                    || detachedFromPartyCohesion(member) || !member.autopilotCohortMember) {
                continue; // on a personal errand OR not in the embark cohort -> never wait on it
            }
            int memberHops = hopDistance.hops(member.bot.getMapId(), bot.getMapId());
            if (memberHops > BotManager.cfg.STRAGGLER_WAIT_HOPS
                    && !aheadOfLeaderTowardDest(member.bot.getMapId(), destMapId, leaderHopsToDest)) {
                waiting = true;
                reason = member.bot.getName() + " off-map (map=" + member.bot.getMapId()
                        + " hops=" + memberHops + " > " + BotManager.cfg.STRAGGLER_WAIT_HOPS + ")";
                break;
            }
            if (member.bot.getMapId() == bot.getMapId() && leaderPos != null) {
                Point memberPos = member.bot.getPosition();
                if (memberPos != null) {
                    // A member is "present" if it's near the group cluster by EITHER metric:
                    //   body distance  -- it's bunched at the leader/portal landing, OR
                    //   slot distance  -- it's settled into its spread formation slot (leaderX + offset).
                    // Take the min. Each pure metric has a blind spot that produced a real stuck-at-portal
                    // bug: body-only waits forever when members sit at wide formation slots; slot-only waits
                    // forever when members bunch at the portal after a hop (their slots are spread +/-180px
                    // so an outer-slot member standing by the leader reads ~385px from its slot). A genuine
                    // straggler is far by BOTH (it hasn't arrived), so min never masks one. See
                    // pathlog-Bowgurl 2026-06-14T08:09 (6 members bunched, body gaps <=205, slot-only stuck).
                    int bodyDistance = Math.abs(memberPos.x - leaderPos.x) + Math.abs(memberPos.y - leaderPos.y);
                    int expectedX = leaderPos.x + member.followOffsetX;
                    int slotDistance = Math.abs(memberPos.x - expectedX) + Math.abs(memberPos.y - leaderPos.y);
                    if (Math.min(bodyDistance, slotDistance) > sameMapBand) {
                        waiting = true;
                        reason = member.bot.getName() + " far on-map (body=" + bodyDistance
                                + " slot=" + slotDistance + " > band=" + sameMapBand + ")";
                        break;
                    }
                }
            }
        }
        // The wait-for-stragglers announcement is intentionally silent: the hold is frequent
        // during transit and the chatter was noise. The verdict still shows in the pathlog.
        entry.autopilotWaitingForStragglers = waiting;
        entry.autopilotStragglerReason = reason; // null when this recompute decided NOT to wait
        return waiting;
    }


    private static PartyPlan decideParty(List<BotEntry> members) {
        try {
            return partyDecider.decide(members);
        } catch (RuntimeException e) {
            // Was silently swallowed -> a party-decide NPE turned "grind together" into a no-op and the
            // whole cohort sat idle with no visible reason. Log it like decide() does (same fast-throw
            // caveat: relaunch with -XX:-OmitStackTraceInFastThrow for the exact line). The party leader
            // names the cohort; see kb_bot_inert_autopilot_recovery.
            BotEntry lead = members != null && !members.isEmpty() ? members.get(0) : null;
            Character leadBot = lead != null ? lead.bot : null;
            log.warn("party decide failed for {} (leader map {}, {} members){}",
                    leadBot != null ? leadBot.getName() : "?",
                    leadBot != null ? leadBot.getMapId() : -1,
                    members != null ? members.size() : 0,
                    e.getStackTrace().length == 0 ? " [stackless fast-throw]" : "", e);
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
        BotEntry leader = members.get(0);
        int leaderMapId = leader.bot.getMapId(); // embark anchor: who's co-located heads out together
        // Publish the shared plan to the SSOT first; the per-member fields below are the derived cache
        // that syncFromPartyState refreshes each tick. The leader is the authoritative writer. ps is
        // null only for an unkeyable cohort (no party + no shared owner) — then members just run off
        // their own per-entry fields, same as before the SSOT existed.
        PartyAutopilotState ps = partyStateOrCreate(leader);
        if (ps != null) {
            ps.mapId = plan.mapId();
            ps.destinationName = destination;
            ps.leaderCharId = leader.bot.getId();
            ps.objectiveByCharId.clear();
        }
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
            if (ps != null) {
                ps.objectiveByCharId.put(member.bot.getId(), member.autopilotObjectiveSummary);
            }
            member.autopilotParty = true;
            // Only members on the leader's map at embark join the travel cohort (formation-follow +
            // leader waits for them). The rest travel independently to the shared destination.
            member.autopilotCohortMember = member.bot.getMapId() == leaderMapId;
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
                return; // group's map unchanged; followers track it via the SSOT sync each tick
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
        // Scroll<->farm coupling (item 08): when this plan is actively steering toward a better-base
        // equip drop, record it so the scroll planner holds scrolls for that slot instead of burning
        // them on the inferior base. Only equips (not scroll drops); cleared otherwise.
        BotGrindPlanner.GearProspect wg = rec.wantedGear();
        if (rec.gearFocused() && wg != null && equipStatsExist.test(wg.itemId())) {
            entry.wantedGearItemId = wg.itemId();
            entry.wantedGearChancePerKill = wg.chancePerKill();
        } else {
            entry.wantedGearItemId = 0;
            entry.wantedGearChancePerKill = 0.0;
        }
        // Already on the picked map: announcePlan's "this map works" covers it — a separate
        // "arrived" line right after would be redundant chatter.
        entry.autopilotArrivalAnnounced = pick.mapId() == fromMapId;
        recordDecision(entry, "grind " + entry.autopilotDestinationName + " ("
                + entry.autopilotObjectiveSummary + ")");
    }

    /** Stamp the last-decision post-mortem ({@link BotEntry#autopilotLastDecisionReason}) for @botstatus. */
    private static void recordDecision(BotEntry entry, String reason) {
        entry.autopilotLastDecisionAtMs = System.currentTimeMillis();
        entry.autopilotLastDecisionReason = reason;
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
