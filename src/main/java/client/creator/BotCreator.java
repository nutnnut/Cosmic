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
import server.bots.BotAppearance;

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
        BotAppearance look = BotAppearance.random();
        botChar.setSkinColor(SkinColor.getById(look.skin));
        botChar.setGender(look.gender);
        botChar.setName(name);
        botChar.setHair(look.hair);
        botChar.setFace(look.face);
        botChar.setJob(Job.BEGINNER);
        botChar.setLevel(1);
        botChar.setMapId(MapId.MUSHROOM_TOWN);

        // Equip gender-legal beginner starting gear (rolled by BotAppearance from the same WZ pools as
        // face/hair) — mirrors CharacterFactory.createNewCharacter, but no longer hardcodes the male
        // top/bottom, which rendered wrong on female bots.
        Inventory equipped = botChar.getInventory(InventoryType.EQUIPPED);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        Item top = ii.getEquipById(look.top);
        top.setPosition((byte) -5);
        equipped.addItemFromDB(top);

        Item bottom = ii.getEquipById(look.bottom);
        bottom.setPosition((byte) -6);
        equipped.addItemFromDB(bottom);

        Item shoes = ii.getEquipById(look.shoes);
        shoes.setPosition((byte) -7);
        equipped.addItemFromDB(shoes);

        Item weapon = ii.getEquipById(look.weapon);
        weapon.setPosition((byte) -11);
        equipped.addItemFromDB(weapon.copy());

        CharacterFactoryRecipe recipe = new CharacterFactoryRecipe(Job.BEGINNER, 1, MapId.MUSHROOM_TOWN,
                look.top, look.bottom, look.shoes, look.weapon);

        if (!botChar.insertNewChar(recipe)) {
            log.error("insertNewChar failed for bot '{}'", name);
            return -1;
        }

        log.info("Bot character '{}' created for account id {}", name, c.getAccID());
        return botChar.getId();
    }
}