-- LumenMS GM Supply Shop
-- Shop ID 9010001, served by Maple Administrator NPC (9010000)
-- Opened in-game via the @shop command (see ShopCommand / scripts/npc/shop.js).

DELETE FROM shopitems WHERE shopid = 9010001;
DELETE FROM shops     WHERE shopid = 9010001;

INSERT INTO shops (shopid, npcid)
VALUES (9010001, 9010000);

-- position DESC = top of list first.
INSERT INTO shopitems (shopid, itemid, price, pitch, position)
VALUES
    -- Potions
    (9010001, 2000000,   50, 0, 52),  -- Red Potion
    (9010001, 2000001,  150, 0, 48),  -- Orange Potion
    (9010001, 2000002,  300, 0, 44),  -- White Potion
    (9010001, 2000003,  100, 0, 40),  -- Blue Potion
    (9010001, 2000006,  300, 0, 36),  -- Mana Elixir
    (9010001, 2000004, 2500, 0, 32),  -- Elixir
    (9010001, 2000005, 5000, 0, 28),  -- Power Elixir
    (9010001, 2050004,  500, 0, 24),  -- All Cure Potion

    -- Scrolls
    (9010001, 2030000,  250, 0, 20),  -- Return Scroll - Nearest Town

    -- Arrows (price = full-stack cost; slotMax 2000)
    (9010001, 2060000, 1000, 0, 16),  -- Arrow for Bow
    (9010001, 2061000, 1000, 0, 12),  -- Arrow for Crossbow

    -- Throwing Stars (price = full-stack cost; slotMax 200)
    (9010001, 2070000, 1000, 0,  8),  -- Subi Throwing-Star

    -- Bullets (price = full-stack cost; slotMax 2000)
    (9010001, 2330000, 1000, 0,  4);  -- Bullet
