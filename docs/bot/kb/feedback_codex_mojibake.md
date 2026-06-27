---
name: feedback_codex_mojibake
description: Windows PowerShell mojibake trap: read Unicode repo docs as UTF-8 before copying patch context
metadata:
  node_type: memory
  type: project
---

Windows PowerShell can display UTF-8 repo docs as mojibake when `Get-Content` output passes through the local ANSI/codepage path. Characters such as arrows, em dashes, ellipses, and comparison symbols may appear as garbage like `โ’`. The file may still be correct UTF-8; the terminal output is the unreliable part.

Do not copy mojibaked terminal output into `apply_patch` context. It will fail against the real file, wastes time, and risks replacing good Unicode with corrupted text.

Safer workflow:
- Prefer `git diff`, `rg -n`, or ASCII-only nearby anchors when patching docs.
- If reading with PowerShell, force UTF-8 first:
  ```powershell
  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
  $OutputEncoding = [System.Text.Encoding]::UTF8
  Get-Content -Encoding UTF8 path\to\file.md
  ```
- Keep new durable KB text ASCII unless Unicode adds real value.
- If output looks mojibaked, stop and re-read with explicit UTF-8 before patching.

Root lesson: terminal rendering is not patch truth. Patch against clean file context, not garbled display text.
