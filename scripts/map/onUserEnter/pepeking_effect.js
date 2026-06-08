// Map 106021500 — King Pepe boss (normal map, no event instance).
//
// Runs on EVERY player entry. If the room doesn't already have a Yeti+Pepe, spawn one random boss
// immediately. Net behavior:
//   * (re-)entering an empty room always gives you a fresh boss to fight;
//   * a boss is never stacked (only spawns when none is present);
//   * killing it does NOT auto-respawn it while you stay (no new entry event fires) — it only comes
//     back on a fresh entry.
// Bots skip onUserEnter, so they never trigger the spawn. Killing the boss credits quest 2330; the
// exit portal (out_pepeking.js) hands out the wedding-hall key once all three Yeti colors are down.
function start(ms) {
    var map = ms.getPlayer().getMap();

    if (map.getMonsterById(3300005) != null ||
        map.getMonsterById(3300006) != null ||
        map.getMonsterById(3300007) != null) {
        return; // a boss is already up — don't add another
    }

    var LifeFactory = Java.type('server.life.LifeFactory');
    var Point = Java.type('java.awt.Point');
    var mobId = 3300000 + (Math.floor(Math.random() * 3) + 5); // 3300005/6/7 = Grey/Gold/White
    map.spawnMonsterOnGroundBelow(LifeFactory.getMonster(mobId), new Point(-28, -67));
}
