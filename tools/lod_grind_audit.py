#!/usr/bin/env python3
"""Measure whether the unobserved-map LOD abstract grind reproduces the real grind it stands in for.

LOD's contract (docs/bot/living-server-design.md) is that an unobserved bot progresses exactly as fast
as an observed one. That is an empirical claim, so measure both arms against the same live population:

  real arm      the chosen maps are PINNED to LOD0 via /api/lod, so their bots run real combat, real
                physics and the 50ms tick. realKills/hr is then the ground-truth sustained rate: it
                includes seek time really walked, respawn waits, and contention from the same rivals.
  abstract arm  the pins are released and those same bots fall back to LOD1. absKills/hr is what LOD grants.

Pinning a handful of maps rather than flipping the global SIMPLIFY_UNOBSERVED_BOTS_* flags matters: full
fidelity for the whole roster does not fit in the CPU budget, and a starved tick pool depresses the very
kill rate we are trying to measure. Pinning keeps the comparison paired -- same bots, same maps, same
crowd -- and leaves the rest of the server coarse.

bias = abstract_kph / real_kph, per bot and per (job, level band, map) cell. LOD is outcome-preserving
only where bias == 1.0.

Usage:
  # ground truth on the 6 busiest grind maps (pins them, measures, releases)
  python tools/lod_grind_audit.py --arm real --pick-maps 6 --window 600 --json logs/lod-real.json

  # abstract arm over the same maps (run immediately after, so the learned rates are still fresh)
  python tools/lod_grind_audit.py --arm abstract --maps-from logs/lod-real.json \
      --window 600 --json logs/lod-abstract.json

  # join the two on bot id
  python tools/lod_grind_audit.py --compare logs/lod-real.json logs/lod-abstract.json

  # CPU cost of each LOD option (flips the global flags; restores the shipped config)
  python tools/lod_grind_audit.py --perf-sweep --window 45

Requires the realKills counter and /api/lod on the bot web server.
"""
import argparse, json, statistics, sys, time, urllib.request
from collections import defaultdict

JOBS = {0: "Beginner", 100: "Warrior", 110: "Fighter", 111: "Crusader", 120: "Page",
        121: "WhiteKnight", 130: "Spearman", 131: "DragonKnight", 200: "Magician", 210: "FP",
        211: "FPMage", 220: "IL", 221: "ILMage", 230: "Cleric", 231: "Priest", 300: "Bowman",
        310: "Hunter", 311: "Ranger", 320: "Crossbowman", 321: "Sniper", 400: "Thief",
        410: "Assassin", 411: "Hermit", 420: "Bandit", 421: "ChiefBandit", 500: "Pirate",
        510: "Brawler", 511: "Marauder", 520: "Gunslinger", 521: "Outlaw"}


def get(host, path, timeout=15):
    with urllib.request.urlopen(f"http://{host}{path}", timeout=timeout) as r:
        return json.load(r)


def post(host, body):
    req = urllib.request.Request(f"http://{host}/api/settings",
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.load(r)


# All four must move together for the real arm. GRIND alone is not enough:
#   PHYSICS  (BotManager.stepMovementCore) swaps real nav+physics for a wall-clock lerp independently of
#            GRIND, so seek time would stay modelled rather than really walked -- it biases the reference.
#   TRAVEL   (BotTravelManager) warps bots across maps on a timer, changing who grinds where mid-window.
#   CADENCE  (BotManager.cadenceForLod) is already inert once GRIND is off, since its gate chains through
#            abstractGrindEligible; flipped anyway so the two arms differ in exactly one named condition.
LOD_FLAGS = ("SIMPLIFY_UNOBSERVED_BOTS_GRIND", "SIMPLIFY_UNOBSERVED_BOTS_PHYSICS",
             "SIMPLIFY_UNOBSERVED_BOTS_TRAVEL", "SIMPLIFY_UNOBSERVED_BOTS_CADENCE")


def set_flags(host, **vals):
    """vals: GRIND/PHYSICS/TRAVEL/CADENCE -> bool."""
    for short, on in vals.items():
        field = f"SIMPLIFY_UNOBSERVED_BOTS_{short}"
        r = post(host, {"cmd": "set", "group": "manager", "field": field, "value": str(on).lower()})
        if not r.get("ok"):
            raise SystemExit(f"config write rejected: {field}")
    print("flags " + " ".join(f"{k}={'on' if v else 'off'}" for k, v in vals.items()), file=sys.stderr)


def set_lod(host, simplified):
    set_flags(host, GRIND=simplified, PHYSICS=simplified, TRAVEL=simplified, CADENCE=simplified)
    if not simplified:
        print("NOTE: every unobserved bot is now on the 50ms tick with full nav+physics. "
              "This is the load the LOD system exists to avoid -- watch /api/perf.", file=sys.stderr)


# Configurations for --perf-sweep. The flags are NOT orthogonal: BotManager.cadenceForLod requires
# CADENCE && PHYSICS && abstractGrindEligible (which requires GRIND), so clearing GRIND or PHYSICS also
# forces the 50ms tick. These rows are chosen so each cost can still be attributed by differencing:
#   shipped -> cadence-off   isolates the 500ms->50ms tick cost (everything else still simplified)
#   cadence-off -> grind-off   isolates abstract-kill vs real combat, both at 50ms
#   cadence-off -> physics-off isolates motion-plan lerp vs real nav+physics, both at 50ms
#   shipped -> travel-off      isolates timed-warp vs real cross-map travel
PERF_SWEEP = [
    ("shipped (all on)", dict(GRIND=True, PHYSICS=True, TRAVEL=True, CADENCE=True)),
    ("cadence off", dict(GRIND=True, PHYSICS=True, TRAVEL=True, CADENCE=False)),
    ("grind off", dict(GRIND=False, PHYSICS=True, TRAVEL=True, CADENCE=True)),
    ("physics off", dict(GRIND=True, PHYSICS=False, TRAVEL=True, CADENCE=True)),
    ("travel off", dict(GRIND=True, PHYSICS=True, TRAVEL=False, CADENCE=True)),
    ("all off (LOD0-equiv)", dict(GRIND=False, PHYSICS=False, TRAVEL=False, CADENCE=False)),
]


def perf_sweep(host, seconds, settle, recover):
    out = []
    for label, flags in PERF_SWEEP:
        if out:
            # Return to the shipped config and let the box drain before the next config, otherwise a
            # heavy row's backlog bleeds into the next window and both readings are wrong.
            set_lod(host, True)
            time.sleep(recover)
        set_flags(host, **flags)
        time.sleep(settle)  # let retasks + motion-plan teardown reach steady state
        out.append((label, capture_perf(host, seconds)))
    set_lod(host, True)  # always restore the shipped config
    base = out[0][1]
    print(f"\nCPU by LOD configuration ({seconds}s window each, {len(PERF_SWEEP)} configs)\n")
    print(f"{'config':<24}{'bots':>6}{'proc cores':>11}{'/100 bots':>11}{'ticks/bot':>11}{'vs shipped':>12}")
    for label, p in out:
        star = " STARVED" if p["starved"] else ""
        print(f"{label:<24}{p['bots']:>6}{p['processCore']:>11.2f}{p['corePer100Bots']:>11.2f}"
              f"{p['ticksPerBot']:>11.1f}{p['processCore'] - base['processCore']:>+12.2f}{star}")
    by = {l: p["processCore"] for l, p in out}
    print("\nattribution (process cores; a STARVED row is a floor, not a measurement):")
    print(f"  coarse cadence costs     {by['cadence off'] - by['shipped (all on)']:+.2f}")
    print(f"  abstract grind costs     {by['grind off'] - by['cadence off']:+.2f}  (both at 50ms)")
    print(f"  motion-plan physics costs{by['physics off'] - by['cadence off']:+.2f}  (both at 50ms)")
    print(f"  timed travel costs       {by['travel off'] - by['shipped (all on)']:+.2f}")
    print(f"  ALL simplifications save {by['all off (LOD0-equiv)'] - by['shipped (all on)']:+.2f}")
    if any(p["starved"] for _, p in out):
        print("\nWARNING: at least one config starved the bot tick pool. Its CPU number is a lower bound "
              "and any kill rate measured in that state is depressed. Re-run at a lower population.")
    return out


def capture_perf(host, seconds):
    """One bounded BotPerformanceMonitor window.

    processCore is the primary metric. tick-total's `core` is only trustworthy while the tick pool keeps
    up: once the box saturates, ticks queue instead of running, so tick-total *falls* even as the machine
    works harder. `ticksPerBot` exposes that -- nominally 1000/tickMs (20/s at the 50ms tick), but the
    shared timer pool delivers late well before CPU saturates, so STARVED means "ticks arriving late"
    (fidelity already degrading), not necessarily a pegged CPU. Compare rows against each other, not
    against the nominal rate.
    """
    ms = min(60_000, max(1_000, seconds * 1_000))  # /api/perf caps durationMs at 60000
    d = get(host, f"/api/perf?on=1&durationMs={ms}", timeout=ms / 1000 + 30)  # the call blocks for the window
    get(host, "/api/perf?on=0")
    bots = get(host, "/api/botdebug")["bots"]
    tick = next((s for s in d["sections"] if s["section"] == "tick-total"), None)
    n = max(1, len(bots))
    expected = statistics.mean([1000.0 / b["tickMs"] for b in bots]) if bots else 0.0
    perf = {"sampleMs": d["sampleMs"], "processCore": d["processCore"], "bots": len(bots),
            "tickCore": tick and tick["core"], "tickAvgMs": tick and tick["avgMs"],
            "tickCallsPerSec": tick and tick["callsPerSec"],
            "ticksPerBot": (tick["callsPerSec"] / n) if tick else 0.0, "expectedTicksPerBot": expected,
            "corePer100Bots": d["processCore"] * 100.0 / n,
            "hot": [{"section": s["section"], "core": s["core"], "sharePct": s["sharePct"]}
                    for s in d["sections"][:8]]}
    perf["starved"] = perf["ticksPerBot"] < 0.7 * expected if expected else False
    print(f"perf: process {perf['processCore']:.2f} cores over {len(bots)} bots "
          f"({perf['corePer100Bots']:.2f}/100 bots) | ticks/bot {perf['ticksPerBot']:.1f} "
          f"of {expected:.1f} expected{'  << STARVED' if perf['starved'] else ''}", file=sys.stderr)
    return perf


def pin_maps(host, maps):
    r = get(host, f"/api/lod?maps={','.join(map(str, maps))}&force=1")
    print(f"pinned to LOD0: {r['forcedLod0']}", file=sys.stderr)


def clear_pins(host):
    r = get(host, "/api/lod?clear=1")
    print(f"pins cleared: {r['forcedLod0']}", file=sys.stderr)


def pick_maps(host, n):
    """The n maps with the most bots actively grinding on them -- where contention is real. Maps with
    no spawn points (towns, the FM: the grinding flag lingers there) are skipped -- pinning them wastes
    the LOD0 budget and their bots score zero in both arms."""
    crowd = defaultdict(int)
    for b in get(host, "/api/botdebug")["bots"]:
        if b.get("grinding"):
            crowd[b["map"]] += 1
    top = []
    for m in sorted(crowd, key=lambda m: -crowd[m]):
        if len(top) >= n:
            break
        info = get(host, f"/api/mapinfo?id={m}")
        if sum(mob["spawns"] for mob in info.get("mobs", [])) > 0:
            top.append(m)
    print(f"picked maps {top} ({[crowd[m] for m in top]} grinders)", file=sys.stderr)
    return top


def wait_for_lod(host, maps, want, timeout):
    """Block until the grinding bots on `maps` have actually switched LOD. LOD0->LOD1 waits out
    LOD_DOWNGRADE_HYSTERESIS_MS, and a coarse bot only re-evaluates every LOD1_TICK_MS, so this is not
    instant. Measuring before the switch lands would mix both regimes into one window."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        on = [b for b in get(host, "/api/botdebug")["bots"]
              if b["map"] in maps and b.get("grinding")]
        if on and sum(1 for b in on if b["lod"] == want) >= 0.9 * len(on):
            print(f"{len(on)} grinding bots on pinned maps are {want}", file=sys.stderr)
            return
        time.sleep(5)
    print(f"WARNING: timed out waiting for {want}; window may mix LOD regimes", file=sys.stderr)


def snapshot(host):
    return time.time(), {b["id"]: b for b in get(host, "/api/botdebug")["bots"]}


def stable(b0, b1):
    """Score only bots whose grind context never changed across the window."""
    return (b0["map"] == b1["map"] and b0.get("grinding") and b1.get("grinding")
            and b0.get("lod") == b1.get("lod") and b0.get("tickMs") == b1.get("tickMs"))


def measure(host, arm, window, interval, maps):
    counter = "absKills" if arm == "abstract" else "realKills"
    t0, s0 = snapshot(host)
    print(f"t0: {len(s0)} bots, counting {counter} on maps {maps}", file=sys.stderr)
    t1, s1 = t0, s0
    while time.time() - t0 < window:
        time.sleep(min(interval, max(1, window - (time.time() - t0))))
        t1, s1 = snapshot(host)
        print(f"  +{t1 - t0:.0f}s", file=sys.stderr)
    dt = t1 - t0
    if dt < 1:
        raise SystemExit("window too short")

    # Contention: every bot grinding this map competes for the same spawn points.
    crowd = defaultdict(int)
    for b in s1.values():
        if b.get("grinding"):
            crowd[b["map"]] += 1

    spawns, rows, zero_kill = {}, [], []
    for bid, b1 in s1.items():
        b0 = s0.get(bid)
        if not b0 or b1["map"] not in maps or not stable(b0, b1):
            continue
        kills = b1.get(counter, 0) - b0.get(counter, 0)
        if kills <= 0:
            # A stable grinding bot that earned nothing all window: rate-less (no model answer) or
            # starved. A cluster of these in the abstract arm is the "grants nothing" failure mode.
            zero_kill.append({"id": bid, "name": b1["n"], "level": b1["lvl"], "map": b1["map"]})
            continue
        mid = b1["map"]
        if mid not in spawns:
            info = get(host, f"/api/mapinfo?id={mid}")
            spawns[mid] = sum(m["spawns"] for m in info["mobs"])
        detail = get(host, f"/api/botdebug?id={bid}")["bots"][0]["detail"]
        rows.append({"id": bid, "name": b1["n"], "level": b1["lvl"], "job": detail["job"],
                     "map": mid, "aoe": bool(b1.get("aoe")), "kills": kills,
                     "kph": kills * 3600.0 / dt, "spawn_points": spawns[mid],
                     "competitors": crowd[mid], "tickMs": b1.get("tickMs"), "lod": b1["lod"]})
    return {"arm": arm, "windowSeconds": dt, "counter": counter, "maps": maps, "rows": rows,
            "zeroKill": zero_kill}


def summarize(rows, key, label):
    print(f"\n{label}")
    cells = defaultdict(list)
    for r in rows:
        cells[key(r)].append(r["kph"])
    for k in sorted(cells, key=str):
        v = cells[k]
        print(f"  {str(k):<16} n={len(v):<4} median={statistics.median(v):>7.0f} kph  max={max(v):>7.0f}")


def report_arm(res):
    rows = res["rows"]
    if not rows:
        raise SystemExit("no stable grinding bots in window")
    kph = [r["kph"] for r in rows]
    print(f"\narm={res['arm']} window={res['windowSeconds']:.0f}s  {len(rows)} bots scored"
          f" (counter={res['counter']})\n")
    print(f"{'bot':<17}{'lvl':>4} {'job':<12}{'map':>10}{'aoe':>4}{'spawns':>7}{'rivals':>7}{'kph':>8}")
    for r in sorted(rows, key=lambda r: -r["kph"])[:20]:
        print(f"{r['name']:<17}{r['level']:>4} {JOBS.get(r['job'], '?'):<12}{r['map']:>10}"
              f"{'Y' if r['aoe'] else '-':>4}{r['spawn_points']:>7}{r['competitors']:>7}{r['kph']:>8.0f}")
    summarize(rows, lambda r: f"lvl {r['level'] // 10 * 10:>3}", "by level band:")
    summarize(rows, lambda r: JOBS.get(r["job"], "?"), "by class:")
    summarize(rows, lambda r: "AoE" if r["aoe"] else "single-target", "by AoE capability:")
    summarize(rows, lambda r: f"{min(r['competitors'], 6)} rivals", "by map contention:")
    print(f"\npopulation median {statistics.median(kph):.0f} kph "
          f"({3600 / statistics.median(kph):.1f}s per kill)")
    zero = res.get("zeroKill", [])
    if zero:
        names = ", ".join(f"{z['name']}(lv{z['level']}@{z['map']})" for z in zero[:8])
        print(f"WARNING: {len(zero)} stable grinding bots earned ZERO kills all window "
              f"(rate-less or starved): {names}{'...' if len(zero) > 8 else ''}")


def compare(real_path, abs_path):
    real = {r["id"]: r for r in json.load(open(real_path))["rows"]}
    abst = {r["id"]: r for r in json.load(open(abs_path))["rows"]}
    joined = []
    for bid, a in abst.items():
        r = real.get(bid)
        # Only a bot that stayed on the same map across BOTH arms is comparable.
        if r and r["map"] == a["map"] and r["kph"] > 0:
            joined.append({**a, "real_kph": r["kph"], "abstract_kph": a["kph"],
                           "bias": a["kph"] / r["kph"]})
    if not joined:
        raise SystemExit("no bots present in both arms on the same map")
    print(f"\n{len(joined)} bots measured in both arms. bias = abstract / real; 1.00 = LOD preserves outcomes\n")
    print(f"{'bot':<17}{'lvl':>4} {'job':<12}{'aoe':>4}{'rivals':>7}{'real':>8}{'abstract':>10}{'bias':>8}")
    for r in sorted(joined, key=lambda r: -r["bias"])[:25]:
        print(f"{r['name']:<17}{r['level']:>4} {JOBS.get(r['job'], '?'):<12}"
              f"{'Y' if r['aoe'] else '-':>4}{r['competitors']:>7}"
              f"{r['real_kph']:>8.0f}{r['abstract_kph']:>10.0f}{r['bias']:>8.2f}")

    def bias_cells(key, label):
        print(f"\n{label}")
        cells = defaultdict(list)
        for r in joined:
            cells[key(r)].append(r["bias"])
        for k in sorted(cells, key=str):
            v = cells[k]
            print(f"  {str(k):<16} n={len(v):<4} median bias={statistics.median(v):>6.2f}x"
                  f"  [{min(v):.2f} .. {max(v):.2f}]")

    bias_cells(lambda r: f"lvl {r['level'] // 10 * 10:>3}", "bias by level band:")
    bias_cells(lambda r: JOBS.get(r["job"], "?"), "bias by class:")
    bias_cells(lambda r: "AoE" if r["aoe"] else "single-target", "bias by AoE capability:")
    bias_cells(lambda r: f"{min(r['competitors'], 6)} rivals", "bias by map contention:")
    b = [r["bias"] for r in joined]
    print(f"\npopulation median bias {statistics.median(b):.2f}x  "
          f"({sum(1 for x in b if x > 1.25)}/{len(b)} bots over-granted, "
          f"{sum(1 for x in b if x < 0.8)}/{len(b)} under-granted)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="localhost:8089")
    ap.add_argument("--arm", choices=["real", "abstract"])
    ap.add_argument("--window", type=int, default=600, help="sampling seconds")
    ap.add_argument("--interval", type=int, default=60, help="seconds between snapshots")
    ap.add_argument("--maps", default=None, help="comma-separated map ids to measure")
    ap.add_argument("--pick-maps", type=int, default=0, help="auto-pick the N busiest grind maps")
    ap.add_argument("--maps-from", default=None, help="reuse the map set from a prior arm's --json output")
    ap.add_argument("--keep-pins", action="store_true", help="leave the real arm's LOD0 pins in place")
    ap.add_argument("--no-set", action="store_true", help="do not pin/release; measure as-is")
    ap.add_argument("--json", default=None)
    ap.add_argument("--compare", nargs=2, metavar=("REAL", "ABSTRACT"))
    ap.add_argument("--perf-sweep", action="store_true",
                    help="measure tick CPU under each LOD configuration, then restore the shipped config")
    ap.add_argument("--perf", type=int, default=0, help="capture an N-second perf window before the arm")
    ap.add_argument("--settle", type=int, default=20, help="seconds to wait after a flag flip")
    ap.add_argument("--recover", type=int, default=45,
                    help="seconds at the shipped config between perf-sweep rows, to drain any backlog")
    a = ap.parse_args()

    if a.compare:
        compare(*a.compare)
        return 0
    if a.perf_sweep:
        res = perf_sweep(a.host, min(60, max(10, a.window)), a.settle, a.recover)
        if a.json:
            with open(a.json, "w", encoding="utf-8") as f:
                json.dump([{"config": l, **p} for l, p in res], f, indent=2)
            print(f"\nwrote {a.json}")
        return 0
    if not a.arm:
        ap.error("--arm, --compare or --perf-sweep required")
    if a.maps_from:
        maps = json.load(open(a.maps_from))["maps"]   # reuse the real arm's exact map set
    elif a.pick_maps:
        maps = pick_maps(a.host, a.pick_maps)
    elif a.maps:
        maps = [int(m) for m in a.maps.split(",")]
    else:
        ap.error("--maps, --pick-maps or --maps-from required for an arm")

    if not a.no_set:
        wait = max(a.settle, 60)  # a LOD0->LOD1 demotion waits out LOD_DOWNGRADE_HYSTERESIS_MS first
        if a.arm == "real":
            pin_maps(a.host, maps)
            wait_for_lod(a.host, maps, "LOD0", wait)
        else:
            clear_pins(a.host)
            wait_for_lod(a.host, maps, "LOD1", wait)
    perf = capture_perf(a.host, a.perf) if a.perf else None
    try:
        res = measure(a.host, a.arm, a.window, a.interval, maps)
    finally:
        if a.arm == "real" and not a.no_set and not a.keep_pins:
            clear_pins(a.host)  # never leave maps pinned to full fidelity
    res["perf"] = perf
    report_arm(res)
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump(res, f, indent=2)
        print(f"\nwrote {a.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
