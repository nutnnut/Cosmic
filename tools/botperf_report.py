#!/usr/bin/env python3
"""Turn a bot-perf CSV into a single self-contained HTML report (no deps, no internet).

Usage:  py tools/botperf_report.py logs/bot-perf/bot-perf-<ts>.csv [out.html]
Writes <csv>.html next to the CSV if no out path given, and prints the path.
"""
import csv, sys, html, os

# ponytail: CSS bars instead of a charting lib — it's one <div> width per row, no JS, opens anywhere.

def load(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))

def fnum(row, key):
    try:
        return float(row[key])
    except (KeyError, ValueError):
        return 0.0

def bar(frac, color):
    pct = max(0.0, min(1.0, frac)) * 100
    return f'<div class="bar"><div class="fill" style="width:{pct:.2f}%;background:{color}"></div></div>'

def build(rows):
    rows = sorted(rows, key=lambda r: fnum(r, "cpu_core"), reverse=True)
    total = next((r for r in rows if r["section"] == "tick-total"), None)
    max_share = max((fnum(r, "share_pct") for r in rows), default=1.0) or 1.0
    max_max = max((fnum(r, "max_ms") for r in rows), default=1.0) or 1.0

    tr = []
    for r in rows:
        sec = html.escape(r["section"])
        share = fnum(r, "share_pct")
        avg = fnum(r, "avg_ms")
        mx = fnum(r, "max_ms")
        cps = fnum(r, "calls_per_s")
        slow = fnum(r, "slow_pct")
        note = html.escape(r.get("note", ""))
        # spike = big single max relative to its avg (cold-path tax), flag it
        spike = mx > 20 and avg > 0 and mx / avg > 50
        mxcolor = "#e06c4f" if spike else "#7aa6c2"
        rowcls = ' class="total"' if r["section"] == "tick-total" else ""
        tr.append(f"""<tr{rowcls}>
  <td class="sec">{sec}</td>
  <td class="n">{share:.2f}%</td><td>{bar(share/max_share,'#5b9bd5')}</td>
  <td class="n">{avg:.3f}</td>
  <td class="n">{mx:.1f}</td><td>{bar(mx/max_max,mxcolor)}</td>
  <td class="n">{cps:,.0f}</td>
  <td class="n">{slow:.3f}%</td>
  <td class="note">{note}</td>
</tr>""")

    hdr = ""
    if total:
        hdr = (f'<p class="hdr">tick-total: <b>{fnum(total,"cpu_core"):.2f}</b> cores &middot; '
               f'avg <b>{fnum(total,"avg_ms"):.3f}</b> ms &middot; '
               f'max <b>{fnum(total,"max_ms"):,.0f}</b> ms &middot; '
               f'<b>{fnum(total,"calls_per_s"):,.0f}</b> ticks/s</p>')

    return f"""<!doctype html><meta charset="utf-8"><title>bot-perf report</title>
<style>
 body{{font:13px/1.4 system-ui,Segoe UI,sans-serif;margin:24px;color:#1c2733;background:#fafbfc}}
 h1{{font-size:18px;margin:0 0 4px}} .hdr{{color:#5a6b7b;margin:0 0 16px}}
 table{{border-collapse:collapse;width:100%}} th,td{{padding:4px 8px;text-align:left}}
 th{{border-bottom:2px solid #d0d8e0;font-size:11px;text-transform:uppercase;color:#5a6b7b}}
 tr:nth-child(even){{background:#f0f3f6}} tr.total{{background:#fff3d6;font-weight:600}}
 td.n{{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}}
 td.sec{{font-family:Consolas,monospace}} td.note{{color:#5a6b7b;font-size:11px;max-width:340px}}
 .bar{{width:120px;height:12px;background:#e3e9ef;border-radius:3px;overflow:hidden}}
 .fill{{height:100%}}
 .legend{{color:#e06c4f;font-size:11px;margin-top:12px}}
</style>
<h1>bot-perf report</h1>{hdr}
<table>
<tr><th>section</th><th>share</th><th></th><th>avg ms</th><th>max ms</th><th></th>
    <th>calls/s</th><th>slow%</th><th>note</th></tr>
{''.join(tr)}
</table>
<p class="legend">Red max-ms bar = cold-path spike (max &gt; 50&times; avg): one-off warm-up tax, not steady cost.</p>
"""

def main():
    if len(sys.argv) < 2:
        sys.exit("usage: botperf_report.py <csv> [out.html]")
    csvp = sys.argv[1]
    outp = sys.argv[2] if len(sys.argv) > 2 else os.path.splitext(csvp)[0] + ".html"
    with open(outp, "w", encoding="utf-8") as f:
        f.write(build(load(csvp)))
    print(outp)

if __name__ == "__main__":
    main()
