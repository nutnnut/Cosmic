-- Bump the global Maple Leaf (4001126) drop chance to 2%.
-- Global drops are rolled as `Randomizer.nextInt(999999) < chance` with no drop-rate multiplier
-- (server.maps.MapleMap.dropGlobalItemsFromMonsterOnMap), so 20000/999999 ~= 2.0%.
-- The row itself was seeded by changeset 151 (db/data/151-global-drop-data.sql); this only raises it.
UPDATE drop_data_global SET chance = 20000 WHERE itemid = 4001126;
