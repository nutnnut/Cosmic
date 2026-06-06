/*
    This file is part of the LumenMS / CosmicMS server.

    GM command: manually trigger the 2x EXP / 2x Drop event.
    Syntax: !startdoublexp [minutes]   (default: 60)
*/
package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import server.events.DoubleRateEventManager;

public class StartDoubleXpCommand extends Command {
    {
        setDescription("Start a 2x EXP / 2x drop event for one hour (or [minutes]).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        long durationMs = DoubleRateEventManager.DEFAULT_DURATION_MS;

        if (params.length >= 1 && !params[0].isEmpty()) {
            try {
                int minutes = Integer.parseInt(params[0]);
                if (minutes < 1) {
                    player.yellowMessage("Duration must be at least 1 minute.");
                    return;
                }
                durationMs = minutes * 60_000L;
            } catch (NumberFormatException e) {
                player.yellowMessage("Syntax: !startdoublexp [minutes]");
                return;
            }
        }

        boolean started = DoubleRateEventManager.getInstance()
                .start(durationMs, "gm:" + player.getName());
        if (!started) {
            player.yellowMessage("A double-rate event is already running.");
            return;
        }
        player.yellowMessage("Double-rate event started for " + (durationMs / 60_000L) + " minutes.");
    }
}
