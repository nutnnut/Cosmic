package server.bots;

import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.game.CharacterStance;
import constants.skills.BlazeWizard;
import constants.skills.Cleric;
import constants.skills.Evan;
import constants.skills.FPWizard;
import constants.skills.Hermit;
import constants.skills.ILWizard;
import constants.skills.NightWalker;
import server.StatEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import server.maps.Foothold;
import server.maps.Portal;
import server.maps.Rope;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class BotNavigationManager {
    private static final Logger log = LoggerFactory.getLogger(BotNavigationManager.class);
    private static final int JUMP_READY_X_TOLERANCE = 10;
    private static final int EDGE_READY_X_TOLERANCE = 14;
    private static final int FLASH_JUMP_LAUNCH_TOL = 12; // FJ window is a single point; let the bot fire from near it
    private static final int NO_MOVEMENT_WALK_TOLERANCE = 4;
    // Stale-edge give-up: after this many consecutive no-movement ticks blocked on a
    // committed edge's position gate ("*-pos"), drop the edge and replan from the live
    // position (6-10 ticks = 300-500ms, jittered per park spot).
    private static final int BLOCKED_POS_GIVE_UP_MIN_TICKS = 6;
    private static final int BLOCKED_POS_GIVE_UP_JITTER_TICKS = 4;
    // Steering anchor inside a launch window: aim a few px inside the nearest window edge
    // (window center when narrower than two insets) instead of the exact boundary pixel.
    // The executable region is the WHOLE window; steering at the boundary pixel parks bots
    // 1-2px outside it whenever arrival tolerances round against them.
    private static final int LAUNCH_WINDOW_STEER_INSET_PX = 4;
    // After a bot takes a portal, suppress further portal usage for this long. Prevents a bot from
    // immediately re-entering a portal (e.g. bouncing back through the return portal). Gates ONLY
    // portal execution — movement, attacks and every other action continue unaffected.
    private static final long PORTAL_USE_COOLDOWN_MS = 250L;
    // Intra-map portal shortcuts only fire once the bot is LANDED (never midair — e.g. down-jumping
    // from a platform above and clipping the portal below as soon as it's permitted), and then it
    // walks a few extra ticks deeper onto the portal before activating instead of firing the instant
    // it's in range. Positional jitter like a launch window — extra walk ticks, NOT a standing wait.
    private static final int PORTAL_ENTER_EXTRA_TICKS_MAX = 3;
    // Terminal warns are for the absolute worst searches only — the perf monitor already
    // aggregates everything else. Rate-limited so one degenerate map can't flood the console.
    private static final long SLOW_PATHFIND_WARN_NS = 250_000_000L;
    private static final long SLOW_PATHFIND_WARN_COOLDOWN_MS = 10_000L;
    // Hard bound on a single A* so one search can't freeze a bot-tick worker. On exceed the search
    // breaks and returns best-effort (cheapest goal reached so far, or empty -> caller retries / picks
    // a nearer target). ~160k edge checks ~= 100ms at the observed ~1.6M checks/s. Unreachable targets
    // on dense maps used to exhaust the whole graph here: live single searches hit 4-7s, resultEdges=0.
    // Tunable at runtime (non-final) like the route-diversity knobs.
    static int MAX_EDGE_CHECKS = 160_000;
    private static final java.util.concurrent.atomic.AtomicLong slowPathfindNextWarnAtMs =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger slowPathfindSuppressed =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Throttle warmup notifications per (ownerId -> mapId -> lastNotifyMs). */
    private static final Map<Integer, Map<Integer, Long>> WARMUP_NOTIFIED = new ConcurrentHashMap<>();

    static final class NavigationDirective {
        final Point targetPos;
        final boolean consumedTick;

        NavigationDirective(Point targetPos, boolean consumedTick) {
            this.targetPos = targetPos;
            this.consumedTick = consumedTick;
        }
    }

    private static final class SearchNode {
        final SearchState state;
        final int cost;
        final int score;

        SearchNode(SearchState state, int cost, int score) {
            this.state = state;
            this.cost = cost;
            this.score = score;
        }
    }

    // viaPortal: true when this state was reached by a PORTAL edge. It distinguishes "arrived here
    // by teleport" from "arrived by walk/jump/etc." so the search can charge the portal cooldown to
    // a portal that chains straight off another portal (see runSearch). The flag is part of the
    // dedup key, so a region reachable both ways is explored under both costs.
    private record SearchState(int regionId, Point point, boolean viaPortal) {
    }

    private record PathfindProfile(long elapsedNs,
                                   int expandedNodes,
                                   int staleNodes,
                                   int edgeChecks,
                                   int usableEdges,
                                   int relaxations,
                                   int openPeak,
                                   int bestGoalCost,
                                   int resultEdges,
                                   boolean capped) {
    }

    static NavigationDirective resolveTarget(BotEntry entry, Point rawTargetPos, boolean runAiTick) {
        long startedAt = System.nanoTime();
        try {
            Character bot = entry.bot;
            if (bot.getMap().getFootholds() == null) {
                entry.graphWarmupFallback = false;
                clearNavigation(entry);
                return new NavigationDirective(rawTargetPos, false);
            }
            if (bot.getMap().isSwim()) {
                // Swim maps don't use a swim-aware nav graph. Airborne motion is handled
                // by the swim integrator (tickSwimming); on platforms we still need
                // ledge-drops, ropes, and ground jumps. Engage the heuristic fallback —
                // it walks off ledges into water, picks up nearby ropes, and jumps onto
                // higher platforms when useful. tickSwimming consults targetPos directly,
                // so the same rawTargetPos works for both grounded and airborne paths.
                entry.graphWarmupFallback = true;
                clearNavigation(entry);
                return new NavigationDirective(rawTargetPos, false);
            }

            BotNavigationGraph graph = resolveActiveGraph(bot.getMap(), entry.movementProfile);
            if (graph == null) {
                BotNavigationGraphProvider.warmGraphAsync(bot.getMap(), entry.movementProfile);
                entry.graphWarmupFallback = true;
                notifyWarmup(entry, bot);
                entry.lastNavDecision = "graph-warmup";
                clearNavigation(entry);
                Point fallbackTarget = rawTargetPos != null ? new Point(rawTargetPos) : bot.getPosition();
                if (entry.pathLogger != null) {
                    entry.pathLogger.record(entry, captureTargetSnapshot(entry, rawTargetPos), -1, false, runAiTick);
                }
                return new NavigationDirective(fallbackTarget, false);
            }
            if (BotNavigationGraphProvider.peekGraph(bot.getMap(), entry.movementProfile) == null) {
                BotNavigationGraphProvider.warmGraphAsync(bot.getMap(), entry.movementProfile);
                entry.lastNavDecision = "graph-fallback-profile";
            }
            entry.graphWarmupFallback = false;
            if (entry.navGraph != graph) {
                // Served graph swapped (exact-profile build finished, or a different closest
                // fallback won): committed edges were calibrated against the old instance —
                // their windows and launch steps don't transfer. Drop and replan right now.
                if (entry.navGraph != null) {
                    BotMovementManager.clearNavigationState(entry);
                }
                clearCommittedRoute(entry); // edges in the route belong to the old graph instance — stale
                entry.navGraph = graph;
            }
            Point botPos = bot.getPosition();
            int startRegionId = resolveCurrentRegionId(graph, entry, bot.getMap(), botPos);
            int targetRegionId = resolveTargetRegionId(graph, entry, bot.getMap(), rawTargetPos);
            Point pathTargetPos = adjustPathTarget(entry, graph, targetRegionId, rawTargetPos);

            // Stale-edge give-up: a committed edge whose position gate (jump-pos/drop-pos/
            // climb-pos) has rejected the bot for several consecutive ticks WITHOUT the bot
            // moving is parked, not approaching — e.g. a window recorded a few px away from
            // where the bot actually stands (pathlog-Leroy-2026-06-12T141517: stale DROP
            // window [1245,1285], bot at 1287, while a fresh plan's window contained the
            // bot the whole time). Drop the edge and replan from the live position.
            if (runAiTick && entry.navEdge != null
                    && entry.navBlockedPosTicks > 0
                    && entry.navBlockedPosTicks >= entry.navBlockedPosGiveUpTicks) {
                clearNavigation(entry);
                clearCommittedRoute(entry); // parked against a gate — force a genuinely fresh route, not the same hop
            }

            BotNavigationGraph.Edge edge = reuseCommittedEdge(graph, entry, startRegionId, targetRegionId);
            boolean edgeReused = (edge != null);
            boolean committedRouteFollow = false;  // took the next hop off an existing committed route
            boolean committedRouteReplan = false;  // had to (re)compute the committed route this tick
            if (edgeReused) {
                BotNavigationGraph.Edge refreshedEdge = refreshPendingClimbExitEdge(
                        graph, entry, bot, botPos, startRegionId, targetRegionId, edge, runAiTick);
                if (refreshedEdge != edge) {
                    edge = refreshedEdge;
                    edgeReused = edge != null;
                }
                if (edgeReused) {
                    BotNavigationGraph.Edge refreshedGroundEdge = refreshCommittedGroundEdge(
                            graph, entry, startRegionId, targetRegionId, edge, runAiTick);
                    if (refreshedGroundEdge != edge) {
                        edge = refreshedGroundEdge;
                        edgeReused = edge != null;
                    }
                }
            }
            if (edge == null && runAiTick && startRegionId >= 0 && targetRegionId >= 0) {
                // Stick to ONE committed route: take the next hop off the bot's already-planned route
                // instead of re-deciding it per region. The best first hop out of a region is
                // position-dependent, but the per-region next-hop cache (findNextEdge) is keyed by
                // (region,target,bucket) — position-blind — and never invalidated, so adjacent regions
                // can serve mutually-inconsistent cached hops (r45->r42 while r42->r45) and trap the bot
                // ping-ponging. One route planned from the bot's own position is acyclic. The route is
                // recomputed only when the goal region changes or the bot is knocked off it.
                // Same-region planning is intentionally allowed: intra-region portals appear as
                // self-loop edges (fromRegionId == toRegionId) and the search picks them when the
                // walk-to-entry + walk-from-exit cost beats the direct walk; an empty route falls
                // through to direct steering.
                edge = nextCommittedRouteEdge(graph, entry, startRegionId, targetRegionId);
                if (edge != null) {
                    committedRouteFollow = true; // following the already-committed route (the good case)
                } else {
                    List<BotNavigationGraph.Edge> route =
                            computeCommittedRoute(graph, bot, startRegionId, targetRegionId, pathTargetPos);
                    if (route != null) {
                        entry.committedRoute = route;
                        entry.committedRouteTargetRegionId = targetRegionId;
                        entry.committedRouteCursor = 0; // fresh route — follow it from the top
                        edge = nextCommittedRouteEdge(graph, entry, startRegionId, targetRegionId);
                        committedRouteReplan = true; // had to (re)plan — goal-region change or knocked off-route
                    } else {
                        // Uncommittable (intra-region portal detour): fall back to the per-hop planner.
                        clearCommittedRoute(entry);
                        edge = findNextEdge(graph, bot, startRegionId, targetRegionId, pathTargetPos);
                    }
                }
                if (edge != null) {
                    entry.navEdge = edge;
                    entry.navTargetRegionId = targetRegionId;
                }
            }

            // Intra-region express: skill bot, same-region far target, no edge needed — blink/dash along
            // the platform instead of walking it. Cross-region hops are graph edges; this is the
            // same-platform speedup. All safeguards (same-region landing, overshoot, MP, cadence) inside.
            if (edge == null && runAiTick && botCanUseMovementSkill(bot)
                    && startRegionId >= 0 && startRegionId == targetRegionId) {
                NavigationDirective hop = tryIntraRegionSkillHop(entry, bot, graph, botPos, rawTargetPos, startRegionId);
                if (hop != null) {
                    entry.lastNavDecision = "skill-hop";
                    if (entry.pathLogger != null) {
                        entry.pathLogger.record(entry, captureTargetSnapshot(entry, rawTargetPos), startRegionId, true, runAiTick);
                    }
                    return hop;
                }
            }

            if (edge == null) {
                entry.lastNavDecision = !runAiTick ? "no-ai"
                        : startRegionId < 0 || targetRegionId < 0 ? "no-region"
                        : startRegionId == targetRegionId ? "same-region" : "no-path";
                clearNavigation(entry);
                if (entry.pathLogger != null) {
                    entry.pathLogger.record(entry, captureTargetSnapshot(entry, rawTargetPos), startRegionId, false, runAiTick);
                }
                return new NavigationDirective(rawTargetPos, false);
            }

            NavigationDirective executionDirective = tryExecuteEdge(graph, entry, bot, botPos, rawTargetPos, edge, runAiTick);
            if (executionDirective != null) {
                entry.lastNavDecision = "exec";
                entry.navBlockedPosTicks = 0;
                if (entry.pathLogger != null) {
                    entry.pathLogger.record(entry, captureTargetSnapshot(entry, rawTargetPos), startRegionId, true, runAiTick);
                }
                return executionDirective;
            }

            // Long-stretch express: walking a long way to a committed cross-region edge's launch point —
            // blink/dash toward that launch X instead of trudging the whole platform. Keeps the committed
            // edge (tryIntraRegionSkillHop no longer clears nav) so the bot executes the hop once in range.
            if (runAiTick && botCanUseMovementSkill(bot) && startRegionId == edge.fromRegionId
                    && Math.abs(botPos.x - edge.startPoint.x) > INTRA_EXPRESS_MIN_PX) {
                NavigationDirective hop = tryIntraRegionSkillHop(entry, bot, graph, botPos, edge.startPoint, startRegionId);
                if (hop != null) {
                    entry.lastNavDecision = "skill-hop-stretch";
                    if (entry.pathLogger != null) {
                        entry.pathLogger.record(entry, captureTargetSnapshot(entry, rawTargetPos), startRegionId, true, runAiTick);
                    }
                    return hop;
                }
            }

            entry.lastNavDecision = edgeReused ? "reuse"
                    : committedRouteFollow ? "route"     // following the committed route — want lots of these
                    : committedRouteReplan ? "replan"    // route recomputed (goal moved / knocked off-route)
                    : "new";
            trackBlockedPositionGate(entry, botPos, edgeReused);
            entry.navPreciseTarget = shouldUsePreciseTarget(graph, entry, botPos, edge);
            entry.navTargetPos = selectWaypoint(entry, graph, botPos, edge);
            if (entry.pathLogger != null) {
                entry.pathLogger.record(entry, captureTargetSnapshot(entry, rawTargetPos), startRegionId, false, runAiTick);
            }
            return new NavigationDirective(new Point(entry.navTargetPos), false);
        } finally {
            BotPerformanceMonitor.record("nav-resolve", System.nanoTime() - startedAt);
        }
    }

    static boolean tryExecuteCommittedEdgeAfterGroundMovement(BotEntry entry, Point rawTargetPos) {
        if (entry == null || entry.bot == null || entry.navEdge == null || entry.inAir || entry.climbing) {
            return false;
        }

        // Validate the edge is still applicable before attempting execution.
        // tickAirborne may have landed the bot at the destination in this same tick; the navEdge
        // isn't cleared until the next resolveTarget call, so reuseCommittedEdge would correctly
        // discard a DROP/JUMP edge whose toRegionId matches the bot's current region. Without this
        // check, tryExecuteDrop re-fires from the landing platform where there's no lower foothold,
        // sending the bot out of the map.
        BotNavigationGraph graph = resolveActiveGraph(entry.bot.getMap(), entry.movementProfile);
        if (graph == null) {
            BotNavigationGraphProvider.warmGraphAsync(entry.bot.getMap(), entry.movementProfile);
            return false;
        }
        Point botPos = entry.bot.getPosition();
        int startRegionId = resolveCurrentRegionId(graph, entry, entry.bot.getMap(), botPos);
        BotNavigationGraph.Edge edge = reuseCommittedEdge(graph, entry, startRegionId, entry.navTargetRegionId);
        if (edge == null) {
            BotMovementManager.clearNavigationState(entry);
            return false;
        }

        NavigationDirective directive = tryExecuteEdge(graph, entry, entry.bot, botPos, rawTargetPos, edge, true);
        if (directive == null || !directive.consumedTick) {
            return false;
        }

        entry.lastNavDecision = "exec";
        return true;
    }

    private static void clearNavigation(BotEntry entry) {
        BotMovementManager.clearNavigationState(entry);
    }

    /** Drop the committed route so the next plan recomputes one from the live position. Only for real
     *  replans (graph swap / stale-edge give-up); routine clears must NOT touch it (see
     *  clearNavigationState), or the route stops surviving jumps and the ping-pong returns. */
    static void clearCommittedRoute(BotEntry entry) {
        entry.committedRoute = null;
        entry.committedRouteTargetRegionId = -1;
        entry.committedRouteCursor = 0;
    }

    /**
     * Counts consecutive ticks spent parked against a committed edge's position gate
     * (block reason "*-pos") without any actual movement. resolveTarget gives the edge up
     * and replans once the count passes a jittered threshold (~300-500ms). Any position
     * change restarts the count, so a slow legal approach (e.g. slippery-ground pulse
     * creep) is never interrupted while it is making progress.
     */
    private static void trackBlockedPositionGate(BotEntry entry, Point botPos, boolean edgeReused) {
        boolean blockedPos = edgeReused
                && entry.lastEdgeBlockReason != null
                && entry.lastEdgeBlockReason.endsWith("-pos");
        if (!blockedPos) {
            entry.navBlockedPosTicks = 0;
            return;
        }
        if (entry.navBlockedPosTicks == 0
                || botPos.x != entry.navBlockedPosX
                || botPos.y != entry.navBlockedPosY) {
            entry.navBlockedPosTicks = 0;
            entry.navBlockedPosGiveUpTicks = BLOCKED_POS_GIVE_UP_MIN_TICKS
                    + ThreadLocalRandom.current().nextInt(BLOCKED_POS_GIVE_UP_JITTER_TICKS + 1);
            entry.navBlockedPosX = botPos.x;
            entry.navBlockedPosY = botPos.y;
        }
        entry.navBlockedPosTicks++;
    }

    private static BotManager.TargetSnapshot captureTargetSnapshot(BotEntry entry, Point rawTargetPos) {
        BotManager.TargetSnapshot snapshot = BotManager.getInstance().captureTargetSnapshot(entry);
        if (rawTargetPos == null || rawTargetPos.equals(snapshot.primaryTargetPos())) {
            return snapshot;
        }
        return new BotManager.TargetSnapshot(
                snapshot.formation(),
                snapshot.rawOwnerPos(),
                snapshot.followAnchorPos(),
                snapshot.followAnchorName(),
                snapshot.followBasePos(),
                snapshot.followTargetPos(),
                snapshot.moveTargetPos(),
                snapshot.farmAnchorPos(),
                snapshot.grindTargetPos(),
                new Point(rawTargetPos),
                "nav-input");
    }

    private static void notifyWarmup(BotEntry entry, Character bot) {
        Character owner = entry.owner;
        if (owner == null) return;
        int ownerId = owner.getId();
        int mapId = bot.getMap().getId();
        long now = System.currentTimeMillis();
        Map<Integer, Long> byMap = WARMUP_NOTIFIED.get(ownerId);
        if (byMap != null) {
            Long last = byMap.get(mapId);
            if (last != null && (now - last) < 10_000L) return;
        }
        // Only count walkable footholds when we are about to send — lazy, inside throttle gate
        long walkable = bot.getMap().getFootholds().getAllFootholds().stream()
                .filter(fh -> !fh.isWall()).count();
        if (walkable < 100) return;
        WARMUP_NOTIFIED.computeIfAbsent(ownerId, k -> new ConcurrentHashMap<>()).put(mapId, now);
        owner.dropMessage(5, bot.getName() + " is warming map navigation cache, using fallback movement...");
    }

    private static BotNavigationGraph.Edge refreshPendingClimbExitEdge(BotNavigationGraph graph,
                                                                       BotEntry entry,
                                                                       Character bot,
                                                                       Point botPos,
                                                                       int startRegionId,
                                                                       int targetRegionId,
                                                                       BotNavigationGraph.Edge edge,
                                                                       boolean runAiTick) {
        if (!runAiTick
                || edge == null
                || !entry.climbing
                || edge.type != BotNavigationGraph.EdgeType.CLIMB
                || edge.launchStepX == 0
                || startRegionId < 0
                || targetRegionId < 0
                || startRegionId == targetRegionId) {
            return edge;
        }

        if (canExecuteClimbExitFromCurrentPosition(graph, bot.getMap(), botPos, edge)) {
            return edge;
        }

        // Committed-route SSOT: pull the next hop from the bot's planned route, never the position-blind
        // bucket cache (findNextEdge) — see refreshCommittedGroundEdge for why that re-injects ping-pong.
        BotNavigationGraph.Edge bestEdge = nextCommittedRouteEdge(graph, entry, startRegionId, targetRegionId);
        if (sameEdge(edge, bestEdge) || bestEdge == null) {
            return edge;
        }

        entry.navEdge = bestEdge;
        entry.navTargetRegionId = targetRegionId;
        entry.navTargetPos = null;
        entry.navPreciseTarget = false;
        return bestEdge;
    }

    private static BotNavigationGraph.Edge refreshCommittedGroundEdge(BotNavigationGraph graph,
                                                                      BotEntry entry,
                                                                      int startRegionId,
                                                                      int targetRegionId,
                                                                      BotNavigationGraph.Edge edge,
                                                                      boolean runAiTick) {
        if (!runAiTick
                || edge == null
                || entry.inAir
                || entry.climbing
                || startRegionId < 0
                || targetRegionId < 0
                || startRegionId == targetRegionId) {
            return edge;
        }

        // Committed-route SSOT: the next hop comes from the bot's planned route, not the position-blind
        // (region,target,bucket) bucket cache. That cache let adjacent regions serve mutually-inconsistent
        // hops (r45->r42 while r42->r45), and refreshing the committed edge against it every ground tick
        // re-injected the cross-region ping-pong the committed route was meant to stop.
        BotNavigationGraph.Edge bestEdge = nextCommittedRouteEdge(graph, entry, startRegionId, targetRegionId);
        if (bestEdge == null || sameEdge(edge, bestEdge)) {
            return edge;
        }
        if (shouldRetainCommittedGroundEdge(edge, bestEdge)) {
            return edge;
        }

        entry.navEdge = bestEdge;
        entry.navTargetRegionId = targetRegionId;
        entry.navTargetPos = null;
        entry.navPreciseTarget = false;
        return bestEdge;
    }

    static BotNavigationGraph.Edge reuseCommittedEdge(BotNavigationGraph graph,
                                                      BotEntry entry,
                                                      int startRegionId,
                                                      int targetRegionId) {
        BotNavigationGraph.Edge edge = entry.navEdge;
        if (edge == null) {
            return null;
        }
        if (targetRegionId < 0) {
            return null;
        }
        int previousTargetRegionId = entry.navTargetRegionId;
        // Update stored target in-place rather than discarding. The Y-snap offset causes
        // followBase.x to differ between AI and non-AI ticks, making targetRegionId fluctuate
        // even when the owner hasn't meaningfully moved. Relying on structural checks below
        // (start-region match, usability, arrival) is sufficient to detect actual invalidity.
        entry.navTargetRegionId = targetRegionId;
        if (!isEdgeUsable(graph, entry.bot, edge)) {
            return null;
        }
        if (entry.climbing && isRopeEntryEdge(graph, edge)) {
            return null;
        }
        if (startRegionId == edge.toRegionId && !entry.inAir && !entry.climbing
                && edge.fromRegionId != edge.toRegionId) {
            // Self-loop edges (intra-region portals) inherently start and end in the same
            // region. Completion is signalled by execution (tryExecutePortal teleporting the
            // bot), not by a region change — don't retire on region match.
            return null;
        }
        // Once the resolved target is back in the bot's current region, a committed edge that
        // would leave that region is *usually* stale — follow/formation loops where the bot keeps
        // running toward an old jump/drop/portal after the live follow target snapped back onto the
        // current platform. But "same region" does NOT imply "direct walk reaches it": a region can
        // be two platforms split by a gap (e.g. map 1020000 r11), where the only route to a target
        // on the far platform genuinely loops out through a portal and back. A* commits that
        // leave-region edge *for this same-region target* (previousTargetRegionId == targetRegionId);
        // retiring it every tick made non-AI ticks revert to the raw pin (opposite direction) and the
        // bot thrashed in place. Only treat it as stale when the target actually CHANGED region
        // (the snap-back case) — then the edge was planned for a different target and is truly stale.
        if (!entry.inAir && !entry.climbing
                && startRegionId >= 0 && startRegionId == targetRegionId
                && edge.toRegionId != startRegionId
                && previousTargetRegionId != targetRegionId) {
            return null;
        }
        if (startRegionId == edge.fromRegionId) {
            if (!entry.inAir && !entry.climbing
                    && previousTargetRegionId >= 0
                    && previousTargetRegionId != targetRegionId
                    && edge.toRegionId != targetRegionId) {
                return null;
            }
            return edge;
        }
        // While climbing, always keep the edge — findGroundFoothold gives false positives
        // (returns the platform below/behind the rope as the "current" region), which would
        // otherwise drop the exit edge the moment the bot enters the destination region's Y range.
        if (entry.climbing && (startRegionId < 0 || startRegionId != edge.toRegionId)) {
            return edge;
        }
        // DROP/JUMP arcs may enter the destination region before the bot touches down.
        // Keep the edge until landing. Only retain if the bot is in a region consistent with
        // this arc (destination or unmapped) — prevents looping in a wrong region mid-air.
        if (entry.inAir && (startRegionId < 0 || startRegionId == edge.toRegionId)
                && (edge.type == BotNavigationGraph.EdgeType.DROP
                    || edge.type == BotNavigationGraph.EdgeType.JUMP
                    || edge.type == BotNavigationGraph.EdgeType.FLASH_JUMP)) {
            return edge;
        }
        if (entry.inAir && edge.type == BotNavigationGraph.EdgeType.CLIMB && edge.launchStepX != 0) {
            // Rope-exit jump arcs use the same sampled ballistic model as JUMP/DROP edges.
            // Keep the committed edge until the bot actually lands or grabs a rope again;
            // otherwise mid-air replans can steer the bot off the authored landing path.
            return edge;
        }
        return null;
    }

    private static NavigationDirective tryExecuteEdge(BotNavigationGraph graph,
                                                      BotEntry entry,
                                                      Character bot,
                                                      Point botPos,
                                                      Point rawTargetPos,
                                                      BotNavigationGraph.Edge edge,
                                                      boolean runAiTick) {
        if (!runAiTick) {
            return null;
        }

        return switch (edge.type) {
            case JUMP -> tryExecuteJump(graph, entry, bot, rawTargetPos, edge);
            case DROP -> tryExecuteDrop(graph, entry, bot, botPos, rawTargetPos, edge);
            case CLIMB -> tryExecuteClimb(graph, entry, bot, botPos, rawTargetPos, edge);
            case PORTAL -> tryExecutePortalEdge(entry, bot, botPos, rawTargetPos, edge);
            case TELEPORT -> tryExecuteTeleport(entry, bot, botPos, rawTargetPos, edge);
            case FLASH_JUMP -> tryExecuteFlashJump(graph, entry, bot, rawTargetPos, edge);
            default -> null;
        };
    }

    private static NavigationDirective tryExecuteJump(BotNavigationGraph graph,
                                                      BotEntry entry,
                                                      Character bot,
                                                      Point rawTargetPos,
                                                      BotNavigationGraph.Edge edge) {
        if (entry.inAir || entry.climbing) {
            return null;
        }
        Point botPos = bot.getPosition();
        if (!canExecuteSelectedJumpFromCurrentPosition(graph, entry, bot.getMap(), botPos, edge)) {
            // Bot may be standing at the top of a rope region whose bottom is the jump entry.
            // Grab the rope and descend — tickClimbing will naturally drive toward edge.startPoint.
            if (edge.startPoint.y > botPos.y) {
                BotNavigationGraph.Region fromRegion = graph.getRegion(edge.fromRegionId);
                if (fromRegion != null && fromRegion.isRopeRegion) {
                    Rope rope = findRopeForRegion(bot.getMap(), fromRegion);
                    if (rope != null && canGrabRopeAtCurrentPosition(botPos, rope)) {
                        // Attach at bot's current Y — tickClimbing will drive it down to startPoint.
                        // Using edge.startPoint.y would teleport the bot rather than letting it climb.
                        startClimbing(entry, bot, rope, botPos.y);
                        return new NavigationDirective(rawTargetPos, true);
                    }
                }
            }
            entry.lastEdgeBlockReason = "jump-pos";
            return null;
        }

        if (deepenJumpLaunchOneStep(graph, entry, bot.getMap(), edge)) {
            entry.lastEdgeBlockReason = "jump-delay";
            return null; // steering follows the bumped launch X — walk a step deeper first
        }
        // Vertical jumps (launchStepX=0) on slippery ground carry the residual slide into the
        // air (packet-true no-input launch), which would drift the planned straight-up arc.
        // Wait for the stop policy (glide / counter-strafe brake) to shed the slide first.
        // Directional jumps never wait: the launch snaps to ±walkSpeed regardless of slide.
        if (edge.launchStepX == 0
                && BotPhysicsEngine.slipperyGround(bot.getMap())
                && BotPhysicsEngine.carriedAirVelX(bot.getMap(), entry) != 0) {
            entry.lastEdgeBlockReason = "jump-slide";
            return null;
        }
        entry.lastEdgeBlockReason = null;
        setEdgeExecutionTarget(entry, edge);
        BotMovementManager.initiateJump(entry, bot, edge.launchStepX);
        // One-shot launch point: a missed arc re-rolls a fresh spot (and a fresh deepen count)
        // on the next approach instead of repeating the identical failure forever.
        entry.navJumpLaunchEdge = null;
        entry.navJumpLaunchX = Integer.MIN_VALUE;
        entry.navJumpLaunchDelaySteps = Integer.MIN_VALUE;
        return new NavigationDirective(rawTargetPos, true);
    }

    /** Teleport edge: blink to the (grounded) destination instantly and broadcast a teleport so other
     *  clients render a blink, not a glide. MP is deducted; the &gt;40% MP / &gt;500k meso gate is enforced
     *  upstream at plan time, with a final affordability guard here. */
    private static NavigationDirective tryExecuteTeleport(BotEntry entry,
                                                          Character bot,
                                                          Point botPos,
                                                          Point rawTargetPos,
                                                          BotNavigationGraph.Edge edge) {
        if (entry.inAir || entry.climbing) {
            return null;
        }
        if (System.currentTimeMillis() < entry.skillHopReadyAtMs) {
            entry.lastEdgeBlockReason = "tele-cd";
            return null;
        }
        if (!isReadyForEdge(botPos, edge)) {
            entry.lastEdgeBlockReason = "tele-pos";
            return null;
        }
        int mpCon = botTeleportMpCon(bot);
        if (bot.getMp() < mpCon) {
            entry.lastEdgeBlockReason = "tele-mp";
            return null;
        }
        entry.lastEdgeBlockReason = null;
        Point origin = new Point(botPos);
        BotPhysicsEngine.teleportTo(entry, bot, edge.endPoint);
        boolean downward = edge.endPoint.y > edge.startPoint.y;
        if (downward) { entry.crouching = true; }   // prone for the down-teleport blink (capture: stance 0x0A both frags)
        if (mpCon > 0) {
            bot.addMP(-mpCon);
        }
        BotMovementManager.broadcastTeleport(entry, origin, edge.endPoint);
        if (downward) { entry.crouching = false; }  // clear: prone is the blink only; next tick stands/walks
        entry.skillHopReadyAtMs = System.currentTimeMillis() + SKILL_CAST_COOLDOWN_MS;
        clearNavigation(entry); // consumed: bot is now in the destination region — replan next tick
        return new NavigationDirective(rawTargetPos, true);
    }

    /** Flash-jump edge: a directional jump with the mid-air dash flagged for apex injection
     *  (BotPhysicsEngine consumes {@code pendingFlashJump} once at apex). Mirrors {@link #tryExecuteJump}. */
    private static NavigationDirective tryExecuteFlashJump(BotNavigationGraph graph,
                                                           BotEntry entry,
                                                           Character bot,
                                                           Point rawTargetPos,
                                                           BotNavigationGraph.Edge edge) {
        if (entry.inAir || entry.climbing) {
            return null;
        }
        if (System.currentTimeMillis() < entry.skillHopReadyAtMs) {
            entry.lastEdgeBlockReason = "fj-cd";
            return null;
        }
        Point botPos = bot.getPosition();
        if (!canExecuteSelectedJumpFromCurrentPosition(graph, entry, bot.getMap(), botPos, edge)) {
            entry.lastEdgeBlockReason = "fj-pos";
            return null;
        }
        int mpCon = botFlashJumpMpCon(bot);
        if (bot.getMp() < mpCon) {
            entry.lastEdgeBlockReason = "fj-mp";
            return null;
        }
        entry.lastEdgeBlockReason = null;
        setEdgeExecutionTarget(entry, edge);
        BotMovementManager.initiateJump(entry, bot, edge.launchStepX);
        entry.pendingFlashJump = true; // AFTER launch — launchAirborne clears it; consumed once at apex
        if (mpCon > 0) {
            bot.addMP(-mpCon);
        }
        entry.skillHopReadyAtMs = System.currentTimeMillis() + SKILL_CAST_COOLDOWN_MS;
        entry.navJumpLaunchEdge = null;
        entry.navJumpLaunchX = Integer.MIN_VALUE;
        entry.navJumpLaunchDelaySteps = Integer.MIN_VALUE;
        return new NavigationDirective(rawTargetPos, true);
    }

    /**
     * Launch variation: instead of always firing the instant the bot reaches its selected
     * launch X, sometimes carry 0-2 more walk steps into the window first (rolled once per
     * approach). A borderline arc — e.g. real speed sitting between graph buckets — that
     * misses from one spot is unlikely to also miss a step or two deeper, and the variation
     * reads more human than frame-perfect launches.
     */
    private static boolean deepenJumpLaunchOneStep(BotNavigationGraph graph,
                                                   BotEntry entry,
                                                   MapleMap map,
                                                   BotNavigationGraph.Edge edge) {
        if (entry.navJumpLaunchX == Integer.MIN_VALUE) {
            return false; // rope-anchored launches have no window to walk around in
        }
        int dir = Integer.signum(edge.launchStepX);
        if (dir == 0) {
            return false; // vertical jump: no launch direction to deepen along
        }
        if (entry.navJumpLaunchDelaySteps == Integer.MIN_VALUE) {
            entry.navJumpLaunchDelaySteps = ThreadLocalRandom.current().nextInt(3);
        }
        if (entry.navJumpLaunchDelaySteps <= 0) {
            return false;
        }
        int deeperX = entry.navJumpLaunchX + dir * BotPhysicsEngine.walkStep(map, entry.movementProfile);
        BotNavigationGraph.Region fromRegion = graph.getRegion(edge.fromRegionId);
        if (!edge.containsLaunchX(deeperX)
                || fromRegion == null || fromRegion.isRopeRegion
                || deeperX < fromRegion.minX || deeperX > fromRegion.maxX) {
            entry.navJumpLaunchDelaySteps = 0; // no legal room deeper — fire from here
            return false;
        }
        entry.navJumpLaunchDelaySteps--;
        entry.navJumpLaunchX = deeperX;
        return true;
    }

    private static NavigationDirective tryExecuteDrop(BotNavigationGraph graph,
                                                      BotEntry entry,
                                                      Character bot,
                                                      Point botPos,
                                                      Point rawTargetPos,
                                                      BotNavigationGraph.Edge edge) {
        if (entry.inAir || entry.climbing || entry.downJumpPending) {
            return null;
        }

        if (edge.launchStepX != 0) {
            // Walk-off drops are not an explicit action. Keep steering in the authored direction
            // and let ground physics carry the bot into a fall with preserved momentum.
            return null;
        }

        if (!canExecuteDropFromCurrentPosition(graph, bot.getMap(), botPos, edge)) {
            entry.lastEdgeBlockReason = "drop-pos";
            return null;
        }

        entry.lastEdgeBlockReason = null;
        setEdgeExecutionTarget(entry, edge);
        BotPhysicsEngine.queueDownJump(entry, bot);
        BotMovementManager.broadcastMovement(entry);
        return new NavigationDirective(rawTargetPos, true);
    }

    private static NavigationDirective tryExecuteClimb(BotNavigationGraph graph,
                                                       BotEntry entry,
                                                       Character bot,
                                                       Point botPos,
                                                       Point rawTargetPos,
                                                       BotNavigationGraph.Edge edge) {
        if (entry.inAir || entry.downJumpPending) {
            return null;
        }

        if (entry.climbing) {
            return tryExecuteClimbExit(graph, entry, bot, botPos, rawTargetPos, edge);
        } else {
            return tryExecuteClimbEntry(graph, entry, bot, botPos, rawTargetPos, edge);
        }
    }

    private static NavigationDirective tryExecuteClimbEntry(BotNavigationGraph graph,
                                                             BotEntry entry,
                                                             Character bot,
                                                             Point botPos,
                                                             Point rawTargetPos,
                                                             BotNavigationGraph.Edge edge) {
        BotNavigationGraph.Region toRegion = graph.getRegion(edge.toRegionId);
        Rope rope = findRopeForRegion(bot.getMap(), toRegion);
        if (rope == null) {
            return null;
        }
        if (!canExecuteClimbEntryFromCurrentPosition(bot.getMap(), botPos, edge, rope)) {
            entry.lastEdgeBlockReason = "climb-pos";
            return null;
        }

        if (canGrabRopeAtCurrentPosition(botPos, rope)) {
            // Bot is already within the rope's Y range — attach at its current Y, not the edge
            // endPoint. Using endPoint.y (rope top) would teleport a bot at the bottom of the
            // rope all the way to the top instantly.
            entry.lastEdgeBlockReason = null;
            startClimbing(entry, bot, rope, botPos.y);
            return new NavigationDirective(rawTargetPos, true);
        }
        if (canAttachToRopeFromTopPlatform(edge, botPos, rope)) {
            entry.lastEdgeBlockReason = null;
            startClimbing(entry, bot, rope, edge.endPoint.y);
            return new NavigationDirective(rawTargetPos, true);
        }
        if (canGrabRopeFromTopPlatform(edge, botPos, rope)) {
            // Top-of-rope entry is a separate intent from down-jump. Queue it and let grounded
            // physics consume the request on the next tick, just like other input-driven actions.
            entry.lastEdgeBlockReason = null;
            BotPhysicsEngine.queueTopRopeEntry(entry, bot, rope, edge.endPoint.y);
            BotMovementManager.broadcastMovement(entry);
            return new NavigationDirective(rawTargetPos, true);
        }

        if (canExecuteGroundRopeJumpEntryFromCurrentPosition(botPos, edge)) {
            entry.lastEdgeBlockReason = null;
            BotMovementManager.initiateRopeJump(entry, bot, edge.launchStepX, rope);
            return new NavigationDirective(rawTargetPos, true);
        }

        entry.lastEdgeBlockReason = "climb-reach";
        return null;
    }

    private static NavigationDirective tryExecuteClimbExit(BotNavigationGraph graph,
                                                            BotEntry entry,
                                                            Character bot,
                                                            Point botPos,
                                                            Point rawTargetPos,
                                                            BotNavigationGraph.Edge edge) {
        if (!canExecuteClimbExitFromCurrentPosition(graph, bot.getMap(), botPos, edge)) {
            return null;
        }
        BotNavigationGraph.Region toRegion = graph.getRegion(edge.toRegionId);

        if (toRegion != null && toRegion.isRopeRegion) {
            // Rope-to-rope: jump to the other rope
            Rope targetRope = findRopeForRegion(bot.getMap(), toRegion);
            if (targetRope == null || BotMovementManager.sameRope(entry.climbRope, targetRope)) {
                return null;
            }
            BotMovementManager.jumpToRope(entry, bot, edge.launchStepX);
            return new NavigationDirective(rawTargetPos, true);
        }

        if (edge.launchStepX == 0) {
            // launchStepX==0 means step off the top of the rope onto the foothold above.
            // Physics already handles this: resolveClimbBoundary lands the bot when it reaches
            // topY. Nav just lets the bot climb — the edge completes when the bot transitions
            // to the destination region after physics lands it.
            return null;
        }

        // Jump off rope
        Rope sourceRope = findRopeForRegion(bot.getMap(), graph.getRegion(edge.fromRegionId));
        if (isTopRopeJumpExitReady(sourceRope, botPos, edge) && botPos.y != edge.startPoint.y) {
            startClimbing(entry, bot, sourceRope, edge.startPoint.y);
        }
        BotMovementManager.jumpOffRope(entry, bot, edge.launchStepX);
        return new NavigationDirective(rawTargetPos, true);
    }

    static boolean canExecuteDropFromCurrentPosition(BotNavigationGraph graph,
                                                     MapleMap map,
                                                     Point botPos,
                                                     BotNavigationGraph.Edge edge) {
        if (edge.type != BotNavigationGraph.EdgeType.DROP) {
            return false;
        }
        if (edge.launchStepX != 0) {
            return false;
        }
        if (!isWithinDropLaunchWindow(graph, botPos, edge)) {
            return false;
        }
        return true;
    }

    private static NavigationDirective tryExecutePortalEdge(BotEntry entry,
                                                            Character bot,
                                                            Point botPos,
                                                            Point rawTargetPos,
                                                            BotNavigationGraph.Edge edge) {
        // Landed gate: never activate a portal while airborne. A down-jump or knockback that clips
        // the portal's trigger box mid-fall must NOT warp — the bot lands and walks in first.
        if (entry.inAir || !isReadyForEdge(botPos, edge)) {
            entry.portalEnterReadyTicks = -1;
            return null;
        }
        // Positional jitter: once eligible, keep walking deeper onto the portal for a few extra ticks
        // (selectWaypoint/precise targeting keep steering to startPoint) instead of firing the instant
        // it's permitted. Tick-counted inward movement, not a standing wait.
        if (entry.portalEnterReadyTicks < 0) {
            entry.portalEnterReadyTicks = ThreadLocalRandom.current().nextInt(PORTAL_ENTER_EXTRA_TICKS_MAX + 1);
        }
        if (entry.portalEnterReadyTicks > 0) {
            entry.portalEnterReadyTicks--;
            return null;
        }
        entry.portalEnterReadyTicks = -1;
        return tryExecutePortal(entry, bot, rawTargetPos, edge);
    }

    private static NavigationDirective tryExecutePortal(BotEntry entry,
                                                        Character bot,
                                                        Point rawTargetPos,
                                                        BotNavigationGraph.Edge edge) {
        if (System.currentTimeMillis() < entry.portalUseCooldownUntilMs) {
            return null;
        }
        if (!usePortal(bot, edge.portalId)) {
            return null;
        }

        entry.portalUseCooldownUntilMs = System.currentTimeMillis() + PORTAL_USE_COOLDOWN_MS;
        clearNavigation(entry);
        BotMovementManager.resetEntryState(entry);
        return new NavigationDirective(rawTargetPos, true);
    }

    private static boolean shouldUsePreciseTarget(BotNavigationGraph graph,
                                                  BotEntry entry,
                                                  Point botPos,
                                                  BotNavigationGraph.Edge edge) {
        if (entry.inAir) {
            return false;
        }
        return switch (edge.type) {
            case WALK -> shouldUsePreciseWalkTarget(edge);
            case JUMP -> !canExecuteSelectedJumpFromCurrentPosition(graph, entry, entry.bot.getMap(), botPos, edge);
            case DROP -> edge.launchStepX == 0
                    && !canExecuteDropFromCurrentPosition(graph, entry.bot.getMap(), botPos, edge);
            case CLIMB -> entry.climbing
                    ? edge.launchStepX != 0
                    && !canExecuteClimbExitFromCurrentPosition(graph, entry.bot.getMap(), botPos, edge)
                    : !canExecuteClimbEntryFromCurrentPosition(entry.bot.getMap(), botPos, edge,
                    findRopeForRegion(entry.bot.getMap(), graph.getRegion(edge.toRegionId)));
            case PORTAL -> !isReadyForEdge(botPos, edge) || entry.portalEnterReadyTicks > 0; // precise while walking the extra jitter ticks in
            case TELEPORT -> !isReadyForEdge(botPos, edge); // walk precisely onto the launch point, then blink
            case FLASH_JUMP -> !canExecuteSelectedJumpFromCurrentPosition(graph, entry, entry.bot.getMap(), botPos, edge);
        };
    }

    private static Point selectWaypoint(BotEntry entry, BotNavigationGraph graph, Point botPos, BotNavigationGraph.Edge edge) {
        // '-<' branch detour: the launch foothold may be reachable only by first walking AWAY from
        // the launch x (cross the shared vertex onto the other arm). Normal steering is monotone
        // toward the launch x and can never take that detour. This override fires ONLY for that case
        // (grounded JUMP/CLIMB/DROP whose foothold-chain to the launch starts in the away direction).
        if (entry != null && !entry.inAir && !entry.climbing) {
            switch (edge.type) {
                case JUMP, CLIMB, DROP -> {
                    Point detour = footholdDetourWaypoint(entry, graph, botPos, edge);
                    if (detour != null) {
                        return detour;
                    }
                }
                default -> { }
            }
        }
        return switch (edge.type) {
            case WALK -> new Point(edge.endPoint);
            case CLIMB -> selectClimbWaypoint(graph, entry, botPos, edge);
            case JUMP -> entry.inAir ? new Point(edge.endPoint) : selectJumpWaypoint(graph, entry, botPos, edge);
            case DROP -> selectDropWaypoint(entry, graph, botPos, edge);
            case PORTAL -> new Point(edge.startPoint); // always head to the portal entrance; it only fires once landed there
            case TELEPORT -> new Point(edge.startPoint); // walk to the launch point, then blink
            case FLASH_JUMP -> entry.inAir ? new Point(edge.endPoint) : selectJumpWaypoint(graph, entry, botPos, edge);
        };
    }

    /**
     * Within-region foothold-chain routing for a launch approach. A merged region can contain a
     * '-<' branch (a vertex where the upper arm, lower arm and stem meet): the bot may stand on one
     * arm while the edge's launch point sits on another. You can only get there by walking to the
     * shared vertex and continuing onto the other arm — which means first moving AWAY from the launch
     * x. The normal waypoint ({@code region.pointAt(launchX)}) steers monotonically toward the launch
     * x, so the bot never crosses the vertex and oscillates forever (the 101020000 magician shaft).
     *
     * <p>This returns a waypoint that sends the bot across the next foothold in the legal walk chain
     * (toward the shared vertex) ONLY when that first step is in the away-from-launch direction.
     * In every other case (same foothold, or the chain already heads toward the launch x) it returns
     * null and the caller's normal monotone steering is used unchanged — so this is inert for all
     * straight-line approaches and only engages on a genuine branch detour. Re-evaluated each tick:
     * once the bot reaches the arm whose chain heads toward the launch, this disengages.
     */
    static Point footholdDetourWaypoint(BotEntry entry, BotNavigationGraph graph, Point botPos,
                                        BotNavigationGraph.Edge edge) {
        MapleMap map = entry.bot.getMap();
        BotNavigationGraph.Region region = graph.getRegion(edge.fromRegionId);
        if (map == null || region == null || region.isRopeRegion) {
            return null;
        }
        Point launchPt = edge.startPoint;
        Foothold curFh = BotPhysicsEngine.findGroundFoothold(map, botPos);
        Foothold launchFh = BotPhysicsEngine.findGroundFoothold(map, launchPt);
        if (curFh == null || launchFh == null || curFh.getId() == launchFh.getId()) {
            return null;
        }
        List<Foothold> path = walkFootholdPath(map, region, curFh, launchFh);
        if (path == null || path.size() < 2) {
            return null;
        }
        Foothold next = path.get(1);
        Point cross = sharedEndpoint(curFh, next);
        if (cross == null) {
            return null;
        }
        int awayDir = Integer.signum(cross.x - botPos.x);
        int launchDir = Integer.signum(launchPt.x - botPos.x);
        if (awayDir == 0 || awayDir == launchDir) {
            return null; // chain already heads toward the launch x -> normal monotone steering reaches it
        }
        return farEndpoint(next, cross); // detour: walk across 'next' toward the shared vertex
    }

    /** BFS over a region's footholds via walkable prev/next links. Returns the foothold chain from
     *  {@code start} to {@code goal} (inclusive), or null if there is no in-region walk path. */
    private static List<Foothold> walkFootholdPath(MapleMap map, BotNavigationGraph.Region region,
                                                   Foothold start, Foothold goal) {
        Set<Integer> inRegion = new HashSet<>();
        for (BotNavigationGraph.Segment s : region.segments) {
            inRegion.add(s.footholdId);
        }
        if (!inRegion.contains(start.getId()) || !inRegion.contains(goal.getId())) {
            return null;
        }
        Map<Integer, Foothold> byId = BotPhysicsEngine.footholdsByIdFor(map);
        Map<Integer, Integer> prevOf = new HashMap<>();
        Deque<Integer> queue = new ArrayDeque<>();
        prevOf.put(start.getId(), start.getId());
        queue.add(start.getId());
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (cur == goal.getId()) {
                break;
            }
            Foothold f = byId.get(cur);
            if (f == null) {
                continue;
            }
            for (int nb : new int[]{f.getPrev(), f.getNext()}) {
                if (nb <= 0 || prevOf.containsKey(nb) || !inRegion.contains(nb)) {
                    continue;
                }
                Foothold nf = byId.get(nb);
                if (nf == null || !BotPhysicsEngine.canWalkAcrossFootholds(f, nf)) {
                    continue;
                }
                prevOf.put(nb, cur);
                queue.add(nb);
            }
        }
        if (!prevOf.containsKey(goal.getId())) {
            return null;
        }
        LinkedList<Foothold> path = new LinkedList<>();
        int cur = goal.getId();
        while (true) {
            path.addFirst(byId.get(cur));
            if (cur == start.getId()) {
                break;
            }
            cur = prevOf.get(cur);
        }
        return path;
    }

    /** The endpoint shared (within 3px) by two linked footholds, or null. */
    private static Point sharedEndpoint(Foothold a, Foothold b) {
        Point[] ae = {new Point(a.getX1(), a.getY1()), new Point(a.getX2(), a.getY2())};
        Point[] be = {new Point(b.getX1(), b.getY1()), new Point(b.getX2(), b.getY2())};
        for (Point p : ae) {
            for (Point q : be) {
                if (Math.abs(p.x - q.x) <= 3 && Math.abs(p.y - q.y) <= 3) {
                    return p;
                }
            }
        }
        return null;
    }

    /** The endpoint of {@code f} that is NOT the given near point. */
    private static Point farEndpoint(Foothold f, Point near) {
        Point e1 = new Point(f.getX1(), f.getY1());
        Point e2 = new Point(f.getX2(), f.getY2());
        return (Math.abs(e1.x - near.x) <= 3 && Math.abs(e1.y - near.y) <= 3) ? e2 : e1;
    }

    static Point selectJumpWaypoint(BotEntry entry, Point botPos, BotNavigationGraph.Edge edge) {
        BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(entry.bot.getMap(), entry.movementProfile);
        return selectJumpWaypoint(graph, entry, botPos, edge);
    }

    static Point selectJumpWaypoint(BotNavigationGraph graph, Point botPos, BotNavigationGraph.Edge edge) {
        return selectJumpWaypoint(graph, null, botPos, edge);
    }

    private static Point selectJumpWaypoint(BotNavigationGraph graph,
                                            BotEntry entry,
                                            Point botPos,
                                            BotNavigationGraph.Edge edge) {
        BotNavigationGraph.Region fromRegion = graph.getRegion(edge.fromRegionId);
        if (fromRegion == null || fromRegion.isRopeRegion) {
            return new Point(edge.startPoint);
        }
        int targetX = entry == null
                ? edge.containsLaunchX(botPos.x) ? botPos.x : botPos.x < edge.launchMinX ? edge.launchMinX : edge.launchMaxX
                : selectedJumpLaunchX(entry, graph, edge);
        return fromRegion.pointAt(targetX);
    }

    static Point selectClimbWaypoint(BotEntry entry, Point botPos, BotNavigationGraph.Edge edge) {
        BotNavigationGraph graph = resolveActiveGraph(entry.bot.getMap(), entry.movementProfile);
        return selectClimbWaypoint(graph, entry, botPos, edge);
    }

    static Point selectClimbWaypoint(BotNavigationGraph graph, BotEntry entry, Point botPos, BotNavigationGraph.Edge edge) {
        if (entry.inAir) {
            return new Point(edge.endPoint);
        }
        if (entry.climbing && edge.launchStepX != 0) {
            // Jump-off and rope-to-rope exits: only hold position when the exit can execute
            // immediately; otherwise keep steering toward the launch window. The edge carries a Y launch
            // window [launchMinY, launchMaxY] (every height in it lands in toRegion, verified at graph-gen),
            // so steer to the nearest in-window climb height — the Y twin of steerXWithinLaunchWindow —
            // rather than a single authored pixel. Steering toward edge.endPoint here would be a
            // runtime-only model mismatch because a climbing bot cannot approach the off-rope landing point.
            if (graph != null && canExecuteClimbExitFromCurrentPosition(graph, entry.bot.getMap(), botPos, edge)) {
                return new Point(botPos);
            }
            return new Point(edge.startPoint.x, steerYWithinLaunchWindow(edge, botPos.y));
        }
        if (entry.climbing) {
            // launchStepX==0: keep holding climb direction on the rope and let physics dismount
            // the bot at the boundary. The on-rope steering target should stay on the rope X;
            // trying to snap to an off-rope landing point is a runtime-only constraint and can
            // re-clamp the bot back onto the rope top/bottom.
            int ropeX = entry.climbRope != null ? entry.climbRope.x() : edge.startPoint.x;
            return new Point(ropeX, edge.endPoint.y);
        }
        if (edge.launchStepX != 0 && edge.launchMaxX > edge.launchMinX) {
            // Grounded rope-jump entry: the gate accepts the whole launch window, so steer
            // to the nearest in-window x (inset), not the authored startPoint pixel.
            return new Point(steerXWithinLaunchWindow(edge, botPos.x), edge.startPoint.y);
        }
        return new Point(edge.startPoint);
    }

    /** Nearest in-window steering x, inset from the window boundary (center when narrow). */
    static int steerXWithinLaunchWindow(BotNavigationGraph.Edge edge, int botX) {
        int inset = Math.min((edge.launchMaxX - edge.launchMinX) / 2, LAUNCH_WINDOW_STEER_INSET_PX);
        return Math.clamp(botX, edge.launchMinX + inset, edge.launchMaxX - inset);
    }

    /** Nearest in-window steering climb height, inset from the boundary (rope-exit twin of the x version). */
    static int steerYWithinLaunchWindow(BotNavigationGraph.Edge edge, int botY) {
        int inset = Math.min((edge.launchMaxY - edge.launchMinY) / 2, LAUNCH_WINDOW_STEER_INSET_PX);
        return Math.clamp(botY, edge.launchMinY + inset, edge.launchMaxY - inset);
    }

    private static BotNavigationGraph resolveActiveGraph(MapleMap map, BotMovementProfile movementProfile) {
        return BotNavigationGraphProvider.peekBestGraph(map, movementProfile);
    }

    static Point selectDropWaypoint(BotEntry entry,
                                    BotNavigationGraph graph,
                                    Point botPos,
                                    BotNavigationGraph.Edge edge) {
        if (entry.inAir) {
            return new Point(edge.endPoint);
        }
        if (edge.launchStepX == 0) {
            BotNavigationGraph.Region fromRegion = graph != null ? graph.getRegion(edge.fromRegionId) : null;
            if (fromRegion == null || fromRegion.isRopeRegion) {
                return new Point(edge.startPoint);
            }
            int targetX = edge.containsLaunchX(botPos.x)
                    ? botPos.x
                    : steerXWithinLaunchWindow(edge, botPos.x);
            return fromRegion.pointAt(targetX);
        }

        if (hasReachedDirectionalDropRunway(botPos, edge)) {
            return new Point(edge.endPoint);
        }

        BotNavigationGraph.Region fromRegion = graph.getRegion(edge.fromRegionId);
        if (fromRegion == null || fromRegion.isRopeRegion) {
            return new Point(edge.endPoint);
        }

        BotPhysicsEngine.WalkOffLanding liveOutcome = BotPhysicsEngine.simulateWalkOffLanding(
                entry.bot.getMap(), botPos, Integer.signum(edge.launchStepX),
                new BotPhysicsEngine.GroundTravelState(entry.physX, entry.hspeed, entry.groundPhysicsCarryMs),
                entry.movementProfile);
        if (matchesDirectionalDrop(edge, graph, liveOutcome)) {
            // Like rope top step-offs, once the continuous-control exit is naturally executable
            // we stop targeting an intermediate anchor and just keep feeding the authored
            // direction until physics performs the dismount.
            return new Point(edge.endPoint);
        }
        return new Point(edge.startPoint);
    }

    private static boolean hasReachedDirectionalDropRunway(Point botPos, BotNavigationGraph.Edge edge) {
        if (botPos == null || edge == null || edge.launchStepX == 0) {
            return false;
        }

        int direction = Integer.signum(edge.launchStepX);
        return direction > 0
                ? botPos.x >= edge.startPoint.x
                : botPos.x <= edge.startPoint.x;
    }

    private static boolean matchesDirectionalDrop(BotNavigationGraph.Edge edge,
                                                  BotNavigationGraph graph,
                                                  BotPhysicsEngine.WalkOffLanding outcome) {
        if (outcome == null || outcome.landing() == null) {
            return false;
        }
        Foothold landingFoothold = outcome.landing().foothold();
        if (landingFoothold == null) {
            return false;
        }
        if (graph.regionIdByFootholdId.getOrDefault(landingFoothold.getId(), -1) != edge.toRegionId) {
            return false;
        }
        int xTolerance = Math.max(6, Math.abs(edge.launchStepX) + 2);
        int yTolerance = BotMovementManager.cfg.JUMP_Y_THRESH * 2;
        return Math.abs(outcome.landing().point().x - edge.endPoint.x) <= xTolerance
                && Math.abs(outcome.landing().point().y - edge.endPoint.y) <= yTolerance;
    }

    // Crowd de-stacking under a shared cache: each bot hashes (by its stable routeSeed) to one of
    // ROUTE_BUCKETS. Bucket 0 is the optimal (seed-0) route; buckets 1..N-1 use jittered weighted-A*
    // so a crowd heading the same way fans across up to N routes instead of all stacking on one --
    // the same diversity the per-bot jitter gave, but at N searches per region-pair, not one per bot.
    static int ROUTE_BUCKETS = 8;

    // Master switch for the position-blind bucket route cache (graph.cachedNextHop/putNextHop, fed by
    // findNextEdge + warmPortalRoutes). Lives in BotManager.cfg so it's live-toggleable from /admin; see
    // ROUTE_CACHE_ENABLED there for the rationale (OFF by default — it caused region ping-pong).
    private static boolean routeCacheEnabled() {
        return BotManager.cfg.ROUTE_CACHE_ENABLED;
    }

    private static long bucketRouteSeed(int bucket) {
        return bucket == 0 ? 0L : (0x9E3779B97F4A7C15L * bucket);
    }

    private static int routeBucket(Character bot) {
        return (int) Long.remainderUnsigned(routeSeed(bot), ROUTE_BUCKETS);
    }

    private static BotNavigationGraph.Edge findNextEdge(BotNavigationGraph graph,
                                                        Character bot,
                                                        int startRegionId,
                                                        int targetRegionId,
                                                        Point targetPos) {
        MapleMap map = bot.getMap();
        if (routeCacheEnabled() && !graph.portalRoutesWarmed) {
            warmPortalRoutes(graph, map);
        }
        // Skill-capable bots (teleport/flash-jump + MP/meso headroom) plan with a fresh per-bot
        // two-pass compare and bypass the shared walk-only route cache: the skill decision is
        // per-bot and MP/meso-dependent, so it must never be cached into the slot other bots read.
        if (botCanUseMovementSkill(bot)) {
            return findNextEdgeWithSkills(graph, map, bot, startRegionId, targetRegionId, targetPos);
        }
        int bucket = routeBucket(bot);
        // Same-region next hop is position-dependent and must NEVER use the position-blind cache. The
        // cache is keyed only by (startRegion, targetRegion, bucket), so every in-region target shares
        // one slot. An intra-map "tubi" PORTAL (a self-loop r->r warp, e.g. Nautilus 120000100's
        // 164<->2798) cached for one target then gets served to a different in-region target it is wrong
        // for: the bot warps on the in-map shortcut instead of walking the few px to the real exit
        // portal, and because each post-warp re-plan re-reads the same stale slot it loops forever and
        // never reaches the map-exit portal (live: pirate bots stuck in the hallway, never job-advancing).
        // Intra-region routing is cheap and only reached on the uncommittable-route fallback; compute it
        // fresh from the live position every time so A* picks the real direct walk once the bot is near.
        if (startRegionId == targetRegionId) {
            List<BotNavigationGraph.Edge> sameRegionPath =
                    findPath(graph, map, bot.getPosition(), startRegionId, targetRegionId, targetPos, "fallback-sameregion", bucketRouteSeed(bucket));
            return sameRegionPath.isEmpty() ? null : collapseLeadingWalkEdges(sameRegionPath);
        }
        // Cache hit: O(1), no search. A cached PORTAL hop whose portal is now closed (isEdgeUsable
        // false) falls through to a fresh search, which reroutes around it and overwrites the slot.
        // Gated by routeCacheEnabled: when off, always miss -> fresh position-aware search.
        BotNavigationGraph.Edge cached = routeCacheEnabled()
                ? graph.cachedNextHop(startRegionId, targetRegionId, bucket) : null;
        if (cached != null && (cached == BotNavigationGraph.NO_EDGE || isEdgeUsable(graph, map, cached))) {
            return cached == BotNavigationGraph.NO_EDGE ? null : cached;
        }
        // Miss: search once for this bucket, cache the next hop. Region progression (and other bots on
        // the same route) then hit the cache -- a fresh A* fires only on a genuinely new (pair, bucket).
        List<BotNavigationGraph.Edge> path =
                findPath(graph, map, bot.getPosition(), startRegionId, targetRegionId, targetPos, "fallback", bucketRouteSeed(bucket));
        BotNavigationGraph.Edge next = path.isEmpty() ? null : collapseLeadingWalkEdges(path);
        if (routeCacheEnabled()) {
            graph.putNextHop(startRegionId, targetRegionId, bucket, ROUTE_BUCKETS,
                    next == null ? BotNavigationGraph.NO_EDGE : next);
        }
        return next;
    }

    // --- Movement skills (teleport / flash jump) --------------------------------------------------
    // Cross-region teleport/flash-jump edges live in the shared graph (BotNavigationGraphProvider) and
    // are filtered here by skill possession. A bot considers them only with the skill AND headroom:
    // >40% MP (preserve combat/heal reserves) and >500k meso (well-off bots zip around; poor ones walk).
    private static final int[] TELEPORT_SKILL_IDS = {
            FPWizard.TELEPORT, ILWizard.TELEPORT, Cleric.TELEPORT, BlazeWizard.TELEPORT, Evan.TELEPORT};
    private static final int[] FLASH_JUMP_SKILL_IDS = {Hermit.FLASH_JUMP, NightWalker.FLASH_JUMP};
    private static final int MOVEMENT_SKILL_MIN_MP_PCT = 40;
    private static final int MOVEMENT_SKILL_MIN_MESO = 500_000;
    private static final int SKILL_CLOSE_GATE_MS = 1200;  // below this walk cost, never bother with skills
    private static final int SKILL_FAR_GATE_MS = 6000;    // at/above, accept almost any saving
    private static final int INTRA_EXPRESS_MIN_PX = 300;       // only blink/dash along a same-platform stretch this long
    private static final long SKILL_CAST_COOLDOWN_MS = 490L;   // teleport recast floor: monitored-packets-teleport-updown shows real casts ~504-510ms apart at the limit; sit just below so blinks stay distinct (not one bunched warp) yet never out-pace a human

    private static int botSkillLevel(Character bot, int[] ids) {
        int best = 0;
        for (int id : ids) {
            best = Math.max(best, bot.getSkillLevel(id));
        }
        return best;
    }

    private static boolean hasTeleport(Character bot) {
        return botSkillLevel(bot, TELEPORT_SKILL_IDS) > 0;
    }

    private static boolean hasFlashJump(Character bot) {
        return botSkillLevel(bot, FLASH_JUMP_SKILL_IDS) > 0;
    }

    private static int skillMpCon(Character bot, int[] ids) {
        for (int id : ids) {
            int lvl = bot.getSkillLevel(id);
            if (lvl > 0) {
                Skill skill = SkillFactory.getSkill(id);
                StatEffect effect = skill == null ? null : skill.getEffect(lvl);
                if (effect != null) {
                    return effect.getMpCon();
                }
            }
        }
        return 0;
    }

    private static int botTeleportMpCon(Character bot) {
        return skillMpCon(bot, TELEPORT_SKILL_IDS);
    }

    private static int botFlashJumpMpCon(Character bot) {
        return skillMpCon(bot, FLASH_JUMP_SKILL_IDS);
    }

    /** A bot may consider teleport/flash-jump only with the skill AND >40% MP AND >500k meso. */
    static boolean botCanUseMovementSkill(Character bot) {
        if (!hasTeleport(bot) && !hasFlashJump(bot)) {
            return false;
        }
        if (bot.getMeso() <= MOVEMENT_SKILL_MIN_MESO) {
            return false;
        }
        int maxMp = bot.getMaxMp();
        return maxMp > 0 && bot.getMp() * 100 > maxMp * MOVEMENT_SKILL_MIN_MP_PCT;
    }

    /** Minimum cost (ms) a skill route must save over walking, scaled by trip length: a big fraction
     *  when the target is close (rarely bother) easing to ~5% when far (use even to straighten a long
     *  walk). Returned to the gate in {@link #findNextEdgeWithSkills}. */
    private static int skillSavingsThreshold(int walkCostMs) {
        double t = Math.clamp((walkCostMs - SKILL_CLOSE_GATE_MS) / (double) (SKILL_FAR_GATE_MS - SKILL_CLOSE_GATE_MS), 0.0, 1.0);
        double frac = 0.60 * (1.0 - t) + 0.05 * t;
        return (int) Math.round(frac * walkCostMs);
    }

    /** Two-pass plan for a skill bot: walk-only baseline, then (if the trip isn't trivially short) a
     *  skill-enabled pass, taken only when it saves at least the distance-scaled threshold. */
    private static BotNavigationGraph.Edge findNextEdgeWithSkills(BotNavigationGraph graph,
                                                                  MapleMap map,
                                                                  Character bot,
                                                                  int startRegionId,
                                                                  int targetRegionId,
                                                                  Point targetPos) {
        List<BotNavigationGraph.Edge> path = skillAwareRoutePath(graph, map, bot, startRegionId, targetRegionId, targetPos);
        return path.isEmpty() ? null : collapseLeadingWalkEdges(path);
    }

    /** Full skill-aware route path (the two-pass walk-vs-skill compare), used both for the next-hop and
     *  for committing a whole route. */
    private static List<BotNavigationGraph.Edge> skillAwareRoutePath(BotNavigationGraph graph,
                                                                     MapleMap map,
                                                                     Character bot,
                                                                     int startRegionId,
                                                                     int targetRegionId,
                                                                     Point targetPos) {
        long seed = routeSeed(bot);
        SearchOutcome walkOnly = runSearch(graph, map, bot.getPosition(), startRegionId, targetRegionId,
                targetPos, "skill-walk", useAdmissibleHeuristic, true, seed, false, bot);
        SearchOutcome chosen = walkOnly;
        if (walkOnly.cost() > SKILL_CLOSE_GATE_MS) {
            SearchOutcome withSkills = runSearch(graph, map, bot.getPosition(), startRegionId, targetRegionId,
                    targetPos, "skill-jump", useAdmissibleHeuristic, true, seed, true, bot);
            int saved = walkOnly.cost() - withSkills.cost();
            if (!withSkills.path().isEmpty() && saved >= skillSavingsThreshold(walkOnly.cost())) {
                chosen = withSkills;
            }
        }
        return chosen.path();
    }

    /**
     * The bot's full committed route to the goal, computed once with the bot's OWN seed (so per-bot
     * route diversity is preserved) and then followed hop-by-hop. Returns {@code null} when the route
     * should NOT be committed — it contains an intra-region PORTAL self-loop (a same-region detour);
     * following those by region-match could re-select the self-loop forever, so the per-hop planner
     * ({@link #findNextEdge}) handles them as before. An empty list means "direct walk, no hop".
     */
    static List<BotNavigationGraph.Edge> computeCommittedRoute(BotNavigationGraph graph, Character bot,
                                                               int startRegionId, int targetRegionId, Point targetPos) {
        MapleMap map = bot.getMap();
        if (routeCacheEnabled() && !graph.portalRoutesWarmed) {
            warmPortalRoutes(graph, map);
        }
        List<BotNavigationGraph.Edge> route = botCanUseMovementSkill(bot)
                ? skillAwareRoutePath(graph, map, bot, startRegionId, targetRegionId, targetPos)
                : findPath(graph, bot, startRegionId, targetRegionId, targetPos);
        for (BotNavigationGraph.Edge e : route) {
            if (e.type == BotNavigationGraph.EdgeType.PORTAL && e.fromRegionId == e.toRegionId) {
                return null;
            }
        }
        return route;
    }

    /**
     * Next hop off the committed route: the first usable, non-WALK edge leaving the bot's current
     * region. A* routes are region-acyclic, so the bot advances along its own route and never reverses
     * into the region it just came from (the GearArrow r45&lt;-&gt;r42 ping-pong from inconsistent
     * position-blind cache entries). Returns {@code null} when the route is absent/stale (goal region
     * changed) or the bot's region isn't on it (knocked off) — the caller then recomputes and commits
     * a fresh route.
     */
    static BotNavigationGraph.Edge nextCommittedRouteEdge(BotNavigationGraph graph, BotEntry entry,
                                                          int startRegionId, int targetRegionId) {
        List<BotNavigationGraph.Edge> route = entry.committedRoute;
        if (route == null || route.isEmpty() || entry.committedRouteTargetRegionId != targetRegionId) {
            return null;
        }
        int cursor = Math.max(0, entry.committedRouteCursor);
        // Advance past hops the bot has already completed: it now stands at the current hop's toRegion
        // (it landed). Routes can revisit a region at different points (jump-up/drop-down staircase), so
        // we follow the SEQUENCE by cursor — matching fromRegion alone aliases a later visit onto an
        // earlier hop and bounces the bot (pathlog-WeeklyCovert r66<->r67).
        while (cursor < route.size()
                && route.get(cursor).toRegionId == startRegionId
                && route.get(cursor).fromRegionId != startRegionId) {
            cursor++;
        }
        while (cursor < route.size() && route.get(cursor).type == BotNavigationGraph.EdgeType.WALK) {
            cursor++;
        }
        if (cursor >= route.size()) {
            return null; // route exhausted (or knocked off its tail) — caller recomputes
        }
        BotNavigationGraph.Edge e = route.get(cursor);
        if (e.fromRegionId == startRegionId && isEdgeUsable(graph, entry.bot, e)) {
            entry.committedRouteCursor = cursor;
            return e;
        }
        return null; // bot's region isn't where the route expects it — knocked off, recompute
    }

    /**
     * Intra-region express: a skill bot far from a SAME-region target blinks (teleport) or dashes
     * (flash jump) along the platform instead of walking the whole stretch — the "speed up a straight
     * walk" case the region A* can't model as an edge. Safeguards: lands in the same region only (no
     * fall-off into a gap), bounded so it can't overshoot the target, MP-affordable, cadence-throttled.
     * Returns a consumed directive when it acted, else null (the bot walks normally).
     */
    private static NavigationDirective tryIntraRegionSkillHop(BotEntry entry, Character bot, BotNavigationGraph graph,
                                                              Point botPos, Point target, int regionId) {
        if (target == null || entry.inAir || entry.climbing) {
            return null;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs < entry.skillHopReadyAtMs) {
            return null;
        }
        int dx = target.x - botPos.x;
        if (Math.abs(dx) <= INTRA_EXPRESS_MIN_PX) {
            return null; // close enough — just walk it (avoids twitchy single blinks near the target)
        }
        int dir = Integer.signum(dx);
        MapleMap map = bot.getMap();

        // Teleport (mages): blink range px, but only if the landing stays on the same platform within
        // the vertical snap band — otherwise the platform ended/there's a gap, so walk to the edge.
        if (hasTeleport(bot)) {
            int mpCon = botTeleportMpCon(bot);
            if (bot.getMp() >= mpCon) {
                Point dest = BotPhysicsEngine.teleportLanding(map, botPos, dir, 0,
                        BotNavigationGraphProvider.TELEPORT_RANGE_PX, BotNavigationGraphProvider.TELEPORT_Y_SNAP_PX);
                if (dest != null && regionIdAt(graph, map, dest) == regionId) {
                    Point origin = new Point(botPos);
                    BotPhysicsEngine.teleportTo(entry, bot, dest);
                    if (mpCon > 0) {
                        bot.addMP(-mpCon);
                    }
                    BotMovementManager.broadcastTeleport(entry, origin, dest);
                    entry.skillHopReadyAtMs = nowMs + SKILL_CAST_COOLDOWN_MS;
                    return new NavigationDirective(target, true);
                }
            }
        }

        // Flash jump (thieves): dash if the arc lands in the same region, ahead, and short of the target.
        if (hasFlashJump(bot)) {
            int mpCon = botFlashJumpMpCon(bot);
            if (bot.getMp() >= mpCon) {
                int jumpStep = BotPhysicsEngine.walkStep(map, entry.movementProfile) * dir;
                BotPhysicsEngine.JumpLanding fj = BotPhysicsEngine.simulateFlashJumpLanding(map, botPos, jumpStep, entry.movementProfile);
                if (fj != null && regionIdAt(graph, map, fj.point()) == regionId
                        && dir * (fj.point().x - botPos.x) > 0
                        && dir * (target.x - fj.point().x) > 0) {
                    BotMovementManager.initiateJump(entry, bot, jumpStep);
                    entry.pendingFlashJump = true; // AFTER launch — launchAirborne clears it; consumed at apex
                    if (mpCon > 0) {
                        bot.addMP(-mpCon);
                    }
                    entry.skillHopReadyAtMs = nowMs + SKILL_CAST_COOLDOWN_MS;
                    return new NavigationDirective(target, true);
                }
            }
        }
        return null;
    }

    private static int regionIdAt(BotNavigationGraph graph, MapleMap map, Point p) {
        Foothold fh = BotPhysicsEngine.findGroundFoothold(map, p);
        return fh == null ? -1 : graph.regionIdByFootholdId.getOrDefault(fh.getId(), -1);
    }

    /** Precompute the canonical (bucket-0) hop between every portal-region pair, once per graph. */
    private static void warmPortalRoutes(BotNavigationGraph graph, MapleMap map) {
        synchronized (graph) {
            if (graph.portalRoutesWarmed) {
                return;
            }
            List<Integer> portals = graph.portalRegionIds();
            for (int from : portals) {
                BotNavigationGraph.Region fromRegion = graph.getRegion(from);
                if (fromRegion == null) {
                    continue;
                }
                for (int to : portals) {
                    if (from == to || graph.cachedNextHop(from, to, 0) != null) {
                        continue;
                    }
                    BotNavigationGraph.Region toRegion = graph.getRegion(to);
                    if (toRegion == null) {
                        continue;
                    }
                    List<BotNavigationGraph.Edge> path = findPath(
                            graph, map, fromRegion.centerPoint(), from, to, toRegion.centerPoint(), "warm");
                    BotNavigationGraph.Edge next = path.isEmpty() ? null : collapseLeadingWalkEdges(path);
                    graph.putNextHop(from, to, 0, ROUTE_BUCKETS,
                            next == null ? BotNavigationGraph.NO_EDGE : next);
                }
            }
            graph.portalRoutesWarmed = true;
        }
    }

    static List<BotNavigationGraph.Edge> findPath(BotNavigationGraph graph,
                                                  Character bot,
                                                  int startRegionId,
                                                  int targetRegionId,
                                                  Point targetPos) {
        return findPath(graph, bot.getMap(), bot.getPosition(), startRegionId, targetRegionId, targetPos, "committed", routeSeed(bot));
    }

    /** Skill-enabled path for /api/navprobe debugging — routes through teleport/flash-jump edges the bot
     *  is eligible for (by skill possession), so an LLM/operator can see what the planner would pick.
     *  Shows the raw skill-enabled route: no MP/meso gate, no cost-saved threshold (those are runtime
     *  decisions in findNextEdgeWithSkills) — this answers "is a skill route even available/routable". */
    static List<BotNavigationGraph.Edge> findPathWithSkills(BotNavigationGraph graph,
                                                            Character bot,
                                                            int startRegionId,
                                                            int targetRegionId,
                                                            Point targetPos) {
        return runSearch(graph, bot.getMap(), bot.getPosition(), startRegionId, targetRegionId, targetPos,
                "navprobe-skills", useAdmissibleHeuristic, true, routeSeed(bot), true, bot).path();
    }

    static List<BotNavigationGraph.Edge> findPath(BotNavigationGraph graph,
                                                  MapleMap map,
                                                  Point startPos,
                                                  int startRegionId,
                                                  int targetRegionId,
                                                  Point targetPos) {
        return findPath(graph, map, startPos, startRegionId, targetRegionId, targetPos, null);
    }

    static List<BotNavigationGraph.Edge> findPathForTargetScore(BotNavigationGraph graph,
                                                                MapleMap map,
                                                                Point startPos,
                                                                int startRegionId,
                                                                int targetRegionId,
                                                                Point targetPos) {
        return findPath(graph, map, startPos, startRegionId, targetRegionId, targetPos, "target-score");
    }

    /**
     * Production pathfinding heuristic toggle. When {@code true} (default) the search runs the
     * admissible h=0 (Dijkstra) variant: optimal-cost paths, no portal-skipping. Flip to
     * {@code false} to restore the legacy dx/walk-speed heuristic (faster per search, but on
     * Kerning City ~19% of cross-region paths were non-optimal and ~7% walked past a usable
     * portal — see {@code BotNavigationProbe --measure}). The legacy {@link #heuristic} and the
     * {@link #runSearch} zeroHeuristic branch are both retained; this is the single knob.
     */
    static boolean useAdmissibleHeuristic = true;

    /**
     * Goal-distance heuristic. When {@code true} (default) the per-bot A* heuristic is a one-step
     * lookahead: live travel from the bot's point to each real region exit + that exit's reverse-
     * Dijkstra {@link BotNavigationGraph#costToGoal cost-to-goal} over the actual edge graph. This is
     * portal-aware (a "backward" portal that genuinely shortens the route scores low, so the search
     * heads toward it) and keeps a vertical/position gradient inside wide regions (the point->exit
     * term), replacing the old X-only straight-line heuristic that had neither -- which made tall
     * maps probe up/down backward edges and cap. Flip off to restore the pure straight-line term.
     */
    static boolean useGoalDistanceHeuristic = true;

    private static List<BotNavigationGraph.Edge> findPath(BotNavigationGraph graph,
                                                          MapleMap map,
                                                          Point startPos,
                                                          int startRegionId,
                                                          int targetRegionId,
                                                          Point targetPos,
                                                          String pathfindCaller) {
        return findPath(graph, map, startPos, startRegionId, targetRegionId, targetPos, pathfindCaller, 0L);
    }

    private static List<BotNavigationGraph.Edge> findPath(BotNavigationGraph graph,
                                                          MapleMap map,
                                                          Point startPos,
                                                          int startRegionId,
                                                          int targetRegionId,
                                                          Point targetPos,
                                                          String pathfindCaller,
                                                          long routeSeed) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                pathfindCaller, useAdmissibleHeuristic, true, routeSeed, false, null).path();
    }

    /** Walk-only convenience overload (no skill edges) — used by probes and white-box tests. */
    static SearchOutcome runSearch(BotNavigationGraph graph,
                                   MapleMap map,
                                   Point startPos,
                                   int startRegionId,
                                   int targetRegionId,
                                   Point targetPos,
                                   String pathfindCaller,
                                   boolean zeroHeuristic,
                                   boolean instrument,
                                   long routeSeed) {
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                pathfindCaller, zeroHeuristic, instrument, routeSeed, false, null);
    }

    /**
     * Core region-graph A* search. With {@code zeroHeuristic=true} it runs an admissible h=0
     * search (degenerates to Dijkstra) that always returns the optimal-cost path; the default
     * dx-based heuristic can over-estimate across zero-cost PORTAL edges (and faster-than-walk
     * jumps) and return a longer route. {@code instrument=false} skips slow-path logging and the
     * perf record so measurement callers can run the search twice cheaply.
     */
    static SearchOutcome runSearch(BotNavigationGraph graph,
                                   MapleMap map,
                                   Point startPos,
                                   int startRegionId,
                                   int targetRegionId,
                                   Point targetPos,
                                   String pathfindCaller,
                                   boolean zeroHeuristic,
                                   boolean instrument,
                                   long routeSeed,
                                   boolean skillsEnabled,
                                   Character bot) {
        // Default cap, no edge collection: every production/bot/test caller uses the standard budget.
        return runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos, pathfindCaller,
                zeroHeuristic, instrument, routeSeed, skillsEnabled, bot, MAX_EDGE_CHECKS, null);
    }

    /** Same search, with the edge-check cap as a parameter (a debug tool can run UNBOUNDED with
     *  {@code edgeCheckBudget = Integer.MAX_VALUE} to exhaust the graph and PROVE unreachability vs the
     *  live bot's bounded budget) and an optional {@code exploredSink}: when non-null, every USABLE edge
     *  the search examined is appended to it, so a best-effort result can show what was explored before
     *  giving up. SSOT: one search body — callers only vary the budget / opt into edge collection. */
    static SearchOutcome runSearch(BotNavigationGraph graph,
                                   MapleMap map,
                                   Point startPos,
                                   int startRegionId,
                                   int targetRegionId,
                                   Point targetPos,
                                   String pathfindCaller,
                                   boolean zeroHeuristic,
                                   boolean instrument,
                                   long routeSeed,
                                   boolean skillsEnabled,
                                   Character bot,
                                   int edgeCheckBudget,
                                   List<BotNavigationGraph.Edge> exploredSink) {
        long startedAt = System.nanoTime();
        PathfindProfile profile = null;
        // routeSeed != 0 (per-bot) diversifies routes so 100 bots don't stack on one optimal
        // path, and switches the search from h=0 Dijkstra (full-graph scan) to a per-bot
        // weighted A* that prunes. Seed 0 = exact legacy behavior (probes/calibration/non-bot).
        boolean randomized = routeSeed != 0;
        double epsilon = randomized ? 1.0 + hashFrac(routeSeed, EPSILON_SALT) * EPSILON_SPAN : 0.0;
        try {
            // Reachability early-exit: if the target region is not forward-reachable from the start for
            // this bot's usable edges, no path can exist -- skip the search. Without this a high-fan-out
            // start region caps A* (160k edge checks) every tick just to fail. Directed + skill-filtered
            // (the old undirected island index missed one-way edges and per-skill gating); PORTAL is
            // treated as usable so the reachable set is a superset of the real search's, making a "not
            // reachable" answer a sound skip.
            if (startRegionId != targetRegionId) {
                int skillMask = 0;
                if (skillsEnabled && bot != null) {
                    if (hasTeleport(bot)) {
                        skillMask |= BotNavigationGraph.SKILL_TELEPORT;
                    }
                    if (hasFlashJump(bot)) {
                        skillMask |= BotNavigationGraph.SKILL_FLASH_JUMP;
                    }
                }
                if (!graph.canReach(startRegionId, targetRegionId, skillMask)) {
                    // Target region is unreachable. Only the per-tick movement executor ("committed")
                    // redirects to walk AS CLOSE AS POSSIBLE: head to the reachable region nearest the
                    // target so the bot makes real progress and lands in NPC/portal interaction range for
                    // the stuck-near fallback, rather than stopping dead. The redirect region is
                    // known-reachable, so the A* below resolves it without burning the edge-check cap.
                    // Every other caller gets the clean empty "no path": scoring/approach-probe must rank
                    // it as unreachable, and the skill-walk/skill-jump cost-comparison searches must keep
                    // their true unreachable cost (a redirected cheap partial would hide that walking
                    // can't reach the target and suppress the teleport route).
                    if (!"committed".equals(pathfindCaller)) {
                        return new SearchOutcome(List.of(), Integer.MAX_VALUE, 0, false);
                    }
                    int redirectRegionId = graph.nearestReachableRegion(startRegionId, skillMask, targetPos);
                    if (redirectRegionId < 0) {
                        return new SearchOutcome(List.of(), Integer.MAX_VALUE, 0, false);
                    }
                    targetRegionId = redirectRegionId;
                    targetPos = graph.getRegion(redirectRegionId).pointAt(targetPos.x);
                }
            }
            // Goal-distance heuristic floor (portal-aware, position-blind region distances). Computed
            // once per search against the final target (post-redirect); the heuristic pairs it with the
            // live point->exit term. Only built when the heuristic is actually consulted (skip the pure
            // h=0 Dijkstra measurement path), and cached on the graph so the fleet shares one build.
            boolean usesHeuristic = randomized || !zeroHeuristic;
            Map<Integer, Integer> costToGoal = (useGoalDistanceHeuristic && usesHeuristic)
                    ? graph.costToGoal(targetRegionId) : null;
            PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingInt(node -> node.score));
            Map<SearchState, Integer> gScore = new HashMap<>();
            Map<SearchState, SearchState> cameFrom = new HashMap<>();
            Map<SearchState, BotNavigationGraph.Edge> cameByEdge = new HashMap<>();
            SearchState startState = new SearchState(startRegionId, new Point(startPos), false);
            SearchState bestGoalState = null;
            int bestGoalCost = Integer.MAX_VALUE;
            int expandedNodes = 0;
            int staleNodes = 0;
            int edgeChecks = 0;
            int usableEdges = 0;
            int relaxations = 0;
            int openPeak = 1;
            boolean capped = false;
            // Closest reached frontier (by raw distance-to-target), for best-effort partial progress
            // when a committed-route search caps out short of the goal.
            SearchState closestState = startState;
            long closestDistance = rawDistance(startPos, targetPos);

            gScore.put(startState, 0);
            open.add(new SearchNode(startState, 0, hValue(graph, startRegionId, startPos, targetRegionId, targetPos, costToGoal, zeroHeuristic, randomized, epsilon)));

            while (!open.isEmpty()) {
                if (edgeChecks >= edgeCheckBudget) {
                    capped = true;
                    break;
                }
                SearchNode current = open.poll();
                if (current.cost != gScore.getOrDefault(current.state, Integer.MAX_VALUE)) {
                    staleNodes++;
                    continue;
                }
                if (bestGoalState != null && current.score >= bestGoalCost) {
                    break;
                }
                expandedNodes++;

                if (current.state.regionId == targetRegionId) {
                    int goalCost = current.cost + intraRegionTravelCost(graph, current.state.regionId, current.state.point, targetPos);
                    if (goalCost < bestGoalCost) {
                        bestGoalCost = goalCost;
                        bestGoalState = current.state;
                    }
                }

                for (BotNavigationGraph.Edge edge : graph.getOutgoing(current.state.regionId)) {
                    edgeChecks++;
                    if (!isEdgeUsable(graph, map, bot, skillsEnabled, edge)) {
                        continue;
                    }
                    usableEdges++;
                    if (exploredSink != null) {
                        exploredSink.add(edge);   // debug: the explored frontier, for best-effort visualisation
                    }

                    boolean isPortal = edge.type == BotNavigationGraph.EdgeType.PORTAL;
                    // Portals are free on their own (edge.cost == 0). Charge PORTAL_USE_COOLDOWN_MS
                    // only when the bot enters a portal *through the exit* of the one it just took —
                    // i.e. it landed on a portal and immediately re-enters without walking. A
                    // viaPortal state's point IS the previous portal's exit, so this is exactly when
                    // that exit coincides with this portal's entry. That covers the "return to old
                    // position" round-trip and co-located A>B>C hops, but NOT A>B>walk>C>D (the bot
                    // walked off the exit first, so the entry points differ and it stays free).
                    boolean enteredThroughExit = current.state.viaPortal
                            && current.state.point.equals(edge.startPoint);
                    // A straight DROP (launchStepX==0) falls in place: it executes from the nearest
                    // in-window x to the bot (selectDropWaypoint) and lands at that same x, NOT from/at
                    // the authored window-midpoint start/end points. Cost the approach to that nearest
                    // in-window x AND land the next state there, so A* matches execution across the whole
                    // window. Otherwise a wide drop window inflates BOTH the approach (to the midpoint
                    // startPoint) and the downstream goal-walk (from the midpoint landing), which can
                    // lose a strictly-cheaper direct drop to a rope detour. Scoped to DROP+stepX==0
                    // only: directional drops and JUMPs keep their authored start/end geometry.
                    boolean straightDrop = edge.type == BotNavigationGraph.EdgeType.DROP && edge.launchStepX == 0;
                    // Rope-exit CLIMB edges carry a Y launch window: the bot launches from the nearest
                    // in-window climb height to its current position, and the fall cost is interpolated for
                    // that height (a top launch falls further and costs more than a low one) — the rope twin
                    // of the straight-drop in-window-x handling. Matches selectClimbWaypoint at execution.
                    boolean ropeWindow = edge.type == BotNavigationGraph.EdgeType.CLIMB
                            && edge.launchMaxY > edge.launchMinY;
                    Point approachPoint = straightDrop
                            ? edge.pointAtNearestLaunchX(current.state.point.x)
                            : ropeWindow
                                    ? edge.pointAtNearestLaunchY(current.state.point.y)
                                    : edge.startPoint;
                    Point landingPoint = straightDrop
                            ? new Point(approachPoint.x, edge.endPoint.y)
                            : edge.endPoint;
                    int edgeCost = isPortal && enteredThroughExit ? (int) PORTAL_USE_COOLDOWN_MS
                            : ropeWindow ? edge.launchCostAt(approachPoint.y)
                            : edge.cost;
                    int stepCost = intraRegionTravelCost(graph, current.state.regionId, current.state.point, approachPoint) + edgeCost;
                    // Per-bot positive jitter, stable per (bot, edge): different bots perceive
                    // different edges as slightly costlier and fan out onto distinct routes, while
                    // a single bot re-plans the same route every tick (no fluttering). Positive-only
                    // so reported cost never under-states true cost (keeps portal/direct-walk
                    // comparisons conservative).
                    if (randomized) {
                        stepCost += (int) Math.round(stepCost * JITTER_FRAC * hashFrac(routeSeed, edgeKey(edge)));
                    }
                    int tentativeCost = current.cost + stepCost;
                    SearchState nextState = new SearchState(edge.toRegionId, landingPoint, isPortal);
                    if (tentativeCost >= gScore.getOrDefault(nextState, Integer.MAX_VALUE)) {
                        continue;
                    }

                    relaxations++;
                    gScore.put(nextState, tentativeCost);
                    cameFrom.put(nextState, current.state);
                    cameByEdge.put(nextState, edge);
                    int fScore = tentativeCost + hValue(graph, nextState.regionId, edge.endPoint, targetRegionId, targetPos, costToGoal, zeroHeuristic, randomized, epsilon);
                    open.add(new SearchNode(nextState, tentativeCost, fScore));
                    openPeak = Math.max(openPeak, open.size());
                    long reachedDistance = rawDistance(landingPoint, targetPos);
                    if (reachedDistance < closestDistance) {
                        closestDistance = reachedDistance;
                        closestState = nextState;
                    }
                }
            }

            SearchState resultState = bestGoalState;
            if (resultState == null && capped && bestEffortCaller(pathfindCaller)
                    && !closestState.equals(startState)) {
                resultState = closestState; // best-effort: head toward the closest reached frontier
            }
            List<BotNavigationGraph.Edge> path = reconstructPath(startState, resultState, cameFrom, cameByEdge);
            profile = new PathfindProfile(
                    System.nanoTime() - startedAt,
                    expandedNodes,
                    staleNodes,
                    edgeChecks,
                    usableEdges,
                    relaxations,
                    openPeak,
                    bestGoalCost,
                    path.size(),
                    capped);
            boolean usesPortal = false;
            for (BotNavigationGraph.Edge edge : path) {
                if (edge.type == BotNavigationGraph.EdgeType.PORTAL) {
                    usesPortal = true;
                    break;
                }
            }
            return new SearchOutcome(path, bestGoalCost, expandedNodes, usesPortal);
        } finally {
            if (instrument) {
                if (profile == null) {
                    profile = new PathfindProfile(
                            System.nanoTime() - startedAt,
                            0,
                            0,
                            0,
                            0,
                            0,
                            0,
                            Integer.MAX_VALUE,
                            0,
                            false);
                }
                logSlowPathfind(graph, map, startPos, startRegionId, targetRegionId, targetPos, pathfindCaller, profile);
                BotPerformanceMonitor.recordPathfind(pathfindCaller, System.nanoTime() - startedAt);
            }
        }
    }

    /** Result of a single {@link #runSearch} call. */
    record SearchOutcome(List<BotNavigationGraph.Edge> path, int cost, int expandedNodes, boolean usesPortal) {
    }

    /** Side-by-side comparison of the production heuristic vs the admissible (h=0) optimal search. */
    record PathOptimality(int currentCost, int optimalCost, boolean currentUsesPortal,
                          boolean optimalUsesPortal, int currentExpanded, int optimalExpanded) {
        boolean reachable() {
            return currentCost != Integer.MAX_VALUE && optimalCost != Integer.MAX_VALUE;
        }

        boolean suboptimal() {
            return reachable() && currentCost > optimalCost;
        }

        int costDelta() {
            return reachable() ? currentCost - optimalCost : 0;
        }

        /** True when the heuristic walked a longer route while the optimal path took a portal. */
        boolean portalSkipped() {
            return suboptimal() && optimalUsesPortal && !currentUsesPortal;
        }
    }

    /**
     * Measurement helper: runs the same start/target search with the production heuristic and with
     * the admissible h=0 heuristic, returning both costs so callers can quantify how often (and by
     * how much) the current heuristic returns a non-optimal path. Not used on any production path.
     */
    static PathOptimality measureOptimality(BotNavigationGraph graph,
                                            MapleMap map,
                                            Point startPos,
                                            int startRegionId,
                                            int targetRegionId,
                                            Point targetPos) {
        SearchOutcome current = runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                "measure", false, false, 0L, false, null);
        SearchOutcome optimal = runSearch(graph, map, startPos, startRegionId, targetRegionId, targetPos,
                "measure", true, false, 0L, false, null);
        return new PathOptimality(current.cost(), optimal.cost(), current.usesPortal(),
                optimal.usesPortal(), current.expandedNodes(), optimal.expandedNodes());
    }

    private static void logSlowPathfind(BotNavigationGraph graph,
                                        MapleMap map,
                                        Point startPos,
                                        int startRegionId,
                                        int targetRegionId,
                                        Point targetPos,
                                        String pathfindCaller,
                                        PathfindProfile profile) {
        if (!profile.capped() && profile.elapsedNs() < SLOW_PATHFIND_WARN_NS) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = slowPathfindNextWarnAtMs.get();
        if (now < next || !slowPathfindNextWarnAtMs.compareAndSet(next, now + SLOW_PATHFIND_WARN_COOLDOWN_MS)) {
            slowPathfindSuppressed.incrementAndGet();
            return;
        }
        int suppressed = slowPathfindSuppressed.getAndSet(0);
        int regionCount = graph != null && graph.regions != null ? graph.regions.size() : -1;
        int outgoingFromStart = graph != null ? graph.getOutgoing(startRegionId).size() : -1;
        String caller = pathfindCaller == null || pathfindCaller.isBlank() ? "default" : pathfindCaller;
        int bestGoalCost = profile.bestGoalCost() == Integer.MAX_VALUE ? -1 : profile.bestGoalCost();
        log.warn(
                "Slow bot pathfind (suppressedSinceLast=" + suppressed
                        + "): caller={} took {} ms map={} startRegion={} targetRegion={} regions={} startOut={} startPos=({}, {}) targetPos=({}, {}) expanded={} stale={} edgeChecks={} usableEdges={} relaxations={} openPeak={} bestGoalCost={} resultEdges={} capped={}",
                caller,
                String.format("%.1f", profile.elapsedNs() / 1_000_000.0),
                map != null ? map.getId() : -1,
                startRegionId,
                targetRegionId,
                regionCount,
                outgoingFromStart,
                startPos != null ? startPos.x : -1,
                startPos != null ? startPos.y : -1,
                targetPos != null ? targetPos.x : -1,
                targetPos != null ? targetPos.y : -1,
                profile.expandedNodes(),
                profile.staleNodes(),
                profile.edgeChecks(),
                profile.usableEdges(),
                profile.relaxations(),
                profile.openPeak(),
                bestGoalCost,
                profile.resultEdges(),
                profile.capped());
    }

    private static List<BotNavigationGraph.Edge> reconstructPath(SearchState startState,
                                                                 SearchState goalState,
                                                                 Map<SearchState, SearchState> cameFrom,
                                                                 Map<SearchState, BotNavigationGraph.Edge> cameByEdge) {
        if (goalState == null || !cameByEdge.containsKey(goalState)) {
            return List.of();
        }

        List<BotNavigationGraph.Edge> path = new ArrayList<>();
        SearchState cursor = goalState;
        while (!cursor.equals(startState)) {
            BotNavigationGraph.Edge edge = cameByEdge.get(cursor);
            if (edge == null) {
                return List.of();
            }

            path.add(0, edge);
            SearchState previousState = cameFrom.get(cursor);
            if (previousState == null) {
                return List.of();
            }
            cursor = previousState;
        }
        return path;
    }

    static BotNavigationGraph.Edge collapseLeadingWalkEdges(List<BotNavigationGraph.Edge> path) {
        BotNavigationGraph.Edge first = path.get(0);
        if (first.type != BotNavigationGraph.EdgeType.WALK) {
            return first;
        }

        if (!isNoMovementWalk(first.startPoint, first.endPoint)) {
            return first;
        }

        int totalCost = 0;
        int walkCount = 0;
        while (walkCount < path.size()) {
            BotNavigationGraph.Edge edge = path.get(walkCount);
            if (edge.type != BotNavigationGraph.EdgeType.WALK
                    || !isNoMovementWalk(edge.startPoint, edge.endPoint)) {
                break;
            }
            totalCost += edge.cost;
            walkCount++;
        }

        if (walkCount >= path.size()) {
            return null;
        }

        BotNavigationGraph.Edge next = path.get(walkCount);
        return new BotNavigationGraph.Edge(first.fromRegionId, next.toRegionId, next.type,
                next.startPoint, next.endPoint, next.launchMinX, next.launchMaxX, next.launchMinY, next.launchMaxY,
                next.launchStepX, next.portalId, next.ropeX, next.ropeTopY, next.ropeBottomY, totalCost + next.cost);
    }

    private static boolean isEdgeUsable(BotNavigationGraph graph, Character bot, BotNavigationGraph.Edge edge) {
        // Committed-edge reuse path (runs every tick): keep a committed teleport/flash-jump edge as long
        // as the bot can still use skills (MP/meso may have dropped mid-trip — then it retires and the
        // bot replans). Only pay the skill/MP/meso check for an actual skill edge — && short-circuits.
        boolean skillEdge = edge.type == BotNavigationGraph.EdgeType.TELEPORT
                || edge.type == BotNavigationGraph.EdgeType.FLASH_JUMP;
        return isEdgeUsable(graph, bot.getMap(), bot, skillEdge && botCanUseMovementSkill(bot), edge);
    }

    private static boolean sameEdge(BotNavigationGraph.Edge left, BotNavigationGraph.Edge right) {
        return left == right || (left != null
                && right != null
                && left.fromRegionId == right.fromRegionId
                && left.toRegionId == right.toRegionId
                && left.type == right.type
                && left.launchMinX == right.launchMinX
                && left.launchMaxX == right.launchMaxX
                && left.launchStepX == right.launchStepX
                && left.portalId == right.portalId
                && left.ropeX == right.ropeX
                && left.ropeTopY == right.ropeTopY
                && left.ropeBottomY == right.ropeBottomY
                && left.startPoint.equals(right.startPoint)
                && left.endPoint.equals(right.endPoint));
    }

    static boolean shouldRetainCommittedGroundEdge(BotNavigationGraph.Edge current,
                                                   BotNavigationGraph.Edge replacement) {
        if (current == null || replacement == null) {
            return false;
        }
        if (current.fromRegionId != replacement.fromRegionId
                || current.toRegionId != replacement.toRegionId) {
            return false;
        }
        // Equivalent first exits into the same downstream region can trade off a few pixels of
        // approach cost as the bot shuffles on the source platform. Replacing the committed edge
        // every AI tick creates oscillation loops like the John 2026-05-01 down-jump trace,
        // where nav flips between a straight DROP and a nearby JUMP before either can execute.
        return current.type != BotNavigationGraph.EdgeType.WALK
                && replacement.type != BotNavigationGraph.EdgeType.WALK;
    }

    private static boolean isEdgeUsable(BotNavigationGraph graph, MapleMap map, BotNavigationGraph.Edge edge) {
        return isEdgeUsable(graph, map, null, false, edge);
    }

    private static boolean isEdgeUsable(BotNavigationGraph graph, MapleMap map, Character bot,
                                        boolean skillsEnabled, BotNavigationGraph.Edge edge) {
        return switch (edge.type) {
            case WALK, JUMP, DROP, CLIMB -> true;
            case PORTAL -> {
                Portal portal = map.getPortal(edge.portalId);
                yield portal != null && portal.getPortalStatus();
            }
            case TELEPORT -> skillsEnabled && bot != null && hasTeleport(bot);
            case FLASH_JUMP -> skillsEnabled && bot != null && hasFlashJump(bot);
        };
    }

    private static boolean usePortal(Character bot, int portalId) {
        Portal portal = bot.getMap().getPortal(portalId);
        if (portal == null || !portal.getPortalStatus()) {
            return false;
        }

        int oldMapId = bot.getMapId();
        Point oldPos = bot.getPosition();
        portal.enterPortal(bot.getClient());
        return bot.getMapId() != oldMapId || !bot.getPosition().equals(oldPos);
    }

    private static boolean isReadyForEdge(Point botPos, BotNavigationGraph.Edge edge) {
        int dx = Math.abs(botPos.x - edge.startPoint.x);
        int dy = Math.abs(botPos.y - edge.startPoint.y);

        return switch (edge.type) {
            case JUMP, FLASH_JUMP -> dx <= JUMP_READY_X_TOLERANCE && dy <= BotMovementManager.cfg.JUMP_Y_THRESH;
            // CLIMB rope-exits launch from anywhere in their Y window; for every other CLIMB the window is
            // degenerate (= startPoint.y) so this stays equivalent to the old dy check.
            case CLIMB -> dx <= EDGE_READY_X_TOLERANCE
                    && edge.containsLaunchY(botPos.y, BotMovementManager.cfg.JUMP_Y_THRESH * 2);
            case DROP, PORTAL -> dx <= EDGE_READY_X_TOLERANCE && dy <= BotMovementManager.cfg.JUMP_Y_THRESH * 2;
            default -> dx <= BotMovementManager.cfg.STOP_DIST + 8
                    && dy <= BotMovementManager.cfg.JUMP_Y_THRESH * 2;
        };
    }

    static boolean canExecuteJumpFromCurrentPosition(BotNavigationGraph graph,
                                                     MapleMap map,
                                                     Point botPos,
                                                     BotNavigationGraph.Edge edge) {
        if (edge.type != BotNavigationGraph.EdgeType.JUMP
                && edge.type != BotNavigationGraph.EdgeType.FLASH_JUMP) {
            return false;
        }
        return isWithinJumpLaunchWindow(graph, botPos, edge);
    }

    private static boolean canExecuteSelectedJumpFromCurrentPosition(BotNavigationGraph graph,
                                                                     BotEntry entry,
                                                                     MapleMap map,
                                                                     Point botPos,
                                                                     BotNavigationGraph.Edge edge) {
        if (!canExecuteJumpFromCurrentPosition(graph, map, botPos, edge)) {
            return false;
        }
        int launchX = selectedJumpLaunchX(entry, graph, edge);
        int tolerance = Math.max(1, BotPhysicsEngine.walkStep(map, entry != null ? entry.movementProfile : null));
        return Math.abs(botPos.x - launchX) <= tolerance;
    }

    private static boolean isReachableWithinRegion(BotNavigationGraph graph,
                                                   MapleMap map,
                                                   int regionId,
                                                   Point fromPos,
                                                   Point toPos) {
        BotNavigationGraph.Region region = graph.getRegion(regionId);
        if (region == null || fromPos == null || toPos == null) {
            return false;
        }
        if (region.isRopeRegion) {
            return fromPos.x == toPos.x;
        }

        int dir = Integer.compare(toPos.x, fromPos.x);
        Point previous = region.pointAt(fromPos.x);
        if (graph.findRegionId(map, previous) != regionId) {
            return false;
        }
        if (dir == 0) {
            return Math.abs(toPos.y - previous.y) <= BotMovementManager.cfg.JUMP_Y_THRESH;
        }

        for (int x = fromPos.x + dir; x != toPos.x + dir; x += dir) {
            Point current = region.pointAt(x);
            if (graph.findRegionId(map, current) != regionId) {
                return false;
            }
            if (!BotPhysicsEngine.isWalkableEndpointStep(Math.abs(current.x - previous.x), current.y - previous.y)) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    static boolean isWithinJumpLaunchWindow(BotNavigationGraph graph,
                                            Point botPos,
                                            BotNavigationGraph.Edge edge) {
        if (botPos == null
                || (edge.type != BotNavigationGraph.EdgeType.JUMP && edge.type != BotNavigationGraph.EdgeType.FLASH_JUMP)
                || !edge.containsLaunchX(botPos.x, edge.type == BotNavigationGraph.EdgeType.FLASH_JUMP ? FLASH_JUMP_LAUNCH_TOL : 0)) {
            return false;
        }

        BotNavigationGraph.Region fromRegion = graph.getRegion(edge.fromRegionId);
        if (fromRegion == null) {
            return false;
        }

        Point expectedLaunchPoint = fromRegion.pointAt(botPos.x);
        return Math.abs(botPos.y - expectedLaunchPoint.y) <= BotMovementManager.cfg.JUMP_Y_THRESH;
    }

    static boolean isWithinDropLaunchWindow(BotNavigationGraph graph,
                                            Point botPos,
                                            BotNavigationGraph.Edge edge) {
        if (botPos == null
                || edge.type != BotNavigationGraph.EdgeType.DROP
                || edge.launchStepX != 0
                || !edge.containsLaunchX(botPos.x)) {
            return false;
        }

        if (graph == null) {
            return Math.abs(botPos.y - edge.startPoint.y) <= BotMovementManager.cfg.JUMP_Y_THRESH;
        }

        BotNavigationGraph.Region fromRegion = graph.getRegion(edge.fromRegionId);
        if (fromRegion == null || fromRegion.isRopeRegion) {
            return false;
        }

        Point expectedLaunchPoint = fromRegion.pointAt(botPos.x);
        return Math.abs(botPos.y - expectedLaunchPoint.y) <= BotMovementManager.cfg.JUMP_Y_THRESH;
    }

    private static int selectedJumpLaunchX(BotEntry entry,
                                           BotNavigationGraph graph,
                                           BotNavigationGraph.Edge edge) {
        if (entry == null || graph == null || edge == null || edge.type != BotNavigationGraph.EdgeType.JUMP) {
            return edge != null ? edge.startPoint.x : 0;
        }
        BotNavigationGraph.Region fromRegion = graph.getRegion(edge.fromRegionId);
        if (fromRegion == null || fromRegion.isRopeRegion) {
            return edge.startPoint.x;
        }
        if (sameEdge(entry.navJumpLaunchEdge, edge)
                && entry.navJumpLaunchX >= edge.launchMinX
                && entry.navJumpLaunchX <= edge.launchMaxX) {
            return entry.navJumpLaunchX;
        }

        int minX = Math.max(edge.launchMinX, fromRegion.minX);
        int maxX = Math.min(edge.launchMaxX, fromRegion.maxX);
        if (minX > maxX) {
            minX = edge.launchMinX;
            maxX = edge.launchMaxX;
        }

        int width = Math.max(0, maxX - minX);
        int margin = Math.min(width / 2, Math.max(1, BotPhysicsEngine.walkStep(entry.bot.getMap(), entry.movementProfile) * 2));
        int randomMinX = minX + margin;
        int randomMaxX = maxX - margin;
        if (randomMinX > randomMaxX) {
            randomMinX = minX;
            randomMaxX = maxX;
        }

        int selectedX = randomMinX >= randomMaxX
                ? randomMinX
                : ThreadLocalRandom.current().nextInt(randomMinX, randomMaxX + 1);
        entry.navJumpLaunchEdge = edge;
        entry.navJumpLaunchX = selectedX;
        return selectedX;
    }

    private static int intraRegionTravelCost(BotNavigationGraph graph, Point from, Point to) {
        int dx = Math.abs(to.x - from.x);
        return Math.max(0, (int) Math.round((dx * 1000.0) / Math.max(1.0, graph.movementProfile.walkVelocityPxs())));
    }

    private static int intraRegionTravelCost(BotNavigationGraph graph, int regionId, Point from, Point to) {
        BotNavigationGraph.Region region = graph.getRegion(regionId);
        if (region != null && region.isRopeRegion) {
            int travel = Math.abs(to.y - from.y);
            return Math.max(0, (int) Math.round((travel * 1000.0) / Math.max(1, BotMovementManager.cfg.CLIMB_SPEED_PXS)));
        }
        return intraRegionTravelCost(graph, from, to);
    }

    private static int heuristic(BotNavigationGraph graph, int regionId, Point from,
                                 int targetRegionId, Point targetPos, Map<Integer, Integer> costToGoal) {
        if (costToGoal == null) {
            // No goal-distance index: straight-line estimate, but now X+Y (the old X-only term had no
            // vertical gradient, so tall maps probed up/down). Still position-aware.
            return manhattanCost(graph, from, targetPos);
        }
        if (regionId == targetRegionId) {
            return intraRegionTravelCost(graph, regionId, from, targetPos);
        }
        // One-step lookahead: for each real exit of this region, cost to walk/climb to that exit from
        // the bot's actual point + the exit edge cost + the exit neighbour's cached cost-to-goal. The
        // min over exits is an admissible lower bound (the true path leaves via one of them, and every
        // term under-estimates), it is portal-aware (cost-to-goal routes through portals), and the
        // point->exit term keeps a gradient inside wide regions where the region-level cache is flat.
        int best = Integer.MAX_VALUE;
        for (BotNavigationGraph.Edge e : graph.getOutgoing(regionId)) {
            Integer downstream = costToGoal.get(e.toRegionId);
            if (downstream == null) {
                continue; // exit leads somewhere that can't reach the goal
            }
            // Rope-exit windows: launch from the nearest in-window climb height and use its interpolated
            // cost, mirroring the search — so the heuristic stays consistent (and admissible).
            boolean ropeWindow = e.type == BotNavigationGraph.EdgeType.CLIMB && e.launchMaxY > e.launchMinY;
            Point approach = ropeWindow ? e.pointAtNearestLaunchY(from.y) : e.startPoint;
            int edgeCost = ropeWindow ? e.launchCostAt(approach.y) : e.cost;
            int c = intraRegionTravelCost(graph, regionId, from, approach) + edgeCost + downstream;
            if (c < best) {
                best = c;
            }
        }
        // No usable exit reaches the goal from here (dead-end region): fall back to straight-line so the
        // node still gets a finite, position-aware estimate rather than a flat zero.
        return best == Integer.MAX_VALUE ? manhattanCost(graph, from, targetPos) : best;
    }

    /** Straight-line lower bound on travel cost, X and Y, scaled to the fastest ground/climb speed so
     *  it under-estimates (admissible). Fallback only -- used when no cost-to-goal index is available. */
    private static int manhattanCost(BotNavigationGraph graph, Point from, Point targetPos) {
        long dist = Math.abs((long) targetPos.x - from.x) + Math.abs((long) targetPos.y - from.y);
        double fastest = Math.max(graph.movementProfile.walkVelocityPxs(), BotMovementManager.cfg.CLIMB_SPEED_PXS);
        return (int) Math.min(Integer.MAX_VALUE, Math.round((dist * 1000.0) / Math.max(1.0, fastest)));
    }

    private static long rawDistance(Point from, Point targetPos) {
        if (from == null || targetPos == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs((long) from.x - targetPos.x) + Math.abs((long) from.y - targetPos.y);
    }

    /** Committed-route movement callers get a best-effort partial path (toward the closest reached
     *  frontier) when a search caps out, so a bot heading to a far-but-reachable goal makes progress
     *  instead of stalling. Scoring/reachability callers stay strict (empty on cap = "too far"). */
    private static boolean bestEffortCaller(String caller) {
        return "committed".equals(caller) || "skill-walk".equals(caller) || "skill-jump".equals(caller);
    }

    // ponytail: route-diversification knobs — calibrated on map 10000 via BotRouteDiversityTest.
    // jitter spreads routes; epsilon trades diversity for a perf prune. At 0.55/0.15 the modal
    // corridor drops from 43% to ~28% of bots (12 distinct routes) for ~29% worst-case overhead.
    // Raising jitter further mostly buys overhead, not spread; lower epsilon = more spread, less prune.
    static double JITTER_FRAC = 0.55;    // per-edge cost perturbation 0..55%, stable per (bot, edge)
    static double EPSILON_SPAN = 0.15;   // weighted-A* heuristic inflation: epsilon in [1.0, 1.15) per bot
    private static final long EPSILON_SALT = 0xE95011L;

    /** Heuristic value: zeroSeed callers keep h=0/legacy; per-bot search uses an inflated (weighted) admissible h to prune. */
    private static int hValue(BotNavigationGraph graph, int regionId, Point from,
                              int targetRegionId, Point targetPos, Map<Integer, Integer> costToGoal,
                              boolean zeroHeuristic, boolean randomized, double epsilon) {
        if (randomized) {
            return (int) Math.round(epsilon * heuristic(graph, regionId, from, targetRegionId, targetPos, costToGoal));
        }
        return zeroHeuristic ? 0 : heuristic(graph, regionId, from, targetRegionId, targetPos, costToGoal);
    }

    /** Per-bot route seed; non-zero so the search takes the randomized branch. */
    private static long routeSeed(Character bot) {
        return mix64(bot.getId()) | 1L;
    }

    /** Stable identity for an edge so jitter is deterministic per (bot, edge), not per tick. */
    private static long edgeKey(BotNavigationGraph.Edge edge) {
        long k = edge.toRegionId;
        k = k * 31 + edge.startPoint.x;
        k = k * 31 + edge.startPoint.y;
        k = k * 31 + edge.type.ordinal();
        return k;
    }

    /** SplitMix64 finalizer mixing seed and key into a stable fraction in [0, 1). */
    private static double hashFrac(long seed, long key) {
        long h = mix64(seed ^ (key * 0x9E3779B97F4A7C15L));
        return (h >>> 11) * 0x1.0p-53;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static boolean shouldUsePreciseWalkTarget(BotNavigationGraph.Edge edge) {
        return edge != null
                && edge.type == BotNavigationGraph.EdgeType.WALK
                && !isNoMovementWalk(edge.startPoint, edge.endPoint);
    }

    private static boolean isNoMovementWalk(Point start, Point end) {
        return Math.abs(end.x - start.x) <= NO_MOVEMENT_WALK_TOLERANCE
                && Math.abs(end.y - start.y) <= NO_MOVEMENT_WALK_TOLERANCE;
    }

    private static boolean canGrabRopeAtCurrentPosition(Point botPos, Rope rope) {
        return Math.abs(botPos.x - rope.x()) <= BotMovementManager.cfg.ROPE_GRAB_X
                && botPos.y >= BotPhysicsEngine.firstClimbableY(rope)
                && botPos.y <= rope.bottomY();
    }

    private static boolean canAttachToRopeFromTopPlatform(BotNavigationGraph.Edge edge, Point botPos, Rope rope) {
        return Math.abs(botPos.x - rope.x()) <= BotMovementManager.cfg.ROPE_GRAB_X
                && edge.endPoint.y == BotPhysicsEngine.firstClimbableY(rope)
                && botPos.y < rope.topY()
                && rope.topY() - botPos.y <= BotPhysicsEngine.cfg.MAX_SNAP_DROP;
    }

    private static boolean canGrabRopeFromTopPlatform(BotNavigationGraph.Edge edge, Point botPos, Rope rope) {
        return edge.startPoint.y <= rope.topY() + BotMovementManager.cfg.JUMP_Y_THRESH
                && Math.abs(botPos.x - rope.x()) <= BotMovementManager.cfg.ROPE_GRAB_X;
    }

    private static boolean canExecuteClimbEntryFromCurrentPosition(MapleMap map,
                                                                   Point botPos,
                                                                   BotNavigationGraph.Edge edge,
                                                                   Rope rope) {
        return rope != null && (canGrabRopeAtCurrentPosition(botPos, rope)
                || canAttachToRopeFromTopPlatform(edge, botPos, rope)
                || canGrabRopeFromTopPlatform(edge, botPos, rope)
                || canExecuteGroundRopeJumpEntryFromCurrentPosition(botPos, edge));
    }

    private static boolean canExecuteGroundRopeJumpEntryFromCurrentPosition(Point botPos,
                                                                           BotNavigationGraph.Edge edge) {
        if (botPos == null || edge == null || edge.type != BotNavigationGraph.EdgeType.CLIMB) {
            return false;
        }
        return edge.containsLaunchX(botPos.x)
                && Math.abs(botPos.y - edge.startPoint.y) <= BotMovementManager.cfg.JUMP_Y_THRESH * 2;
    }

    private static boolean canExecuteClimbExitFromCurrentPosition(BotNavigationGraph graph,
                                                                  MapleMap map,
                                                                  Point botPos,
                                                                  BotNavigationGraph.Edge edge) {
        if (edge.type != BotNavigationGraph.EdgeType.CLIMB) {
            return false;
        }

        if (edge.launchStepX == 0) {
            // Step off the top of the rope onto the foothold above.
            Rope rope = findRopeForRegion(map, graph.getRegion(edge.fromRegionId));
            return rope != null && isTopStepOffExit(rope, botPos, edge);
        }

        // Jump-off (to ground or another rope): fire from anywhere STRICTLY inside the authored Y launch
        // window — the window expansion verified every height in [launchMinY, launchMaxY] lands in toRegion
        // (replacing the old single exact-Y point), so the range IS the tolerance. selectClimbWaypoint
        // steers the bot inset-inside the window before this fires; widening by a ± band would let it launch
        // from unverified heights that miss the target.
        if (edge.containsLaunchY(botPos.y)) {
            return true;
        }
        // Top-of-rope grab tolerance: the first-climbable anchor keeps its small extra slack.
        Rope rope = findRopeForRegion(map, graph.getRegion(edge.fromRegionId));
        return isTopRopeJumpExitReady(rope, botPos, edge);
    }

    private static boolean isTopRopeJumpExitReady(Rope rope, Point botPos, BotNavigationGraph.Edge edge) {
        // Top-of-rope tolerance window only: the bot lands at firstClimbableY when grabbing
        // from above (canTopGrab/canTopStep), and the launch arc is invariant within the first
        // climbStep below the top. Non-top anchors are single-point launch windows whose arcs
        // are precomputed by simulateRopeJumpLanding — launching from any other Y misses the
        // destination. The bot reaches non-top anchors exactly via the precise-target snap in
        // BotMovementManager.shouldSnapToClimbTarget (sub-tick clamp; the only one in the
        // physics path), so the bypass `botPos.y == edge.startPoint.y` in
        // canExecuteClimbExitFromCurrentPosition covers those without tolerance here.
        if (rope == null || botPos == null || edge == null || edge.launchStepX == 0) {
            return false;
        }
        int firstClimbableY = BotPhysicsEngine.firstClimbableY(rope);
        return edge.startPoint.x == rope.x()
                && edge.startPoint.y == firstClimbableY
                && botPos.x == rope.x()
                && botPos.y >= firstClimbableY
                && botPos.y <= firstClimbableY + BotPhysicsEngine.climbStepPerTick() + 2;
    }

    private static void startClimbing(BotEntry entry, Character bot, Rope rope, int climbY) {
        BotPhysicsEngine.attachToRope(entry, bot, rope, climbY);
        BotMovementManager.broadcastMovement(entry);
    }

    private static void setEdgeExecutionTarget(BotEntry entry, BotNavigationGraph.Edge edge) {
        entry.navPreciseTarget = false;
        entry.navTargetPos = new Point(edge.endPoint);
    }

    private static Point adjustPathTarget(BotEntry entry,
                                          BotNavigationGraph graph,
                                          int targetRegionId,
                                          Point rawTargetPos) {
        if (rawTargetPos == null || !entry.grinding || targetRegionId < 0) {
            return rawTargetPos;
        }

        BotNavigationGraph.Region targetRegion = graph.getRegion(targetRegionId);
        if (targetRegion == null || targetRegion.isRopeRegion) {
            return rawTargetPos;
        }

        int safeLeft = targetRegion.minX + BotMovementManager.cfg.GRIND_EDGE_MARGIN;
        int safeRight = targetRegion.maxX - BotMovementManager.cfg.GRIND_EDGE_MARGIN;
        if (safeLeft >= safeRight) {
            return rawTargetPos;
        }

        int clampedX = Math.max(safeLeft, Math.min(safeRight, rawTargetPos.x));
        return targetRegion.pointAt(clampedX);
    }

    private static int landingRegionId(BotNavigationGraph graph, BotPhysicsEngine.JumpLanding landing) {
        if (landing == null) {
            return -1;
        }
        return graph.regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1);
    }

    static int resolveCurrentRegionId(BotNavigationGraph graph,
                                      BotEntry entry,
                                      MapleMap map,
                                      Point botPos) {
        if (entry.climbing || (entry.bot != null && CharacterStance.isClimbing(entry.bot.getStance()))) {
            // Rope climbing state is authoritative. Ground lookup below a rope often resolves to
            // the nearby platform instead of the rope region, which can replan from the wrong side
            // of the rope and bounce between entry/exit climb edges.
            int ropeX = entry.climbRope != null ? entry.climbRope.x() : botPos.x;
            int ropeRegionId = graph.findRopeRegionId(new Point(ropeX, botPos.y));
            if (ropeRegionId >= 0) {
                return ropeRegionId;
            }
        }
        // Airborne over a real gap has no meaningful "current region": a ground lookup mid-arc
        // resolves to whatever foothold is below the arc, which can be an unrelated platform, and
        // runtime nav would discard the committed jump even though the authored ballistic landing
        // still agrees. But "airborne" while hugging a platform (within a snap) is NOT a real arc —
        // e.g. a bot settled at a rope bottom sits 1-2px above the ground in the off-graph gap under
        // the rope region. Returning -1 there gave an empty A* path and a grab/exit loop
        // (pathlog-rApIdScUrVy / live bot 1416, whose real route was a rope-free jump). Only blank the
        // region when the ground is genuinely far below; otherwise resolve to the platform underfoot.
        if (entry.inAir && BotPhysicsEngine.isGroundFarBelow(map, botPos)) {
            return -1;
        }
        return graph.findRegionId(map, botPos);
    }

    static int resolveTargetRegionId(BotNavigationGraph graph,
                                     BotEntry entry,
                                     MapleMap map,
                                     Point targetPos) {
        if (targetPos == null) {
            return -1;
        }

        Character owner = entry.owner;
        Character followAnchor = BotManager.getInstance().resolveFollowAnchor(entry, owner);
        if (entry.following
                && entry.moveTarget == null
                && entry.farmAnchor == null
                && !entry.shopVisitPending
                && !entry.grinding
                && followAnchor != null
                && followAnchor.getMap() == map) {
            // Follow mode + owner climbing: prioritise a rope target. The follow
            // resolver may have already snapped targetPos to a rope's X, so the
            // exact equality check below would miss — explicitly look for a rope
            // at targetPos, and fall back to the follow anchor's own rope region if none
            // is found there. This keeps the bot climbing onto rope alongside
            // the anchor instead of clamping to the platform below the rope.
            if (CharacterStance.isClimbing(followAnchor.getStance())) {
                int ropeRegionId = graph.findRopeRegionId(targetPos);
                if (ropeRegionId >= 0) {
                    return ropeRegionId;
                }
                return resolveCharacterRegionId(graph, map, followAnchor);
            }
            if (targetPos.equals(followAnchor.getPosition())) {
                return resolveCharacterRegionId(graph, map, followAnchor);
            }
        }

        return resolvePointTargetRegionId(graph, map, targetPos);
    }

    static int resolveCharacterRegionId(BotNavigationGraph graph,
                                        MapleMap map,
                                        Character character) {
        if (character == null) {
            return -1;
        }

        Point position = character.getPosition();
        if (position == null) {
            return -1;
        }

        if (CharacterStance.isClimbing(character.getStance())) {
            int ropeRegionId = graph.findRopeRegionId(position);
            if (ropeRegionId >= 0) {
                return ropeRegionId;
            }
        }

        return resolvePointTargetRegionId(graph, map, position);
    }

    static int resolvePointTargetRegionId(BotNavigationGraph graph,
                                          MapleMap map,
                                          Point position) {
        int ropeRegionId = graph.findRopeRegionId(position);
        if (ropeRegionId >= 0 && shouldPreferRopeRegion(map, position)) {
            return ropeRegionId;
        }
        return graph.findRegionId(map, position);
    }

    private static boolean shouldPreferRopeRegion(MapleMap map, Point position) {
        return BotPhysicsEngine.isGroundFarBelow(map, position);
    }

    private static boolean isRopeEntryEdge(BotNavigationGraph graph, BotNavigationGraph.Edge edge) {
        if (edge.type != BotNavigationGraph.EdgeType.CLIMB) {
            return false;
        }

        BotNavigationGraph.Region from = graph.getRegion(edge.fromRegionId);
        BotNavigationGraph.Region to = graph.getRegion(edge.toRegionId);
        return from != null && to != null && !from.isRopeRegion && to.isRopeRegion;
    }

    static boolean isTopStepOffExit(Rope rope, Point botPos, BotNavigationGraph.Edge edge) {
        if (rope == null || botPos == null || edge == null || edge.launchStepX != 0) {
            return false;
        }
        return edge.startPoint.y == rope.topY()
                && Math.abs(edge.endPoint.y - rope.topY()) <= BotMovementManager.cfg.JUMP_Y_THRESH * 2
                && botPos.y <= rope.topY() + BotMovementManager.cfg.JUMP_Y_THRESH * 2;
    }

    private static Rope findRopeForRegion(MapleMap map, BotNavigationGraph.Region region) {
        return BotNavigationGraphProvider.findRopeFromRegion(map, region);
    }

}
