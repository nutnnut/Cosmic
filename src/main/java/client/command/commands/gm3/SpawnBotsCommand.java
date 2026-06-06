package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.bots.BotManager;
import server.bots.BotOwnershipService;

import java.util.List;

public class SpawnBotsCommand extends Command {
    {
        setDescription("Spawn all of your registered bots at once.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        BotManager botManager = BotManager.getInstance();
        BotOwnershipService ownershipService = BotOwnershipService.getInstance();

        List<Integer> botIds = ownershipService.getRegisteredBotIds(player.getId());
        if (botIds.isEmpty()) {
            player.yellowMessage("You have no registered bots. Use @registerbot / @spawnbot first.");
            return;
        }

        int spawned = 0;
        int failed = 0;
        for (int botId : botIds) {
            BotOwnershipService.ResolvedCharacter bot = ownershipService.resolveCharacterById(botId);
            if (bot == null) {
                failed++;
                continue;
            }

            BotManager.SpawnResult result = botManager.spawnBotForOwner(player, bot.name());
            if (result.success()) {
                botManager.joinBotToOwnerParty(player, result.bot());
                spawned++;
            } else {
                failed++;
                player.yellowMessage("Could not spawn '" + bot.name() + "': " + result.errorMessage());
            }
        }

        player.yellowMessage("Spawned " + spawned + " of " + botIds.size() + " registered bot(s)"
                + (failed > 0 ? " (" + failed + " failed)" : "") + ".");
    }
}
