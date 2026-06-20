package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import java.nio.file.Path;
import server.bots.BotPerformanceMonitor;

/**
 * On-demand CSV export of the bot performance monitor's current window, separate from the live
 * console report toggle ({@code !botperfdebug}). Writes logs/bot-perf/bot-perf-&lt;ts&gt;.csv for
 * offline analysis / heatmaps. Requires the monitor to be ON (it only accumulates while enabled).
 */
public class BotPerfLogCommand extends Command {
    {
        setDescription("Export bot perf stats to CSV: !botperflog");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (!BotPerformanceMonitor.enabled()) {
            player.yellowMessage("bot perf monitor is OFF - run '!botperfdebug on', let it sample, then '!botperflog'");
            return;
        }
        Path file = BotPerformanceMonitor.exportCsv();
        if (file == null) {
            player.yellowMessage("nothing to export yet - no samples accumulated this window");
            return;
        }
        player.yellowMessage("bot perf exported: " + file.toAbsolutePath());
    }
}
