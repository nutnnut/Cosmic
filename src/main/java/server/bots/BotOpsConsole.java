package server.bots;

import client.Character;
import client.command.CommandsExecutor;
import java.util.ArrayList;
import java.util.List;
import tools.PacketCreator;

/**
 * Hidden in-game operator console for driving/inspecting bots without spamming party/map chat.
 *
 * <p>A GM opens the Maple Messenger window and types {@code Console: <verb> <args>}. The
 * messenger chat handler ({@code MessengerHandler} case {@code 0x06}) routes such lines here
 * (gated on {@link Character#isGM()}) instead of broadcasting them, and output is rendered back
 * into the GM's own messenger window. The window gives a persistent, scrollable pane separate
 * from normal chat — ideal for the one capability chat lacks: live-tailing a bot's autopilot
 * decisions (see {@link BotConsoleTap}).
 *
 * <p>This is a thin front-end: every verb DELEGATES to an existing manager. It does not
 * reimplement command parsing or replace the file-based loggers ({@code BotPathLogger}, inv/
 * scroll dumps) — those structured dumps stay file-bound and are reached via {@code grind}
 * (which writes a file and chats its path) or via {@code cmd} passthrough.
 *
 * <p>Single-operator dev tool: one console, GM-gated, no synthetic messenger member.
 */
public final class BotOpsConsole {

    private static final String PREFIX = "Console:";
    private static final BotOpsConsole instance = new BotOpsConsole();

    public static BotOpsConsole getInstance() {
        return instance;
    }

    private BotOpsConsole() {}

    /** True when a messenger line is addressed to the console (case-insensitive {@code Console:} prefix). */
    public boolean isConsoleLine(String input) {
        return input != null && input.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /** Tear down any active stream owned by this operator (called on messenger close / logout). */
    public void onMessengerClosed(Character gm) {
        if (BotConsoleTap.isOperator(gm)) {
            BotConsoleTap.unsubscribe();
        }
    }

    /** Parse and dispatch a {@code Console: ...} line. Must already be GM-gated and prefix-matched. */
    public void handle(Character gm, String raw) {
        String body = raw.substring(raw.indexOf(':') + 1).trim();
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
            case "say", "chat" -> say(gm, rest);
            case "cmd" -> cmd(gm, rest);
            default -> print(gm, "unknown verb '" + verb + "' - try 'Console: help'");
        }
    }

    private void help(Character gm) {
        print(gm, List.of(
                "=== bot ops console ===",
                "list                  - all spawned bots (name, map, job/lv)",
                "status [name]         - bot status (one bot, or all on your map)",
                "log <name>            - stream that bot's live autopilot decisions here",
                "unlog                 - stop streaming",
                "grind <name>          - write the autopilot decision dump (path -> chat)",
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
        print(gm, "streaming " + entry.bot.getName() + "'s decisions (Console: unlog to stop)");
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

    private void say(Character gm, String rest) {
        int sp = rest.indexOf(' ');
        if (sp < 0) {
            print(gm, "usage: Console: say <name> <text>");
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
            print(gm, "usage: Console: cmd <@command ...>");
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

    private void print(Character gm, String line) {
        gm.sendPacket(PacketCreator.messengerChat(line));
    }

    private void print(Character gm, List<String> lines) {
        for (String line : lines) {
            gm.sendPacket(PacketCreator.messengerChat(line));
        }
    }
}
