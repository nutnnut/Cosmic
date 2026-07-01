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
    // v3: indexes TALK quests too (talk NPC A -> talk NPC B). Bumped so a v2 cache (mob quests only,
    // no talk column) is rebuilt rather than loaded missing the talk-quest rows.
    // v4: indexes FETCH quests (obtain item, deliver to NPC) — adds the required-items column and now
    // persists the `scripted` flag (a v3 cache hardcoded scripted=false on read). Bumped so a v3 cache
    // is rebuilt with the new columns rather than loaded missing fetch quests / mis-reporting scripted.
    private static final int INDEX_VERSION = 4;
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
                     List<String> completeReqKeys, boolean talk, Map<Integer, Integer> items) {
        /** Back-compat 12-arg form (pre-fetch): a quest with no required-to-deliver items. */
        QuestMeta(int id, int startNpc, int endNpc, int lvmin,
                  Map<Integer, Integer> mobs, int rewardExp, List<Integer> rewardItems,
                  boolean autoStart, boolean autoComplete, boolean scripted,
                  List<String> completeReqKeys, boolean talk) {
            this(id, startNpc, endNpc, lvmin, mobs, rewardExp, rewardItems,
                    autoStart, autoComplete, scripted, completeReqKeys, talk, Map.of());
        }

        /** Back-compat 11-arg form (pre-talk): builds a non-talk mob quest. Used by the slice-1/2
         *  unit tests and the cache-row reader, which never construct talk quests. */
        QuestMeta(int id, int startNpc, int endNpc, int lvmin,
                  Map<Integer, Integer> mobs, int rewardExp, List<Integer> rewardItems,
                  boolean autoStart, boolean autoComplete, boolean scripted,
                  List<String> completeReqKeys) {
            this(id, startNpc, endNpc, lvmin, mobs, rewardExp, rewardItems,
                    autoStart, autoComplete, scripted, completeReqKeys, false);
        }
    }

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

    /** True when this quest is a bot-runnable shape: either the kill-and-turn-in MOB quest (slice
     *  1/2) or a pure TALK quest (talk NPC A -> talk NPC B, no kills, no fetched items). Pure over
     *  {@link QuestMeta}; the WZ parse is elsewhere. */
    static boolean qualifies(QuestMeta q) {
        return qualifiesMob(q) || q.talk() || qualifiesFetch(q);
    }

    /** The FETCH shape: walk to the start NPC, obtain the required item(s) (usually a mob drop the bot
     *  collects while grinding), walk back and deliver. Qualifies when: not scripted, both NPCs, at
     *  least one required complete-item, and every complete-req is a kill target, an item, or a
     *  runtime-rechecked gate (no money/pop/pet). Whether the item is ACTUALLY mob-droppable is decided
     *  at runtime (the index is WZ-pure; the drop table is DB-side) — see BotQuestManager. A fetch quest
     *  may also list mobs (kill AND collect); that's allowed. */
    static boolean qualifiesFetch(QuestMeta q) {
        if (q.scripted() || q.startNpc() <= 0 || q.endNpc() <= 0) {
            return false;
        }
        if (q.items().isEmpty()) {
            return false;
        }
        for (String key : q.completeReqKeys()) {
            if (!ALLOWED_COMPLETE_KEYS.contains(key) && !"item".equals(key)) {
                return false;
            }
        }
        return true;
    }

    /** The original kill-and-turn-in shape: not scripted, both NPCs, at least one required mob, and
     *  every complete-req is a kill target or a runtime-rechecked gate. */
    static boolean qualifiesMob(QuestMeta q) {
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
        Map<Integer, Integer> items = new LinkedHashMap<>();
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
            // Required items to DELIVER (count > 0). count <= 0 entries are tutorial "consume this"
            // markers the runtime ItemRequirement treats as satisfied (see isTalkShape) — not a fetch.
            Data itemNode = complete.getChildByPath("item");
            if (itemNode != null) {
                for (Data it : itemNode.getChildren()) {
                    int itemId = DataTool.getInt("id", it, 0);
                    int count = DataTool.getInt("count", it, 0);
                    if (itemId > 0 && count > 0) {
                        items.put(itemId, count);
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

        boolean talk = isTalkShape(startNpc, endNpc, mobs, complete);

        return new QuestMeta(id, startNpc, endNpc, lvmin, mobs, rewardExp, rewardItems,
                autoStart, autoComplete, scripted, completeKeys, talk, items);
    }

    /** A TALK quest is the simplest bot-runnable shape: walk to the start NPC and press start, walk
     *  to the end NPC and press complete — no kills, no items the bot must fetch. It qualifies when
     *  it has both NPCs, NO required mobs, and every COMPLETE requirement is either a runtime-rechecked
     *  gate (level/job/prereq/npc/...) OR a non-blocking item req (count {@literal <=} 0, i.e. a
     *  tutorial "use this" item that {@code ItemRequirement} treats as already satisfied — e.g.
     *  Roger's Apple in q1021). Scripts don't block: {@code ScriptRequirement.check} always returns
     *  true, so a startscript/endscript quest is still completable via {@code Quest.start/complete}. */
    static boolean isTalkShape(int startNpc, int endNpc, Map<Integer, Integer> mobs, Data complete) {
        if (startNpc <= 0 || endNpc <= 0 || !mobs.isEmpty()) {
            return false;
        }
        if (complete == null) {
            return false;
        }
        for (Data req : complete.getChildren()) {
            String key = req.getName();
            if (ALLOWED_COMPLETE_KEYS.contains(key) || "startscript".equals(key) || "endscript".equals(key)) {
                continue;
            }
            if ("item".equals(key)) {
                if (hasBlockingItemReq(req)) {
                    return false; // a real fetch item the bot can't get by talking
                }
                continue;
            }
            return false; // money/pop/pet/... — out of a talk quest's reach
        }
        return true;
    }

    /** True when any item entry under a Check.img item node demands a positive count (a real fetch).
     *  Count-absent / {@literal <=}0 entries are tutorial "consume this" markers the runtime
     *  {@code ItemRequirement} treats as satisfied (countNeeded 0), so they don't block a bot. */
    private static boolean hasBlockingItemReq(Data itemNode) {
        for (Data it : itemNode.getChildren()) {
            if (DataTool.getInt("count", it, 0) > 0) {
                return true;
            }
        }
        return false;
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

    /** Row (v4): id \t startNpc \t endNpc \t lvmin \t rewardExp \t mob:count,... \t rewardItem,... \t
     *  talk(0/1) \t reqItem:count,... \t scripted(0/1) */
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
        List<Integer> rewardItems = new ArrayList<>();
        if (!f[6].isBlank()) {
            for (String s : f[6].split(",")) {
                rewardItems.add(Integer.parseInt(s));
            }
        }
        boolean talk = f.length > 7 && "1".equals(f[7]);
        Map<Integer, Integer> reqItems = new LinkedHashMap<>();
        if (f.length > 8 && !f[8].isBlank()) {
            for (String pair : f[8].split(",")) {
                String[] kv = pair.split(":");
                reqItems.put(Integer.parseInt(kv[0]), Integer.parseInt(kv[1]));
            }
        }
        boolean scripted = f.length > 9 && "1".equals(f[9]);
        // Cached rows are already-qualified. completeReqKeys is reconstructed enough to keep
        // status/objective rendering and the runtime fetch/mob branches correct: mob targets, an
        // "item" marker when items are required, and "npc" for a pure talk quest.
        List<String> completeKeys = new ArrayList<>(mobs.keySet().stream().map(x -> "mob").toList());
        if (!reqItems.isEmpty()) {
            completeKeys.add("item");
        }
        if (completeKeys.isEmpty()) {
            completeKeys.add("npc");
        }
        return new QuestMeta(id, Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                Integer.parseInt(f[3]), mobs, Integer.parseInt(f[4]), rewardItems,
                false, false, scripted, completeKeys, talk, reqItems);
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
                sb.append('\t').append(q.talk() ? '1' : '0');
                sb.append('\t');
                first = true;
                for (Map.Entry<Integer, Integer> e : q.items().entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(e.getKey()).append(':').append(e.getValue());
                    first = false;
                }
                sb.append('\t').append(q.scripted() ? '1' : '0');
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
