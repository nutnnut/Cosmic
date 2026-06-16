package client.creator;

import client.Character;
import client.Client;
import client.Job;
import client.SkinColor;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.MapId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;

/**
 * Creates bot/companion characters server-side using the same Character.getDefault +
 * insertNewChar pipeline as normal character creation, but without the client-packet
 * validation (MakeCharInfoValidator) which is irrelevant for server-initiated creation.
 */
public class BotCreator extends CharacterFactory {
    private static final Logger log = LoggerFactory.getLogger(BotCreator.class);

    public static int createCharacter(Client c, String name) {
        if (!Character.canCreateChar(name)) {
            log.warn("Bot creation rejected — invalid name '{}'", name);
            return -1;
        }

        Character botChar = Character.getDefault(c);
        botChar.setWorld(c.getWorld());
        botChar.setSkinColor(SkinColor.getById(0));
        botChar.setGender(0);
        botChar.setName(name);
        botChar.setHair(30020);
        botChar.setFace(20100);
        botChar.setJob(Job.BEGINNER);
        botChar.setLevel(1);
        botChar.setMapId(MapId.MUSHROOM_TOWN);

        // Equip standard beginner starting gear (mirrors CharacterFactory.createNewCharacter)
        Inventory equipped = botChar.getInventory(InventoryType.EQUIPPED);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        Item top = ii.getEquipById(1040002);    // White Undershirt
        top.setPosition((byte) -5);
        equipped.addItemFromDB(top);

        Item bottom = ii.getEquipById(1060002); // Undies (blue shorts)
        bottom.setPosition((byte) -6);
        equipped.addItemFromDB(bottom);

        Item shoes = ii.getEquipById(1072001);  // Rubber Boots
        shoes.setPosition((byte) -7);
        equipped.addItemFromDB(shoes);

        Item weapon = ii.getEquipById(1302000); // Wooden Sword
        weapon.setPosition((byte) -11);
        equipped.addItemFromDB(weapon.copy());

        CharacterFactoryRecipe recipe = new CharacterFactoryRecipe(Job.BEGINNER, 1, MapId.MUSHROOM_TOWN, 1040002, 1060002, 1072001, 1302000);

        if (!botChar.insertNewChar(recipe)) {
            log.error("insertNewChar failed for bot '{}'", name);
            return -1;
        }

        log.info("Bot character '{}' created for account id {}", name, c.getAccID());
        return botChar.getId();
    }
}