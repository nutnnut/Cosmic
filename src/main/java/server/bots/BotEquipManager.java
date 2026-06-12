package server.bots;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import constants.inventory.EquipSlot;
import constants.skills.Crusader;
import constants.skills.DragonKnight;
import constants.skills.Fighter;
import constants.skills.Page;
import constants.skills.Paladin;
import constants.skills.Pirate;
import constants.skills.Rogue;
import constants.skills.Spearman;
import constants.skills.WhiteKnight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.bots.combat.BotAttackDataProvider;
import server.combat.CombatFormulaProvider;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.MonsterStats;
import server.life.SpawnPoint;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;

class BotEquipManager {

    private static final Logger log = LoggerFactory.getLogger(BotEquipManager.class);
    // Shared with BotInventoryManager's invlog dump (same directory + filename idiom).
    static final java.nio.file.Path EQUIP_LOG_DIR = java.nio.file.Path.of("logs", "bot-equip");
    static final java.time.format.DateTimeFormatter EQUIP_LOG_FILE_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss");
    static final java.time.format.DateTimeFormatter EQUIP_LOG_HEADER_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final short[] RING_SLOTS = {-12, -13, -15, -16};
    /** Hard cap on Pareto-frontier size per DP step to bound worst-case runtime. */
    private static final int MAX_PARETO_STATES = 2000;
    private static final long AUTOEQUIP_THROTTLE_MS = 30_000L;
    private static final Map<Integer, Long> LAST_AUTOEQUIP_MS = new java.util.concurrent.ConcurrentHashMap<>();

    static final class EquipRecommendation {
        private final short targetSlot;
        private final Equip current;
        private final Equip candidate;

        private EquipRecommendation(short targetSlot, Equip current, Equip candidate) {
            this.targetSlot = targetSlot;
            this.current = current;
            this.candidate = candidate;
        }

        short targetSlot() {
            return targetSlot;
        }

        Equip current() {
            return current;
        }

        Equip candidate() {
            return candidate;
        }
    }

    record EquipScore(int damage, int statSum) {}
    record WeaponScoreBreakdown(int rawMax, int preCycleDamage, int cycleMs, int normalizedDamage) {}

    /**
     * Pareto-frontier DP across equipment slots. Outer loop iterates each viable weapon
     * (currently-wearable + stat-only-blocked, dominance-pruned); inner DP enumerates non-
     * weapon non-ring slot picks while pruning Pareto-dominated stat states. Each chosen
     * item must satisfy its requirements against the FINAL state (Option A: final-stat
     * model), which captures cross-slot stat chains that unlock otherwise-unreachable gear.
     *
     * Scoring (lex): damage > defense > useful-stat sum. Physical damage = max-base ×
     * expected-after-def × hit-chance × 1000 / weapon-cycle-ms. Mage damage = round(int*1.1)
     * + magic. Mob benchmark = highest-avoid mob on the bot's map.
     *
     * Rings are picked greedily after the main DP commits (4 slots over a small shared pool;
     * ring stat contribution rarely unlocks armor). Cash and {@code pendingOffer} excluded.
     * Called on mode change (follow / stop / grind).
     */
    static boolean autoEquip(Character bot, Character owner, Item pendingOffer) {
        return autoEquip(bot, owner, pendingOffer, false);
    }

    /** Returns true when the pass actually moved gear (an upgrade got equipped). */
    static boolean autoEquip(Character bot, Character owner, Item pendingOffer, boolean force) {
        if (!shouldRunAutoEquip(bot, System.currentTimeMillis(), force)) {
            return false;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Inventory eqpInv = bot.getInventory(InventoryType.EQUIP);
        Inventory eqdInv = bot.getInventory(InventoryType.EQUIPPED);
        MapDamageProfile mob = MapDamageProfile.snapshotByAvoid(bot);

        Map<Short, List<Equip>> bySlot = collectAutoEquipCandidates(bot, ii, eqpInv, eqdInv, pendingOffer);
        Map<Short, Equip> currentBySlot = new HashMap<>();
        for (Item it : eqdInv.list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId())) {
                currentBySlot.put(e.getPosition(), e);
            }
        }

        List<Short> dpSlots = buildDpSlots(bySlot, currentBySlot);
        boolean[] reqRel = scanReqRelevantDims(bySlot, ii);

        // Outer weapon pool: currently-wearable + stat-only-blocked + currently equipped.
        List<Equip> weaponPool = new ArrayList<>(bySlot.getOrDefault((short) -11, List.of()));
        Equip currentWeapon = compatibleWeaponOrNull(bot, ii, (Equip) eqdInv.getItem((short) -11));
        if (currentWeapon != null && !weaponPool.contains(currentWeapon)) weaponPool.add(currentWeapon);
        if (weaponPool.isEmpty()) weaponPool.add(null);

        StatSnapshot naked = nakedBase(bot, ii, eqdInv);

        Map<Short, Equip> bestPicks = null;
        EquipScore bestScore = null;
        Equip bestWeapon = currentWeapon;
        boolean bestCapHit = false;
        boolean anyCapHit = false;
        for (Equip w : weaponPool) {
            DpResult r = solveForWeapon(bot, ii, naked, w, dpSlots, currentBySlot, bySlot, mob, reqRel);
            if (r == null) continue;
            if (r.paretoCapHit()) anyCapHit = true;
            if (bestScore == null || compareScores(r.score(), bestScore) > 0) {
                bestScore = r.score();
                bestPicks = r.picks();
                bestWeapon = w;
                bestCapHit = r.paretoCapHit();
            }
        }
        // Every weapon failed reqs — fall back to a no-weapon plan so the armor pass still runs.
        if (bestPicks == null && !weaponPool.contains(null)) {
            DpResult r = solveForWeapon(bot, ii, naked, null, dpSlots, currentBySlot, bySlot, mob, reqRel);
            if (r != null) {
                bestScore = r.score();
                bestPicks = r.picks();
                bestWeapon = null;
                bestCapHit = r.paretoCapHit();
                if (r.paretoCapHit()) anyCapHit = true;
            }
        }

        boolean changed = false;
        if (bestPicks != null) {
//            log.info("Bot {} autoequip: {}", bot.getName(),
//                    describeEquipPlan(ii, bestWeapon, bestScore, bestPicks, bestCapHit));
            changed = applyEquipPlan(bot, ii, eqdInv, currentBySlot, bestPicks, bestWeapon, dpSlots);
            // Sweep currently-equipped items whose reqs aren't met against the bot's now-final
            // stats. This catches gear left equipped via prior trade-debug or stat changes that
            // would otherwise stick because applyEquipPlan only emits moves into occupied slots.
            unequipInfeasibleEquipped(bot, ii);
        }

        if (anyCapHit) {
            // DP frontier overflowed — too many Pareto-incomparable items in the bot's bag for
            // the optimizer to exhaustively enumerate. The chosen set is best-effort under an
            // admissible-bound truncation; the owner should clean up redundant gear.
            try {
                BotManager.getInstance().botSay(bot,
                        "inventory's too cluttered, cant fully optimize gear - try selling/dropping spares");
            } catch (Throwable ignored) {
                // Don't let a chat error block the equip pass.
            }
        }
        return changed;
    }

    static boolean shouldRunAutoEquip(Character bot, long nowMs, boolean force) {
        if (force) {
            if (bot != null) {
                LAST_AUTOEQUIP_MS.put(bot.getId(), nowMs);
            }
            return true;
        }
        if (bot == null) {
            return true;
        }
        int botId = bot.getId();
        Long previous = LAST_AUTOEQUIP_MS.get(botId);
        if (previous != null && nowMs - previous < AUTOEQUIP_THROTTLE_MS) {
            return false;
        }
        LAST_AUTOEQUIP_MS.put(botId, nowMs);
        return true;
    }

    /**
     * Builds the ordered DP slot list from the merged candidate + currently-equipped slot
     * sets. All four ring slots are included whenever any ring is in the pool, so the DP can
     * place rings into now-empty positions. Sort is descending so {@code -5} (top) is solved
     * before {@code -6} (pants) — the overall→pants block at -6 reads picks[-5].
     */
    private static List<Short> buildDpSlots(Map<Short, List<Equip>> bySlot,
                                             Map<Short, Equip> currentBySlot) {
        Set<Short> set = new HashSet<>();
        for (Short s : bySlot.keySet()) if (s != (short) -11 && !isRingSlot(s)) set.add(s);
        for (Short s : currentBySlot.keySet()) if (s != (short) -11 && !isRingSlot(s)) set.add(s);
        boolean hasRings = !bySlot.getOrDefault((short) -12, List.of()).isEmpty();
        if (!hasRings) {
            for (Short s : currentBySlot.keySet()) if (isRingSlot(s)) { hasRings = true; break; }
        }
        if (hasRings) for (short rs : RING_SLOTS) set.add(rs);
        List<Short> out = new ArrayList<>(set);
        out.sort((a, b) -> Short.compare(b, a));
        return out;
    }

    /**
     * Diagnostic dump of what {@link #autoEquip} would do, without applying any moves.
     * Returns multiple short lines suitable for sequential bot chat. Includes mob benchmark,
     * naked stats, per-weapon score (top 3), changed slots vs current, and pareto-cap status.
     */
    static List<String> autoEquipDebug(Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Inventory eqpInv = bot.getInventory(InventoryType.EQUIP);
        Inventory eqdInv = bot.getInventory(InventoryType.EQUIPPED);
        MapDamageProfile mob = MapDamageProfile.snapshotByAvoid(bot);

        List<String> out = new ArrayList<>();
        if (mob == null) {
            out.add("autoequip: no mob context (in town?) - cant benchmark");
        } else {
            out.add("autoequip mob: avd " + mob.mobAvoid()
                    + " wdef " + mob.mobWdef() + " lv " + mob.mobLevel());
        }

        Map<Short, List<Equip>> bySlot = collectAutoEquipCandidates(bot, ii, eqpInv, eqdInv, null);
        Map<Short, Equip> currentBySlot = new HashMap<>();
        for (Item it : eqdInv.list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId())) currentBySlot.put(e.getPosition(), e);
        }

        List<Short> dpSlots = buildDpSlots(bySlot, currentBySlot);
        boolean[] reqRel = scanReqRelevantDims(bySlot, ii);

        List<Equip> weaponPool = new ArrayList<>(bySlot.getOrDefault((short) -11, List.of()));
        Equip currentWeapon = compatibleWeaponOrNull(bot, ii, (Equip) eqdInv.getItem((short) -11));
        if (currentWeapon != null && !weaponPool.contains(currentWeapon)) weaponPool.add(currentWeapon);
        if (weaponPool.isEmpty()) weaponPool.add(null);

        StatSnapshot naked = nakedBase(bot, ii, eqdInv);
        out.add("naked: str " + naked.str() + " dex " + naked.dex() + " int " + naked.int_()
                + " luk " + naked.luk() + " watk " + naked.watk() + " mag " + naked.magic()
                + " acc " + naked.totalAcc());

        record Branch(Equip weapon, DpResult result) {}
        List<Branch> branches = new ArrayList<>();
        boolean anyCap = false;
        for (Equip w : weaponPool) {
            DpResult r = solveForWeapon(bot, ii, naked, w, dpSlots, currentBySlot, bySlot, mob, reqRel);
            if (r != null) {
                branches.add(new Branch(w, r));
                if (r.paretoCapHit()) anyCap = true;
            }
        }
        // Last-resort fallback: every weapon's reqs failed against the bare snapshot. Try a
        // no-weapon branch so we still produce a best-effort armor plan instead of giving up.
        if (branches.isEmpty() && !weaponPool.contains(null)) {
            DpResult r = solveForWeapon(bot, ii, naked, null, dpSlots, currentBySlot, bySlot, mob, reqRel);
            if (r != null) branches.add(new Branch(null, r));
        }
        branches.sort((a, b) -> compareScores(b.result().score(), a.result().score()));

        if (branches.isEmpty()) {
            out.add("autoequip: no weapon found and no items wearable");
        } else {
            int show = Math.min(3, branches.size());
            for (int i = 0; i < show; i++) {
                Branch b = branches.get(i);
                String wName = b.weapon() == null ? "(no weapon)" : ii.getName(b.weapon().getItemId());
                EquipScore s = b.result().score();
                StatSnapshot branchSnap = snapshotForBranch(naked, b.weapon(), b.result().picks());
                WeaponType wt = b.weapon() != null ? ii.getWeaponType(b.weapon().getItemId()) : null;
                WeaponScoreBreakdown breakdown = weaponScoreBreakdown(branchSnap, b.weapon(), wt, mob);
                String tag = i == 0 ? "*" : " ";
                out.add(tag + " W=" + wName
                        + " dmg=" + s.damage()
                        + " rawMax=" + breakdown.rawMax()
                        + " preCycle=" + breakdown.preCycleDamage()
                        + " cycle=" + breakdown.cycleMs() + "ms"
                        + " stat=" + s.statSum());
            }

            // Diff vs current for the winning branch.
            Branch best = branches.get(0);
            List<String> diffs = new ArrayList<>();
            for (Map.Entry<Short, Equip> e : best.result().picks().entrySet()) {
                Equip cur = currentBySlot.get(e.getKey());
                if (cur != e.getValue()) {
                    diffs.add(slotLabel(e.getKey()) + ":"
                            + (cur == null ? "-" : ii.getName(cur.getItemId()))
                            + ">" + ii.getName(e.getValue().getItemId()));
                }
            }
            Equip currentWp = (Equip) eqdInv.getItem((short) -11);
            if (best.weapon() != currentWp) {
                diffs.add(0, "weapon:" + (currentWp == null ? "-" : ii.getName(currentWp.getItemId()))
                        + ">" + (best.weapon() == null ? "-" : ii.getName(best.weapon().getItemId())));
            }
            if (diffs.isEmpty()) {
                out.add("changes: none (already optimal)");
            } else {
                // Chunk into lines of ≤3 changes to avoid one giant message.
                for (int i = 0; i < diffs.size(); i += 3) {
                    out.add("change: " + String.join(", ", diffs.subList(i, Math.min(i + 3, diffs.size()))));
                }
            }
            if (anyCap) out.add("WARN: pareto cap hit, result is best-effort");
        }

        out.add("range: " + BotChatManager.buildRangeReport(bot));

        // Full dump to disk — chat is too narrow for inventory + per-branch breakdown.
        String filePath = writeAutoEquipDumpFile(bot, ii, eqpInv, eqdInv, mob, naked,
                bySlot, dpSlots, weaponPool, branches, anyCap);
        if (filePath != null) out.add("dump: " + filePath);

        String botName = bot != null ? bot.getName() : "?";
        log.info("autoEquipDebug[{}]:\n  {}", botName, String.join("\n  ", out));
        return out;
    }

    /**
     * Writes a comprehensive autoEquip decision dump to {@code logs/bot-equip/}, mirroring the
     * format of {@link BotPathLogger}. Captures everything the optimizer saw: mob profile,
     * naked stats, currently-equipped items with stats/reqs, candidate inventory items with
     * stats/reqs, and per-weapon-branch DP results with chosen picks. Returns absolute path.
     */
    @SuppressWarnings("unchecked")
    private static String writeAutoEquipDumpFile(Character bot, ItemInformationProvider ii,
            Inventory eqpInv, Inventory eqdInv, MapDamageProfile mob, StatSnapshot naked,
            Map<Short, List<Equip>> bySlot, List<Short> dpSlots, List<Equip> weaponPool,
            List<?> branches, boolean anyCap) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String botName = bot != null ? bot.getName() : "unknown";
        String safeName = botName.replaceAll("[^a-zA-Z0-9_-]", "_");
        String filename = "equiplog-" + safeName + "-" + now.format(EQUIP_LOG_FILE_FMT) + ".txt";

        StringBuilder sb = new StringBuilder(8192);
        sb.append("=== autoEquip dump ===\n");
        sb.append("time:    ").append(now.format(EQUIP_LOG_HEADER_FMT)).append('\n');
        sb.append("bot:     ").append(botName)
          .append(" job=").append(bot != null ? bot.getJob() : "?")
          .append(" lv=").append(bot != null ? bot.getLevel() : 0)
          .append(" fame=").append(bot != null ? bot.getFame() : 0).append('\n');
        sb.append("map:     ").append(safeMapId(bot)).append('\n');
        sb.append("mob:     ");
        if (mob == null) sb.append("(no mob context)\n");
        else sb.append("avd=").append(mob.mobAvoid())
                .append(" wdef=").append(mob.mobWdef())
                .append(" lv=").append(mob.mobLevel()).append('\n');
        sb.append("naked:   str=").append(naked.str())
          .append(" dex=").append(naked.dex())
          .append(" int=").append(naked.int_())
          .append(" luk=").append(naked.luk())
          .append(" watk=").append(naked.watk())
          .append(" mag=").append(naked.magic())
          .append(" acc=").append(naked.totalAcc()).append('\n');
        sb.append("range:   ").append(BotChatManager.buildRangeReport(bot, mob)).append('\n');

        sb.append("\n--- equipped ---\n");
        sb.append(itemHeader(false));
        for (Item it : eqdInv.list()) {
            if (it instanceof Equip e) appendItemRow(sb, ii, e, e.getPosition(), null);
        }

        sb.append("\n--- inventory (equip bag) ---\n");
        sb.append(itemHeader(true));
        // STATUS mirrors the real sell pipeline (see BotInventoryManager.classifyBagEquips).
        BotEntry dumpEntry = bot != null
                ? BotManager.getInstance().getEntryByBotCharId(bot.getId()) : null;
        Map<Item, BotInventoryManager.BagEquipClass> statuses = bot != null
                ? BotInventoryManager.classifyBagEquips(dumpEntry, bot) : Map.of();
        for (Item it : eqpInv.list()) {
            if (it instanceof Equip e) {
                BotInventoryManager.BagEquipClass c = statuses.get(it);
                appendItemRow(sb, ii, e, e.getPosition(), c == null ? "-" : c.label());
            }
        }

        sb.append("\n--- candidate pools by slot ---\n");
        for (Map.Entry<Short, List<Equip>> en : bySlot.entrySet()) {
            sb.append(slotLabel(en.getKey())).append(" (").append(en.getKey()).append("): ");
            if (en.getValue().isEmpty()) sb.append("(empty)\n");
            else {
                sb.append(en.getValue().size()).append(" cands: ");
                List<String> names = new ArrayList<>();
                for (Equip e : en.getValue()) names.add(ii.getName(e.getItemId()) + "#" + e.getPosition());
                sb.append(String.join(", ", names)).append('\n');
            }
        }

        sb.append("\n--- weapon branches (sorted by score) ---\n");
        // branches is List<Branch> (record local to autoEquipDebug); reflect via toString fallback.
        // For type safety, recompute here from the same inputs:
        Equip currentWp = (Equip) eqdInv.getItem((short) -11);
        Map<Short, Equip> currentBySlot = new HashMap<>();
        for (Item it : eqdInv.list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId())) currentBySlot.put(e.getPosition(), e);
        }
        record Br(Equip w, DpResult r) {}
        List<Br> sorted = new ArrayList<>();
        boolean[] reqRel = scanReqRelevantDims(bySlot, ii);
        for (Equip w : weaponPool) {
            DpResult r = solveForWeapon(bot, ii, naked, w, dpSlots, currentBySlot, bySlot, mob, reqRel);
            if (r != null) sorted.add(new Br(w, r));
        }
        sorted.sort((a, b) -> compareScores(b.r().score(), a.r().score()));
        for (int i = 0; i < sorted.size(); i++) {
            Br b = sorted.get(i);
            String wName = b.w() == null ? "(none)" : ii.getName(b.w().getItemId());
            EquipScore s = b.r().score();
            StatSnapshot branchSnap = snapshotForBranch(naked, b.w(), b.r().picks());
            WeaponType wt = b.w() != null ? ii.getWeaponType(b.w().getItemId()) : null;
            WeaponScoreBreakdown breakdown = weaponScoreBreakdown(branchSnap, b.w(), wt, mob);
            sb.append(i == 0 ? "[*] " : "[ ] ").append(wName)
              .append(" id=").append(b.w() == null ? 0 : b.w().getItemId())
              .append(" dmg=").append(s.damage())
              .append(" rawMax=").append(breakdown.rawMax())
              .append(" preCycle=").append(breakdown.preCycleDamage())
              .append(" cycle=").append(breakdown.cycleMs()).append("ms")
              .append(" stat=").append(s.statSum())
              .append(b.r().paretoCapHit() ? " (pareto-cap)" : "").append('\n');
            for (Map.Entry<Short, Equip> pick : b.r().picks().entrySet()) {
                Equip cur = currentBySlot.get(pick.getKey());
                String marker = cur == pick.getValue() ? "  =" : "  >";
                sb.append(marker).append(' ').append(slotLabel(pick.getKey())).append(": ");
                if (cur != pick.getValue() && cur != null) {
                    sb.append(ii.getName(cur.getItemId())).append(" -> ");
                }
                sb.append(ii.getName(pick.getValue().getItemId())).append('\n');
            }
        }

        sb.append("\n--- summary ---\n");
        if (sorted.isEmpty()) {
            sb.append("no feasible set found\n");
        } else {
            Br winner = sorted.get(0);
            sb.append("winner weapon: ").append(winner.w() == null ? "(none)" : ii.getName(winner.w().getItemId())).append('\n');
            sb.append("current weapon: ").append(currentWp == null ? "(none)" : ii.getName(currentWp.getItemId())).append('\n');
            sb.append("pareto-cap-hit: ").append(anyCap).append('\n');
        }

        try {
            java.nio.file.Files.createDirectories(EQUIP_LOG_DIR);
            java.nio.file.Path file = EQUIP_LOG_DIR.resolve(filename);
            java.nio.file.Files.writeString(file, sb.toString());
            return file.toAbsolutePath().toString();
        } catch (java.io.IOException e) {
            log.warn("Failed to write autoEquip dump", e);
            return null;
        }
    }

    private static String itemHeader(boolean includeStatus) {
        return String.format("%-3s %-30s %-7s %4s %4s %4s %4s %4s %4s %4s %4s %4s %4s %5s %5s%s   reqs%n",
                "pos", "name", "slot", "STR", "DEX", "INT", "LUK", "WAK", "MAK", "WDF", "MDF", "ACC", "AVD", "HP", "MP",
                includeStatus ? "  STATUS    " : "");
    }

    private static void appendItemRow(StringBuilder sb, ItemInformationProvider ii, Equip e, short pos,
                                      String status) {
        String name = ii.getName(e.getItemId());
        if (name == null) name = "id=" + e.getItemId();
        if (name.length() > 30) name = name.substring(0, 30);
        String textSlot = ii.getEquipmentSlot(e.getItemId());
        sb.append(String.format("%-3d %-30s %-7s %4d %4d %4d %4d %4d %4d %4d %4d %4d %4d %5d %5d%s   ",
                pos, name, textSlot == null ? "?" : textSlot,
                e.getStr(), e.getDex(), e.getInt(), e.getLuk(),
                e.getWatk(), e.getMatk(), e.getWdef(), e.getMdef(),
                e.getAcc(), e.getAvoid(), e.getHp(), e.getMp(),
                status == null ? "" : String.format("  %-10s", status)));
        // Reqs from WZ stat map.
        Map<String, Integer> stats = ii.getEquipStats(e.getItemId());
        if (stats != null) {
            int rl = ii.getEquipLevelReq(e.getItemId());
            int rj = stats.getOrDefault("reqJob", 0);
            int rs = stats.getOrDefault("reqSTR", 0);
            int rd = stats.getOrDefault("reqDEX", 0);
            int ri = stats.getOrDefault("reqINT", 0);
            int rk = stats.getOrDefault("reqLUK", 0);
            int rp = stats.getOrDefault("reqPOP", 0);
            sb.append("lv").append(rl).append(" job").append(rj);
            if (rs > 0) sb.append(" str").append(rs);
            if (rd > 0) sb.append(" dex").append(rd);
            if (ri > 0) sb.append(" int").append(ri);
            if (rk > 0) sb.append(" luk").append(rk);
            if (rp > 0) sb.append(" pop").append(rp);
        }
        sb.append('\n');
    }

    private static int safeMapId(Character bot) {
        if (bot == null) return -1;
        try { return bot.getMap() != null ? bot.getMap().getId() : -1; }
        catch (Throwable t) { return -1; }
    }

    /**
     * Builds per-slot candidate pools for the optimizer. Includes currently-wearable items,
     * stat-only-blocked items (DP may unlock them), and currently-equipped items (so DP can
     * choose to keep them). Per-slot dominance-pruned with reqs honored.
     */
    private static Map<Short, List<Equip>> collectAutoEquipCandidates(
            Character bot, ItemInformationProvider ii, Inventory eqpInv,
            Inventory eqdInv, Item pendingOffer) {
        Map<Short, List<Equip>> bySlot = new LinkedHashMap<>();
        for (Item item : eqpInv.list()) {
            if (ii.isCash(item.getItemId())) continue;
            if (item == pendingOffer) continue;
            if (!(item instanceof Equip equip)) continue;
            String textSlot = ii.getEquipmentSlot(item.getItemId());
            EquipSlot eslot = EquipSlot.getFromTextSlot(textSlot);
            if (eslot == null || eslot == EquipSlot.PET_EQUIP) continue;
            short primary = (short) eslot.getPrimarySlot();
            if (primary == 0) continue;
            if (primary == (short) -11
                    && !isWeaponCompatible(bot, ii.getWeaponType(equip.getItemId()))) continue;
            if (ii.canWearEquipment(bot, equip, primary) || statOnlyBlocked(bot, ii, equip)) {
                bySlot.computeIfAbsent(primary, k -> new ArrayList<>()).add(equip);
            }
        }
        // Include currently-equipped items (they're already legal).
        for (Item it : eqdInv.list()) {
            if (!(it instanceof Equip e) || ii.isCash(e.getItemId())) continue;
            short pos = e.getPosition();
            if (pos == (short) -11
                    && !isWeaponCompatible(bot, ii.getWeaponType(e.getItemId()))) continue;
            short key = isRingSlot(pos) ? (short) -12 : pos;
            List<Equip> pool = bySlot.computeIfAbsent(key, k -> new ArrayList<>());
            if (!pool.contains(e)) pool.add(e);
        }
        Job botJob = bot.getJob();
        boolean[] reqRel = scanReqRelevantDims(bySlot, ii);
        for (Map.Entry<Short, List<Equip>> e : bySlot.entrySet()) {
            e.setValue(pruneDominatedWithReqs(ii, e.getValue(), botJob, reqRel));
        }
        return bySlot;
    }

    record DpResult(Map<Short, Equip> picks, EquipScore score, boolean paretoCapHit) {
        DpResult(Map<Short, Equip> picks, EquipScore score) { this(picks, score, false); }
    }

    /** Outcome of {@link #runOptimizerWithExtras}: the picked weapon (may be null) and
     *  non-ring slot picks. Picks omit slots the optimizer chose to leave empty. */
    record OptimizerResult(Equip weapon, Map<Short, Equip> picks) {}

    private enum RecommendationScope {
        IMMEDIATE,
        FUTURE
    }

    /**
     * Runs the autoEquip DP after merging {@code extras} into the receiver's candidate pool.
     * Used by the trade request/offer path so its recommendations match what autoEquip would
     * actually do. The {@code extras} are still subject to scope-appropriate requirement and
     * weapon-compat filters before entering the DP.
     */
    static OptimizerResult runOptimizerWithExtras(Character bot, Collection<Equip> extras) {
        return runOptimizerWithExtras(bot, extras, RecommendationScope.IMMEDIATE);
    }

    private static OptimizerResult runOptimizerWithExtras(Character bot, Collection<Equip> extras,
                                                          RecommendationScope scope) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Inventory eqpInv = bot.getInventory(InventoryType.EQUIP);
        Inventory eqdInv = bot.getInventory(InventoryType.EQUIPPED);
        MapDamageProfile mob = MapDamageProfile.snapshotByAvoid(bot);

        Map<Short, List<Equip>> bySlot = scope == RecommendationScope.IMMEDIATE
                ? collectAutoEquipCandidates(bot, ii, eqpInv, eqdInv, null)
                : collectFutureEquipCandidates(bot, ii, eqpInv, eqdInv);
        for (Equip ex : extras) {
            if (ex == null || ii.isCash(ex.getItemId())) continue;
            String ts = ii.getEquipmentSlot(ex.getItemId());
            if (ts == null) continue;
            EquipSlot eslot = EquipSlot.getFromTextSlot(ts);
            if (eslot == null || eslot == EquipSlot.PET_EQUIP) continue;
            short pslot = (short) eslot.getPrimarySlot();
            if (pslot == 0) continue;
            if (pslot == (short) -11
                    && !isWeaponCompatible(bot, ii.getWeaponType(ex.getItemId()))) continue;
            if (!isRecommendationCandidate(bot, ii, ex, pslot, scope)) continue;
            // Rings live in the shared -12 pool regardless of which equipped position they came from.
            short key = isRingSlot(pslot) ? (short) -12 : pslot;
            List<Equip> pool = bySlot.computeIfAbsent(key, k -> new ArrayList<>());
            if (!pool.contains(ex)) pool.add(ex);
        }
        // Re-prune so newly-added extras can knock out dominated incumbents (and vice versa).
        Job receiverJob = bot.getJob();
        boolean[] reqRel = scanReqRelevantDims(bySlot, ii);
        for (Map.Entry<Short, List<Equip>> e : bySlot.entrySet()) {
            e.setValue(pruneDominatedWithReqs(ii, e.getValue(), receiverJob, reqRel));
        }

        Map<Short, Equip> currentBySlot = new HashMap<>();
        for (Item it : eqdInv.list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId())) currentBySlot.put(e.getPosition(), e);
        }

        List<Short> dpSlots = buildDpSlots(bySlot, currentBySlot);

        List<Equip> weaponPool = new ArrayList<>(bySlot.getOrDefault((short) -11, List.of()));
        Equip currentWeapon = compatibleWeaponOrNull(bot, ii, (Equip) eqdInv.getItem((short) -11));
        if (currentWeapon != null && !weaponPool.contains(currentWeapon)) weaponPool.add(currentWeapon);
        if (weaponPool.isEmpty()) weaponPool.add(null);

        StatSnapshot naked = nakedBase(bot, ii, eqdInv);
        OptimizerHooks hooks = scope == RecommendationScope.IMMEDIATE
                ? OptimizerHooks.from(ii)
                : OptimizerHooks.futureFrom(ii, bot);
        Map<Short, Equip> bestPicks = null;
        EquipScore bestScore = null;
        Equip bestWeapon = null;
        for (Equip w : weaponPool) {
            DpResult r = solveForWeapon(bot, hooks, naked, w, dpSlots, currentBySlot, bySlot, mob, reqRel);
            if (r == null) continue;
            if (bestScore == null || compareScores(r.score(), bestScore) > 0) {
                bestScore = r.score();
                bestPicks = r.picks();
                bestWeapon = w;
            }
        }
        if (bestPicks == null && !weaponPool.contains(null)) {
            DpResult r = solveForWeapon(bot, hooks, naked, null, dpSlots, currentBySlot, bySlot, mob, reqRel);
            if (r != null) { bestPicks = r.picks(); bestWeapon = null; }
        }
        return new OptimizerResult(bestWeapon, bestPicks != null ? bestPicks : Map.of());
    }

    /**
     * Narrow surface of {@link ItemInformationProvider} that the optimizer's DP needs.
     * Tests stub these directly with lambdas — Mockito cannot instrument II in unit tests
     * because of its static WZ-data initializer.
     */
    interface OptimizerHooks {
        boolean isTwoHanded(int itemId);
        WeaponType getWeaponType(int itemId);
        boolean isOverall(int itemId);
        boolean meetsReqs(Equip equip, Job job, int level, int str, int dex, int int_, int luk, int fame);
        default Map<String, Integer> getEquipStats(int itemId) { return Map.of(); }

        static OptimizerHooks from(ItemInformationProvider ii) {
            return new OptimizerHooks() {
                @Override public boolean isTwoHanded(int itemId) { return ii.isTwoHanded(itemId); }
                @Override public WeaponType getWeaponType(int itemId) { return ii.getWeaponType(itemId); }
                @Override public boolean isOverall(int itemId) {
                    return "MaPn".equals(ii.getEquipmentSlot(itemId));
                }
                @Override public boolean meetsReqs(Equip e, Job job, int lvl, int s, int d, int i, int l, int f) {
                    return ii.meetsEquipRequirements(e, job, lvl, s, d, i, l, f);
                }
                @Override public Map<String, Integer> getEquipStats(int itemId) { return ii.getEquipStats(itemId); }
            };
        }

        static OptimizerHooks futureFrom(ItemInformationProvider ii, Character bot) {
            final Job botJob = bot != null ? bot.getJob() : null;
            final int level = bot != null && bot.getLevel() > 0 ? bot.getLevel() : Short.MAX_VALUE;
            final int fame = bot != null ? bot.getFame() : 0;
            final int max = Integer.MAX_VALUE / 4;
            return new OptimizerHooks() {
                @Override public boolean isTwoHanded(int itemId) { return ii.isTwoHanded(itemId); }
                @Override public WeaponType getWeaponType(int itemId) { return ii.getWeaponType(itemId); }
                @Override public boolean isOverall(int itemId) {
                    return "MaPn".equals(ii.getEquipmentSlot(itemId));
                }
                @Override public boolean meetsReqs(Equip e, Job job, int lvl, int s, int d, int i, int l, int f) {
                    return ii.meetsEquipRequirements(e, botJob, level, max, max, max, max, fame);
                }
                @Override public Map<String, Integer> getEquipStats(int itemId) { return ii.getEquipStats(itemId); }
            };
        }
    }

    interface EquipUsefulnessHooks {
        boolean isCash(int itemId);
        String getEquipmentSlot(int itemId);
        WeaponType getWeaponType(int itemId);
        boolean meetsReqs(Equip equip, Job job, int level, int str, int dex, int int_, int luk, int fame);

        static EquipUsefulnessHooks from(ItemInformationProvider ii) {
            return new EquipUsefulnessHooks() {
                @Override public boolean isCash(int itemId) { return ii.isCash(itemId); }
                @Override public String getEquipmentSlot(int itemId) { return ii.getEquipmentSlot(itemId); }
                @Override public WeaponType getWeaponType(int itemId) { return ii.getWeaponType(itemId); }
                @Override public boolean meetsReqs(Equip equip, Job job, int level, int str, int dex,
                                                   int int_, int luk, int fame) {
                    return ii.meetsEquipRequirements(equip, job, level, str, dex, int_, luk, fame);
                }
            };
        }
    }

    interface SelfReserveHooks extends EquipUsefulnessHooks {
        int getEquipLevelReq(int itemId);
        Map<String, Integer> getEquipStats(int itemId);
        double maxScrollOffenseGainPerSlot(Character bot, int itemId);

        static SelfReserveHooks from(ItemInformationProvider ii) {
            return new SelfReserveHooks() {
                @Override public boolean isCash(int itemId) { return ii.isCash(itemId); }
                @Override public String getEquipmentSlot(int itemId) { return ii.getEquipmentSlot(itemId); }
                @Override public WeaponType getWeaponType(int itemId) { return ii.getWeaponType(itemId); }
                @Override public boolean meetsReqs(Equip equip, Job job, int level, int str, int dex,
                                                   int int_, int luk, int fame) {
                    return ii.meetsEquipRequirements(equip, job, level, str, dex, int_, luk, fame);
                }
                @Override public int getEquipLevelReq(int itemId) { return ii.getEquipLevelReq(itemId); }
                @Override public Map<String, Integer> getEquipStats(int itemId) { return ii.getEquipStats(itemId); }
                @Override public double maxScrollOffenseGainPerSlot(Character bot, int itemId) {
                    return BotScrollManager.maxScrollOffenseGainPerSlot(bot, ii, itemId);
                }
            };
        }
    }

    /**
     * Pareto-DP across {@code dpSlots} for a fixed weapon. Frontier carries (StatSnapshot,
     * def, hp, mp, statSum, picks[]); per-slot transition tries each candidate plus an
     * "empty" option, then prunes dominated states. Slot-collision constraints (2H↔shield,
     * overall↔pants) enforced inline. Returns the best validated final state, or null.
     */
    static DpResult solveForWeapon(Character bot, ItemInformationProvider ii,
                                            StatSnapshot naked, Equip weapon,
                                            List<Short> dpSlots,
                                            Map<Short, Equip> currentBySlot,
                                            Map<Short, List<Equip>> bySlot,
                                            MapDamageProfile mob) {
        return solveForWeapon(bot, ii, naked, weapon, dpSlots, currentBySlot, bySlot,
                mob, scanReqRelevantDims(bySlot, ii));
    }

    static DpResult solveForWeapon(Character bot, ItemInformationProvider ii,
                                            StatSnapshot naked, Equip weapon,
                                            List<Short> dpSlots,
                                            Map<Short, Equip> currentBySlot,
                                            Map<Short, List<Equip>> bySlot,
                                            MapDamageProfile mob,
                                            boolean[] reqRel) {
        return solveForWeapon(bot, OptimizerHooks.from(ii), naked, weapon, dpSlots,
                              currentBySlot, bySlot, mob, reqRel);
    }

    static DpResult solveForWeapon(Character bot, OptimizerHooks hooks,
                                            StatSnapshot naked, Equip weapon,
                                            List<Short> dpSlots,
                                            Map<Short, Equip> currentBySlot,
                                            Map<Short, List<Equip>> bySlot,
                                            MapDamageProfile mob) {
        return solveForWeapon(bot, hooks, naked, weapon, dpSlots, currentBySlot, bySlot,
                mob, scanReqRelevantDims(bySlot, hooks));
    }

    private static DpResult solveForWeapon(Character bot, OptimizerHooks hooks,
                                            StatSnapshot naked, Equip weapon,
                                            List<Short> dpSlots,
                                            Map<Short, Equip> currentBySlot,
                                            Map<Short, List<Equip>> bySlot,
                                            MapDamageProfile mob,
                                            boolean[] reqRel) {
        StatSnapshot init = weapon != null ? naked.swap(null, weapon) : naked;
        boolean is2H = weapon != null && hooks.isTwoHanded(weapon.getItemId());
        WeaponType wt = weapon != null ? hooks.getWeaponType(weapon.getItemId()) : null;
        boolean[] capHit = {false};

        int n = dpSlots.size();
        int overallIdx = dpSlots.indexOf((short) -5);
        DpNode start = pinSafeSingletonSlots(init, hooks, dpSlots, bySlot, n);
        List<DpNode> frontier = new ArrayList<>();
        frontier.add(start);

        for (int i = 0; i < n; i++) {
            short slot = dpSlots.get(i);
            if (start.picks[i] != null) {
                continue;
            }
            // Rings share a single pool keyed at -12; cross-slot dedupe prevents the same ring
            // instance being placed in two ring slots. The 4 ring positions are otherwise
            // interchangeable for stats, so the DP just picks up to 4 rings from the pool.
            boolean ringSlot = isRingSlot(slot);
            List<Equip> pool = ringSlot
                    ? bySlot.getOrDefault((short) -12, List.of())
                    : bySlot.getOrDefault(slot, List.of());
            List<DpNode> next = new ArrayList<>(Math.max(8, frontier.size() * (pool.size() + 1)));
            for (DpNode prev : frontier) {
                // Empty option always available — always carry forward.
                next.add(prev);
                if (slot == (short) -10 && is2H) continue; // shield blocked by 2H weapon
                boolean blockedByOverall = (slot == (short) -6 && overallIdx >= 0
                        && prev.picks[overallIdx] != null
                        && hooks.isOverall(prev.picks[overallIdx].getItemId()));
                if (blockedByOverall) continue;
                candLoop:
                for (Equip cand : pool) {
                    if (cand == null) continue;
                    if (ringSlot) {
                        for (int j = 0; j < i; j++) {
                            if (isRingSlot(dpSlots.get(j)) && prev.picks[j] == cand) continue candLoop;
                        }
                    }
                    StatSnapshot ns = prev.snap.swap(null, cand);
                    int nHp = prev.hp + cand.getHp();
                    int nMp = prev.mp + cand.getMp();
                    int nStat = prev.statSum + usefulStatSum(cand, ns.job());
                    Equip[] picks = prev.picks.clone();
                    picks[i] = cand;
                    next.add(new DpNode(ns, nHp, nMp, nStat, picks));
                }
            }
            frontier = paretoPruneNodes(next, capHit, hooks, dpSlots, wt, reqRel);
        }

        DpNode best = null;
        EquipScore bestScore = null;
        for (DpNode node : frontier) {
            if (!validateReqs(hooks, node, dpSlots, weapon)) continue;
            EquipScore s = scoreNode(node, weapon, wt, mob);
            if (bestScore == null || compareScores(s, bestScore) > 0) {
                bestScore = s;
                best = node;
            }
        }
        // Best-effort fallback: Pareto pruning may have dropped the all-empty state in favor
        // of higher-stat states that include picks whose reqs aren't met against the final
        // snapshot (e.g. boots needing dex60 when no item in the pool adds dex). Rather than
        // reporting "no feasible set", relax each frontier node by dropping infeasible picks
        // (cascading until stable) and pick the best-scoring result. Returns null only if the
        // weapon itself fails reqs against the bare snapshot — caller can try a null weapon.
        if (best == null) {
            for (DpNode node : frontier) {
                DpNode relaxed = relaxToFeasible(hooks, node, dpSlots, weapon);
                if (relaxed == null) continue;
                EquipScore s = scoreNode(relaxed, weapon, wt, mob);
                if (bestScore == null || compareScores(s, bestScore) > 0) {
                    bestScore = s;
                    best = relaxed;
                }
            }
        }
        if (best == null) return null;

        Map<Short, Equip> picks = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (best.picks[i] != null) picks.put(dpSlots.get(i), best.picks[i]);
        }
        return new DpResult(picks, bestScore, capHit[0]);
    }

    private static DpNode pinSafeSingletonSlots(StatSnapshot init, OptimizerHooks hooks,
                                                List<Short> dpSlots, Map<Short, List<Equip>> bySlot,
                                                int n) {
        StatSnapshot snap = init;
        int hp = 0, mp = 0, statSum = 0;
        Equip[] picks = new Equip[n];
        for (int i = 0; i < n; i++) {
            short slot = dpSlots.get(i);
            if (!canPinSingletonSlot(slot)) continue;
            List<Equip> pool = bySlot.getOrDefault(slot, List.of());
            if (pool.size() != 1) continue;
            Equip cand = pool.get(0);
            if (cand == null) continue;
            if (!hooks.meetsReqs(cand, snap.job(), snap.level(),
                    snap.str(), snap.dex(), snap.int_(), snap.luk(), snap.fame())) continue;
            snap = snap.swap(null, cand);
            hp += cand.getHp();
            mp += cand.getMp();
            statSum += usefulStatSum(cand, snap.job());
            picks[i] = cand;
        }
        return new DpNode(snap, hp, mp, statSum, picks);
    }

    private static boolean canPinSingletonSlot(short slot) {
        return slot != (short) -5      // top/overall can block pants
                && slot != (short) -6  // pants can be blocked by an overall
                && slot != (short) -10 // shield can be blocked by 2H weapons
                && !isRingSlot(slot);
    }

    private static final class DpNode {
        final StatSnapshot snap;
        final int hp, mp, statSum;
        final Equip[] picks;
        DpNode(StatSnapshot snap, int hp, int mp, int statSum, Equip[] picks) {
            this.snap = snap; this.hp = hp; this.mp = mp;
            this.statSum = statSum; this.picks = picks;
        }
    }

    private static List<DpNode> paretoPruneNodes(List<DpNode> nodes, boolean[] capHitOut,
                                                   OptimizerHooks hooks, List<Short> dpSlots,
                                                   WeaponType wt, boolean[] reqRel) {
        if (nodes.size() <= 1) return nodes;
        nodes = dedupEquivalentNodes(nodes, wt, reqRel, hooks, dpSlots);
        if (nodes.size() <= 1) return nodes;
        final int N = nodes.size();
        // Cache vec + per-node feasibility once. allPicksMeetReqs depends only on b, not a,
        // but was previously recomputed for every (i,j) dominance check — O(N^2 * slots * WZ)
        // for what's actually O(N * slots * WZ) of work.
        int[][] vecs = new int[N][];
        boolean[] picksOk = new boolean[N];
        for (int k = 0; k < N; k++) {
            DpNode n = nodes.get(k);
            vecs[k] = nodeVec(n, wt, reqRel);
            picksOk[k] = allPicksMeetReqs(n, hooks, dpSlots);
        }
        List<DpNode> kept = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            int[] a = vecs[i];
            boolean dominated = false;
            for (int j = 0; j < N; j++) {
                if (i == j) continue;
                // Only allow b to prune a if b's picks already meet their stat requirements
                // against b's partial snapshot. A speculative node whose items are stat-gated
                // (e.g. dex70 glove when dex=50) must not eliminate the fallback that would
                // otherwise be chosen after relaxToFeasible drops the infeasible pick.
                if (!picksOk[j]) continue;
                if (vecDominates(vecs[j], a)) { dominated = true; break; }
            }
            if (!dominated) kept.add(nodes.get(i));
        }
        if (kept.size() > MAX_PARETO_STATES) {
            // Best-effort overload guard: keep the highest branch damage potential and drop
            // Pareto-front fringe under load.
            kept.sort((x, y) -> Integer.compare(damagePotential(y, wt), damagePotential(x, wt)));
            kept = new ArrayList<>(kept.subList(0, MAX_PARETO_STATES));
            if (capHitOut != null && capHitOut.length > 0) capHitOut[0] = true;
        }
        return kept;
    }

    private static int damagePotential(DpNode n, WeaponType wt) {
        return isMageJob(n.snap.job()) ? magicScore(n.snap) : rawPhysicalMax(n.snap, wt);
    }

    private static List<DpNode> dedupEquivalentNodes(List<DpNode> nodes, WeaponType wt, boolean[] reqRel,
                                                     OptimizerHooks hooks, List<Short> dpSlots) {
        Map<DpSignature, DpNode> bestValidBySignature = new LinkedHashMap<>();
        Map<DpSignature, DpNode> bestSpeculativeBySignature = new LinkedHashMap<>();
        for (DpNode node : nodes) {
            DpSignature signature = DpSignature.from(node, wt, reqRel);
            Map<DpSignature, DpNode> bucket = allPicksMeetReqs(node, hooks, dpSlots)
                    ? bestValidBySignature
                    : bestSpeculativeBySignature;
            DpNode existing = bucket.get(signature);
            if (existing == null || node.statSum > existing.statSum) {
                bucket.put(signature, node);
            }
        }
        int keptSize = bestValidBySignature.size() + bestSpeculativeBySignature.size();
        if (keptSize == nodes.size()) return nodes;
        List<DpNode> kept = new ArrayList<>(keptSize);
        kept.addAll(bestValidBySignature.values());
        kept.addAll(bestSpeculativeBySignature.values());
        return kept;
    }

    private record DpSignature(int damage, int acc, int str, int dex, int int_, int luk) {
        static DpSignature from(DpNode node, WeaponType wt, boolean[] reqRel) {
            StatSnapshot s = node.snap;
            return new DpSignature(
                    damagePotential(node, wt),
                    isMageJob(s.job()) ? 0 : s.totalAcc(),
                    reqRel != null && reqRel[0] ? s.str() : 0,
                    reqRel != null && reqRel[1] ? s.dex() : 0,
                    reqRel != null && reqRel[2] ? s.int_() : 0,
                    reqRel != null && reqRel[3] ? s.luk() : 0);
        }
    }

    private static int[] nodeVec(DpNode n, WeaponType wt, boolean[] reqRel) {
        // Preserve score drivers plus stat gates, not every raw stat. statSum is deliberately
        // excluded here; it only breaks ties after exact-signature dedup and final scoring.
        StatSnapshot s = n.snap;
        int reqCount = 0;
        if (reqRel != null) for (boolean b : reqRel) if (b) reqCount++;
        int[] vec = new int[2 + reqCount];
        int k = 0;
        vec[k++] = damagePotential(n, wt);
        vec[k++] = isMageJob(s.job()) ? 0 : s.totalAcc();
        if (reqRel != null) {
            for (int i = 0; i < reqRel.length; i++) {
                if (reqRel[i]) vec[k++] = statByIdx(s, i);
            }
        }
        return vec;
    }

    private static boolean vecDominates(int[] b, int[] a) {
        boolean strict = false;
        for (int i = 0; i < b.length; i++) {
            if (b[i] < a[i]) return false;
            if (b[i] > a[i]) strict = true;
        }
        return strict;
    }

    private static boolean allPicksMeetReqs(DpNode node, OptimizerHooks hooks, List<Short> dpSlots) {
        StatSnapshot s = node.snap;
        for (int i = 0; i < dpSlots.size(); i++) {
            Equip p = node.picks[i];
            if (p == null) continue;
            StatSnapshot withoutSelf = s.swap(p, null);
            if (!hooks.meetsReqs(p, withoutSelf.job(), withoutSelf.level(),
                    withoutSelf.str(), withoutSelf.dex(), withoutSelf.int_(),
                    withoutSelf.luk(), withoutSelf.fame())) {
                return false;
            }
        }
        return true;
    }

    private static boolean validateReqs(OptimizerHooks hooks, DpNode node,
                                         List<Short> dpSlots, Equip weapon) {
        StatSnapshot s = node.snap;
        // Equip-order constraint: each item's reqs must be satisfied by the stats present
        // BEFORE that item is worn — an item's own contribution cannot count toward its own
        // prereq. So check each pick (and the weapon) against the snapshot with that pick's
        // stats removed. If every pick passes its without-self check, a valid wear order
        // exists (equip the highest-margin items last).
        if (weapon != null) {
            StatSnapshot withoutSelf = s.swap(weapon, null);
            if (!hooks.meetsReqs(weapon, withoutSelf.job(), withoutSelf.level(),
                    withoutSelf.str(), withoutSelf.dex(), withoutSelf.int_(),
                    withoutSelf.luk(), withoutSelf.fame())) return false;
        }
        for (int i = 0; i < dpSlots.size(); i++) {
            Equip p = node.picks[i];
            if (p == null) continue;
            StatSnapshot withoutSelf = s.swap(p, null);
            if (!hooks.meetsReqs(p, withoutSelf.job(), withoutSelf.level(),
                    withoutSelf.str(), withoutSelf.dex(), withoutSelf.int_(),
                    withoutSelf.luk(), withoutSelf.fame())) return false;
        }
        return true;
    }

    /**
     * Iteratively drops picks whose reqs aren't met against the current snapshot until the
     * remaining set is self-consistent. Cascading: dropping a stat-giving pick may invalidate
     * another. Returns null iff the weapon itself fails reqs against the bare snapshot.
     */
    private static DpNode relaxToFeasible(OptimizerHooks hooks, DpNode node,
                                           List<Short> dpSlots, Equip weapon) {
        StatSnapshot s = node.snap;
        int hp = node.hp, mp = node.mp, statSum = node.statSum;
        Equip[] picks = node.picks.clone();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < dpSlots.size(); i++) {
                Equip p = picks[i];
                if (p == null) continue;
                // Equip-order: check p's reqs against the snapshot with p removed (an item's
                // own stats can't satisfy its own req).
                StatSnapshot withoutSelf = s.swap(p, null);
                if (!hooks.meetsReqs(p, withoutSelf.job(), withoutSelf.level(),
                        withoutSelf.str(), withoutSelf.dex(), withoutSelf.int_(),
                        withoutSelf.luk(), withoutSelf.fame())) {
                    s = withoutSelf;
                    hp -= p.getHp();
                    mp -= p.getMp();
                    statSum -= usefulStatSum(p, s.job());
                    picks[i] = null;
                    changed = true;
                }
            }
        }
        if (weapon != null) {
            StatSnapshot withoutWeapon = s.swap(weapon, null);
            if (!hooks.meetsReqs(weapon, withoutWeapon.job(), withoutWeapon.level(),
                    withoutWeapon.str(), withoutWeapon.dex(), withoutWeapon.int_(),
                    withoutWeapon.luk(), withoutWeapon.fame())) return null;
        }
        return new DpNode(s, hp, mp, statSum, picks);
    }

    private static EquipScore scoreNode(DpNode node, Equip weapon, WeaponType wt, MapDamageProfile mob) {
        if (isMageJob(node.snap.job())) {
            return new EquipScore(magicScore(node.snap), node.statSum);
        }
        if (wt == null) return new EquipScore(0, node.statSum);
        int dmg = damageWith(node.snap, null, wt, mob);
        int cycleMs = weapon != null ? weaponCycleMs(weapon.getItemId()) : 0;
        if (cycleMs > 0) dmg = (int) (dmg * 1000.0 / cycleMs);
        return new EquipScore(dmg, node.statSum);
    }

    private static StatSnapshot snapshotForBranch(StatSnapshot naked, Equip weapon, Map<Short, Equip> picks) {
        StatSnapshot snap = weapon != null ? naked.swap(null, weapon) : naked;
        for (Equip pick : picks.values()) {
            if (pick != null) {
                snap = snap.swap(null, pick);
            }
        }
        return snap;
    }

    private static WeaponScoreBreakdown weaponScoreBreakdown(StatSnapshot sim, Equip weapon, WeaponType wt,
                                                             MapDamageProfile mob) {
        if (isMageJob(sim.job()) || wt == null) {
            return new WeaponScoreBreakdown(0, 0, 0, 0);
        }
        int rawMax = rawPhysicalMax(sim, wt);
        int preCycleDamage = damageWith(sim, null, wt, mob);
        int cycleMs = weapon != null ? weaponCycleMs(weapon.getItemId()) : 0;
        int normalizedDamage = preCycleDamage;
        if (cycleMs > 0) {
            normalizedDamage = (int) (preCycleDamage * 1000.0 / cycleMs);
        }
        return new WeaponScoreBreakdown(rawMax, preCycleDamage, cycleMs, normalizedDamage);
    }

    /** Naked stat snapshot: bot totals minus all currently-equipped non-cash gear. */
    private static StatSnapshot nakedBase(Character bot, ItemInformationProvider ii, Inventory eqdInv) {
        StatSnapshot sim = StatSnapshot.of(bot);
        for (Item it : eqdInv.list()) {
            if (it instanceof Equip e && !ii.isCash(e.getItemId())) sim = sim.swap(e, null);
        }
        return sim;
    }

    /**
     * Emits equip moves to realize the optimizer's chosen set. Conservative: only emits a
     * move when the target differs from the currently-equipped item AND the target sits in
     * the EQUIP inventory (positive position). Manipulator handles displacement of the old
     * item (and 2H↔shield / overall↔pants auto-unequips). Does NOT proactively unequip
     * gear when target is empty — that would downgrade without a replacement.
     */
    /** Returns true when any move was issued (the worn set actually changed). */
    private static boolean applyEquipPlan(Character bot, ItemInformationProvider ii, Inventory eqdInv,
                                        Map<Short, Equip> currentBySlot, Map<Short, Equip> picks,
                                        Equip targetWeapon, List<Short> dpSlots) {
        // Order: weapon first (handles 2H↔1H eviction), overall before pants, then others.
        List<Short> order = new ArrayList<>();
        order.add((short) -11);
        if (dpSlots.contains((short) -5)) order.add((short) -5);
        if (dpSlots.contains((short) -6)) order.add((short) -6);
        for (Short s : dpSlots) {
            if (s != (short) -5 && s != (short) -6) order.add(s);
        }
        Map<Short, Equip> full = new HashMap<>(picks);
        full.put((short) -11, targetWeapon);
        boolean moved = false;
        for (Short slot : order) {
            Equip target = full.get(slot);
            Equip current = currentBySlot.get(slot);
            if (target == null) continue;
            if (target == current) continue;
            short pos = target.getPosition();
            if (pos <= 0) continue; // already in an EQUIPPED slot — skip to avoid swap loops
            InventoryManipulator.handleItemMove(bot.getClient(), InventoryType.EQUIP,
                    pos, slot, (short) 1);
            moved = true;
        }
        return moved;
    }

    /**
     * True if the bot meets job/level/fame for {@code equip} but fails only on stat
     * requirements. Used to seed the weapon lookahead pool.
     */
    static boolean statOnlyBlocked(Character bot, ItemInformationProvider ii, Equip equip) {
        // Pass huge stat values: if it still fails, level/job/fame is blocking, skip.
        return statOnlyBlocked(bot, EquipUsefulnessHooks.from(ii), equip);
    }

    static boolean statOnlyBlocked(Character bot, EquipUsefulnessHooks hooks, Equip equip) {
        // Pass huge stat values: if it still fails, level/job/fame is blocking, skip.
        return hooks.meetsReqs(equip, bot.getJob(), bot.getLevel(),
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, bot.getFame());
    }

    /** Current level/fame wearability gate: only stat reqs are treated as satisfiable by gear. */
    static boolean isOwnClassEquip(Character bot, ItemInformationProvider ii, Equip equip) {
        return ii.meetsEquipRequirements(equip, bot.getJob(), bot.getLevel(),
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, bot.getFame());
    }

    static boolean futureOnlyBlocked(Character bot, ItemInformationProvider ii, Equip equip) {
        // Pass huge stat values only: if it still fails, job/level/fame is blocking, skip.
        return ii.meetsEquipRequirements(equip, bot.getJob(), bot.getLevel(),
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, bot.getFame());
    }

    private static boolean isRecommendationCandidate(Character bot, ItemInformationProvider ii, Equip equip,
                                                     short primarySlot, RecommendationScope scope) {
        if (scope == RecommendationScope.IMMEDIATE) {
            return ii.canWearEquipment(bot, equip, primarySlot) || statOnlyBlocked(bot, ii, equip);
        }
        return futureOnlyBlocked(bot, ii, equip);
    }

    private static Map<Short, List<Equip>> collectFutureEquipCandidates(
            Character bot, ItemInformationProvider ii, Inventory eqpInv, Inventory eqdInv) {
        Map<Short, List<Equip>> bySlot = new LinkedHashMap<>();
        for (Item item : eqpInv.list()) {
            if (ii.isCash(item.getItemId())) continue;
            if (!(item instanceof Equip equip)) continue;
            String textSlot = ii.getEquipmentSlot(item.getItemId());
            EquipSlot eslot = EquipSlot.getFromTextSlot(textSlot);
            if (eslot == null || eslot == EquipSlot.PET_EQUIP) continue;
            short primary = (short) eslot.getPrimarySlot();
            if (primary == 0) continue;
            if (primary == (short) -11
                    && !isWeaponCompatible(bot, ii.getWeaponType(equip.getItemId()))) continue;
            if (!futureOnlyBlocked(bot, ii, equip)) continue;
            short key = isRingSlot(primary) ? (short) -12 : primary;
            bySlot.computeIfAbsent(key, k -> new ArrayList<>()).add(equip);
        }
        for (Item it : eqdInv.list()) {
            if (!(it instanceof Equip e) || ii.isCash(e.getItemId())) continue;
            short pos = e.getPosition();
            if (pos == (short) -11
                    && !isWeaponCompatible(bot, ii.getWeaponType(e.getItemId()))) continue;
            if (!futureOnlyBlocked(bot, ii, e)) continue;
            short key = isRingSlot(pos) ? (short) -12 : pos;
            List<Equip> pool = bySlot.computeIfAbsent(key, k -> new ArrayList<>());
            if (!pool.contains(e)) pool.add(e);
        }
        for (Map.Entry<Short, List<Equip>> e : bySlot.entrySet()) {
            e.setValue(pruneDominated(ii, e.getValue()));
        }
        return bySlot;
    }

    static List<EquipRecommendation> findRecommendedEquips(Character receiver, Character holder) {
        return findRecommendedEquips(receiver, holder, RecommendationScope.IMMEDIATE);
    }

    static List<EquipRecommendation> findFutureRecommendedEquips(Character receiver, Character holder) {
        return findRecommendedEquips(receiver, holder, RecommendationScope.FUTURE);
    }

    static List<EquipRecommendation> findRecommendedEquipsFromItems(Character receiver, Collection<Equip> holderItems) {
        return buildRecommendations(receiver, holderItems, RecommendationScope.IMMEDIATE);
    }

    static List<EquipRecommendation> findFutureRecommendedEquipsFromItems(Character receiver, Collection<Equip> holderItems) {
        return buildRecommendations(receiver, holderItems, RecommendationScope.FUTURE);
    }

    private static List<EquipRecommendation> findRecommendedEquips(Character receiver, Character holder,
                                                                   RecommendationScope scope) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Inventory holderEquipInv = holder.getInventory(InventoryType.EQUIP);

        // Single source of truth: the autoEquip DP. Add the holder's tradeable items (incl.
        // rings) to the receiver's pool, run the optimizer, and recommend only the picks that
        // came from the holder. This guarantees the bot never asks for an item it wouldn't
        // actually equip.
        Set<Equip> holderItems = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Item item : holderEquipInv.list()) {
            if (!(item instanceof Equip equip) || ii.isCash(item.getItemId())) continue;
            if (item.isUntradeable() && !YamlConfig.config.server.UNTRADEABLE_ITEMS_TRADEABLE) continue;
            String textSlot = ii.getEquipmentSlot(equip.getItemId());
            if (textSlot == null) continue;
            EquipSlot eslot = EquipSlot.getFromTextSlot(textSlot);
            if (eslot == null || eslot == EquipSlot.PET_EQUIP) continue;
            if (eslot.getPrimarySlot() == 0) continue;
            short primarySlot = (short) eslot.getPrimarySlot();
            if (primarySlot == (short) -11
                    && !isWeaponCompatible(receiver, ii.getWeaponType(equip.getItemId()))) continue;
            if (!isRecommendationCandidate(receiver, ii, equip, primarySlot, scope)) continue;
            holderItems.add(equip);
        }

        return buildRecommendations(receiver, holderItems, scope);
    }

    private static List<EquipRecommendation> buildRecommendations(Character receiver, Collection<Equip> holderItems,
                                                                  RecommendationScope scope) {
        Inventory receiverEquippedInv = receiver.getInventory(InventoryType.EQUIPPED);
        List<EquipRecommendation> recommendations = new ArrayList<>();
        OptimizerResult opt = runOptimizerWithExtras(receiver, holderItems, scope);
        if (opt.weapon() != null && holderItems.contains(opt.weapon())) {
            Equip cur = (Equip) receiverEquippedInv.getItem((short) -11);
            recommendations.add(new EquipRecommendation((short) -11, cur, opt.weapon()));
        }
        for (Map.Entry<Short, Equip> p : opt.picks().entrySet()) {
            if (holderItems.contains(p.getValue())) {
                Equip cur = (Equip) receiverEquippedInv.getItem(p.getKey());
                recommendations.add(new EquipRecommendation(p.getKey(), cur, p.getValue()));
            }
        }
        return recommendations;
    }

    static List<Item> collectRecommendedItems(Character receiver, Character holder) {
        return new ArrayList<>(findRecommendedEquips(receiver, holder).stream()
                .map(EquipRecommendation::candidate)
                .toList());
    }

    static EquipRecommendation findRecommendationForItem(Character receiver, Character holder, Item holderItem) {
        return findRecommendationForItem(receiver, holder, holderItem, RecommendationScope.IMMEDIATE);
    }

    static EquipRecommendation findFutureRecommendationForItem(Character receiver, Character holder, Item holderItem) {
        return findRecommendationForItem(receiver, holder, holderItem, RecommendationScope.FUTURE);
    }

    private static EquipRecommendation findRecommendationForItem(Character receiver, Character holder, Item holderItem,
                                                                RecommendationScope scope) {
        if (!(holderItem instanceof Equip candidate)) return null;

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ii.isCash(candidate.getItemId())) return null;
        if (holderItem.isUntradeable() && !YamlConfig.config.server.UNTRADEABLE_ITEMS_TRADEABLE) return null;

        String textSlot = ii.getEquipmentSlot(candidate.getItemId());
        if (textSlot == null) return null;
        EquipSlot slot = EquipSlot.getFromTextSlot(textSlot);
        if (slot == null || slot == EquipSlot.PET_EQUIP) return null;
        short primarySlot = (short) slot.getPrimarySlot();
        if (primarySlot == 0) return null;
        if (primarySlot == (short) -11
                && !isWeaponCompatible(receiver, ii.getWeaponType(candidate.getItemId()))) return null;
        if (!isRecommendationCandidate(receiver, ii, candidate, primarySlot, scope)) return null;

        // Cheap dominance pre-filter for IMMEDIATE scope: if the candidate is Pareto-dominated
        // by what's already worn in its slot (over the receiver's job-relevant stats), the DP
        // cannot choose it as a strict upgrade. Skipping the DP setup here turns the
        // owner-pickup hot path (notifyOwnerGainedEquip) from O(K equips × N bots) full
        // optimizer runs into a fast in-slot dominance check for the common trash-loot case.
        // Cross-slot rescues are excluded by construction since relevant stats are job-trimmed.
        if (scope == RecommendationScope.IMMEDIATE && !isEquipUsefulToBot(receiver, ii, candidate)) {
            return null;
        }

        Inventory receiverEquippedInv = receiver.getInventory(InventoryType.EQUIPPED);

        // Run the DP with the candidate added to the receiver's pool — recommend iff the
        // optimizer actually chose to equip it. This is the same oracle autoEquip uses, so
        // the bot only requests items it would put on.
        OptimizerResult opt = runOptimizerWithExtras(receiver, List.of(candidate), scope);
        if (opt.weapon() == candidate) {
            Equip cur = (Equip) receiverEquippedInv.getItem((short) -11);
            return new EquipRecommendation((short) -11, cur, candidate);
        }
        for (Map.Entry<Short, Equip> p : opt.picks().entrySet()) {
            if (p.getValue() == candidate) {
                Equip cur = (Equip) receiverEquippedInv.getItem(p.getKey());
                return new EquipRecommendation(p.getKey(), cur, candidate);
            }
        }
        return null;
    }

    static boolean shouldReserveOwnedItem(Character bot, Item item) {
        if (item instanceof Equip equip) {
            return shouldReserveOwnedItem(bot, ItemInformationProvider.getInstance(), equip);
        }
        return findRecommendationForItem(bot, bot, item, RecommendationScope.IMMEDIATE) != null
                || findRecommendationForItem(bot, bot, item, RecommendationScope.FUTURE) != null;
    }

    static boolean shouldReserveOwnedItem(Character bot, EquipUsefulnessHooks hooks, Equip item) {
        return isEquipUsefulToBot(bot, hooks, item);
    }

    static boolean shouldReserveOwnedItem(Character bot, ItemInformationProvider ii, Equip item) {
        return selectOwnedItemsForSelfReserve(bot, SelfReserveHooks.from(ii), collectOwnedEquips(bot, ii)).contains(item);
    }

    static boolean wouldReserveIncomingItem(Character bot, ItemInformationProvider ii, Equip item) {
        List<Equip> owned = collectOwnedEquips(bot, ii);
        owned.add(item);
        return selectOwnedItemsForSelfReserve(bot, SelfReserveHooks.from(ii), owned).contains(item);
    }

    static boolean isEquipUsefulToBot(Character recipient, ItemInformationProvider ii, Equip item) {
        return isEquipUsefulToBot(recipient, EquipUsefulnessHooks.from(ii), item);
    }

    /**
     * Stat dimensions that count when deciding whether to reserve a bag item for the bot's
     * own future use. Excludes WDEF/MDEF/HP/MP/AVD/SPD/JUMP — those don't drive the bot's
     * combat output and shouldn't override "trade away" for trash gear.
     */
    enum RelevantStat {
        STR, DEX, INT, LUK, WATK, MATK, ACC;
        int of(Equip e) {
            return switch (this) {
                case STR  -> e.getStr();
                case DEX  -> e.getDex();
                case INT  -> e.getInt();
                case LUK  -> e.getLuk();
                case WATK -> e.getWatk();
                case MATK -> e.getMatk();
                case ACC  -> e.getAcc();
            };
        }
    }

    private static final EnumSet<RelevantStat> ALL_RELEVANT_STATS = EnumSet.allOf(RelevantStat.class);

    /**
     * Per-class stat tracks to consider when reserving items as "I might want this later".
     * Warriors and STR-pirates need ACC (low natural DEX); DEX-based classes get ACC from
     * stat scaling and don't need it on gear.
     */
    static EnumSet<RelevantStat> relevantStatsFor(Job job) {
        if (job == null) return ALL_RELEVANT_STATS.clone();
        if (isMageJob(job)) return EnumSet.of(RelevantStat.INT, RelevantStat.LUK, RelevantStat.MATK);
        int id = job.getId();
        int branch = id / 100;
        if (branch == 1 || branch == 11 || branch == 21)
            return EnumSet.of(RelevantStat.STR, RelevantStat.DEX, RelevantStat.WATK, RelevantStat.ACC);
        if (branch == 3 || branch == 13)
            return EnumSet.of(RelevantStat.DEX, RelevantStat.STR, RelevantStat.WATK);
        if (branch == 4 || branch == 14)
            return EnumSet.of(RelevantStat.LUK, RelevantStat.DEX, RelevantStat.WATK);
        if (id == 500)
            return EnumSet.of(RelevantStat.STR, RelevantStat.DEX, RelevantStat.WATK, RelevantStat.ACC);
        if (id / 10 == 51 || branch == 15)
            return EnumSet.of(RelevantStat.STR, RelevantStat.DEX, RelevantStat.WATK, RelevantStat.ACC);
        if (id / 10 == 52)
            return EnumSet.of(RelevantStat.DEX, RelevantStat.STR, RelevantStat.WATK);
        return ALL_RELEVANT_STATS.clone();
    }

    static Set<Equip> selectItemsBeatingBaseline(EnumSet<RelevantStat> relevant,
                                                 Collection<Equip> bagItems,
                                                 Collection<Equip> baseline) {
        Set<Equip> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Equip c : bagItems) {
            if (!hasPositiveRelevant(relevant, c)) continue;
            if (anyDominates(relevant, baseline, c)) continue;
            keep.add(c);
        }
        return keep;
    }

    private static boolean hasPositiveRelevant(EnumSet<RelevantStat> relevant, Equip e) {
        for (RelevantStat s : relevant) if (s.of(e) > 0) return true;
        return false;
    }

    private static boolean anyDominates(EnumSet<RelevantStat> relevant, Collection<Equip> pool, Equip c) {
        for (Equip o : pool) {
            if (o == c) continue;
            if (paretoDominates(relevant, o, c)) return true;
        }
        return false;
    }

    private static boolean paretoDominates(EnumSet<RelevantStat> relevant, Equip a, Equip b) {
        RelevantStat primary = primaryStatFor(relevant);
        boolean strictlyGreater = false;
        for (RelevantStat s : relevant) {
            int va = effectiveStatValue(s, primary, a), vb = effectiveStatValue(s, primary, b);
            if (va < vb) return false;
            if (va > vb) strictlyGreater = true;
        }
        return strictlyGreater;
    }

    // For classes that score gear ACC (warriors, STR-pirates), DEX also contributes to
    // in-game accuracy via stat scaling. The client uses ~0.8 acc per DEX, but DEX is more
    // generally useful, so for dominance we weight DEX 1:1 with ACC — a DEX-heavy item can
    // outclass a low-ACC item even with zero raw ACC.
    private static int effectiveStatValue(RelevantStat s, RelevantStat primary, Equip e) {
        if (s == RelevantStat.ACC) return e.getAcc() + e.getDex();
        if (primary != null && s == RelevantStat.WATK) {
            return e.getWatk() + primary.of(e) / 5;
        }
        if (s == primary) {
            return s.of(e) + e.getWatk() * 2;
        }
        return s.of(e);
    }

    private static RelevantStat primaryStatFor(EnumSet<RelevantStat> relevant) {
        if (relevant == null || !relevant.contains(RelevantStat.WATK)) return null;
        if (relevant.contains(RelevantStat.LUK)) return RelevantStat.LUK;
        if (relevant.contains(RelevantStat.STR) && relevant.contains(RelevantStat.ACC)) return RelevantStat.STR;
        if (relevant.contains(RelevantStat.DEX)) return RelevantStat.DEX;
        if (relevant.contains(RelevantStat.STR)) return RelevantStat.STR;
        return null;
    }

    static boolean isEquipUsefulToBot(Character recipient, EquipUsefulnessHooks hooks, Equip item) {
        if (!isOwnClassEquip(recipient, hooks, item)) return false;
        String slot = textSlotKey(hooks, item);
        if (slot == null) return false;
        String weaponTrack = null;
        if (isWeaponSlot(slot)) {
            weaponTrack = weaponUsefulnessTrackKey(recipient, hooks.getWeaponType(item.getItemId()));
            if (weaponTrack == null) return false;
        }
        EnumSet<RelevantStat> relevant = relevantStatsFor(recipient.getJob());
        if (!hasPositiveRelevant(relevant, item)) return false;
        Inventory equippedInv = recipient.getInventory(InventoryType.EQUIPPED);
        List<Equip> baseline = new ArrayList<>();
        for (Item it : equippedInv.list()) {
            if (!(it instanceof Equip e) || hooks.isCash(e.getItemId())) continue;
            if (!slot.equals(textSlotKey(hooks, e))) continue;
            if (weaponTrack != null) {
                String equippedTrack = weaponUsefulnessTrackKey(recipient, hooks.getWeaponType(e.getItemId()));
                if (!weaponTrack.equals(equippedTrack)) continue;
            }
            baseline.add(e);
        }
        return !anyDominates(relevant, baseline, item);
    }

    static Set<Item> collectPotentialSelfUpgradeItems(Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Set<Item> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Equip> bagEquips = collectOwnedBagEquips(bot, ii);
        Set<Equip> selected = selectOwnedItemsForSelfReserve(bot, SelfReserveHooks.from(ii), collectOwnedEquips(bot, ii));
        for (Equip equip : bagEquips) {
            if (selected.contains(equip)) {
                keep.add(equip);
            }
        }
        return keep;
    }

    static Set<Equip> selectOwnedItemsForSelfReserve(Character bot, ItemInformationProvider ii,
                                                     Collection<Equip> ownedItems) {
        return selectOwnedItemsForSelfReserve(bot, SelfReserveHooks.from(ii), ownedItems);
    }

    static Set<Equip> selectOwnedItemsForSelfReserve(Character bot, SelfReserveHooks hooks,
                                                     Collection<Equip> ownedItems) {
        EnumSet<RelevantStat> relevant = relevantStatsFor(bot.getJob());
        Map<String, List<Equip>> byTrack = new LinkedHashMap<>();
        for (Equip equip : ownedItems) {
            if (equip == null || hooks.isCash(equip.getItemId())) continue;
            if (!isFutureOwnClassEquip(bot, hooks, equip)) continue;
            if (!hasPositiveRelevant(relevant, equip) && maxScrollReserveUpside(hooks, bot, equip) <= 0.0) continue;
            String track = selfReserveTrackKey(bot, hooks, equip);
            if (track == null) continue;
            byTrack.computeIfAbsent(track, ignored -> new ArrayList<>()).add(equip);
        }

        Set<Equip> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<Equip> trackItems : byTrack.values()) {
            List<Equip> survivors = new ArrayList<>();
            for (Equip candidate : trackItems) {
                boolean dominated = false;
                for (Equip other : trackItems) {
                    if (other == candidate) continue;
                    if (dominatesForSelfReserve(hooks, relevant, bot, other, candidate)) {
                        dominated = true;
                        break;
                    }
                }
                if (!dominated) survivors.add(candidate);
            }
            keep.addAll(capSelfReserveTrack(hooks, bot, survivors));
        }
        return keep;
    }

    /**
     * Pareto width per track is unbounded (incomparable stat mixes and the ceiling gate keep
     * almost everything), so after dominance filtering keep only the top few survivors ranked
     * by self-reserve ceiling. The ceiling already counts scroll upside from open upgrade
     * slots, so high-slot scroll projects still survive. Demoted items are NOT lost: they flow
     * into the sell-trash pipeline where good rolls are still kept on the bounded valuables
     * shelf in BotInventoryManager.
     */
    static final int SELF_RESERVE_TRACK_CAP = 3;

    private static List<Equip> capSelfReserveTrack(SelfReserveHooks hooks, Character bot, List<Equip> survivors) {
        if (survivors.size() <= SELF_RESERVE_TRACK_CAP) return survivors;
        List<Equip> ranked = new ArrayList<>(survivors);
        ranked.sort(Comparator
                .comparingDouble((Equip e) -> -selfReserveCeiling(hooks, bot, e))
                .thenComparingInt(e -> -usefulStatSum(e, bot.getJob()))
                .thenComparingInt(e -> -e.getUpgradeSlots()));
        return ranked.subList(0, SELF_RESERVE_TRACK_CAP);
    }

    private static List<Equip> collectOwnedEquips(Character bot, ItemInformationProvider ii) {
        List<Equip> owned = new ArrayList<>();
        owned.addAll(collectOwnedBagEquips(bot, ii));
        Inventory equippedInv = bot.getInventory(InventoryType.EQUIPPED);
        for (Item item : equippedInv.list()) {
            if (item instanceof Equip equip && !ii.isCash(equip.getItemId())) owned.add(equip);
        }
        return owned;
    }

    private static List<Equip> collectOwnedBagEquips(Character bot, ItemInformationProvider ii) {
        List<Equip> owned = new ArrayList<>();
        Inventory equipInv = bot.getInventory(InventoryType.EQUIP);
        for (Item item : equipInv.list()) {
            if (item instanceof Equip equip && !ii.isCash(equip.getItemId())) owned.add(equip);
        }
        return owned;
    }

    private static String selfReserveTrackKey(Character bot, EquipUsefulnessHooks hooks, Equip equip) {
        String slot = textSlotKey(hooks, equip);
        if (slot == null) return null;
        if (!isWeaponSlot(slot)) return slot;
        String weaponTrack = weaponUsefulnessTrackKey(bot, hooks.getWeaponType(equip.getItemId()));
        return weaponTrack != null ? slot + ":" + weaponTrack : null;
    }

    private static String weaponUsefulnessTrackKey(Character bot, WeaponType weaponType) {
        if (!isWeaponCompatible(bot, weaponType)) return null;
        if (weaponType == null || weaponType == WeaponType.NOT_A_WEAPON) return "non-weapon";
        if (isSword(weaponType)) return "sword";
        if (isGeneralWeapon(weaponType)) return "general";
        if (isSpearWeapon(weaponType)) return "spear";
        if (isPolearmWeapon(weaponType)) return "polearm";
        if (isThiefDagger(weaponType)) return "thief-dagger";
        return switch (weaponType) {
            case BOW -> "bow";
            case CROSSBOW -> "crossbow";
            case CLAW -> "claw";
            case GUN -> "gun";
            case KNUCKLE -> "knuckle";
            case WAND -> "wand";
            case STAFF -> "staff";
            default -> weaponType.name();
        };
    }

    private static boolean dominatesForSelfReserve(SelfReserveHooks hooks, EnumSet<RelevantStat> relevant,
                                                   Character bot, Equip better, Equip worse) {
        boolean relevantDominates = paretoDominates(relevant, better, worse);
        boolean tieBreakDominates = relevantStatsEqual(relevant, better, worse)
                && selfReserveTieBreak(hooks, bot, better, worse) > 0;
        if (!relevantDominates && !tieBreakDominates) return false;
        if (!reqsAtLeastAsEasy(hooks, better, worse) && !currentlyWearable(bot, hooks, better)) return false;
        if (selfReserveCeiling(hooks, bot, better) < selfReserveCeiling(hooks, bot, worse)) return false;
        return true;
    }

    private static int selfReserveTieBreak(SelfReserveHooks hooks, Character bot, Equip better, Equip worse) {
        if (!sameRequirementSignature(hooks, better, worse)) return 0;
        int betterScore = usefulStatSum(better, bot.getJob());
        int worseScore = usefulStatSum(worse, bot.getJob());
        if (betterScore != worseScore) return Integer.compare(betterScore, worseScore);
        if (better.getUpgradeSlots() != worse.getUpgradeSlots()) {
            return Integer.compare(better.getUpgradeSlots(), worse.getUpgradeSlots());
        }
        int betterPos = better.getPosition();
        int worsePos = worse.getPosition();
        if (betterPos != worsePos && betterPos > 0 && worsePos > 0) return Integer.compare(worsePos, betterPos);
        return Integer.compare(System.identityHashCode(worse), System.identityHashCode(better));
    }

    private static double selfReserveCeiling(SelfReserveHooks hooks, Character bot, Equip equip) {
        return BotScrollManager.offenseValue(bot, equip) + maxScrollReserveUpside(hooks, bot, equip);
    }

    private static double maxScrollReserveUpside(SelfReserveHooks hooks, Character bot, Equip equip) {
        return Math.max(0, equip.getUpgradeSlots())
                * Math.max(0.0, hooks.maxScrollOffenseGainPerSlot(bot, equip.getItemId()));
    }

    private static boolean currentlyWearable(Character bot, EquipUsefulnessHooks hooks, Equip equip) {
        return hooks.meetsReqs(equip, bot.getJob(), bot.getLevel(),
                bot.getTotalStr(), bot.getTotalDex(), bot.getTotalInt(), bot.getTotalLuk(), bot.getFame());
    }

    private static boolean relevantStatsEqual(EnumSet<RelevantStat> relevant, Equip a, Equip b) {
        for (RelevantStat stat : relevant) {
            if (stat.of(a) != stat.of(b)) return false;
        }
        return true;
    }

    private static boolean sameRequirementSignature(SelfReserveHooks hooks, Equip a, Equip b) {
        if (hooks.getEquipLevelReq(a.getItemId()) != hooks.getEquipLevelReq(b.getItemId())) return false;
        Map<String, Integer> as = hooks.getEquipStats(a.getItemId());
        Map<String, Integer> bs = hooks.getEquipStats(b.getItemId());
        if (as == null || bs == null) return as == bs;
        for (String key : new String[]{"reqJob", "reqSTR", "reqDEX", "reqINT", "reqLUK", "reqPOP"}) {
            if (as.getOrDefault(key, 0).intValue() != bs.getOrDefault(key, 0).intValue()) return false;
        }
        return true;
    }

    private static boolean isOwnClassEquip(Character bot, EquipUsefulnessHooks hooks, Equip equip) {
        int level = bot.getLevel() > 0 ? bot.getLevel() : Short.MAX_VALUE;
        return hooks.meetsReqs(equip, bot.getJob(), level,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, bot.getFame());
    }

    private static boolean isFutureOwnClassEquip(Character bot, EquipUsefulnessHooks hooks, Equip equip) {
        return hooks.meetsReqs(equip, bot.getJob(), Short.MAX_VALUE,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4,
                Integer.MAX_VALUE / 4, Integer.MAX_VALUE / 4, Short.MAX_VALUE);
    }

    private static String textSlotKey(EquipUsefulnessHooks hooks, Equip equip) {
        String textSlot = hooks.getEquipmentSlot(equip.getItemId());
        if (textSlot == null) return null;
        EquipSlot slot = EquipSlot.getFromTextSlot(textSlot);
        if (slot == null || slot == EquipSlot.PET_EQUIP) return null;
        return textSlot;
    }

    private static String textSlotKey(ItemInformationProvider ii, Equip equip) {
        return textSlotKey(EquipUsefulnessHooks.from(ii), equip);
    }

    private static boolean isWeaponSlot(String textSlot) {
        // Weapons can come back as "Wp" (1H), "WpSi" (2H — occupies shield slot), or "WpSp"
        // (LOW_WEAPON). All three resolve to the -11 weapon slot and must be routed through
        // the weapon-type compatibility check (isWeaponCompatible).
        return "Wp".equals(textSlot) || "WpSi".equals(textSlot) || "WpSp".equals(textSlot);
    }

    private static boolean meetsReqsNaked(Character bot, ItemInformationProvider ii,
                                          StatSnapshot naked, Equip equip) {
        return ii.meetsEquipRequirements(equip, bot.getJob(), bot.getLevel(),
                naked.str(), naked.dex(), naked.int_(), naked.luk(), bot.getFame());
    }

    static String recommendationSummary(Character receiver, Character holder, int maxItems) {
        List<EquipRecommendation> recommendations = findRecommendedEquips(receiver, holder);
        if (recommendations.isEmpty()) {
            return null;
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        StringBuilder summary = new StringBuilder("better gear for you: ");
        int count = Math.min(maxItems, recommendations.size());
        for (int i = 0; i < count; i++) {
            EquipRecommendation recommendation = recommendations.get(i);
            if (i > 0) {
                summary.append(", ");
            }
            summary.append(slotLabel(recommendation.targetSlot()))
                    .append(" -> ")
                    .append(ii.getName(recommendation.candidate().getItemId()));
        }
        if (recommendations.size() > count) {
            summary.append(" +").append(recommendations.size() - count).append(" more");
        }
        return summary.toString();
    }

    static String unequipAll(Character bot) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Inventory eqpInv = bot.getInventory(InventoryType.EQUIP);
        Inventory eqdInv = bot.getInventory(InventoryType.EQUIPPED);

        List<Short> equippedSlots = new ArrayList<>();
        for (Item item : eqdInv.list()) {
            if (ii.isCash(item.getItemId())) continue;
            equippedSlots.add(item.getPosition());
        }
        if (equippedSlots.isEmpty()) return "nothing to unequip";

        int freeSlots = eqpInv.getNumFreeSlot();
        if (freeSlots < equippedSlots.size()) {
            return "need " + equippedSlots.size() + " free equip slots, only have " + freeSlots;
        }

        equippedSlots.sort(Short::compare);
        for (short src : equippedSlots) {
            short dst = eqpInv.getNextFreeSlot();
            if (dst < 0) {
                return "ran out of equip slots while unequipping";
            }
            InventoryManipulator.handleItemMove(bot.getClient(), InventoryType.EQUIP, src, dst, (short) 1);
        }
        return "unequipped " + equippedSlots.size() + " item" + (equippedSlots.size() != 1 ? "s" : "");
    }

    /**
     * Unequips any currently-worn non-cash item whose reqs no longer hold against the bot's
     * current totals. Used after {@link #applyEquipPlan} to clean up gear the optimizer chose
     * to leave bare (e.g. boots whose dex prereq was satisfied only by a now-removed overall).
     */
    private static void unequipInfeasibleEquipped(Character bot, ItemInformationProvider ii) {
        Inventory eqdInv = bot.getInventory(InventoryType.EQUIPPED);
        List<Short> bad = new ArrayList<>();
        for (Item it : eqdInv.list()) {
            if (!(it instanceof Equip e)) continue;
            if (ii.isCash(e.getItemId())) continue;
            if (!ii.canWearEquipment(bot, e, e.getPosition())) bad.add(e.getPosition());
        }
        if (bad.isEmpty()) return;
        short[] arr = new short[bad.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = bad.get(i);
        unequipSlot(bot, arr);
    }

    static String unequipSlot(Character bot, short[] slots) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Inventory eqpInv = bot.getInventory(InventoryType.EQUIP);
        Inventory eqdInv = bot.getInventory(InventoryType.EQUIPPED);

        List<Short> toUnequip = new ArrayList<>();
        for (short slot : slots) {
            Item item = eqdInv.getItem(slot);
            if (item != null && !ii.isCash(item.getItemId())) {
                toUnequip.add(slot);
            }
        }
        if (toUnequip.isEmpty()) {
            return "nothing equipped there";
        }
        if (eqpInv.getNumFreeSlot() < toUnequip.size()) {
            return "equip bag full";
        }
        StringBuilder names = new StringBuilder();
        for (short src : toUnequip) {
            Item item = eqdInv.getItem(src);
            short dst = eqpInv.getNextFreeSlot();
            if (dst < 0) return "ran out of equip slots";
            InventoryManipulator.handleItemMove(bot.getClient(), InventoryType.EQUIP, src, dst, (short) 1);
            if (!names.isEmpty()) names.append(", ");
            names.append(ii.getName(item.getItemId()));
        }
        return "unequipped " + names;
    }

    /** Returns the equipped slot(s) that match the given slot name from chat. Empty array = unknown. */
    static short[] slotsFromName(String name) {
        return switch (name.trim().toLowerCase().replaceAll("\\s+", "")) {
            case "hat", "helm", "helmet" -> new short[]{-1};
            case "face", "faceacc", "faceaccessory" -> new short[]{-2};
            case "eye", "eyeacc", "eyeaccessory", "eyepiece" -> new short[]{-3};
            case "ear", "earring", "earrings" -> new short[]{-4};
            case "top", "shirt", "overall" -> new short[]{-5};
            case "bottom", "pant", "pants" -> new short[]{-6};
            case "shoe", "shoes", "boot", "boots" -> new short[]{-7};
            case "glove", "gloves" -> new short[]{-8};
            case "cape", "capes" -> new short[]{-9};
            case "shield", "shields", "offhand" -> new short[]{-10};
            case "weapon", "weapons", "wep" -> new short[]{-11};
            case "ring" -> RING_SLOTS.clone();
            case "ring1" -> new short[]{-12};
            case "ring2" -> new short[]{-13};
            case "ring3" -> new short[]{-15};
            case "ring4" -> new short[]{-16};
            case "petwear" -> new short[]{-14};
            case "pendant" -> new short[]{-17};
            case "medal" -> new short[]{-49};
            case "belt" -> new short[]{-50};
            default -> new short[0];
        };
    }

    private static int compareScores(EquipScore left, EquipScore right) {
        int cmp = Integer.compare(left.damage(), right.damage());
        if (cmp != 0) return cmp;
        return Integer.compare(left.statSum(), right.statSum());
    }

    /**
     * Computes max-base damage from a simulated stat snapshot, then if a {@link MapDamageProfile}
     * is available approximates expected per-hit damage as the integral of
     * {@code max(1, uniform[min,max] - wdef)} where min ≈ max/2 (low-mastery proxy). Falls back
     * to raw max when no map context (town, recommendations from trade).
     */
    private static int damageWith(StatSnapshot sim, ItemInformationProvider ii, WeaponType wtype,
                                   MapDamageProfile mobProfile) {
        int rawMax = rawPhysicalMax(sim, wtype);
        if (rawMax <= 0) return 0;
        if (mobProfile == null) {
            return rawMax;
        }
        double expectedAfterDef = expectedDamageAfterDef(rawMax, mobProfile.mobWdef());
        double hitChance;
        try {
            hitChance = CombatFormulaProvider.getInstance().calculatePhysicalMobHitChance(
                    sim.totalAcc(), sim.level(), mobProfile.mobLevel(), mobProfile.mobAvoid());
        } catch (Throwable t) {
            hitChance = 1.0;
        }
        // Scale by 1000 so hitChance and small expectedAfterDef differences survive the int cast.
        return Math.max(1, (int) Math.round(expectedAfterDef * hitChance * 1000.0));
    }

    private static int rawPhysicalMax(StatSnapshot sim, WeaponType wtype) {
        if (wtype == null) return 0;
        WeaponType effective = wtype;
        if (sim.job() != null && sim.job().isA(Job.THIEF) && effective == WeaponType.DAGGER_OTHER) {
            effective = WeaponType.DAGGER_THIEVES;
        }
        int main, sec;
        if (effective == WeaponType.BOW || effective == WeaponType.CROSSBOW || effective == WeaponType.GUN) {
            main = sim.dex(); sec = sim.str();
        } else if (effective == WeaponType.CLAW || effective == WeaponType.DAGGER_THIEVES) {
            main = sim.luk(); sec = sim.dex() + sim.str();
        } else {
            main = sim.str(); sec = sim.dex();
        }
        // Spear/polearm: bot alternates stab and swing — average the two multipliers.
        double mult = switch (effective) {
            case SPEAR_STAB     -> (WeaponType.SPEAR_STAB.getMaxDamageMultiplier()    + WeaponType.SPEAR_SWING.getMaxDamageMultiplier())  / 2.0;
            case POLE_ARM_SWING -> (WeaponType.POLE_ARM_SWING.getMaxDamageMultiplier() + WeaponType.POLE_ARM_STAB.getMaxDamageMultiplier()) / 2.0;
            default             -> effective.getMaxDamageMultiplier();
        };
        return (int) Math.ceil((mult * main + sec) / 100.0 * sim.watk());
    }

    /**
     * Expected per-hit damage when each roll is {@code uniform[rawMax/2, rawMax] - wdef} clamped
     * to 1. The previous {@code (rawMin + rawMax)/2 - wdef} approximation collapsed to the floor
     * once wdef exceeded the midpoint, erasing the upper-tail damage that higher STR/WATK
     * actually delivers — so two candidates with different rawMax both scored ≈1 against a
     * high-WDEF mob, even when one cleared the defense and the other barely did.
     */
    static double expectedDamageAfterDef(int rawMax, int wdef) {
        if (rawMax <= 0) return 1.0;
        double rawMin = rawMax * 0.5;
        if (wdef <= rawMin) {
            return Math.max(1.0, (rawMin + rawMax) / 2.0 - wdef);
        }
        if (wdef >= rawMax) {
            return 1.0;
        }
        // Partial clamp: fraction below wdef floors to 1; above-tail integrates as a triangle.
        double range = rawMax - rawMin;
        double clampedFraction = (wdef - rawMin) / range;
        double aboveTail = rawMax - wdef;
        double aboveContribution = (aboveTail * aboveTail) / (2.0 * range);
        return Math.max(1.0, clampedFraction + aboveContribution);
    }

    /**
     * Returns the effective attack cycle in milliseconds for a weapon using the same formula
     * as BotCombatManager.
     * The raw animation comes from the weapon's WZ XML; the speed value scales playback rate,
     * so two weapons with the same speed tier but different base animations have different DPS.
     * Returns 0 if no WZ profile is available — caller skips DPS scaling.
     */
    static int weaponCycleMs(int itemId) {
        try {
            BotAttackDataProvider provider = BotAttackDataProvider.getInstance();
            BotAttackDataProvider.NormalAttackProfile profile = provider.getNormalAttackProfile(itemId);
            if (profile == null) {
                return 0;
            }
            WeaponType weaponType = ItemInformationProvider.getInstance().getWeaponType(itemId);
            BotAttackDataProvider.AttackAnimationSpec attackSpec = provider.getBasicAttackSpec(profile.getAttack(), weaponType);
            int rawAnimationDelayMs = provider.getBodyStanceDurationMs(attackSpec.primaryAction());
            if (rawAnimationDelayMs <= 0) {
                return 0;
            }
            return server.bots.combat.BotAttackTiming.adjustDelayMillis(rawAnimationDelayMs, profile.getAttackSpeed());
        } catch (Throwable t) {
            // WZ data may not be initialized in unit-test contexts; fall back to no DPS scaling.
            return 0;
        }
    }

    private static int defScore(Equip e)  { return e != null ? e.getWdef() + e.getMdef() : 0; }

    static int usefulStatSum(Equip e, Job job) {
        if (e == null) return 0;
        // HP/MP weighted at 0.1× — a few hundred max-HP from gear is worth roughly a single
        // primary-stat point in practice; full-weight HP/MP would crowd out real stat picks.
        int hpmp = (int) Math.round((e.getHp() + e.getMp()) * 0.1d);
        if (isMageJob(job)) {
            return e.getInt() * 5 + e.getMatk() * 4 + e.getLuk()
                    + e.getMdef() + hpmp;
        }
        double sum = e.getStr() + e.getDex() + e.getInt() * 1.1 + e.getLuk()
                   + e.getWatk() * 4
                   + (e.getWdef() + e.getMdef()) * 0.25
                   + e.getAcc() + e.getAvoid() + e.getSpeed()
                   + (e.getHp() + e.getMp()) * 0.1;
        return (int) Math.round(sum);
    }

    private static int magicScore(StatSnapshot sim) {
        // Weights INT and MAGIC almost equally — INT*1.1 nudges main-stat ties toward INT
        // (matches v83 mage growth where INT scales magic damage and unlocks better gear).
        return (int) Math.round(sim.int_() * 1.1d) + sim.magic();
    }

    static boolean isMageJob(Job job) {
        return job == Job.MAGICIAN
                || job == Job.FP_WIZARD || job == Job.FP_MAGE || job == Job.FP_ARCHMAGE
                || job == Job.IL_WIZARD || job == Job.IL_MAGE || job == Job.IL_ARCHMAGE
                || job == Job.CLERIC || job == Job.PRIEST || job == Job.BISHOP;
    }

    private static boolean isRingSlot(short slot) {
        for (short rs : RING_SLOTS) if (slot == rs) return true;
        return false;
    }

    /** One-line, human-readable summary of a chosen equip plan, for the autoequip log. */
    private static String describeEquipPlan(ItemInformationProvider ii, Equip weapon,
                                            EquipScore score, Map<Short, Equip> picks, boolean capHit) {
        StringBuilder sb = new StringBuilder();
        sb.append("weapon ").append(weapon == null ? "(none)" : ii.getName(weapon.getItemId()));
        sb.append(" -> score ").append(score.statSum()).append(", damage ").append(score.damage());
        if (capHit) {
            sb.append(" (frontier cap hit)");
        }
        if (!picks.isEmpty()) {
            sb.append(" [");
            boolean first = true;
            for (Map.Entry<Short, Equip> e : picks.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(slotLabel(e.getKey())).append(": ").append(ii.getName(e.getValue().getItemId()));
            }
            sb.append("]");
        }
        return sb.toString();
    }

    private static String slotLabel(short slot) {
        return switch (slot) {
            case -11 -> "weapon";
            case -10 -> "shield";
            case -9 -> "cape";
            case -8 -> "glove";
            case -7 -> "shoes";
            case -6 -> "pants";
            case -5 -> "top";
            case -4 -> "earring";
            case -3 -> "face";
            case -2 -> "eye";
            case -1 -> "hat";
            case -12, -13, -15, -16 -> "ring";
            case -17 -> "pendant";
            case -18 -> "tamed mob";
            case -19 -> "saddle";
            case -20 -> "medal";
            case -21 -> "belt";
            default -> "slot " + slot;
        };
    }

    static boolean isWeaponCompatible(Character bot, WeaponType weaponType) {
        if (weaponType == null || weaponType == WeaponType.NOT_A_WEAPON) {
            return true;
        }

        Job job = bot.getJob();
        if (job == Job.THIEF) {
            if (bot.getSkillLevel(Rogue.LUCKY_SEVEN) > 0) {
                return weaponType == WeaponType.CLAW;
            }
            if (bot.getSkillLevel(Rogue.DOUBLE_STAB) > 0) {
                return isThiefDagger(weaponType);
            }
        }
        if (job == Job.PIRATE) {
            boolean gunBuild = bot.getSkillLevel(Pirate.DOUBLE_SHOT) > 0;
            boolean knuckleBuild = bot.getSkillLevel(Pirate.FLASH_FIST) > 0
                    || bot.getSkillLevel(Pirate.SOMERSAULT_KICK) > 0;
            if (gunBuild && !knuckleBuild) {
                return weaponType == WeaponType.GUN;
            }
            if (knuckleBuild && !gunBuild) {
                return weaponType == WeaponType.KNUCKLE;
            }
            return weaponType == WeaponType.GUN || weaponType == WeaponType.KNUCKLE;
        }

        return switch (job) {
            case BOWMAN -> weaponType == WeaponType.BOW || weaponType == WeaponType.CROSSBOW;
            case FIGHTER -> matchesWarriorWeaponFamily(bot,
                    isSword(weaponType), isGeneralWeapon(weaponType),
                    new int[]{Fighter.SWORD_MASTERY, Fighter.SWORD_BOOSTER},
                    new int[]{Fighter.AXE_MASTERY, Fighter.AXE_BOOSTER});
            case CRUSADER -> matchesWarriorWeaponFamily(bot,
                    isSword(weaponType), isGeneralWeapon(weaponType),
                    new int[]{Crusader.SWORD_COMA, Crusader.SWORD_PANIC},
                    new int[]{Crusader.AXE_COMA, Crusader.AXE_PANIC});
            case HERO -> matchesWarriorWeaponFamily(bot,
                    isSword(weaponType), isGeneralWeapon(weaponType),
                    new int[]{Crusader.SWORD_COMA, Crusader.SWORD_PANIC},
                    new int[]{Crusader.AXE_COMA, Crusader.AXE_PANIC});
            case PAGE -> matchesWarriorWeaponFamily(bot,
                    isSword(weaponType), isGeneralWeapon(weaponType),
                    new int[]{Page.SWORD_MASTERY, Page.SWORD_BOOSTER},
                    new int[]{Page.BW_MASTERY, Page.BW_BOOSTER});
            case WHITEKNIGHT -> matchesWarriorWeaponFamily(bot,
                    isSword(weaponType), isGeneralWeapon(weaponType),
                    new int[]{WhiteKnight.SWORD_FIRE_CHARGE, WhiteKnight.SWORD_ICE_CHARGE, WhiteKnight.SWORD_LIT_CHARGE},
                    new int[]{WhiteKnight.BW_FIRE_CHARGE, WhiteKnight.BW_ICE_CHARGE, WhiteKnight.BW_LIT_CHARGE});
            case PALADIN -> matchesWarriorWeaponFamily(bot,
                    isSword(weaponType), isGeneralWeapon(weaponType),
                    new int[]{Paladin.SWORD_HOLY_CHARGE},
                    new int[]{Paladin.BW_HOLY_CHARGE});
            case SPEARMAN -> matchesWarriorWeaponFamily(bot,
                    isSpearWeapon(weaponType), isPolearmWeapon(weaponType),
                    new int[]{Spearman.SPEAR_MASTERY, Spearman.SPEAR_BOOSTER},
                    new int[]{Spearman.POLEARM_MASTERY, Spearman.POLEARM_BOOSTER});
            case DRAGONKNIGHT -> matchesWarriorWeaponFamily(bot,
                    isSpearWeapon(weaponType), isPolearmWeapon(weaponType),
                    new int[]{DragonKnight.SPEAR_CRUSHER, DragonKnight.SPEAR_DRAGON_FURY},
                    new int[]{DragonKnight.POLE_ARM_CRUSHER, DragonKnight.POLE_ARM_DRAGON_FURY});
            case DARKKNIGHT -> matchesWarriorWeaponFamily(bot,
                    isSpearWeapon(weaponType), isPolearmWeapon(weaponType),
                    new int[]{DragonKnight.SPEAR_CRUSHER, DragonKnight.SPEAR_DRAGON_FURY},
                    new int[]{DragonKnight.POLE_ARM_CRUSHER, DragonKnight.POLE_ARM_DRAGON_FURY});
            case MAGICIAN, FP_WIZARD, FP_MAGE, FP_ARCHMAGE, IL_WIZARD, IL_MAGE, IL_ARCHMAGE, CLERIC, PRIEST, BISHOP ->
                    weaponType == WeaponType.WAND || weaponType == WeaponType.STAFF;
            case HUNTER, RANGER, BOWMASTER -> weaponType == WeaponType.BOW;
            case CROSSBOWMAN, SNIPER, MARKSMAN -> weaponType == WeaponType.CROSSBOW;
            case ASSASSIN, HERMIT, NIGHTLORD -> weaponType == WeaponType.CLAW;
            case BANDIT, CHIEFBANDIT, SHADOWER -> isThiefDagger(weaponType);
            case BRAWLER, MARAUDER, BUCCANEER -> weaponType == WeaponType.KNUCKLE;
            case GUNSLINGER, OUTLAW, CORSAIR -> weaponType == WeaponType.GUN;
            default -> true;
        };
    }

    private static Equip compatibleWeaponOrNull(Character bot, ItemInformationProvider ii, Equip equip) {
        if (equip == null) {
            return null;
        }
        return isWeaponCompatible(bot, ii.getWeaponType(equip.getItemId())) ? equip : null;
    }

    private static boolean matchesWarriorWeaponFamily(Character bot,
                                                      boolean firstFamilyMatch,
                                                      boolean secondFamilyMatch,
                                                      int[] firstFamilySkills,
                                                      int[] secondFamilySkills) {
        boolean firstFamilyChosen = hasAnySkill(bot, firstFamilySkills);
        boolean secondFamilyChosen = hasAnySkill(bot, secondFamilySkills);
        if (firstFamilyChosen && !secondFamilyChosen) {
            return firstFamilyMatch;
        }
        if (secondFamilyChosen && !firstFamilyChosen) {
            return secondFamilyMatch;
        }
        return firstFamilyMatch || secondFamilyMatch;
    }

    private static boolean hasAnySkill(Character bot, int... skillIds) {
        for (int skillId : skillIds) {
            if (bot.getSkillLevel(skillId) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSword(WeaponType weaponType) {
        return weaponType == WeaponType.SWORD1H || weaponType == WeaponType.SWORD2H;
    }

    private static boolean isGeneralWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.GENERAL1H_SWING
                || weaponType == WeaponType.GENERAL1H_STAB
                || weaponType == WeaponType.GENERAL2H_SWING
                || weaponType == WeaponType.GENERAL2H_STAB;
    }

    private static boolean isSpearWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.SPEAR_STAB || weaponType == WeaponType.SPEAR_SWING;
    }

    private static boolean isPolearmWeapon(WeaponType weaponType) {
        return weaponType == WeaponType.POLE_ARM_SWING || weaponType == WeaponType.POLE_ARM_STAB;
    }

    private static boolean isThiefDagger(WeaponType weaponType) {
        return weaponType == WeaponType.DAGGER_OTHER || weaponType == WeaponType.DAGGER_THIEVES;
    }

    // ---- helper records / pruning ------------------------------------------------------

    /**
     * Snapshot of bot totals plus job/level/fame for non-mutating wearability checks.
     * {@code flatAcc} = total accuracy minus its derived (dex/luk) component, so {@link #swap}
     * can recompute total accuracy after stat changes without re-reading the live bot state.
     */
    record StatSnapshot(int str, int dex, int int_, int luk, int watk, int magic, int flatAcc,
                                int level, int fame, Job job) {
        static StatSnapshot of(Character bot) {
            int totalAcc = CombatFormulaProvider.getInstance().getTotalAccuracy(bot);
            int derived = (int) Math.floor(bot.getTotalDex() * 0.8d + bot.getTotalLuk() * 0.5d);
            int flatAcc = Math.max(0, totalAcc - Math.max(0, derived));
            return new StatSnapshot(bot.getTotalStr(), bot.getTotalDex(), bot.getTotalInt(),
                    bot.getTotalLuk(), bot.getTotalWatk(), bot.getTotalMagic(), flatAcc,
                    bot.getLevel(), bot.getFame(), bot.getJob());
        }

        StatSnapshot swap(Equip removed, Equip added) {
            return new StatSnapshot(
                    str + d(added, removed, e -> (int) e.getStr()),
                    dex + d(added, removed, e -> (int) e.getDex()),
                    int_ + d(added, removed, e -> (int) e.getInt()),
                    luk + d(added, removed, e -> (int) e.getLuk()),
                    watk + d(added, removed, e -> (int) e.getWatk()),
                    magic + d(added, removed, e -> (int) e.getInt()) + d(added, removed, e -> (int) e.getMatk()),
                    flatAcc + d(added, removed, e -> (int) e.getAcc()),
                    level, fame, job);
        }

        int totalAcc() {
            int derived = (int) Math.floor(dex * 0.8d + luk * 0.5d);
            return Math.max(0, derived + flatAcc);
        }

        private static int d(Equip a, Equip r, ToIntFunction<Equip> g) {
            return (a != null ? g.applyAsInt(a) : 0) - (r != null ? g.applyAsInt(r) : 0);
        }
    }

    /**
     * Stats of the highest-level non-friendly mob on the bot's map. Includes currently
     * alive mobs and normal spawn templates, so an equip pass that runs while the room is
     * briefly clear still benchmarks against the map's mobs instead of raw damage.
     * Returns null when no map context is available or no map mobs are present.
     */
    record MapDamageProfile(int mobWdef, int mobAvoid, int mobLevel) {
        static MapDamageProfile snapshot(Character bot) {
            return fromStats(collectCandidates(bot));
        }

        static MapDamageProfile snapshotByAvoid(Character bot) {
            return fromStatsByAvoid(collectCandidates(bot));
        }

        private static List<MonsterStats> collectCandidates(Character bot) {
            if (bot == null) return null;
            MapleMap map;
            try { map = bot.getMap(); } catch (Throwable t) { return null; }
            if (map == null) return null;
            List<MonsterStats> candidates = new ArrayList<>();
            List<Monster> mobs;
            try { mobs = map.getAllMonsters(); } catch (Throwable t) { return null; }
            if (mobs != null) {
                for (Monster m : mobs) {
                    if (m == null || !m.isAlive()) continue;
                    MonsterStats s = m.getStats();
                    if (s != null) candidates.add(s);
                }
            }
            try {
                for (SpawnPoint spawn : map.getMonsterSpawn()) {
                    if (spawn == null || spawn.getDenySpawn() || spawn.getMobTime() < 0) continue;
                    Monster template = LifeFactory.getMonster(spawn.getMonsterId());
                    if (template != null && template.getStats() != null) {
                        candidates.add(template.getStats());
                    }
                }
            } catch (Throwable ignored) {
                // Live mobs are enough; spawn templates are only a fallback/stabilizer.
            }
            return candidates;
        }

        static MapDamageProfile fromStats(List<MonsterStats> candidates) {
            if (candidates == null || candidates.isEmpty()) return null;
            MonsterStats picked = null;
            for (MonsterStats s : candidates) {
                if (s == null || s.isFriendly()) continue;
                if (picked == null
                        || s.getLevel() > picked.getLevel()
                        || (s.getLevel() == picked.getLevel() && s.getAvoidability() > picked.getAvoidability())
                        || (s.getLevel() == picked.getLevel()
                            && s.getAvoidability() == picked.getAvoidability()
                            && s.getPDDamage() > picked.getPDDamage())) {
                    picked = s;
                }
            }
            if (picked == null) return null;
            return new MapDamageProfile(picked.getPDDamage(), picked.getAvoidability(), picked.getLevel());
        }

        static MapDamageProfile fromStatsByAvoid(List<MonsterStats> candidates) {
            if (candidates == null || candidates.isEmpty()) return null;
            MonsterStats picked = null;
            for (MonsterStats s : candidates) {
                if (s == null || s.isFriendly()) continue;
                if (picked == null
                        || s.getAvoidability() > picked.getAvoidability()
                        || (s.getAvoidability() == picked.getAvoidability() && s.getLevel() > picked.getLevel())) {
                    picked = s;
                }
            }
            if (picked == null) return null;
            return new MapDamageProfile(picked.getPDDamage(), picked.getAvoidability(), picked.getLevel());
        }
    }

    /**
     * Drops items strictly dominated by another in {@code items} (every relevant stat ≤,
     * at least one strictly less). Safe within a currently-wearable pool: legality already
     * passed for every item, so the surviving Pareto front preserves all useful trade-offs.
     * Skipped when the pool has 0 or 1 items.
     */
    private static boolean dominates(Equip b, Equip a) {
        int[] bs = statVec(b);
        int[] as = statVec(a);
        boolean strictlyBetter = false;
        for (int i = 0; i < bs.length; i++) {
            if (bs[i] < as[i]) return false;
            if (bs[i] > as[i]) strictlyBetter = true;
        }
        return strictlyBetter;
    }

    private static List<Equip> pruneDominated(ItemInformationProvider ii, List<Equip> items) {
        if (items == null || items.size() <= 1) return items;
        List<Equip> kept = new ArrayList<>(items.size());
        for (Equip a : items) {
            boolean dominated = false;
            for (Equip b : items) {
                if (a == b) continue;
                if (!sameFutureTrack(ii, a, b)) continue;
                if (dominates(b, a)) { dominated = true; break; }
            }
            if (!dominated) kept.add(a);
        }
        return kept.isEmpty() ? items : kept;
    }

    private static List<Equip> pruneDominatedSameTrackWithReqs(ItemInformationProvider ii, List<Equip> items) {
        if (items == null || items.size() <= 1) return items;
        List<Equip> kept = new ArrayList<>(items.size());
        for (Equip a : items) {
            boolean dominated = false;
            for (Equip b : items) {
                if (a == b) continue;
                if (!sameFutureTrack(ii, a, b)) continue;
                if (dominates(b, a) && reqsAtLeastAsEasy(ii, b, a)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) kept.add(a);
        }
        return kept.isEmpty() ? items : kept;
    }

    /**
     * Prune for lookahead pools where reqs differ across items. {@code b} dominates {@code a}
     * only if b's stats ≥ a's AND b's reqs ≤ a's — otherwise a weaker but easier-to-wear
     * item is meaningful and must be retained.
     *
     * <p>Job-aware variant: dim selection focuses on the bot's job-primary stat + watk/magic
     * + job-conditional eff_acc + req-relevant str/dex/int/luk dims. Stats that don't matter
     * to the bot's damage formula and don't gate any candidate's reqs collapse into a
     * combat-effectiveness tiebreaker score. Much tighter than the legacy 14-dim raw vec —
     * shrinks per-slot pools so the DP step downstream sees fewer candidates.
     */
    private static List<Equip> pruneDominatedWithReqs(ItemInformationProvider ii, List<Equip> items,
                                                       Job job, boolean[] reqRel) {
        if (items == null || items.size() <= 1) return items;
        int[] priority = jobStatPriority(job);
        boolean isMage = isMageJob(job);
        boolean accRel = isAccRelevantJob(job);
        // Precompute vec + tiebreak per item; the dominance scan is O(N^2) but the per-item
        // computation is O(N).
        final int n = items.size();
        int[][] vecs = new int[n][];
        int[] tiebreak = new int[n];
        for (int i = 0; i < n; i++) {
            vecs[i] = dedupStatVec(items.get(i), priority, reqRel, isMage, accRel);
            tiebreak[i] = dedupTiebreak(items.get(i), priority);
        }
        List<Equip> kept = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Equip a = items.get(i);
            boolean dominated = false;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                Equip b = items.get(j);
                if (!dedupDominatesPre(vecs[j], vecs[i], tiebreak[j], tiebreak[i])) continue;
                if (!reqsAtLeastAsEasy(ii, b, a)) continue;
                dominated = true; break;
            }
            if (!dominated) kept.add(a);
        }
        return kept.isEmpty() ? items : kept;
    }

    /**
     * Per-job stat priority for the dedup vec / tiebreaker. Index 0 is the primary damage
     * stat; remaining indices are secondaries (added at 0.25 weight into eff_primary).
     * Stat indices: 0=str, 1=dex, 2=int, 3=luk.
     */
    private static int[] jobStatPriority(Job job) {
        if (job == null) return new int[]{0, 1};
        if (isMageJob(job)) return new int[]{2};
        int id = job.getId();
        int niche = (id / 100) % 10;
        return switch (niche) {
            case 1 -> new int[]{0, 1};       // warrior (str primary, dex secondary)
            case 3 -> new int[]{1, 0};       // bowman (dex primary, str secondary)
            case 4 -> new int[]{3, 1, 0};    // thief (luk primary, dex+str secondary)
            case 5 -> (id / 10 == 51 || id / 10 == 151)
                    ? new int[]{0, 1}        // brawler line: str primary
                    : new int[]{1, 0};       // gunslinger line: dex primary
            default -> new int[]{0, 1};
        };
    }

    /** Acc-contribution stats only earn a vec dim for jobs that actually need acc to hit. */
    private static boolean isAccRelevantJob(Job job) {
        if (job == null) return false;
        int id = job.getId();
        int niche = (id / 100) % 10;
        if (niche == 1) return true;             // warrior
        if (niche == 5) {
            int sub = id / 10;
            return sub == 51 || sub == 151;      // brawler / thunderbreaker
        }
        return false;
    }

    /**
     * Scans every candidate pool to detect which stat reqs (str/dex/int/luk) appear on any
     * item. Returned mask drives whether that stat survives as a raw dim in {@code dedupStatVec}
     * — preserves req-unlocking potential while still allowing aggressive dedup on
     * never-req-gated stats.
     */
    private static boolean[] scanReqRelevantDims(Map<Short, List<Equip>> bySlot,
                                                  ItemInformationProvider ii) {
        return scanReqRelevantDims(bySlot, ii::getEquipStats);
    }

    private static boolean[] scanReqRelevantDims(Map<Short, List<Equip>> bySlot,
                                                  OptimizerHooks hooks) {
        return scanReqRelevantDims(bySlot, hooks::getEquipStats);
    }

    private interface EquipStatsLookup {
        Map<String, Integer> getEquipStats(int itemId);
    }

    private static boolean[] scanReqRelevantDims(Map<Short, List<Equip>> bySlot,
                                                  EquipStatsLookup lookup) {
        boolean[] mask = new boolean[4];
        for (List<Equip> pool : bySlot.values()) {
            if (pool == null) continue;
            for (Equip e : pool) {
                Map<String, Integer> s = lookup.getEquipStats(e.getItemId());
                if (s == null) continue;
                if (s.getOrDefault("reqSTR", 0) > 0) mask[0] = true;
                if (s.getOrDefault("reqDEX", 0) > 0) mask[1] = true;
                if (s.getOrDefault("reqINT", 0) > 0) mask[2] = true;
                if (s.getOrDefault("reqLUK", 0) > 0) mask[3] = true;
                if (mask[0] && mask[1] && mask[2] && mask[3]) return mask;
            }
        }
        return mask;
    }

    /**
     * Dims (in this order): job-primary stat (always); watk for non-mage / magic for mage;
     * eff_acc = acc + dex + luk*0.5 (warrior/brawler only); each of str/dex/int/luk where
     * reqRel[stat] is true (and not already emitted as primary).
     */
    private static int[] dedupStatVec(Equip e, int[] priority, boolean[] reqRel,
                                       boolean isMage, boolean accRelevant) {
        int primaryIdx = priority.length > 0 ? priority[0] : -1;
        boolean[] reqDim = new boolean[4];
        for (int i = 0; i < 4; i++) {
            reqDim[i] = reqRel != null && reqRel[i] && i != primaryIdx;
        }
        int count = (primaryIdx >= 0 ? 1 : 0) + 1 /* watk or magic */
                + (accRelevant ? 1 : 0);
        for (boolean b : reqDim) if (b) count++;
        int[] v = new int[count];
        int k = 0;
        int primary = primaryIdx >= 0 ? statByIdx(e, primaryIdx) : 0;
        if (primaryIdx >= 0) v[k++] = isMage ? primary : primary + e.getWatk() * 2;
        v[k++] = isMage ? e.getMatk() : e.getWatk() + primary / 5;
        if (accRelevant) {
            v[k++] = e.getAcc() + e.getDex() + (int) Math.round(e.getLuk() * 0.5);
        }
        for (int i = 0; i < 4; i++) {
            if (reqDim[i]) v[k++] = statByIdx(e, i);
        }
        return v;
    }

    /**
     * Tiebreaker score used when {@link #dedupStatVec} ties — captures combat-relevant
     * contributions that didn't survive into the vec. eff_primary = primary + secondaries*0.25
     * picks up secondary stats that aren't already raw dims (e.g. str for a thief when no
     * candidate has reqSTR). watk added at ×4 keeps damage signal dominant.
     */
    private static int dedupTiebreak(Equip e, int[] priority) {
        if (priority == null || priority.length == 0) return 0;
        int main = statByIdx(e, priority[0]);
        int secSum = 0;
        for (int i = 1; i < priority.length; i++) secSum += statByIdx(e, priority[i]);
        int effPrimary = main + (int) Math.round(secSum * 0.25);
        return effPrimary + e.getWatk() * 4;
    }

    private static int statByIdx(Equip e, int idx) {
        return switch (idx) {
            case 0 -> e.getStr();
            case 1 -> e.getDex();
            case 2 -> e.getInt();
            case 3 -> e.getLuk();
            default -> 0;
        };
    }

    private static int statByIdx(StatSnapshot s, int idx) {
        return switch (idx) {
            case 0 -> s.str();
            case 1 -> s.dex();
            case 2 -> s.int_();
            case 3 -> s.luk();
            default -> 0;
        };
    }

    private static boolean dedupDominatesPre(int[] bs, int[] as, int bTie, int aTie) {
        boolean strict = false;
        for (int i = 0; i < bs.length; i++) {
            if (bs[i] < as[i]) return false;
            if (bs[i] > as[i]) strict = true;
        }
        if (strict) return true;
        return bTie > aTie;
    }

    private static boolean reqsAtLeastAsEasy(ItemInformationProvider ii, Equip b, Equip a) {
        if (ii.getEquipLevelReq(b.getItemId()) > ii.getEquipLevelReq(a.getItemId())) return false;
        Map<String, Integer> bs = ii.getEquipStats(b.getItemId());
        Map<String, Integer> as = ii.getEquipStats(a.getItemId());
        if (bs == null || as == null) return bs == as;
        if (!reqJobAtLeastAsEasy(bs.getOrDefault("reqJob", 0), as.getOrDefault("reqJob", 0))) return false;
        for (String key : new String[]{"reqSTR", "reqDEX", "reqINT", "reqLUK", "reqPOP"}) {
            if (bs.getOrDefault(key, 0) > as.getOrDefault(key, 0)) return false;
        }
        return true;
    }

    private static boolean reqsAtLeastAsEasy(SelfReserveHooks hooks, Equip b, Equip a) {
        if (hooks.getEquipLevelReq(b.getItemId()) > hooks.getEquipLevelReq(a.getItemId())) return false;
        Map<String, Integer> bs = hooks.getEquipStats(b.getItemId());
        Map<String, Integer> as = hooks.getEquipStats(a.getItemId());
        if (bs == null || as == null) return bs == as;
        if (!reqJobAtLeastAsEasy(bs.getOrDefault("reqJob", 0), as.getOrDefault("reqJob", 0))) return false;
        for (String key : new String[]{"reqSTR", "reqDEX", "reqINT", "reqLUK", "reqPOP"}) {
            if (bs.getOrDefault(key, 0) > as.getOrDefault(key, 0)) return false;
        }
        return true;
    }

    private static boolean reqJobAtLeastAsEasy(int betterReqJob, int worseReqJob) {
        return betterReqJob == 0 || betterReqJob == worseReqJob;
    }

    private static boolean sameFutureTrack(ItemInformationProvider ii, Equip a, Equip b) {
        String slotA = ii.getEquipmentSlot(a.getItemId());
        String slotB = ii.getEquipmentSlot(b.getItemId());
        if (!Objects.equals(slotA, slotB)) return false;
        return ii.getWeaponType(a.getItemId()) == ii.getWeaponType(b.getItemId());
    }

    private static int[] statVec(Equip e) {
        return new int[]{e.getStr(), e.getDex(), e.getInt(), e.getLuk(),
                          e.getWatk(), e.getMatk(), e.getWdef(), e.getMdef(),
                          e.getAcc(), e.getAvoid(), e.getHp(), e.getMp(),
                          e.getSpeed(), e.getJump()};
    }
}
