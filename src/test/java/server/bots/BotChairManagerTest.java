package server.bots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pure-decision coverage for the chair PICK (no WZ / no live bot): empty/single handling, the
 *  neutral-uniform vs obnoxious-biggest weighting. The sprite-size lookup is swapped for a fake. */
class BotChairManagerTest {

    private java.util.function.IntUnaryOperator original;

    @BeforeEach
    void swapSizeSeam() {
        original = BotChairManager.chairSize;
        BotChairManager.clearSizeCacheForTest();
    }

    @AfterEach
    void restoreSizeSeam() {
        BotChairManager.chairSize = original;
        BotChairManager.clearSizeCacheForTest();
    }

    @Test
    void emptyPoolReturnsNoChair() {
        assertEquals(-1, BotChairManager.chooseChair(List.of(), 0.0));
        assertEquals(-1, BotChairManager.chooseChair(List.of(), 1.0));
    }

    @Test
    void singleChairIsAlwaysChosen() {
        assertEquals(3010000, BotChairManager.chooseChair(List.of(3010000), 0.0));
        assertEquals(3010000, BotChairManager.chooseChair(List.of(3010000), 1.0));
    }

    @Test
    void neutralBotPicksChairsRoughlyUniformly() {
        // Size ignored at obnoxiousness 0 (k=0 -> all weights 1), so a tiny and a huge chair are
        // chosen about equally often.
        Map<Integer, Integer> sizes = Map.of(3010000, 10, 3010001, 100_000);
        BotChairManager.chairSize = sizes::get;
        List<Integer> chairs = List.of(3010000, 3010001);
        int big = 0, n = 20_000;
        for (int i = 0; i < n; i++) {
            if (BotChairManager.chooseChair(chairs, 0.0) == 3010001) {
                big++;
            }
        }
        double frac = big / (double) n;
        assertTrue(frac > 0.4 && frac < 0.6, "expected ~uniform, got big-chair frac=" + frac);
    }

    @Test
    void obnoxiousBotHogsTheBiggestChair() {
        Map<Integer, Integer> sizes = Map.of(3010000, 10, 3010001, 100_000);
        BotChairManager.chairSize = sizes::get;
        List<Integer> chairs = List.of(3010000, 3010001);
        int big = 0, n = 20_000;
        for (int i = 0; i < n; i++) {
            if (BotChairManager.chooseChair(chairs, 1.0) == 3010001) {
                big++;
            }
        }
        double frac = big / (double) n;
        assertTrue(frac > 0.95, "obnoxious bot should almost always take the biggest, got frac=" + frac);
    }
}
