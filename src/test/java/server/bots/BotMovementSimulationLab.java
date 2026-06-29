package server.bots;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.mockito.stubbing.Answer;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class BotMovementSimulationLab {
    private final BotManager manager = BotManager.getInstance();
    private final Map<String, SimActor> actors = new LinkedHashMap<>();
    private final Map<String, BotEntry> bots = new LinkedHashMap<>();
    private final List<TraceFrame> trace = new ArrayList<>();
    private long elapsedMs = 0L;

    private BotMovementSimulationLab() {
    }

    static BotMovementSimulationLab loadMap(int mapId) {
        ensureWzPath();
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        return fromMap(map);
    }

    static BotMovementSimulationLab fromMap(MapleMap map) {
        BotNavigationGraphProvider.rebuildGraph(map);
        return new BotMovementSimulationLab();
    }

    Character spawnActor(String name, int id, MapleMap map, Point startPosition) {
        Character actor = mockCharacter(name, id, map, startPosition);
        actors.put(name, new SimActor(actor));
        return actor;
    }

    BotEntry spawnBot(String name, int id, MapleMap map, Point startPosition) {
        Character bot = spawnActor(name, id, map, startPosition);
        BotEntry entry = new BotEntry(bot, null, null);
        entry.skipDelayMs = 0;
        entry.lastMapId = map.getId();
        entry.movementProfile = BotMovementProfile.fromCharacter(bot);
        bots.put(name, entry);
        return entry;
    }

    void setFollow(String botName, String ownerName) {
        BotEntry entry = requireBot(botName);
        Character owner = requireActor(ownerName);
        entry.owner = owner;
        entry.following = true;
        entry.grinding = false;
        refreshFormation(owner);
    }

    void clearFollow(String botName) {
        BotEntry entry = requireBot(botName);
        entry.following = false;
        entry.owner = null;
        entry.followOffsetX = 0;
    }

    void setMoveTarget(String botName, Point target, boolean precise) {
        BotEntry entry = requireBot(botName);
        entry.moveTarget = new Point(target);
        entry.moveTargetPrecise = precise;
    }

    void clearMoveTarget(String botName) {
        BotEntry entry = requireBot(botName);
        entry.moveTarget = null;
        entry.moveTargetPrecise = false;
    }

    void teleport(String actorName, Point position) {
        Character actor = requireActor(actorName);
        actor.setPosition(new Point(position));
        BotEntry entry = bots.get(actorName);
        if (entry != null) {
            BotPhysicsEngine.teleportTo(entry, actor, position);
            BotMovementManager.resetEntryStateAfterTeleport(entry);
        }
    }

    void setFormation(String ownerName, BotManager.FormationType type, int px, int snapRange) {
        Character owner = requireActor(ownerName);
        manager.setFormationState(owner, type, px, snapRange, followersOf(owner));
    }

    void setFollowOffset(String botName, int offsetX) {
        requireBot(botName).followOffsetX = offsetX;
    }

    void setAiAccumulator(String botName, int accumulatorMs) {
        requireBot(botName).aiTickAccumulatorMs = accumulatorMs;
    }

    void primeMapState(String botName) {
        BotEntry entry = requireBot(botName);
        entry.lastMapId = entry.bot.getMapId();
    }

    void attachBotToRope(String botName, Rope rope, int y) {
        BotEntry entry = requireBot(botName);
        BotPhysicsEngine.attachToRope(entry, entry.bot, rope, y);
        primeMapState(botName);
    }

    void setNavState(String botName, BotNavigationGraph.Edge edge, int targetRegionId, boolean preciseTarget) {
        BotEntry entry = requireBot(botName);
        entry.navEdge = edge;
        entry.navTargetRegionId = targetRegionId;
        entry.navPreciseTarget = preciseTarget;
        entry.navTargetPos = edge == null ? null : new Point(edge.startPoint);
    }

    void step(int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            elapsedMs += BotMovementManager.cfg.TICK_MS;

            List<PendingStep> pending = new ArrayList<>(bots.size());
            for (Map.Entry<String, BotEntry> botEntry : bots.entrySet()) {
                BotEntry entry = botEntry.getValue();
                boolean runAiTick = consumeAiTick(entry);
                BotManager.TargetSnapshot targetSnapshot = manager.captureTargetSnapshot(entry);
                Point ownerPos = targetSnapshot.rawOwnerPos();
                entry.lastTickWasAi = runAiTick;
                entry.lastTickAtMs = elapsedMs;
                entry.lastOwnerPos = new Point(ownerPos);
                pending.add(new PendingStep(botEntry.getKey(), entry, runAiTick, targetSnapshot));
            }

            for (PendingStep pendingStep : pending) {
                manager.stepMovementOnly(
                        pendingStep.entry(),
                        pendingStep.targetSnapshot().primaryTargetPos(),
                        pendingStep.targetSnapshot().rawOwnerPos(),
                        pendingStep.runAiTick());
                trace.add(TraceFrame.capture(
                        trace.size(),
                        elapsedMs,
                        pendingStep.name(),
                        pendingStep.entry(),
                        pendingStep.targetSnapshot()));
            }
        }
    }

    void stepRaw(String botName, Point targetPos, boolean runAiTick) {
        BotEntry entry = requireBot(botName);
        elapsedMs += BotMovementManager.cfg.TICK_MS;
        entry.lastTickWasAi = runAiTick;
        entry.lastTickAtMs = elapsedMs;

        BotManager.TargetSnapshot targetSnapshot = manager.captureTargetSnapshot(entry);
        Point ownerPos = targetSnapshot.rawOwnerPos();
        entry.lastOwnerPos = new Point(ownerPos);
        manager.stepMovementOnly(entry, new Point(targetPos), ownerPos, runAiTick);
        trace.add(TraceFrame.capture(trace.size(), elapsedMs, botName, entry, manager.captureTargetSnapshot(entry)));
    }

    Character actor(String name) {
        return requireActor(name);
    }

    BotEntry botEntry(String name) {
        return requireBot(name);
    }

    Point position(String actorName) {
        return requireActor(actorName).getPosition();
    }

    List<String> formatRecentTrace(String botName, int limit) {
        List<String> lines = new ArrayList<>();
        int emitted = 0;
        for (int i = trace.size() - 1; i >= 0 && emitted < limit; i--) {
            TraceFrame frame = trace.get(i);
            if (!frame.botName().equals(botName)) {
                continue;
            }
            lines.add(0, frame.format());
            emitted++;
        }
        return lines;
    }

    String describeCurrentState(String botName) {
        BotEntry entry = requireBot(botName);
        BotManager.TargetSnapshot snapshot = manager.captureTargetSnapshot(entry);
        return TraceFrame.capture(trace.size(), elapsedMs, botName, entry, snapshot).format();
    }

    List<String> botNames() {
        return new ArrayList<>(bots.keySet());
    }

    private void refreshFormation(Character owner) {
        List<BotEntry> followers = followersOf(owner);
        BotManager.FormationState formation = followers.isEmpty()
                ? BotManager.FormationState.defaultStagger()
                : manager.formationStateFor(followers.getFirst());
        manager.setFormationState(owner, formation.type(), formation.px(), formation.snapRange(), followers);
    }

    private List<BotEntry> followersOf(Character owner) {
        return bots.values().stream()
                .filter(entry -> entry.owner != null && entry.owner.getId() == owner.getId())
                .sorted(Comparator.comparing(entry -> entry.bot.getName()))
                .toList();
    }

    private Character requireActor(String name) {
        SimActor actor = actors.get(name);
        if (actor == null) {
            throw new IllegalArgumentException("Unknown actor: " + name);
        }
        return actor.character();
    }

    private BotEntry requireBot(String name) {
        BotEntry entry = bots.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown bot: " + name);
        }
        return entry;
    }

    private static boolean consumeAiTick(BotEntry entry) {
        entry.aiTickAccumulatorMs += BotMovementManager.cfg.TICK_MS;
        if (entry.aiTickAccumulatorMs < BotManager.cfg.AI_TICK_MS) {
            return false;
        }

        entry.aiTickAccumulatorMs -= BotManager.cfg.AI_TICK_MS;
        return true;
    }

    private static Character mockCharacter(String name, int id, MapleMap initialMap, Point startPosition) {
        Character character = mock(Character.class);
        Inventory equipped = mock(Inventory.class);
        AtomicReference<Point> position = new AtomicReference<>(new Point(startPosition));
        AtomicReference<MapleMap> map = new AtomicReference<>(initialMap);
        AtomicInteger hp = new AtomicInteger(100);
        AtomicInteger stance = new AtomicInteger(0);

        when(character.getName()).thenReturn(name);
        when(character.getId()).thenReturn(id);
        when(character.getPosition()).thenAnswer((Answer<Point>) invocation -> new Point(position.get()));
        doAnswer(invocation -> {
            position.set(new Point(invocation.getArgument(0)));
            return null;
        }).when(character).setPosition(any(Point.class));
        when(character.getMap()).thenAnswer(invocation -> map.get());
        when(character.getMapId()).thenAnswer(invocation -> map.get().getId());
        when(character.getHp()).thenAnswer(invocation -> hp.get());
        when(character.getCurrentMaxHp()).thenReturn(100);
        when(character.getStance()).thenAnswer(invocation -> stance.get());
        doAnswer(invocation -> {
            stance.set(invocation.getArgument(0));
            return null;
        }).when(character).setStance(anyInt());
        when(character.getJob()).thenReturn(Job.BEGINNER);
        when(character.getLevel()).thenReturn(1);
        when(character.getSkills()).thenReturn(Map.of());
        when(character.getRemainingSps()).thenReturn(new int[5]);
        when(character.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(equipped.getItem((short) -11)).thenReturn(null);
        when(equipped.iterator()).thenReturn(Collections.emptyIterator());
        when(character.getTotalMoveSpeedStat()).thenReturn(100);
        when(character.getTotalJumpStat()).thenReturn(100);
        when(character.isLoggedinWorld()).thenReturn(true);
        doAnswer(invocation -> {
            map.set(invocation.getArgument(0));
            position.set(new Point(invocation.getArgument(1)));
            return null;
        }).when(character).changeMap(any(MapleMap.class), any(Point.class));
        return character;
    }

    private static void ensureWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    private record SimActor(Character character) {
    }

    private record PendingStep(String name,
                               BotEntry entry,
                               boolean runAiTick,
                               BotManager.TargetSnapshot targetSnapshot) {
    }

    private record TraceFrame(long index,
                              long elapsedMs,
                              String botName,
                              boolean runAiTick,
                              Point botPos,
                              Point ownerPos,
                              Point goalPos,
                              Point steeringPos,
                              String navDecision,
                              String physics,
                              String edge) {
        static TraceFrame capture(long index,
                                  long elapsedMs,
                                  String botName,
                                  BotEntry entry,
                                  BotManager.TargetSnapshot targetSnapshot) {
            Point botPos = entry.bot.getPosition();
            Point ownerPos = targetSnapshot.rawOwnerPos();
            Point goalPos = targetSnapshot.primaryTargetPos();
            Point steeringPos = targetSnapshot.steeringTargetPos(entry);
            return new TraceFrame(
                    index,
                    elapsedMs,
                    botName,
                    entry.lastTickWasAi,
                    new Point(botPos),
                    new Point(ownerPos),
                    new Point(goalPos),
                    new Point(steeringPos),
                    entry.lastNavDecision,
                    describePhysics(entry),
                    describeEdge(entry.navEdge));
        }

        String format() {
            return String.format(
                    "[%05d +%4dms] ai=%s bot=%s owner=%s goal=%s steer=%s nav=%s phys=%s edge=%s",
                    index,
                    elapsedMs,
                    runAiTick ? "Y" : "N",
                    formatPoint(botPos),
                    formatPoint(ownerPos),
                    formatPoint(goalPos),
                    formatPoint(steeringPos),
                    navDecision,
                    physics,
                    edge);
        }

        private static String describePhysics(BotEntry entry) {
            if (entry.climbing && entry.climbRope != null) {
                return String.format("ROPE(x=%d top=%d bot=%d)", entry.climbRope.x(), entry.climbRope.topY(), entry.climbRope.bottomY());
            }
            if (entry.inAir) {
                return String.format("AIR(velY=%.1f airVelX=%d)", entry.velY, entry.airVelX);
            }
            return "GND";
        }

        private static String describeEdge(BotNavigationGraph.Edge edge) {
            if (edge == null) {
                return "none";
            }
            return switch (edge.type) {
                case WALK -> String.format("WALK r%d->r%d %s->%s",
                        edge.fromRegionId, edge.toRegionId, formatPoint(edge.startPoint), formatPoint(edge.endPoint));
                case JUMP -> String.format("JUMP r%d->r%d %s->%s stepX=%d",
                        edge.fromRegionId, edge.toRegionId, formatPoint(edge.startPoint), formatPoint(edge.endPoint), edge.launchStepX);
                case DROP -> String.format("DROP r%d->r%d %s->%s stepX=%d",
                        edge.fromRegionId, edge.toRegionId, formatPoint(edge.startPoint), formatPoint(edge.endPoint), edge.launchStepX);
                case CLIMB -> String.format("CLIMB r%d->r%d %s->%s stepX=%d",
                        edge.fromRegionId, edge.toRegionId, formatPoint(edge.startPoint), formatPoint(edge.endPoint), edge.launchStepX);
                case PORTAL -> String.format("PORTAL r%d->r%d %s->%s",
                        edge.fromRegionId, edge.toRegionId, formatPoint(edge.startPoint), formatPoint(edge.endPoint));
                case TELEPORT -> String.format("TELEPORT r%d->r%d %s->%s",
                        edge.fromRegionId, edge.toRegionId, formatPoint(edge.startPoint), formatPoint(edge.endPoint));
                case FLASH_JUMP -> String.format("FLASH_JUMP r%d->r%d %s->%s stepX=%d",
                        edge.fromRegionId, edge.toRegionId, formatPoint(edge.startPoint), formatPoint(edge.endPoint), edge.launchStepX);
            };
        }

        private static String formatPoint(Point point) {
            return "(" + point.x + "," + point.y + ")";
        }
    }
}
