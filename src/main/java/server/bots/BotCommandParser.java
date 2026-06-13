package server.bots;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BotCommandParser {
    private static final Pattern TRANSFER_PATTERN = Pattern.compile(
            "\\btransfer\\s+(\\S+)(?:\\s+to)?\\s+(\\S+)\\b", Pattern.CASE_INSENSITIVE);
    private static final int MIN_PREFIX_TARGET_LENGTH = 2;
    private static final int MAX_NUMERIC_TARGET_SLOT = 5;

    private BotCommandParser() {
    }

    record BotTransferCommand(String botName, String targetName) {}

    private record TargetedBotCommand(String targetToken, String commandText) {}

    record TargetedBotMatch(BotEntry entry, String commandText, String feedbackMessage) {}

    static BotTransferCommand matchBotTransferCommand(String message) {
        Matcher matcher = TRANSFER_PATTERN.matcher(message);
        if (!matcher.find()) {
            return null;
        }

        return new BotTransferCommand(matcher.group(1), matcher.group(2));
    }

    /** Name-only variant for admin cross-owner targeting: numeric slot tokens are owner-list
     *  positions and must never select from a global list of other players' bots. */
    static TargetedBotMatch resolveTargetedBotByName(List<BotEntry> entries, String message) {
        if (entries == null || entries.isEmpty()) {
            return new TargetedBotMatch(null, null, null);
        }
        TargetedBotCommand targetedCommand = parseTargetedBotCommand(message);
        if (targetedCommand == null) {
            return new TargetedBotMatch(null, null, null);
        }
        for (BotEntry entry : entries) {
            if (entry.bot.getName().equalsIgnoreCase(targetedCommand.targetToken())) {
                return new TargetedBotMatch(entry, targetedCommand.commandText(), null);
            }
        }
        return new TargetedBotMatch(null, null, null);
    }

    static TargetedBotMatch resolveTargetedBot(List<BotEntry> entries, String message) {
        if (entries == null || entries.isEmpty()) {
            return new TargetedBotMatch(null, null, null);
        }

        TargetedBotCommand targetedCommand = parseTargetedBotCommand(message);
        if (targetedCommand == null) {
            return new TargetedBotMatch(null, null, null);
        }

        String targetToken = targetedCommand.targetToken();
        for (BotEntry entry : entries) {
            if (entry.bot.getName().equalsIgnoreCase(targetToken)) {
                return new TargetedBotMatch(entry, targetedCommand.commandText(), null);
            }
        }

        if (isNumericTarget(targetToken)) {
            int slot = Integer.parseInt(targetToken);
            if (slot < 1 || slot > MAX_NUMERIC_TARGET_SLOT) {
                return new TargetedBotMatch(null, null, null);
            }
            if (slot > entries.size()) {
                return new TargetedBotMatch(null, null, "No bot in slot " + slot + ".");
            }
            return new TargetedBotMatch(entries.get(slot - 1), targetedCommand.commandText(), null);
        }

        if (targetToken.length() < MIN_PREFIX_TARGET_LENGTH) {
            return new TargetedBotMatch(null, null, null);
        }

        List<BotEntry> prefixMatches = new ArrayList<>();
        for (BotEntry entry : entries) {
            if (entry.bot.getName().regionMatches(true, 0, targetToken, 0, targetToken.length())) {
                prefixMatches.add(entry);
            }
        }

        if (prefixMatches.size() == 1) {
            return new TargetedBotMatch(prefixMatches.get(0), targetedCommand.commandText(), null);
        }
        if (prefixMatches.size() > 1) {
            return new TargetedBotMatch(null, null, buildAmbiguousPrefixMessage(targetToken, prefixMatches));
        }

        return new TargetedBotMatch(null, null, null);
    }

    private static TargetedBotCommand parseTargetedBotCommand(String message) {
        if (message == null) {
            return null;
        }

        String trimmed = message.stripLeading();
        if (trimmed.isEmpty()) {
            return null;
        }

        int separatorIndex = 0;
        while (separatorIndex < trimmed.length() && !isTargetSeparator(trimmed.charAt(separatorIndex))) {
            separatorIndex++;
        }
        if (separatorIndex == 0 || separatorIndex >= trimmed.length()) {
            return null;
        }

        String commandText = trimmed.substring(separatorIndex).replaceFirst("^[,!?\\s]+", "").trim();
        if (commandText.isEmpty()) {
            return null;
        }

        return new TargetedBotCommand(trimmed.substring(0, separatorIndex), commandText);
    }

    private static boolean isTargetSeparator(char ch) {
        return java.lang.Character.isWhitespace(ch) || ch == ',' || ch == '!' || ch == '?';
    }

    private static boolean isNumericTarget(String targetToken) {
        return !targetToken.isEmpty() && targetToken.chars().allMatch(java.lang.Character::isDigit);
    }

    private static String buildAmbiguousPrefixMessage(String prefix, List<BotEntry> matches) {
        StringBuilder message = new StringBuilder("Ambiguous bot prefix '")
                .append(prefix)
                .append("': ");
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) {
                message.append(", ");
            }
            message.append(i + 1).append(": ").append(matches.get(i).bot.getName());
        }
        message.append(". Use the full name or a slot number.");
        return message.toString();
    }
}
