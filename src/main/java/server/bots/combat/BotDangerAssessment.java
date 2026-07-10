package server.bots.combat;

import client.Character;
import server.life.Monster;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-preservation (combat-side): how dangerous is a mob's *contact/touch* damage to this bot?
 *
 * <p>This is the COMBAT counterpart to the travel-side risk layer. It does NOT compute damage on its
 * own — it wraps the SSOT {@link BotDefenseDataProvider#rollPhysicalTouchDamage(Character, Monster)}
 * (the player-matching contact-damage formula that already accounts for the bot's WDEF, job/level
 * standard PDD, and the mob's PAD). We only sample that roll and turn it into a hits-to-kill verdict.
 *
 * <p>Used by two consumers so danger scoring stays non-redundant:
 * <ul>
 *   <li>target selection — a soft scoring penalty that de-prioritizes touch-dangerous mobs for a
 *       fragile bot (never a hard skip, so the bot still fights if nothing safer exists);</li>
 *   <li>proactive retreat — disengage a touch-dangerous mob while HP is still healthy, distinct from
 *       the reactive low-HP potion/heal path.</li>
 * </ul>
 */
public final class BotDangerAssessment {

    /** Samples of the random touch-damage roll; we take the max as the worst-case contact hit. */
    private static final int SAMPLES = 6;

    /** Cache the worst-case touch damage per (bot, mob, level, WDEF). WDEF is in the key because bots
     *  auto-equip drops/owner gifts mid-life with NO level change — keying on level alone went stale
     *  (a re-armored bot kept reading its old, higher touch damage and fleeing now-safe mobs). The mob
     *  side (PAD) is static per mobId; maxHp isn't cached (the verdict reads it live). */
    private record DamageKey(int botId, int mobId, int botLevel, int botWdef) {
    }

    private static final Map<DamageKey, Integer> DAMAGE_CACHE = new ConcurrentHashMap<>();
    /** Coarse cap so the cache can't grow without bound over long server uptime (many bot/mob/gear
     *  combos). The estimate is cheap to recompute, so a hard clear at the cap is fine. */
    private static final int DAMAGE_CACHE_MAX = 20_000;

    private BotDangerAssessment() {
    }

    /**
     * Worst-case physical touch (contact) damage this mob can deal the bot, after the bot's armor.
     * Wraps the SSOT roll (sampled {@link #SAMPLES} times, max taken — the roll has a random factor
     * and a hit/miss gate, so a single call would understate the threat on a miss). Cached per
     * (botId, mobId, botLevel, botWdef) so a level-up OR a gear swap (bots re-equip without leveling)
     * invalidates the entry; the cache is size-capped ({@link #DAMAGE_CACHE_MAX}) against unbounded growth.
     */
    public static int estimateMaxTouchDamage(Character bot, Monster mob) {
        if (bot == null || mob == null) {
            return 0;
        }
        DamageKey key = new DamageKey(bot.getId(), mob.getId(), bot.getLevel(), bot.getTotalWdef());
        Integer cached = DAMAGE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int worst = rollWorstCaseTouchDamage(bot, mob);
        if (DAMAGE_CACHE.size() >= DAMAGE_CACHE_MAX) {
            DAMAGE_CACHE.clear();
        }
        DAMAGE_CACHE.put(key, worst);
        return worst;
    }

    /** Uncached worst-case sample of the SSOT touch-damage roll. */
    static int rollWorstCaseTouchDamage(Character bot, Monster mob) {
        BotDefenseDataProvider defense = BotDefenseDataProvider.getInstance();
        int worst = 0;
        for (int i = 0; i < SAMPLES; i++) {
            worst = Math.max(worst, defense.rollPhysicalTouchDamage(bot, mob));
        }
        return worst;
    }

    /**
     * True when a single contact hit from {@code mob} would kill the bot in {@code hitsToKill} or
     * fewer hits, i.e. {@code maxHp / worstTouchDamage <= hitsToKill}. {@code hitsToKill} is the
     * tunable knob (lower = more cautious). A mob that can't touch the bot (0 damage) is never
     * dangerous; a one-shot threat (damage &gt;= maxHp) always is.
     */
    public static boolean isTouchDangerous(Character bot, Monster mob, int hitsToKill) {
        if (bot == null || mob == null || hitsToKill <= 0) {
            return false;
        }
        return isTouchDangerous(bot.getMaxHp(), estimateMaxTouchDamage(bot, mob), hitsToKill);
    }

    /**
     * Pure verdict over already-resolved numbers (WZ-free, unit-testable): a bot with {@code maxHp}
     * facing a worst-case contact hit of {@code touchDamage} is in danger if it would die in
     * {@code hitsToKill} or fewer such hits.
     */
    public static boolean isTouchDangerous(int maxHp, int touchDamage, int hitsToKill) {
        if (touchDamage <= 0 || hitsToKill <= 0 || maxHp <= 0) {
            return false;
        }
        // maxHp / touchDamage <= hitsToKill, integer-safe (floor division matches "survivable hits").
        return maxHp / touchDamage <= hitsToKill;
    }
}
