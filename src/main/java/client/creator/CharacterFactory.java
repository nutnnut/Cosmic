/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package client.creator;

import client.Character;
import client.Client;
import client.SkinColor;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import tools.PacketCreator;

/**
 * @author RonanLana
 */
public abstract class CharacterFactory {
    private static final Logger log = LoggerFactory.getLogger(CharacterFactory.class);

    protected synchronized static int createNewCharacter(Client c, String name, int face, int hair, int skin, int gender, CharacterFactoryRecipe recipe) {
        if (YamlConfig.config.server.COLLECTIVE_CHARSLOT ? c.getAvailableCharacterSlots() <= 0 : c.getAvailableCharacterWorldSlots() <= 0) {
            return -3;
        }

        if (!Character.canCreateChar(name)) {
            return -1;
        }

        Character newCharacter = Character.getDefault(c);
        newCharacter.setWorld(c.getWorld());
        newCharacter.setSkinColor(SkinColor.getById(skin));
        newCharacter.setGender(gender);
        newCharacter.setName(name);
        newCharacter.setHair(hair);
        newCharacter.setFace(face);

        newCharacter.setLevel(recipe.getLevel());
        newCharacter.setJob(recipe.getJob());
        newCharacter.setMapId(recipe.getMap());

        Inventory equipped = newCharacter.getInventory(InventoryType.EQUIPPED);
        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        int top = recipe.getTop(), bottom = recipe.getBottom(), shoes = recipe.getShoes(), weapon = recipe.getWeapon();

        if (top > 0) {
            Item eq_top = ii.getEquipById(top);
            eq_top.setPosition((byte) -5);
            equipped.addItemFromDB(eq_top);
        }

        // Every character starts with the bound quest ring (Lilin's Ring) equipped in ring slot 2.
        // Its stats grow with completed quests (see Character.applyQuestRingBoost).
        Item questRing = ii.getEquipById(ItemId.QUEST_RING);
        questRing.setPosition((byte) -13);
        questRing.setFlag((short) (questRing.getFlag() | ItemConstants.UNTRADEABLE | ItemConstants.LOCK));
        equipped.addItemFromDB(questRing);

        if (bottom > 0) {
            Item eq_bottom = ii.getEquipById(bottom);
            eq_bottom.setPosition((byte) -6);
            equipped.addItemFromDB(eq_bottom);
        }

        if (shoes > 0) {
            Item eq_shoes = ii.getEquipById(shoes);
            eq_shoes.setPosition((byte) -7);
            equipped.addItemFromDB(eq_shoes);
        }

        if (weapon > 0) {
            Item eq_weapon = ii.getEquipById(weapon);
            eq_weapon.setPosition((byte) -11);
            equipped.addItemFromDB(eq_weapon.copy());
        }

        if (!MakeCharInfoValidator.isNewCharacterValid(newCharacter)) {
            log.warn("Owner from account {} tried to packet edit in character creation", c.getAccountName());
            return -2;
        }

        if (!newCharacter.insertNewChar(recipe)) {
            return -2;
        }

//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5000000, (short) 5, (short)1)); // brownKitty
//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5190001, (short) 6, (short)1)); // petHPSkill
//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5190006, (short) 7, (short)1)); // petMPSkill
//
//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5190000, (short) 8, (short)1)); // Item Pick-up Skill
//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5190002, (short) 9, (short)1)); // Expand Range Skill
//        newCharacter.getInventory(InventoryType.CASH).addItem(new Item(5190003, (short) 10, (short)1)); // Automatic Pick-Up Skill

        c.sendPacket(PacketCreator.addNewCharEntry(newCharacter));

        Server.getInstance().createCharacterEntry(newCharacter);
        Server.getInstance().broadcastGMMessage(c.getWorld(), PacketCreator.sendYellowTip("[New Char]: " + c.getAccountName() + " has created a new character with IGN " + name));
        log.info("Account {} created chr with name {}", c.getAccountName(), name);

        return 0;
    }
}
