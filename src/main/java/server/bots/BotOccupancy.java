package server.bots;

import client.BotClient;
import client.Character;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

/**
 * Live map occupancy for grind dispersion. Counts how many OTHER players/bots are actively contesting
 * spawns on each map so the planner can treat a crowded map as extra competition for spawns
 * (anti-stacking / anti kill-steal — see {@link BotGrindPlanner}'s spawn-share model).
 *
 * <p>Only spawn CONTESTANTS are counted ({@link #contestsSpawns}): grinding bots, and humans who
 * attacked recently. A standing/socializing player or a following/idle bot is ignored, so bots don't
 * visibly avoid a map just because someone is watching. A traveling bot is counted on its COMMITTED
 * autopilot target ({@code entry.autopilotMapId}), not where it currently stands — so many bots
 * deciding at once spread across maps instead of stampeding the same empty one; everyone else counts on
 * their current map. The deciding bot and its own party are excluded: the party already shares spawns
 * by its size.
 */
final class BotOccupancy {

    private BotOccupancy() {}

    /** Outsider count per mapId from the deciding bot's perspective (self + own party excluded). */
    static Map<Integer, Integer> outsidersByMap(Character self) {
        Map<Integer, Integer> byMap = new HashMap<>();
        if (self == null || self.getWorldServer() == null) {
            return byMap;
        }
        int selfId = self.getId();
        int myPartyId = self.getParty() != null ? self.getParty().getId() : -1;
        for (Character chr : self.getWorldServer().getPlayerStorage().getAllCharacters()) {
            if (chr == null || chr.getId() == selfId) {
                continue;
            }
            if (myPartyId != -1 && chr.getParty() != null && chr.getParty().getId() == myPartyId) {
                continue; // cohort: already modeled by party-size spawn sharing
            }
            if (!contestsSpawns(chr)) {
                continue; // idle observer / following bot: not competing for mobs, don't disperse off them
            }
            byMap.merge(occupiedMap(chr), 1, Integer::sum);
        }
        return byMap;
    }

    /** True when this character is actually contesting mob spawns — so crowding off it is justified.
     *  A bot counts only in an active combat mode ({@code grinding}: grind/sentry/patrol/roam, NOT
     *  follow/idle); a human counts only if they attacked within {@code ACTIVE_GRIND_WINDOW_MS}. This is
     *  what lets a player stand on a map and watch bots work without the bots fleeing the "crowd". */
    private static boolean contestsSpawns(Character chr) {
        if (chr.getClient() instanceof BotClient) {
            BotEntry e = BotManager.getInstance().getEntryByBotCharId(chr.getId());
            return e != null && e.grinding;
        }
        return System.currentTimeMillis() - chr.getLastAttackTime() <= BotManager.cfg.ACTIVE_GRIND_WINDOW_MS;
    }

    /** Where this character competes: a traveling bot claims its committed target map; everyone else
     *  their current map. */
    private static int occupiedMap(Character chr) {
        if (chr.getClient() instanceof BotClient) {
            BotEntry e = BotManager.getInstance().getEntryByBotCharId(chr.getId());
            if (e != null && e.autopilotMapId != -1) {
                return e.autopilotMapId;
            }
        }
        return chr.getMapId();
    }

    /** Crowd surcharge for the planner: {@code penalty * outsiders(mapId)} extra competitors, 0 when
     *  nobody else is there (so a lone bot's scoring is unchanged). Tallied ONCE per decision. */
    static IntToDoubleFunction extraCompetitors(Character self, double penalty) {
        if (penalty <= 0.0) {
            return mapId -> 0.0;
        }
        Map<Integer, Integer> byMap = outsidersByMap(self);
        return mapId -> penalty * byMap.getOrDefault(mapId, 0);
    }
}
