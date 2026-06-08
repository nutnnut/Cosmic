/* Bot Slots dialog — opened via the @botslots command.

   One purchase expands EVERY spawned bot's four main inventory tabs (Equip/Use/Set-up/Etc) by 8
   slots each for 10,000 NX. The actual expansion and the 96-slot-per-tab cap live in
   BotManager.expandBotInventorySlots (Java); this script handles the NX check, confirmation,
   and charge. NX (NX Credit, cash type 1) persists with the player on save, same as other
   NX-granting scripts (see 1022101_old.js).
*/

var BotManager = Java.type('server.bots.BotManager');

var COST = 10000;      // NX
var PER_TAB = 8;       // slots added to each inventory tab
var NX_CREDIT = 1;     // CashShop currency type

var ROOT = 0, CONFIRM = 1, DONE = 2;
var status = ROOT;

function botCount() {
    // describeBots is public and returns one line per spawned bot.
    return BotManager.getInstance().describeBots(cm.getPlayer().getId(), null).size();
}

function nx() {
    return cm.getPlayer().getCashShop().getCash(NX_CREDIT);
}

function start() {
    var n = botCount();
    if (n == 0) {
        status = DONE;
        cm.sendOk("You don't have any bots out at the moment. Summon your bots first, then come back and I'll roomy up their packs.");
        return;
    }
    status = ROOT;
    cm.sendSimple(
        "Your bots haul a lot of loot, and a full pack means dropped treasure goes to waste.\r\n\r\n" +
        "For #b" + COST + " NX#k I'll add #b" + PER_TAB + " slots#k to #beach#k inventory tab " +
        "(Equip, Use, Set-up, Etc) of #ball " + n + "#k of your spawned bots — capped at #b96#k per tab.\r\n\r\n" +
        "You currently have #b" + nx() + " NX#k.\r\n\r\n" +
        "#L0#Expand all my bots' inventory (+" + PER_TAB + " per tab) for " + COST + " NX#l"
    );
}

function action(mode, type, selection) {
    if (mode < 1) {        // End chat / No / Back
        cm.dispose();
        return;
    }

    if (status == DONE) {
        cm.dispose();
        return;
    }

    if (status == ROOT) {
        if (nx() < COST) {
            cm.sendOk("That costs #b" + COST + " NX#k, but you only have #b" + nx() + " NX#k. Come back when you've saved up.");
            cm.dispose();
            return;
        }
        status = CONFIRM;
        cm.sendYesNo("Spend #b" + COST + " NX#k to add #b" + PER_TAB + "#k slots to every inventory tab of all your spawned bots?");
        return;
    }

    if (status == CONFIRM) {
        // mode == 1 here means Yes.
        if (nx() < COST) {
            cm.sendOk("You no longer have enough NX. Nothing was charged.");
            cm.dispose();
            return;
        }

        var expanded = BotManager.getInstance().expandBotInventorySlots(cm.getPlayer(), PER_TAB);

        if (expanded < 0) {
            cm.sendOk("I couldn't find any of your bots to expand. Nothing was charged.");
            cm.dispose();
            return;
        }
        if (expanded == 0) {
            cm.sendOk("All of your bots' inventory tabs are already at the maximum #b96#k slots. Nothing to expand — nothing charged.");
            cm.dispose();
            return;
        }

        cm.getPlayer().getCashShop().gainCash(NX_CREDIT, -COST);
        cm.sendOk("Done! I expanded #b" + expanded + "#k bot" + (expanded == 1 ? "" : "s") +
                " by #b" + PER_TAB + "#k slots per tab. #b" + COST + " NX#k deducted — you have #b" + nx() + " NX#k left.");
        cm.dispose();
        return;
    }

    cm.dispose();
}
