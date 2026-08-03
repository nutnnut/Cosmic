package client.processor.stat;

import client.Character;
import client.Client;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillBookProcessorTest {
    @Test
    void consumesAndAppliesABookThroughTheSharedPlayerPath() {
        Character player = mock(Character.class);
        Client client = mock(Client.class);
        Inventory use = mock(Inventory.class);
        Item book = mock(Item.class);
        Skill skill = mock(Skill.class);
        short slot = 3;
        int itemId = 2_290_001;
        int skillId = 1_122_001;

        when(player.isAlive()).thenReturn(true);
        when(player.getClient()).thenReturn(client);
        when(client.tryacquireClient()).thenReturn(true);
        when(player.getInventory(InventoryType.USE)).thenReturn(use);
        when(use.getItem(slot)).thenReturn(book);
        when(book.getItemId()).thenReturn(itemId);
        when(book.getQuantity()).thenReturn((short) 1);
        when(player.getSkillLevel(skill)).thenReturn((byte) 10);
        when(player.getMasterLevel(skill)).thenReturn(10);

        SkillBookProcessor.Rules realRules = SkillBookProcessor.rules;
        try (MockedStatic<SkillFactory> skills = mockStatic(SkillFactory.class);
             MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            SkillBookProcessor.rules = new SkillBookProcessor.Rules() {
                @Override
                public Map<String, Integer> stats(Character p, int id) {
                    return Map.of("skillid", skillId, "masterLevel", 20, "success", 70);
                }

                @Override
                public boolean canUse(Character p, int id) {
                    return true;
                }

                @Override
                public boolean succeeds(int chancePercent) {
                    return true;
                }
            };
            skills.when(() -> SkillFactory.getSkill(skillId)).thenReturn(skill);

            SkillBookProcessor.Result result = SkillBookProcessor.useSkillBook(player, slot, itemId);

            assertNotNull(result);
            assertTrue(result.canUse());
            assertTrue(result.success());
            inventory.verify(() -> InventoryManipulator.removeFromSlot(
                    client, InventoryType.USE, slot, (short) 1, false));
            verify(player).changeSkillLevel(skill, (byte) 10, 20, -1L);
            verify(client).releaseClient();
        } finally {
            SkillBookProcessor.rules = realRules;
        }
    }
}
