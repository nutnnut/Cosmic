---
name: feedback-subagent-model-tiering
description: "Conserve tokens when spawning subagents: pick the weakest model the task difficulty allows (sonnet/opus/fable), judgment call not a hard rule"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a05517cc-fbd8-4558-83bc-a6266fec5fbc
---

When spawning subagents, pick the model by task difficulty instead of defaulting to the
strongest (2026-06-12 user instruction):
- straightforward tasks (scripted scans, formatting, mechanical edits, simple greps/reports) -> sonnet
- important/moderate tasks (typical feature work, multi-file fixes with a clear spec, read-only
  code audits) -> opus
- hardest tasks only (deep multi-system debugging, physics/reverse-engineering work, ambiguous
  design with high regression risk) -> fable

**Why:** token conservation - Fable subagents are expensive and most delegated work does not
need them; the user also hit session limits twice from heavy agents.

**How to apply:** set the `model` param on the Agent tool. NOT forced: if quality or time saved
plausibly outweighs the risk of a weaker model (e.g. a botched commit costing a redo), picking
a stronger tier is fine - just do not overkill routine work. When a cheap-model agent returns
weak results, escalate the retry one tier instead of iterating at the same tier.
