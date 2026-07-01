package server.bots;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure (WZ/DB-free) checks on the equip-value SSOT's survivability term — the stats {@code offenseValue}
 * ignores. Guards that a defensive piece (e.g. the Old Raggedy Cape's +10 avoid) is no longer worth 0.
 */
class BotScrollManagerTest {

    @Test
    void survivalValueCountsDefensiveAndUtilityStats() {
        // +10 avoid only (the Old Raggedy Cape) -> nonzero now (was 0 under the offense-only metric).
        assertTrue(BotScrollManager.survivalValueFromStats(Map.of("EVA", 10)) > 0.0,
                "avoid must register survival value");

        // A pure-offense stat line carries no survival worth (offense is valued separately).
        assertEquals(0.0, BotScrollManager.survivalValueFromStats(Map.of("PAD", 12, "STR", 5)), 1e-9);

        // WDEF/MDEF/HP/MP/move all contribute and stack above a small avoid-only cape.
        double bundle = BotScrollManager.survivalValueFromStats(
                Map.of("PDD", 100, "MDD", 50, "MHP", 300, "MMP", 100, "Speed", 5, "Jump", 5));
        assertTrue(bundle > BotScrollManager.survivalValueFromStats(Map.of("EVA", 10)),
                "a defensive bundle outweighs a single small avoid roll");
    }

    @Test
    void marketStatValueIsJobAgnosticBestBuyer() {
        // Attack weighted far above a stat point (ATT_WEIGHT 5 vs main-stat 1).
        assertEquals(10.0, BotScrollManager.marketStatValue(Map.of("PAD", 2)), 1e-9);
        // Every base stat at its best (main) weight, no job lookup: an INT scroll keeps a mage's value
        // even with no bot in sight, and STR/DEX/LUK all weigh the same per point.
        assertEquals(2.0, BotScrollManager.marketStatValue(Map.of("INT", 2)), 1e-9);
        assertEquals(BotScrollManager.marketStatValue(Map.of("STR", 3)),
                BotScrollManager.marketStatValue(Map.of("LUK", 3)), 1e-9);
        // An attack scroll out-values an equal-point stat scroll.
        assertTrue(BotScrollManager.marketStatValue(Map.of("PAD", 2))
                > BotScrollManager.marketStatValue(Map.of("DEX", 2)));
    }
}
