package server.bots.combat;

import client.BuffStat;
import client.Character;
import client.Job;
import server.life.Monster;

import java.util.EnumMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

public final class BotDefenseDataProvider {
    private static final double MIN_DAMAGE_FACTOR = 0.008d;
    private static final double MAX_DAMAGE_FACTOR = 0.0085d;
    private static final BotDefenseDataProvider instance = new BotDefenseDataProvider();

    private enum JobFamily {
        BEGINNER,
        WARRIOR,
        MAGICIAN,
        ARCHER,
        THIEF,
        PIRATE
    }

    private final Map<JobFamily, NavigableMap<Integer, Integer>> standardPddTables = new EnumMap<>(JobFamily.class);

    public static BotDefenseDataProvider getInstance() {
        return instance;
    }

    private BotDefenseDataProvider() {
        standardPddTables.put(JobFamily.BEGINNER, createTable(
                entry(1, 7),
                entry(5, 17),
                entry(8, 19)
        ));
        standardPddTables.put(JobFamily.WARRIOR, createTable(
                entry(10, 54),
                entry(12, 57),
                entry(15, 83),
                entry(20, 106),
                entry(22, 109),
                entry(25, 129),
                entry(30, 154),
                entry(35, 179),
                entry(40, 203),
                entry(47, 208),
                entry(50, 261),
                entry(55, 267),
                entry(60, 305),
                entry(65, 308),
                entry(70, 359),
                entry(75, 356),
                entry(80, 382),
                entry(85, 388),
                entry(90, 440),
                entry(95, 446),
                entry(100, 494)
        ));
        standardPddTables.put(JobFamily.MAGICIAN, createTable(
                entry(8, 25),
                entry(10, 31),
                entry(13, 40),
                entry(15, 49),
                entry(18, 54),
                entry(20, 56),
                entry(25, 60),
                entry(28, 64),
                entry(30, 75),
                entry(33, 91),
                entry(35, 98),
                entry(40, 99),
                entry(48, 107),
                entry(50, 131),
                entry(55, 134),
                entry(58, 142),
                entry(60, 159),
                entry(65, 162),
                entry(68, 170),
                entry(70, 184),
                entry(75, 190),
                entry(78, 198),
                entry(80, 212),
                entry(85, 218),
                entry(88, 226),
                entry(90, 240),
                entry(95, 246),
                entry(98, 254),
                entry(100, 266)
        ));
        standardPddTables.put(JobFamily.ARCHER, createTable(
                entry(10, 32),
                entry(15, 49),
                entry(20, 65),
                entry(25, 80),
                entry(30, 95),
                entry(35, 110),
                entry(40, 125),
                entry(50, 145),
                entry(55, 148),
                entry(60, 177),
                entry(65, 180),
                entry(70, 206),
                entry(75, 212),
                entry(80, 238),
                entry(85, 244),
                entry(90, 270),
                entry(95, 276),
                entry(100, 298)
        ));
        standardPddTables.put(JobFamily.THIEF, createTable(
                entry(10, 42),
                entry(15, 60),
                entry(20, 76),
                entry(22, 85),
                entry(25, 100),
                entry(30, 115),
                entry(32, 116),
                entry(35, 131),
                entry(37, 132),
                entry(40, 147),
                entry(50, 184),
                entry(55, 187),
                entry(60, 220),
                entry(65, 223),
                entry(70, 257),
                entry(75, 263),
                entry(80, 291),
                entry(85, 297),
                entry(90, 325),
                entry(95, 331),
                entry(100, 331)
        ));
        standardPddTables.put(JobFamily.PIRATE, createTable(
                entry(10, 24),
                entry(15, 43),
                entry(20, 56),
                entry(25, 69),
                entry(30, 82),
                entry(35, 95),
                entry(40, 108),
                entry(50, 146),
                entry(55, 149),
                entry(60, 178),
                entry(65, 181),
                entry(70, 207),
                entry(75, 213),
                entry(80, 239),
                entry(85, 245),
                entry(90, 271),
                entry(100, 309)
        ));
    }

    public int rollPhysicalTouchDamage(Character bot, Monster mob) {
        int physicalAttackDamage = Math.max(0, mob.getPADamage());
        if (physicalAttackDamage <= 0) {
            return 1;
        }
        if (!server.combat.CombatFormulaProvider.getInstance().doesMobHit(bot, mob)) {
            return 0;
        }
        double randomFactor = ThreadLocalRandom.current().nextDouble(MIN_DAMAGE_FACTOR, Math.nextUp(MAX_DAMAGE_FACTOR));
        return physicalTouchDamage(bot, physicalAttackDamage, mob.getLevel(), randomFactor);
    }

    /** Expected touch damage ON A LANDED HIT (mean damage factor, no hit roll) — the score-side
     *  companion to {@link #rollPhysicalTouchDamage}. Stat-based (no live {@link Monster} needed) so
     *  the grind advisor can value maps it isn't standing on. */
    public int expectedPhysicalTouchDamageOnHit(Character bot, int mobPADamage, int mobLevel) {
        int pad = Math.max(0, mobPADamage);
        if (pad <= 0) {
            return 1;
        }
        double meanFactor = (MIN_DAMAGE_FACTOR + MAX_DAMAGE_FACTOR) / 2.0;
        return physicalTouchDamage(bot, pad, mobLevel, meanFactor);
    }

    /**
     * Expected fraction of the bot's max HP lost per mob touch-attack ATTEMPT: P(mob lands) x
     * E[damage | hit] / maxHp. The SSOT "how dangerous is this mob to me" primitive — reuses the real
     * avoid (CombatFormulaProvider) and defense (this provider's damage curve) formulas, so grind-map
     * valuation and safe-rest/idle region picking agree with what actually happens in combat.
     */
    public double expectedTouchHpLossFraction(Character bot, int mobPADamage, int mobLevel, int mobAccuracy) {
        int maxHp = Math.max(1, effectiveMaxHp(bot));
        var formulas = server.combat.CombatFormulaProvider.getInstance();
        double pHit = formulas.calculateBotAvoidChance(
                mobAccuracy, mobLevel, bot.getLevel(), formulas.getTotalAvoidability(bot));
        int dmg = expectedPhysicalTouchDamageOnHit(bot, mobPADamage, mobLevel);
        return pHit * dmg / maxHp;
    }

    /** Live-mob convenience for the SSOT danger primitive. */
    public double expectedTouchHpLossFraction(Character bot, Monster mob) {
        return expectedTouchHpLossFraction(bot, mob.getPADamage(), mob.getLevel(), mob.getAccuracy());
    }

    public static int effectiveMaxHp(Character bot) {
        int maxHp = Math.max(1, bot.getCurrentMaxHp());
        Integer magicGuard = bot.getBuffedValue(BuffStat.MAGIC_GUARD);
        if (magicGuard == null) {
            return maxHp;
        }
        double mgPct = magicGuard.doubleValue() / 100.0;
        if (mgPct <= 0.0 || mgPct >= 1.0) {
            return maxHp;
        }
        int mp = Math.max(0, bot.getMp());
        double totalFromHp = maxHp / (1.0 - mgPct);
        double totalFromMp = mp / mgPct;
        return Math.max(maxHp, (int) Math.min(totalFromHp, totalFromMp));
    }

    private int physicalTouchDamage(Character bot, int physicalAttackDamage, int mobLevel, double factor) {
        int standardPdd = getStandardPdd(bot.getJob(), bot.getLevel());
        int wdef = Math.max(0, bot.getTotalWdef());
        double c = computeC(bot);
        double a = c + 0.28d;
        double b = computeB(bot, mobLevel, c, wdef, standardPdd);
        double damage = (physicalAttackDamage * (double) physicalAttackDamage * factor)
                - (wdef * a)
                - ((wdef - standardPdd) * b);
        return Math.max(1, (int) Math.floor(damage));
    }

    public int getStandardPdd(Job job, int level) {
        NavigableMap<Integer, Integer> table = standardPddTables.get(resolveJobFamily(job));
        if (table == null || table.isEmpty()) {
            return 0;
        }

        Map.Entry<Integer, Integer> match = table.floorEntry(level);
        if (match != null) {
            return match.getValue();
        }

        return table.firstEntry().getValue();
    }

    private double computeC(Character bot) {
        if (resolveJobFamily(bot.getJob()) == JobFamily.WARRIOR) {
            return bot.getTotalStr() / 2800.0d
                    + bot.getTotalDex() / 3200.0d
                    + bot.getTotalInt() / 7200.0d
                    + bot.getTotalLuk() / 3200.0d;
        }

        return bot.getTotalStr() / 2000.0d
                + bot.getTotalDex() / 2800.0d
                + bot.getTotalInt() / 7200.0d
                + bot.getTotalLuk() / 3200.0d;
    }

    private double computeB(Character bot, int mobLevel, double c, int wdef, int standardPdd) {
        if (wdef >= standardPdd) {
            return (c * 28.0d / 45.0d)
                    + (bot.getLevel() * 7.0d / 13000.0d)
                    + 0.196d;
        }

        return computeLevelFactor(bot.getLevel(), mobLevel)
                * (c + (bot.getLevel() / 550.0d) + 0.28d);
    }

    private double computeLevelFactor(int characterLevel, int mobLevel) {
        if (characterLevel >= mobLevel) {
            return 13.0d / (13.0d + characterLevel - mobLevel);
        }

        return 1.3d;
    }

    private JobFamily resolveJobFamily(Job job) {
        if (job == null) {
            return JobFamily.BEGINNER;
        }

        return switch (job.getId() / 100) {
            case 1, 11, 21 -> JobFamily.WARRIOR;
            case 2, 12, 22 -> JobFamily.MAGICIAN;
            case 3, 13 -> JobFamily.ARCHER;
            case 4, 14 -> JobFamily.THIEF;
            case 5, 15 -> JobFamily.PIRATE;
            default -> JobFamily.BEGINNER;
        };
    }

    private static NavigableMap<Integer, Integer> createTable(int[]... entries) {
        TreeMap<Integer, Integer> table = new TreeMap<>();
        for (int[] entry : entries) {
            table.put(entry[0], entry[1]);
        }
        return table;
    }

    private static int[] entry(int level, int standardPdd) {
        return new int[]{level, standardPdd};
    }
}
