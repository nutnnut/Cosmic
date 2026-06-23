package server.bots;

import client.BotClient;
import client.Character;
import config.YamlConfig;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.Party;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

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
    /** Flow 1: how long a bot waits for the asked player to say "yes" before the offer lapses. */
    private static final long ASK_WINDOW_MS = 15_000L;
    /** Flow 3: per-bot throttle so a player spamming "party" can't trigger a burst of invites. */
    private static final long PLAYER_REPLY_COOLDOWN_MS = 8_000L;

    // ponytail: fixed alias regex (known ceiling — no fuzzy/LLM parse). US-ASCII only.
    private static final Pattern PARTY_REQUEST_PATTERN = Pattern.compile(
            "^\\s*(pt|party|party\\s*up|p2|lfp|lf\\s*party|lfg|invite\\s*me|inv\\s*me|invite|inv|"
                    + "join|join\\s*you|can\\s*i\\s*join|lemme\\s*join|let\\s*me\\s*join|group|group\\s*up)"
                    + "\\s*[?!.]*\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AFFIRMATIVE_PATTERN = Pattern.compile(
            "^\\s*(yes|ya|yea|yeah|yep|yup|sure|ok|okay|k|y|im\\s*in|i'?m\\s*in|lets\\s*go|let'?s\\s*go|"
                    + "sounds\\s*good|sg|down)\\s*[?!.]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> OFFER_MSGS = List.of(
            "hey %s, wanna party?", "%s party up?", "yo %s wanna duo?", "%s lets team up", "wanna group %s?");
    private static final List<String> ACCEPT_MSGS = List.of(
            "sure lets go", "ok im in", "yeah lets party", "sounds good", "lets do it");
    private static final List<String> DECLINE_MSGS = List.of(
            "nah im good", "soloing rn", "maybe later", "no thanks", "ill pass");
    private static final List<String> INVITE_PLAYER_MSGS = List.of(
            "hey %s, wanna party up?", "%s wanna group?", "yo %s, party?", "%s lets duo!",
            "wanna team up %s?", "%s join me? sent an invite");

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
        } else if (BotManager.cfg.SOCIAL_INVITE_PLAYERS) {
            Character player = findPlayerCandidate(bot);
            if (player != null) {
                offerToPlayer(entry, bot, player);
            }
        }
    }

    /**
     * Proactive overture to a real player, mode chosen by personality:
     * <ul>
     *   <li><b>Flow 1 (ask-then-wait)</b> — a sociable, chatty bot says "wanna party?" and waits for
     *       an affirmative reply (handled in {@link #completeAskedInvite}); the invite fires only on
     *       a "yes" within {@link #ASK_WINDOW_MS}.</li>
     *   <li><b>Flow 2 (unprompted)</b> — a quieter bot just sends the party invite (optional flavor
     *       line), no waiting.</li>
     * </ul>
     */
    private static void offerToPlayer(BotEntry entry, Character bot, Character player) {
        BotPersonality p = personality(entry);
        double askMode = p.sociability() * p.chattiness();
        if (ThreadLocalRandom.current().nextDouble() < askMode) {
            // Flow 1: ask first, invite only once they say yes.
            BotManager.getInstance().botSay(bot,
                    String.format(BotManager.randomReply(INVITE_PLAYER_MSGS), player.getName()));
            entry.pendingPartyAskPlayerId = player.getId();
            entry.pendingPartyAskUntilMs = System.currentTimeMillis() + ASK_WINDOW_MS;
        } else {
            // Flow 2: unprompted invite (speak only if chatty enough).
            if (ThreadLocalRandom.current().nextDouble() < p.chattiness()) {
                BotManager.getInstance().botSay(bot,
                        String.format(BotManager.randomReply(INVITE_PLAYER_MSGS), player.getName()));
            }
            sendPartyInvite(bot, player);
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

    /** A co-located, party-less REAL player within the exp-share level window, or null. */
    private static Character findPlayerCandidate(Character bot) {
        MapleMap map = bot.getMap();
        int shareWindow = YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL;
        for (Character c : map.getCharacters()) {
            if (c == bot || c.getClient() instanceof BotClient || c.getParty() != null) {
                continue;
            }
            if (Math.abs(bot.getLevel() - c.getLevel()) <= shareWindow) {
                return c;
            }
        }
        return null;
    }

    /**
     * Send a REAL party invite from {@code bot} to {@code player} (the player accepts/declines through
     * the normal UI — never auto-joined). Reuses the bot's party if it has room, else creates one; an
     * un-taken freshly-created party is disbanded by {@link #scheduleLonePartyCleanup} so a declined
     * invite doesn't strand the bot out of "solo" state. Shared by Flow 1 (ask-then-yes), Flow 2
     * (unprompted), and Flow 3 (player-requested). Returns whether an invite was sent.
     */
    private static boolean sendPartyInvite(Character bot, Character player) {
        if (bot.getClient() == null || player.getParty() != null) {
            return false;
        }
        Party party = bot.getParty();
        if (party == null) {
            if (!Party.createParty(bot, true)) {
                return false;
            }
            party = bot.getParty();
        }
        if (party == null || party.getMembers().size() >= 6) {
            return false;
        }
        int partyId = party.getId();
        if (InviteCoordinator.createInvite(InviteType.PARTY, bot, partyId, player.getId())) {
            player.sendPacket(PacketCreator.partyInvite(bot));
            scheduleLonePartyCleanup(bot, partyId);
            return true;
        }
        return false;
    }

    /**
     * Non-owner party-chat chokepoint, called from {@link BotManager#handleChat} before the
     * owner-scoped routing (so a nearby player who doesn't OWN the bot can still reach it). Two cases:
     * <ol>
     *   <li>an affirmative reply that completes a bot's pending Flow-1 ask to this speaker, or</li>
     *   <li>a fresh party request ("pt"/"party"/"invite me") → Flow 3.</li>
     * </ol>
     * Returns true if it consumed the message (so the caller stops). Self-owned bots only; gated by
     * {@code SOCIAL_PARTY_ENABLED}.
     */
    static boolean maybeHandlePartyChat(Character speaker, String message) {
        if (!BotManager.cfg.SOCIAL_PARTY_ENABLED || speaker == null || message == null
                || speaker.getClient() instanceof BotClient || speaker.getMap() == null) {
            return false;
        }
        if (isAffirmative(message) && completeAskedInvite(speaker)) {
            return true;
        }
        if (isPartyRequest(message)) {
            return handlePlayerPartyRequest(speaker);
        }
        return false;
    }

    /** "pt"/"party"/"invite me"/... — a player asking to join (Flow 3 trigger). */
    static boolean isPartyRequest(String message) {
        return message != null && PARTY_REQUEST_PATTERN.matcher(message).matches();
    }

    /** "yes"/"ok"/"sure"/... — an affirmative completing a bot's Flow-1 ask. */
    static boolean isAffirmative(String message) {
        return message != null && AFFIRMATIVE_PATTERN.matcher(message).matches();
    }

    /**
     * Flow 1 completion: if a co-located self-owned bot asked this speaker to party and is still inside
     * its ask window, the "yes" fires the actual invite. Clears the pending ask either way. Returns
     * whether an ask was matched (so a generic "ok" isn't swallowed when no bot is waiting on it).
     */
    private static boolean completeAskedInvite(Character speaker) {
        MapleMap map = speaker.getMap();
        long now = System.currentTimeMillis();
        for (Character c : map.getCharacters()) {
            if (c == speaker || !(c.getClient() instanceof BotClient)) {
                continue;
            }
            BotEntry e = BotManager.getInstance().getEntryByBotCharId(c.getId());
            if (e == null || e.pendingPartyAskPlayerId != speaker.getId()) {
                continue;
            }
            boolean live = now < e.pendingPartyAskUntilMs;
            e.pendingPartyAskPlayerId = 0;
            e.pendingPartyAskUntilMs = 0L;
            if (live && (e.owner == null || e.owner == c)) {
                sendPartyInvite(c, speaker);
            }
            return true;
        }
        return false;
    }

    /**
     * Flow 3: a real player asked to party. Pick the nearest eligible self-owned bot on their map and
     * let personality decide — ACCEPT sends a real invite (+ optional flavor line), DECLINE speaks a
     * refusal, IGNORE stays silent. "Any self-owned bot" includes one already partied WITH ROOM (it
     * invites the player into its existing party). A short per-bot cooldown throttles spam. Returns
     * true once a bot has taken the request (even on decline/ignore) so the chat is consumed.
     */
    private static boolean handlePlayerPartyRequest(Character player) {
        if (player.getParty() != null) {
            return false; // already partied — they'd invite the bot via the UI instead
        }
        BotEntry chosen = nearestEligibleBot(player);
        if (chosen == null) {
            return false;
        }
        Character bot = chosen.bot;
        long now = System.currentTimeMillis();
        chosen.nextPlayerPartyReplyAtMs = now + PLAYER_REPLY_COOLDOWN_MS;

        int levelGap = Math.abs(bot.getLevel() - player.getLevel());
        int shareWindow = YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        BotSocialMath.Response resp = BotSocialMath.respondToOffer(personality(chosen), levelGap,
                shareWindow, rng.nextDouble(), rng.nextDouble(), rng.nextDouble());

        BotManager bm = BotManager.getInstance();
        if (resp == BotSocialMath.Response.ACCEPT) {
            BotManager.after(BotManager.randMs(600, 1200), () -> {
                if (ThreadLocalRandom.current().nextDouble() < personality(chosen).chattiness()) {
                    bm.botSay(bot, BotManager.randomReply(ACCEPT_MSGS));
                }
                sendPartyInvite(bot, player);
            });
        } else if (resp == BotSocialMath.Response.DECLINE) {
            BotManager.after(BotManager.randMs(600, 1200),
                    () -> bm.botSay(bot, BotManager.randomReply(DECLINE_MSGS)));
        }
        return true;
    }

    /** Nearest off-cooldown self-owned autopilot bot in the player's exp-share level window with party
     *  room (solo, or partied with < 6), or null. */
    private static BotEntry nearestEligibleBot(Character player) {
        MapleMap map = player.getMap();
        int shareWindow = YamlConfig.config.server.EXP_SPLIT_LEECH_INTERVAL;
        long now = System.currentTimeMillis();
        BotEntry best = null;
        double bestDist = Double.MAX_VALUE;
        for (Character c : map.getCharacters()) {
            if (c == player || !(c.getClient() instanceof BotClient)) {
                continue;
            }
            BotEntry e = BotManager.getInstance().getEntryByBotCharId(c.getId());
            if (e == null || (e.owner != null && e.owner != c) || !BotAutopilotManager.isActive(e)
                    || now < e.nextPlayerPartyReplyAtMs) {
                continue;
            }
            Party party = c.getParty();
            if (party != null && party.getMembers().size() >= 6) {
                continue;
            }
            if (Math.abs(c.getLevel() - player.getLevel()) > shareWindow) {
                continue;
            }
            double d = c.getPosition().distanceSq(player.getPosition());
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    /** Disband the bot's just-created party if the player never joined, so the bot returns to solo. */
    private static void scheduleLonePartyCleanup(Character bot, int partyId) {
        BotManager.after(40_000L, () -> {
            Party p = bot.getParty();
            if (p != null && p.getId() == partyId && p.getMembers().size() <= 1 && bot.getClient() != null) {
                Party.leaveParty(p, bot.getClient());
            }
        });
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
