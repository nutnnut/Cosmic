---
name: bot-perf
description: Use when asked to convert, visualize, read, or report on a bot performance CSV (logs/bot-perf/bot-perf-*.csv) — e.g. "convert this log to html", "visualize bot-perf", "make the perf log readable". Also covers interpreting the columns (cold vs warm spikes, the rolling window) emitted by BotPerformanceMonitor.
---

# bot-perf CSV → HTML report

`BotPerformanceMonitor.exportCsv()` writes timestamped per-section CPU stats to `logs/bot-perf/bot-perf-<ts>.csv`. To turn one into a readable report, run the existing converter — do NOT hand-build HTML or transcribe numbers.

## Convert

```
py tools/botperf_report.py logs/bot-perf/bot-perf-<ts>.csv
```

Writes `bot-perf-<ts>.html` next to the CSV (pass a second arg to override the path) and prints the output path. Self-contained HTML: CSS bars, no JS, no deps, no internet. To also open it for the user: `Start-Process <path>.html` (PowerShell). Use `py` (the Windows launcher) — `python`/`python3` aren't on PATH here.

## Reading the output

- Each CSV is a **rolling window snapshot**, not cumulative since boot. The monitor's 15s periodic report calls `statsBySection.clear()`, and `exportCsv` captures only the live window since that last reset (it does not itself reset). Window length ≈ `count / calls_per_s`.
- `share_pct` nests: sub-sections (e.g. `combat-plan`) live inside parents (e.g. `tick-grind-dispatch`), so shares sum to >100%.
- **Cold vs warm:** a large `max_ms` with `slow_pct` near 0 and a tiny `avg_ms` (max > ~50× avg) is a one-off cache warm-up spike, not steady cost — the report flags these with a red max-ms bar. Steady-state hot spots show up as high `cpu_core`/`share_pct` with low max. Don't optimize cold spikes unless they bunch at boot (the quest-scan freeze was that pattern: warm-gated in BotQuestManager via BotGrindAdvisor.isWarm).
