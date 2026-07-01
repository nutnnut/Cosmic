package server.bots;

import org.junit.jupiter.api.Test;
import server.maps.MapFactory;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotNavigationMapLoaderTest {

    @Test
    void mapFactoryResolvesVictoriaMapNames() {
        assertEquals("Pig Park", MapFactory.loadPlaceName(100000003));
        assertEquals("Hidden Street", MapFactory.loadStreetName(100000003));
    }

    @Test
    void loadMapGeometryCarriesStringWzMapNames() {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(100000003);

        assertEquals("Pig Park", map.getMapName());
        assertEquals("Hidden Street", map.getStreetName());
    }
}
