package server.bots;

import client.Character;
import client.command.CommandsExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tools.PacketCreator;

/**
 * Hidden in-game operator console for driving/inspecting bots without spamming party/map chat.
 *
 * <p>A GM opens the Maple Messenger window and types {@code mmc connect} to enter console mode
 * (modelled on SoloMapling's MMC). While connected, EVERY line the GM types is parsed as a console
 * command instead of broadcast as chat; {@code mmc disconnect} (or closing the window) leaves. The
 * replies render as {@code Console : ...} in the GM's own messenger window — a persistent, scrollable
 * pane separate from normal chat, ideal for the one capability chat lacks: live-tailing a bot's
 * autopilot decisions (see {@link BotConsoleTap}). There is no command prefix: the connect handshake,
 * not a per-line marker, is what tells chat from commands (a plain {@code Console:} prefix never
 * matched real messenger lines — those arrive as the GM's own raw text).
 *
 * <p>This is a thin front-end: every verb DELEGATES to an existing manager. It does not
 * reimplement command parsing or replace the file-based loggers ({@code BotPathLogger}, inv/
 * scroll dumps) — those structured dumps stay file-bound and are reached via {@code grind}
 * (which writes a file and chats its path) or via {@code cmd} passthrough.
 *
 * <p>Single-operator dev tool: one console, GM-gated, no synthetic messenger member.
 */
public final class BotOpsConsole {

    private static final BotOpsConsole instance = new BotOpsConsole();

    /** GM ids currently in console mode (their messenger lines are commands, not chat). */
    private final Set<Integer> connected = ConcurrentHashMap.newKeySet();

    public static BotOpsConsole getInstance() {
        return instance;
    }

    private BotOpsConsole() {}

    /**
     * Route one messenger chat line from a GM. Returns {@code true} when the console consumed it (the
     * caller must NOT broadcast it as chat), {@code false} to let it broadcast normally.
     *
     * <p>The {@code mmc connect}/{@code mmc disconnect} handshake works whether or not already
     * connected; any other line is a command only while connected, so a GM can still chat normally
     * until they opt in.
     */
    public boolean handleMessengerLine(Character gm, String input) {
        if (input == null) {
            return false;
        }
        String line = input.trim();
        String low = line.toLowerCase();
        if (low.equals("mmc connect") || low.equals("mmc")) {
            connect(gm);
            return true;
        }
        if (low.equals("mmc disconnect") || low.equals("mmc quit")) {
            disconnect(gm);
            return true;
        }
        if (!connected.contains(gm.getId())) {
            return false; // not in console mode -> let it broadcast as normal messenger chat
        }
        dispatch(gm, line);
        return true;
    }

    private void connect(Character gm) {
        connected.add(gm.getId());
        print(gm, List.of("console connected - every line you type here is now a command.",
                "type 'help' for verbs, 'mmc disconnect' to leave."));
    }

    private void disconnect(Character gm) {
        boolean was = connected.remove(gm.getId());
        if (BotConsoleTap.isOperator(gm)) {
            BotConsoleTap.unsubscribe();
        }
        if (was) {
            print(gm, "console disconnected - messenger is normal chat again.");
        }
    }

    /** Tear down console mode + any active stream (called on messenger close / logout). */
    public void onMessengerClosed(Character gm) {
        connected.remove(gm.getId());
        if (BotConsoleTap.isOperator(gm)) {
            BotConsoleTap.unsubscribe();
        }
    }

    /** Parse and dispatch one console command line (already GM-gated and connected). */
    private void dispatch(Character gm, String body) {
        if (body.isEmpty()) {
            help(gm);
            return;
        }
        int sp = body.indexOf(' ');
        String verb = (sp < 0 ? body : body.substring(0, sp)).toLowerCase();
        String rest = sp < 0 ? "" : body.substring(sp + 1).trim();

        switch (verb) {
            case "help", "?" -> help(gm);
            case "list", "bots" -> list(gm);
            case "status" -> status(gm, rest);
            case "log", "botlog" -> log(gm, rest);
            case "unlog", "botunlog", "disconnect" -> unlog(gm);
            case "grind", "ap" -> grind(gm, rest);
            case "gachapon", "gacha" -> gachapon(gm, rest);
            case "say", "chat" -> say(gm, rest);
            case "cmd" -> cmd(gm, rest);
            default -> print(gm, "unknown verb '" + verb + "' - try 'help'");
        }
    }

    private void help(Character gm) {
        print(gm, List.of(
                "=== bot ops console === (mmc disconnect to leave)",
                "list                  - all spawned bots (name, map, job/lv)",
                "status [name]         - bot status (one bot, or all on your map)",
                "log <name>            - stream that bot's live autopilot decisions here",
                "unlog                 - stop streaming",
                "grind <name>          - write the autopilot decision dump (path -> chat)",
                "gachapon <name> [npc] - force a gacha trip now (watch it navigate + roll)",
                "say <name> <text>     - drive the bot via its own chat commands",
                "cmd <@command ...>    - run a GM command (output -> normal chat)"));
    }

    private void list(Character gm) {
        List<BotEntry> entries = BotManager.getInstance().allEntries();
        if (entries.isEmpty()) {
            print(gm, "no bots spawned");
            return;
        }
        List<String> lines = new ArrayList<>(entries.size() + 1);
        lines.add(entries.size() + " bot(s):");
        for (BotEntry e : entries) {
            Character bot = e.bot;
            if (bot == null) {
                continue;
            }
            String job = bot.getJob() == null ? "?" : bot.getJob().toString();
            lines.add("  " + bot.getName() + " (map " + bot.getMapId() + ") [" + job + " lv" + bot.getLevel() + "]");
        }
        print(gm, lines);
    }

    private void status(Character gm, String name) {
        if (name.isEmpty()) {
            List<String> lines = BotManager.getInstance().mapBotStatusLines(gm.getMapId());
            print(gm, lines.isEmpty() ? List.of("no bots on this map") : lines);
            return;
        }
        BotEntry entry = resolve(gm, name);
        if (entry != null) {
            print(gm, entry.bot.getName() + ": " + BotAutopilotManager.statusReport(entry, entry.bot));
        }
    }

    private void log(Character gm, String name) {
        BotEntry entry = resolve(gm, name);
        if (entry == null) {
            return;
        }
        BotConsoleTap.subscribe(gm, entry.bot.getId());
        print(gm, "streaming " + entry.bot.getName() + "'s decisions (unlog to stop)");
    }

    private void unlog(Character gm) {
        boolean was = BotConsoleTap.isStreaming();
        BotConsoleTap.unsubscribe();
        print(gm, was ? "stopped streaming" : "nothing was streaming");
    }

    private void grind(Character gm, String name) {
        BotEntry entry = resolve(gm, name);
        if (entry == null) {
            return;
        }
        BotAutopilotDebug.exportPartyDecision(entry, entry.bot);
        print(gm, "writing " + entry.bot.getName() + "'s autopilot decision dump - file path will appear in chat");
    }

    private void gachapon(Character gm, String rest) {
        int sp = rest.indexOf(' ');
        String name = (sp < 0 ? rest : rest.substring(0, sp)).trim();
        BotEntry entry = resolve(gm, name);
        if (entry == null) {
            return;
        }
        int npcId = 0;
        if (sp >= 0) {
            try {
                npcId = Integer.parseInt(rest.substring(sp + 1).trim());
            } catch (NumberFormatException ignored) {
                // no/garbage npc id -> auto-pick the best reachable town
            }
        }
        print(gm, BotGachaponManager.forceErrand(entry, entry.bot, npcId));
    }

    private void say(Character gm, String rest) {
        int sp = rest.indexOf(' ');
        if (sp < 0) {
            print(gm, "usage: say <name> <text>");
            return;
        }
        BotEntry entry = resolve(gm, rest.substring(0, sp));
        if (entry == null) {
            return;
        }
        String text = rest.substring(sp + 1).trim();
        BotChatManager.handleChat(entry, text);
        print(gm, "-> " + entry.bot.getName() + ": " + text);
    }

    private void cmd(Character gm, String rest) {
        if (rest.isEmpty()) {
            print(gm, "usage: cmd <@command ...>");
            return;
        }
        // Output of GM commands goes to the operator's normal chat, not this window.
        CommandsExecutor.getInstance().handle(gm.getClient(), rest);
    }

    /** Resolve a bot by exact (case-insensitive) name, then by unique prefix; prints on miss/ambiguity. */
    private BotEntry resolve(Character gm, String name) {
        List<BotEntry> entries = BotManager.getInstance().allEntries();
        BotEntry exact = null;
        List<BotEntry> prefix = new ArrayList<>();
        for (BotEntry e : entries) {
            if (e.bot == null) {
                continue;
            }
            String bn = e.bot.getName();
            if (bn.equalsIgnoreCase(name)) {
                exact = e;
                break;
            }
            if (bn.regionMatches(true, 0, name, 0, name.length())) {
                prefix.add(e);
            }
        }
        if (exact != null) {
            return exact;
        }
        if (prefix.size() == 1) {
            return prefix.get(0);
        }
        if (prefix.isEmpty()) {
            print(gm, "no bot matching '" + name + "' - try 'Console: list'");
        } else {
            StringBuilder sb = new StringBuilder("ambiguous '" + name + "':");
            for (BotEntry e : prefix) {
                sb.append(' ').append(e.bot.getName());
            }
            print(gm, sb.toString());
        }
        return null;
    }

    // Render output as the "Console" speaker (matches SoloMapling's named-bot look) in the GM's window.
    private void print(Character gm, String line) {
        gm.sendPacket(PacketCreator.messengerChat("Console : " + line));
    }

    private void print(Character gm, List<String> lines) {
        for (String line : lines) {
            print(gm, line);
        }
    }
}
