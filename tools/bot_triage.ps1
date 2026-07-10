# One-shot stuck-bot triage: bundles the first three lookups of the bot-nav live-debug workflow
# (docs/bot/web-endpoints.md) into a single report so a debug session starts from the full picture.
#   1. /api/live entry        — activity bucket, stuck flag, party/crew, position map
#   2. /api/botdebug?id=      — autopilot internals + detail block (stats, nav edge, decisions, skills)
#   3. /api/mapinfo?id=       — mobs/bots/chat on the bot's current map
#   4. /api/navprobe (only with -X/-Y) — can the bot's own planner reach that point from where it stands?
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools/bot_triage.ps1 <botName> [-X 123 -Y -45] [-BaseUrl http://localhost:8089]
param(
    [Parameter(Mandatory = $true, Position = 0)][string] $BotName,
    [int] $X,
    [int] $Y,
    [switch] $ProbeSkills,   # navprobe with skill edges (teleport/flash-jump) enabled
    [string] $BaseUrl = "http://localhost:8089"
)

$ErrorActionPreference = "Stop"

$live = Invoke-RestMethod "$BaseUrl/api/live"
$botId = $null
$mapId = $null
$liveEntry = $null
foreach ($map in $live.maps.PSObject.Properties) {
    foreach ($b in $map.Value.bots) {
        if ($b.n -eq $BotName) { $botId = $b.id; $mapId = $map.Name; $liveEntry = $b; break }
    }
    if ($null -ne $botId) { break }
}
if ($null -eq $botId) {
    Write-Error "bot '$BotName' not found in /api/live (offline or misspelled)"
}

Write-Output "== /api/live entry (map $mapId) =="
Write-Output ($liveEntry | ConvertTo-Json -Depth 4)

Write-Output ""
Write-Output "== /api/botdebug?id=$botId =="
$debug = Invoke-RestMethod "$BaseUrl/api/botdebug?id=$botId"
Write-Output ($debug | ConvertTo-Json -Depth 8)

Write-Output ""
Write-Output "== /api/mapinfo?id=$mapId =="
$mapinfo = Invoke-RestMethod "$BaseUrl/api/mapinfo?id=$mapId"
Write-Output ($mapinfo | ConvertTo-Json -Depth 6)

if ($PSBoundParameters.ContainsKey('X') -and $PSBoundParameters.ContainsKey('Y')) {
    $skills = 0
    if ($ProbeSkills) { $skills = 1 }
    Write-Output ""
    Write-Output "== /api/navprobe?id=$botId&x=$X&y=$Y&skills=$skills =="
    $probe = Invoke-RestMethod "$BaseUrl/api/navprobe?id=$botId&x=$X&y=$Y&skills=$skills"
    Write-Output ($probe | ConvertTo-Json -Depth 6)
}
