/*
    This file is part of the OdinMS Maple Story Server.
    Bot quest index (AI companion feature).
*/
package server.bots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which quests can a bot legally run on its own, fully from {@code Quest.wz}? This indexes the
 * subset of quests a companion can drive by walking to an NPC and pressing start/complete with
 * NO scripts and NO inventory items to fetch — the "kill mobs, turn in" loop that piggybacks on
 * grinding. The actual eligibility (level/job/prereq) is re-checked at runtime by
 * {@code Quest.canStart}/{@code canComplete}; this index only narrows the field to quests whose
 * SHAPE the bot can handle.
 *
 * <p>A quest qualifies (see {@link #qualifies}) when:
 * <ul>
 *   <li>it is NOT scripted — no {@code scripts/quest/<id>.js} on disk, and no
 *       {@code startscript}/{@code endscript} marker in its WZ check node;</li>
 *   <li>its COMPLETE requirements (Check.img node {@code 1}) are MOB-only — {@code mob} counts plus
 *       the runtime-checked gates ({@code lvmin/lvmax/job/quest/interval/normalAutoStart/npc}); any
 *       {@code item}/{@code money}/{@code pop}/{@code pet}/... complete-req disqualifies it (the bot
 *       can't fetch those legally as a side effect of grinding);</li>
 *   <li>it has both a start NPC and an end NPC (Check.img node {@code 0}/{@code 1} {@code npc}).</li>
 * </ul>
 *
 * <p>The autoStart+autoComplete set (no NPC needed at all) is indexed separately — those run with
 * no travel.
 *
 * <p>Parse is split from filtering on purpose: {@link #parseQuestMeta} reads the raw WZ into a
 * {@link QuestMeta} record, and the pure {@link #qualifies} predicate decides eligibility. Unit
 * tests feed synthetic {@code QuestMeta} (WZ can't load in tests) and assert the filter rules.
 *
 * <p>Cached as TSV under {@code cache/bot-quest/v<N>/} (same convention as {@link BotSpawnIndex}).
 * Bump {@link #INDEX_VERSION} when the row format or scan semantics change.
 */
final class BotQuestIndex {

    private static final Logger log = LoggerFactory.getLogger(BotQuestIndex.class);
    // v2: adds the item-req reverse map (Feature B quest-item hygiene). Bumped so a warm v1 cache
    // (which lacks the ITEMREQ rows) is rebuilt rather than loaded with an empty reverse map.
    private static final int INDEX_VERSION = 2;
    private static final Path CACHE_FILE =
            Path.of("cache", "bot-quest", "v" + INDEX_VERSION, "quest-index.tsv");
    private static final Path SCRIPT_DIR = Path.of("scripts", "quest");

    /** One indexable quest's metadata, distilled from Quest.wz. {@code startNpc}/{@code endNpc} are
     *  0 when absent. {@code mobs} maps required-kill mob id -> count (complete reqs). {@code rewardExp}
     *  is the complete-action exp; {@code rewardItems} the complete-action item ids (for the
     *  inventory-space precheck). {@code scripted} flags a startscript/endscript or a .js file. The
     *  {@code completeReqKeys} are the raw WZ child-node names under Check.img node 1 — the
     *  mob-only check reads these. */
    record QuestMeta(int id, int startNpc, int endNpc, int lvmin,
                     Map<Integer, Integer> mobs, int rewardExp, List<Integer> rewardItems,
                     boolean autoStart, boolean autoComplete, boolean scripted,
                     List<String> completeReqKeys) {}

    /** One quest that REQUIRES an item (to start at Check.img node 0, or to complete at node 1).
     *  {@code lvmin}/{@code lvmax} are that quest's level window (0 = absent). Drives the Feature B
     *  stale-quest-item check: an item is stale only when EVERY quest using it is done-or-past for
     *  the bot. */
    record QuestItemReq(int questId, int lvmin, int lvmax) {}

    /** {@code itemReqs}: item id -> the quests that require it (start or complete). Built over ALL
     *  quests (NOT just the runnable mob quests in {@code byId}, which by construction have no item
     *  reqs at all). */
    record Index(Map<Integer, QuestMeta> byId, List<Integer> autoBoth,
                 Map<Integer, List<QuestItemReq>> itemReqs) {}

    /** The quests that require {@code itemId} (start or complete reqs). Empty when no indexed quest
     *  uses it — such items are out of Feature B's scope (left to the existing sell pipeline). */
    static List<QuestItemReq> questsRequiringItem(int itemId) {
        return get().itemReqs().getOrDefault(itemId, List.of());
    }

    private static volatile Index index;

    private BotQuestIndex() {}

    static Index get() {
        Index cached = index;
        if (cached != null) {
            return cached;
        }
        synchronized (BotQuestIndex.class) {
            if (index == null) {
                index = loadOrBuild();
            }
            return index;
        }
    }

    // ---- pure filter (test seam) -------------------------------------------------------------

    /** Complete-req WZ keys that DON'T disqualify a mob quest: the kill targets plus gates the
     *  runtime canComplete re-checks. Anything else (item/money/pop/pet/mbmin/...) means the bot
     *  would have to acquire something it can't get by grinding. */
    private static final java.util.Set<String> ALLOWED_COMPLETE_KEYS = java.util.Set.of(
            "mob", "npc", "lvmin", "lvmax", "job", "quest", "interval", "normalAutoStart", "infoNumber");

    /** True when this quest is the kill-and-turn-in shape a bot can drive by itself. Pure over
     *  {@link QuestMeta}; the WZ parse is elsewhere. */
    static boolean qualifies(QuestMeta q) {
        if (q.scripted()) {
            return false;
        }
        if (q.startNpc() <= 0 || q.endNpc() <= 0) {
            return false;
        }
        if (q.mobs().isEmpty()) {
            return false;
        }
        for (String key : q.completeReqKeys()) {
            if (!ALLOWED_COMPLETE_KEYS.contains(key)) {
                return false;
            }
        }
        return true;
    }

    // ---- WZ parse ----------------------------------------------------------------------------

    private static Index loadOrBuild() {
        Index loaded = loadCache();
        if (loaded != null) {
            log.info("Bot quest index: loaded {} quests from cache", loaded.byId().size());
            return loaded;
        }
        long startedAt = System.currentTimeMillis();
        Index built = build();
        writeCache(built);
        log.info("Bot quest index: built {} runnable quests ({} auto-both) in {} ms",
                built.byId().size(), built.autoBoth().size(), System.currentTimeMillis() - startedAt);
        return built;
    }

    private static Index build() {
        Map<Integer, QuestMeta> byId = new LinkedHashMap<>();
        List<Integer> autoBoth = new ArrayList<>();
        Map<Integer, List<QuestItemReq>> itemReqs = new LinkedHashMap<>();
        try {
            DataProvider quest = DataProviderFactory.getDataProvider(WZFiles.QUEST);
            Data checkRoot = quest.getData("Check.img");
            Data actRoot = quest.getData("Act.img");
            Data infoRoot = quest.getData("QuestInfo.img");
            if (checkRoot == null) {
                return new Index(Map.of(), List.of(), Map.of());
            }
            for (Data questNode : checkRoot.getChildren()) {
                int id;
                try {
                    id = Integer.parseInt(questNode.getName());
                } catch (NumberFormatException e) {
                    continue;
                }
                QuestMeta meta = parseQuestMeta(id, questNode,
                        actRoot != null ? actRoot.getChildByPath(questNode.getName()) : null,
                        infoRoot != null ? infoRoot.getChildByPath(questNode.getName()) : null);
                if (qualifies(meta)) {
                    byId.put(id, meta);
                }
                // Feature B: index this quest's REQUIRED items (start node 0 + complete node 1)
                // across ALL quests — the runnable mob quests in byId have none by construction.
                indexItemReqs(id, questNode, itemReqs);
            }
            // The auto-both set iterates QuestInfo.img — the SAME set Quest.loadAllQuests() walks
            // (auto quests need no NPC/Check node, so Check.img would miss the ones without reqs).
            if (infoRoot != null) {
                for (Data infoNode : infoRoot.getChildren()) {
                    int id;
                    try {
                        id = Integer.parseInt(infoNode.getName());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (isAutoBoth(infoNode)) {
                        autoBoth.add(id);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Bot quest index build failed", e);
        }
        return new Index(byId, autoBoth, itemReqs);
    }

    /** Add this quest to the item-req reverse map for every item it requires to START (Check.img
     *  node 0 {@code item}) or to COMPLETE (node 1 {@code item}). Captures the quest's level
     *  window ({@code lvmin} from node 0, {@code lvmax} from node 0 or 1) for the stale-item
     *  outlevel rule. A positive {@code count} req is what consumes the item; we union start +
     *  complete so an item needed at either gate counts. */
    static void indexItemReqs(int questId, Data checkNode, Map<Integer, List<QuestItemReq>> out) {
        Data start = checkNode.getChildByPath("0");
        Data complete = checkNode.getChildByPath("1");
        int lvmin = start != null ? DataTool.getInt("lvmin", start, 0) : 0;
        int lvmax = start != null ? DataTool.getInt("lvmax", start, 0) : 0;
        if (lvmax <= 0 && complete != null) {
            lvmax = DataTool.getInt("lvmax", complete, 0);
        }
        java.util.Set<Integer> items = new java.util.LinkedHashSet<>();
        collectReqItemIds(start, items);
        collectReqItemIds(complete, items);
        if (items.isEmpty()) {
            return;
        }
        QuestItemReq req = new QuestItemReq(questId, lvmin, lvmax);
        for (int itemId : items) {
            out.computeIfAbsent(itemId, k -> new ArrayList<>()).add(req);
        }
    }

    /** Required item ids under a Check.img node's {@code item} child (id + positive count). */
    private static void collectReqItemIds(Data node, java.util.Set<Integer> out) {
        if (node == null) {
            return;
        }
        Data itemNode = node.getChildByPath("item");
        if (itemNode == null) {
            return;
        }
        for (Data it : itemNode.getChildren()) {
            int itemId = DataTool.getInt("id", it, 0);
            int count = DataTool.getInt("count", it, 0);
            if (itemId > 0 && count > 0) {
                out.add(itemId);
            }
        }
    }

    /** Read one quest's Check.img node (plus matching Act.img / QuestInfo.img nodes) into a
     *  {@link QuestMeta}. {@code autoStart}/{@code autoComplete} read the SAME QuestInfo.img keys
     *  the real {@link server.quest.Quest} parser uses for {@code isAutoStart()}/{@code isAutoComplete()}
     *  (autoComplete OR autoPreComplete) — kept in sync with that SSOT, just without mutating the
     *  shared Quest cache from this off-thread build. */
    static QuestMeta parseQuestMeta(int id, Data checkNode, Data actNode, Data infoNode) {
        Data start = checkNode.getChildByPath("0");
        Data complete = checkNode.getChildByPath("1");

        int startNpc = start != null ? DataTool.getInt("npc", start, 0) : 0;
        int endNpc = complete != null ? DataTool.getInt("npc", complete, 0) : 0;
        int lvmin = start != null ? DataTool.getInt("lvmin", start, 0) : 0;

        Map<Integer, Integer> mobs = new LinkedHashMap<>();
        List<String> completeKeys = new ArrayList<>();
        boolean scripted = hasScriptMarker(start) || hasScriptMarker(complete)
                || Files.exists(SCRIPT_DIR.resolve(id + ".js"));
        if (complete != null) {
            for (Data req : complete.getChildren()) {
                completeKeys.add(req.getName());
            }
            Data mobNode = complete.getChildByPath("mob");
            if (mobNode != null) {
                for (Data m : mobNode.getChildren()) {
                    int mobId = DataTool.getInt("id", m, 0);
                    int count = DataTool.getInt("count", m, 0);
                    if (mobId > 0 && count > 0) {
                        mobs.put(mobId, count);
                    }
                }
            }
        }

        // SSOT: Quest.isAutoStart()/isAutoComplete() read these QuestInfo.img keys (the latter is
        // autoComplete OR autoPreComplete). Not the Check.img normalAutoStart requirement node.
        boolean autoStart = infoNode != null && DataTool.getInt("autoStart", infoNode, 0) == 1;
        boolean autoComplete = infoNode != null
                && (DataTool.getInt("autoComplete", infoNode, 0) == 1
                    || DataTool.getInt("autoPreComplete", infoNode, 0) == 1);

        int rewardExp = 0;
        List<Integer> rewardItems = new ArrayList<>();
        if (actNode != null) {
            Data actComplete = actNode.getChildByPath("1");
            if (actComplete != null) {
                rewardExp = DataTool.getInt("exp", actComplete, 0);
                Data itemNode = actComplete.getChildByPath("item");
                if (itemNode != null) {
                    for (Data it : itemNode.getChildren()) {
                        int itemId = DataTool.getInt("id", it, 0);
                        if (itemId > 0) {
                            rewardItems.add(itemId);
                        }
                    }
                }
            }
        }

        return new QuestMeta(id, startNpc, endNpc, lvmin, mobs, rewardExp, rewardItems,
                autoStart, autoComplete, scripted, completeKeys);
    }

    /** Mirrors {@code Quest.isAutoStart() && Quest.isAutoComplete()} read straight off a
     *  QuestInfo.img node: {@code autoStart==1} AND ({@code autoComplete==1} OR
     *  {@code autoPreComplete==1}). These quests advance with no NPC at all. */
    static boolean isAutoBoth(Data infoNode) {
        boolean autoStart = DataTool.getInt("autoStart", infoNode, 0) == 1;
        boolean autoComplete = DataTool.getInt("autoComplete", infoNode, 0) == 1
                || DataTool.getInt("autoPreComplete", infoNode, 0) == 1;
        return autoStart && autoComplete;
    }

    private static boolean hasScriptMarker(Data node) {
        return node != null
                && (node.getChildByPath("startscript") != null || node.getChildByPath("endscript") != null);
    }

    // ---- cache I/O ---------------------------------------------------------------------------

    private static Index loadCache() {
        if (!Files.exists(CACHE_FILE)) {
            return null;
        }
        Map<Integer, QuestMeta> byId = new LinkedHashMap<>();
        List<Integer> autoBoth = new ArrayList<>();
        Map<Integer, List<QuestItemReq>> itemReqs = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(CACHE_FILE, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                if (line.startsWith("AUTO\t")) {
                    for (String s : line.substring(5).split(",")) {
                        if (!s.isBlank()) {
                            autoBoth.add(Integer.parseInt(s.trim()));
                        }
                    }
                    continue;
                }
                if (line.startsWith("ITEMREQ\t")) {
                    parseItemReqRow(line, itemReqs);
                    continue;
                }
                QuestMeta meta = parseRow(line);
                if (meta != null) {
                    byId.put(meta.id(), meta);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Bot quest index: cache read failed, rebuilding", e);
            return null;
        }
        return new Index(byId, autoBoth, itemReqs);
    }

    /** Row: {@code ITEMREQ \t <itemId> \t questId:lvmin:lvmax,...} */
    private static void parseItemReqRow(String line, Map<Integer, List<QuestItemReq>> out) {
        String[] f = line.split("\t", -1);
        if (f.length < 3) {
            return;
        }
        int itemId = Integer.parseInt(f[1]);
        List<QuestItemReq> reqs = new ArrayList<>();
        if (!f[2].isBlank()) {
            for (String triple : f[2].split(",")) {
                String[] t = triple.split(":");
                reqs.add(new QuestItemReq(Integer.parseInt(t[0]),
                        Integer.parseInt(t[1]), Integer.parseInt(t[2])));
            }
        }
        if (!reqs.isEmpty()) {
            out.put(itemId, reqs);
        }
    }

    /** Row: id \t startNpc \t endNpc \t lvmin \t rewardExp \t mob:count,... \t item,... */
    private static QuestMeta parseRow(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 7) {
            return null;
        }
        int id = Integer.parseInt(f[0]);
        Map<Integer, Integer> mobs = new LinkedHashMap<>();
        if (!f[5].isBlank()) {
            for (String pair : f[5].split(",")) {
                String[] kv = pair.split(":");
                mobs.put(Integer.parseInt(kv[0]), Integer.parseInt(kv[1]));
            }
        }
        List<Integer> items = new ArrayList<>();
        if (!f[6].isBlank()) {
            for (String s : f[6].split(",")) {
                items.add(Integer.parseInt(s));
            }
        }
        // Cached rows are already-qualified mob quests: non-scripted, mob-only completes.
        return new QuestMeta(id, Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                Integer.parseInt(f[3]), mobs, Integer.parseInt(f[4]), items,
                false, false, false, new ArrayList<>(mobs.keySet().stream().map(x -> "mob").toList()));
    }

    private static void writeCache(Index idx) {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            StringBuilder sb = new StringBuilder();
            for (QuestMeta q : idx.byId().values()) {
                sb.append(q.id()).append('\t').append(q.startNpc()).append('\t').append(q.endNpc())
                        .append('\t').append(q.lvmin()).append('\t').append(q.rewardExp()).append('\t');
                boolean first = true;
                for (Map.Entry<Integer, Integer> e : q.mobs().entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(e.getKey()).append(':').append(e.getValue());
                    first = false;
                }
                sb.append('\t');
                first = true;
                for (int it : q.rewardItems()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(it);
                    first = false;
                }
                sb.append('\n');
            }
            sb.append("AUTO\t");
            boolean first = true;
            for (int qid : idx.autoBoth()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(qid);
                first = false;
            }
            sb.append('\n');
            for (Map.Entry<Integer, List<QuestItemReq>> e : idx.itemReqs().entrySet()) {
                sb.append("ITEMREQ\t").append(e.getKey()).append('\t');
                boolean firstReq = true;
                for (QuestItemReq r : e.getValue()) {
                    if (!firstReq) {
                        sb.append(',');
                    }
                    sb.append(r.questId()).append(':').append(r.lvmin()).append(':').append(r.lvmax());
                    firstReq = false;
                }
                sb.append('\n');
            }
            Files.writeString(CACHE_FILE, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Bot quest index: cache write failed", e);
        }
    }
}
