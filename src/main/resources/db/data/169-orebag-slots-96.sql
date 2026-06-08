-- Increase existing ore bags to 96 slots (new bags default to 96 via OreStorage.create).
-- The ore bag uses the storage UI; 96 matches the inventory cap and stays under the v83
-- storage-window ceiling.
UPDATE orestorages SET slots = 96 WHERE slots < 96;
