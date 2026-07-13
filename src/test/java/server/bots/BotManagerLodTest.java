package server.bots;

import org.junit.jupiter.api.Test;
import server.bots.BotManager.LodDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unobserved-map LOD hysteresis (docs/bot/unobserved-lod-design.md §1, Stage 1). */
class BotManagerLodTest {

    private static final long HYST = 10_000L;

    @Test
    void observedBotIsImmediatelyLod0AndResetsClock() {
        LodDecision d = BotManager.decideLod(BotEntry.Lod.LOD1, 5_000L, true, 100_000L, HYST);
        assertEquals(BotEntry.Lod.LOD0, d.lod());
        assertEquals(0L, d.unobservedSinceMs()); // clock cleared
    }

    @Test
    void becomingUnobservedStartsClockButStaysLod0() {
        // First unobserved tick: clock starts at 'now', LOD unchanged during the window.
        LodDecision d = BotManager.decideLod(BotEntry.Lod.LOD0, 0L, false, 100_000L, HYST);
        assertEquals(BotEntry.Lod.LOD0, d.lod());
        assertEquals(100_000L, d.unobservedSinceMs());
    }

    @Test
    void staysLod0WithinHysteresisWindow() {
        // Player-free since 100_000; only 9.9s later — not yet past the 10s window.
        LodDecision d = BotManager.decideLod(BotEntry.Lod.LOD0, 100_000L, false, 109_900L, HYST);
        assertEquals(BotEntry.Lod.LOD0, d.lod());
        assertEquals(100_000L, d.unobservedSinceMs()); // clock preserved
    }

    @Test
    void downgradesToLod1AfterHysteresis() {
        LodDecision d = BotManager.decideLod(BotEntry.Lod.LOD0, 100_000L, false, 110_000L, HYST);
        assertEquals(BotEntry.Lod.LOD1, d.lod());
    }

    @Test
    void reobservingReturnsToLod0Immediately() {
        // Was LOD1 and unobserved; a player arriving flips it straight back to LOD0 (seamless upgrade).
        LodDecision d = BotManager.decideLod(BotEntry.Lod.LOD1, 100_000L, true, 200_000L, HYST);
        assertEquals(BotEntry.Lod.LOD0, d.lod());
        assertEquals(0L, d.unobservedSinceMs());
    }
}
