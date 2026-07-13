package server.bots;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;

/**
 * Idle-time chair sitting — a bit of humanlike loitering flavor. When a bot is parked and mostly idling
 * (a town rest break, or standing to shout-sell in the FM) it may plop into a chair it happens to OWN,
 * the way a real player rests on a relaxer while shouting or waiting. Chairs enter a bot's SETUP
 * inventory organically (gachapon pulls), so this fires emergently for bots that gamble, not from any
 * hand-out.
 *
 * <p>Two {@link BotPersonality} knobs shape it: {@link BotPersonality#sitAppetite()} is the per-idle sit
 * probability (some bots almost never sit, some sit at every chance), and
 * {@link BotPersonality#obnoxiousness()} biases the CHOICE toward the biggest-looking chair the bot owns
 * (an obnoxious player hogs the fattest seat; see {@link ItemInformationProvider#getChairSpriteArea}).
 *
 * <p>Sitting is entered only from those two controlled idle branches and the bot always stands before it
 * does anything active (the break ends, an errand travels, it logs out) — {@link #standIfSeated} is the
 * belt-and-suspenders for every such transition.
 */
final class BotChairManager {

    private BotChairManager() {}

    /** Sit-sprite footprint per chair id (WZ read once, then cached — the pool is tiny and stable). */
    private static final Map<Integer, Integer> SIZE_CACHE = new ConcurrentHashMap<>();

    /** Chair id -> sit-sprite area (px^2); the WZ-backed size lookup, swappable for tests. */
    static java.util.function.IntUnaryOperator chairSize =
            id -> Math.max(0, ItemInformationProvider.getInstance().getChairSpriteArea(id));

    /** How long one sit lasts before the bot stands and re-decides (jittered). */
    private static final int SIT_MIN_MS = 20_000;
    private static final int SIT_MAX_MS = 90_000;
    /** Cooldown between sit rolls while idling (so a "no" doesn't re-roll every tick). */
    private static final int ROLL_MIN_MS = 15_000;
    private static final int ROLL_MAX_MS = 45_000;
    /** Longer wait before re-checking when the bot owns no chair at all (bag rarely changes at idle). */
    private static final int NO_CHAIR_RECHECK_MS = 5 * 60_000;
    /** How hard obnoxiousness skews the chair pick toward the biggest sprite (exponent on the area). */
    private static final double SIZE_BIAS_MAX = 3.0;

    /** Chair item ids the bot currently owns in its SETUP inventory (fishing chairs excluded — sitting
     *  on one starts the fishing minigame, not idle flavor). */
    static List<Integer> ownedChairs(Character bot) {
        List<Integer> out = new ArrayList<>();
        Inventory setup = bot.getInventory(InventoryType.SETUP);
        if (setup == null) {
            return out;
        }
        for (Item it : setup.list()) {
            int id = it.getItemId();
            if (ItemId.isChair(id) && !ItemConstants.isFishingChair(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static int chairSpriteArea(int itemId) {
        return SIZE_CACHE.computeIfAbsent(itemId, chairSize::applyAsInt);
    }

    /** Test hook: drop the sprite-size cache so a swapped {@link #chairSize} is re-read. */
    static void clearSizeCacheForTest() {
        SIZE_CACHE.clear();
    }

    /**
     * Pick which owned chair to sit on. A non-obnoxious bot picks at random; the more obnoxious it is,
     * the more the roulette weight tilts toward the biggest-looking chair (area^k, k growing with
     * obnoxiousness) — it wants to be seen hogging the fattest seat. -1 when it owns none.
     */
    static int chooseChair(List<Integer> chairs, double obnoxiousness) {
        if (chairs.isEmpty()) {
            return -1;
        }
        if (chairs.size() == 1) {
            return chairs.get(0);
        }
        double k = SIZE_BIAS_MAX * clamp01(obnoxiousness); // 0 -> uniform, high -> the biggest seat
        double[] w = new double[chairs.size()];
        double total = 0;
        for (int i = 0; i < chairs.size(); i++) {
            int area = Math.max(1, chairSpriteArea(chairs.get(i)));
            w[i] = k <= 0 ? 1.0 : Math.pow(area, k);
            total += w[i];
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (int i = 0; i < chairs.size(); i++) {
            roll -= w[i];
            if (roll < 0) {
                return chairs.get(i);
            }
        }
        return chairs.get(chairs.size() - 1);
    }

    /**
     * Idle-sit lifecycle. Call each tick while the bot is settled and idling in a sit-eligible context
     * (town break / chill / inert town idle / FM shout stand). Returns true while the bot is parked in a
     * chair and the caller should just hold (skip the idle-move broadcast, which would fight the sit
     * pose); false to idle normally on its feet.
     *
     * <p>Deliberately NOT gated on observation. Sitting is a persistent state (a chair field carried in
     * the spawn packet — see {@code PacketCreator.spawnPlayerMapObject}), so a bot must roll to sit even
     * while its map is empty of real players; otherwise the moment a player walks in they'd find every
     * bot standing and only trickling into chairs afterward, instead of the steady-state fraction already
     * seated. The sit/stand broadcasts are no-ops on an unobserved map, so this is nearly free there.
     */
    static boolean tickIdleSit(BotEntry entry, Character bot, long now) {
        if (bot.getChair() >= 0) {
            // Already seated: hold until the dwell elapses, then stand and cool down before the next sit
            // decision so it alternates sitting/standing like a person. Trades run fine from the chair (no
            // server stance gate) and a hit knocks it out via applyDamage, so neither forces a stand here.
            if (now < entry.chairSitUntilMs) {
                return true;
            }
            standIfSeated(bot);
            entry.nextChairRollAtMs = now + BotManager.randMs(ROLL_MIN_MS, ROLL_MAX_MS);
            return false;
        }
        if (now < entry.nextChairRollAtMs || bot.getMap() == null) {
            return false; // just cooling down between sit decisions
        }
        BotPersonality p = entry.personality != null ? entry.personality : BotPersonality.defaults();
        entry.nextChairRollAtMs = now + BotManager.randMs(ROLL_MIN_MS, ROLL_MAX_MS);
        if (ThreadLocalRandom.current().nextDouble() >= p.sitAppetite()) {
            return false; // this idle window, chose to keep standing
        }
        int chair = chooseChair(ownedChairs(bot), p.obnoxiousness());
        if (chair < 0) {
            entry.nextChairRollAtMs = now + NO_CHAIR_RECHECK_MS; // owns none — don't rescan constantly
            return false;
        }
        bot.sitChair(chair);
        if (bot.getChair() < 0) {
            return false; // sit refused for some reason — just idle normally
        }
        entry.chairSitUntilMs = now + BotManager.randMs(SIT_MIN_MS, SIT_MAX_MS);
        return true;
    }

    /** Stand the bot up if it is parked in a chair. Cheap no-op otherwise; safe to call from any
     *  transition that precedes real activity (break end, errand travel, logout). */
    static void standIfSeated(Character bot) {
        if (bot != null && bot.getChair() >= 0) {
            bot.sitChair(-1);
        }
    }

    /** Cooldown before re-sitting after a hit knocked the bot off its chair — it stays on its feet a
     *  bit, like a spooked player, instead of plopping straight back down mid-fight. */
    private static final int HIT_RESIT_MIN_MS = 20_000;
    private static final int HIT_RESIT_MAX_MS = 60_000;

    /**
     * A hit knocks the bot out of its chair, exactly like a real player: stand up so normal physics /
     * knockback resumes from a standing pose, and hold off the next sit decision for a bit. No-op when
     * the bot isn't seated. Sitting never changed the touch hitbox in the first place
     * (see {@code BotCombatManager.getBotTouchBounds} — foot position only), so this is presentation +
     * anti-replop, not a vulnerability fix.
     */
    static void knockOutOfChair(BotEntry entry, Character bot, long now) {
        if (bot == null || bot.getChair() < 0) {
            return;
        }
        standIfSeated(bot);
        entry.chairSitUntilMs = 0L;
        entry.nextChairRollAtMs = now + BotManager.randMs(HIT_RESIT_MIN_MS, HIT_RESIT_MAX_MS);
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(1.0, v);
    }
}
