# Capture a bot's nav pathlog via the live web endpoint (/api/bot/pathlog, docs/bot/web-endpoints.md).
# The endpoint is a toggle: first call attaches a ~6s ring-buffer recorder, second call dumps it.
# This script does both calls with the wait in between, resolving the charId from the bot's name.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools/bot_pathlog.ps1 <botName> [-Seconds 8] [-BaseUrl http://localhost:8089]
param(
    [Parameter(Mandatory = $true, Position = 0)][string] $BotName,
    [int] $Seconds = 8,   # ring buffer holds ~6s; sleeping longer just keeps the most recent ~6s
    [string] $BaseUrl = "http://localhost:8089"
)

$ErrorActionPreference = "Stop"

$live = Invoke-RestMethod "$BaseUrl/api/live"
$botId = $null
foreach ($map in $live.maps.PSObject.Properties) {
    foreach ($b in $map.Value.bots) {
        if ($b.n -eq $BotName) { $botId = $b.id; break }
    }
    if ($null -ne $botId) { break }
}
if ($null -eq $botId) {
    Write-Error "bot '$BotName' not found in /api/live (offline or misspelled)"
}

$first = Invoke-RestMethod "$BaseUrl/api/bot/pathlog?id=$botId"
if (-not $first.recording) {
    # a recorder was already attached by an earlier call — this call dumped it
    Write-Output $first.report
    Write-Output ""
    Write-Output "(dumped a pre-existing recording; file: $($first.file))"
    exit 0
}

Write-Output "recording $BotName (id $botId) for $Seconds s..."
Start-Sleep -Seconds $Seconds

$dump = Invoke-RestMethod "$BaseUrl/api/bot/pathlog?id=$botId"
Write-Output $dump.report
Write-Output ""
Write-Output "file: $($dump.file)"
