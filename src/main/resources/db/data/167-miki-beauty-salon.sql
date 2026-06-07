-- Add the Beauty Salon slot-unlock item (5920000) to Miki's shop (9201060) for
-- 500,000 mesos, directly below the Damage Skin picker (5910000, position 19).
-- Each one consumed unlocks one Beauty Salon slot (6 max). Everything below the
-- damage skin shifts down one slot to open position 18; relative order is preserved.
DELETE FROM shopitems WHERE shopid = 9201060 AND itemid = 5920000;
UPDATE shopitems SET position = position - 1 WHERE shopid = 9201060 AND position < 19;
INSERT INTO shopitems (shopid, itemid, price, pitch, position)
VALUES (9201060, 5920000, 500000, 0, 18);
