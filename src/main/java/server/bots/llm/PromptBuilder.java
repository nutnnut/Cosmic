package server.bots.llm;

import client.Character;
import server.bots.BotEntry;

import java.util.List;

public final class PromptBuilder {
    private PromptBuilder() {}

    // Persistent, differentiated personalities — assigned deterministically by character id so each
    // bot keeps the same voice across sessions with zero storage.
    private static final String[] PERSONAS = {
            "chill and laid-back — you take everything in stride",
            "a sweaty tryhard obsessed with efficiency and xp/hr",
            "the comic relief — you joke around and meme constantly",
            "a nervous newbie who second-guesses and asks a lot of questions",
            "a loud hype-man who pumps up the squad",
            "a calm veteran who's seen it all and gives dry, short advice",
            "blunt and a little grumpy, but loyal",
            "bubbly and over-enthusiastic about literally everything",
    };

    private static String persona(Character bot) {
        int id = bot != null ? bot.getId() : 0;
        return PERSONAS[Math.floorMod(id, PERSONAS.length)];
    }

    private static void appendPersona(StringBuilder sb, BotEntry entry, Character bot, int lvl, String job) {
        String botName = bot != null ? bot.getName() : "bot";
        sb.append("Your IGN is ").append(botName).append(". ")
                .append("You are a real human MapleStory player, level ").append(lvl).append(' ').append(job).append(". ")
                .append("Your personality: ").append(persona(bot)).append(". ");
        String mood = entry != null ? entry.moodHint() : "";
        if (mood != null && !mood.isBlank()) {
            sb.append("Right now you're ").append(mood).append(". ");
        }
        sb.append("Talk like mmo chatter: lowercase, 1 short sentence, abbreviate whenever possible, text only, "
                + "internet slang when applicable, no emoji, no speaker label. ")
                .append("Stay in character for your personality and mood. ");
    }

    public static String buildSystem(BotEntry entry, SenderRelation relation, String senderName) {
        Character bot = entry.getBot();
        String job = bot != null ? bot.getJob().toString().toLowerCase().replace('_', ' ') : "adventurer";
        int lvl = bot != null ? bot.getLevel() : 1;

        String relationLine = switch (relation) {
            case OWNER -> "You are talking to your close gaming friend and party leader " + senderName + ". Be friendly and helpful.";
            case PARTY -> "You are talking to " + senderName + ", your party member. Be casual.";
            case STRANGER -> "You are talking to " + senderName + ", a stranger you just met in the game.";
        };

        StringBuilder sb = new StringBuilder(640);
        appendPersona(sb, entry, bot, lvl, job);
        sb.append("Keep replies simple. Prefer fragments over full grammar. ")
                .append("Answer only the latest message directly. If unsure, say so briefly instead of guessing. ")
                .append("Never say you are an ai, assistant, chatbot, or bot. ");
        if (relation == SenderRelation.OWNER) {
            // Command-interpreter: let the model turn a fuzzy instruction into one of the real bot
            // commands. The caller validates the emitted command and only runs it if it matches.
            sb.append("If the owner is telling you to DO something in-game (go train/farm somewhere, follow, "
                    + "come here, stop, go shopping, sell, reset sp, change formation, etc.), do NOT chat — reply with "
                    + "exactly one line starting with 'CMD: ' then a short imperative, e.g. 'CMD: farm <monster>', "
                    + "'CMD: go shopping', 'CMD: follow me', 'CMD: stop', 'CMD: reset sp'. "
                    + "For questions, advice, or small talk, reply normally (no CMD). ");
        }
        sb.append(relationLine);
        return sb.toString();
    }

    public static String buildPrompt(BotEntry entry, String senderName, String newMessage,
                                     String summary, List<BotMemoryStore.Turn> recent, String grounded) {
        StringBuilder sb = new StringBuilder(640);
        if (summary != null && !summary.isBlank()) {
            sb.append("What you remember: ").append(summary).append("\n\n");
        }
        String situation = SituationBuilder.build(entry);
        if (!situation.isEmpty()) {
            sb.append(situation).append('\n');
        }
        if (grounded != null && !grounded.isBlank()) {
            sb.append("[Server facts you can rely on to answer — don't contradict these]\n")
                    .append(grounded).append('\n');
        }
        if (recent != null && !recent.isEmpty()) {
            sb.append("Recent chat (older lines matter less):\n");
            String botName = entry.getBot() != null ? entry.getBot().getName() : "bot";
            long now = System.currentTimeMillis();
            for (BotMemoryStore.Turn t : recent) {
                String age = SituationBuilder.ago(now - t.ts());
                sb.append('[').append(age).append(" ago] ")
                        .append(t.sender()).append(": ").append(t.msg()).append('\n');
                sb.append(botName).append(": ").append(t.reply()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Reply to the newest message only. Treat older chat as background, not the topic.\n");
        sb.append(senderName).append(": ").append(newMessage).append('\n');
        sb.append(entry.getBot() != null ? entry.getBot().getName() : "bot").append(':');
        return sb.toString();
    }

    /** Persona-only system prompt (no relation line, no CMD instruction) for utility re-wording. */
    public static String buildPlainSystem(BotEntry entry) {
        Character bot = entry.getBot();
        String job = bot != null ? bot.getJob().toString().toLowerCase().replace('_', ' ') : "adventurer";
        int lvl = bot != null ? bot.getLevel() : 1;
        StringBuilder sb = new StringBuilder(400);
        appendPersona(sb, entry, bot, lvl, job);
        sb.append("Never say you are an ai, assistant, chatbot, or bot.");
        return sb.toString();
    }

    /** Prompt to re-word a command acknowledgement in the bot's own voice (meaning preserved). */
    public static String buildAckReword(BotEntry entry, String ownerName, String ack) {
        Character bot = entry.getBot();
        String botName = bot != null ? bot.getName() : "bot";
        StringBuilder sb = new StringBuilder(256);
        String situation = SituationBuilder.build(entry);
        if (!situation.isEmpty()) {
            sb.append(situation).append('\n');
        }
        sb.append(ownerName).append(" gave you an order and you just did it. Acknowledge it in your own ")
                .append("voice/persona, keeping the same meaning as: \"").append(ack).append("\". ")
                .append("One short line, no quotes, do not output 'CMD:'.\n")
                .append(botName).append(':');
        return sb.toString();
    }

    // --- Bot-to-bot idle banter -------------------------------------------------------------

    /** Persona prompt for a bot bantering with a fellow party-member bot while grinding together. */
    public static String buildBanterSystem(BotEntry entry, String siblingName) {
        Character bot = entry.getBot();
        String job = bot != null ? bot.getJob().toString().toLowerCase().replace('_', ' ') : "adventurer";
        int lvl = bot != null ? bot.getLevel() : 1;
        StringBuilder sb = new StringBuilder(512);
        appendPersona(sb, entry, bot, lvl, job);
        sb.append("You are grinding alongside your friend and party member ").append(siblingName).append(". ")
                .append("Keep it casual party small-talk, in character. ")
                .append("Never say you are an ai, assistant, chatbot, or bot.");
        return sb.toString();
    }

    private static void appendBanterMemory(StringBuilder sb, List<BotMemoryStore.Turn> recent) {
        if (recent == null || recent.isEmpty()) {
            return;
        }
        sb.append("Earlier banter with the squad (for callbacks/inside jokes):\n");
        int from = Math.max(0, recent.size() - 3);
        for (int i = from; i < recent.size(); i++) {
            BotMemoryStore.Turn t = recent.get(i);
            sb.append("- ").append(t.sender()).append(": ").append(t.msg()).append('\n');
        }
        sb.append('\n');
    }

    /** Prompt for the opener: a bot starts a bit of small-talk with a sibling, grounded in the scene. */
    public static String buildBanterOpener(BotEntry entry, String siblingName, List<BotMemoryStore.Turn> recent) {
        StringBuilder sb = new StringBuilder(320);
        String situation = SituationBuilder.build(entry);
        if (!situation.isEmpty()) {
            sb.append(situation).append('\n');
        }
        appendBanterMemory(sb, recent);
        sb.append("Say one short, casual thing to ").append(siblingName)
                .append(" to pass the time while grinding — a remark about the drops, the xp, the map, the mobs, the boss if any, or the squad's vibe. ")
                .append("One short line only, no quotes.\n")
                .append(entry.getBot() != null ? entry.getBot().getName() : "bot").append(':');
        return sb.toString();
    }

    /** Prompt for the sibling's reply to {@code openerLine}, grounded in its own scene. */
    public static String buildBanterReply(BotEntry entry, String siblingName, String openerLine, List<BotMemoryStore.Turn> recent) {
        StringBuilder sb = new StringBuilder(320);
        String situation = SituationBuilder.build(entry);
        if (!situation.isEmpty()) {
            sb.append(situation).append('\n');
        }
        appendBanterMemory(sb, recent);
        sb.append(siblingName).append(" just said to you: \"").append(openerLine).append("\"\n")
                .append("Reply with one short, casual line, no quotes.\n")
                .append(entry.getBot() != null ? entry.getBot().getName() : "bot").append(':');
        return sb.toString();
    }

    /** Prompt for a short reaction to arriving at a new map (town/dungeon vibe). */
    public static String buildMapRemark(BotEntry entry) {
        StringBuilder sb = new StringBuilder(256);
        String situation = SituationBuilder.build(entry);
        if (!situation.isEmpty()) {
            sb.append(situation).append('\n');
        }
        sb.append("You just arrived at this map. Say one short, casual reaction to being here — the ")
                .append("town/dungeon vibe, what you'll do here, or a quick quip. One short line, no quotes.\n")
                .append(entry.getBot() != null ? entry.getBot().getName() : "bot").append(':');
        return sb.toString();
    }
}
