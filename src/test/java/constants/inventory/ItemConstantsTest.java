package constants.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure id-math checks on {@link ItemConstants#canScroll} — the shared applicability rule the player
 * {@code ScrollHandler} and the bot scroll planner both use. Focus is the generic accessory-scroll
 * special case (20492xx) and the default category match.
 */
class ItemConstantsTest {

    private static final int GENERIC_ACCESSORY_SCROLL = 2049200; // Scroll for Accessory (ring/pendant/belt)
    private static final int GLOVE_ATT_SCROLL = 2040800;         // default category 8 (gloves)

    private static final int RING = 1112000;
    private static final int PENDANT = 1122000;
    private static final int BELT = 1132000;
    private static final int GLOVE = 1082000;

    @Test
    void accessoryScrollAppliesToRingPendantBelt() {
        assertTrue(ItemConstants.canScroll(GENERIC_ACCESSORY_SCROLL, RING), "20492xx must fit rings");
        assertTrue(ItemConstants.canScroll(GENERIC_ACCESSORY_SCROLL, PENDANT), "20492xx must fit pendants");
        assertTrue(ItemConstants.canScroll(GENERIC_ACCESSORY_SCROLL, BELT), "20492xx must fit belts");
    }

    @Test
    void accessoryScrollDoesNotApplyToNonAccessory() {
        assertFalse(ItemConstants.canScroll(GENERIC_ACCESSORY_SCROLL, GLOVE),
                "20492xx must not fit a glove (category 8)");
    }

    @Test
    void defaultCategoryMatch() {
        assertTrue(ItemConstants.canScroll(GLOVE_ATT_SCROLL, GLOVE), "glove scroll fits a glove");
        assertFalse(ItemConstants.canScroll(GLOVE_ATT_SCROLL, RING), "glove scroll does not fit a ring");
    }
}
