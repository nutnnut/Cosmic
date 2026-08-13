package server.bots.pq;

import client.Character;
import server.bots.BotScript;
import server.bots.BotScriptRunner;
import server.bots.BotEntry;

import java.util.List;

/**
 * Single call-site for all per-map party-quest bot automation.
 * BotManager calls {@link #tick} once per bot tick; each PQ class handles its own map range.
 */
public final class BotPqHooks {
    private static final List<BotScript> SCRIPTS = List.of(BotKpqStage1.script());

    private BotPqHooks() {}

    /**
     * Returns true when a PQ machine consumed the whole bot tick (Zakum dead-mine run: the SSOT
     * {@link server.bots.BotZakumPqRun} drives the bot's movement/actions and the normal companion
     * AI must stand down, exactly as it does for the autonomous errand). KPQ keeps its flag-based
     * integration and never consumes the tick here.
     */
    public static boolean tick(BotEntry entry, Character bot, Character owner) {
        BotScriptRunner.tick(entry, bot, owner, SCRIPTS);
        BotKpqStage5.tick(entry, bot);
        return server.bots.BotZakumPqRun.tickSupervised(entry, bot, owner);
    }

    /**
     * Returns true when the bot should stand idle at an NPC and skip normal AI.
     */
    public static boolean isNpcLocked(BotEntry entry) {
        return BotKpqStage1.isNpcLocked(entry);
    }

    /** Returns true if the bot is in a PQ map that requires grind mode (KPQ stage 1). */
    public static boolean requiresGrind(BotEntry entry, Character bot) {
        return bot.getMapId() == BotKpqStage1.KPQ_STAGE1_MAP
                && entry.kpq.state == BotKpqStage1.GRINDING;
    }

    /** True once the bot no longer needs coupons — suppress coupon loot. */
    public static boolean shouldSkipCouponLoot(BotEntry entry) {
        return BotKpqStage1.shouldSkipCouponLoot(entry);
    }

    /** Returns true if the bot is in a PQ map that should default to follow mode (KPQ stages 2-5). */
    public static boolean requiresFollow(BotEntry entry, Character bot) {
        int mapId = bot.getMapId();
        return mapId >= 103000801 && mapId <= 103000805;
    }
}
