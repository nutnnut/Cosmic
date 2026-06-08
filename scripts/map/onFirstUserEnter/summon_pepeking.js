// No-op. The King Pepe boss is spawned by onUserEnter/pepeking_effect.js, which runs on EVERY entry
// and spawns a boss only if none is already present (so re-entering an empty room always respawns
// one). This onFirstUserEnter hook is referenced by map 106021500 but intentionally does nothing.
function start(ms) {}
