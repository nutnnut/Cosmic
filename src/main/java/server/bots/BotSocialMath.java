package server.bots;

/**
 * Pure, unit-tested decision math for bot social/party behavior (P4). No game state: {@link
 * BotSocialManager} supplies the random rolls, the live level gap, and the server's exp-share window
 * ({@code EXP_SPLIT_LEECH_INTERVAL}). Keeping the policy here (like {@link BotScheduleMath}) means the
 * "who parties with whom, and when" rules are testable without a live map/party.
 */
final class BotSocialMath {

    private BotSocialMath() {}

    /** A bot's reaction to a party overture. IGNORE = stays silent (a quiet/antisocial bot just doesn't
     *  reply); DECLINE = spoken "no thanks"; ACCEPT = joins. */
    enum Response { ACCEPT, DECLINE, IGNORE }

    /** Whether a SOLO bot proactively starts a party overture this social tick. Already-partied bots
     *  never initiate. {@code roll} in [0,1). */
    static boolean shouldInitiate(BotPersonality p, boolean alreadyPartied, double roll) {
        return p != null && !alreadyPartied && roll < p.partyInitiateChance();
    }

    /**
     * How the bot responds to a party offer/ask. {@code levelGap} = |my level - theirs|; {@code
     * shareWindow} = the server EXP_SPLIT_LEECH_INTERVAL (a party only shares a kill's exp inside this
     * window). Out of exp range it declines (no point) UNLESS a mistake roll slips it through; in range
     * it accepts by sociability, else declines/ignores. A bot SPEAKS its refusal only when chatty
     * enough — otherwise it just ignores. All rolls in [0,1).
     */
    static Response respondToOffer(BotPersonality p, int levelGap, int shareWindow,
                                   double acceptRoll, double mistakeRoll, double speakRoll) {
        if (p == null) {
            return Response.IGNORE;
        }
        boolean expRangeOk = levelGap <= shareWindow || mistakeRoll < p.partyMistakeChance();
        if (expRangeOk && acceptRoll < p.acceptChance()) {
            return Response.ACCEPT;
        }
        return speakRoll < p.chattiness() ? Response.DECLINE : Response.IGNORE;
    }
}
