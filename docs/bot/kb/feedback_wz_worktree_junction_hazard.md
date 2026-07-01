---
name: feedback_wz_worktree_junction_hazard
description: "WZ data wipe hazard — deleting a worktree is allowed but must remove any wz junction (non-recursive rmdir) FIRST, else recursive delete wipes real wz/"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 3ba2e094-4330-46cc-ad73-ebfae4332dae
---

Spawning agents with `isolation: worktree` in this repo wiped the real `wz/` directory. Root cause: `wz/` is gitignored and large, so it is ABSENT from a fresh worktree; an agent created a `wz` junction inside its worktree to make a WZ-backed test pass, and a later `git worktree remove --force` followed that Windows reparse point and recursively deleted the real `wz/` target's contents.

**Why:** On Windows, recursive delete of a directory containing a junction/symlink follows the reparse point into the target. `--force` removal of an untracked junction is exactly this footgun. WZ is the irreplaceable game-data source (see [[reference_build_tools]], the wz-data skill).

**How to apply:** Worktrees may be both USED and DELETED (user updated this 2026-06-17) — deletion just must follow a safe procedure. Safe delete: (1) `git worktree list` to find the path; (2) remove any `wz` reparse point INSIDE it FIRST with a NON-recursive command that deletes the link only — `rmdir <worktree>\wz` (cmd) or `(Get-Item <worktree>\wz).Delete()` (PowerShell); never `rm -rf`/`Remove-Item -Recurse` for this step; (3) verify real `wz/` (D:\GameServers\Maplestory\Cosmic\wz) still intact; (4) then `git worktree remove <path>` (`--force` if dirty). If unsure whether a junction exists, run step 2 anyway — `rmdir` on a missing path is harmless. WZ-backed tests are nav/graph-adjacent and usually skipped (CLAUDE.md rule #4), so the junction is rarely worth creating. Documented as CLAUDE.md project rule #7.
