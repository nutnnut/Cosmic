package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

/**
 * Diagnostic for the map 100000102 (Henesys department store) stuck bug:
 * travel re-runs region 17 -> region 24 BFS every tick and never succeeds.
 */
class BotHenesysDeptStoreDescentTest {

    private static final int MAP = 100000102;

    @Test
    void dumpGraph() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        System.out.println("=== regions ===");
        for (BotNavigationGraph.Region r : graph.regions) {
            System.out.println("region " + r.id + " rope=" + r.isRopeRegion
                    + " bbox x[" + r.minX + "," + r.maxX + "] y[" + r.minY + "," + r.maxY + "]");
        }
        System.out.println("=== fh->region ===");
        graph.regionIdByFootholdId.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("fh " + e.getKey() + " -> region " + e.getValue()));
        System.out.println("=== edges ===");
        for (BotNavigationGraph.Region r : graph.regions) {
            for (BotNavigationGraph.Edge e : graph.getOutgoing(r.id)) {
                System.out.println("edge r" + e.fromRegionId + " -> r" + e.toRegionId + " " + e.type
                        + " start=" + e.startPoint + " end=" + e.endPoint);
            }
        }
        System.out.println("canReach 17->24: " + graph.canReach(17, 24, 0));
        System.out.println("canReach 24->17: " + graph.canReach(24, 17, 0));
    }

    @Test
    void probeRegionResolution() {
        server.maps.MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        java.awt.Point portal = new java.awt.Point(300, 182);
        System.out.println("portal region = " + graph.findRegionId(map, portal));
        System.out.println("ground fh at portal = " + BotPhysicsEngine.findGroundFoothold(map, portal));
        for (java.awt.Point p : new java.awt.Point[]{
                new java.awt.Point(-147, 98), new java.awt.Point(-30, 103),
                new java.awt.Point(0, 182), new java.awt.Point(0, 184)}) {
            System.out.println("pos " + p.x + "," + p.y + " -> region " + graph.findRegionId(map, p));
        }
        int portalRegion = graph.findRegionId(map, portal);
        for (int from : new int[]{15, 16, 17, 18}) {
            System.out.println("canReach " + from + "->" + portalRegion + " mask0: "
                    + graph.canReach(from, portalRegion, 0));
        }
        System.out.println("canReach 17->25 mask0: " + graph.canReach(17, 25, 0));
        System.out.println("canReach 18->25 mask0: " + graph.canReach(18, 25, 0));
        System.out.println("canReach 25->24 mask0: " + graph.canReach(25, 24, 0));
    }

    @Test
    void simulateDescentFromRegion17ToExitPortal() {
        server.maps.MapleMap map = BotNavigationMapLoader.loadMapGeometry(MAP);
        BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        BotEntry entry = lab.spawnBot("HEN", 1, map, new java.awt.Point(-147, 98));
        lab.setMoveTarget("HEN", new java.awt.Point(300, 182), true);
        for (int i = 0; i < 300; i++) {
            lab.step(1);
            if (i % 10 == 0) {
                java.awt.Point p = lab.position("HEN");
                System.out.println("tick " + i + " pos=" + p.x + "," + p.y
                        + " inAir=" + entry.inAir
                        + " navEdge=" + (entry.navEdge == null ? "null"
                                : entry.navEdge.type + " r" + entry.navEdge.fromRegionId + "->r" + entry.navEdge.toRegionId
                                + " start=" + entry.navEdge.startPoint.x + "," + entry.navEdge.startPoint.y)
                        + " decision=" + entry.lastNavDecision);
            }
        }
        java.awt.Point p = lab.position("HEN");
        System.out.println("final pos=" + p.x + "," + p.y + " decision=" + entry.lastNavDecision);
    }
}
