/* Maple Leaf Shop — opened via the @leaf command.

   Sells every Maple-branded equip for Maple Leaves (ETC item 4001126), which drop globally at 2%
   and are auto-funneled to the player by their bots. TEST PRICING: every item costs 1 Maple Leaf
   (PRICE). Two-level menu — pick a category, pick an item, confirm, pay leaves, receive the equip
   (gainItem gives random stats). Leaves are removed only after a haveItem + canHold check, so a
   failed grant never costs a leaf.

   Coverage: all functional Maple weapons (every class + tier), shields, accessories, hats, medals,
   and novelty/racing gear. The 9 duplicate "Maple 1500 Anniv. Flag" item-ids (one per weapon
   class, all the same flag) are intentionally omitted; the base Maple Flag is in Novelty.
*/

var LEAF = 4001126;   // Maple Leaf (ETC)
var PRICE = 1;        // Maple Leaves per item — test pricing

var CATS = [
    { name: "Warrior — 1H Weapons", items: [
        [1302020, "Maple Sword"],
        [1302030, "Maple Soul Singer"],
        [1302064, "Maple Glory Sword"],
        [1312032, "Maple Steel Axe"],
        [1322054, "Maple Havoc Hammer"]
    ]},
    { name: "Warrior — 2H Weapons & Spears", items: [
        [1402039, "Maple Soul Rohen (2H Sword)"],
        [1412011, "Maple Dragon Axe (2H)"],
        [1412027, "Maple Demon Axe (2H)"],
        [1422014, "Maple Doom Singer (2H Mace)"],
        [1422029, "Maple Belzet (2H Mace)"],
        [1432012, "Maple Impaler (Spear)"],
        [1432040, "Maple Soul Spear"],
        [1442024, "Maple Scorpio (Polearm)"],
        [1442051, "Maple Karstan (Polearm)"]
    ]},
    { name: "Magician Weapons", items: [
        [1372034, "Maple Shine Wand"],
        [1382009, "Maple Staff"],
        [1382012, "Maple Lama Staff"],
        [1382039, "Maple Wisdom Staff"]
    ]},
    { name: "Bowman Weapons", items: [
        [1452016, "Maple Bow"],
        [1452022, "Maple Soul Searcher (Bow)"],
        [1452045, "Maple Kandiva Bow"],
        [1462014, "Maple Crow (Crossbow)"],
        [1462019, "Maple Crossbow"],
        [1462040, "Maple Nishada (Crossbow)"]
    ]},
    { name: "Thief Weapons", items: [
        [1332025, "Maple Wagner (Dagger)"],
        [1332055, "Maple Dark Mate (Dagger)"],
        [1332056, "Maple Asura Dagger"],
        [1472030, "Maple Claw"],
        [1472032, "Maple Kandayo (Claw)"],
        [1472055, "Maple Skanda (Claw)"]
    ]},
    { name: "Pirate Weapons", items: [
        [1482020, "Maple Knuckle"],
        [1482021, "Maple Storm Finger (Knuckle)"],
        [1482022, "Maple Golden Claw (Knuckle)"],
        [1492020, "Maple Gun"],
        [1492021, "Maple Storm Pistol (Gun)"],
        [1492022, "Maple Canon Shooter (Gun)"]
    ]},
    { name: "Shields", items: [
        [1092030, "Maple Shield"],
        [1092046, "Maple Warrior Shield"],
        [1092045, "Maple Magician Shield"],
        [1092047, "Maple Thief Shield"],
        [1092062, "Maple Girl Shield"]
    ]},
    { name: "Accessories", items: [
        [1032040, "Maple Earring (I)"],
        [1032041, "Maple Earring (II)"],
        [1032042, "Maple Earring (III)"],
        [1012098, "Maple Leaf — Face (I)"],
        [1012101, "Maple Leaf — Face (II)"],
        [1012102, "Maple Leaf — Face (III)"],
        [1012103, "Maple Leaf — Face (IV)"],
        [1122015, "Maple Scarf (Pendant)"],
        [1102166, "Maple Cape (I)"],
        [1102167, "Maple Cape (II)"],
        [1102168, "Maple Cape (III)"]
    ]},
    { name: "Hats & Bandanas", items: [
        [1002508, "Maple Hat (I)"],
        [1002509, "Maple Hat (II)"],
        [1002510, "Maple Hat (III)"],
        [1002511, "Maple Hat (IV)"],
        [1002758, "Maple Hat (Lv4)"],
        [1002759, "Maple Hood Hat"],
        [1002513, "Maple Party Hat"],
        [1002515, "Maple Bandana (White)"],
        [1002516, "Maple Bandana (Yellow)"],
        [1002517, "Maple Bandana (Red)"],
        [1002518, "Maple Bandana (Blue)"]
    ]},
    { name: "Medals", items: [
        [1142006, "Maple Idol Medal"],
        [1142100, "Maple Lover Medal (I)"],
        [1142101, "Maple Lover Medal (II)"],
        [1142120, "Maple Explorer Medal"],
        [1142126, "Maple School Medal"]
    ]},
    { name: "Novelty & Racing", items: [
        [1302033, "Maple Flag"],
        [1302058, "Maple Umbrella"],
        [1442030, "Maple Snowboard"],
        [1102164, "Maple MSX Guitar"],
        [1003005, "Maple Racing Helmet"],
        [1052214, "Maple Racing Suit"],
        [1072408, "Maple Racing Shoes"],
        [1082255, "Maple Racing Glove"],
        [1082252, "Maple Gage (Glove)"]
    ]}
];

var MENU = 0, CAT = 1, BUY = 2;
var status = MENU;
var curCat = -1, curItemId = -1;

function leafCount() {
    return cm.itemQuantity(LEAF);
}

function start() {
    sendMainMenu();
}

function sendMainMenu() {
    status = MENU;
    var txt = "Welcome to the #bMaple Leaf Shop#k!\r\n" +
        "Everything here is paid for with #bMaple Leaves#k (#i" + LEAF + "#) — #b" + PRICE + " Maple Leaf#k each.\r\n" +
        "You have #b" + leafCount() + "#k Maple Leaves.\r\n\r\n#bChoose a category:#k";
    for (var i = 0; i < CATS.length; i++) {
        txt += "\r\n#L" + i + "#" + CATS[i].name + "#l";
    }
    cm.sendSimple(txt);
}

function sendCatMenu(c) {
    status = CAT;
    curCat = c;
    var items = CATS[c].items;
    var txt = "#b" + CATS[c].name + "#k — #b" + PRICE + " Maple Leaf#k each " +
        "(you have #b" + leafCount() + "#k):\r\n";
    for (var i = 0; i < items.length; i++) {
        txt += "\r\n#L" + i + "##t" + items[i][0] + "##l";
    }
    cm.sendSimple(txt);
}

function action(mode, type, selection) {
    if (mode < 1) {            // Back / End / No
        if (status == CAT) { sendMainMenu(); return; }
        if (status == BUY) { sendCatMenu(curCat); return; }
        cm.dispose();
        return;
    }

    if (status == MENU) {
        if (selection < 0 || selection >= CATS.length) { cm.dispose(); return; }
        sendCatMenu(selection);
        return;
    }

    if (status == CAT) {
        var items = CATS[curCat].items;
        if (selection < 0 || selection >= items.length) { cm.dispose(); return; }
        curItemId = items[selection][0];
        status = BUY;
        cm.sendYesNo("Buy #t" + curItemId + "# for #b" + PRICE + " Maple Leaf#k?\r\n(You have #b" + leafCount() + "#k.)");
        return;
    }

    if (status == BUY) {       // mode == 1 → Yes
        if (!cm.haveItem(LEAF, PRICE)) {
            cm.sendOk("You need #b" + PRICE + " Maple Leaf#k but don't have enough. Go hunt some more!");
            cm.dispose();
            return;
        }
        if (!cm.canHold(curItemId)) {
            cm.sendOk("Your inventory has no room for that — make some space and come back.");
            cm.dispose();
            return;
        }
        cm.gainItem(LEAF, -PRICE);
        cm.gainItem(curItemId, 1);
        cm.sendOk("Enjoy your #t" + curItemId + "#!  (#r-" + PRICE + " Maple Leaf#k)\r\n" +
                "You have #b" + leafCount() + "#k Maple Leaves left.");
        cm.dispose();
        return;
    }

    cm.dispose();
}
