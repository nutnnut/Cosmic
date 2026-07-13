package server.bots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * WZ-backed sanity print for the scroll slot-durability calibration (skips silently on WZ-less
 * machines). Asserts the real-economy ordering the factor exists to reproduce: armor ATT scrolls
 * (glove cat 8) price ABOVE same-tier weapon scrolls because scrolled flat-slot gear stays
 * best-in-slot while weapons get outgrown.
 */
class BotSlotDurabilityPrintTest {

    @Test
    void gloveDurabilityShouldBeatWeaponAverage() {
        try {
            tools.DatabaseConnection.initializeConnectionPool();
        } catch (Throwable t) {
            assumeTrue(false, "DB unavailable - skipping WZ/DB-backed calibration check");
        }
        double glove = BotScrollManager.slotDurabilityFactor(8);
        double shoe = BotScrollManager.slotDurabilityFactor(7);
        double cape = BotScrollManager.slotDurabilityFactor(10);
        double oneHandedSword = BotScrollManager.slotDurabilityFactor(30);
        double claw = BotScrollManager.slotDurabilityFactor(47);
        System.out.printf("durability glove=%.2f shoe=%.2f cape=%.2f 1hSword=%.2f claw=%.2f%n",
                glove, shoe, cape, oneHandedSword, claw);
        assumeTrue(glove != 1.0 || claw != 1.0, "WZ data unavailable - nothing calibrated");
        org.junit.jupiter.api.Assertions.assertTrue(glove > 1.2,
                "glove scrolls should carry a durability premium over weapons, got " + glove);
        org.junit.jupiter.api.Assertions.assertTrue(shoe > 1.0,
                "shoe scrolls should not price below weapon scrolls, got " + shoe);
    }
}
