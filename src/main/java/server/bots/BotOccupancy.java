package server.bots;

import client.BotClient;
import client.Character;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

/**
 * Live map occupancy for grind dispersion. Counts how many OTHER players/bots are committed to or
 * standing on each map so the planner can treat a crowded map as extra competition for spawns
 * (anti-stacking / anti kill-steal — see {@link BotGrindPlanner}'s spawn-share model).
 *
 * <p>A traveling bot is counted on its COMMITTED autopilot target ({@code entry.autopilotMapId}), not
 * where it currently stands — so many bots deciding at once spread across maps instead of stampeding
 * the same empty one. Everyone else (humans, idle/following bots) counts on their current map. The
 * deciding bot and its own party are excluded: the party already shares spawns by its size.
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
            byMap.merge(occupiedMap(chr), 1, Integer::sum);
        }
        return byMap;
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
