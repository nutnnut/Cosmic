-- Miki's shop (NPC/shop 9201060) customizations:
--   * Arrow for Bow (2060000) / Arrow for Crossbow (2061000): 1 meso each.
--     The shop "buyable" group is 1000, so a group of 1000 arrows costs 1000 mesos.
--   * Add The Magic Rock (4006000) and The Summoning Rock (4006001).

UPDATE shopitems SET price = 1 WHERE shopid = 9201060 AND itemid IN (2060000, 2061000);

DELETE FROM shopitems WHERE shopid = 9201060 AND itemid IN (4006000, 4006001);

INSERT INTO shopitems (shopid, itemid, price, pitch, position) VALUES
    (9201060, 4006000, 1500, 0, 64),   -- The Magic Rock
    (9201060, 4006001, 1500, 0, 60);   -- The Summoning Rock
