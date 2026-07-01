package server.bots.combat;

import client.inventory.WeaponType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import provider.wz.WZFiles;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static server.bots.combat.BotWzXml.findNamedChild;
import static server.bots.combat.BotWzXml.getIntAttribute;
import static server.bots.combat.BotWzXml.getIntValue;

public final class BotAttackDataProvider {
    private static final Logger log = LoggerFactory.getLogger(BotAttackDataProvider.class);
    private static final BotAttackDataProvider instance = new BotAttackDataProvider();
    private static final Map<String, Integer> BODY_ACTION_ID_OVERRIDES = createBodyActionIdOverrides();
    private static final Map<String, Integer> CLAW_BODY_ACTION_ID_OVERRIDES = createClawBodyActionIdOverrides();
    private static final Map<String, Integer> WAND_BODY_ACTION_ID_OVERRIDES = createWandBodyActionIdOverrides();
    private static final Map<String, Integer> ATTACK_STANCE_IDS = createAttackStanceIds();

    public static BotAttackDataProvider getInstance() {
        return instance;
    }

    public static final class NormalAttackProfile {
        private final int attackSpeed;
        private final int attack;
        private final String afterImage;
        private final Rectangle rightFacingBounds;
        private final Map<String, Rectangle> rightFacingBoundsByAction;
        private final List<String> sourceActions;
        private final Map<String, Integer> afterimageFirstFramesByAction;

        private NormalAttackProfile(int attackSpeed, int attack, String afterImage, Rectangle rightFacingBounds,
                                    Map<String, Rectangle> rightFacingBoundsByAction,
                                    List<String> sourceActions, Map<String, Integer> afterimageFirstFramesByAction) {
            this.attackSpeed = attackSpeed;
            this.attack = attack;
            this.afterImage = afterImage;
            this.rightFacingBounds = rightFacingBounds != null ? new Rectangle(rightFacingBounds) : null;
            this.rightFacingBoundsByAction = Map.copyOf(rightFacingBoundsByAction);
            this.sourceActions = List.copyOf(sourceActions);
            this.afterimageFirstFramesByAction = Map.copyOf(afterimageFirstFramesByAction);
        }

        public int getAttackSpeed() {
            return attackSpeed;
        }

        public int getAttack() {
            return attack;
        }

        public String getAfterImage() {
            return afterImage;
        }

        public boolean hasBoundingBox() {
            return rightFacingBounds != null;
        }

        public List<String> getSourceActions() {
            return sourceActions;
        }

        public String getActionForVariant(int variantOffset, String fallbackAction) {
            if (sourceActions.isEmpty()) {
                return fallbackAction;
            }
            int normalizedIndex = Math.max(0, Math.min(variantOffset, sourceActions.size() - 1));
            return sourceActions.get(normalizedIndex);
        }

        public int getAfterimageFirstFrame(String actionName) {
            return afterimageFirstFramesByAction.getOrDefault(actionName, 0);
        }

        public Rectangle calculateBoundingBox(Point origin, boolean facingLeft) {
            return toWorldBox(rightFacingBounds, origin, facingLeft);
        }

        // Per-action hitbox: a single moveset mixes actions with genuinely different reach
        // (e.g. a spear stab is long+low while its overhead swing is tall+short). The bot rolls
        // one action per swing and gates the hit on exactly that action's box, so it hits/misses
        // like a real player instead of claiming the union envelope of every action. Falls back to
        // the unioned bounds when the action is unknown / not in the afterimage.
        public Rectangle calculateActionBoundingBox(String action, Point origin, boolean facingLeft) {
            Rectangle bounds = action != null ? rightFacingBoundsByAction.get(action) : null;
            if (bounds == null) {
                bounds = rightFacingBounds;
            }
            return toWorldBox(bounds, origin, facingLeft);
        }

        private static Rectangle toWorldBox(Rectangle rightFacingBounds, Point origin, boolean facingLeft) {
            if (rightFacingBounds == null) {
                return null;
            }

            int leftOffset = rightFacingBounds.x;
            int rightOffset = rightFacingBounds.x + rightFacingBounds.width;
            if (facingLeft) {
                int originalLeftOffset = leftOffset;
                leftOffset = -rightOffset;
                rightOffset = -originalLeftOffset;
            }

            return new Rectangle(origin.x + leftOffset, origin.y + rightFacingBounds.y,
                    rightOffset - leftOffset, rightFacingBounds.height);
        }
    }

    public record AttackAnimationSpec(int display, List<String> actions) {
        public String primaryAction() {
            return actions.isEmpty() ? "swingO1" : actions.get(0);
        }

        public String actionForVariant(int variantOffset) {
            if (actions.isEmpty()) {
                return "swingO1";
            }
            int normalizedIndex = Math.max(0, Math.min(variantOffset, actions.size() - 1));
            return actions.get(normalizedIndex);
        }
    }

    private record BodyStanceTiming(List<Integer> frameDelays, int totalDelayMillis) {
    }

    private record BodyActionTiming(List<Integer> attackFrameDelays, int totalDelayMillis) {
    }

    private static final class AttackBoundsData {
        private final Rectangle bounds;
        private final Map<String, Rectangle> boundsByAction;
        private final List<String> sourceActions;
        private final Map<String, Integer> firstFramesByAction;

        private AttackBoundsData(Rectangle bounds, Map<String, Rectangle> boundsByAction, List<String> sourceActions,
                                 Map<String, Integer> firstFramesByAction) {
            this.bounds = new Rectangle(bounds);
            this.boundsByAction = Map.copyOf(boundsByAction);
            this.sourceActions = List.copyOf(sourceActions);
            this.firstFramesByAction = Map.copyOf(firstFramesByAction);
        }
    }

    private final Map<Integer, NormalAttackProfile> normalAttackProfiles = new HashMap<>();
    private volatile Map<String, BodyStanceTiming> bodyStanceTimings = null;
    private volatile Map<String, BodyActionTiming> bodyActionTimings = null;
    private volatile Map<String, Integer> bodyActionIds = null;
    private volatile Path cachedCharacterRoot = null;

    private BotAttackDataProvider() {
    }

    private static Map<String, Integer> createBodyActionIdOverrides() {
        Map<String, Integer> overrides = new HashMap<>();
        overrides.put("walk1", 0);
        overrides.put("walk2", 1);
        overrides.put("stand1", 2);
        overrides.put("stand2", 3);
        overrides.put("alert", 4);
        overrides.put("swingO1", 5);
        overrides.put("swingO2", 6);
        overrides.put("swingO3", 7);
        overrides.put("swingOF", 8);
        overrides.put("swingT1", 9);
        overrides.put("swingT2", 10);
        overrides.put("swingT3", 11);
        overrides.put("swingTF", 12);
        overrides.put("swingP1", 13);
        overrides.put("swingP2", 14);
        overrides.put("swingPF", 15);
        overrides.put("stabO1", 16);
        overrides.put("stabO2", 17);
        overrides.put("stabOF", 18);
        overrides.put("stabT1", 19);
        overrides.put("stabT2", 20);
        overrides.put("stabTF", 21);
        overrides.put("shoot1", 22);
        overrides.put("shoot2", 23);
        overrides.put("shootF", 27);
        overrides.put("wand1", 28);
        overrides.put("wand2", 29);
        overrides.put("heal", 31);
        overrides.put("proneStab", 32);
        overrides.put("prone", 33);
        overrides.put("fly", 34);
        overrides.put("jump", 35);
        overrides.put("ladder", 36);
        overrides.put("rope", 37);
        overrides.put("dead", 38);
        overrides.put("sit", 39);
        overrides.put("tired", 40);
        overrides.put("alert2", 41);
        overrides.put("alert3", 42);
        overrides.put("alert4", 43);
        overrides.put("alert5", 44);
        overrides.put("alert6", 45);
        overrides.put("ladder2", 46);
        overrides.put("rope2", 47);
        overrides.put("shoot6", 48);
        overrides.put("magic1", 49);
        overrides.put("magic2", 50);
        overrides.put("magic3", 51);
        overrides.put("magic5", 52);
        overrides.put("burster1", 53);
        overrides.put("burster2", 54);
        overrides.put("savage", 55);
        overrides.put("avenger", 56);
        overrides.put("assaulter", 57);
        overrides.put("prone2", 58);
        overrides.put("assassination", 59);
        overrides.put("assassinationS", 60);
        overrides.put("rush", 61);
        overrides.put("rush2", 62);
        overrides.put("brandish1", 63);
        overrides.put("brandish2", 64);
        overrides.put("sanctuary", 65);
        overrides.put("meteor", 66);
        overrides.put("paralyze", 67);
        overrides.put("blizzard", 68);
        overrides.put("genesis", 69);
        overrides.put("ninjastorm", 70);
        overrides.put("blast", 71);
        overrides.put("holyshield", 72);
        overrides.put("showdown", 73);
        overrides.put("resurrection", 74);
        overrides.put("chainlightning", 75);
        overrides.put("smokeshell", 76);
        overrides.put("handgun", 77);
        overrides.put("somersault", 78);
        overrides.put("straight", 79);
        overrides.put("eburster", 80);
        overrides.put("backspin", 81);
        overrides.put("eorb", 82);
        overrides.put("screw", 83);
        overrides.put("doubleupper", 84);
        overrides.put("dragonstrike", 85);
        overrides.put("doublefire", 86);
        overrides.put("triplefire", 87);
        overrides.put("fake", 88);
        overrides.put("airstrike", 89);
        overrides.put("edrain", 90);
        overrides.put("octopus", 91);
        overrides.put("backstep", 92);
        overrides.put("shot", 93);
        overrides.put("recovery", 94);
        overrides.put("fireburner", 95);
        overrides.put("coolingeffect", 96);
        overrides.put("fist", 97);
        overrides.put("timeleap", 98);
        overrides.put("rapidfire", 99);
        overrides.put("homing", 100);
        overrides.put("ghostwalk", 101);
        overrides.put("ghoststand", 102);
        overrides.put("ghostjump", 103);
        overrides.put("ghostproneStab", 104);
        overrides.put("ghostfly", 105);
        overrides.put("ghostladder", 106);
        overrides.put("ghostrope", 107);
        overrides.put("ghostsit", 108);
        overrides.put("cannon", 109);
        overrides.put("torpedo", 110);
        overrides.put("darksight", 111);
        overrides.put("bamboo", 112);
        overrides.put("pyramid", 113);
        overrides.put("wave", 114);
        overrides.put("blade", 115);
        overrides.put("souldriver", 116);
        overrides.put("firestrike", 117);
        overrides.put("flamegear", 118);
        overrides.put("stormbreak", 119);
        overrides.put("vampire", 120);
        overrides.put("float", 121);
        overrides.put("swingT2PoleArm", 122);
        overrides.put("swingP1PoleArm", 123);
        overrides.put("swingP2PoleArm", 124);
        overrides.put("doubleSwing", 125);
        overrides.put("tripleSwing", 126);
        overrides.put("fullSwingDouble", 127);
        overrides.put("fullSwingTriple", 128);
        overrides.put("overSwingDouble", 129);
        overrides.put("overSwingTriple", 130);
        overrides.put("rollingSpin", 131);
        overrides.put("comboSmash", 132);
        overrides.put("comboFenrir", 133);
        overrides.put("comboTempest", 134);
        overrides.put("finalCharge", 135);
        overrides.put("combatStep", 136);
        overrides.put("finalBlow", 137);
        overrides.put("finalToss", 138);
        overrides.put("magicMissile", 139);
        overrides.put("dragonSpark", 140);
        overrides.put("dragonBreathe", 141);
        overrides.put("breathePrepare", 142);
        overrides.put("dragonIceBreathe", 143);
        overrides.put("iceBreathePrepare", 144);
        overrides.put("infinityExplosion", 145);
        overrides.put("superMagicMissile", 146);
        overrides.put("illusion", 147);
        overrides.put("magicFlare", 148);
        overrides.put("elementalReset", 149);
        overrides.put("elementalRegistance", 150);
        overrides.put("dragonAura", 151);
        overrides.put("magicBooster", 152);
        overrides.put("dragonShield", 153);
        overrides.put("dragonFury", 154);
        overrides.put("dragonFly", 155);
        overrides.put("dragonSkin", 156);
        overrides.put("shockwave", 157);
        overrides.put("demolition", 158);
        return Map.copyOf(overrides);
    }

    private static Map<String, Integer> createWandBodyActionIdOverrides() {
        // Real staff/wand melee broadcasts use the standard body action ids (see
        // logs/monitored-packets-staff-melee-swing.log), not the later magic-only swingO block.
        return Map.of();
    }

    private static Map<String, Integer> createClawBodyActionIdOverrides() {
        // Claw ranged attack uses the second swingO block in actions.txt (IDs 24/25/26),
        // not the standard sword swingO block (IDs 5/6/7).
        Map<String, Integer> overrides = new HashMap<>();
        overrides.put("stabO1", 16);
        overrides.put("stabO2", 17);
        overrides.put("swingO1", 24);
        overrides.put("swingO2", 25);
        overrides.put("swingO3", 26);
        return Map.copyOf(overrides);
    }

    private static Map<String, Integer> createAttackStanceIds() {
        Map<String, Integer> stanceIds = new HashMap<>();
        stanceIds.put("shot", 10);
        stanceIds.put("handgun", 10);
        stanceIds.put("shoot1", 11);
        stanceIds.put("shoot2", 12);
        stanceIds.put("stabO1", 15);
        stanceIds.put("stabO2", 16);
        stanceIds.put("stabT1", 18);
        stanceIds.put("stabT2", 19);
        stanceIds.put("swingO1", 23);
        stanceIds.put("swingO2", 24);
        stanceIds.put("swingO3", 25);
        stanceIds.put("swingP1", 27);
        stanceIds.put("swingP2", 28);
        stanceIds.put("swingT1", 30);
        stanceIds.put("swingT2", 31);
        stanceIds.put("swingT3", 32);
        return Map.copyOf(stanceIds);
    }

    public AttackAnimationSpec getBasicAttackSpec(int attackGroup, WeaponType fallbackWeaponType) {
        return getBasicAttackSpec(attackGroup, fallbackWeaponType, false);
    }

    public AttackAnimationSpec getBasicAttackSpec(int attackGroup, WeaponType fallbackWeaponType, boolean degenerate) {
        if (degenerate) {
            return switch (attackGroup) {
                case 3 -> new AttackAnimationSpec(3, List.of("swingT1", "swingT3"));
                case 4 -> new AttackAnimationSpec(4, List.of("swingT1", "stabT1"));
                case 7 -> new AttackAnimationSpec(7, List.of("stabO1", "stabO2"));
                case 9 -> new AttackAnimationSpec(9, List.of("swingP1", "stabT2"));
                default -> getBasicAttackSpec(fallbackWeaponType, false);
            };
        }

        return switch (attackGroup) {
            case 1 -> new AttackAnimationSpec(1, List.of("stabO1", "stabO2", "swingO1", "swingO2", "swingO3"));
            case 2 -> new AttackAnimationSpec(2, List.of("stabT1", "swingP1"));
            case 3 -> new AttackAnimationSpec(3, List.of("shoot1"));
            case 4 -> new AttackAnimationSpec(4, List.of("shoot2"));
            case 5 -> new AttackAnimationSpec(5, List.of("stabO1", "stabO2", "swingT1", "swingT2", "swingT3"));
            case 6 -> new AttackAnimationSpec(6, List.of("swingO2"));
            case 7 -> new AttackAnimationSpec(7, List.of("swingO1", "swingO2", "swingO3"));
            case 9 -> new AttackAnimationSpec(9, List.of("handgun"));
            default -> getBasicAttackSpec(fallbackWeaponType);
        };
    }

    public AttackAnimationSpec getBasicAttackSpec(WeaponType weaponType) {
        return getBasicAttackSpec(weaponType, false);
    }

    public AttackAnimationSpec getBasicAttackSpec(WeaponType weaponType, boolean degenerate) {
        if (weaponType == null) {
            return new AttackAnimationSpec(1, List.of("stabO1", "stabO2", "swingO1", "swingO2", "swingO3"));
        }
        if (degenerate) {
            return switch (weaponType) {
                case BOW -> new AttackAnimationSpec(3, List.of("swingT1", "swingT3"));
                case CROSSBOW -> new AttackAnimationSpec(4, List.of("swingT1", "stabT1"));
                case CLAW -> new AttackAnimationSpec(7, List.of("stabO1", "stabO2"));
                case GUN -> new AttackAnimationSpec(9, List.of("swingP1", "stabT2"));
                default -> getBasicAttackSpec(weaponType, false);
            };
        }
        return switch (weaponType) {
            case BOW -> new AttackAnimationSpec(3, List.of("shoot1"));
            case CROSSBOW -> new AttackAnimationSpec(4, List.of("shoot2"));
            case SPEAR_SWING, SPEAR_STAB, POLE_ARM_SWING, POLE_ARM_STAB ->
                    new AttackAnimationSpec(2, List.of("stabT1", "swingP1"));
            case GENERAL2H_SWING, GENERAL2H_STAB, SWORD2H ->
                    new AttackAnimationSpec(5, List.of("stabO1", "stabO2", "swingT1", "swingT2", "swingT3"));
            case WAND, STAFF -> new AttackAnimationSpec(6, List.of("swingO2"));
            case CLAW -> new AttackAnimationSpec(7, List.of("swingO1", "swingO2", "swingO3"));
            case GUN -> new AttackAnimationSpec(9, List.of("handgun"));
            default -> new AttackAnimationSpec(1, List.of("stabO1", "stabO2", "swingO1", "swingO2", "swingO3"));
        };
    }

    public int getAttackStanceId(String actionName) {
        return ATTACK_STANCE_IDS.getOrDefault(actionName, 0);
    }

    /**
     * Returns the total animation duration in milliseconds for the given body stance
     * (e.g. "swingO1", "shoot1"), loaded from {@code Character/00002000.img.xml}.
     * This mirrors OpenStory's {@code BodyDrawInfo::get_delay} summed over all stance frames,
     * which is the authoritative timing source used by {@code Char::get_attackdelay}.
     * Returns 0 if the stance is not found.
     */
    public int getBodyStanceDurationMs(String stanceName) {
        BodyStanceTiming timing = getBodyStanceTiming(stanceName);
        return timing != null ? timing.totalDelayMillis() : 0;
    }

    public int getBodyStanceDelayBeforeFrameMs(String stanceName, int firstFrame) {
        BodyStanceTiming timing = getBodyStanceTiming(stanceName);
        if (timing == null || firstFrame <= 0) {
            return 0;
        }

        int delay = 0;
        List<Integer> frameDelays = timing.frameDelays();
        for (int frame = 0; frame < Math.min(firstFrame, frameDelays.size()); frame++) {
            delay += frameDelays.get(frame);
        }
        return delay;
    }

    public int getBodyActionDurationMs(String actionName) {
        BodyActionTiming timing = getBodyActionTiming(actionName);
        return timing != null ? timing.totalDelayMillis() : 0;
    }

    public int getBodyActionAttackDelayMs(String actionName, int attackIndex) {
        BodyActionTiming timing = getBodyActionTiming(actionName);
        if (timing == null || attackIndex < 0 || attackIndex >= timing.attackFrameDelays().size()) {
            return -1;
        }
        return timing.attackFrameDelays().get(attackIndex);
    }

    public int getBodyActionId(String actionName) {
        return getBodyActionId(actionName, null);
    }

    public int getBodyActionId(String actionName, WeaponType weaponType) {
        if (actionName == null || actionName.isBlank()) {
            return -1;
        }

        if (weaponType == WeaponType.CLAW) {
            Integer clawId = CLAW_BODY_ACTION_ID_OVERRIDES.get(actionName);
            if (clawId != null) {
                return clawId;
            }
        }

        if (weaponType == WeaponType.WAND || weaponType == WeaponType.STAFF) {
            Integer wandId = WAND_BODY_ACTION_ID_OVERRIDES.get(actionName);
            if (wandId != null) {
                return wandId;
            }
        }

        ensureCurrentCharacterRoot();
        if (bodyActionIds == null) {
            synchronized (this) {
                if (bodyActionIds == null) {
                    bodyActionIds = loadBodyActionIds();
                }
            }
        }
        return bodyActionIds.getOrDefault(actionName, -1);
    }

    private BodyStanceTiming getBodyStanceTiming(String stanceName) {
        ensureCurrentCharacterRoot();
        if (bodyStanceTimings == null) {
            synchronized (this) {
                if (bodyStanceTimings == null) {
                    bodyStanceTimings = loadBodyStanceTimings();
                }
            }
        }
        return bodyStanceTimings.get(stanceName);
    }

    private BodyActionTiming getBodyActionTiming(String actionName) {
        if (actionName == null || actionName.isBlank()) {
            return null;
        }

        ensureCurrentCharacterRoot();
        if (bodyActionTimings == null) {
            synchronized (this) {
                if (bodyActionTimings == null) {
                    bodyActionTimings = loadBodyActionTimings();
                }
            }
        }
        return bodyActionTimings.get(actionName);
    }

    private void ensureCurrentCharacterRoot() {
        Path currentCharacterRoot = WZFiles.CHARACTER.getFile();
        Path previousCharacterRoot = cachedCharacterRoot;
        if (previousCharacterRoot != null && previousCharacterRoot.equals(currentCharacterRoot)) {
            return;
        }

        synchronized (this) {
            if (cachedCharacterRoot != null && cachedCharacterRoot.equals(currentCharacterRoot)) {
                return;
            }
            normalAttackProfiles.clear();
            bodyStanceTimings = null;
            bodyActionTimings = null;
            bodyActionIds = null;
            cachedCharacterRoot = currentCharacterRoot;
        }
    }

    private Map<String, BodyStanceTiming> loadBodyStanceTimings() {
        Path bodyFile = WZFiles.CHARACTER.getFile().resolve("00002000.img.xml");
        if (!Files.isRegularFile(bodyFile)) {
            log.warn("Bot attack timing: body animation file not found at {}", bodyFile);
            return Map.of();
        }

        Document doc = parseXmlDocument(bodyFile);
        if (doc == null) {
            return Map.of();
        }

        Map<String, BodyStanceTiming> timings = new HashMap<>();
        for (Element stanceEl : getNamedChildren(doc.getDocumentElement())) {
            String stanceName = stanceEl.getAttribute("name");
            if (stanceName.isBlank()) {
                continue;
            }

            List<Integer> frameDelays = new ArrayList<>();
            int totalDelay = 0;
            for (Element frameEl : getNamedChildren(stanceEl)) {
                // Frames with an "action" child are action-redirect frames (no direct delay)
                if (findNamedChild(frameEl, "action") != null) {
                    continue;
                }
                // Default 100 ms matches OpenStory's BodyDrawInfo fallback
                int frameDelay = getIntValue(findNamedChild(frameEl, "delay"), 100);
                frameDelays.add(frameDelay);
                totalDelay += frameDelay;
            }

            if (totalDelay > 0) {
                timings.put(stanceName, new BodyStanceTiming(List.copyOf(frameDelays), totalDelay));
            }
        }

        log.info("Bot attack timing: loaded {} body stances from {}", timings.size(), bodyFile.getFileName());
        return Map.copyOf(timings);
    }

    private Map<String, BodyActionTiming> loadBodyActionTimings() {
        Path bodyFile = WZFiles.CHARACTER.getFile().resolve("00002000.img.xml");
        if (!Files.isRegularFile(bodyFile)) {
            log.warn("Bot skill timing: body animation file not found at {}", bodyFile);
            return Map.of();
        }

        Document doc = parseXmlDocument(bodyFile);
        if (doc == null) {
            return Map.of();
        }

        Map<String, BodyActionTiming> timings = new HashMap<>();
        for (Element actionEl : getNamedChildren(doc.getDocumentElement())) {
            String actionName = actionEl.getAttribute("name");
            if (actionName.isBlank()) {
                continue;
            }

            List<Integer> attackFrameDelays = new ArrayList<>();
            int totalDelay = 0;
            boolean sawActionFrame = false;
            for (Element frameEl : getNamedChildren(actionEl)) {
                if (findNamedChild(frameEl, "action") == null) {
                    continue;
                }

                sawActionFrame = true;
                int signedDelay = getIntValue(findNamedChild(frameEl, "delay"), 0);
                int frameDelay = signedDelay == 0 ? 100 : Math.abs(signedDelay);
                if (signedDelay >= 0) {
                    attackFrameDelays.add(totalDelay);
                }
                totalDelay += frameDelay;
            }

            if (sawActionFrame && totalDelay > 0) {
                timings.put(actionName, new BodyActionTiming(List.copyOf(attackFrameDelays), totalDelay));
            }
        }

        log.info("Bot skill timing: loaded {} body action timings from {}", timings.size(), bodyFile.getFileName());
        return Map.copyOf(timings);
    }

    private Map<String, Integer> loadBodyActionIds() {
        Map<String, Integer> actionIds = new HashMap<>(BODY_ACTION_ID_OVERRIDES);

        Path bodyFile = WZFiles.CHARACTER.getFile().resolve("00002000.img.xml");
        if (!Files.isRegularFile(bodyFile)) {
            log.warn("Bot attack direction ids: body animation file not found at {}", bodyFile);
            return actionIds.isEmpty() ? Map.of() : Map.copyOf(actionIds);
        }

        Document doc = parseXmlDocument(bodyFile);
        if (doc == null) {
            return actionIds.isEmpty() ? Map.of() : Map.copyOf(actionIds);
        }

        int actionId = 0;
        for (Element child : getNamedChildren(doc.getDocumentElement())) {
            String actionName = child.getAttribute("name");
            if (actionName.isBlank() || "info".equals(actionName)) {
                continue;
            }
            actionIds.putIfAbsent(actionName, actionId++);
        }

        log.info("Bot attack direction ids: loaded {} action ids using built-in overrides + {}",
                actionIds.size(), bodyFile.getFileName());
        return Map.copyOf(actionIds);
    }

    public NormalAttackProfile getNormalAttackProfile(int itemId) {
        ensureCurrentCharacterRoot();
        if (normalAttackProfiles.containsKey(itemId)) {
            return normalAttackProfiles.get(itemId);
        }

        NormalAttackProfile profile = loadNormalAttackProfile(itemId);
        normalAttackProfiles.put(itemId, profile);
        return profile;
    }

    private NormalAttackProfile loadNormalAttackProfile(int itemId) {
        Path weaponFile = WZFiles.CHARACTER.getFile()
                .resolve("Weapon")
                .resolve(String.format("%08d.img.xml", itemId));
        if (!Files.isRegularFile(weaponFile)) {
            return null;
        }

        Document weaponDocument = parseXmlDocument(weaponFile);
        if (weaponDocument == null) {
            return null;
        }

        Element weaponRoot = weaponDocument.getDocumentElement();
        Element info = findNamedChild(weaponRoot, "info");
        String afterImage = getStringValue(findNamedChild(info, "afterImage"));
        int attackSpeed = getIntValue(findNamedChild(info, "attackSpeed"), 0);
        int attack = getIntValue(findNamedChild(info, "attack"), 0);
        int reqLevel = getIntValue(findNamedChild(info, "reqLevel"), 0);

        // Actions come from the weapon XML — the afterimage only covers a subset (e.g. swordOL
        // bucket 0 has swingO1-swingOF/stabO1 but omits stabO2, stabOF, proneStab).
        List<String> weaponActions = loadWeaponActions(weaponRoot);

        AttackBoundsData afterImageData = loadAfterimageBounds(afterImage, reqLevel);
        if (afterImageData == null) {
            // Ranged weapons have no hitbox in afterimage (normal) — bounds stay null.
            return new NormalAttackProfile(attackSpeed, attack, afterImage, null, Map.of(), weaponActions, Map.of());
        }

        // Use afterimage for hit bounds (cleaner per-level data), weapon XML for action list.
        return new NormalAttackProfile(attackSpeed, attack, afterImage, afterImageData.bounds,
                afterImageData.boundsByAction, weaponActions, afterImageData.firstFramesByAction);

    }

    private List<String> loadWeaponActions(Element weaponRoot) {
        List<String> actions = new ArrayList<>();
        for (Element child : getNamedChildren(weaponRoot)) {
            String name = child.getAttribute("name");
            if (!name.isBlank() && !"info".equals(name) && BODY_ACTION_ID_OVERRIDES.containsKey(name)) {
                actions.add(name);
            }
        }
        return List.copyOf(actions);
    }

    private AttackBoundsData loadAfterimageBounds(String afterImage, int reqLevel) {
        if (afterImage == null || afterImage.isBlank()) {
            return null;
        }

        Path afterimageFile = WZFiles.CHARACTER.getFile()
                .resolve("Afterimage")
                .resolve(afterImage + ".img.xml");
        if (!Files.isRegularFile(afterimageFile)) {
            return null;
        }

        Document afterimageDocument = parseXmlDocument(afterimageFile);
        if (afterimageDocument == null) {
            return null;
        }

        Element root = afterimageDocument.getDocumentElement();
        Element levelBucket = findBestLevelBucket(root, reqLevel / 10);
        if (levelBucket == null) {
            return null;
        }

        Rectangle bounds = null;
        Set<String> actionNames = new LinkedHashSet<>();
        Map<String, Rectangle> boundsByAction = new HashMap<>();
        Map<String, Integer> firstFramesByAction = new HashMap<>();
        for (Element action : getNamedChildren(levelBucket)) {
            Element lt = findNamedChild(action, "lt");
            Element rb = findNamedChild(action, "rb");
            if (lt == null || rb == null) {
                continue;
            }

            Rectangle actionBounds = toRightFacingBounds(lt, rb);
            if (actionBounds == null) {
                continue;
            }

            bounds = bounds == null ? new Rectangle(actionBounds) : bounds.union(actionBounds);
            String actionName = action.getAttribute("name");
            actionNames.add(actionName);
            boundsByAction.merge(actionName, actionBounds, Rectangle::union);
            firstFramesByAction.put(actionName, findAfterimageFirstFrame(action));
        }

        if (bounds == null) {
            return null;
        }

        return new AttackBoundsData(bounds, boundsByAction, new ArrayList<>(actionNames), firstFramesByAction);
    }

    private Element findBestLevelBucket(Element root, int requestedBucket) {
        Element fallback = null;
        int bestBucket = Integer.MIN_VALUE;

        for (Element child : getNamedChildren(root)) {
            int bucket = parseInt(child.getAttribute("name"), Integer.MIN_VALUE);
            if (bucket == Integer.MIN_VALUE) {
                continue;
            }

            if (bucket == requestedBucket) {
                return child;
            }

            if (bucket <= requestedBucket && bucket > bestBucket) {
                bestBucket = bucket;
                fallback = child;
            }
        }

        return fallback;
    }

    private Rectangle toRightFacingBounds(Element lt, Element rb) {
        int leftX = getIntAttribute(lt, "x", 0);
        int topY = getIntAttribute(lt, "y", 0);
        int rightX = getIntAttribute(rb, "x", 0);
        int bottomY = getIntAttribute(rb, "y", 0);
        if (topY >= bottomY) {
            return null;
        }

        int normalizedLeft = Math.min(-rightX, -leftX);
        int normalizedRight = Math.max(-rightX, -leftX);
        if (normalizedLeft == normalizedRight) {
            return null;
        }

        return new Rectangle(normalizedLeft, topY, normalizedRight - normalizedLeft, bottomY - topY);
    }

    private int findAfterimageFirstFrame(Element action) {
        int bestFrame = 0;
        boolean found = false;

        for (Element child : getNamedChildren(action)) {
            int frame = parseInt(child.getAttribute("name"), Integer.MIN_VALUE);
            if (frame == Integer.MIN_VALUE) {
                continue;
            }

            if (!found || frame > bestFrame) {
                bestFrame = frame;
                found = true;
            }
        }

        return found ? bestFrame : 0;
    }

    private Element resolveRelativePath(Element start, String path) {
        Element current = start;
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }

            if ("..".equals(segment)) {
                current = getParentElement(current);
            } else {
                current = findNamedChild(current, segment);
            }

            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Document parseXmlDocument(Path path) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(path.toFile());
        } catch (ParserConfigurationException | SAXException | IOException e) {
            log.warn("Failed to load bot attack data from {}", path, e);
            return null;
        }
    }

    private static Element getParentElement(Element element) {
        Node parent = element.getParentNode();
        while (parent != null && parent.getNodeType() != Node.ELEMENT_NODE) {
            parent = parent.getParentNode();
        }
        return parent instanceof Element ? (Element) parent : null;
    }

    private static List<Element> getNamedChildren(Element parent) {
        List<Element> children = new ArrayList<>();
        if (parent == null) {
            return children;
        }

        Node child = parent.getFirstChild();
        while (child != null) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) child);
            }
            child = child.getNextSibling();
        }
        return children;
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String getStringValue(Element element) {
        if (element == null || !element.hasAttribute("value")) {
            return null;
        }
        return element.getAttribute("value");
    }

    private static int sumActionDelayMillis(Element action) {
        if (action == null) {
            return 0;
        }

        int totalDelay = 0;
        for (Element child : getNamedChildren(action)) {
            if ("int".equals(child.getTagName()) && "delay".equals(child.getAttribute("name"))) {
                totalDelay += getIntAttribute(child, "value", 0);
                continue;
            }
            totalDelay += sumActionDelayMillis(child);
        }
        return totalDelay;
    }

    private static int averageDelay(int totalDelay, int delayedActions) {
        if (totalDelay <= 0 || delayedActions <= 0) {
            return 0;
        }
        return Math.max(1, totalDelay / delayedActions);
    }
}
