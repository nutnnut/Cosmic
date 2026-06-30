package server.bots;

import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import server.bots.combat.BotAttackDataProvider;
import server.bots.combat.BotAttackTiming;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotAttackDataProviderTest {
    @Test
    void shouldMatchOpenStoryBodyAndAfterimageAttackTimingInputs() {
        BotAttackDataProvider provider = BotAttackDataProvider.getInstance();

        assertTrue(provider.getBodyStanceDurationMs("swingO1") > 0);
        assertTrue(provider.getBodyStanceDelayBeforeFrameMs("swingO1", 2) > 0);
        assertTrue(provider.getBodyActionDurationMs("doublefire") > 0);
        assertTrue(provider.getBodyActionAttackDelayMs("doublefire", 0) >= 0);
        assertEquals(5, provider.getBodyActionId("swingO1"));
        assertEquals(6, provider.getBodyActionId("swingO2"));
        assertEquals(7, provider.getBodyActionId("swingO3"));
        assertEquals(16, provider.getBodyActionId("stabO1"));
        assertEquals(17, provider.getBodyActionId("stabO2"));
        assertEquals(32, provider.getBodyActionId("proneStab"));
        assertEquals(56, provider.getBodyActionId("avenger"));
        assertEquals(69, provider.getBodyActionId("genesis"));
        assertEquals(77, provider.getBodyActionId("handgun"));
        assertEquals(86, provider.getBodyActionId("doublefire"));
        BotAttackExecutionProvider.CloseRangePacketFields closeRangeFields =
                BotAttackExecutionProvider.mimicCloseRangePacketFields("stabO1", "swingO1", false);
        assertEquals(0, closeRangeFields.display());
        assertEquals(16, closeRangeFields.bodyActionId());
        assertEquals(0, closeRangeFields.facingMask());
        assertEquals(0x80, BotAttackExecutionProvider.mimicCloseRangePacketFields("stabO1", "swingO1", true).facingMask());
        assertEquals(0x00, BotAttackExecutionProvider.attackPacketStance(false));
        assertEquals(0x80, BotAttackExecutionProvider.attackPacketStance(true));

        BotAttackDataProvider.NormalAttackProfile profile = provider.getNormalAttackProfile(1302077);
        assertNotNull(profile);
        assertTrue(profile.getSourceActions().contains("swingO1"));
        assertTrue(profile.getAfterimageFirstFrame("swingO1") > 0);
    }

    @Test
    void shouldPreferExplicitBodyActionTimingOverSkillAnimationDelay() {
        BotAttackDataProvider provider = BotAttackDataProvider.getInstance();
        int rawActionHitDelayMs = provider.getBodyActionAttackDelayMs("doublefire", 0);
        int rawActionDurationMs = provider.getBodyActionDurationMs("doublefire");
        BotAttackExecutionProvider.SkillAttackTiming timing =
                BotAttackExecutionProvider.resolveSkillAttackTiming("doublefire", null, 999, 4, 300, 590);

        assertTrue(rawActionDurationMs > 0);
        assertTrue(rawActionHitDelayMs >= 0);
        assertEquals(adjustedDelay(rawActionHitDelayMs), timing.hitDelayMs());
        assertEquals(Math.max(adjustedDelay(rawActionDurationMs), 590), timing.cooldownMs());
    }

    @Test
    void shouldUseBodyStanceTimingForExplicitStanceStyleSkillActions() {
        BotAttackDataProvider provider = BotAttackDataProvider.getInstance();
        BotAttackDataProvider.NormalAttackProfile profile = provider.getNormalAttackProfile(1302077);
        assertNotNull(profile);

        int rawStanceHitDelayMs = provider.getBodyStanceDelayBeforeFrameMs("swingO1",
                profile.getAfterimageFirstFrame("swingO1"));
        int rawStanceDurationMs = provider.getBodyStanceDurationMs("swingO1");

        BotAttackExecutionProvider.SkillAttackTiming timing =
                BotAttackExecutionProvider.resolveSkillAttackTiming("swingO1", profile, 999, 4, 0, 0);

        assertTrue(rawStanceDurationMs > 0);
        assertTrue(rawStanceHitDelayMs > 0);
        assertEquals(adjustedDelay(rawStanceHitDelayMs), timing.hitDelayMs());
        assertEquals(adjustedDelay(rawStanceDurationMs), timing.cooldownMs());
    }

    @Test
    void shouldUseFullRegularAttackCooldownForSkillsSharingBasicAttackAnimation() {
        BotAttackDataProvider provider = BotAttackDataProvider.getInstance();
        int rawShootDurationMs = provider.getBodyStanceDurationMs("shoot1");
        int rawClawDurationMs = provider.getBodyStanceDurationMs("swingO1");

        BotAttackExecutionProvider.SkillAttackTiming doubleShotTiming =
                BotAttackExecutionProvider.resolveSkillAttackTiming("shoot1", null, 0, 4, 0, 0);
        BotAttackExecutionProvider.SkillAttackTiming luckySevenTiming =
                BotAttackExecutionProvider.resolveSkillAttackTiming("swingO1", null, 0, 4, 0, 0);

        assertTrue(rawShootDurationMs > 0);
        assertTrue(rawClawDurationMs > 0);
        assertEquals(adjustedDelay(rawShootDurationMs), doubleShotTiming.cooldownMs());
        assertEquals(adjustedDelay(rawClawDurationMs), luckySevenTiming.cooldownMs());
    }

    private static int adjustedDelay(int rawDelayMs) {
        return BotAttackTiming.adjustDelayMillis(rawDelayMs, 4);
    }

    @Test
    void shouldExposePerActionAttackBoundsThatDifferBetweenStabAndSwing() {
        BotAttackDataProvider provider = BotAttackDataProvider.getInstance();
        BotAttackDataProvider.NormalAttackProfile profile = provider.getNormalAttackProfile(1302077);
        assertNotNull(profile);
        assertTrue(profile.hasBoundingBox());

        Point origin = new Point(0, 0);
        Rectangle swing = profile.calculateActionBoundingBox("swingO1", origin, false);
        Rectangle stab = profile.calculateActionBoundingBox("stabO1", origin, false);
        assertNotNull(swing);
        assertNotNull(stab);
        // A single moveset's overhead swing (tall) and stab (low/short) cover different areas, so
        // the bot must gate the hit on the rolled action's own box, not the union envelope.
        assertNotEquals(swing, stab);

        // An unknown / non-afterimage action falls back to the unioned envelope (still valid).
        Rectangle union = profile.calculateBoundingBox(origin, false);
        assertEquals(union, profile.calculateActionBoundingBox("not-a-real-action", origin, false));
    }

    @Test
    void shouldFilterWeaponActionsToLegalAttackGroupAnimations() {
        BotAttackDataProvider.AttackAnimationSpec attackSpec =
                BotAttackDataProvider.getInstance().getBasicAttackSpec(1, client.inventory.WeaponType.GENERAL1H_SWING);

        List<String> actions = BotAttackExecutionProvider.resolveAttackActions(attackSpec,
                List.of("swingOF", "stabO1", "proneStab", "swingO3", "stabOF"));

        assertEquals(List.of("stabO1", "swingO3"), actions);
    }

    @Test
    void shouldMatchCapturedWandStaffMeleePacketAction() {
        BotAttackDataProvider provider = BotAttackDataProvider.getInstance();

        assertEquals("swingO2", provider.getBasicAttackSpec(WeaponType.STAFF).primaryAction());
        assertEquals("swingO2", provider.getBasicAttackSpec(WeaponType.WAND).primaryAction());
        assertEquals(6, provider.getBodyActionId("swingO2", WeaponType.STAFF));
        assertEquals(6, provider.getBodyActionId("swingO2", WeaponType.WAND));
    }
}
