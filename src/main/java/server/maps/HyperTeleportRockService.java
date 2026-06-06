package server.maps;

import client.Character;
import client.Client;
import constants.id.ItemId;
import constants.id.MapId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import tools.PacketCreator;

public final class HyperTeleportRockService {
    private static final Logger log = LoggerFactory.getLogger(HyperTeleportRockService.class);

    private HyperTeleportRockService() {
    }

    public static boolean tryTeleport(Client c, int targetMapId) {
        Character player = c.getPlayer();

        if (!canTeleport(c, player, targetMapId)) {
            return false;
        }

        MapleMap targetMap = c.getChannelServer().getMapFactory().getMap(targetMapId);
        if (targetMap == null) {
            if (player != null) {
                player.dropMessage(1, "You cannot teleport to this map.");
            }
            log.warn("[HyperTeleportRock] chr={} rejected: map {} not found",
                    player != null ? player.getName() : "unknown", targetMapId);
            return false;
        }

        String mapName = targetMap.getMapName();
        if (mapName != null && !mapName.isEmpty()) {
            player.dropMessage(5, "The Hyper Teleport Rock warps you to " + mapName + ".");
        } else {
            player.dropMessage(5, "The Hyper Teleport Rock warps you to the selected map.");
        }

        log.info("[HyperTeleportRock] chr={} from={} to={} result=accepted",
                player.getName(), player.getMapId(), targetMapId);

        player.forceChangeMap(targetMap, targetMap.getRandomPlayerSpawnpoint());
        return true;
    }

    private static boolean canTeleport(Client c, Character player, int targetMapId) {
        if (player == null || !c.isLoggedIn() || !player.isLoggedinWorld()) {
            return false;
        }

        if (player.isBanned()) {
            return false;
        }

        if (!player.isAlive()) {
            player.dropMessage(1, "You cannot use Hyper Teleport Rocks while dead.");
            return false;
        }

        if (player.isChangingMaps()) {
            player.dropMessage(1, "You cannot use Hyper Teleport Rocks right now.");
            return false;
        }

        if (player.getCashShop().isOpened() || player.getTrade() != null
                || player.getShop() != null || player.getPlayerShop() != null
                || player.getMiniGame() != null || player.getHiredMerchant() != null) {
            player.dropMessage(1, "You cannot use Hyper Teleport Rocks right now.");
            return false;
        }

        EventInstanceManager eim = player.getEventInstance();
        if (eim != null) {
            player.dropMessage(1, "Hyper Teleport Rocks cannot be used inside event instances.");
            return false;
        }

        if (!player.haveItem(ItemId.HYPER_TELEPORT_ROCK)) {
            player.dropMessage(1, "You need a Hyper Teleport Rock to use this.");
            log.warn("[HyperTeleportRock] chr={} from={} to={} result=rejected reason=no-item",
                    player.getName(), player.getMapId(), targetMapId);
            return false;
        }

        int currentMapId = player.getMapId();

        if (MapId.isTimeTemple(currentMapId)) {
            player.dropMessage(1, "You cannot use Hyper Teleport Rocks here.");
            return false;
        }

        if (MapId.isBossExpeditionMap(currentMapId)) {
            player.dropMessage(1, "Hyper Teleport Rocks cannot be used from boss expedition maps.");
            return false;
        }

        if (targetMapId <= 0) {
            player.dropMessage(1, "You cannot teleport to this map.");
            return false;
        }

        if (MapId.isTimeTemple(targetMapId)) {
            player.dropMessage(1, "You cannot teleport to this map.");
            return false;
        }

        if (MapId.isBossExpeditionMap(targetMapId)) {
            player.dropMessage(1, "Hyper Teleport Rocks cannot be used to enter boss expedition maps.");
            return false;
        }

        if (targetMapId >= 180000000 && targetMapId < 181000000) {
            player.dropMessage(1, "You cannot teleport to this map.");
            return false;
        }

        log.info("[HyperTeleportRock] chr={} from={} to={} result=pending-map-check",
                player.getName(), currentMapId, targetMapId);
        return true;
    }

    public static void enableActions(Client c) {
        c.sendPacket(PacketCreator.enableActions());
    }
}
