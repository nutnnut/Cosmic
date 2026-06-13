package server.bots;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.ItemInformationProvider;
import server.bots.BotAutopilotManager.PartyInputs;
import server.bots.BotGrindPlanner.GearProspect;
import server.bots.BotGrindPlanner.MobCandidate;
import server.bots.BotGrindPlanner.PartyScoring;
import server.bots.BotGrindPlanner.Recommendation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntToDoubleFunction;

/**
 * "autopilot debug" / "party debug" / "autopilot why": dumps the COMPLETE party-autopilot grind
 * decision to a report file so the owner can see, at a glance, why a map was chosen — and so a
 * mis-valuation (e.g. a high-att empty-slot weapon prospect losing to a stat-scroll cape for another
 * member) is diagnosable without a debugger.
 *
 * <p>Runs OFF-THREAD on {@link BotGrindAdvisor#DECIDE_POOL} (the decision iterates every known mob —
 * seconds of work). It reuses the EXACT decision path: {@link BotAutopilotManager#partyInputs}
 * assembles the same reachable set / travel weights / per-member candidate pools the live decider
 * uses, and {@link BotGrindPlanner#scorePartyBest} returns the same intermediate scoring the live
 * {@code planPartyBest} takes its plan from — the report renders that intermediate, it does not
 * recompute it. Solo (no party) falls back to a single-member party so the command always works.
 */
final class BotAutopilotDebug {

    private static final int CANDIDATES_SHOWN = 15;
    private static final short WEAPON_SLOT = -11;

    private BotAutopilotDebug() {}

    /** Owner-scoped chat dispatch entry: schedule the dump on the decide pool, then chat the path. */
    static void exportPartyDecision(BotEntry entry, Character bot) {
        BotGrindAdvisor.DECIDE_POOL.execute(() -> exportBlocking(entry, bot));
    }

    private static void exportBlocking(BotEntry entry, Character bot) {
        String report;
        try {
            report = buildReport(entry, bot);
        } catch (RuntimeException e) {
            BotManager.getInstance().botReply(entry, "autopilot debug blew up, check the log");
            return;
        }
        String path = BotGrindAdvisor.writeReport(bot, "ap-debug-", report);
        BotManager.getInstance().botReply(entry,
                path != null ? "wrote the autopilot decision to " + path
                        : "couldn't write the autopilot report");
    }

    /** Resolve the party the SAME way a real decision does; degrade to a single-member party when
     *  no active party-autopilot group exists (the usual case when someone asks "why" out of band),
     *  so the command always produces a report. */
    private static List<BotEntry> resolveParty(BotEntry entry) {
        List<BotEntry> members = new ArrayList<>(BotAutopilotManager.partyMembers.members(entry));
        if (members.isEmpty() || !members.contains(entry)) {
            members = new ArrayList<>();
            members.add(entry);
        }
        return members;
    }

    static String buildReport(BotEntry entry, Character bot) {
        List<BotEntry> members = resolveParty(entry);
        PartyInputs in = BotAutopilotManager.partyInputs(members);
        // Same call the live decider makes, but keep the full intermediate for the dump.
        PartyScoring scoring = BotGrindPlanner.scorePartyBest(
                in.perMember(), in.weights(), ThreadLocalRandom.current());
        return renderReport(ItemInformationProvider.getInstance(), bot, members, in, scoring);
    }

    /** Pure rendering of an already-assembled decision — the unit-test seam (no WZ / world graph;
     *  pass a mock {@code ii}). {@code scoring} may be null when nothing the party can all reach
     *  scored above zero. */
    static String renderReport(ItemInformationProvider ii, Character bot, List<BotEntry> members,
                               PartyInputs in, PartyScoring scoring) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AUTOPILOT (PARTY) DECISION DEBUG ===\n");
        sb.append(String.format("requested by %s, party of %d%s%n%n",
                safeName(bot), members.size(), members.size() == 1 ? " (solo -> single-member party)" : ""));

        appendComposition(sb, ii, members);
        appendReachability(sb, in, members);
        appendCandidates(sb, in, scoring, members);
        appendPartySum(sb, scoring, members);
        appendDecision(sb, scoring, members);
        return sb.toString();
    }

    // a. PARTY COMPOSITION ------------------------------------------------------------------------

    private static void appendComposition(StringBuilder sb, ItemInformationProvider ii, List<BotEntry> members) {
        sb.append("--- a. PARTY COMPOSITION ---\n");
        for (BotEntry m : members) {
            Character c = m.bot;
            sb.append(String.format("%s  job=%s  lv%d  STR=%d DEX=%d INT=%d LUK=%d%n",
                    safeName(c), jobName(c), c.getLevel(),
                    c.getTotalStr(), c.getTotalDex(), c.getTotalInt(), c.getTotalLuk()));
            sb.append(String.format("    map: %d %s%n", c.getMapId(), mapNameOf(c)));
            Equip weapon = wornInSlot(c, ii, WEAPON_SLOT);
            if (weapon == null) {
                sb.append("    weapon: (none)\n");
            } else {
                sb.append(String.format(
                        "    weapon: %s  att=%d matt=%d slots=%d  offense=%.1f potential=%.1f%n",
                        equipName(ii, weapon.getItemId()), weapon.getWatk(), weapon.getMatk(),
                        weapon.getUpgradeSlots(),
                        BotScrollManager.offenseValue(c, weapon),
                        potentialOrOffense(c, ii, weapon)));
            }
            appendCheapSlot(sb, c, ii, (short) -1, "hat");
            appendCheapSlot(sb, c, ii, (short) -9, "gloves");
            appendCheapSlot(sb, c, ii, (short) -5, "top/overall");
            sb.append('\n');
        }
    }

    private static void appendCheapSlot(StringBuilder sb, Character c, ItemInformationProvider ii,
                                        short slot, String label) {
        Equip eq = wornInSlot(c, ii, slot);
        if (eq != null) {
            sb.append(String.format("      %-12s %s  slots=%d  offense=%.1f%n",
                    label, equipName(ii, eq.getItemId()), eq.getUpgradeSlots(),
                    BotScrollManager.offenseValue(c, eq)));
        }
    }

    // b. REACHABILITY -----------------------------------------------------------------------------

    private static void appendReachability(StringBuilder sb, PartyInputs in, List<BotEntry> members) {
        sb.append("--- b. REACHABILITY ---\n");
        sb.append(String.format("common reachable maps (walkable for ALL members): %d%n",
                in.allowed().size()));
        for (int m = 0; m < members.size(); m++) {
            Character c = members.get(m).bot;
            IntToDoubleFunction weight = in.weights().get(m);
            sb.append(String.format("  %s travelWeight(currentMap=%d)=%.3f%n",
                    safeName(c), c.getMapId(), weight.applyAsDouble(c.getMapId())));
        }
        sb.append('\n');
    }

    // c. PER-MEMBER CANDIDATES --------------------------------------------------------------------

    private static void appendCandidates(StringBuilder sb, PartyInputs in, PartyScoring scoring,
                                         List<BotEntry> members) {
        sb.append("--- c. PER-MEMBER CANDIDATES (competition-adjusted; top ").append(CANDIDATES_SHOWN)
                .append(" by partyScore) ---\n");
        for (int m = 0; m < members.size(); m++) {
            Character c = members.get(m).bot;
            sb.append(String.format("# %s%n", safeName(c)));
            List<MobCandidate> adjusted = scoring != null && m < scoring.adjusted().size()
                    ? scoring.adjusted().get(m) : List.of();
            double[] scores = scoring != null && m < scoring.memberScores().size()
                    ? scoring.memberScores().get(m) : new double[0];
            if (adjusted.isEmpty()) {
                sb.append("  (no reachable candidates)\n\n");
                continue;
            }
            // Sort candidate indices by partyScore desc for readability.
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < adjusted.size(); i++) {
                order.add(i);
            }
            order.sort((a, b) -> Double.compare(score(scores, b), score(scores, a)));
            int shown = 0;
            for (int idx : order) {
                if (shown++ >= CANDIDATES_SHOWN) {
                    break;
                }
                MobCandidate cand = adjusted.get(idx);
                double kph = BotGrindPlanner.killsPerHour(cand);
                sb.append(String.format(
                        "  %-22s map=%d  spawns(shared)=%d  exp/hr=%,.0f  partyScore=%.4f%n",
                        truncate(cand.mapName(), 22), cand.mapId(), cand.spawnPoints(),
                        cand.exp() * kph, score(scores, idx)));
                for (GearProspect g : cand.gearDrops()) {
                    sb.append(String.format(
                            "      gear: %-26s chance/kill=%.5f scoreGain=%.1f dps%%=%.3f desirability=%.4f%n",
                            truncate(g.itemName(), 26), g.chancePerKill(), g.scoreGain(),
                            g.dpsGainFraction(), BotGrindPlanner.desirability(g, kph)));
                }
            }
            sb.append('\n');
        }
    }

    // d. PARTY SUM --------------------------------------------------------------------------------

    private static void appendPartySum(StringBuilder sb, PartyScoring scoring, List<BotEntry> members) {
        sb.append("--- d. PARTY SUM (best-per-member partyScore summed per map, desc) ---\n");
        if (scoring == null) {
            sb.append("  (no scoring - nothing reachable scored above zero)\n\n");
            return;
        }
        List<Map.Entry<Integer, Double>> sorted = new ArrayList<>(scoring.scoreByMap().entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (Map.Entry<Integer, Double> e : sorted) {
            int mapId = e.getKey();
            boolean chosen = mapId == scoring.pickedMapId();
            sb.append(String.format("  map=%-12d sum=%.4f%s%n", mapId, e.getValue(),
                    chosen ? "   <== CHOSEN" : ""));
            for (int m = 0; m < members.size(); m++) {
                double bestHere = bestMemberScoreOnMap(scoring, m, mapId);
                sb.append(String.format("        %-16s best=%.4f%n",
                        safeName(members.get(m).bot), bestHere));
            }
        }
        sb.append('\n');
    }

    private static double bestMemberScoreOnMap(PartyScoring scoring, int member, int mapId) {
        List<MobCandidate> adjusted = scoring.adjusted().get(member);
        double[] scores = scoring.memberScores().get(member);
        double best = 0.0;
        for (int i = 0; i < adjusted.size(); i++) {
            if (adjusted.get(i).mapId() == mapId) {
                best = Math.max(best, score(scores, i));
            }
        }
        return best;
    }

    // e. DECISION ---------------------------------------------------------------------------------

    private static void appendDecision(StringBuilder sb, PartyScoring scoring, List<BotEntry> members) {
        sb.append("--- e. DECISION ---\n");
        if (scoring == null || scoring.plan() == null) {
            sb.append("  no plan: nothing the party can all reach scored above zero\n");
            return;
        }
        sb.append(String.format("chosen map: %d%n", scoring.plan().mapId()));
        for (int m = 0; m < members.size(); m++) {
            Recommendation rec = m < scoring.plan().perMember().size()
                    ? scoring.plan().perMember().get(m) : null;
            if (rec == null) {
                sb.append(String.format("  %-16s (tagging along - nothing worthwhile here)%n",
                        safeName(members.get(m).bot)));
                continue;
            }
            sb.append(String.format(
                    "  %-16s mob=%s  gearFocused=%s  needGear=%.2f  wantedGear=%s  exp/hr=%,.0f  score=%.4f%n",
                    safeName(members.get(m).bot), rec.pick().mobName(), rec.gearFocused(),
                    rec.needGear(),
                    rec.wantedGear() != null ? rec.wantedGear().itemName() : "(none)",
                    rec.expPerHour(), rec.score()));
        }
    }

    // helpers -------------------------------------------------------------------------------------

    private static double potentialOrOffense(Character bot, ItemInformationProvider ii, Equip eq) {
        try {
            return BotScrollManager.potentialValue(bot, ii, eq);
        } catch (RuntimeException e) {
            return BotScrollManager.offenseValue(bot, eq);
        }
    }

    private static Equip wornInSlot(Character bot, ItemInformationProvider ii, short slot) {
        for (Item it : bot.getInventory(InventoryType.EQUIPPED).list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId())) {
                Short s = BotScrollManager.primarySlot(ii, e.getItemId());
                if (s != null && s == slot) {
                    return e;
                }
            }
        }
        return null;
    }

    private static double score(double[] scores, int idx) {
        return idx >= 0 && idx < scores.length ? scores[idx] : 0.0;
    }

    private static String equipName(ItemInformationProvider ii, int itemId) {
        String name = ii.getName(itemId);
        return name == null ? ("item " + itemId) : name;
    }

    private static String mapNameOf(Character c) {
        var map = c.getMap();
        if (map != null && map.getMapName() != null && !map.getMapName().isBlank()) {
            return map.getMapName();
        }
        return "";
    }

    private static String jobName(Character c) {
        return c.getJob() == null ? "?" : c.getJob().toString();
    }

    private static String safeName(Character c) {
        return c == null || c.getName() == null ? "bot" : c.getName();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
