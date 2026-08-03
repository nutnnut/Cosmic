package client.processor.stat;

import client.Character;
import client.Client;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;
import tools.PacketCreator;

import java.util.Map;

/** Shared player/bot skill-book use path. */
public final class SkillBookProcessor {
    public interface Rules {
        Map<String, Integer> stats(Character player, int itemId);

        boolean canUse(Character player, int itemId);

        boolean succeeds(int chancePercent);
    }

    static Rules rules = new Rules() {
        @Override
        public Map<String, Integer> stats(Character player, int itemId) {
            return ItemInformationProvider.getInstance().getSkillStats(itemId, player.getJob().getId());
        }

        @Override
        public boolean canUse(Character player, int itemId) {
            return ItemInformationProvider.getInstance().canUseSkillBook(player, itemId);
        }

        @Override
        public boolean succeeds(int chancePercent) {
            return ItemInformationProvider.rollSuccessChance(chancePercent);
        }
    };

    private SkillBookProcessor() {
    }

    public record Result(boolean canUse, boolean success, int skillId, int masterLevel) {
    }

    /**
     * Attempts to use the book in {@code slot}. A null result means the request did not identify a
     * real skill-book stack; a non-null result is broadcast exactly like a client book use.
     */
    public static Result useSkillBook(Character player, short slot, int itemId) {
        if (player == null || !player.isAlive()) {
            return null;
        }
        Client client = player.getClient();
        if (client == null || !client.tryacquireClient()) {
            return null;
        }

        Result result = null;
        try {
            Inventory inv = player.getInventory(InventoryType.USE);
            Item toUse = inv.getItem(slot);
            if (toUse == null || toUse.getItemId() != itemId) {
                return null;
            }
            Map<String, Integer> stats = rules.stats(player, itemId);
            if (stats == null) {
                return null;
            }

            int skillId = stats.getOrDefault("skillid", 0);
            int masterLevel = stats.getOrDefault("masterLevel", 0);
            Skill skill = SkillFactory.getSkill(skillId);
            boolean canUse = skill != null && rules.canUse(player, itemId);
            boolean success = false;
            if (canUse) {
                inv.lockInventory();
                try {
                    Item current = inv.getItem(slot);
                    if (current != toUse || toUse.getQuantity() < 1) {
                        return null;
                    }
                    InventoryManipulator.removeFromSlot(client, InventoryType.USE, slot,
                            (short) 1, false);
                } finally {
                    inv.unlockInventory();
                }

                success = rules.succeeds(stats.getOrDefault("success", 0));
                if (success) {
                    player.changeSkillLevel(skill, player.getSkillLevel(skill),
                            Math.max(masterLevel, player.getMasterLevel(skill)), -1);
                }
            }
            result = new Result(canUse, success, skillId, masterLevel);
            return result;
        } finally {
            client.releaseClient();
            if (result != null && player.getMap() != null) {
                player.getMap().broadcastMessage(PacketCreator.skillBookResult(player,
                        result.skillId(), result.masterLevel(), result.canUse(), result.success()));
            }
        }
    }
}
