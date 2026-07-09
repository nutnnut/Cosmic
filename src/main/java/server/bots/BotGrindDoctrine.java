package server.bots;

import client.Character;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Map-archetype grind positioning ("doctrine"): where on the map a grinding bot works and how it
 * moves between kills. Sits between the grind advisor (which map) and the target scorer (which
 * mob) — it only FILTERS/biases target selection and idle positioning, never replaces the scoring.
 * Ported from SoloMapling v0.3 (docs/bot/kb/kb_bot_solomapling_grind_v03.md), adapted to this
 * fork's pipeline: applies to everything that flows through findGrindTarget (solo autopilot,
 * party cohorts, real-player takeover), leaving follow-mode local attacks untouched.
 *
 * Styles, chosen once per (bot, map) from the spot profile ({@link BotGrindSpots}):
 * <ul>
 *   <li><b>CAMP</b> — claim the best Spot, fight only mobs on its ledge, wait for respawns,
 *       relocate when patiently dry AND unproductive.</li>
 *   <li><b>PATROL</b> — SPREAD maps: same as CAMP but rotates a small x-sorted ring of spots.</li>
 *   <li><b>STACK</b> — vertically layered spot columns (traversable by the bot): tether to the
 *       whole column's box and fight across its floors.</li>
 *   <li><b>ROAM</b> (style == null) — no doctrine: today's map-wide best-score chase.</li>
 * </ul>
 * Toggle: {@code BotCombatManager.cfg.GRIND_DOCTRINE_ENABLED} (off = pre-doctrine behavior,
 * everything roams). Owner-issued patrol commands ({@code entry.patrolRegionId}) always win.
 */
final class BotGrindDoctrine {

    enum Style {CAMP, PATROL, STACK}

    /** Sticky-target margin past the spot radius (mobs drift while fighting). */
    static final int ACQUIRE_MARGIN_PX = 130;
    /** "On the anchor's ledge" approximation for candidate gating (avoids per-mob graph lookups). */
    static final int SPOT_TARGET_Y_BAND = 80;
    static final long RELOCATE_EXCLUDE_MS = 30_000L;
    static final int STACK_TETHER_X_PAD = 130;
    static final int STACK_TETHER_Y_PAD = 60;
    static final double STACK_DOMINANCE = 1.5;   // stack must out-feed the best single ledge by this
    static final int STACK_MIN_FEED = 8;
    static final int PATROL_RING_MAX = 4;
    static final int PATROL_MIN_SPOTS = 3;

    private BotGrindDoctrine() {
    }

    static boolean active(BotEntry entry) {
        return entry != null && entry.grindSpotAnchor != null && entry.grindDoctrineStyle != null;
    }

    /**
     * Per-AI-tick state upkeep: (re)build the profile on map change, choose a style, select/claim a
     * spot, renew the claim, and relocate when the spot has been dry AND unproductive long enough.
     * Cheap after the first tick on a map (profile is cached; selection only reruns on relocate).
     */
    static void update(BotEntry entry, Character bot, long now) {
        if (!BotCombatManager.cfg.GRIND_DOCTRINE_ENABLED || entry == null || bot == null
                || entry.patrolRegionId >= 0) {
            clear(entry, bot);
            return;
        }
        MapleMap map = bot.getMap();
        if (map == null) {
            return;
        }
        if (entry.grindDoctrineMapId != map.getId()) {
            clear(entry, bot);
            entry.grindDoctrineMapId = map.getId();
        }
        if (entry.grindDoctrineProfile == null) {
            BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, entry.movementProfile);
            BotGrindSpots.Profile profile = BotGrindSpots.profileFor(map, graph, entry.movementProfile);
            if (profile == null) {
                return; // graph still warming — roam until ledge identity exists
            }
            entry.grindDoctrineProfile = profile;
            entry.grindDoctrineStyle = chooseStyle(bot, profile);
        }
        if (entry.grindDoctrineStyle == null) {
            return; // ROAM
        }
        if (entry.grindSpotAnchor == null && !selectSpot(entry, bot, map, now)) {
            entry.grindDoctrineStyle = null; // nothing claimable -> roam this map
            return;
        }
        BotGrindSpots.Claims.renew(map.getId(), entry.grindSpotClaimKey, bot.getId(), now);
        if (entry.grindSpotDrySinceMs > 0
                && now - entry.grindSpotDrySinceMs >= waitPatienceMs(entry)
                && now - entry.grindSpotProgressAtMs >= unproductiveMs(entry)) {
            relocate(entry, bot, map, now);
        }
    }

    /** A landed attack = the spot is producing. Called from the grind attack path. */
    static void markProgress(BotEntry entry, long now) {
        if (entry != null) {
            entry.grindSpotProgressAtMs = now;
        }
    }

    /**
     * Restrict target candidates to the claimed spot (CAMP/PATROL: anchor ledge band; STACK: the
     * column's tether box). An empty result starts the dry timer — the bot waits at its spot for
     * respawns (that oscillation IS the natural camping look) until the relocation timers fire.
     * Doctrine inactive -> candidates returned unchanged.
     */
    static List<Monster> filterCandidates(BotEntry entry, List<Monster> candidates, long now) {
        if (!active(entry) || candidates.isEmpty()) {
            return candidates;
        }
        List<Monster> inSpot = new ArrayList<>(candidates.size());
        for (Monster m : candidates) {
            Point mp = m.getPosition();
            if (mp != null && inSpotBounds(entry, mp)) {
                inSpot.add(m);
            }
        }
        if (inSpot.isEmpty()) {
            if (entry.grindSpotDrySinceMs == 0) {
                entry.grindSpotDrySinceMs = now;
            }
        } else {
            entry.grindSpotDrySinceMs = 0;
        }
        return inSpot;
    }

    static boolean inSpotBounds(BotEntry entry, Point p) {
        if (entry.grindDoctrineStyle == Style.STACK && entry.grindStackBox != null) {
            return entry.grindStackBox.contains(p);
        }
        Point anchor = entry.grindSpotAnchor;
        return Math.abs(p.x - anchor.x) <= entry.grindSpotRadius + ACQUIRE_MARGIN_PX
                && Math.abs(p.y - anchor.y) <= SPOT_TARGET_Y_BAND;
    }

    /**
     * No-target wander position while camped: hold near the anchor (small in-spot drift) instead of
     * region-random wandering, so the bot visibly WORKS a spot. Null when doctrine is inactive.
     */
    static Point idleAnchorTarget(BotEntry entry, Point botPos) {
        if (!active(entry) || botPos == null) {
            return null;
        }
        Point anchor = entry.grindSpotAnchor;
        int drift = Math.min(entry.grindSpotRadius, 200);
        if (Math.abs(botPos.x - anchor.x) > entry.grindSpotRadius
                || Math.abs(botPos.y - anchor.y) > SPOT_TARGET_Y_BAND + STACK_TETHER_Y_PAD) {
            return anchor; // out of the spot -> walk home first
        }
        Point wander = entry.patrolWanderTarget;
        if (wander == null || Math.abs(botPos.x - wander.x) <= BotMovementManager.cfg.STOP_DIST) {
            wander = new Point(anchor.x + ThreadLocalRandom.current().nextInt(-drift, drift + 1), anchor.y);
            entry.patrolWanderTarget = wander;
        }
        return wander;
    }

    // ------------------------------------------------------------------ style + spot selection

    /** Map shape x class capability -> style. STACK only when the bot can actually traverse the
     *  column (hop-sized gaps, or teleport); walkers CAMP the best single ledge instead. */
    static Style chooseStyle(Character bot, BotGrindSpots.Profile profile) {
        if (profile.spots().isEmpty()) {
            return null;
        }
        if (bestTraversableStack(bot, profile) != null) {
            return Style.STACK;
        }
        if (profile.roam()) {
            return null;
        }
        if (profile.regime() == BotGrindSpots.Regime.SPREAD && profile.spots().size() >= PATROL_MIN_SPOTS) {
            return Style.PATROL;
        }
        return Style.CAMP;
    }

    static BotGrindSpots.SpotStack bestTraversableStack(Character bot, BotGrindSpots.Profile profile) {
        int bestSameLedge = 0;
        for (BotGrindSpots.Spot s : profile.spots()) {
            bestSameLedge = Math.max(bestSameLedge, s.sameLedgeSpawnCount());
        }
        boolean canBlink = bot != null
                && (BotNavigationManager.botSkillMask(bot) & BotNavigationGraph.SKILL_TELEPORT) != 0;
        BotGrindSpots.SpotStack best = null;
        for (BotGrindSpots.SpotStack st : profile.stacks()) {
            if (!st.hopTraversable() && !canBlink) {
                continue;
            }
            if (st.totalFeed() < STACK_MIN_FEED || st.totalFeed() < bestSameLedge * STACK_DOMINANCE) {
                continue;
            }
            if (best == null || st.totalFeed() > best.totalFeed()) {
                best = st;
            }
        }
        return best;
    }

    private static boolean selectSpot(BotEntry entry, Character bot, MapleMap map, long now) {
        BotGrindSpots.Profile profile = entry.grindDoctrineProfile;
        if (entry.grindDoctrineStyle == Style.STACK) {
            BotGrindSpots.SpotStack stack = bestTraversableStack(bot, profile);
            if (stack == null) {
                return false;
            }
            BotGrindSpots.Spot keySpot = null;
            for (int idx : stack.spotIndices()) {
                BotGrindSpots.Spot s = profile.spots().get(idx);
                if (keySpot == null || s.sameLedgeSpawnCount() > keySpot.sameLedgeSpawnCount()) {
                    keySpot = s;
                }
            }
            commitSpot(entry, keySpot, now);
            entry.grindStackBox = new Rectangle(
                    stack.x0() - STACK_TETHER_X_PAD,
                    stack.topY() - STACK_TETHER_Y_PAD,
                    stack.x1() - stack.x0() + 2 * STACK_TETHER_X_PAD,
                    stack.bottomY() - stack.topY() + 2 * STACK_TETHER_Y_PAD);
            return true;
        }
        if (entry.grindDoctrineStyle == Style.PATROL) {
            if (entry.grindPatrolRing == null) {
                entry.grindPatrolRing = buildPatrolRing(profile);
            }
            List<BotGrindSpots.Spot> ring = entry.grindPatrolRing;
            if (ring.size() < 2) {
                entry.grindDoctrineStyle = Style.CAMP; // ring collapsed -> camp
                return selectSpot(entry, bot, map, now);
            }
            // Rotate to the next ring member with a free claim slot; full ring -> least crowded.
            BotGrindSpots.Spot pick = null;
            int leastHolders = Integer.MAX_VALUE;
            BotGrindSpots.Spot leastCrowded = null;
            for (int i = 0; i < ring.size(); i++) {
                BotGrindSpots.Spot s = ring.get((entry.grindPatrolRingPos + i) % ring.size());
                int holders = BotGrindSpots.Claims.holders(map.getId(), s.claimKey(), bot.getId(), now);
                if (holders < s.shareCap() && pick == null && !isExcluded(entry, s, now)) {
                    pick = s;
                }
                if (holders < leastHolders) {
                    leastHolders = holders;
                    leastCrowded = s;
                }
            }
            commitSpot(entry, pick != null ? pick : leastCrowded, now);
            return entry.grindSpotAnchor != null;
        }
        // CAMP
        BotGrindSpots.Spot best = pickBestSpot(entry, bot, map, profile.spots(), now);
        if (best == null) {
            return false;
        }
        commitSpot(entry, best, now);
        return true;
    }

    private static void commitSpot(BotEntry entry, BotGrindSpots.Spot spot, long now) {
        if (spot == null) {
            return;
        }
        entry.grindSpotAnchor = spot.anchor();
        entry.grindSpotRadius = spot.radius();
        entry.grindSpotClaimKey = spot.claimKey();
        entry.grindStackBox = null;
        entry.grindSpotDrySinceMs = 0;
        entry.grindSpotProgressAtMs = now;
        entry.patrolWanderTarget = null;
    }

    private static List<BotGrindSpots.Spot> buildPatrolRing(BotGrindSpots.Profile profile) {
        List<BotGrindSpots.Spot> byFeed = new ArrayList<>(profile.spots());
        byFeed.sort(Comparator.comparingInt(BotGrindSpots.Spot::sameLedgeSpawnCount).reversed());
        List<BotGrindSpots.Spot> ring = new ArrayList<>(byFeed.subList(0, Math.min(PATROL_RING_MAX, byFeed.size())));
        ring.sort(Comparator.comparingInt(s -> s.anchor().x)); // sweep order, not zig-zag
        return ring;
    }

    /** SoloMapling pickBest: feed + tightness + room + start-hot bias - travel - crowding, with an
     *  overflow-scaled cap penalty ("fewest holders first") and jitter to decorrelate cohorts. */
    private static BotGrindSpots.Spot pickBestSpot(BotEntry entry, Character bot, MapleMap map,
                                                   List<BotGrindSpots.Spot> spots, long now) {
        Point botPos = bot.getPosition();
        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, entry.movementProfile);
        int botRegion = graph != null
                ? BotNavigationManager.resolveCurrentRegionId(graph, entry, map, botPos) : -1;
        int skillMask = BotNavigationManager.botSkillMask(bot);
        List<Monster> live = map.getAllMonsters();
        BotGrindSpots.Spot best = null;
        double bestScore = -Double.MAX_VALUE;
        for (BotGrindSpots.Spot s : spots) {
            if (isExcluded(entry, s, now)) {
                continue;
            }
            if (graph != null && botRegion >= 0 && s.regionId() >= 0
                    && !graph.canReach(botRegion, s.regionId(), skillMask)) {
                continue;
            }
            int holders = BotGrindSpots.Claims.holders(map.getId(), s.claimKey(), bot.getId(), now);
            double score = BotGrindSpots.SPAWN_DENSITY_W * s.sameLedgeSpawnCount()
                    + BotGrindSpots.TIGHTNESS_W * (100.0 * s.sameLedgeSpawnCount() / Math.max(1, 2 * s.radius()))
                    + BotGrindSpots.LEDGE_EXTENT_W * (Math.min(s.ledgeSpanPx(), BotGrindSpots.LEDGE_EXTENT_CAP_PX) / 100.0)
                    + BotGrindSpots.LIVE_MOB_W * liveHostilesWithin(live, s.anchor(), s.radius())
                    - BotGrindSpots.DISTANCE_W * (botPos != null ? botPos.distance(s.anchor()) : 0)
                    - BotGrindSpots.CROWDING_W * holders
                    - BotGrindSpots.OVER_CAP_PENALTY * Math.max(0, holders - s.shareCap() + 1)
                    + ThreadLocalRandom.current().nextDouble() * BotGrindSpots.SELECT_JITTER;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private static boolean isExcluded(BotEntry entry, BotGrindSpots.Spot s, long now) {
        return entry.grindSpotExcludedKey == s.claimKey() && now < entry.grindSpotExcludeUntilMs;
    }

    private static int liveHostilesWithin(List<Monster> live, Point anchor, int radius) {
        int count = 0;
        long radiusSq = (long) radius * radius;
        for (Monster m : live) {
            if (m == null || !m.isAlive()) {
                continue;
            }
            Point p = m.getPosition();
            if (p != null && p.distanceSq(anchor) <= radiusSq) {
                count++;
            }
        }
        return count;
    }

    private static void relocate(BotEntry entry, Character bot, MapleMap map, long now) {
        BotGrindSpots.Claims.release(map.getId(), entry.grindSpotClaimKey, bot.getId());
        entry.grindSpotExcludedKey = entry.grindSpotClaimKey;
        entry.grindSpotExcludeUntilMs = now + RELOCATE_EXCLUDE_MS;
        entry.grindSpotAnchor = null;
        entry.grindStackBox = null;
        entry.grindSpotDrySinceMs = 0;
        if (entry.grindDoctrineStyle == Style.PATROL) {
            entry.grindPatrolRingPos++;
        }
    }

    // Regime-tuned patience: how long an empty spot is tolerated (respawn waiting is the point on
    // dense maps), and how long since the last landed hit before the spot counts as unproductive.
    private static long waitPatienceMs(BotEntry entry) {
        if (entry.grindDoctrineStyle == Style.STACK) {
            return 5_000L;
        }
        BotGrindSpots.Regime regime = entry.grindDoctrineProfile.regime();
        return switch (regime) {
            case COMPACT -> 8_000L;
            case SPREAD -> 4_000L;
            case SPARSE -> 2_500L;
        };
    }

    private static long unproductiveMs(BotEntry entry) {
        if (entry.grindDoctrineStyle == Style.STACK) {
            return 20_000L;
        }
        BotGrindSpots.Regime regime = entry.grindDoctrineProfile.regime();
        return switch (regime) {
            case COMPACT -> 35_000L;
            case SPREAD -> 18_000L;
            case SPARSE -> 10_000L;
        };
    }

    static void clear(BotEntry entry, Character bot) {
        if (entry == null) {
            return;
        }
        if (entry.grindSpotAnchor != null && entry.grindDoctrineMapId >= 0 && bot != null) {
            BotGrindSpots.Claims.release(entry.grindDoctrineMapId, entry.grindSpotClaimKey, bot.getId());
        }
        entry.grindDoctrineMapId = -1;
        entry.grindDoctrineProfile = null;
        entry.grindDoctrineStyle = null;
        entry.grindSpotAnchor = null;
        entry.grindSpotRadius = 0;
        entry.grindSpotClaimKey = 0;
        entry.grindStackBox = null;
        entry.grindSpotDrySinceMs = 0;
        entry.grindSpotProgressAtMs = 0;
        entry.grindSpotExcludedKey = 0;
        entry.grindSpotExcludeUntilMs = 0;
        entry.grindPatrolRing = null;
        entry.grindPatrolRingPos = 0;
    }
}
