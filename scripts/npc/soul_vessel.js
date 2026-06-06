/* Soul Vessel dialog — invoked via the @vessel command.

   The Soul Vessel is the rebirth ring (see service.RebirthRingService).
   This dialog lets the player:
     - read a short lore blurb (the NPC knows it's an NPC; classic LumenMS meta voice)
     - view their per-class stack breakdown (Warrior x2, Magician x1, ...)
     - preview the stats their CURRENT class will engrave on the ring next time
     - inspect the ring's current absolute stat totals (the ones written to the equip)
     - request a re-issue if they don't have the ring (uses RebirthRingService.reissueRing)

   All numeric work goes through RebirthRingService so the dialog stays a thin view.
*/

var RebirthRingService = Java.type('service.RebirthRingService');
var RebirthService = Java.type('service.RebirthService');
var Job = Java.type('client.Job');

var ROOT = 0;
var LORE = 1;
var STACKS = 2;
var PREVIEW = 3;
var CURRENT_STATS = 4;
var REISSUE = 5;

var status = ROOT;

function start() {
    sendMenu();
}

function sendMenu() {
    var stacks = RebirthRingService.getStacks(cm.getPlayer().getId());
    var has = " (#b" + stacks.total() + "#k engravings)";
    cm.sendSimple(
        "I am the keeper of the #bSoul Vessel#k. Yes — I'm an NPC. No, that doesn't make this " +
        "any less real to your character.\r\n\r\n" +
        "Each time you are reborn, the class you leave behind etches itself onto your ring. " +
        "What would you like to do?" + has + "\r\n\r\n" +
        "#L1#What IS this ring?#l\r\n" +
        "#L2#Show me my engravings#l\r\n" +
        "#L3#What will my NEXT rebirth add?#l\r\n" +
        "#L4#What stats does my ring have right now?#l\r\n" +
        "#L5#I lost my ring — please re-issue it#l"
    );
}

function lore() {
    cm.sendNext(
        "The Soul Vessel is a small ring. Looks unimpressive. The catch is what it #bremembers#k.\r\n\r\n" +
        "Every rebirth, the version of you that walks away — Warrior, Magician, Bowman, Thief or " +
        "Pirate — leaves a residue inside it. Specialize and one stat dominates. Spread out and " +
        "you become a generalist with a little of everything.\r\n\r\n" +
        "The ring grows with diminishing returns (#bstat = base × √stacks#k), so the tenth " +
        "Warrior rebirth still helps, but nowhere near as much as the first."
    );
}

function classifyBranch(job) {
    if (job == null) return 0;
    var id = job.getId();
    var b = Math.floor(id / 100);
    if (b >= 1 && b <= 5) return b;
    if (id >= 1000 && id < 2000) {
        b = Math.floor((id % 1000) / 100);
        if (b >= 1 && b <= 5) return b;
    }
    return 0;
}

function branchName(b) {
    return ["(unmapped)", "Warrior", "Magician", "Bowman", "Thief", "Pirate"][b];
}

function showStacks() {
    var s = RebirthRingService.getStacks(cm.getPlayer().getId());
    var text = "Your engravings so far:\r\n\r\n" +
        "  Warrior:  #b" + s.warrior + "#k\r\n" +
        "  Magician: #b" + s.magician + "#k\r\n" +
        "  Bowman:   #b" + s.bowman + "#k\r\n" +
        "  Thief:    #b" + s.thief + "#k\r\n" +
        "  Pirate:   #b" + s.pirate + "#k\r\n\r\n" +
        "Total: #b" + s.total() + "#k (= rebirth count, give or take any unmapped jobs).";
    cm.sendNext(text);
}

function showCurrentStats() {
    var s = RebirthRingService.getStacks(cm.getPlayer().getId());
    if (s.total() == 0) {
        cm.sendNext("You haven't been reborn yet — your ring is unwritten.");
        return;
    }
    var v = RebirthRingService.previewStats(s);
    cm.sendNext(statTable("Your ring currently grants:", v));
}

function showPreview() {
    var player = cm.getPlayer();
    var s = RebirthRingService.getStacks(player.getId());

    var leavingBranch = classifyBranch(player.getJob());
    if (leavingBranch == 0) {
        cm.sendNext("Your current job doesn't map to an explorer branch, so a rebirth from here " +
                "would leave the ring unchanged. Switch to a Warrior/Magician/Bowman/Thief/Pirate " +
                "lineage to engrave.");
        return;
    }

    // Build a hypothetical stacks bucket with +1 on the leaving branch
    // (factory hop avoids Nashorn's nested-class instantiation quirks).
    var nextStacks = RebirthRingService.stacksOf(
        s.warrior  + (leavingBranch == 1 ? 1 : 0),
        s.magician + (leavingBranch == 2 ? 1 : 0),
        s.bowman   + (leavingBranch == 3 ? 1 : 0),
        s.thief    + (leavingBranch == 4 ? 1 : 0),
        s.pirate   + (leavingBranch == 5 ? 1 : 0)
    );
    var before = RebirthRingService.previewStats(s);
    var after = RebirthRingService.previewStats(nextStacks);

    var text = "Rebirthing now as a #b" + branchName(leavingBranch) + "#k would update your ring to:\r\n\r\n";
    text += diffRow("STR  ", before.str,  after.str);
    text += diffRow("DEX  ", before.dex,  after.dex);
    text += diffRow("INT  ", before._int, after._int);
    text += diffRow("LUK  ", before.luk,  after.luk);
    text += diffRow("MaxHP", before.hp,   after.hp);
    text += diffRow("MaxMP", before.mp,   after.mp);
    text += diffRow("Avoid", before.avoid, after.avoid);
    text += diffRow("Speed", before.speed, after.speed);
    cm.sendNext(text);
}

function statTable(header, v) {
    return header + "\r\n\r\n" +
        "  STR:   #b+" + v.str   + "#k\r\n" +
        "  DEX:   #b+" + v.dex   + "#k\r\n" +
        "  INT:   #b+" + v._int  + "#k\r\n" +
        "  LUK:   #b+" + v.luk   + "#k\r\n" +
        "  MaxHP: #b+" + v.hp    + "#k\r\n" +
        "  MaxMP: #b+" + v.mp    + "#k\r\n" +
        "  Avoid: #b+" + v.avoid + "#k\r\n" +
        "  Speed: #b+" + v.speed + "#k";
}

function diffRow(label, before, after) {
    var arrow = (after > before) ? (" → #b" + after + "#k (+" + (after - before) + ")") :
                                   (" → #b" + after + "#k");
    return "  " + label + ": " + before + arrow + "\r\n";
}

function reissue() {
    var ok = RebirthRingService.reissueRing(cm.getPlayer());
    if (ok) {
        cm.sendNext("Done. Your Soul Vessel has been restored with all your accumulated engravings.");
    } else {
        var s = RebirthRingService.getStacks(cm.getPlayer().getId());
        if (s.total() == 0) {
            cm.sendNext("You have no engravings to bind a ring to. Be reborn at Level " +
                    RebirthService.REBIRTH_LEVEL + " first.");
        } else {
            cm.sendNext("I can't hand it to you — your equip inventory is full. Make a slot and " +
                    "come back.");
        }
    }
}

function action(mode, type, selection) {
    if (mode < 1) {
        // Back/End: return to root after a leaf screen, or close from root.
        if (status == ROOT) {
            cm.dispose();
            return;
        }
        status = ROOT;
        sendMenu();
        return;
    }

    if (status == ROOT) {
        switch (selection) {
            case 1: status = LORE;          lore(); break;
            case 2: status = STACKS;        showStacks(); break;
            case 3: status = PREVIEW;       showPreview(); break;
            case 4: status = CURRENT_STATS; showCurrentStats(); break;
            case 5: status = REISSUE;       reissue(); break;
            default: cm.dispose();
        }
        return;
    }

    // Any leaf screen: clicking Next/End returns to the root menu.
    status = ROOT;
    sendMenu();
}
