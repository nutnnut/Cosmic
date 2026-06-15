package server.bots;

import client.Character;
import java.util.function.BiConsumer;
import tools.PacketCreator;

/**
 * Live decision stream for the {@link BotOpsConsole}: a decorator installed over the mutable
 * static {@link BotAutopilotManager#reply} dispatcher. Every autopilot decision announcement
 * already flows through that {@code BiConsumer}; when an operator subscribes to a bot, the
 * lines for that bot are mirrored into the operator's Messenger window (in addition to their
 * normal party/whisper routing, which is never disturbed).
 *
 * <p>Single-operator dev tool: the wrapper is a process-global swap, installed lazily on the
 * first {@code subscribe} and restored on {@code unsubscribe}. While nobody is subscribed the
 * original dispatcher is in place, so there is zero overhead on the hot decision path.
 */
final class BotConsoleTap {

    private BotConsoleTap() {}

    private static volatile int botCharId;          // 0 = nobody subscribed
    private static volatile Character operator;
    private static BiConsumer<BotEntry, String> original;   // captured original dispatcher

    static synchronized void subscribe(Character gm, int targetBotCharId) {
        if (original == null) {                     // install the decorator once
            original = BotAutopilotManager.reply;
            BotAutopilotManager.reply = BotConsoleTap::dispatch;
        }
        operator = gm;
        botCharId = targetBotCharId;
    }

    static synchronized void unsubscribe() {
        botCharId = 0;
        operator = null;
        if (original != null) {                     // restore the original dispatcher
            BotAutopilotManager.reply = original;
            original = null;
        }
    }

    static boolean isStreaming() {
        return botCharId != 0;
    }

    /** True if {@code gm} is the current operator (so console teardown can drop a stale stream). */
    static boolean isOperator(Character gm) {
        return gm != null && gm == operator;
    }

    private static void dispatch(BotEntry entry, String text) {
        BiConsumer<BotEntry, String> orig = original;
        if (orig != null) {
            orig.accept(entry, text);               // never break normal routing
        }
        int target = botCharId;
        Character op = operator;
        if (target != 0 && op != null && entry != null && entry.bot != null
                && entry.bot.getId() == target) {
            op.sendPacket(PacketCreator.messengerChat("[" + entry.bot.getName() + "] " + text));
        }
    }
}
