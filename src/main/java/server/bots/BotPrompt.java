package server.bots;

import client.Character;
import java.util.List;
import tools.PacketCreator;

/**
 * SSOT for the on-screen "possible responses" overlay a bot shows its owner whenever it asks a
 * discrete-choice question (scroll yes/no/let-me-see, AP build, SP variant, job advance, ...).
 *
 * <p>The bot's chat line stays the source of truth — it persists in the chat log and carries the
 * full question. This overlay is a transient visual nudge that lists the replies the owner can
 * type, so they don't have to remember the wording. It mirrors the canonical NPC-script hint idiom
 * ({@code sendHint} + {@code enableActions}, see
 * {@link scripting.AbstractPlayerInteraction#showInstruction}). It does NOT capture the reply:
 * parsing stays in the existing per-prompt handlers (e.g. {@code BotScrollManager.handleScrollConfirm},
 * the AP/job patterns in {@code BotChatManager}). Labels passed here should be the exact tokens
 * those handlers accept.
 *
 * <p>The hint is owner-facing UI, so it is plain US-ASCII like the rest of bot chat (see the chat
 * charset rule) and is a no-op when the owner is offline.
 */
final class BotPrompt {

    private BotPrompt() {}

    /**
     * Shows the owner an overlay headed by the bot's question and listing the replies they can type, e.g.
     * <pre>scroll my coat? ~75% success
     * - yes
     * - no
     * - let me see</pre>
     * Labels are rendered verbatim (no numeric prefixes) because the prompt handlers parse the reply
     * tokens, not list indices — a "1." prefix would invite typing "1", which those handlers reject.
     * No-op if the owner is offline or there are no options.
     *
     * @param question the bot's chat line for this prompt; shown as the overlay header (falls back to a
     *                 generic header if blank). The chat line itself remains the source of truth.
     */
    static void showOptions(BotEntry entry, String question, List<String> options) {
        if (entry == null || options == null || options.isEmpty()) {
            return;
        }
        Character owner = entry.getOwner();
        if (owner == null || owner.getClient() == null) {
            return;
        }

        String header = (question == null || question.isBlank()) ? "Reply with:" : question.trim();
        StringBuilder sb = new StringBuilder(header);
        int maxLineLen = header.length();
        for (String option : options) {
            String line = "- " + option;
            sb.append("\r\n").append(line);
            maxLineLen = Math.max(maxLineLen, line.length());
        }

        // Cap the box width; the client wraps the header to it, so size the height for the wrapped
        // header lines plus one line per option.
        int width = Math.max(120, Math.min(320, maxLineLen * 8));
        int charsPerLine = Math.max(1, width / 7);
        int headerLines = Math.max(1, (header.length() + charsPerLine - 1) / charsPerLine);
        int height = (headerLines + options.size()) * 18 + 14;
        owner.sendPacket(PacketCreator.sendHint(sb.toString(), width, height));
        owner.sendPacket(PacketCreator.enableActions());
    }
}
