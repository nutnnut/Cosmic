package server.bots;

/**
 * Farming-cost ("rarity → meso") model: the expected meso-equivalent <em>effort</em> to obtain one of
 * an item by killing the mobs that drop it, from the perspective of a given producer (the asking bot).
 * This replaces the flat / level-scaled clean-base and drop-only-scroll stubs in
 * {@link BotScrollManager} with a number grounded in real drop rate and real kill time.
 *
 * <pre>
 * rarityMeso = expectedKills × (secondsPerKill + seekOverhead) × mesoPerSecond
 *   expectedKills  = 1 / baseDropRate           (drop_data chance / 1,000,000; capped)
 *   secondsPerKill = max(attackCycle, mobHp / damagePerSecond)
 *   seekOverhead   = flat travel/respawn-wait placeholder per kill
 * </pre>
 *
 * <p><b>Realistic, capped kill time.</b> {@code secondsPerKill} is floored at one attack cycle — you
 * cannot kill faster than a single swing — so a tiny-HP mob with huge DPS does <em>not</em> yield
 * "1000 kills/sec"; it yields one kill per swing. A producer that cannot meaningfully damage the mob
 * (zero DPS) gets {@code +∞}, so the caller falls back to other sources (matching the design's
 * "∞ when unreachable").
 *
 * <p><b>Deferred:</b> {@code seekOverhead} is a flat constant standing in for the real mob-commonness
 * term (spawns/map, #maps, respawn) which lives in the Map WZ and needs the spawn cache to index. The
 * producer's DPS here is a physical-attack estimate (see the wiring in {@link BotScrollManager}).
 *
 * <p>Pure and unit-tested in isolation; the wiring layer supplies the primitives.
 */
final class BotFarmingCostModel {

    /** Cap on expected kills so a near-zero drop rate gives a bounded (very large) cost rather than
     *  infinity — "practically unobtainable", not a divide-by-zero. */
    static final double MAX_EXPECTED_KILLS = 1_000_000.0;
    /** Floor on seconds per kill: even a 1-HP mob takes at least one attack swing. */
    static final double MIN_SECONDS_PER_KILL = 0.6;

    private BotFarmingCostModel() {}

    /**
     * @param baseDropRate       obtain probability per kill (drop_data chance / 1,000,000; &gt;1 ⇒ guaranteed).
     * @param mobHp              the dropper mob's max HP.
     * @param damagePerSecond    the producer's expected damage/sec against that mob (after its defense).
     * @param attackCycleSeconds the producer's attack period — the floor for time-to-kill.
     * @param seekOverheadSeconds flat per-kill travel/wait overhead (spawn-density placeholder).
     * @param mesoPerSecond      effort→meso anchor (how much a second of farming is worth).
     */
    record FarmInput(double baseDropRate, double mobHp, double damagePerSecond,
                     double attackCycleSeconds, double seekOverheadSeconds, double mesoPerSecond) {}

    /** Expected meso-equivalent effort to obtain one of the item, or {@code +∞} when un-farmable
     *  (no drop rate, or the producer can't damage the mob). */
    static double rarityMeso(FarmInput in) {
        if (in.mesoPerSecond() <= 0 || in.baseDropRate() <= 0 || in.damagePerSecond() <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double expectedKills = Math.min(MAX_EXPECTED_KILLS, 1.0 / Math.min(1.0, in.baseDropRate()));
        double effortSeconds = expectedKills * (secondsPerKill(in) + Math.max(0.0, in.seekOverheadSeconds()));
        return effortSeconds * in.mesoPerSecond();
    }

    /** Realistic, capped time to kill one mob: at least one attack cycle, otherwise HP ÷ DPS. */
    static double secondsPerKill(FarmInput in) {
        if (in.damagePerSecond() <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double cycle = in.attackCycleSeconds() > 0 ? in.attackCycleSeconds() : MIN_SECONDS_PER_KILL;
        double floor = Math.max(MIN_SECONDS_PER_KILL, cycle);
        return Math.max(floor, in.mobHp() / in.damagePerSecond());
    }
}
