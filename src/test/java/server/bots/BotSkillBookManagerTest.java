package server.bots;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.processor.stat.SkillBookProcessor;
import constants.skills.Bishop;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotSkillBookManagerTest {
    @Test
    void tickUsesOneWantedBookAndAssignsNewlyUnlockedSp() {
        Character bot = mock(Character.class);
        Inventory use = mock(Inventory.class);
        Item book = mock(Item.class);
        BotEntry entry = new BotEntry(bot, bot, null);
        long now = 10_000L;
        var realWanted = BotSkillBookManager.wantedNow;
        var realUse = BotSkillBookManager.bookUse;
        var realAssign = BotSkillBookManager.assignSp;
        boolean[] assigned = {false};
        try {
            when(bot.getInventory(InventoryType.USE)).thenReturn(use);
            when(use.list()).thenReturn(java.util.List.of(book));
            when(book.getItemId()).thenReturn(2_290_001);
            when(book.getPosition()).thenReturn((short) 4);
            BotSkillBookManager.wantedNow = (e, b, id) -> true;
            BotSkillBookManager.bookUse = (b, slot, id) ->
                    new SkillBookProcessor.Result(true, true, 1_122_001, 20);
            BotSkillBookManager.assignSp = (e, b) -> assigned[0] = true;

            BotSkillBookManager.tick(entry, bot, now);

            assertTrue(assigned[0]);
            assertTrue(entry.nextSkillBookUseAtMs >= now + 2_500L);
            assertTrue(entry.nextSkillBookUseAtMs <= now + 6_500L);
        } finally {
            BotSkillBookManager.wantedNow = realWanted;
            BotSkillBookManager.bookUse = realUse;
            BotSkillBookManager.assignSp = realAssign;
        }
    }

    @Test
    void sharingIncludesRealPartyMembersAndCapsTheOfferAtOneBook() {
        Character donor = mock(Character.class);
        Character partyMember = mock(Character.class);
        Item books = mock(Item.class);
        when(donor.getPartyMembersOnSameMap()).thenReturn(java.util.List.of(donor, partyMember));
        when(books.getItemId()).thenReturn(2_290_001);

        assertTrue(BotOfferManager.skillBookRecipients(null, donor).contains(partyMember));
        assertEquals(1, BotOfferManager.lootOfferQuantity(books));
    }


    @Test
    void plannedTargetUsesTheFinalBuildCapNotAnEarlyBreakpoint() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.BISHOP);
        BotEntry entry = new BotEntry(bot, bot, null);

        assertEquals(30, BotBuildManager.plannedSkillTarget(entry, bot, Bishop.GENESIS));
    }

    @Test
    void recognizesSkillAndMasteryBookRanges() {
        assertTrue(BotSkillBookManager.isSkillBook(2_280_000));
        assertTrue(BotSkillBookManager.isSkillBook(2_290_139));
        assertFalse(BotSkillBookManager.isSkillBook(2_280_020));
        assertFalse(BotSkillBookManager.isSkillBook(2_290_140));
        assertFalse(BotSkillBookManager.isSkillBook(2_279_999));
        assertFalse(BotSkillBookManager.isSkillBook(2_300_000));
    }

    @Test
    void onlyWantsValidBooksThatRaiseAPlannedSkillCap() {
        assertTrue(BotSkillBookManager.raisesPlannedCap(1_122_001, 30, 10));
        assertFalse(BotSkillBookManager.raisesPlannedCap(1_122_001, 10, 10));
        assertFalse(BotSkillBookManager.raisesPlannedCap(1_122_001, 0, 0));
    }

    @Test
    void grindWeightIncludesUnlockedLevelsAndApplicationChance() {
        double masteryTwenty = BotSkillBookManager.needFraction(70, 20, 10, 30);
        double masteryThirty = BotSkillBookManager.needFraction(50, 30, 20, 30);

        assertEquals(0.70 * (0.20 + 10.0 / 60.0), masteryTwenty, 1e-9);
        assertEquals(0.50 * (0.20 + 10.0 / 60.0), masteryThirty, 1e-9);
        assertEquals(0.0, BotSkillBookManager.needFraction(0, 30, 20, 30), 1e-9);
    }
}
