package server;

import client.Character;
import client.SkinColor;
import client.Stat;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies a randomized appearance (hair / face / skin) to a {@link Character}, validating every
 * candidate id against WZ data before it is sent. An appearance id that doesn't exist in the
 * client's WZ crashes the client on render, so each pick falls back to the current look when the
 * random candidate is missing.
 *
 * Used by the Random Beauty Coupon ({@link constants.id.ItemId#RANDOM_BEAUTY_COUPON}). The bot
 * "style" command keeps its own equivalent copy in {@code server.bots.BotChatManager} (which also
 * shares the arrays with the bot gender-change command).
 */
public final class RandomStyle {

    private static final int[] MALE_HAIR = {
            30010, 30040, 30060, 30070, 30080, 30090, 30100, 30130, 30140,
            30200, 30210, 30230, 30260, 30280, 30310, 30340, 30480, 30490,
            30560, 30690, 30760, 30780, 30850, 30860, 30890, 30920, 30930, 30950};
    private static final int[] FEMALE_HAIR = {
            31020, 31090, 31110, 31130, 31140, 31150, 31220, 31230, 31300,
            31330, 31350, 31440, 31510, 31530, 31630, 31700, 31740, 31760,
            31790, 31820, 31860, 31880, 31890, 31920, 31940, 31950};
    private static final int[] MALE_FACE = {
            20000, 20001, 20003, 20004, 20005, 20006, 20007, 20008, 20012, 20014, 20015, 20022, 20028, 20031};
    private static final int[] FEMALE_FACE = {
            21000, 21001, 21002, 21003, 21004, 21005, 21006, 21007, 21008, 21012, 21013, 21014, 21023, 21026};
    private static final int[] SKIN_COLORS = {0, 1, 2, 3, 4, 5, 9, 10, 11};

    private RandomStyle() {
    }

    /** Randomizes the character's hair, face and skin, then broadcasts the new look. */
    public static void apply(Character chr) {
        if (chr == null) {
            return;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        boolean male = chr.getGender() == 0;
        int[] hairs = male ? MALE_HAIR : FEMALE_HAIR;
        int[] faces = male ? MALE_FACE : FEMALE_FACE;

        int hair = pickValidHair(hairs[rng.nextInt(hairs.length)], rng.nextInt(8), chr.getHair());
        int face = pickValid(faces[rng.nextInt(faces.length)], chr.getFace());
        int skin = SKIN_COLORS[rng.nextInt(SKIN_COLORS.length)];
        SkinColor sc = SkinColor.getById(skin);

        chr.setHair(hair);
        chr.updateSingleStat(Stat.HAIR, hair);
        chr.setFace(face);
        chr.updateSingleStat(Stat.FACE, face);
        if (sc != null) {
            chr.setSkinColor(sc);
            chr.updateSingleStat(Stat.SKIN, skin);
        }
        chr.equipChanged();
    }

    /** Prefer the color variant if it exists, else the base style, else keep the current look. */
    private static int pickValidHair(int base, int color, int fallback) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ii.getName(base + color) != null) {
            return base + color;
        }
        return ii.getName(base) != null ? base : fallback;
    }

    private static int pickValid(int candidate, int fallback) {
        return ItemInformationProvider.getInstance().getName(candidate) != null ? candidate : fallback;
    }
}
