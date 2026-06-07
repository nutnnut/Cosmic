-- Add the Damage Skin picker (item 5910000, the reusable picker opener) to Miki's shop
-- (9201060) for 500,000 mesos, directly below the Hyper Teleport Rock (5041002, position 20).
-- Everything below the rock shifts down one slot to open position 19; relative order is preserved.
DELETE FROM shopitems WHERE shopid = 9201060 AND itemid = 5910000;
UPDATE shopitems SET position = position - 1 WHERE shopid = 9201060 AND position < 20;
INSERT INTO shopitems (shopid, itemid, price, pitch, position)
VALUES (9201060, 5910000, 500000, 0, 19);
