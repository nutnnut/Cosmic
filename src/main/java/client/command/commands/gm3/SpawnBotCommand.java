package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.bots.BotGenerator;
import server.bots.BotManager;
import server.bots.BotNameGenerator;
import server.bots.BotOwnershipService;

public class SpawnBotCommand extends Command {
    {
        setDescription("Spawn an authorized character as a bot companion.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        BotManager botManager = BotManager.getInstance();
        BotOwnershipService ownershipService = BotOwnershipService.getInstance();
        if (params.length < 1) {
            player.yellowMessage("Syntax: @spawnbot <name|generate> [confirm] [autopilot]");
            return;
        }

        // params are lowercased by CommandsExecutor; use lastCommandMessage to preserve casing
        String[] rawArgs = player.getLastCommandMessage().trim().split("[ ]", 2);
        String requestedName = rawArgs[0];
        boolean createRequested = hasFlag(params, "confirm");
        // "autopilot": spawn it self-owned and playing on its own (no human owner), instead of
        // following the spawner. This is the launch path for ownerless/supervised bots.
        boolean ownerless = hasFlag(params, "autopilot");

        // "generate" as the name: pick a procedural MMO name instead of a literal name
        boolean autoName = requestedName.equalsIgnoreCase("generate");
        String botName = autoName ? BotNameGenerator.generate() : requestedName;
        if (autoName) {
            player.yellowMessage("Generated bot name: " + botName);
        }

        BotOwnershipService.ResolvedCharacter bot = ownershipService.resolveCharacterByName(botName);
        if (bot == null) {
            if (!createRequested && !autoName) {
                player.yellowMessage("Bot '" + botName + "' does not exist. Run: @spawnbot " + botName + " confirm  to create it.");
                return;
            }

            BotGenerator.Result created = BotGenerator.createBotCharacter(c.getWorld(), c.getChannel(), botName);
            if (!created.ok()) {
                player.yellowMessage(created.error());
                return;
            }
            int createdCharId = created.charId();

            ownershipService.registerOwner(createdCharId, player.getId());
            // A procedurally-generated ("generate") bot is a disposable, server-owned character: mark it
            // in the managed_bot registry so the population scheduler may schedule it. Characters created
            // for a named @spawnbot, or registered via @registerbot/@botme, are NOT marked and are never
            // auto-scheduled.
            if (autoName) {
                server.bots.ManagedBotService.getInstance().insert(createdCharId, null);
            }
            bot = ownershipService.resolveCharacterByName(botName);
            player.yellowMessage("Bot '" + botName + "' created. Login with: user=" + botName + " pw=botbot");
        }

        BotManager.SpawnResult result = ownerless
                ? botManager.spawnOwnerlessBot(player, botName)
                : botManager.spawnBotForOwner(player, botName);
        if (!result.success()) {
            player.yellowMessage(result.errorMessage());
            return;
        }
        if (ownerless) {
            player.yellowMessage("Bot '" + result.bot().getName() + "' spawned as a self-owned autopilot bot - it'll play on its own.");
            return;
        }
        joinBotToPlayerParty(player, result.bot());
        if (result.autoRegistered()) {
            player.yellowMessage("Bot '" + result.bot().getName() + "' auto-registered to " + player.getName() + " because it is on the same account.");
        }
        player.yellowMessage("Bot '" + result.bot().getName() + "' spawned. Say 'follow me' or 'stop' to control it.");
    }

    private static boolean hasFlag(String[] params, String flag) {
        for (int i = 1; i < params.length; i++) {
            if (flag.equals(params[i])) {
                return true;
            }
        }
        return false;
    }

    private void joinBotToPlayerParty(Character player, Character bot) {
        BotManager.getInstance().joinBotToOwnerParty(player, bot);
    }
}
