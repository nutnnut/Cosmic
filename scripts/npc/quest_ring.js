/* Quest Ring dialog — opened via the @ring command.

   Explains the Quest Ring (Lilin's Ring; see Character.applyQuestRingBoost) and shows the player's
   current quest-ring bonuses plus their Soul Vessel (rebirth ring) engravings. The ring itself is
   granted and kept in sync by the @ring command and by login, so this dialog is a thin view.

   Stat growth: +1 to every stat per completed quest, and +1 weapon/magic attack every OTHER quest.
*/

var RebirthRingService = Java.type('service.RebirthRingService');

var ROOT = 0, LORE = 1, QSTATS = 2, VESSEL = 3;
var status = ROOT;

function start() {
    sendMenu();
}

function questCount() {
    return cm.getPlayer().getCompletedQuests().size();
}

function sendMenu() {
    var q = questCount();
    cm.sendSimple(
        "Ah — that #bQuest Ring#k on your finger. Yes, I'm an NPC, and yes, I know all about it.\r\n\r\n" +
        "It remembers every quest you've ever finished and grows stronger for it. You've completed " +
        "#b" + q + "#k so far.\r\n\r\n" +
        "What would you like to know?\r\n\r\n" +
        "#L1#What IS this ring?#l\r\n" +
        "#L2#What does my Quest Ring give right now?#l\r\n" +
        "#L3#Tell me about my Soul Vessel#l"
    );
}

function lore() {
    cm.sendNext(
        "The #bQuest Ring#k is bound to your very soul — it can't be dropped, traded, or sold, and it " +
        "follows you for life.\r\n\r\n" +
        "Every quest you complete etches a little more power into it: #b+1 to every stat#k per quest. " +
        "Its weapon and magic attack grow more slowly — #b+1 attack for every two quests#k.\r\n\r\n" +
        "So go forth and finish quests. The ring only ever grows."
    );
}

function questStats() {
    var q = questCount();
    var atk = Math.floor(q / 2);
    cm.sendNext(
        "Your #bQuest Ring#k currently grants:\r\n\r\n" +
        "  STR / DEX / INT / LUK:  #b+" + q + "#k each\r\n" +
        "  Max HP / Max MP:        #b+" + q + "#k each\r\n" +
        "  Weapon / Magic Attack:  #b+" + atk + "#k each\r\n\r\n" +
        "Quests completed: #b" + q + "#k  (attack rises +1 every other quest).\r\n\r\n" +
        "Make sure it's equipped to enjoy the bonus!"
    );
}

function vessel() {
    var s = RebirthRingService.getStacks(cm.getPlayer().getId());
    if (s.total() == 0) {
        cm.sendNext("Your #bSoul Vessel#k is unwritten — be reborn at Level 200 to start engraving it.");
        return;
    }
    var v = RebirthRingService.previewStats(s);
    cm.sendNext(
        "Your #bSoul Vessel#k (rebirth ring) holds #b" + s.total() + "#k engravings:\r\n\r\n" +
        "  Warrior #b" + s.warrior + "#k  Magician #b" + s.magician + "#k  Bowman #b" + s.bowman + "#k  " +
        "Thief #b" + s.thief + "#k  Pirate #b" + s.pirate + "#k\r\n\r\n" +
        "It grants:\r\n" +
        "  STR+#b" + v.str + "#k DEX+#b" + v.dex + "#k INT+#b" + v._int + "#k LUK+#b" + v.luk + "#k\r\n" +
        "  HP+#b" + v.hp + "#k MP+#b" + v.mp + "#k Avoid+#b" + v.avoid + "#k Speed+#b" + v.speed + "#k"
    );
}

function action(mode, type, selection) {
    if (mode < 1) {
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
            case 1: status = LORE;   lore(); break;
            case 2: status = QSTATS; questStats(); break;
            case 3: status = VESSEL; vessel(); break;
            default: cm.dispose();
        }
        return;
    }

    status = ROOT;
    sendMenu();
}
