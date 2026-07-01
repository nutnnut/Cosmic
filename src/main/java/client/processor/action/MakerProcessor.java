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
package client.processor.action;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.YamlConfig;
import constants.game.GameConstants;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.MakerItemFactory;
import server.MakerItemFactory.MakerItemCreateEntry;
import tools.PacketCreator;
import tools.Pair;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author Ronan
 */
public class MakerProcessor {
    private static final Logger log = LoggerFactory.getLogger(MakerProcessor.class);
    private static final ItemInformationProvider ii = ItemInformationProvider.getInstance();

    public static void makerAction(InPacket p, Client c) {
        if (c.tryacquireClient()) {
            try {
                int type = p.readInt();
                int toCreate = p.readInt();

                if (type == 3) {    // building monster crystal
                    makeLeftoverCrystal(c, toCreate);
                    return;
                } else if (type == 4) {  // disassembling
                    p.readInt(); // 1... probably inventory type
                    disassembleEquip(c, (short) p.readInt());
                    return;
                }

                // Equip create: read the stimulant flag + chosen reagent ids off the packet, then run
                // the shared create path (also used by bots via makeItem).
                boolean useStimulant = false;
                List<Integer> reagentItemIds = new LinkedList<>();
                if (ItemConstants.isEquipment(toCreate)) {
                    useStimulant = p.readByte() != 0;
                    int reagents = Math.min(p.readInt(), getMakerReagentSlots(toCreate));
                    for (int i = 0; i < reagents; i++) {  // crystals
                        reagentItemIds.add(p.readInt());
                    }
                }
                makeItem(c, toCreate, useStimulant, reagentItemIds);
            } finally {
                c.releaseClient();
            }
        }
    }

    /**
     * Shared equip-create path (reagents + optional stimulant), used by the Maker UI handler and by
     * bots. The caller supplies the stimulant flag and chosen reagent item ids (the handler reads them
     * off the packet; a bot picks them by EV). Validates materials/meso/level/skill, consumes inputs,
     * rolls + adds the item, and sends the result packets. Assumes the caller already holds the client
     * lock. Returns 0 on success, otherwise the create-status code ({@code -2} = an att/matt gem on a
     * non-weapon was rejected).
     */
    public static short makeItem(Client c, int toCreate, boolean useStimulant, List<Integer> reagentItemIds) {
        Map<Integer, Short> reagentids = new LinkedHashMap<>();
        int stimulantid = -1;
        if (ItemConstants.isEquipment(toCreate)) {   // only equips use stimulant and reagents
            if (useStimulant) {
                stimulantid = ii.getMakerStimulant(toCreate);
                if (!c.getAbstractPlayerInteraction().haveItem(stimulantid)) {
                    stimulantid = -1;
                }
            }
            int slots = getMakerReagentSlots(toCreate);
            for (int reagentid : reagentItemIds) {
                if (reagentids.size() >= slots && !reagentids.containsKey(reagentid)) {
                    continue;
                }
                if (ItemConstants.isMakerReagent(reagentid)) {
                    Short rs = reagentids.get(reagentid);
                    reagentids.put(reagentid, rs == null ? (short) 1 : (short) (rs + 1));
                }
            }

            List<Pair<Integer, Short>> toUpdate = new LinkedList<>();
            for (Map.Entry<Integer, Short> r : reagentids.entrySet()) {
                int qty = c.getAbstractPlayerInteraction().getItemQuantity(r.getKey());
                if (qty < r.getValue()) {
                    toUpdate.add(new Pair<>(r.getKey(), (short) qty));
                }
            }
            for (Pair<Integer, Short> rp : toUpdate) {   // drop reagents not actually in inventory
                if (rp.getRight() > 0) {
                    reagentids.put(rp.getLeft(), rp.getRight());
                } else {
                    reagentids.remove(rp.getLeft());
                }
            }

            if (!reagentids.isEmpty() && !removeOddMakerReagents(toCreate, reagentids)) {
                c.sendPacket(PacketCreator.serverNotice(1, "You can only use WATK and MATK Strengthening Gems on weapon items."));
                c.sendPacket(PacketCreator.makerEnableActions());
                return -2;
            }
        }

        MakerItemCreateEntry recipe = MakerItemFactory.getItemCreateEntry(toCreate, stimulantid, reagentids);
        short createStatus = getCreateStatus(c, recipe);
        if (createStatus != 0) {
            sendMakerCreateFailure(c, createStatus, recipe, toCreate);
            return createStatus;
        }

        for (Pair<Integer, Integer> pair : recipe.getReqItems()) {
            c.getAbstractPlayerInteraction().gainItem(pair.getLeft(), (short) -pair.getRight(), false);
        }

        boolean makerSucceeded = true;
        int cost = recipe.getCost();
        if (stimulantid == -1 && reagentids.isEmpty()) {
            if (cost > 0) {
                c.getPlayer().gainMeso(-cost, false);
            }
            for (Pair<Integer, Integer> pair : recipe.getGainItems()) {
                c.getPlayer().setCS(true);
                c.getAbstractPlayerInteraction().gainItem(pair.getLeft(), pair.getRight().shortValue(), false);
                c.getPlayer().setCS(false);
            }
        } else {
            int created = recipe.getGainItems().get(0).getLeft();
            if (stimulantid != -1) {
                c.getAbstractPlayerInteraction().gainItem(stimulantid, (short) -1, false);
            }
            if (!reagentids.isEmpty()) {
                for (Map.Entry<Integer, Short> r : reagentids.entrySet()) {
                    c.getAbstractPlayerInteraction().gainItem(r.getKey(), (short) (-1 * r.getValue()), false);
                }
            }
            if (cost > 0) {
                c.getPlayer().gainMeso(-cost, false);
            }
            makerSucceeded = addBoostedMakerItem(c, created, stimulantid, reagentids);
        }

        // thanks inhyuk for noticing missing MAKER_RESULT packets
        c.sendPacket(PacketCreator.makerResult(makerSucceeded, recipe.getGainItems().get(0).getLeft(), recipe.getGainItems().get(0).getRight(), recipe.getCost(), recipe.getReqItems(), stimulantid, new LinkedList<>(reagentids.keySet())));
        c.sendPacket(PacketCreator.showMakerEffect(makerSucceeded));
        c.getPlayer().getMap().broadcastMessage(c.getPlayer(), PacketCreator.showForeignMakerEffect(c.getPlayer().getId(), makerSucceeded), false);
        return makerSucceeded ? (short) 0 : (short) 1;
    }

    /**
     * Builds one Monster Crystal from a stack of leftover etc items, sharing the exact
     * player Maker path (eligibility, status checks, item/meso consume, gain, result packets).
     * Used by both the Maker UI handler (type 3) and bots. Assumes the caller already holds
     * the client lock.
     *
     * @return 0 on success, otherwise the failure status already noticed to the client.
     */
    public static short makeLeftoverCrystal(Client c, int fromLeftover) {
        int toCreate = ii.getMakerCrystalFromLeftover(fromLeftover);
        if (toCreate == -1) {
            c.sendPacket(PacketCreator.serverNotice(1, ii.getName(fromLeftover) + " is unavailable for Monster Crystal conversion."));
            c.sendPacket(PacketCreator.makerEnableActions());
            return -1;
        }

        int makerRate = YamlConfig.config.worlds.get(c.getWorld()).maker_rate;
        MakerItemCreateEntry recipe = MakerItemFactory.generateLeftoverCrystalEntry(fromLeftover, toCreate, makerRate);

        short createStatus = getCreateStatus(c, recipe);
        if (createStatus != 0) {
            sendMakerCreateFailure(c, createStatus, recipe, toCreate);
            return createStatus;
        }

        for (Pair<Integer, Integer> pair : recipe.getReqItems()) {
            c.getAbstractPlayerInteraction().gainItem(pair.getLeft(), (short) -pair.getRight(), false);
        }

        int cost = recipe.getCost();
        if (cost > 0) {
            c.getPlayer().gainMeso(-cost, false);
        }

        for (Pair<Integer, Integer> pair : recipe.getGainItems()) {
            c.getPlayer().setCS(true);
            c.getAbstractPlayerInteraction().gainItem(pair.getLeft(), pair.getRight().shortValue(), false);
            c.getPlayer().setCS(false);
        }

        c.sendPacket(PacketCreator.makerResultCrystal(recipe.getGainItems().get(0).getLeft(), recipe.getReqItems().get(0).getLeft()));
        c.sendPacket(PacketCreator.showMakerEffect(true));
        c.getPlayer().getMap().broadcastMessage(c.getPlayer(), PacketCreator.showForeignMakerEffect(c.getPlayer().getId(), true), false);

        if (toCreate == 4260003 && c.getPlayer().getQuestStatus(6033) == 1) {
            c.getAbstractPlayerInteraction().setQuestProgress(6033, 1);
        }

        return 0;
    }

    /**
     * Disassembles one equip (from the EQUIP bag at {@code pos}) into Monster Crystals,
     * sharing the player Maker type-4 path. Used by both the Maker UI handler and bots.
     * Assumes the caller already holds the client lock.
     *
     * @return 0 on success, otherwise the failure status already noticed to the client.
     */
    public static short disassembleEquip(Client c, short pos) {
        Item it = c.getPlayer().getInventory(InventoryType.EQUIP).getItem(pos);
        if (it == null) {
            c.sendPacket(PacketCreator.serverNotice(1, "An unknown error occurred when trying to apply that item for disassembly."));
            c.sendPacket(PacketCreator.makerEnableActions());
            return -1;
        }

        int toDisassemble = it.getItemId();
        Pair<Integer, List<Pair<Integer, Integer>>> info = generateDisassemblyInfo(toDisassemble);
        if (info == null) {
            c.sendPacket(PacketCreator.serverNotice(1, ii.getName(toDisassemble) + " is unavailable for Monster Crystal disassembly."));
            c.sendPacket(PacketCreator.makerEnableActions());
            return -1;
        }

        MakerItemCreateEntry recipe = MakerItemFactory.generateDisassemblyCrystalEntry(toDisassemble, info.getLeft(), info.getRight());

        short createStatus = getCreateStatus(c, recipe);
        if (createStatus != 0) {
            sendMakerCreateFailure(c, createStatus, recipe, toDisassemble);
            return createStatus;
        }

        InventoryManipulator.removeFromSlot(c, InventoryType.EQUIP, pos, (short) 1, false);

        int cost = recipe.getCost();
        if (cost > 0) {
            c.getPlayer().gainMeso(-cost, false);
        }

        for (Pair<Integer, Integer> pair : recipe.getGainItems()) {
            c.getPlayer().setCS(true);
            c.getAbstractPlayerInteraction().gainItem(pair.getLeft(), pair.getRight().shortValue(), false);
            c.getPlayer().setCS(false);
        }

        c.sendPacket(PacketCreator.makerResultDesynth(recipe.getReqItems().get(0).getLeft(), recipe.getCost(), recipe.getGainItems()));
        c.sendPacket(PacketCreator.showMakerEffect(true));
        c.getPlayer().getMap().broadcastMessage(c.getPlayer(), PacketCreator.showForeignMakerEffect(c.getPlayer().getId(), true), false);
        return 0;
    }

    private static void sendMakerCreateFailure(Client c, short createStatus, MakerItemCreateEntry recipe, int toCreate) {
        switch (createStatus) {
            case -1 -> {    // non-available for Maker itemid has been tried to forge
                log.warn("Chr {} tried to craft itemid {} using the Maker skill.", c.getPlayer().getName(), toCreate);
                c.sendPacket(PacketCreator.serverNotice(1, "The requested item could not be crafted on this operation."));
            }
            case 1 ->   // no items
                    c.sendPacket(PacketCreator.serverNotice(1, "You don't have all required items in your inventory to make " + ii.getName(toCreate) + "."));
            case 2 ->   // no meso
                    c.sendPacket(PacketCreator.serverNotice(1, "You don't have enough mesos (" + GameConstants.numberWithCommas(recipe.getCost()) + ") to complete this operation."));
            case 3 ->   // no req level
                    c.sendPacket(PacketCreator.serverNotice(1, "You don't have enough level to complete this operation."));
            case 4 ->   // no req skill level
                    c.sendPacket(PacketCreator.serverNotice(1, "You don't have enough Maker level to complete this operation."));
            case 5 ->   // inventory full
                    c.sendPacket(PacketCreator.serverNotice(1, "Your inventory is full."));
        }
        c.sendPacket(PacketCreator.makerEnableActions());
    }

    // checks and prevents hackers from PE'ing Maker operations with invalid operations
    private static boolean removeOddMakerReagents(int toCreate, Map<Integer, Short> reagentids) {
        Map<Integer, Integer> reagentType = new LinkedHashMap<>();
        List<Integer> toRemove = new LinkedList<>();

        boolean isWeapon = ItemConstants.isWeapon(toCreate) || YamlConfig.config.server.USE_MAKER_PERMISSIVE_ATKUP;  // thanks Vcoc for finding a case where a weapon wouldn't be counted as such due to a bounding on isWeapon

        for (Map.Entry<Integer, Short> r : reagentids.entrySet()) {
            int curRid = r.getKey();
            int type = r.getKey() / 100;

            if (type < 42502 && !isWeapon) {     // only weapons should gain w.att/m.att from these.
                return false;   //toRemove.add(curRid);
            } else {
                Integer tableRid = reagentType.get(type);

                if (tableRid != null) {
                    if (tableRid < curRid) {
                        toRemove.add(tableRid);
                        reagentType.put(type, curRid);
                    } else {
                        toRemove.add(curRid);
                    }
                } else {
                    reagentType.put(type, curRid);
                }
            }
        }

        // removing less effective gems of repeated type
        for (Integer i : toRemove) {
            reagentids.remove(i);
        }

        // the Maker skill will use only one of each gem
        for (Integer i : reagentids.keySet()) {
            reagentids.put(i, (short) 1);
        }

        return true;
    }

    private static int getMakerReagentSlots(int itemId) {
        try {
            int eqpLevel = ii.getEquipLevelReq(itemId);

            if (eqpLevel < 78) {
                return 1;
            } else if (eqpLevel >= 78 && eqpLevel < 108) {
                return 2;
            } else {
                return 3;
            }
        } catch (NullPointerException npe) {
            return 0;
        }
    }

    /** Whether an equip has a Monster Crystal disassembly recipe (fee + crystal yields). */
    public static boolean canDisassemble(int itemId) {
        return generateDisassemblyInfo(itemId) != null;
    }

    private static Pair<Integer, List<Pair<Integer, Integer>>> generateDisassemblyInfo(int itemId) {
        int recvFee = ii.getMakerDisassembledFee(itemId);
        if (recvFee > -1) {
            List<Pair<Integer, Integer>> gains = ii.getMakerDisassembledItems(itemId);
            if (!gains.isEmpty()) {
                return new Pair<>(recvFee, gains);
            }
        }

        return null;
    }

    public static int getMakerSkillLevel(Character chr) {
        return chr.getSkillLevel((chr.getJob().getId() / 1000) * 10000000 + 1007);
    }

    private static short getCreateStatus(Client c, MakerItemCreateEntry recipe) {
        if (recipe.isInvalid()) {
            return -1;
        }

        if (!hasItems(c, recipe)) {
            return 1;
        }

        if (c.getPlayer().getMeso() < recipe.getCost()) {
            return 2;
        }

        if (c.getPlayer().getLevel() < recipe.getReqLevel()) {
            return 3;
        }

        if (getMakerSkillLevel(c.getPlayer()) < recipe.getReqSkillLevel()) {
            return 4;
        }

        List<Integer> addItemids = new LinkedList<>();
        List<Integer> addQuantity = new LinkedList<>();
        List<Integer> rmvItemids = new LinkedList<>();
        List<Integer> rmvQuantity = new LinkedList<>();

        for (Pair<Integer, Integer> p : recipe.getReqItems()) {
            rmvItemids.add(p.getLeft());
            rmvQuantity.add(p.getRight());
        }

        for (Pair<Integer, Integer> p : recipe.getGainItems()) {
            addItemids.add(p.getLeft());
            addQuantity.add(p.getRight());
        }

        if (!c.getAbstractPlayerInteraction().canHoldAllAfterRemoving(addItemids, addQuantity, rmvItemids, rmvQuantity)) {
            return 5;
        }

        return 0;
    }

    private static boolean hasItems(Client c, MakerItemCreateEntry recipe) {
        for (Pair<Integer, Integer> p : recipe.getReqItems()) {
            int itemId = p.getLeft();
            if (c.getPlayer().getInventory(ItemConstants.getInventoryType(itemId)).countById(itemId) < p.getRight()) {
                return false;
            }
        }
        return true;
    }

    private static boolean addBoostedMakerItem(Client c, int itemid, int stimulantid, Map<Integer, Short> reagentids) {
        if (stimulantid != -1 && !ItemInformationProvider.rollSuccessChance(90.0)) {
            return false;
        }

        Item item = ii.getEquipById(itemid);
        if (item == null) {
            return false;
        }

        Equip eqp = (Equip) item;
        if (ItemConstants.isAccessory(item.getItemId()) && eqp.getUpgradeSlots() <= 0) {
            eqp.setUpgradeSlots(3);
        }

        if (YamlConfig.config.server.USE_ENHANCED_CRAFTING == true) {
            if (!(c.getPlayer().isGM() && YamlConfig.config.server.USE_PERFECT_GM_SCROLL)) {
                eqp.setUpgradeSlots((byte) (eqp.getUpgradeSlots() + 1));
            }
            item = ItemInformationProvider.getInstance().scrollEquipWithId(eqp, ItemId.CHAOS_SCROll_60, true, ItemId.CHAOS_SCROll_60, c.getPlayer().isGM());
        }

        if (!reagentids.isEmpty()) {
            Map<String, Integer> stats = new LinkedHashMap<>();
            List<Short> randOption = new LinkedList<>();
            List<Short> randStat = new LinkedList<>();

            for (Map.Entry<Integer, Short> r : reagentids.entrySet()) {
                Pair<String, Integer> reagentBuff = ii.getMakerReagentStatUpgrade(r.getKey());

                if (reagentBuff != null) {
                    String s = reagentBuff.getLeft();

                    if (s.substring(0, 4).contains("rand")) {
                        if (s.substring(4).equals("Stat")) {
                            randStat.add((short) (reagentBuff.getRight() * r.getValue()));
                        } else {
                            randOption.add((short) (reagentBuff.getRight() * r.getValue()));
                        }
                    } else {
                        String stat = s.substring(3);

                        if (!stat.equals("ReqLevel")) {    // improve req level... really?
                            switch (stat) {
                                case "MaxHP":
                                    stat = "MHP";
                                    break;

                                case "MaxMP":
                                    stat = "MMP";
                                    break;
                            }

                            Integer d = stats.get(stat);
                            if (d == null) {
                                stats.put(stat, reagentBuff.getRight() * r.getValue());
                            } else {
                                stats.put(stat, d + (reagentBuff.getRight() * r.getValue()));
                            }
                        }
                    }
                }
            }

            ItemInformationProvider.improveEquipStats(eqp, stats);

            for (Short sh : randStat) {
                ii.scrollOptionEquipWithChaos(eqp, sh, false);
            }

            for (Short sh : randOption) {
                ii.scrollOptionEquipWithChaos(eqp, sh, true);
            }
        }

        if (stimulantid != -1) {
            eqp = ii.randomizeUpgradeStats(eqp);
        }

        InventoryManipulator.addFromDrop(c, item, false, -1);
        return true;
    }
}
