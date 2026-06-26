package server.bots;

import client.Character;
import client.SkillFactory;
import constants.skills.Evan;
import constants.skills.FPMage;
import constants.skills.Shadower;
import server.StatEffect;
import server.TimerManager;
import server.maps.MapleMap;
import server.maps.Mist;
import tools.PacketCreator;

import java.awt.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

public final class BotNavigationDebugOverlay {
    private static final int AUTO_CLEAR_MS = 30_000;
    private static final int MAX_MISTS = 900;
    private static final int NODE_SIZE = 10;
    private static final int GRAPH_NODE_SIZE = 8;
    private static final int LINE_THICKNESS = 4;
    private static final int PATH_THICKNESS = 6;
    private static final int DOTTED_SPACING = 22;
    private static final int GRAPH_EDGE_SPACING = 60;
    private static final int GRAPH_REGION_SPACING = 90;
    private static final int PATH_EDGE_SPACING = 28;

    private static final Map<Integer, OverlayState> overlaysByViewerId = new ConcurrentHashMap<>();

    private BotNavigationDebugOverlay() {
    }

    public static synchronized String showGraph(Character viewer) {
        if (!overlayEffectsAvailable()) {
            return "Bot nav overlay unavailable: no supported mist skill effect is loaded on this server build.";
        }

        OverlayBuilder overlay = new OverlayBuilder(viewer);
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(viewer.getMap(), BotMovementProfile.fromCharacter(viewer));
        Set<String> drawnEdges = new HashSet<>();

        for (BotNavigationGraph.Region region : graph.regions) {
            overlay.drawApproxRegion(region, OverlayType.REGION);
            overlay.drawNode(region.centerPoint(), OverlayType.NODE, GRAPH_NODE_SIZE);
        }

        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type == BotNavigationGraph.EdgeType.WALK) {
                    continue;
                }

                String key = canonicalEdgeKey(edge);
                if (!drawnEdges.add(key)) {
                    continue;
                }

                overlay.drawSparseLine(edge.startPoint, edge.endPoint, overlayTypeForEdge(edge.type), LINE_THICKNESS, GRAPH_EDGE_SPACING);
            }
        }

        replaceOverlay(viewer, overlay.objectIds());
        return buildGraphMessage(graph, overlay);
    }

    /** Debug lines for the nav region the {@code viewer} is standing on — backs the {@code !pos} command.
     *  Uses the viewer's OWN movement profile ({@link BotMovementProfile#fromCharacter}) and the same
     *  position→region resolution the bot uses ({@link BotNavigationGraph#findRegionId}), then delegates
     *  to {@link #describeRegion} (the SSOT shared with the web map-graph region click). */
    public static List<String> posReport(Character viewer) {
        List<String> out = new ArrayList<>();
        MapleMap map = viewer.getMap();
        Point pos = viewer.getPosition();
        BotMovementProfile profile = BotMovementProfile.fromCharacter(viewer);
        out.add("pos (" + pos.x + "," + pos.y + ")  map " + map.getId()
                + "  graph sp" + profile.totalSpeedStat() + " jmp" + profile.totalJumpStat()
                + (profile.snowShoes() ? " snow" : ""));
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, profile);
        if (graph == null) {
            out.add("nav graph unavailable for this map");
            return out;
        }
        int regionId = graph.findRegionId(map, pos);
        if (regionId < 0) {
            out.add("not on any nav region (mid-air / off-graph)  regions=" + graph.regions.size());
            return out;
        }
        out.addAll(describeRegion(graph, regionId));
        return out;
    }

    /** Human-readable debug lines for one nav region — the SSOT shared by the {@code !pos} command and
     *  the web map-graph region click: id, kind (platform/rope/ladder), bounds, footholds, and every
     *  outgoing edge grouped by destination region (edge types, {@code <->} when bidirectional). */
    static List<String> describeRegion(BotNavigationGraph graph, int regionId) {
        List<String> out = new ArrayList<>();
        BotNavigationGraph.Region r = graph.getRegion(regionId);
        if (r == null) {
            out.add("region " + regionId + ": not found in graph");
            return out;
        }
        String kind = r.isRopeRegion ? (r.isLadder ? "ladder" : "rope") : "platform";
        out.add("region " + r.id + " — " + kind
                + "  x[" + r.minX + ".." + r.maxX + "] y[" + r.minY + ".." + r.maxY + "]");
        if (r.isRopeRegion) {
            out.add("  rope span " + r.height() + "px at x=" + r.minX);
        } else {
            StringBuilder fhs = new StringBuilder();
            boolean forbid = false;
            for (BotNavigationGraph.Segment s : r.segments) {
                if (fhs.length() > 0) {
                    fhs.append(", ");
                }
                fhs.append(s.footholdId);
                forbid |= s.forbidFallDown;
            }
            out.add("  footholds(" + r.segments.size() + "): " + fhs + (forbid ? "  [forbidFallDown]" : ""));
        }
        Map<Integer, EnumSet<BotNavigationGraph.EdgeType>> byDest = new TreeMap<>();
        Map<Integer, Integer> portalTo = new HashMap<>();
        for (BotNavigationGraph.Edge e : graph.getOutgoing(regionId)) {
            if (e.fromRegionId == e.toRegionId) {
                continue; // intra-region walk
            }
            byDest.computeIfAbsent(e.toRegionId, k -> EnumSet.noneOf(BotNavigationGraph.EdgeType.class)).add(e.type);
            if (e.type == BotNavigationGraph.EdgeType.PORTAL && e.portalId >= 0) {
                portalTo.put(e.toRegionId, e.portalId);
            }
        }
        out.add("  out-edges: " + byDest.size() + " connected region(s)");
        Set<Integer> mutual = graph.getMutualAdjacentRegionIds(regionId);
        for (Map.Entry<Integer, EnumSet<BotNavigationGraph.EdgeType>> en : byDest.entrySet()) {
            StringBuilder types = new StringBuilder();
            for (BotNavigationGraph.EdgeType t : en.getValue()) {
                if (types.length() > 0) {
                    types.append(", ");
                }
                types.append(t.name());
            }
            if (portalTo.containsKey(en.getKey())) {
                types.append(" portal#").append(portalTo.get(en.getKey()));
            }
            out.add("   " + (mutual.contains(en.getKey()) ? "<->" : " ->") + " R" + en.getKey() + ": " + types);
        }
        return out;
    }

    public static synchronized String showPath(Character viewer, String botName) {
        if (!overlayEffectsAvailable()) {
            return "Bot nav overlay unavailable: no supported mist skill effect is loaded on this server build.";
        }

        BotSelection selection = selectBotEntry(viewer, botName);
        if (selection.errorMessage != null) {
            return selection.errorMessage;
        }
        BotEntry entry = selection.entry;

        Character bot = entry.bot;
        if (bot.getMapId() != viewer.getMapId()) {
            return "Bot '" + bot.getName() + "' is on map " + bot.getMapId() + ", not your current map.";
        }

        BotManager.TargetSnapshot targetSnapshot = BotManager.getInstance().captureTargetSnapshot(entry);
        Point targetPos = targetSnapshot.primaryTargetPos();
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(bot.getMap(), BotMovementProfile.fromCharacter(bot));
        int startRegionId = BotNavigationManager.resolveCurrentRegionId(graph, entry, bot.getMap(), bot.getPosition());
        int targetRegionId = BotNavigationManager.resolveTargetRegionId(graph, entry, bot.getMap(), targetPos);
        List<BotNavigationGraph.Edge> path = startRegionId >= 0 && targetRegionId >= 0 && startRegionId != targetRegionId
                ? BotNavigationManager.findPath(graph, bot, startRegionId, targetRegionId, targetPos)
                : List.of();

        OverlayBuilder overlay = new OverlayBuilder(viewer);
        drawRegion(overlay, graph.getRegion(startRegionId), OverlayType.REGION);
        if (targetRegionId != startRegionId) {
            drawRegion(overlay, graph.getRegion(targetRegionId), OverlayType.TRANSITION);
        }

        for (BotNavigationGraph.Edge edge : path) {
            overlay.drawSparseLine(edge.startPoint, edge.endPoint, OverlayType.PATH, PATH_THICKNESS, PATH_EDGE_SPACING);
        }

        if (entry.navEdge != null) {
            overlay.drawSparseLine(entry.navEdge.startPoint, entry.navEdge.endPoint, OverlayType.CURRENT_EDGE, PATH_THICKNESS + 2, PATH_EDGE_SPACING);
            overlay.drawNode(entry.navEdge.startPoint, OverlayType.CURRENT_EDGE, NODE_SIZE + 2);
            overlay.drawNode(entry.navEdge.endPoint, OverlayType.CURRENT_EDGE, NODE_SIZE + 2);
        }

        overlay.drawNode(bot.getPosition(), OverlayType.CURRENT_EDGE, NODE_SIZE + 4);
        overlay.drawNode(targetPos, OverlayType.TRANSITION, NODE_SIZE + 4);
        if (entry.navTargetPos != null) {
            overlay.drawNode(entry.navTargetPos, OverlayType.PATH, NODE_SIZE + 2);
        }

        replaceOverlay(viewer, overlay.objectIds());
        return buildPathMessage(bot, startRegionId, targetRegionId, path, entry, overlay);
    }

    public static synchronized String clear(Character viewer) {
        OverlayState state = overlaysByViewerId.remove(viewer.getId());
        clearOverlay(viewer, state);
        return "Bot nav overlay cleared.";
    }

    public static synchronized String pathLog(Character viewer, String botName, String note) {
        BotSelection selection = selectBotEntry(viewer, botName);
        if (selection.errorMessage != null) {
            return selection.errorMessage;
        }
        BotEntry entry = selection.entry;

        if (entry.pathLogger == null) {
            entry.pathLogger = new BotPathLogger(entry.bot.getName(), entry.bot.getMapId());
            return "Path logging started for '" + entry.bot.getName() + "'. Run !botnav pathlog again to dump.";
        }

        BotPathLogger logger = entry.pathLogger;
        entry.pathLogger = null;
        BotManager.TargetSnapshot targetSnapshot = BotManager.getInstance().captureTargetSnapshot(entry);
        String filePath = logger.dumpToFile(entry, targetSnapshot, note);
        return "Path log for '" + entry.bot.getName() + "' dumped: " + filePath;
    }

    private static void drawRegion(OverlayBuilder overlay, BotNavigationGraph.Region region, OverlayType type) {
        if (region == null) {
            return;
        }
        overlay.drawApproxRegion(region, type);
    }

    private static BotSelection selectBotEntry(Character viewer, String botName) {
        BotManager botManager = BotManager.getInstance();
        if (botName == null || botName.isBlank()) {
            List<BotEntry> entries = botManager.getBotEntries(viewer.getId());
            if (entries.isEmpty()) {
                return new BotSelection(null, "No owned bot found. Spawn one first or use !botnav path <botName>.");
            }
            if (entries.size() > 1) {
                String names = entries.stream()
                        .map(botEntry -> botEntry.bot.getName())
                        .sorted(String::compareToIgnoreCase)
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("");
                return new BotSelection(null, "Multiple owned bots: " + names + ". Use !botnav path <botName>.");
            }
            return new BotSelection(entries.getFirst(), null);
        }
        BotEntry entry = botManager.getBotEntry(viewer.getId(), botName);
        if (entry == null) {
            // !botnav is GM-gated (gm3), so an admin may debug ANY spawned bot by name - including
            // ownerless / independent bots they do not own.
            entry = botManager.findSpawnedBotByName(botName);
        }
        if (entry == null) {
            return new BotSelection(null, "No spawned bot named '" + botName + "' found.");
        }
        return new BotSelection(entry, null);
    }

    private static String buildGraphMessage(BotNavigationGraph graph, OverlayBuilder overlay) {
        return "Bot nav graph overlay: regions=" + graph.regions.size()
                + ", mists=" + overlay.objectIds().size()
                + (overlay.truncated() ? " (truncated)" : "")
                + ", auto-clear " + (AUTO_CLEAR_MS / 1000) + "s.";
    }

    private static String buildPathMessage(Character bot,
                                           int startRegionId,
                                           int targetRegionId,
                                           List<BotNavigationGraph.Edge> path,
                                           BotEntry entry,
                                           OverlayBuilder overlay) {
        String currentEdge = entry.navEdge == null ? "none" : entry.navEdge.type.name();
        String status;
        if (startRegionId == targetRegionId && entry.navEdge == null) {
            status = "same-region/local";
        } else if (entry.navEdge != null) {
            status = "committed";
        } else if (path.isEmpty()) {
            status = "no-path";
        } else {
            status = "path-only";
        }
        return "Bot nav path for '" + bot.getName() + "': region " + startRegionId + " -> " + targetRegionId
                + ", edges=" + path.size()
                + ", committed=" + currentEdge
                + ", status=" + status
                + ", mists=" + overlay.objectIds().size()
                + (overlay.truncated() ? " (truncated)" : "")
                + ", auto-clear " + (AUTO_CLEAR_MS / 1000) + "s.";
    }

    private static void replaceOverlay(Character viewer, List<Integer> objectIds) {
        OverlayState previous = overlaysByViewerId.remove(viewer.getId());
        clearOverlay(viewer, previous);

        ScheduledFuture<?> clearTask = TimerManager.getInstance().schedule(() -> clear(viewer), AUTO_CLEAR_MS);
        overlaysByViewerId.put(viewer.getId(), new OverlayState(objectIds, clearTask));
    }

    private static void clearOverlay(Character viewer, OverlayState state) {
        if (state == null) {
            return;
        }
        if (state.clearTask != null) {
            state.clearTask.cancel(false);
        }
        for (int objectId : state.objectIds) {
            viewer.sendPacket(PacketCreator.removeMist(objectId));
        }
    }

    private static String canonicalEdgeKey(BotNavigationGraph.Edge edge) {
        Point first = edge.startPoint;
        Point second = edge.endPoint;
        if (first.x > second.x || (first.x == second.x && first.y > second.y)) {
            Point swapped = first;
            first = second;
            second = swapped;
        }
        return edge.type + ":" + first.x + ":" + first.y + ":" + second.x + ":" + second.y;
    }

    private static boolean overlayEffectsAvailable() {
        return firstAvailableEffect(Evan.RECOVERY_AURA, FPMage.POISON_MIST, Shadower.SMOKE_SCREEN) != null;
    }

    private static StatEffect firstAvailableEffect(int... skillIds) {
        for (int skillId : skillIds) {
            var skill = SkillFactory.getSkill(skillId);
            if (skill != null) {
                return skill.getEffect(1);
            }
        }
        return null;
    }

    private static OverlayType overlayTypeForEdge(BotNavigationGraph.EdgeType edgeType) {
        return switch (edgeType) {
            case DROP, PORTAL, TELEPORT -> OverlayType.TRANSITION;
            case JUMP, CLIMB, FLASH_JUMP -> OverlayType.PATH;
            case WALK -> OverlayType.REGION;
        };
    }

    private enum OverlayType {
        REGION,
        TRANSITION,
        NODE,
        PATH,
        CURRENT_EDGE
    }

    private static final class OverlayState {
        private final List<Integer> objectIds;
        private final ScheduledFuture<?> clearTask;

        private OverlayState(List<Integer> objectIds, ScheduledFuture<?> clearTask) {
            this.objectIds = new ArrayList<>(objectIds);
            this.clearTask = clearTask;
        }
    }

    private record BotSelection(BotEntry entry, String errorMessage) {
    }

    private static final class OverlayBuilder {
        private final Character viewer;
        private final MapleMap map;
        private final List<Integer> objectIds = new ArrayList<>();
        private boolean truncated = false;

        private OverlayBuilder(Character viewer) {
            this.viewer = viewer;
            this.map = viewer.getMap();
        }

        private List<Integer> objectIds() {
            return objectIds;
        }

        private boolean truncated() {
            return truncated;
        }

        private void drawNode(Point center, OverlayType type, int size) {
            int half = Math.max(1, size / 2);
            drawRect(new Rectangle(center.x - half, center.y - half, Math.max(2, size), Math.max(2, size)), type);
        }

        private void drawLine(Point start, Point end, OverlayType type, int thickness) {
            if (truncated) {
                return;
            }

            int dx = end.x - start.x;
            int dy = end.y - start.y;
            if (Math.abs(dy) <= thickness || Math.abs(dx) <= thickness) {
                int minX = Math.min(start.x, end.x) - Math.max(1, thickness / 2);
                int minY = Math.min(start.y, end.y) - Math.max(1, thickness / 2);
                int width = Math.max(2, Math.abs(dx) + thickness);
                int height = Math.max(2, Math.abs(dy) + thickness);
                drawRect(new Rectangle(minX, minY, width, height), type);
                return;
            }

            drawSparseLine(start, end, type, thickness, DOTTED_SPACING);
        }

        private void drawSparseLine(Point start, Point end, OverlayType type, int thickness, int spacing) {
            if (truncated) {
                return;
            }

            int dx = end.x - start.x;
            int dy = end.y - start.y;
            double distance = start.distance(end);
            int steps = Math.max(1, (int) Math.ceil(distance / Math.max(1, spacing)));
            for (int i = 0; i <= steps && !truncated; i++) {
                double t = i / (double) steps;
                int x = (int) Math.round(start.x + dx * t);
                int y = (int) Math.round(start.y + dy * t);
                drawNode(new Point(x, y), type, thickness + 2);
            }
        }

        private void drawApproxRegion(BotNavigationGraph.Region region, OverlayType type) {
            if (region == null) {
                return;
            }

            Point left = region.leftPoint();
            Point right = region.rightPoint();
            if (left.distance(right) <= Math.max(40, GRAPH_REGION_SPACING / 2.0)) {
                drawLine(left, right, type, LINE_THICKNESS);
                return;
            }

            drawSparseLine(left, right, type, LINE_THICKNESS, GRAPH_REGION_SPACING);
        }

        private void drawRect(Rectangle rectangle, OverlayType type) {
            if (truncated || objectIds.size() >= MAX_MISTS) {
                truncated = true;
                return;
            }

            Rectangle box = normalize(rectangle);
            Mist mist = new Mist(box, viewer, effectFor(type));
            mist.setObjectId(map.allocateMapObjectId());
            viewer.sendPacket(mist.makeFakeSpawnData(1));
            objectIds.add(mist.getObjectId());
        }

        private Rectangle normalize(Rectangle rectangle) {
            int x = Math.min(rectangle.x, rectangle.x + rectangle.width);
            int y = Math.min(rectangle.y, rectangle.y + rectangle.height);
            int width = Math.max(2, Math.abs(rectangle.width));
            int height = Math.max(2, Math.abs(rectangle.height));
            return new Rectangle(x, y, width, height);
        }

        private StatEffect effectFor(OverlayType type) {
            return switch (type) {
                case REGION, CURRENT_EDGE -> firstAvailableEffect(Shadower.SMOKE_SCREEN, FPMage.POISON_MIST);
                case TRANSITION, NODE -> firstAvailableEffect(FPMage.POISON_MIST, Shadower.SMOKE_SCREEN);
                case PATH -> firstAvailableEffect(Evan.RECOVERY_AURA, FPMage.POISON_MIST, Shadower.SMOKE_SCREEN);
            };
        }
    }
}
