# Hot-thread profiler for the live Cosmic server.
# Finds which JVM threads burn CPU — the fast way to attribute a "java.exe is bursty"
# Task Manager reading to actual bot code (tick vs nav-warmup vs GC vs decide pool).
#
# Usage:  powershell -File tools/bot_hotthreads.ps1 [-Port 8089] [-Top 10] [-Delta 4]
#   -Port   LAN web-server port, used to auto-find the server PID (default 8089)
#   -Top    how many threads to rank by cumulative CPU (default 10)
#   -Delta  seconds to measure a LIVE CPU rate over (default 4); 0 to skip
#
# Needs the JDK's jstack (Amazon Corretto 21 on this box).
param([int]$Port = 8089, [int]$Top = 10, [int]$Delta = 4)

$jstack = "C:\Program Files\Amazon Corretto\jdk21.0.4_7\bin\jstack.exe"
$srv = (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
if (-not $srv) { Write-Error "No listener on port $Port — is the server running?"; exit 1 }
Write-Host "server PID=$srv  (port $Port)" -ForegroundColor Cyan

# jstack reports a cumulative cpu= per thread and (on this build) a DECIMAL nid that matches the OS thread id.
$dump = & $jstack $srv
$threads = foreach ($line in $dump) {
  if ($line -match '"([^"]+)".*\scpu=([0-9.]+)ms.*\snid=(\d+)\s') {
    [pscustomobject]@{ Name = $matches[1]; nid = [int]$matches[3]; CPUs = [math]::Round([double]$matches[2] / 1000, 1) }
  }
}
Write-Host "`n=== Top $Top threads by cumulative CPU ===" -ForegroundColor Yellow
$threads | Sort-Object CPUs -Descending | Select-Object -First $Top | Format-Table CPUs, nid, Name -AutoSize

# Live rate: measure the busiest thread's CPU growth over -Delta seconds.
if ($Delta -gt 0) {
  $hot = ($threads | Sort-Object CPUs -Descending | Select-Object -First 1)
  $get = { ((Get-Process -Id $srv).Threads | Where-Object { $_.Id -eq $hot.nid } | ForEach-Object { $_.TotalProcessorTime.TotalSeconds }) }
  $a = & $get; Start-Sleep -Seconds $Delta; $b = & $get
  if ($a -ne $null -and $b -ne $null) {
    Write-Host ("`nLIVE: '{0}' burned {1:N2}s over {2}s = {3:N0}% of one core" -f $hot.Name, ($b - $a), $Delta, (($b - $a) / $Delta * 100)) -ForegroundColor Green
  }
  # show what it's doing right now
  $i = ($dump | Select-String ("nid={0}\s" -f $hot.nid)).LineNumber
  if ($i) { Write-Host "--- current stack (top 8) ---"; $dump[$i..([math]::Min($i + 8, $dump.Count - 1))] }
}
