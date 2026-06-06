package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.bots.BotOwnershipService;

import java.util.List;

public class ListBotsCommand extends Command {
    {
        setDescription("List your registered bots and their status.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        BotOwnershipService ownershipService = BotOwnershipService.getInstance();

        List<Integer> botIds = ownershipService.getRegisteredBotIds(player.getId());
        if (botIds.isEmpty()) {
            player.yellowMessage("You have no registered bots. Use @registerbot / @spawnbot first.");
            return;
        }

        player.dropMessage(6, "Your registered bots (" + botIds.size() + "):");
        for (int botId : botIds) {
            BotOwnershipService.ResolvedCharacter bot = ownershipService.resolveCharacterById(botId);
            if (bot == null) {
                continue;
            }
            String status = bot.isOnlineAsBot() ? "spawned"
                    : (bot.isOnline() ? "online (played by a person)" : "offline");
            player.dropMessage(6, "  " + bot.name() + " - " + status);
        }
    }
}
