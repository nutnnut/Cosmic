/*
    gachaponFromInventory.js
    Triggered when a player double-clicks a local Gachapon ticket (5220000).

    Flow — Use tickets:
      0. Pre-menu: "Use my tickets" or "View item rates".
      1. Pick a Gachapon location.
      2. Type how many tickets to use (1 – inventory count).
      3. Validate, consume all tickets, roll each one silently, show summary.

    Flow — View item rates:
      0. Pre-menu: select "View item rates".
      1. Pick a Gachapon location.
      2. Show that location's item rates and exit.
*/

var status        = -1;
var viewMode      = false;
var selectedNpcId = -1;
var selectedName  = "";
var rollCount     = 0;
var ticketId      = 5220000;

var npcIds   = [9100100, 9100101, 9100102, 9100103, 9100104, 9100105,
                9100106, 9100107, 9100108, 9100109, 9100110, 9100117];
var mapNames = ["Henesys", "Ellinia", "Perion", "Kerning City", "Sleepywood",
                "Mushroom Shrine", "Showa Spa (M)", "Showa Spa (F)",
                "Ludibrium", "New Leaf City", "El Nath", "Nautilus"];

function start() {
    status   = -1;
    viewMode = false;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode < 0) {
        cm.dispose();
        return;
    }
    if (mode === 0) {
        cm.dispose();
        return;
    }

    status++;

    // ── Step 0: pre-menu ────────────────────────────────────────────────────
    if (status === 0) {
        var count = cm.itemQuantity(ticketId);
        if (count < 1) {
            cm.sendOk("You don't have any #bGachapon tickets#k.");
            cm.dispose();
            return;
        }
        cm.sendSimple(
            "What would you like to do?\r\n\r\n" +
            "#L0# Use my Gachapon tickets#l\r\n" +
            "#L1# View item rates#l"
        );

    // ── Step 1: choose location (shared by both flows) ─────────────────────
    } else if (status === 1) {
        if (selection === 0) {
            viewMode = false;
        } else if (selection === 1) {
            viewMode = true;
        } else {
            cm.dispose();
            return;
        }

        var prompt = viewMode
            ? "Which Gachapon's item rates would you like to see?"
            : "Which Gachapon would you like to use your tickets at?";
        var msg = prompt;
        for (var i = 0; i < mapNames.length; i++) {
            msg += "\r\n#L" + i + "# " + mapNames[i] + "#l";
        }
        cm.sendSimple(msg);

    // ── Step 2: view rates OR choose how many tickets ───────────────────────
    } else if (status === 2) {
        if (selection < 0 || selection >= npcIds.length) {
            cm.dispose();
            return;
        }
        selectedNpcId = npcIds[selection];
        selectedName  = mapNames[selection];

        if (viewMode) {
            var Gachapon = Java.type('server.gachapon.Gachapon');
            cm.sendOk(selectedName + " Gachapon — Item Rates:\r\n\r\n" +
                      Gachapon.GachaponType.getFormattedLootInfo(selectedNpcId));
            cm.dispose();
        } else {
            var count = cm.itemQuantity(ticketId);
            cm.sendGetNumber(
                "How many tickets would you like to use at the #b" + selectedName +
                "#k Gachapon?\r\nYou have #r" + count + "#k ticket(s).",
                1, 1, count
            );
        }

    // ── Step 3: roll tickets and show summary (use-tickets flow only) ────────
    } else if (status === 3) {
        if (viewMode) {
            cm.dispose();
            return;
        }

        rollCount = selection;
        var count = cm.itemQuantity(ticketId);

        if (rollCount < 1 || rollCount > count) {
            cm.sendOk("Invalid number of tickets. You have #r" + count + "#k.");
            cm.dispose();
            return;
        }

        if (!(cm.canHold(1302000) && cm.canHold(2000000) &&
              cm.canHold(3010001) && cm.canHold(4000000))) {
            cm.sendOk(
                "Please free at least one slot in your " +
                "#rEQUIP, USE, SET-UP,#k and #rETC#k inventories first."
            );
            cm.dispose();
            return;
        }

        cm.gainItem(ticketId, -rollCount);

        var items = [];
        for (var i = 0; i < rollCount; i++) {
            items.push(cm.doGachaponAtSilently(selectedNpcId));
        }

        var plural = rollCount > 1 ? "s" : "";
        var msg = "You used #r" + rollCount + "#k ticket" + plural +
                  " at the #b" + selectedName + "#k Gachapon and obtained:\r\n";
        for (var j = 0; j < items.length; j++) {
            msg += "#b#t" + items[j] + "##k\r\n";
        }
        cm.sendOk(msg);

    } else {
        cm.dispose();
    }
}
