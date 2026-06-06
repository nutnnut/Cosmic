-- Random Beauty Coupon (2002031): global drop from all monsters at ~5%.
-- Global drop chance is rolled as Randomizer.nextInt(999999) < chance, so 50000/999999 = 5.0%.
INSERT INTO drop_data_global (continent, itemid, minimum_quantity, maximum_quantity, questid, chance, comments)
VALUES (-1, 2002031, 1, 1, 0, 50000, 'Random Beauty Coupon (5%)');
