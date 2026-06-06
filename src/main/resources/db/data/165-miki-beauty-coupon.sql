-- Add the Random Beauty Coupon (2002031) to Miki's shop (9201060) for 100 meso.
-- Position 60 sits above the current top item (Red Potion, position 56) so it's easy to find.
DELETE FROM shopitems WHERE shopid = 9201060 AND itemid = 2002031;
INSERT INTO shopitems (shopid, itemid, price, pitch, position)
VALUES (9201060, 2002031, 100, 0, 60);
