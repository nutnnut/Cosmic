package server.bots;

import client.Character;
import server.life.LifeFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In-session "take a break" behavior: a grinding bot periodically stops and idles for a while instead
 * of farming non-stop, so even while online it isn't a 24/7 grinder. Frequency and length come from
 * the bot's {@link BotPersonality} ({@code breakFreqPerHour}, {@code breakLenMeanMin}); diligent bots
 * (high {@code farmIdleRatio}) break less. Pure decision seam ({@link #startsBreak}) for unit testing;
 * the idle execution reuses the grind tick's held-anchor idle (see {@code BotManager.tickGrindMode}).
 */
final class BotBreakManager {
    private static final List<String> BREAK_MSGS = List.of(
            "taking a quick break", "brb, resting a sec", "gonna idle a bit");
    private static final List<String> RESUME_MSGS = List.of(
            "ok, back to it", "break over, grinding", "alright, back at it");

    private BotBreakManager() {}

    static boolean onBreak(BotEntry entry, long now) {
        return now < entry.breakUntilMs;
    }

    /** True during a deliberate rest break — parked in a town or at a chosen nearby safe map — where a
     *  gacha side-trip is fine, as opposed to a short in-place break on the grind map (mobs around). */
    static boolean onRestBreak(BotEntry entry, Character bot, long now) {
        return onBreak(entry, now) && bot.getMap() != null
                && (bot.getMap().isTown() || entry.restErrand);
    }

    /**
     * Pure per-minute start decision. {@code rollUnit} is a uniform [0,1) sample; the per-minute break
     * probability is {@code breakFreqPerHour/60}, damped by diligence (a high farm/idle ratio breaks
     * less). Bounded so a degenerate profile can't break every minute.
     */
    static boolean startsBreak(double breakFreqPerHour, double farmIdleRatio, double rollUnit) {
        double perMinute = Math.max(0.0, breakFreqPerHour) / 60.0;
        perMinute *= (1.0 - 0.5 * clamp01(farmIdleRatio)); // diligent bots break less often
        return rollUnit < Math.min(0.5, perMinute);
    }

    /** Jittered break length in ms from the personality mean (0.5x..1.5x). */
    static long breakDurationMs(int breakLenMeanMin) {
        int meanMin = Math.max(1, breakLenMeanMin);
        double factor = 0.5 + ThreadLocalRandom.current().nextDouble(); // 0.5..1.5
        return Math.round(meanMin * 60_000L * factor);
    }

    /** A town-break lingers in town (sell/resupply + self-scroll), longer than an in-place break. Scales
     *  with laziness: a diligent bot rests 10-30 min, a maximally lazy one 20-60 min. */
    static long townBreakDurationMs(double laziness) {
        double lazy = clamp01(laziness);
        long lowMs = Math.round((10 + 10 * lazy) * 60_000.0);   // 10..20 min floor
        long spanMs = Math.round((20 + 40 * lazy) * 60_000.0);  // 20..60 min span
        return lowMs + (long) (ThreadLocalRandom.current().nextDouble() * spanMs);
    }

    /**
     * Roll (at most once a minute) whether a grinding bot should start a break; if so, arm
     * {@code breakUntilMs} and announce. No-op while already on a break or off cooldown.
     */
    static void maybeStartBreak(BotEntry entry, Character bot, long now) {
        if (onBreak(entry, now) || entry.restErrand || now < entry.nextBreakRollAtMs) {
            return;
        }
        // Stay-online QoL: a bot grouped with a real player doesn't wander off on a break.
        if (BotManager.partyHasRealPlayer(bot)) {
            return;
        }
        entry.nextBreakRollAtMs = now + 60_000L;
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        if (!startsBreak(p.breakFreqPerHour(), p.farmIdleRatio(), ThreadLocalRandom.current().nextDouble())) {
            return;
        }
        // Self-scroll bots on autopilot take their break in a TOWN (sell trash + resupply + tinker with
        // gear there); already in a town -> rest right here. Everyone else keeps the in-place break.
        if (entry.selfScrollEnabled && BotAutopilotManager.isActive(entry) && bot.getMap() != null) {
            startTownBreak(entry, bot, now);
        } else {
            entry.breakUntilMs = now + breakDurationMs(p.breakLenMeanMin());
            entry.breakIdleAnchor = null;
        }
        if (ThreadLocalRandom.current().nextDouble() < p.chattiness()) {
            BotManager.getInstance().botSay(bot, BotManager.randomReply(BREAK_MSGS));
        }
    }

    /** Begin a town-break for one bot: rest in place if already in a town, else flag a rest errand so
     *  the autopilot routes it to a town (the in-town rest clock starts on arrival). Shared by the solo
     *  break roll and the leader-driven group break. */
    static void startTownBreak(BotEntry entry, Character bot, long now) {
        if (bot.getMap() != null && bot.getMap().isTown()) {
            BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
            entry.breakUntilMs = now + townBreakDurationMs(p.laziness());
            entry.breakIdleAnchor = null;
        } else {
            entry.restErrand = true;
        }
    }

    /**
     * Whether a cohort member should SKIP a group break and keep grinding to catch up: it sits in the
     * low-level cluster, separated from the pack by a gap of at least {@code trigger} levels. Members at
     * or below the first {@code >= trigger} jump from the bottom of the sorted cohort levels split off
     * (so a low pair like 10,11 below a 20,21 pack both split); no qualifying gap -> nobody splits.
     */
    static boolean catchUpSplit(int memberLevel, int[] sortedAscLevels, int trigger) {
        if (sortedAscLevels.length < 2 || trigger <= 0) {
            return false;
        }
        int lowClusterMax = sortedAscLevels[0];
        for (int i = 1; i < sortedAscLevels.length; i++) {
            if (sortedAscLevels[i] - sortedAscLevels[i - 1] >= trigger) {
                break; // first gap from the bottom: lowClusterMax is the level just below it
            }
            lowClusterMax = sortedAscLevels[i];
        }
        return lowClusterMax < sortedAscLevels[sortedAscLevels.length - 1] && memberLevel <= lowClusterMax;
    }

    /** Called when a break ends to clear state and optionally announce the resume. */
    static void endBreak(BotEntry entry, Character bot) {
        entry.breakUntilMs = 0L;
        entry.breakIdleAnchor = null;
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        if (bot != null && ThreadLocalRandom.current().nextDouble() < p.chattiness()) {
            BotManager.getInstance().botSay(bot, BotManager.randomReply(RESUME_MSGS));
        }
    }

    // ---- break-location choice: town vs a nearby safe map -------------------------------------------
    // Deep grind spots shouldn't waste a full town round-trip every break. Getting TO town is a free
    // return scroll; the cost is the walk BACK, measured in hops. The deeper that walk, the more often
    // the bot rests at a nearby safe map instead.

    /** Within this many hops of the grind map still counts as "nearby" for an in-the-field break.
     *  ponytail: fixed small radius — a break map farther than this is barely closer than town anyway. */
    private static final int NEARBY_BREAK_MAX_HOPS = 5;
    private static final int HOP_CAP = 30;

    /** Hop count of the walk back from {@code from} to {@code to}; large sentinel when unreachable. */
    static int hopsBack(int from, int to) {
        if (from == to) {
            return 0;
        }
        List<Integer> route = BotWorldGraph.route(from, to, HOP_CAP);
        return route == null ? 999 : route.size() - 1;
    }

    /** Chance of bothering with a full town break given the hops to walk back to the grind spot:
     *  90% at 1 hop, linearly down to 10% at 10 hops (a soft cap — deeper just stays at 10%). */
    static double townBreakChance(int hopsBackToGrind) {
        double p = 0.9 - (hopsBackToGrind - 1) / 9.0 * 0.8;
        return Math.max(0.1, Math.min(0.9, p));
    }

    /** Find a nearby map to rest on instead of trekking to town: closer to the grind map than town is,
     *  preferring a no-mob map, then a map whose mobs can't fly (the on-arrival safe-idle picker parks
     *  on a platform away from ground mobs). Returns -1 when nothing closer qualifies (caller falls back
     *  to town). ponytail: "no mob can JUMP" and "a safe platform EXISTS" aren't checkable here — no jump
     *  flag exists in the server and platform reachability needs the map loaded; we gate on fly-capability
     *  only and trust the existing safe-idle picker once the bot arrives. */
    static int findNearbyBreakMap(int grindMap, int townHops) {
        int radius = Math.min(NEARBY_BREAK_MAX_HOPS, townHops - 1);
        if (radius < 1) {
            return -1; // town is already adjacent — nothing closer to find
        }
        BotSpawnIndex.Index idx = BotSpawnIndex.get();
        int bestNoMob = -1, bestNoFly = -1;
        int bestNoMobHops = Integer.MAX_VALUE, bestNoFlyHops = Integer.MAX_VALUE;
        for (int mapId : BotWorldGraph.reachableWithin(grindMap, radius)) {
            if (mapId == grindMap) {
                continue;
            }
            int hops = hopsBack(grindMap, mapId);
            if (hops < 1 || hops >= townHops) {
                continue; // must be strictly closer to the grind map than town is
            }
            int tier = breakMapTier(idx, mapId);
            if (tier == 1 && hops < bestNoMobHops) {
                bestNoMob = mapId;
                bestNoMobHops = hops;
            } else if (tier == 2 && hops < bestNoFlyHops) {
                bestNoFly = mapId;
                bestNoFlyHops = hops;
            }
        }
        return bestNoMob != -1 ? bestNoMob : bestNoFly; // no-mob beats no-fly; -1 if neither found
    }

    /** 1 = no monster spawns (safest), 2 = has mobs but none can fly, 0 = a flying mob makes any spot unsafe. */
    private static int breakMapTier(BotSpawnIndex.Index idx, int mapId) {
        BotSpawnIndex.MapSpawns sp = idx == null ? null : idx.byMap().get(mapId);
        if (sp == null || sp.mobCounts().isEmpty()) {
            return 1; // no spawn points at all
        }
        for (int mobId : sp.mobCounts().keySet()) {
            if (mobCanFly(mobId)) {
                return 0;
            }
        }
        return 2;
    }

    /** Whether a mob has a "fly" animation (could reach an elevated idle platform). Unknown -> unsafe. */
    static boolean mobCanFly(int mobId) {
        try {
            var m = LifeFactory.getMonster(mobId);
            return m == null || m.getStats().animationTimes.containsKey("fly");
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(1.0, v);
    }
}
