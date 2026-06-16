package server.bots;

import client.creator.MakeCharInfo;
import client.creator.MakeCharInfoValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks a random but always-legal Beginner appearance for a freshly created bot.
 *
 * <p>Legal ids are NOT hardcoded here. They come from the same WZ-loaded sets the real
 * character-creation validator checks against ({@link MakeCharInfo} via
 * {@link MakeCharInfoValidator#beginnerInfo(boolean)} — SSOT: {@code Etc.wz/MakeCharInfo.img}
 * {@code Info/CharMale} and {@code Info/CharFemale}). The picker logic is pure and operates on
 * plain id sets so it can be unit-tested without loading WZ.
 *
 * <p>Hair ids are stored in WZ as base ids (color stripped); the final hair id worn by a character
 * is {@code base + colorDigit}. The validator enforces exactly this split
 * ({@code verifyHairId} strips {@code %10}, {@code verifyHairColorId} checks {@code %10}).
 */
public final class BotAppearance {
    /** gender: 0 = male, 1 = female (Character.setGender convention). */
    public final int gender;
    public final int face;
    /** Final hair id (base + color), ready for Character.setHair. */
    public final int hair;
    public final int skin;

    public BotAppearance(int gender, int face, int hair, int skin) {
        this.gender = gender;
        this.face = face;
        this.hair = hair;
        this.skin = skin;
    }

    /**
     * Pure picker: draws a legal appearance from the supplied pools. Gender is chosen first, then a
     * face/hair/hair-color/skin legal for that gender. The caller supplies gender-correct pools.
     *
     * @param male true for the male pools, false for female
     */
    public static BotAppearance pick(boolean male, Set<Integer> faces, Set<Integer> hairBases,
                                      Set<Integer> hairColors, Set<Integer> skins) {
        int face = randomOf(faces);
        int hairBase = randomOf(hairBases);
        int hairColor = randomOf(hairColors);
        int skin = randomOf(skins);
        return new BotAppearance(male ? 0 : 1, face, hairBase + hairColor, skin);
    }

    /**
     * Production entry point: rolls gender, then draws a legal look from the WZ char-creation pools
     * for that gender.
     */
    public static BotAppearance random() {
        boolean male = ThreadLocalRandom.current().nextBoolean();
        MakeCharInfo info = MakeCharInfoValidator.beginnerInfo(male);
        return pick(male, info.getFaces(), info.getHairs(), info.getHairColors(), info.getSkins());
    }

    private static int randomOf(Set<Integer> pool) {
        List<Integer> list = new ArrayList<>(pool);
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }
}
