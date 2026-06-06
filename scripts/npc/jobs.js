/*
    jobs.js — opened by the !jobs GM command via Maple Administrator (9010000)
    Two-level selection: class tree → specific job → apply.
*/

var status = -1;
var selectedCategory = -1;

var categories = [
    "Warrior",
    "Magician",
    "Bowman",
    "Thief",
    "Pirate",
    "Cygnus Knights",
    "Aran",
    "GM"
];

var jobsByCategory = [
    // Warrior
    [[100,"Warrior"],[110,"Fighter"],[111,"Crusader"],[112,"Hero"],
     [120,"Page"],[121,"White Knight"],[122,"Paladin"],
     [130,"Spearman"],[131,"Dragon Knight"],[132,"Dark Knight"]],
    // Magician
    [[200,"Magician"],[210,"F/P Wizard"],[211,"F/P Mage"],[212,"F/P Arch Mage"],
     [220,"I/L Wizard"],[221,"I/L Mage"],[222,"I/L Arch Mage"],
     [230,"Cleric"],[231,"Priest"],[232,"Bishop"]],
    // Bowman
    [[300,"Bowman"],[310,"Hunter"],[311,"Ranger"],[312,"Bowmaster"],
     [320,"Crossbowman"],[321,"Sniper"],[322,"Marksman"]],
    // Thief
    [[400,"Thief"],[410,"Assassin"],[411,"Hermit"],[412,"Night Lord"],
     [420,"Bandit"],[421,"Chief Bandit"],[422,"Shadower"]],
    // Pirate
    [[500,"Pirate"],[510,"Brawler"],[511,"Marauder"],[512,"Buccaneer"],
     [520,"Gunslinger"],[521,"Outlaw"],[522,"Corsair"]],
    // Cygnus Knights
    [[1000,"Noblesse"],
     [1100,"Dawn Warrior 1"],[1110,"Dawn Warrior 2"],[1111,"Dawn Warrior 3"],[1112,"Dawn Warrior 4"],
     [1200,"Blaze Wizard 1"],[1210,"Blaze Wizard 2"],[1211,"Blaze Wizard 3"],[1212,"Blaze Wizard 4"],
     [1300,"Wind Archer 1"],[1310,"Wind Archer 2"],[1311,"Wind Archer 3"],[1312,"Wind Archer 4"],
     [1400,"Night Walker 1"],[1410,"Night Walker 2"],[1411,"Night Walker 3"],[1412,"Night Walker 4"],
     [1500,"Thunder Breaker 1"],[1510,"Thunder Breaker 2"],[1511,"Thunder Breaker 3"],[1512,"Thunder Breaker 4"]],
    // Aran
    [[2000,"Legend"],[2100,"Aran 1"],[2110,"Aran 2"],[2111,"Aran 3"],[2112,"Aran 4"]],
    // GM
    [[900,"GM"],[910,"SuperGM"]]
];

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        cm.dispose();
        return;
    }

    if (mode == 1) {
        status++;
    } else {
        status--;
    }

    if (status < 0) {
        cm.dispose();
        return;
    }

    if (status == 0) {
        var msg = "Hello, #h #. Which class would you like to be?\r\n\r\n#b";
        for (var i = 0; i < categories.length; i++) {
            msg += "#L" + i + "#" + categories[i] + "#l\r\n";
        }
        cm.sendSimple(msg);

    } else if (status == 1) {
        selectedCategory = selection;
        var jobs = jobsByCategory[selectedCategory];
        var msg = "Choose a job:\r\n\r\n#b";
        for (var i = 0; i < jobs.length; i++) {
            msg += "#L" + i + "#" + jobs[i][1] + " (" + jobs[i][0] + ")#l\r\n";
        }
        cm.sendSimple(msg);

    } else if (status == 2) {
        var jobs = jobsByCategory[selectedCategory];
        var jobId   = jobs[selection][0];
        var jobName = jobs[selection][1];
        cm.changeJobById(jobId);
        cm.sendOk("You are now a #b" + jobName + "#k!");

    } else {
        cm.dispose();
    }
}
