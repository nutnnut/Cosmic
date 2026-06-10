package server.bots;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotWorldGraphTest {

    @Test
    void shouldRouteShortestPathWithinHopCap() {
        BotWorldGraph.Index graph = BotWorldGraph.indexOf(Map.of(
                1, new int[]{2, 5},
                2, new int[]{1, 3},
                3, new int[]{2, 4},
                5, new int[]{4},
                4, new int[0]));

        assertEquals(List.of(), BotWorldGraph.route(graph, 1, 1, 4));
        assertEquals(List.of(2), BotWorldGraph.route(graph, 1, 2, 4));
        // 1→5→4 beats 1→2→3→4.
        assertEquals(List.of(5, 4), BotWorldGraph.route(graph, 1, 4, 4));
        assertNull(BotWorldGraph.route(graph, 1, 4, 1)); // beyond the hop cap
        assertNull(BotWorldGraph.route(graph, 4, 1, 8)); // edges are directed; 4 is a sink
    }

    @Test
    void shouldBuildWorldGraphFromWz() {
        BotWorldGraph.Index graph = BotWorldGraph.get();

        // The world has thousands of connected fields.
        assertTrue(graph.edges().size() > 4000, "expected most maps indexed, got " + graph.edges().size());

        // Ground truth from Map.wz 104040000 (Henesys Hunting Ground I): east00 → Henesys,
        // west00 → 104030000 (both pt=2, unscripted).
        int[] neighbors = graph.neighbors(104040000);
        assertTrue(Arrays.stream(neighbors).anyMatch(t -> t == 100000000),
                "hunting ground should connect to Henesys, got " + Arrays.toString(neighbors));
        assertTrue(Arrays.stream(neighbors).anyMatch(t -> t == 104030000),
                "hunting ground should connect to 104030000, got " + Arrays.toString(neighbors));

        // Adjacent map routes in one hop; a farther map routes in a few, ending at the target.
        assertEquals(List.of(100000000), BotWorldGraph.route(104040000, 100000000, 4));
        List<Integer> multiHop = BotWorldGraph.route(104030000, 100000000, 4);
        assertNotNull(multiHop, "104030000 should reach Henesys within 4 hops");
        assertEquals(100000000, multiHop.get(multiHop.size() - 1));
    }
}
