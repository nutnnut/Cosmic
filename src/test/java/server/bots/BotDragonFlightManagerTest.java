package server.bots;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Corridor-direction inference for a companion following its owner across the Leafre <-> Temple of
 * Time dragon flight. The corridor is strictly linear:
 * Leafre dock (240000110) - 200090500 - 200090510 - Temple (270000100).
 */
class BotDragonFlightManagerTest {

    private static final int LEAFRE_DOCK = 240000110;
    private static final int LEAFRE_FLIGHT = 200090500;
    private static final int TEMPLE_FLIGHT = 200090510;
    private static final int TEMPLE = 270000100;

    @Test
    void onlyTheTwoFlightMapsAreFlightMaps() {
        assertTrue(BotDragonFlightManager.isFlightMap(LEAFRE_FLIGHT));
        assertTrue(BotDragonFlightManager.isFlightMap(TEMPLE_FLIGHT));
        assertFalse(BotDragonFlightManager.isFlightMap(LEAFRE_DOCK));
        assertFalse(BotDragonFlightManager.isFlightMap(TEMPLE));
    }

    @Test
    void ownerAheadInTheCorridorSteersOutboundTowardTemple() {
        // Owner already reached the far (Temple) flight map or Temple itself while the bot is still
        // on the near flight map -> keep going forward.
        assertTrue(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, TEMPLE_FLIGHT));
        assertTrue(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, TEMPLE));
        assertTrue(BotDragonFlightManager.followOutbound(TEMPLE_FLIGHT, TEMPLE));
    }

    @Test
    void ownerBehindInTheCorridorSteersInboundTowardLeafre() {
        assertFalse(BotDragonFlightManager.followOutbound(TEMPLE_FLIGHT, LEAFRE_FLIGHT));
        assertFalse(BotDragonFlightManager.followOutbound(TEMPLE_FLIGHT, LEAFRE_DOCK));
        assertFalse(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, LEAFRE_DOCK));
    }

    @Test
    void ownerOffCorridorOrSameMapDefaultsOutbound() {
        assertTrue(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, 100000000)); // owner elsewhere
        assertTrue(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, -1));         // no owner
        assertTrue(BotDragonFlightManager.followOutbound(LEAFRE_FLIGHT, LEAFRE_FLIGHT)); // same map
    }

    @Test
    void enteringSecondOutboundFlightMapGetsFreshLegBudget() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Portal entrance = mock(Portal.class);
        when(bot.getMapId()).thenReturn(TEMPLE_FLIGHT);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(571, -227));
        when(bot.getClient()).thenReturn(mock(client.Client.class));
        when(map.getPortal("in00")).thenReturn(entrance);
        when(entrance.getPortalStatus()).thenReturn(true);
        when(entrance.getPosition()).thenReturn(new Point(571, -227));
        BotEntry entry = new BotEntry(bot, null, null);
        entry.dragonFlightTargetMapId = TEMPLE;
        entry.dragonFlightMapId = LEAFRE_FLIGHT;
        entry.followTravelDeadlineMs = System.currentTimeMillis() - 1;

        assertTrue(BotDragonFlightManager.tick(entry, bot, TEMPLE, true));

        verify(entrance).enterPortal(any());
    }

    @Test
    void expiredBudgetOnSameFlightLegStillAborts() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Portal entrance = mock(Portal.class);
        when(bot.getMapId()).thenReturn(TEMPLE_FLIGHT);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(571, -227));
        when(map.getPortal("in00")).thenReturn(entrance);
        when(entrance.getPortalStatus()).thenReturn(true);
        when(entrance.getPosition()).thenReturn(new Point(571, -227));
        BotEntry entry = new BotEntry(bot, null, null);
        entry.dragonFlightTargetMapId = TEMPLE;
        entry.dragonFlightMapId = TEMPLE_FLIGHT;
        entry.followTravelDeadlineMs = System.currentTimeMillis() - 1;

        assertFalse(BotDragonFlightManager.tick(entry, bot, TEMPLE, true));

        verify(entrance, never()).enterPortal(any());
    }

    @Test
    void reverseFlightCanBeArmedFromTemple() {
        Character bot = mock(Character.class);
        when(bot.getMapId()).thenReturn(TEMPLE);
        BotEntry entry = new BotEntry(bot, null, null);
        BotWorldGraph.TaxiEdge edge = new BotWorldGraph.TaxiEdge(TEMPLE,
                BotDragonFlightManager.DRAGON_NPC_ID, LEAFRE_DOCK, 0);

        assertTrue(BotDragonFlightManager.beginFromTemple(entry, bot, edge));
        assertEquals(LEAFRE_DOCK, entry.dragonFlightTargetMapId);
        assertEquals(TEMPLE, entry.dragonFlightMapId);
        assertTrue(entry.followTravelDeadlineMs > System.currentTimeMillis());
    }
}
