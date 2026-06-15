package server.bots;

import client.Character;
import net.packet.Packet;
import server.maps.Foothold;
import tools.PacketCreator;

import java.awt.*;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

enum BotFidgetMode {
    NONE,
    WAIT,
    JUMP,
    DIAGONAL_JUMP,
    PRONE,
    SPAM_PRONE,
    SPAM_SIDEWAYS
}

enum BotFidgetTrigger {
    NONE,
    AUTO_FOLLOW,
    IDLE,
    SOCIAL
}

final class BotFidgetManager {
    private static final int SPAM_BASE_DELAY_MIN_MS = 100;
    private static final int SPAM_BASE_DELAY_MAX_MS = 250;
    private static final int SPAM_JITTER_MS = 50;

    private BotFidgetManager() {
    }

    static boolean tryHandleTick(BotEntry entry, Point targetPos, boolean runAiTick) {
        if (entry == null || entry.bot == null || targetPos == null) {
            clear(entry);
            return false;
        }

        Point botPos = entry.bot.getPosition();
        long now = System.currentTimeMillis();
        if (entry.fidgetMode != BotFidgetMode.NONE) {
            if (!shouldKeepRunning(entry, botPos, targetPos, now)) {
                finishFidget(entry, botPos);
                return false;
            }
            return handleActiveTick(entry, botPos, targetPos, now);
        }

        if (!isEligible(entry, botPos, targetPos)) {
            return false;
        }

        if (runAiTick) {
            maybeRollIdleFidget(entry, botPos, targetPos, now);
        }
        if (entry.fidgetMode == BotFidgetMode.NONE) {
            maybeStartSpeedMismatchFidget(entry, botPos, targetPos, now, runAiTick);
        }
        if (entry.fidgetMode == BotFidgetMode.NONE) {
            return false;
        }

        return handleActiveTick(entry, botPos, targetPos, now);
    }

    static void clear(BotEntry entry) {
        if (entry == null) {
            return;
        }

        entry.fidgetMode = BotFidgetMode.NONE;
        entry.fidgetTrigger = BotFidgetTrigger.NONE;
        entry.fidgetUntilMs = 0L;
        entry.nextFidgetActionAtMs = 0L;
        entry.fidgetAirSteerDir = 0;
        entry.fidgetJumpDir = 0;
        entry.fidgetMoveDir = 0;
        entry.fidgetSpamAirSteer = false;
        entry.fidgetActionBaseDelayMs = 0;
        entry.nextFidgetJumpAtMs = 0L;
        entry.fidgetOriginPos = null;
        entry.nextFidgetVisualAtMs = 0L;
    }

    private static void finishFidget(BotEntry entry, Point botPos) {
        if (entry == null) {
            return;
        }

        BotFidgetTrigger trigger = entry.fidgetTrigger;
        Point origin = entry.fidgetOriginPos == null ? null : new Point(entry.fidgetOriginPos);
        clear(entry);
        if (shouldReturnToOrigin(trigger, origin, botPos)) {
            entry.moveTarget = origin;
            entry.moveTargetPrecise = true;
            entry.moveTargetSource = "fidget-return-origin";
            BotMovementManager.clearNavigationState(entry);
        }
    }

    private static boolean shouldReturnToOrigin(BotFidgetTrigger trigger, Point origin, Point botPos) {
        if (trigger != BotFidgetTrigger.IDLE && trigger != BotFidgetTrigger.SOCIAL) {
            return false;
        }
        if (origin == null || botPos == null) {
            return false;
        }
        return Math.abs(botPos.x - origin.x) > 8 || Math.abs(botPos.y - origin.y) > 8;
    }

    static void startFidget(BotEntry entry, BotFidgetMode mode, long now, int durationMs) {
        startFidget(entry, mode, now, durationMs, BotFidgetTrigger.AUTO_FOLLOW);
    }

    static void startFidget(BotEntry entry,
                           BotFidgetMode mode,
                           long now,
                           int durationMs,
                           BotFidgetTrigger trigger) {
        if (entry == null || mode == null || mode == BotFidgetMode.NONE) {
            return;
        }

        entry.fidgetMode = mode;
        entry.fidgetTrigger = trigger == null ? BotFidgetTrigger.AUTO_FOLLOW : trigger;
        entry.fidgetUntilMs = now + Math.max(2000, durationMs);
        entry.nextFidgetActionAtMs = now;
        entry.fidgetAirSteerDir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        entry.fidgetJumpDir = entry.fidgetAirSteerDir == 0 ? 1 : entry.fidgetAirSteerDir;
        entry.fidgetMoveDir = entry.fidgetAirSteerDir;
        entry.fidgetSpamAirSteer = isJumpFidget(mode) && ThreadLocalRandom.current().nextInt(100) < 35;
        entry.fidgetActionBaseDelayMs = randomActionBaseDelayMs(mode, entry.fidgetSpamAirSteer);
        entry.nextFidgetJumpAtMs = now;
        entry.fidgetOriginPos = entry.bot == null ? null : new Point(entry.bot.getPosition());
        entry.nextFidgetVisualAtMs = now + BotManager.randMs(500, 1200);
        entry.nextFidgetAtMs = now + BotManager.randMs(4000, 8000);
    }

    static boolean maybeStartGreetingFidget(BotEntry entry, int roll) {
        if (roll >= 50) {
            return false;
        }
        return maybeStartSocialFidget(entry);
    }

    static boolean maybeStartSocialFidget(BotEntry entry) {
        if (entry == null
                || entry.fidgetMode != BotFidgetMode.NONE
                || !entry.following
                || BotChatManager.isOwnerIdle(entry)
                || entry.grinding
                || entry.moveTarget != null
                || entry.navEdge != null
                || entry.navPreciseTarget
                || entry.graphWarmupFallback
                || entry.inAir
                || entry.climbing) {
            return false;
        }

        startRandomFidget(entry, System.currentTimeMillis(), (int) BotManager.randMs(2000, 5000), BotFidgetTrigger.SOCIAL);
        return true;
    }

    private static boolean isEligible(BotEntry entry, Point botPos, Point targetPos) {
        return isEligible(entry, botPos, targetPos, false);
    }

    private static boolean isEligible(BotEntry entry,
                                      Point botPos,
                                      Point targetPos,
                                      boolean allowAirborneJumpFidget) {
        boolean airborneJumpFidget = entry.inAir && allowAirborneJumpFidget && isJumpFidget(entry.fidgetMode);
        return entry.following
                && !BotChatManager.isOwnerIdle(entry)
                && !entry.grinding
                && entry.moveTarget == null
                && entry.navEdge == null
                && !entry.navPreciseTarget
                && !entry.graphWarmupFallback
                && !entry.climbing
                && (!entry.inAir || airborneJumpFidget)
                && !entry.downJumpPending
                && (airborneJumpFidget || Math.abs(targetPos.y - botPos.y) <= BotMovementManager.cfg.JUMP_Y_THRESH * 2);
    }

    private static boolean shouldKeepRunning(BotEntry entry, Point botPos, Point targetPos, long now) {
        if (!isEligible(entry, botPos, targetPos, true) || now >= entry.fidgetUntilMs) {
            return false;
        }
        int walkStep = BotPhysicsEngine.walkStep(entry.bot.getMap(), entry.movementProfile);
        int absDx = Math.abs(targetPos.x - botPos.x);
        return absDx <= BotMovementManager.cfg.FOLLOW_DIST + walkStep * 3;
    }

    private static boolean isJumpFidget(BotFidgetMode mode) {
        return mode == BotFidgetMode.JUMP || mode == BotFidgetMode.DIAGONAL_JUMP;
    }

    private static void maybeRollIdleFidget(BotEntry entry, Point botPos, Point targetPos, long now) {
        if (!entry.lastTickWasAi || !isOwnerMostlyIdle(entry)) {
            return;
        }
        if (Math.abs(targetPos.x - botPos.x) > BotMovementManager.cfg.FOLLOW_DIST) {
            return;
        }
        if (entry.nextIdleFidgetRollAtMs == 0L) {
            entry.nextIdleFidgetRollAtMs = now + BotManager.randMs(30_000, 60_000);
            return;
        }
        if (now < entry.nextIdleFidgetRollAtMs) {
            return;
        }

        entry.nextIdleFidgetRollAtMs = now + BotManager.randMs(30_000, 60_000);
        if (ThreadLocalRandom.current().nextInt(100) >= 20) {
            return;
        }

        startRandomFidget(entry, now, (int) BotManager.randMs(2000, 10_000), BotFidgetTrigger.IDLE);
    }

    private static void maybeStartSpeedMismatchFidget(BotEntry entry, Point botPos, Point targetPos, long now, boolean runAiTick) {
        if (!runAiTick || now < entry.nextFidgetAtMs) {
            return;
        }
        if (!shouldStartSpeedMismatchFidget(entry, botPos, targetPos)) {
            return;
        }

        entry.nextFidgetAtMs = now + BotManager.randMs(1500, 3000);
        if (ThreadLocalRandom.current().nextInt(100) >= 35) {
            return;
        }

        startRandomFidget(entry, now, (int) BotManager.randMs(2000, 4500), BotFidgetTrigger.AUTO_FOLLOW);
    }

    static boolean shouldStartSpeedMismatchFidget(BotEntry entry, Point botPos, Point targetPos) {
        if (entry == null || entry.bot == null || botPos == null || targetPos == null) {
            return false;
        }
        if (isOwnerMostlyIdle(entry)) {
            return false;
        }

        int walkStep = BotPhysicsEngine.walkStep(entry.bot.getMap(), entry.movementProfile);
        int absDx = Math.abs(targetPos.x - botPos.x);
        int ownerStep = Math.max(Math.abs(entry.observedOwnerStepX), Math.abs(entry.observedOwnerStepY));
        return absDx <= BotMovementManager.cfg.FOLLOW_DIST + walkStep
                && ownerStep < walkStep;
    }

    private static boolean isOwnerMostlyIdle(BotEntry entry) {
        return Math.abs(entry.observedOwnerStepX) <= 1 && Math.abs(entry.observedOwnerStepY) <= 1;
    }

    static void startRandomFidget(BotEntry entry, long now, int durationMs) {
        startRandomFidget(entry, now, durationMs, BotFidgetTrigger.AUTO_FOLLOW);
    }

    static void startRandomFidget(BotEntry entry, long now, int durationMs, BotFidgetTrigger trigger) {
        BotFidgetMode mode = switch (ThreadLocalRandom.current().nextInt(6)) {
            case 0 -> BotFidgetMode.WAIT;
            case 1 -> BotFidgetMode.JUMP;
            case 2 -> BotFidgetMode.DIAGONAL_JUMP;
            case 3 -> BotFidgetMode.PRONE;
            case 4 -> BotFidgetMode.SPAM_PRONE;
            default -> BotFidgetMode.SPAM_SIDEWAYS;
        };
        startFidget(entry, mode, now, durationMs, trigger);
    }

    private static boolean handleActiveTick(BotEntry entry, Point botPos, Point targetPos, long now) {
        if (entry.climbing) {
            finishFidget(entry, botPos);
            return false;
        }
        if (entry.inAir) {
            tickActiveAirborne(entry, now);
            BotMovementManager.tickAirborne(entry, null);
            return true;
        }
        return executeGrounded(entry, botPos, targetPos, now);
    }

    private static void tickActiveAirborne(BotEntry entry, long now) {
        if ((entry.fidgetMode != BotFidgetMode.JUMP
                && entry.fidgetMode != BotFidgetMode.DIAGONAL_JUMP)
                || now < entry.nextFidgetActionAtMs) {
            return;
        }
        if (!entry.fidgetSpamAirSteer) {
            return;
        }

        int steerDir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        entry.fidgetAirSteerDir = steerDir;
        entry.moveDir = steerDir;
        entry.nextFidgetActionAtMs = now + jitteredDelayMs(entry.fidgetActionBaseDelayMs);
    }

    private static boolean executeGrounded(BotEntry entry, Point botPos, Point targetPos, long now) {
        Character bot = entry.bot;
        return switch (entry.fidgetMode) {
            case WAIT -> {
                BotPhysicsEngine.idleOnGround(entry, bot);
                BotMovementManager.broadcastMovement(entry);
                yield true;
            }
            case PRONE -> {
                BotPhysicsEngine.proneOnGround(entry, bot);
                maybeBroadcastProneAttackVisual(entry, now);
                BotMovementManager.broadcastMovement(entry);
                yield true;
            }
            case SPAM_PRONE -> {
                if (now >= entry.nextFidgetActionAtMs) {
                    if (entry.crouching) {
                        BotPhysicsEngine.idleOnGround(entry, bot);
                    } else {
                        BotPhysicsEngine.proneOnGround(entry, bot);
                    }
                    BotMovementManager.broadcastMovement(entry);
                    entry.nextFidgetActionAtMs = now + BotManager.randMs(120, 350);
                }
                maybeBroadcastProneAttackVisual(entry, now);
                yield true;
            }
            case SPAM_SIDEWAYS -> {
                tickSidewaysMovement(entry, bot, botPos, now);
                yield true;
            }
            case JUMP -> {
                if (now >= entry.nextFidgetJumpAtMs) {
                    initiateFidgetJump(entry, bot, botPos, targetPos, now, false);
                } else {
                    BotPhysicsEngine.idleOnGround(entry, bot);
                    BotMovementManager.broadcastMovement(entry);
                }
                yield true;
            }
            case DIAGONAL_JUMP -> {
                if (now >= entry.nextFidgetJumpAtMs) {
                    initiateFidgetJump(entry, bot, botPos, targetPos, now, true);
                } else {
                    BotPhysicsEngine.idleOnGround(entry, bot);
                    BotMovementManager.broadcastMovement(entry);
                }
                yield true;
            }
            default -> false;
        };
    }

    private static void initiateFidgetJump(BotEntry entry,
                                          Character bot,
                                          Point botPos,
                                          Point targetPos,
                                          long now,
                                          boolean diagonal) {
        int walkStep = BotPhysicsEngine.walkStep(bot.getMap(), entry.movementProfile);
        int jumpDx;
        if (diagonal) {
            int jumpDir = nextDiagonalJumpDir(entry, botPos);
            jumpDx = jumpDir * walkStep;
            entry.fidgetJumpDir = -jumpDir;
            entry.fidgetAirSteerDir = jumpDir;
        } else {
            int dx = targetPos.x - botPos.x;
            jumpDx = switch (ThreadLocalRandom.current().nextInt(3)) {
                case 0 -> 0;
                case 1 -> dx == 0 ? walkStep : Integer.signum(dx) * walkStep;
                default -> (ThreadLocalRandom.current().nextBoolean() ? 1 : -1) * walkStep;
            };
            entry.fidgetAirSteerDir = Integer.signum(jumpDx);
        }

        BotMovementManager.initiateJump(entry, bot, jumpDx);
        entry.nextFidgetJumpAtMs = now + BotManager.randMs(200, 400);
    }

    private static int nextDiagonalJumpDir(BotEntry entry, Point botPos) {
        Point origin = entry.fidgetOriginPos;
        if (origin != null && botPos != null) {
            int dxFromOrigin = botPos.x - origin.x;
            int bias = Math.max(8, BotPhysicsEngine.walkStep(entry.bot.getMap(), entry.movementProfile));
            if (dxFromOrigin >= bias) {
                return -1;
            }
            if (dxFromOrigin <= -bias) {
                return 1;
            }
        }
        return entry.fidgetJumpDir == 0
                ? (ThreadLocalRandom.current().nextBoolean() ? 1 : -1)
                : entry.fidgetJumpDir;
    }

    private static void tickSidewaysMovement(BotEntry entry, Character bot, Point botPos, long now) {
        if (now >= entry.nextFidgetActionAtMs || entry.fidgetMoveDir == 0) {
            entry.fidgetMoveDir = nextSidewaysDir(entry, botPos);
            entry.nextFidgetActionAtMs = now + jitteredDelayMs(entry.fidgetActionBaseDelayMs);
        }

        int dir = entry.fidgetMoveDir == 0 ? 1 : entry.fidgetMoveDir;
        int walkStep = BotPhysicsEngine.walkStep(bot.getMap(), entry.movementProfile);
        if (!BotPhysicsEngine.canWalkGroundStep(bot.getMap(), botPos, dir * walkStep)) {
            dir = -dir;
            entry.fidgetMoveDir = dir;
            if (!BotPhysicsEngine.canWalkGroundStep(bot.getMap(), botPos, dir * walkStep)) {
                BotPhysicsEngine.idleOnGround(entry, bot);
                BotMovementManager.broadcastMovement(entry);
                return;
            }
        }

        Foothold currentFh = BotPhysicsEngine.findGroundFoothold(bot.getMap(), botPos);
        if (currentFh == null) {
            BotMovementManager.broadcastMovement(entry);
            return;
        }

        entry.moveDir = dir;
        BotPhysicsEngine.applyGroundMotion(entry, bot, currentFh);
        BotMovementManager.broadcastMovement(entry);
    }

    private static int nextSidewaysDir(BotEntry entry, Point botPos) {
        Point origin = entry.fidgetOriginPos;
        int walkStep = BotPhysicsEngine.walkStep(entry.bot.getMap(), entry.movementProfile);
        if (origin != null && botPos != null) {
            int dxFromOrigin = botPos.x - origin.x;
            int bound = Math.max(12, walkStep * 2);
            if (dxFromOrigin >= bound) {
                return -1;
            }
            if (dxFromOrigin <= -bound) {
                return 1;
            }
        }
        return entry.fidgetMoveDir == 0
                ? (ThreadLocalRandom.current().nextBoolean() ? 1 : -1)
                : -entry.fidgetMoveDir;
    }

    private static int randomActionBaseDelayMs(BotFidgetMode mode, boolean spamAirSteer) {
        if (mode == BotFidgetMode.SPAM_SIDEWAYS) {
            return randomTickAlignedBaseDelayMs();
        }
        if (isJumpFidget(mode) && spamAirSteer) {
            return randomTickAlignedBaseDelayMs();
        }
        return 0;
    }

    private static int randomTickAlignedBaseDelayMs() {
        int ticks = ThreadLocalRandom.current().nextInt(SPAM_BASE_DELAY_MIN_MS / BotPhysicsEngine.cfg.TICK_MS,
                SPAM_BASE_DELAY_MAX_MS / BotPhysicsEngine.cfg.TICK_MS + 1);
        return ticks * BotPhysicsEngine.cfg.TICK_MS;
    }

    private static long jitteredDelayMs(int baseDelayMs) {
        int base = baseDelayMs > 0 ? baseDelayMs : SPAM_BASE_DELAY_MIN_MS;
        return base + (ThreadLocalRandom.current().nextBoolean() ? SPAM_JITTER_MS : 0);
    }

    private static void maybeBroadcastProneAttackVisual(BotEntry entry, long now) {
        if (entry == null || entry.bot == null || entry.bot.getMap() == null
                || !entry.crouching || now < entry.nextFidgetVisualAtMs) {
            return;
        }

        entry.nextFidgetVisualAtMs = now + BotManager.randMs(700, 1600);
        if (ThreadLocalRandom.current().nextInt(100) >= 35) {
            return;
        }

        Character bot = entry.bot;
        int direction = BotAttackExecutionProvider.bodyActionId("proneStab", "stabO1", null);
        Packet attackPacket = PacketCreator.closeRangeAttack(
                bot,
                0,
                0,
                entry.facingDir < 0 ? 0x80 : 0x00,
                0,
                Map.of(),
                4,
                direction,
                0);
        bot.getMap().broadcastMessage(bot, attackPacket, false);
    }
}
