package server.bots;

import client.Character;
import constants.game.CharacterStance;
import constants.id.MobId;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import server.life.LifeFactory;
import server.life.Monster;
import server.TimerManager;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class BotAirshowManager {
    private static final int FRAME_MS = 50;
    private static final int HORIZONTAL_SPEED_PX_PER_SEC = 2000;
    private static final int VERTICAL_SPEED_PX_PER_SEC = 1200;
    private static final long TRAIL_INTERVAL_MS = 100L;
    private static final long TRAIL_DEATH_DELAY_MS = 120L;
    private static final long BETWEEN_RUN_DELAY_MS = 1000L;
    private static final AtomicInteger TRAIL_OBJECT_IDS = new AtomicInteger(1_900_000_000);

    private BotAirshowManager() {
    }

    public static String start(Character owner, String botName) {
        if (owner == null) {
            return "No owner.";
        }
        if (botName == null || botName.isBlank()) {
            return "Syntax: !airshow <botname>";
        }

        BotEntry entry = BotManager.getInstance().getBotEntry(owner.getId(), botName);
        if (entry == null) {
            return "No active owned bot named '" + botName + "'.";
        }

        Character bot = entry.bot;
        MapleMap map = owner.getMap();
        if (bot == null || bot.getMap() == null || map == null || bot.getMap() != map) {
            return "Bot '" + entry.bot.getName() + "' must be in your map.";
        }
        if (entry.airshowActive) {
            return "Bot '" + bot.getName() + "' is already doing an airshow.";
        }

        Rectangle bounds = map.getMapArea();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return "This map has no usable bounds for airshow.";
        }

        Point previousPosition = new Point(bot.getPosition());
        int previousStance = bot.getStance();
        entry.airshowActive = true;
        entry.airshowLastTrailAtMs = 0L;
        entry.movementBroadcastValid = false;

        int left = bounds.x;
        int right = bounds.x + bounds.width;
        int top = bounds.y;
        int bottom = bounds.y + bounds.height;
        int showY = owner.getPosition().y;
        int showX = owner.getPosition().x;

        flyHorizontal(entry, map, new Point(left, showY), right, HORIZONTAL_SPEED_PX_PER_SEC,
                CharacterStance.PRONE_RIGHT_STANCE,
                () -> TimerManager.getInstance().schedule(
                        () -> flyHorizontal(entry, map, new Point(right, showY), left, -HORIZONTAL_SPEED_PX_PER_SEC,
                                CharacterStance.PRONE_LEFT_STANCE,
                                () -> TimerManager.getInstance().schedule(
                                        () -> flyVertical(entry, map, new Point(showX, bottom), top,
                                                -VERTICAL_SPEED_PX_PER_SEC, CharacterStance.ROPE_RIGHT_STANCE,
                                                () -> restore(entry, previousPosition, previousStance)),
                                        BETWEEN_RUN_DELAY_MS)),
                        BETWEEN_RUN_DELAY_MS));

        return "Airshow started for " + bot.getName() + ".";
    }

    private static void flyHorizontal(BotEntry entry,
                                      MapleMap map,
                                      Point start,
                                      int targetX,
                                      int velocityX,
                                      int stance,
                                      Runnable done) {
        moveFrame(entry, map, start, velocityX, 0, stance);
        scheduleHorizontalFrame(entry, map, targetX, velocityX, stance, done);
    }

    private static void scheduleHorizontalFrame(BotEntry entry,
                                                MapleMap map,
                                                int targetX,
                                                int velocityX,
                                                int stance,
                                                Runnable done) {
        TimerManager.getInstance().schedule(() -> {
            if (!entry.airshowActive || entry.bot.getMap() != map) {
                restore(entry, entry.bot.getPosition(), entry.bot.getStance());
                return;
            }

            Point current = entry.bot.getPosition();
            int step = velocityX * FRAME_MS / 1000;
            int nextX = current.x + step;
            boolean finished = velocityX >= 0 ? nextX >= targetX : nextX <= targetX;
            Point next = new Point(finished ? targetX : nextX, current.y);
            moveFrame(entry, map, next, velocityX, 0, stance);
            if (finished) {
                done.run();
            } else {
                scheduleHorizontalFrame(entry, map, targetX, velocityX, stance, done);
            }
        }, FRAME_MS);
    }

    private static void flyVertical(BotEntry entry,
                                    MapleMap map,
                                    Point start,
                                    int targetY,
                                    int velocityY,
                                    int stance,
                                    Runnable done) {
        moveFrame(entry, map, start, 0, velocityY, stance);
        scheduleVerticalFrame(entry, map, targetY, velocityY, stance, done);
    }

    private static void scheduleVerticalFrame(BotEntry entry,
                                              MapleMap map,
                                              int targetY,
                                              int velocityY,
                                              int stance,
                                              Runnable done) {
        TimerManager.getInstance().schedule(() -> {
            if (!entry.airshowActive || entry.bot.getMap() != map) {
                restore(entry, entry.bot.getPosition(), entry.bot.getStance());
                return;
            }

            Point current = entry.bot.getPosition();
            int step = velocityY * FRAME_MS / 1000;
            int nextY = current.y + step;
            boolean finished = velocityY >= 0 ? nextY >= targetY : nextY <= targetY;
            Point next = new Point(current.x, finished ? targetY : nextY);
            moveFrame(entry, map, next, 0, velocityY, stance);
            if (finished) {
                done.run();
            } else {
                scheduleVerticalFrame(entry, map, targetY, velocityY, stance, done);
            }
        }, FRAME_MS);
    }

    private static void restore(BotEntry entry, Point previousPosition, int previousStance) {
        if (!entry.airshowActive) {
            return;
        }
        Character bot = entry.bot;
        entry.airshowActive = false;
        entry.airshowLastTrailAtMs = 0L;
        BotPhysicsEngine.teleportTo(entry, bot, previousPosition);
        bot.setStance(previousStance);
        entry.movementBroadcastValid = false;
        sendMovementPacket(bot, previousPosition, 0, 0, previousStance);
        BotMovementManager.resetEntryStateAfterTeleport(entry);
    }

    private static void moveFrame(BotEntry entry, MapleMap map, Point position, int velocityX, int velocityY, int stance) {
        Character bot = entry.bot;
        bot.setPosition(position);
        bot.setStance(stance);
        entry.physX = position.x;
        entry.physY = position.y;
        entry.movementVelX = velocityX;
        entry.movementVelY = velocityY;
        entry.facingDir = CharacterStance.isFacingLeft(stance) ? -1 : 1;
        entry.inAir = true;
        entry.climbing = CharacterStance.isClimbing(stance);
        sendMovementPacket(bot, position, velocityX, velocityY, stance);
        maybeChemTrail(entry, map, position);
        bot.updatePartyMemberHP();
    }

    private static void maybeChemTrail(BotEntry entry, MapleMap map, Point position) {
        if (!map.isObservedByPlayer()) {
            return; // purely cosmetic trail — no audience, no point spawning/killing trail mobs
        }
        long now = System.currentTimeMillis();
        if (now - entry.airshowLastTrailAtMs < TRAIL_INTERVAL_MS) {
            return;
        }
        entry.airshowLastTrailAtMs = now;

        Monster trail = LifeFactory.getMonster(MobId.ORANGE_MUSHROOM);
        if (trail == null) {
            return;
        }
        trail.setObjectId(TRAIL_OBJECT_IDS.getAndIncrement());
        trail.setPosition(position);
        trail.setFh(entry.lastGroundFhId);
        trail.setStance(CharacterStance.STAND_RIGHT_STANCE);
        map.broadcastMessage(PacketCreator.spawnMonster(trail, true));
        TimerManager.getInstance().schedule(
                () -> map.broadcastMessage(PacketCreator.killMonster(trail.getObjectId(), 1), trail.getPosition()),
                TRAIL_DEATH_DELAY_MS);
    }

    private static void sendMovementPacket(Character bot, Point position, int velocityX, int velocityY, int stance) {
        byte[] data = new byte[15];
        data[0] = 1;
        data[2] = (byte) (position.x & 0xFF);
        data[3] = (byte) (position.x >> 8);
        data[4] = (byte) (position.y & 0xFF);
        data[5] = (byte) (position.y >> 8);
        data[6] = (byte) (velocityX & 0xFF);
        data[7] = (byte) (velocityX >> 8);
        data[8] = (byte) (velocityY & 0xFF);
        data[9] = (byte) (velocityY >> 8);
        data[12] = (byte) stance;
        data[13] = (byte) (FRAME_MS & 0xFF);
        data[14] = (byte) (FRAME_MS >> 8);
        InPacket packet = new ByteBufInPacket(Unpooled.wrappedBuffer(data));
        Packet movePacket = PacketCreator.movePlayer(bot.getId(), packet, data.length);
        bot.getMap().broadcastMessage(bot, movePacket, false);
    }
}
