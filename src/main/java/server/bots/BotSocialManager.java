package server.bots;

import client.BotClient;
import client.Character;
import config.YamlConfig;
import server.maps.MapleMap;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ad-hoc bot party-up (P4 dynamic). Once in a while a SOLO self-owned autopilot bot, co-located with
 * another solo bot, offers to party. Whether it offers (sociability x chattiness), whether the other
 * accepts (sociability + the exp-share level window), and whether either speaks is all trait-driven
 * ({@link BotSocialMath}/{@link BotPersonality}) — so some bots constantly group up, some never do.
 *
 * <p>Tick-driven, NOT chat-response-parsing: the offer / accept / decline LINES are cosmetic flavor
 * (US-ASCII), but the party itself is formed through the server-side SSOT ({@link BotManager#partyUp}
 * + {@link BotAutopilotManager#startParty} so the new crew grinds together). Self-owned autopilot bots
 * only: it never touches a player's companion and never grants ownership. Gated by
 * {@code cfg.SOCIAL_PARTY_ENABLED} and a per-bot cooldown; harmless when a bot is alone on its map.
 */
final class BotSocialManager {

    private static final long SOCIAL_COOLDOWN_MS = 3 * 60_000L;

    private static final List<String> OFFER_MSGS = List.of(
            "hey %s, wanna party?", "%s party up?", "yo %s wanna duo?", "%s lets team up", "wanna group %s?");
    private static final List<String> ACCEPT_MSGS = List.of(
            "sure lets go", "ok im in", "yeah lets party", "sounds good", "lets do it");
    private static final List<String> DECLINE_MSGS = List.of(
            "nah im good", "soloing rn", "maybe later", "no thanks", "ill pass");

    private BotSocialManager() {}

    /** Common-tick hook: self-gates on the cooldown, so it's cheap to call every tick. */
    static void tick(BotEntry entry, Character bot) {
        if (!BotManager.cfg.SOCIAL_PARTY_ENABLED || entry == null || bot == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < entry.nextSocialAtMs) {
            return;
        }
        entry.nextSocialAtMs = now + SOCIAL_COOLDOWN_MS + BotManager.randMs(0, (int) SOCIAL_COOLDOWN_MS);
        if (!soloAutopilot(entry, bot)
                || !BotSocialMath.shouldInitiate(personality(entry), false, ThreadLocalRandom.current().nextDouble())) {
            return;
        }
        BotEntry target = findCandidate(bot);
        if (target != null) {
            offerParty(entry, bot, target, target.bot);
        }
    }

    /** A solo (party-less), self-owned, actively-autopiloting bot on a live map. */
    private static boolean soloAutopilot(BotEntry entry, Character bot) {
        return (entry.owner == null || entry.owner == bot)
                && BotAutopilotManager.isActive(entry)
                && bot.getMap() != null
                && bot.getParty() == null;
    }

    /** Another solo self-owned autopilot bot on the same map, or null. */
    private static BotEntry findCandidate(Character bot) {
        MapleMap map = bot.getMap();
        for (Character c : map.getCharacters()) {
            if (c == bot || !(c.getClient() instanceof BotClient) || c.getParty() != null) {
                continue;
            }
            BotEntry e = BotManager.getInstance().getEntryByBotCharId(c.getId());
            if (e != null && soloAutopilot(e, c)) {
                return e;
            }
        }
        return null;
    }

    private static void offerParty(BotEntry initiator, Character bot, BotEntry targetEntry, Character target) {
        BotManager bm = BotManager.getInstance();
        if (ThreadLocalRandom.current().nextDouble() < personality(initiator).chattiness()) {
            bm.botSay(bot, String.format(BotManager.randomReply(OFFER_MSGS), target.getName()));
        }
        // Don't let the just-offered-to bot immediately fire its own overture back.
        targetEntry.nextSocialAtMs = System.currentTimeMillis() + SOCIAL_COOLDOWN_MS;

        int levelGap = Math.abs(bot.getLevel() - target.getLevel());
        int shareWindow = YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        BotSocialMath.Response resp = BotSocialMath.respondToOffer(personality(targetEntry),
                levelGap, shareWindow, rng.nextDouble(), rng.nextDouble(), rng.nextDouble());

        if (resp == BotSocialMath.Response.ACCEPT) {
            bm.partyUp(bot, target); // initiator leads the new party
            initiator.autopilotParty = true;
            targetEntry.autopilotParty = true;
            BotManager.after(BotManager.randMs(700, 1300), () -> {
                if (ThreadLocalRandom.current().nextDouble() < personality(targetEntry).chattiness()) {
                    bm.botSay(target, BotManager.randomReply(ACCEPT_MSGS));
                }
                BotAutopilotManager.startParty(bot, List.of(initiator, targetEntry));
            });
        } else if (resp == BotSocialMath.Response.DECLINE) {
            BotManager.after(BotManager.randMs(700, 1300),
                    () -> bm.botSay(target, BotManager.randomReply(DECLINE_MSGS)));
        }
        // IGNORE: stays silent.
    }

    /**
     * Whether a bot accepts a party invite from {@code inviter} (the {@code PartyOperationHandler}
     * BotClient branch, via {@link BotManager#acceptsPartyInvite}). An OWNED companion accepts ONLY its
     * registered owner — a stranger can never yank it. A self-owned autopilot bot accepts per its
     * sociability + the exp-share level window (same policy as a bot-to-bot offer). Joining a party
     * never grants the inviter any ownership/command/loot/supply/trade privilege.
     */
    static boolean acceptsInvite(BotEntry entry, Character bot, Character inviter) {
        if (entry == null || bot == null || inviter == null) {
            return false;
        }
        if (entry.owner != null && entry.owner != bot) {
            return inviter.getId() == entry.owner.getId(); // companion: registered owner only
        }
        if (!BotManager.cfg.SOCIAL_PARTY_ENABLED) {
            return false;
        }
        int levelGap = Math.abs(bot.getLevel() - inviter.getLevel());
        int shareWindow = YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return BotSocialMath.respondToOffer(personality(entry), levelGap, shareWindow,
                rng.nextDouble(), rng.nextDouble(), rng.nextDouble()) == BotSocialMath.Response.ACCEPT;
    }

    private static BotPersonality personality(BotEntry e) {
        return e.personality != null ? e.personality : BotPersonality.defaults();
    }
}
