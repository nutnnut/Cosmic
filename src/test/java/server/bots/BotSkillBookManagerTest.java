package server.bots;

import client.BuffStat;
import client.Character;
import client.Job;
import client.Skill;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.processor.stat.SkillBookProcessor;
import constants.skills.Bishop;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import tools.Pair;

import java.util.Map;

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

    /** Flat statups map straight onto the equip-stat keys the shared scorer reads, so a buff book is
     *  priced by the same function as an equip drop rather than a book-specific constant. */
    @Test
    void flatStatBuffGrantsMapOntoEquipStatKeys() {
        Character bot = mock(Character.class);
        Skill skill = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(skill.getEffect(30)).thenReturn(effect);
        when(effect.getStatups()).thenReturn(java.util.List.of(
                new Pair<>(BuffStat.WATK, 30), new Pair<>(BuffStat.AVOID, 12)));

        Map<String, Integer> grant = BotSkillBookManager.statGrantAt(bot, skill, 30);

        assertEquals(30, grant.get("PAD"));
        assertEquals(12, grant.get("EVA"));
    }

    /** Maple Warrior is a percentage of the bot's OWN base stats — resolved the same way
     *  Character.recalcLocalStats applies it, so a stronger bot values the book more. */
    @Test
    void mapleWarriorGrantScalesWithTheBotsOwnBaseStats() {
        Character bot = mock(Character.class);
        Skill skill = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(bot.getStr()).thenReturn(400);
        when(bot.getDex()).thenReturn(120);
        when(bot.getInt()).thenReturn(4);
        when(bot.getLuk()).thenReturn(4);
        when(skill.getEffect(30)).thenReturn(effect);
        when(effect.getStatups()).thenReturn(java.util.List.of(new Pair<>(BuffStat.MAPLE_WARRIOR, 15)));

        Map<String, Integer> grant = BotSkillBookManager.statGrantAt(bot, skill, 30);

        assertEquals(60, grant.get("STR"));
        assertEquals(18, grant.get("DEX"));
    }

    /** A skill whose grant the offense model cannot see (utility buffs, packed crit) scores nothing
     *  rather than a fabricated weight. */
    @Test
    void grantsWithNoEquipStatEquivalentScoreNothing() {
        Character bot = mock(Character.class);
        Skill skill = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(skill.getEffect(30)).thenReturn(effect);
        when(effect.getStatups()).thenReturn(java.util.List.of(new Pair<>(BuffStat.SHARP_EYES, 0x1408)));

        assertTrue(BotSkillBookManager.statGrantAt(bot, skill, 30).isEmpty());
        assertTrue(BotSkillBookManager.statGrantAt(bot, skill, 0).isEmpty());
    }
}
