package server.bots;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotOfferManagerTest {

    @Test
    void crossbowmanCanAcceptBowOffersAsFallback() {
        Character recipient = mock(Character.class);
        when(recipient.getJob()).thenReturn(Job.CROSSBOWMAN);
        assertTrue(BotOfferManager.isWeaponOfferCompatible(recipient, WeaponType.BOW));
    }

    @Test
    void crossbowmanAcceptsCrossbowOffers() {
        Character recipient = mock(Character.class);
        when(recipient.getJob()).thenReturn(Job.CROSSBOWMAN);
        assertTrue(BotOfferManager.isWeaponOfferCompatible(recipient, WeaponType.CROSSBOW));
    }

    @Test
    void mageRecipientAcceptsOffTypeMatkWeaponOffers() {
        // Black Umbrella field case: a 1H any-job sword carrying MAD must be offerable to a
        // mage owner/party member instead of falling through to NPC sell-trash.
        Character mage = mock(Character.class);
        when(mage.getJob()).thenReturn(Job.CLERIC);

        Equip umbrella = mock(Equip.class);
        when(umbrella.getMatk()).thenReturn((short) 92);

        assertTrue(BotOfferManager.isWeaponOfferCompatible(mage, WeaponType.SWORD1H, umbrella));
        // The type-only check now allows fallback weapons; item scoring decides whether to request it.
        assertTrue(BotOfferManager.isWeaponOfferCompatible(mage, WeaponType.SWORD1H));
    }

    @Test
    void selfOwnedBotNeverOffersGearToItself() {
        // A self-owned managed bot has owner == bot; offering would open a trade with itself
        // ("trade declined"). The guard must short-circuit before any trade is queued.
        Character bot = mock(Character.class);
        assertFalse(BotOfferManager.offerBestRecommendedGear(null, bot, bot));
    }

    @Test
    void physicalRecipientCanAcceptOffTypeWeaponOffersAsFallback() {
        Character recipient = mock(Character.class);
        when(recipient.getJob()).thenReturn(Job.CROSSBOWMAN);

        Equip umbrella = mock(Equip.class);
        when(umbrella.getMatk()).thenReturn((short) 92);

        assertTrue(BotOfferManager.isWeaponOfferCompatible(recipient, WeaponType.SWORD1H, umbrella));
    }
}
