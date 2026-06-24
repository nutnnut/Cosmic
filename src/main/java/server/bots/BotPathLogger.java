package server.bots;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import server.maps.MapleMap;

/**
 * Records per-tick navigation snapshots for a single bot and dumps them to a human-readable file.
 * Attach to BotEntry.pathLogger to start recording; call dumpToFile() to write the report.
 */
final class BotPathLogger {
    private static final int MAX_TICKS = 120; // 6s at 50ms tick
    private static final Path LOG_DIR = Path.of("logs", "bot-nav");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss");
    private static final DateTimeFormatter HEADER_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private record TickRecord(
            long elapsedMs,
            int botX, int botY,
            int ownerX, int ownerY,
            int goalX, int goalY,
            int steerX, int steerY,
            int botRegionId,
            String physState,
            String navEdge,
            String navDecision,
            String goalSource,
            String steerSource,
            String navTarget,
            String combat,
            boolean aiTick,
            boolean consumedTick,
            boolean stuck,
            boolean unstuck
    ) {}

    private record GraphSnapshot(
            BotMovementProfile requestedProfile,
            BotNavigationGraph graph,
            String source
    ) {}

    private final String botName;
    private final int mapId;
    private final long startMs = System.currentTimeMillis();
    private final Deque<TickRecord> history = new ArrayDeque<>(MAX_TICKS + 1);
    // Live map at dump time — lets pointRegionStr flag points that float off the walkable surface
    // their region= was resolved from (region= drops straight down to the floor, hiding mid-air).
    private MapleMap dumpMap;

    BotPathLogger(String botName, int mapId) {
        this.botName = botName;
        this.mapId = mapId;
    }

    void record(BotEntry entry,
                BotManager.TargetSnapshot targetSnapshot,
                int botRegionId,
                boolean consumedTick,
                boolean aiTick) {
        Point botPos = entry.bot.getPosition();
        Point ownerPos = targetSnapshot.rawOwnerPos();
        Point goalPos = targetSnapshot.primaryTargetPos();
        Point steerPos = targetSnapshot.steeringTargetPos(entry);
        long elapsed = System.currentTimeMillis() - startMs;

        TickRecord rec = new TickRecord(
                elapsed,
                botPos.x, botPos.y,
                ownerPos.x, ownerPos.y,
                goalPos.x, goalPos.y,
                steerPos.x, steerPos.y,
                botRegionId,
                physState(entry),
                navEdgeSummary(entry),
                entry.lastEdgeBlockReason != null
                        ? entry.lastNavDecision + "[" + entry.lastEdgeBlockReason + "]"
                        : entry.lastNavDecision,
                targetSnapshot.primaryTargetSource(),
                targetSnapshot.steeringTargetSource(entry),
                navTargetSummary(entry),
                combatToken(entry),
                aiTick,
                consumedTick,
                computeStuck(botPos.x, botPos.y),
                entry.unstuckCooldownMs > 0 && consumedTick
        );

        if (history.size() >= MAX_TICKS) {
            history.pollFirst();
        }
        history.addLast(rec);
    }

    /**
     * Writes the full report to disk.
     *
     * @param note optional free-text comment included in the report header (may be null)
     * @return absolute file path, or an error string if the write failed
     */
    String dumpToFile(BotEntry entry, BotManager.TargetSnapshot targetSnapshot, String note) {
        LocalDateTime now = LocalDateTime.now();
        String filename = "pathlog-" + botName + "-" + now.format(FILE_FMT) + ".txt";

        GraphSnapshot graphSnapshot = resolveGraphSnapshot(entry);
        BotNavigationGraph graph = graphSnapshot.graph();
        this.dumpMap = entry.bot.getMap();
        Point botPos = entry.bot.getPosition();
        Point goalTargetPos = targetSnapshot.primaryTargetPos();
        Point steeringTargetPos = targetSnapshot.steeringTargetPos(entry);
        int botRegionId = resolveCurrentRegionId(graph, entry, botPos);
        int rawOwnerRegionId = resolveTargetRegionId(graph, entry, targetSnapshot.rawOwnerPos());
        int followAnchorRegionId = resolveTargetRegionId(graph, entry, targetSnapshot.followAnchorPos());
        int followBaseRegionId = resolveTargetRegionId(graph, entry, targetSnapshot.followBasePos());
        int followTargetRegionId = resolveTargetRegionId(graph, entry, targetSnapshot.followTargetPos());
        int goalRegionId = resolveTargetRegionId(graph, entry, goalTargetPos);
        int steeringTargetRegionId = resolveTargetRegionId(graph, entry, steeringTargetPos);
        int moveTargetRegionId = targetSnapshot.moveTargetPos() == null
                ? -1
                : resolveTargetRegionId(graph, entry, targetSnapshot.moveTargetPos());
        int grindTargetRegionId = targetSnapshot.grindTargetPos() == null
                ? -1
                : resolveTargetRegionId(graph, entry, targetSnapshot.grindTargetPos());

        StringBuilder sb = new StringBuilder(4096);
        appendHeader(sb, now, note);
        appendCurrentState(sb, entry, targetSnapshot, botPos, botRegionId, rawOwnerRegionId,
                followAnchorRegionId, followBaseRegionId, followTargetRegionId, goalRegionId, steeringTargetPos,
                steeringTargetRegionId, moveTargetRegionId, grindTargetRegionId, graphSnapshot);
        appendCurrentPath(sb, entry, targetSnapshot, goalRegionId, rawOwnerRegionId, botRegionId, graph);
        appendHistory(sb);

        try {
            Files.createDirectories(LOG_DIR);
            Path file = LOG_DIR.resolve(filename);
            Files.writeString(file, sb.toString());
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            return "Failed to write log: " + e.getMessage();
        }
    }

    private static GraphSnapshot resolveGraphSnapshot(BotEntry entry) {
        MapleMap map = entry.bot.getMap();
        BotMovementProfile requestedProfile = entry.movementProfile == null
                ? BotMovementProfile.fromCharacter(entry.bot)
                : entry.movementProfile;
        BotNavigationGraph exact = BotNavigationGraphProvider.peekGraph(map, requestedProfile);
        if (exact != null) {
            return new GraphSnapshot(requestedProfile, exact, "exact");
        }

        BotNavigationGraph closest = BotNavigationGraphProvider.peekClosestGraph(map, requestedProfile);
        if (closest != null) {
            return new GraphSnapshot(requestedProfile, closest, "closest");
        }

        BotNavigationGraphProvider.warmGraphAsync(map, requestedProfile);
        return new GraphSnapshot(requestedProfile, null, "none/warming");
    }

    private static int resolveCurrentRegionId(BotNavigationGraph graph, BotEntry entry, Point point) {
        if (graph == null) {
            return -1;
        }
        return BotNavigationManager.resolveCurrentRegionId(graph, entry, entry.bot.getMap(), point);
    }

    private static int resolveTargetRegionId(BotNavigationGraph graph, BotEntry entry, Point point) {
        if (graph == null) {
            return -1;
        }
        return BotNavigationManager.resolveTargetRegionId(graph, entry, entry.bot.getMap(), point);
    }

    private void appendHeader(StringBuilder sb, LocalDateTime now, String note) {
        sb.append("=== Bot Path Log: ").append(botName).append(" ===\n");
        sb.append("Map: ").append(mapId).append("\n");
        sb.append("Captured: ").append(now.format(HEADER_FMT)).append("\n");
        sb.append("Ticks: ").append(history.size()).append(" recorded (max ").append(MAX_TICKS).append(")\n");
        if (note != null && !note.isBlank()) {
            sb.append("Note:  ").append(note).append("\n");
        }
        sb.append("\n");
    }

    private void appendCurrentState(StringBuilder sb,
                                    BotEntry entry,
                                    BotManager.TargetSnapshot targetSnapshot,
                                    Point botPos,
                                    int botRegionId,
                                    int rawOwnerRegionId,
                                    int followAnchorRegionId,
                                    int followBaseRegionId,
                                    int followTargetRegionId,
                                    int goalRegionId,
                                    Point steeringTargetPos,
                                    int steeringTargetRegionId,
                                    int moveTargetRegionId,
                                    int grindTargetRegionId,
                                    GraphSnapshot graphSnapshot) {
        sb.append("--- CURRENT STATE ---\n");
        sb.append("Bot:        ").append(pointRegionStr(botPos, botRegionId)).append("\n");
        sb.append("Owner raw:  ").append(pointRegionStr(targetSnapshot.rawOwnerPos(), rawOwnerRegionId)).append("\n");
        if (entry.following) {
            sb.append("Follow anchor:")
                    .append(" ").append(targetSnapshot.followAnchorName())
                    .append(" ").append(pointRegionStr(targetSnapshot.followAnchorPos(), followAnchorRegionId))
                    .append("\n");
        }
        appendMovementGraphState(sb, entry, graphSnapshot);
        sb.append("Formation:  ").append(targetSnapshot.formation().type().name().toLowerCase())
                .append(" px=").append(targetSnapshot.formation().px())
                .append(" snap=").append(targetSnapshot.formation().snapRange())
                .append(" offsetX=").append(entry.followOffsetX).append("\n");
        if (entry.following || !targetSnapshot.followBasePos().equals(targetSnapshot.rawOwnerPos())) {
            sb.append("Follow base:")
                    .append(" ").append(pointRegionStr(targetSnapshot.followBasePos(), followBaseRegionId))
                    .append("  [anchor + formation offset]\n");
        }
        if (entry.following) {
            sb.append("Follow tgt: ").append(pointRegionStr(targetSnapshot.followTargetPos(), followTargetRegionId))
                    .append("  [after snap/clamp]\n");
        }
        if (targetSnapshot.moveTargetPos() != null) {
            sb.append("Move target:").append(" ").append(pointRegionStr(targetSnapshot.moveTargetPos(), moveTargetRegionId));
            if (entry.moveTargetSource != null) {
                sb.append("  [set by: ").append(entry.moveTargetSource).append("]");
            }
            sb.append("\n");
        }
        if (targetSnapshot.grindTargetPos() != null) {
            sb.append("Grind tgt:  ").append(pointRegionStr(targetSnapshot.grindTargetPos(), grindTargetRegionId)).append("\n");
        }
        sb.append("Goal:       ").append(pointRegionStr(targetSnapshot.primaryTargetPos(), goalRegionId))
                .append("  [").append(targetSnapshot.primaryTargetSource()).append("]\n");
        sb.append("Steering:   ").append(pointRegionStr(steeringTargetPos, steeringTargetRegionId))
                .append("  [").append(targetSnapshot.steeringTargetSource(entry)).append("]\n");
        sb.append("Physics:    ").append(physState(entry)).append("\n");
        sb.append("Nav edge:   ").append(navEdgeSummary(entry)).append("\n");
        sb.append("Nav target: ").append(navTargetSummary(entry))
                .append("  targetRegion=").append(entry.navTargetRegionId).append("\n");
        sb.append("Last nav decision: ").append(entry.lastNavDecision);
        if ("no-ai".equals(entry.lastNavDecision)) {
            // "no-ai" just means runAiTick was false this tick — by itself it looks like a freeze.
            // Name the cause so a logging-out/break linger isn't mistaken for a stuck bot.
            sb.append(entry.loggingOut ? "  (AI step suppressed: logging out / lingering)"
                    : entry.breakUntilMs > System.currentTimeMillis() ? "  (AI step suppressed: on break)"
                    : "  (no AI step this tick: between AI-cadence ticks — normal)");
        }
        if (entry.lastEdgeBlockReason != null) {
            sb.append("  [blocked: ").append(entry.lastEdgeBlockReason).append("]");
        }
        sb.append("\n");
        sb.append("AI cadence:  every ").append(BotManager.cfg.AI_TICK_MS).append("ms")
                .append("  accum=").append(entry.aiTickAccumulatorMs).append("ms")
                .append("  lastTick=").append(entry.lastTickWasAi ? "AI" : "non-AI");
        if (entry.lastTickAtMs > 0) {
            sb.append("  at=").append(entry.lastTickAtMs);
        }
        sb.append("\n");
        sb.append("Mode:       ").append(entry.following ? "follow" : entry.grinding ? "grind" : "idle").append("\n");
        // Autopilot / ownership / admin-binding state - so a self-owned (@botme/@botparty) bot that
        // grind-wanders in a town, or one redirected by an admin debug-command, is diagnosable.
        String ownerDesc = entry.owner == null ? "none(offline)"
                : entry.owner == entry.bot ? "self(@botme/@botparty)"
                : entry.owner.getName();
        sb.append("Owner:      ").append(ownerDesc)
                .append("  followTargetId=").append(entry.followTargetId).append("\n");
        sb.append("Autopilot:  ").append(BotAutopilotManager.isActive(entry) ? "ACTIVE" : "off")
                .append(entry.autopilotParty ? " party" : "")
                .append("  destMap=").append(entry.autopilotMapId)
                .append(entry.autopilotFarmItemId != 0 ? "  farmItem=" + entry.autopilotFarmItemId : "")
                .append("  transitFollow=").append(entry.autopilotTransitFollow).append("\n");
        sb.append("Errands:    questMap=").append(entry.questErrandMapId)
                .append("  gachaMap=").append(entry.gachaErrandMapId)
                .append("  resupplyMap=").append(entry.autopilotErrandMapId)
                .append("  returningFromErrand=").append(entry.autopilotReturningFromErrand)
                .append("  shopVisitPending=").append(entry.shopVisitPending).append("\n");
        appendCohesionState(sb, entry);
        appendTravelState(sb, entry);
        if (entry.loggingOut || entry.breakUntilMs > 0L) {
            // Disambiguates an intentional idle (scheduled logout / in-session break) from the
            // inert-autopilot leak — all three look like "idle in town" without this.
            sb.append("Lifecycle:  loggingOut=").append(entry.loggingOut)
                    .append("  lingerUntilMs=").append(entry.logoutLingerUntilMs)
                    .append("  breakUntilMs=").append(entry.breakUntilMs).append("\n");
        }
        if (entry.debugCommanderId > 0) {
            sb.append("AdminBind:  commanderId=").append(entry.debugCommanderId)
                    .append("  untilMs=").append(entry.debugCommanderUntilMs).append("\n");
        }
        appendCombatState(sb, entry, botPos);
        boolean isStuck = entry.stuckMs >= 500 || computeStuck(botPos.x, botPos.y);
        sb.append("Stuck:      ").append(isStuck ? "YES (" + entry.stuckMs + "ms) ***" : "no").append("\n");
        sb.append("\n");
    }

    /**
     * Combat-decision diagnostics: why a grinding bot is/ isn't attacking its target. Surfaces the
     * attack-gate verdict captured each grind tick in BotManager.tickGrindMode — the prime suspect
     * when a bot "never attacks" is a retreat (ranged-spacing or touch-danger self-preservation)
     * holding the gate shut, often toward a cross-region vantage it can't reach.
     */
    private void appendCombatState(StringBuilder sb, BotEntry entry, Point botPos) {
        if (!entry.grinding || entry.bot == null) {
            return;
        }
        var bot = entry.bot;
        var wt = BotAttackExecutionProvider.getEquippedWeaponType(bot);
        sb.append("Combat:     weapon=").append(wt == null ? "none" : wt.name())
                .append("  hp=").append(bot.getHp()).append("/").append(bot.getCurrentMaxHp())
                .append("  mp=").append(bot.getMp()).append("/").append(bot.getCurrentMaxMp())
                .append("  noAmmo=").append(entry.noAmmo).append("\n");
        // Resolved attack skills (SSOT: BotCombatManager.rebuildSkillCacheIfNeeded). atkSkill=0 means NO
        // offensive skill is leveled -> the bot can only basic-swing its weapon (CLOSE route): it walks
        // onto the mob instead of casting from range, and a short-reach wand/claw can wedge just shy of it.
        sb.append("            atkSkill=").append(entry.attackSkillId)
                .append("  aoeSkill=").append(entry.aoeSkillId)
                .append("  allAtk=").append(entry.attackSkillIds)
                .append(entry.attackSkillId == 0 && entry.attackSkillIds.isEmpty()
                        ? "  (NONE -> basic weapon swing only)" : "")
                .append("\n");
        var mob = entry.grindTarget;
        if (mob == null || !mob.isAlive()) {
            sb.append("            grindTarget=<none — searching/wandering>\n");
        } else {
            Point mp = mob.getPosition();
            boolean touchDanger = server.bots.combat.BotDangerAssessment.isTouchDangerous(
                    bot, mob, BotCombatManager.cfg.TOUCH_HITS_TO_KILL);
            sb.append("            grindTarget=").append(mob.getId())
                    .append(" @(").append(mp.x).append(",").append(mp.y).append(")")
                    .append(" dx=").append(Math.abs(mp.x - botPos.x))
                    .append(" dy=").append(Math.abs(mp.y - botPos.y))
                    .append("  touchDanger=").append(touchDanger)
                    .append(" (hitsToKill=").append(BotCombatManager.cfg.TOUCH_HITS_TO_KILL).append(")\n");
            // FIRE PATH — recompute the plan live against this target and show which gate blocks the
            // shot. The SSOT for "standing next to a mob but won't attack": plan=NULL (no usable attack
            // this tick), inRange=false (the plan's hitbox misses — facing/reach/vertical), atkCooling,
            // or canUseNow=false. "SHOULD FIRE" here while the mob lives => the freeze is downstream
            // (movement/nav never parks the bot in this firing pose long enough to act).
            BotCombatManager.AttackPlan plan = BotCombatManager.planAttack(entry, bot, mob);
            boolean inRange = BotCombatManager.isTargetInAttackRange(plan, bot, mob);
            boolean canUseNow = BotCombatManager.canUseAttackPlanNow(entry, wt, plan);
            boolean cooling = entry.attackSkillId != 0 && bot.skillIsCooling(entry.attackSkillId);
            sb.append("            firePath: plan=")
                    .append(plan == null ? "NULL"
                            : plan.route + " skill=" + plan.skillId + " hitBox=" + (plan.hasHitBox() ? "yes" : "no"))
                    .append("  inRange=").append(inRange)
                    .append("  canUseNow=").append(canUseNow)
                    .append("  atkCooling=").append(cooling)
                    .append("  cdLeftMs=").append(Math.max(0, entry.attackCooldownMs))
                    .append(plan != null && inRange && canUseNow && entry.dbgAttackGateOpen
                            ? "  => SHOULD FIRE (freeze is downstream in movement/nav)" : "  => NO FIRE")
                    .append("\n");
        }
        if (entry.dbgCombatDecisionAtMs == 0L) {
            sb.append("            decision=<no grind-combat tick recorded yet>\n");
            return;
        }
        long now = System.currentTimeMillis();
        BotCombatManager.Config c = BotCombatManager.cfg;
        // VERDICT — what the bot decided this tick.
        sb.append("            verdict: attackGateOpen=").append(entry.dbgAttackGateOpen)
                .append("  retreatSpacing=").append(entry.dbgRangedSpacingRetreat)
                .append("  dangerRetreat=").append(entry.dbgProactiveDangerRetreat)
                .append("  crossRegion=").append(entry.dbgCrossRegionRetreat)
                .append("  decisionAgoMs=").append(now - entry.dbgCombatDecisionAtMs).append("\n");
        // INPUTS — the factors that produced it. Bands shown so "crowded/degen" is provable from dx/dy.
        sb.append("            inputs:  crowded=").append(entry.dbgRangedSpacingCrowded)
                .append(" (retreatBand dx<=").append(c.RANGED_RETREAT_THRESHOLD_X)
                .append(" dy<=").append(c.RANGED_DEGENERATE_RANGE_Y).append(")")
                .append("  inDegenBand=").append(entry.dbgInDegenBand)
                .append(" (dx<=").append(c.RANGED_DEGENERATE_RANGE_X)
                .append(" dy<=").append(c.RANGED_DEGENERATE_RANGE_Y).append(")")
                .append("  degenAttackDone=").append(entry.degenAttackDone)
                .append("  climbing=").append(entry.climbing).append(entry.climbing ? " (spacing suppressed)" : "")
                .append("\n");
        // ANTI-FREEZE — the shared give-up watchdogs (RetreatGiveUp). fightLeftMs>0 => fighting in place;
        // streakMs counts up to the cap, at which point it gives up and opens a fight window.
        sb.append("            antifreeze: spacing[gaveUp=").append(entry.dbgRangedSpacingGaveUp)
                .append(" streakMs=").append(entry.spacingGiveUp.streakAgeMs(now))
                .append("/").append(BotManager.MAX_RANGED_SPACING_RETREAT_MS)
                .append(" fightLeftMs=").append(entry.spacingGiveUp.fightWindowLeftMs(now)).append("]")
                .append("  danger[streakMs=").append(entry.dangerGiveUp.streakAgeMs(now))
                .append("/").append(BotManager.MAX_DANGER_RETREAT_MS)
                .append(" fightLeftMs=").append(entry.dangerGiveUp.fightWindowLeftMs(now))
                .append(" holdLeftMs=").append(Math.max(0L, entry.dangerRetreatUntilMs - now)).append("]")
                .append("\n");
        // POSITION — committed retreat target state.
        sb.append("            position: retreatHoldPos=").append(entry.retreatHoldPos == null ? "none"
                        : "(" + entry.retreatHoldPos.x + "," + entry.retreatHoldPos.y + ")")
                .append("  retreatHoldLeftMs=").append(Math.max(0L, entry.retreatHoldUntilMs - now))
                .append("  breakoutDir=").append(entry.breakoutDirection)
                .append("\n");
    }

    /** Compact per-tick combat verdict for the tick history (mirror of appendCombatState flags). */
    static String combatToken(BotEntry entry) {
        if (entry.dbgCombatDecisionAtMs == 0L
                || System.currentTimeMillis() - entry.dbgCombatDecisionAtMs > 2000L) {
            return "-"; // no recent grind-combat decision (no target / not grinding)
        }
        String base = entry.dbgProactiveDangerRetreat ? "RETdgr"
                : entry.dbgRangedSpacingRetreat ? "RETrng"
                : entry.dbgAttackGateOpen ? "ATK"
                : "hold";
        String flags = (entry.dbgInDegenBand ? "d" : "") + (entry.dbgCrossRegionRetreat ? "X" : "")
                + (entry.dbgRangedSpacingGaveUp ? "g" : "");
        return flags.isEmpty() ? base : base + "/" + flags;
    }

    /**
     * Party-cohesion diagnostics: why an autopilot-party bot is holding instead of advancing.
     * Read-only mirror of {@link BotAutopilotManager#waitingForStragglers} — it recomputes each
     * member's hop/px gap from the leader's vantage WITHOUT mutating the cached verdict or sending
     * chat, and flags the member(s) that trip the hold. The cohesion leader is the first member
     * not off on a resupply errand; only that leader anchors at a portal and waits.
     */
    private void appendCohesionState(StringBuilder sb, BotEntry entry) {
        if (!BotAutopilotManager.isActive(entry) || !entry.autopilotParty) {
            return;
        }
        List<BotEntry> members;
        try {
            members = BotAutopilotManager.partyMembers.members(entry);
        } catch (RuntimeException e) {
            sb.append("Cohesion:   <members lookup failed: ").append(e).append(">\n");
            return;
        }
        BotEntry leader = BotAutopilotManager.effectiveCohesionLeader(members);
        boolean isLeader = leader == entry;
        String leaderName = leader == null ? "none(all resupplying)"
                : leader.bot != null ? leader.bot.getName() : "?";
        sb.append("Cohesion:   leader=").append(leaderName)
                .append(isLeader ? " (THIS BOT)" : "")
                .append("  members=").append(members.size())
                .append("  waitingForStragglers=").append(entry.autopilotWaitingForStragglers)
                .append("\n");
        sb.append("Cohesion cfg: waitHops=").append(BotManager.cfg.STRAGGLER_WAIT_HOPS)
                .append("  sameMapPx=").append(BotManager.cfg.SAME_MAP_STRAGGLER_PX)
                .append("  resumePx=").append(BotManager.cfg.SAME_MAP_STRAGGLER_RESUME_PX)
                .append("  nextCheckInMs=")
                .append(Math.max(0L, entry.autopilotNextStragglerCheckAtMs - System.currentTimeMillis()))
                .append("\n");
        // The verdict the LAST real recompute reached (<=3s stale). If this says a member tripped the
        // hold but the live per-member mirror below shows everyone present, the group is oscillating
        // (members briefly far/off-map at the check instant) -- not a stuck flag.
        sb.append("Last straggler verdict: ").append(entry.autopilotStragglerReason != null
                ? "WAIT - " + entry.autopilotStragglerReason
                : "no straggler (would release)").append("\n");
        if (entry.autopilotWaitAnchor != null) {
            sb.append("Wait anchor: (").append(entry.autopilotWaitAnchor.x).append(",")
                    .append(entry.autopilotWaitAnchor.y).append(")  map=")
                    .append(entry.autopilotWaitAnchorMapId).append("  [holding at next-hop portal]\n");
        }
        // The hold is computed from the LEADER's position/map; show the breakdown from there even
        // when a follower captured the log. While already waiting, the resume band is the tighter
        // hysteresis value (the same value waitingForStragglers uses on a holding tick).
        Point leaderPos = leader != null && leader.bot != null ? leader.bot.getPosition() : null;
        int leaderMap = leader != null && leader.bot != null ? leader.bot.getMapId() : -1;
        // Mirror waitingForStragglers: a member at/closer to the grind dest than the leader is
        // AHEAD, not behind, so it never trips the hops hold. Hoisted (member-independent).
        int destMapId = entry.autopilotMapId;
        int leaderHopsToDest = leaderMap < 0 ? Integer.MAX_VALUE
                : BotAutopilotManager.hopDistance.hops(leaderMap, destMapId);
        int band = entry.autopilotWaitingForStragglers
                ? BotManager.cfg.SAME_MAP_STRAGGLER_RESUME_PX
                : BotManager.cfg.SAME_MAP_STRAGGLER_PX;
        for (BotEntry m : members) {
            if (m.bot == null) {
                sb.append("  - <null bot> \n");
                continue;
            }
            String name = m.bot.getName();
            if (m == leader) {
                sb.append("  - ").append(name).append("  [leader, map=").append(leaderMap).append("]\n");
                continue;
            }
            if (m.autopilotErrandMapId != -1) {
                sb.append("  - ").append(name).append("  resupplying(errandMap=")
                        .append(m.autopilotErrandMapId).append(") - excluded\n");
                continue;
            }
            if (m.bot.getMap() == null) {
                sb.append("  - ").append(name).append("  <no map> - skipped\n");
                continue;
            }
            int memberMap = m.bot.getMapId();
            String detail;
            if (memberMap != leaderMap) {
                int hops = -1;
                boolean ahead = false;
                try {
                    hops = BotAutopilotManager.hopDistance.hops(memberMap, leaderMap);
                    ahead = BotAutopilotManager.aheadOfLeaderTowardDest(memberMap, destMapId, leaderHopsToDest);
                } catch (RuntimeException ignored) {
                    // best-effort; an unmapped pair just shows hops=-1
                }
                boolean straggler = !ahead && (hops < 0 || hops > BotManager.cfg.STRAGGLER_WAIT_HOPS);
                detail = "map=" + memberMap + " hops=" + hops
                        + (ahead ? " ahead(toDest<leader)" : "")
                        + (straggler ? "  *STRAGGLER(hops)*" : "");
            } else {
                Point mp = m.bot.getPosition();
                if (leaderPos == null || mp == null) {
                    detail = "sameMap gap=? (band=" + band + ")  *STRAGGLER(px)*";
                } else {
                    // Mirror waitingForStragglers EXACTLY: present if near by body OR formation slot,
                    // straggler only if far by BOTH. Show the split so the verdict is provable from the log.
                    int bodyGap = Math.abs(leaderPos.x - mp.x) + Math.abs(leaderPos.y - mp.y);
                    int expectedX = leaderPos.x + m.followOffsetX;
                    int slotGap = Math.abs(mp.x - expectedX) + Math.abs(mp.y - leaderPos.y);
                    int gap = Math.min(bodyGap, slotGap);
                    boolean straggler = gap > band;
                    detail = "sameMap gap=" + gap + "px (body=" + bodyGap + " slot=" + slotGap
                            + " offsetX=" + m.followOffsetX + ") (band=" + band + ")"
                            + (straggler ? "  *STRAGGLER(px)*" : "");
                }
            }
            sb.append("  - ").append(name).append("  ").append(detail).append("\n");
        }
    }

    /**
     * Travel diagnostics for an autopilot bot off its destination: the world-graph route it is
     * trying to walk (current map -> destination/resupply map), the next-hop portal it would enter,
     * and the live follow-travel hop state incl. the give-up/random-portal-fallback window. Surfaces
     * a bot "not taking the next portal" -- a hop it can't reach, or a give-up window bouncing it
     * back to a prior map (which then reads as a perpetual straggler-wait at the lower map).
     */
    private void appendTravelState(StringBuilder sb, BotEntry entry) {
        if (!BotAutopilotManager.isActive(entry)) {
            return;
        }
        client.Character bot = entry.bot;
        if (bot == null || bot.getMap() == null) {
            return;
        }
        int dest = entry.autopilotErrandMapId != -1 ? entry.autopilotErrandMapId : entry.autopilotMapId;
        int here = bot.getMapId();
        if (dest <= 0 || dest == here) {
            return; // on the destination map: travel isn't driving this tick
        }
        sb.append("Travel:     ").append(here).append(" -> ").append(dest);
        try {
            List<Integer> route = BotWorldGraph.route(here, dest, BotAutopilotManager.MAX_TRAVEL_HOPS);
            sb.append("  route=").append(route == null ? "<none/unreachable>" : route.toString());
        } catch (RuntimeException e) {
            sb.append("  route=<err:").append(e).append(">");
        }
        sb.append("\n");
        // Meso gates taxi/ferry hops, so surface it next to the route. Also call out the active errand —
        // a job/quest/gacha errand drives travel to ITS target, not the grind dest shown above, so the two
        // legitimately differ (the common "why is it heading the wrong way" confusion).
        sb.append("            meso=").append(bot.getMeso());
        if (entry.jobErrandMapId != -1) {
            sb.append("  ACTIVE ERRAND=job->").append(entry.jobErrandMapId)
                    .append(" npc=").append(entry.jobErrandNpcId).append(" (drives travel, not the grind dest)");
        } else if (entry.questErrandMapId != -1) {
            sb.append("  ACTIVE ERRAND=quest->").append(entry.questErrandMapId);
        } else if (entry.gachaErrandMapId != -1) {
            sb.append("  ACTIVE ERRAND=gacha->").append(entry.gachaErrandMapId);
        }
        sb.append("\n");
        try {
            java.awt.Point portal = BotTravelManager.nextHopPortalPosition(
                    entry, bot, dest, BotAutopilotManager.MAX_TRAVEL_HOPS);
            sb.append("            nextHopPortal=").append(portal == null
                    ? "<none: scroll/taxi/ferry leg, or no walkable portal>"
                    : "(" + portal.x + "," + portal.y + ")").append("\n");
        } catch (RuntimeException e) {
            sb.append("            nextHopPortal=<err:").append(e).append(">\n");
        }
        long now = System.currentTimeMillis();
        if (entry.followTravelTargetMapId != -1) {
            sb.append("            hop: target=").append(entry.followTravelTargetMapId)
                    .append(" nextHop=").append(entry.followTravelNextHopMapId)
                    .append(" portalId=").append(entry.followTravelPortalId)
                    .append(" fromMap=").append(entry.followTravelFromMapId);
            if (entry.followTravelTaxiNpcId != 0) {
                sb.append(" taxiNpc=").append(entry.followTravelTaxiNpcId);
                // Live cab geometry: a taxi "deadline" almost always means the bot is near the cab but
                // never goes grounded-in-range (cab on a foothold it can't stand on). distToCab vs the
                // 500px hail radius + grounded state shows exactly that; deadlineInMs<0 => hailing from here.
                Point cab = entry.followTravelTaxiPos;
                Point bp = bot.getPosition();
                if (cab != null && bp != null) {
                    int dist = Math.abs(bp.x - cab.x) + Math.abs(bp.y - cab.y);
                    sb.append(" cab=(").append(cab.x).append(",").append(cab.y).append(")")
                            .append(" distToCab=").append(dist).append(dist <= 500 ? "" : " (>500 hail radius)")
                            .append(" grounded=").append(!entry.inAir && !entry.climbing)
                            .append(entry.inAir ? " inAir" : "").append(entry.climbing ? " climbing" : "");
                }
            }
            if (entry.followTravelFerry) {
                sb.append(" ferry: ").append(BotFerryManager.describeLeg(entry, bot));
            }
            if (entry.followTravelEnteredAtMs > 0) {
                sb.append(" enteredAgoMs=").append(now - entry.followTravelEnteredAtMs);
            }
            sb.append(" deadlineInMs=").append(entry.followTravelDeadlineMs - now);
            if (entry.followTravelBestDist != Integer.MAX_VALUE) {
                sb.append(" bestDist=").append(entry.followTravelBestDist); // closest to portal so far; resets deadline on progress
            }
            sb.append("\n");
        } else {
            sb.append("            hop: <inactive>\n");
        }
        if (now < entry.followTravelGiveUpUntilMs) {
            sb.append("            give-up window: ").append(entry.followTravelGiveUpUntilMs - now)
                    .append("ms left  reason=").append(entry.followTravelGiveUpReason == null ? "?" : entry.followTravelGiveUpReason)
                    .append("  hop=[").append(entry.followTravelGiveUpHop).append("]")
                    .append(" failedMap=").append(entry.followTravelGiveUpTargetMapId)
                    .append("  (travel paused; retries the real hop when it lapses)\n");
        } else if (entry.followTravelGiveUpReason != null) {
            // Not currently paused, but show the LAST give-up so an oscillating "plan -> fail -> replan"
            // loop (the bot that never makes a hop) is visible even between windows.
            sb.append("            last give-up: reason=").append(entry.followTravelGiveUpReason)
                    .append("  hop=[").append(entry.followTravelGiveUpHop).append("]")
                    .append(" failedMap=").append(entry.followTravelGiveUpTargetMapId)
                    .append(" agoMs=").append(entry.followTravelGiveUpAtMs > 0 ? (now - entry.followTravelGiveUpAtMs) : -1)
                    .append("\n");
        }
    }

    private void appendMovementGraphState(StringBuilder sb, BotEntry entry, GraphSnapshot graphSnapshot) {
        BotMovementProfile requested = graphSnapshot.requestedProfile();
        sb.append("Movement:   speed=").append(requested.totalSpeedStat()).append("%")
                .append(" jump=").append(requested.totalJumpStat()).append("%")
                .append(" rawSpeed=").append(entry.bot.getTotalMoveSpeedStat()).append("%")
                .append(" rawJump=").append(entry.bot.getTotalJumpStat()).append("%")
                .append(" walkStep=").append(BotPhysicsEngine.walkStep(entry.bot.getMap(), requested))
                .append(" walkVel=").append(String.format("%.1f", requested.walkVelocityPxs()))
                .append(" jumpForce=").append(String.format("%.1f", requested.jumpSpeedPxs()))
                .append("\n");
        BotNavigationGraph graph = graphSnapshot.graph();
        if (graph == null) {
            sb.append("Graph:      none/warming requestedSpeed=").append(requested.totalSpeedStat()).append("%")
                    .append(" requestedJump=").append(requested.totalJumpStat()).append("%\n");
        } else {
            BotMovementProfile graphProfile = graph.movementProfile;
            sb.append("Graph:      ").append(graphSnapshot.source())
                    .append(" version=").append(graph.version)
                    .append(" speed=").append(graphProfile.totalSpeedStat()).append("%")
                    .append(" jump=").append(graphProfile.totalJumpStat()).append("%");
            if (!graphProfile.equals(requested)) {
                sb.append(" requestedSpeed=").append(requested.totalSpeedStat()).append("%")
                        .append(" requestedJump=").append(requested.totalJumpStat()).append("%");
            }
            sb.append("\n");
        }
        sb.append("Fallback:   heuristic=").append(entry.graphWarmupFallback ? "yes" : "no")
                .append(" closestGraph=").append("closest".equals(graphSnapshot.source()) ? "yes" : "no")
                .append("\n");
    }

    private void appendCurrentPath(StringBuilder sb,
                                   BotEntry entry,
                                   BotManager.TargetSnapshot targetSnapshot,
                                   int goalRegionId,
                                   int rawOwnerRegionId,
                                   int botRegionId,
                                   BotNavigationGraph graph) {
        sb.append("--- CURRENT A* PATH ---\n");
        sb.append("Goal basis:  ").append(targetSnapshot.primaryTargetSource())
                .append(" ").append(pointRegionStr(targetSnapshot.primaryTargetPos(), goalRegionId)).append("\n");
        appendPath(sb, entry, targetSnapshot.primaryTargetPos(), goalRegionId, botRegionId, graph);
        if (!targetSnapshot.rawOwnerPos().equals(targetSnapshot.primaryTargetPos())) {
            sb.append("Raw owner:   ").append(pointRegionStr(targetSnapshot.rawOwnerPos(), rawOwnerRegionId)).append("\n");
            appendPath(sb, entry, targetSnapshot.rawOwnerPos(), rawOwnerRegionId, botRegionId, graph);
        }
        sb.append("\n");
    }

    private void appendPath(StringBuilder sb,
                            BotEntry entry,
                            Point targetPos,
                            int targetRegionId,
                            int botRegionId,
                            BotNavigationGraph graph) {
        if (graph == null) {
            sb.append("  graph unavailable - exact graph warming and no closest cached graph\n");
            return;
        }
        if (botRegionId < 0 || targetRegionId < 0) {
            sb.append("  unknown region  botRegion=").append(botRegionId)
                    .append(" targetRegion=").append(targetRegionId).append("\n");
        } else if (botRegionId == targetRegionId) {
            // Same region does NOT mean "direct walk reaches it" — a region can be two platforms
            // split by a gap, where A* returns an intra-region detour (portal/jump loop out and
            // back). Run the search and show that loop instead of claiming "no path"; an empty
            // result means the straight-line walk genuinely wins.
            List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(
                    graph, entry.bot, botRegionId, targetRegionId, targetPos);
            if (path.isEmpty()) {
                sb.append("  same region - direct walk (no detour)");
                String surface = surfaceFlag(targetPos);
                if (!surface.isEmpty()) {
                    sb.append("  <-- target").append(surface)
                            .append("; same-region straight-line steer cannot reach it");
                }
                sb.append("\n");
            } else {
                sb.append("  same region - intra-region detour (platform split by gap):\n");
                for (int i = 0; i < path.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(edgeStr(path.get(i))).append("\n");
                }
            }
        } else {
            List<BotNavigationGraph.Edge> path = BotNavigationManager.findPath(
                    graph, entry.bot, botRegionId, targetRegionId, targetPos);
            if (path.isEmpty()) {
                sb.append("  no path found\n");
            } else {
                for (int i = 0; i < path.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(edgeStr(path.get(i))).append("\n");
                }
            }
        }
    }

    private void appendHistory(StringBuilder sb) {
        sb.append("--- TICK HISTORY (oldest first, ").append(history.size()).append(" ticks) ---\n");
        for (TickRecord rec : history) {
            String goalSuffix = String.format(" goal=(%4d,%4d)[%s]", rec.goalX, rec.goalY, rec.goalSource);
            String steerSuffix = rec.goalX == rec.steerX
                    && rec.goalY == rec.steerY
                    && rec.goalSource.equals(rec.steerSource)
                    ? ""
                    : String.format(" steer=(%4d,%4d)[%s]", rec.steerX, rec.steerY, rec.steerSource);
            sb.append(String.format("[+%5dms] ai=%s bot=(%4d,%4d) own=(%4d,%4d)%s%s r=%-3d %-18s nav=%-8s edge=%-46s tgt=%s cmb=%-7s%s%s%n",
                    rec.elapsedMs,
                    rec.aiTick ? "Y" : "N",
                    rec.botX, rec.botY,
                    rec.ownerX, rec.ownerY,
                    goalSuffix,
                    steerSuffix,
                    rec.botRegionId,
                    rec.physState,
                    rec.navDecision,
                    rec.navEdge,
                    rec.navTarget,
                    rec.combat,
                    rec.consumedTick ? " [exec]" : "",
                    rec.unstuck ? " *** UNSTUCK ***" : rec.stuck ? " *** STUCK ***" : ""));
        }
    }

    private boolean computeStuck(int x, int y) {
        if (history.size() < 5) {
            return false;
        }
        return history.stream()
                .skip(history.size() - 5)
                .allMatch(r -> Math.abs(r.botX - x) <= 8 && Math.abs(r.botY - y) <= 8);
    }

    static String physState(BotEntry entry) {
        if (entry.climbing) {
            if (entry.climbRope != null) {
                return "ROPE(x=" + entry.climbRope.x()
                        + " top=" + entry.climbRope.topY()
                        + " bot=" + entry.climbRope.bottomY() + ")";
            }
            return "ROPE(? climbRope=null)";
        }
        if (entry.inAir) {
            return "AIR(velY=" + String.format("%.1f", entry.velY)
                    + " airVelX=" + entry.airVelX
                    + (entry.climbUpIntent ? " climbIntent" : "") + ")";
        }
        return "GND"
                + (entry.downJumpPending ? "(downJump)" : "")
                + (entry.crouching ? "(crouch)" : "");
    }

    static String navEdgeSummary(BotEntry entry) {
        BotNavigationGraph.Edge e = entry.navEdge;
        if (e == null) {
            return "none";
        }
        return e.type + " r" + e.fromRegionId + "->r" + e.toRegionId
                + " (" + e.startPoint.x + "," + e.startPoint.y
                + ")->(" + e.endPoint.x + "," + e.endPoint.y + ")"
                + launchWindowSummary(e)
                + (e.launchStepX != 0 ? " stepX=" + e.launchStepX : "");
    }

    private static String navTargetSummary(BotEntry entry) {
        if (entry.navTargetPos == null) {
            return "none";
        }
        return "(" + entry.navTargetPos.x + "," + entry.navTargetPos.y + ")"
                + (entry.navPreciseTarget ? "[precise]" : "");
    }

    private static String edgeStr(BotNavigationGraph.Edge e) {
        return e.type + " r" + e.fromRegionId + "->r" + e.toRegionId
                + "  (" + e.startPoint.x + "," + e.startPoint.y
                + ")->(" + e.endPoint.x + "," + e.endPoint.y + ")"
                + launchWindowSummary(e)
                + (e.launchStepX != 0 ? "  stepX=" + e.launchStepX : "")
                + "  cost=" + e.cost;
    }

    private static String launchWindowSummary(BotNavigationGraph.Edge edge) {
        if ((edge.type != BotNavigationGraph.EdgeType.JUMP
                && !(edge.type == BotNavigationGraph.EdgeType.DROP && edge.launchStepX == 0))
                || edge.launchMinX == edge.launchMaxX) {
            return "";
        }
        return " window=[" + edge.launchMinX + "," + edge.launchMaxX + "]";
    }

    private String pointRegionStr(Point point, int regionId) {
        return "(" + point.x + ", " + point.y + ")  region=" + regionId + surfaceFlag(point);
    }

    /**
     * Flags a point that does NOT sit on the walkable surface its region= was resolved from.
     * region= comes from findGroundFoothold, which drops straight down to the floor under the
     * point — so an airborne target (a follow/move target left mid-jump, a formation slot with
     * no ground, an upper-platform point) reads as the bot's OWN region and tricks nav into a
     * "same-region" straight-line steer it can never satisfy (the arrival check needs both x and
     * y). The gap is the vertical distance down to that floor; >tolerance ⇒ not standable here.
     */
    private String surfaceFlag(Point point) {
        if (dumpMap == null || point == null) {
            return "";
        }
        Point ground = BotPhysicsEngine.findGroundPoint(dumpMap, point);
        if (ground == null) {
            return "  *OFF-GRAPH: no ground below*";
        }
        int gap = ground.y - point.y; // y grows downward: >0 ⇒ point floats above the floor
        int tol = BotMovementManager.cfg.JUMP_Y_THRESH;
        if (gap > tol) {
            // A point on a rope/ladder is SUPPOSED to float above the floor — you stand by clinging to
            // the rope, not on a foothold. Don't cry "not standable" there: it's a legit climb target.
            if (BotPhysicsEngine.climbableAtPoint(dumpMap, point) != null) {
                return "  *ON-ROPE +" + gap + "px above floor(y=" + ground.y + ") — standable by climbing*";
            }
            return "  *MIDAIR +" + gap + "px above floor(y=" + ground.y + ") — not standable here*";
        }
        if (gap < -tol) {
            return "  *BELOW-FLOOR " + (-gap) + "px*";
        }
        return "";
    }
}
