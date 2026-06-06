/* Style Wizard — invoked via @style command or by talking to NPC 1012117 in the FM.
   Lets any player preview and apply hair, hair color, face, eye color, and skin color
   with no cost and no coupon required.
   NOTE: keep this file in sync with 1012117.js which contains a copy of this logic.
*/

var status = "MENU";
var selType = -1;
var styleOptions = [];

var mhair_all = [
    30010, 30040, 30060, 30070, 30080, 30090, 30100, 30130, 30140,
    30200, 30210, 30230, 30260, 30280, 30310, 30340, 30480, 30490,
    30560, 30690, 30760, 30780, 30850, 30860, 30890, 30920, 30930,
    30950, 33040, 33100
];

var fhair_all = [
    31020, 31090, 31110, 31130, 31140, 31150, 31220, 31230, 31300,
    31330, 31350, 31440, 31510, 31530, 31630, 31700, 31740, 31760,
    31790, 31820, 31860, 31880, 31890, 31920, 31940, 31950,
    34000, 34050, 34110
];

var mface_all = [
    20000, 20001, 20003, 20004, 20005, 20006, 20007, 20008,
    20012, 20014, 20015, 20022, 20028, 20031
];

var fface_all = [
    21000, 21001, 21002, 21003, 21004, 21005, 21006, 21007, 21008,
    21012, 21013, 21014, 21023, 21026
];

var skinIds = [0, 1, 2, 3, 4, 5, 9, 10, 11];

var MENU_TEXT =
    "Welcome to the Style Wizard! What would you like to change?\r\n" +
    "#L0#Male Hair Styles#l\r\n" +
    "#L1#Female Hair Styles#l\r\n" +
    "#L2#Hair Color#l\r\n" +
    "#L3#Male Faces#l\r\n" +
    "#L4#Female Faces#l\r\n" +
    "#L5#Eye Color#l\r\n" +
    "#L6#Skin Color#l";

function pushIfExists(array, itemid) {
    var resolved = cm.getCosmeticItem(itemid);
    if (resolved != -1 && array.indexOf(resolved) == -1) {
        array.push(resolved);
    }
}

function showMainMenu() {
    status = "MENU";
    cm.sendSimple(MENU_TEXT);
}

function start() {
    showMainMenu();
}

function action(mode, type, selection) {
    if (mode < 1) {
        cm.dispose();
        return;
    }

    if (status == "MENU") {
        selType = selection;
        styleOptions = [];

        if (selType == 0) {
            // Male Hair Styles — preserve current hair color
            var colorOffset = parseInt(cm.getPlayer().getHair() % 10);
            for (var i = 0; i < mhair_all.length; i++) {
                pushIfExists(styleOptions, mhair_all[i] + colorOffset);
            }
            if (styleOptions.length == 0) { cm.sendOk("No male hair styles are available."); cm.dispose(); return; }
            status = "CHOOSING";
            cm.sendStyle("Choose a male hairstyle. Click through to preview, press OK to apply.", styleOptions);

        } else if (selType == 1) {
            // Female Hair Styles — preserve current hair color
            var colorOffset = parseInt(cm.getPlayer().getHair() % 10);
            for (var i = 0; i < fhair_all.length; i++) {
                pushIfExists(styleOptions, fhair_all[i] + colorOffset);
            }
            if (styleOptions.length == 0) { cm.sendOk("No female hair styles are available."); cm.dispose(); return; }
            status = "CHOOSING";
            cm.sendStyle("Choose a female hairstyle. Click through to preview, press OK to apply.", styleOptions);

        } else if (selType == 2) {
            // Hair Color — cycle through 8 color variants of current hair shape
            var hairBase = parseInt(cm.getPlayer().getHair() / 10) * 10;
            for (var i = 0; i < 8; i++) {
                pushIfExists(styleOptions, hairBase + i);
            }
            if (styleOptions.length == 0) { cm.sendOk("No hair colors are available for your current style."); cm.dispose(); return; }
            status = "CHOOSING";
            cm.sendStyle("Choose your hair color. Click through to preview, press OK to apply.", styleOptions);

        } else if (selType == 3) {
            // Male Faces — preserve current eye color (hundreds digit of face ID)
            var colorOffset = cm.getPlayer().getFace() % 1000 - (cm.getPlayer().getFace() % 100);
            for (var i = 0; i < mface_all.length; i++) {
                pushIfExists(styleOptions, mface_all[i] + colorOffset);
            }
            if (styleOptions.length == 0) { cm.sendOk("No male faces are available."); cm.dispose(); return; }
            status = "CHOOSING";
            cm.sendStyle("Choose a male face. Click through to preview, press OK to apply.", styleOptions);

        } else if (selType == 4) {
            // Female Faces — preserve current eye color
            var colorOffset = cm.getPlayer().getFace() % 1000 - (cm.getPlayer().getFace() % 100);
            for (var i = 0; i < fface_all.length; i++) {
                pushIfExists(styleOptions, fface_all[i] + colorOffset);
            }
            if (styleOptions.length == 0) { cm.sendOk("No female faces are available."); cm.dispose(); return; }
            status = "CHOOSING";
            cm.sendStyle("Choose a female face. Click through to preview, press OK to apply.", styleOptions);

        } else if (selType == 5) {
            // Eye Color — cycle through 8 color variants of current face shape
            // faceBase zeroes out the hundreds digit (color) while keeping the shape
            var faceBase = parseInt(cm.getPlayer().getFace() / 1000) * 1000 + (cm.getPlayer().getFace() % 100);
            for (var i = 0; i < 8; i++) {
                pushIfExists(styleOptions, faceBase + i * 100);
            }
            if (styleOptions.length == 0) { cm.sendOk("No eye colors are available for your current face."); cm.dispose(); return; }
            status = "CHOOSING";
            cm.sendStyle("Choose your eye color. Click through to preview, press OK to apply.", styleOptions);

        } else if (selType == 6) {
            // Skin Color
            status = "CHOOSING";
            cm.sendStyle("Choose your skin color. Click through to preview, press OK to apply.", skinIds);
        }

    } else if (status == "CHOOSING") {
        if (selType == 0 || selType == 1 || selType == 2) {
            cm.setHair(styleOptions[selection]);
        } else if (selType == 3 || selType == 4 || selType == 5) {
            cm.setFace(styleOptions[selection]);
        } else if (selType == 6) {
            cm.setSkin(skinIds[selection]);
        }
        showMainMenu();
    }
}
