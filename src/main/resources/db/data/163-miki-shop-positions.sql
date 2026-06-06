-- Move The Magic Rock / The Summoning Rock to sit directly below the
-- Hyper Teleport Rock (5041002, position 20) in Miki's shop (9201060).
UPDATE shopitems SET position = 19 WHERE shopid = 9201060 AND itemid = 4006000;   -- The Magic Rock
UPDATE shopitems SET position = 18 WHERE shopid = 9201060 AND itemid = 4006001;   -- The Summoning Rock
