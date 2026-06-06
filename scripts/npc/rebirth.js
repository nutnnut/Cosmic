/* Rebirth dialog — invoked via the @rebirth command.

   Two flows:
   - TASTER (players who have never been reborn and never claimed it): choose one of the
     five explorer classes, learn one castable skill from it at max level, and bind it to
     any key. A one-time power boost and a preview of rebirth's cross-class skill keeping.
   - REBIRTH (Lv.200): keep two current-job skills -> pick a class to rebirth into ->
     confirm -> optionally bind the two kept skills to any key.

   Heavy lifting lives in service.RebirthService so the logic stays testable in Java.
*/

var RebirthService = Java.type('service.RebirthService');

// Explorer classes (also the classes you can rebirth into; you return to Lv.10).
var JOB_IDS = [100, 200, 300, 400, 500];
var JOB_NAMES = ["Warrior", "Magician", "Bowman", "Thief", "Pirate"];

// Full-keyboard hotkey picker, organised into categories -> client keymap scancodes.
var KEY_CATEGORIES = ["Letters (A-Z)", "Numbers (0-9)", "Function keys (F1-F12)", "Symbols & Space"];
var KEY_GROUP_NAMES = [
    ["A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
     "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"],
    ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"],
    ["F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"],
    ["- (minus)", "= (equals)", "[ (left bracket)", "] (right bracket)", "; (semicolon)",
     "' (quote)", "` (backtick)", "\\ (backslash)", ", (comma)", ". (period)", "/ (slash)", "Space"]
];
var KEY_GROUP_CODES = [
    [30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
     49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44],
    [2, 3, 4, 5, 6, 7, 8, 9, 10, 11],
    [59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88],
    [12, 13, 26, 27, 39, 40, 41, 43, 51, 52, 53, 57]
];

var status = "START";

// Rebirth flow state.
var skillIds = [];
var skillLabels = [];
var keepIdx1 = -1;
var keepIdx2 = -1;
var targetJob = -1;
var grantedAp = 0;

// Taster flow state.
var tasterIds = [];
var tasterLabels = [];

// Rebind flow state (re-binding already-owned rebirth skills to a hotkey).
var rebindIds = [];
var rebindLabels = [];

// Shared key-picker state.
var keyTargetSkillId = -1;
var keyTargetLabel = "";
var keyReturnContext = "";
var keyCat = -1;

function start() {
    var player = cm.getPlayer();

    if (RebirthService.tasterAvailable(player)) {
        status = "TASTER_CLASS";
        cm.sendSimple("You haven't been reborn yet. As a #btaste of rebirth#k, pick a class to "
                + "borrow one skill from — you'll learn it and can bind it to any key:\r\n" + jobOptions());
        return;
    }

    var eligible = RebirthService.isEligible(player);
    var canRebind = RebirthService.rebindableSkillIds(player).length > 0;

    // Offer both paths when available; otherwise jump straight into the only one.
    if (eligible && canRebind) {
        status = "MAIN_MENU";
        cm.sendSimple("What would you like to do?\r\n"
                + "#L0#Be #breborn#k#l\r\n"
                + "#L1#Rebind a rebirth skill to a hotkey#l");
        return;
    }
    if (eligible) {
        beginRebirth(player);
        return;
    }
    if (canRebind) {
        beginRebind(player);
        return;
    }

    cm.sendOk("You must reach Level " + RebirthService.REBIRTH_LEVEL
            + " before you can be reborn. Come back when you are ready.");
    cm.dispose();
}

function beginRebirth(player) {
    var ids = RebirthService.keepableSkillIds(player);
    if (ids.length < 2) {
        cm.sendOk("You need at least two castable skills from your class to be reborn.");
        cm.dispose();
        return;
    }
    skillIds = [];
    skillLabels = [];
    for (var i = 0; i < ids.length; i++) {
        skillIds.push(ids[i]);
        skillLabels.push(RebirthService.skillLabel(player, ids[i]));
    }
    status = "FIRST_SKILL";
    cm.sendSimple(buildListMenu("So you wish to be #breborn#k. Choose the #rfirst#k skill you want to keep:",
            skillLabels, -1));
}

// List the player's owned rebirth skills so they can rebind any of them to a key.
function beginRebind(player) {
    var ids = RebirthService.rebindableSkillIds(player);
    if (ids.length == 0) {
        cm.sendOk("You have no rebirth skills to rebind yet.");
        cm.dispose();
        return;
    }
    rebindIds = [];
    rebindLabels = [];
    for (var i = 0; i < ids.length; i++) {
        rebindIds.push(ids[i]);
        rebindLabels.push(RebirthService.skillLabel(player, ids[i]));
    }
    status = "REBIND_PICK";
    cm.sendSimple(buildListMenu("Which rebirth skill do you want to bind to a hotkey?", rebindLabels, -1));
}

function jobOptions() {
    var text = "";
    for (var i = 0; i < JOB_NAMES.length; i++) {
        text += "#L" + i + "#" + JOB_NAMES[i] + "#l\r\n";
    }
    return text;
}

function buildListMenu(prompt, labels, excludeIdx) {
    var text = prompt + "\r\n";
    for (var i = 0; i < labels.length; i++) {
        if (i == excludeIdx) {
            continue;
        }
        text += "#L" + i + "#" + labels[i] + "#l\r\n";
    }
    return text;
}

function buildJobMenu() {
    return "Which class do you want to be reborn into? You will start over at Level 10 as your "
            + "chosen class, keeping your two selected skills.\r\n" + jobOptions();
}

function confirmText() {
    var nextAp = RebirthService.AP_PER_REBIRTH * (RebirthService.getRebirthCount(cm.getPlayer().getId()) + 1);
    return "Confirm your rebirth:\r\n"
            + "Keeping: #b" + skillLabels[keepIdx1] + "#k and #b" + skillLabels[keepIdx2] + "#k\r\n"
            + "Reborn as: #b" + jobNameFor(targetJob) + "#k (Level 10)\r\n"
            + "You will receive #b" + nextAp + " AP#k. You keep these two plus any skills from previous "
            + "rebirths; all other skills and stats are reset.\r\n\r\nProceed?";
}

function jobNameFor(jobId) {
    for (var i = 0; i < JOB_IDS.length; i++) {
        if (JOB_IDS[i] == jobId) {
            return JOB_NAMES[i];
        }
    }
    return "your class";
}

// ----- Shared full-keyboard key picker -----

function startKeyPick(skillId, label, context) {
    keyTargetSkillId = skillId;
    keyTargetLabel = label;
    keyReturnContext = context;
    status = "KEY_CATEGORY";
    var text = "Bind #b" + label + "#k to which key? First pick a category:\r\n";
    for (var i = 0; i < KEY_CATEGORIES.length; i++) {
        text += "#L" + i + "#" + KEY_CATEGORIES[i] + "#l\r\n";
    }
    cm.sendSimple(text);
}

function buildKeyMenu(cat) {
    var names = KEY_GROUP_NAMES[cat];
    var text = "Choose a key to bind #b" + keyTargetLabel + "#k to:\r\n";
    for (var i = 0; i < names.length; i++) {
        text += "#L" + i + "#" + names[i] + "#l\r\n";
    }
    return text;
}

function onKeyBound() {
    if (keyReturnContext == "REBIRTH_SKILL2") {
        startKeyPick(skillIds[keepIdx2], skillLabels[keepIdx2], "REBIRTH_DONE");
    } else if (keyReturnContext == "REBIRTH_DONE") {
        cm.sendOk("Your hotkeys are set. Welcome to your new life, #b" + cm.getPlayer().getName() + "#k!");
        cm.dispose();
    } else if (keyReturnContext == "REBIND_DONE") {
        cm.sendOk("#b" + keyTargetLabel + "#k is now bound to your chosen key.");
        cm.dispose();
    } else { // TASTER_DONE
        cm.sendOk("#b" + keyTargetLabel + "#k is yours and bound to your chosen key. Reach Level "
                + RebirthService.REBIRTH_LEVEL + " to be reborn and keep even more skills!");
        cm.dispose();
    }
}

function action(mode, type, selection) {
    // mode < 1 means No / End Chat / Back. Once a grant has happened it simply ends the chat.
    if (mode < 1) {
        cm.dispose();
        return;
    }

    if (status == "MAIN_MENU") {
        if (selection == 0) {
            beginRebirth(cm.getPlayer());
        } else {
            beginRebind(cm.getPlayer());
        }

    } else if (status == "TASTER_CLASS") {
        var baseJob = JOB_IDS[selection];
        var ids = RebirthService.tasterSkillIds(cm.getPlayer(), baseJob);
        if (ids.length == 0) {
            cm.sendOk("There are no new " + JOB_NAMES[selection] + " skills available for you to learn.");
            cm.dispose();
            return;
        }
        tasterIds = [];
        tasterLabels = [];
        for (var i = 0; i < ids.length; i++) {
            tasterIds.push(ids[i]);
            tasterLabels.push(RebirthService.skillName(ids[i]));
        }
        status = "TASTER_SKILL";
        cm.sendSimple(buildListMenu("Choose one skill to learn (granted at max level):", tasterLabels, -1));

    } else if (status == "TASTER_SKILL") {
        var tid = tasterIds[selection];
        RebirthService.grantTasterSkill(cm.getPlayer(), tid);
        startKeyPick(tid, RebirthService.skillName(tid), "TASTER_DONE");

    } else if (status == "REBIND_PICK") {
        startKeyPick(rebindIds[selection], rebindLabels[selection], "REBIND_DONE");

    } else if (status == "FIRST_SKILL") {
        keepIdx1 = selection;
        status = "SECOND_SKILL";
        cm.sendSimple(buildListMenu("Now choose the #rsecond#k skill you want to keep:", skillLabels, keepIdx1));

    } else if (status == "SECOND_SKILL") {
        keepIdx2 = selection;
        status = "JOB";
        cm.sendSimple(buildJobMenu());

    } else if (status == "JOB") {
        targetJob = JOB_IDS[selection];
        status = "CONFIRM";
        cm.sendYesNo(confirmText());

    } else if (status == "CONFIRM") {
        grantedAp = RebirthService.performRebirth(
                cm.getPlayer(), skillIds[keepIdx1], skillIds[keepIdx2], targetJob);
        status = "REBIND_ASK";
        cm.sendYesNo("You have been #breborn#k as a #b" + jobNameFor(targetJob) + "#k with #b"
                + grantedAp + " AP#k!\r\nWould you like to bind your two kept skills to hotkeys now?");

    } else if (status == "REBIND_ASK") {
        startKeyPick(skillIds[keepIdx1], skillLabels[keepIdx1], "REBIRTH_SKILL2");

    } else if (status == "KEY_CATEGORY") {
        keyCat = selection;
        status = "KEY_PICK";
        cm.sendSimple(buildKeyMenu(keyCat));

    } else if (status == "KEY_PICK") {
        var code = KEY_GROUP_CODES[keyCat][selection];
        RebirthService.bindHotkey(cm.getPlayer(), keyTargetSkillId, code);
        onKeyBound();
    }
}
