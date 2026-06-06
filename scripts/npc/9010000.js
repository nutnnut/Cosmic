/*
    Maple Administrator (NPC 9010000) — custom job advancement NPC.

    Talking to her checks the player's current job and level. If they meet the
    level requirement for their next advancement she lets them advance on the spot:
        Beginner  -> 1st job  @ Lv.10  (choose class)
        1st job   -> 2nd job  @ Lv.30  (choose path)
        2nd job   -> 3rd job  @ Lv.70  (e.g. Cleric -> Priest)
        3rd job   -> 4th job  @ Lv.120 (e.g. Priest -> Bishop)
    Explorers only; other classes are directed to their own instructors.
*/

var status = -1;
var options = null;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        cm.dispose();
        return;
    }
    if (mode == 0 && status == 0) {
        cm.dispose();
        return;
    }

    if (mode == 1) {
        status++;
    } else {
        status--;
    }

    if (status == 0) {
        var result = computeAdvancement(cm.getJobId(), cm.getLevel());
        if (result.error != null) {
            cm.sendOk(result.error);
            cm.dispose();
            return;
        }
        options = result.options;

        var msg = "Hello, I'm the #bMaple Administrator#k. I handle job advancements.\r\n"
                + "You qualify for the following advancement"
                + (options.length > 1 ? "s" : "") + ":";
        for (var i = 0; i < options.length; i++) {
            msg += "\r\n#L" + i + "##b" + options[i][1] + "#l";
        }
        cm.sendSimple(msg);
        return;
    }

    if (status == 1) {
        if (options == null || selection < 0 || selection >= options.length) {
            cm.dispose();
            return;
        }
        var chosen = options[selection];
        cm.changeJobById(chosen[0]);
        cm.sendOk("Congratulations! You are now a #b" + chosen[1] + "#k!");
        cm.dispose();
        return;
    }

    cm.dispose();
}

function computeAdvancement(job, level) {
    // Beginner -> 1st job
    if (job == 0) {
        if (level < 10) {
            return { error: needLevel(10, "first", level) };
        }
        return { options: [[100, "Warrior"], [200, "Magician"], [300, "Bowman"], [400, "Thief"], [500, "Pirate"]] };
    }

    // Explorers only (100-599)
    if (job < 100 || job > 599) {
        return { error: "I can only help Explorers advance. Please visit your own class instructor." };
    }

    // 1st job (X00) -> 2nd job
    if (job % 100 == 0) {
        if (level < 30) {
            return { error: needLevel(30, "second", level) };
        }
        var second = secondJobOptions(job);
        if (second == null) {
            return { error: "No advancement is available for your class right now." };
        }
        return { options: second };
    }

    // 2nd job (ends in 0) -> 3rd job (job + 1)
    if (job % 10 == 0) {
        if (level < 70) {
            return { error: needLevel(70, "third", level) };
        }
        return { options: [[job + 1, jobName(job + 1)]] };
    }

    // 3rd job (ends in 1) -> 4th job (job + 1)
    if (job % 10 == 1) {
        if (level < 120) {
            return { error: needLevel(120, "fourth", level) };
        }
        return { options: [[job + 1, jobName(job + 1)]] };
    }

    // 4th job (ends in 2) — already maxed
    return { error: "You've already reached the final job advancement. There's nothing more I can do for you!" };
}

function needLevel(req, which, level) {
    return "You need to be at least #bLevel " + req + "#k for your " + which
         + " job advancement. You're currently #rLevel " + level + "#k.";
}

function secondJobOptions(job) {
    switch (job) {
        case 100: return [[110, "Fighter"], [120, "Page"], [130, "Spearman"]];
        case 200: return [[210, "F/P Wizard"], [220, "I/L Wizard"], [230, "Cleric"]];
        case 300: return [[310, "Hunter"], [320, "Crossbowman"]];
        case 400: return [[410, "Assassin"], [420, "Bandit"]];
        case 500: return [[510, "Brawler"], [520, "Gunslinger"]];
        default: return null;
    }
}

function jobName(id) {
    var names = {
        110: "Fighter", 111: "Crusader", 112: "Hero",
        120: "Page", 121: "White Knight", 122: "Paladin",
        130: "Spearman", 131: "Dragon Knight", 132: "Dark Knight",
        210: "F/P Wizard", 211: "F/P Mage", 212: "F/P Arch Mage",
        220: "I/L Wizard", 221: "I/L Mage", 222: "I/L Arch Mage",
        230: "Cleric", 231: "Priest", 232: "Bishop",
        310: "Hunter", 311: "Ranger", 312: "Bowmaster",
        320: "Crossbowman", 321: "Sniper", 322: "Marksman",
        410: "Assassin", 411: "Hermit", 412: "Night Lord",
        420: "Bandit", 421: "Chief Bandit", 422: "Shadower",
        510: "Brawler", 511: "Marauder", 512: "Buccaneer",
        520: "Gunslinger", 521: "Outlaw", 522: "Corsair"
    };
    return names[id] != null ? names[id] : ("Job " + id);
}
