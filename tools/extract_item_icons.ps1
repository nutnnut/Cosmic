# Extract item icon PNGs from the binary client WZs into the /market web view's icon folder.
#
# Why: the repo's wz/ tree is an XML dump with canvas basedata (the PNG bytes) stripped, so the
# market page's /api/market/icon seam needs icons extracted from a real client. Sources:
#   wz-client\Character.wz  (equip icons: one <id>.img per equip, info/icon canvas)
#   wz-client\Item.wz       (use/setup/etc icons: per-100 group imgs, <id>/info/icon canvas)
# Only ids the v83 server actually knows (file/imgdir names in the repo's XML wz/) are extracted,
# so a modern client dump does not flood the jar with items this server can never trade.
#
# Requires HaRepacker-resurrected's MapleLib.dll (https://github.com/lastbattle/Harepacker-resurrected),
# default location D:\GameServers\Maplestory\tools\WZharepackerx86. If Add-Type fails with
# BadImageFormat, rerun under 32-bit PowerShell: C:\Windows\SysWOW64\WindowsPowerShell\v1.0\powershell.exe.
#
# Usage:  powershell -ExecutionPolicy Bypass -File tools\extract_item_icons.ps1
# Output: src\main\resources\web\item-icons\<itemId>.png  (gitignored; served from the jar classpath,
#         so run `mvn package` + restart after extraction)

param(
    [string]$RepoRoot = (Split-Path $PSScriptRoot -Parent),
    [string]$ClientWzDir = (Join-Path (Split-Path $PSScriptRoot -Parent) 'wz-client'),
    [string]$MapleLibDir = 'D:\GameServers\Maplestory\tools\WZharepackerx86',
    [string]$OutDir = (Join-Path (Split-Path $PSScriptRoot -Parent) 'src\main\resources\web\item-icons')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
# MapleLib's texture decode pulls SharpDX at specific versions; resolve every dependency from the
# HaRepacker folder regardless of the version stamp (the GUI does this via .exe.config redirects).
$script:libDir = $MapleLibDir
[System.AppDomain]::CurrentDomain.add_AssemblyResolve({
    param($s, $e)
    $name = (New-Object System.Reflection.AssemblyName($e.Name)).Name
    $p = Join-Path $script:libDir "$name.dll"
    if (Test-Path $p) { return [System.Reflection.Assembly]::LoadFrom($p) }
    return $null
})
[void][System.Reflection.Assembly]::LoadFrom((Join-Path $MapleLibDir 'MapleLib.dll'))

# ---- wanted ids from the server's XML wz (the v83 item universe) ----
$xmlWz = Join-Path $RepoRoot 'wz'
$wanted = New-Object 'System.Collections.Generic.HashSet[string]'
Get-ChildItem (Join-Path $xmlWz 'Character.wz') -Recurse -Filter '*.img.xml' | ForEach-Object {
    $n = $_.BaseName -replace '\.img$', ''
    if ($n -match '^\d{8}$') { [void]$wanted.Add($n) }
}
Get-ChildItem (Join-Path $xmlWz 'Item.wz') -Recurse -Filter '*.img.xml' | ForEach-Object {
    foreach ($m in [regex]::Matches((Get-Content $_.FullName -Raw), '<imgdir name="(\d{8})"')) {
        [void]$wanted.Add($m.Groups[1].Value)
    }
}
Write-Host "wanted ids from XML wz: $($wanted.Count)"

New-Item -ItemType Directory -Force $OutDir | Out-Null

function Open-Wz([string]$path) {
    foreach ($ver in 'GMS', 'BMS', 'EMS', 'CLASSIC') {
        try {
            $wz = New-Object MapleLib.WzLib.WzFile($path, [MapleLib.WzLib.WzMapleVersion]::$ver)
            $status = $wz.ParseWzFile()
            # Newer MapleLib returns WzFileParseStatus; older returns void (then $status is $null).
            if ($null -ne $status -and "$status" -ne 'Success') { $wz.Dispose(); continue }
            if ($null -eq $wz.WzDirectory) { $wz.Dispose(); continue }
            Write-Host "opened $(Split-Path $path -Leaf) as $ver"
            return $wz
        } catch { }
    }
    throw "could not parse $path with any known encryption"
}

function Get-Node($parent, [string]$path) {
    # PowerShell can't always bind MapleLib's C# indexers; GetFromPath works on WzImage and
    # WzImageProperty alike.
    if ($null -eq $parent) { return $null }
    return $parent.GetFromPath($path)
}

function Resolve-Canvas($prop) {
    # An icon node may be a UOL reference to the real canvas; follow it.
    for ($i = 0; $i -lt 4 -and $null -ne $prop -and $prop.GetType().Name -eq 'WzUOLProperty'; $i++) {
        $prop = $prop.LinkValue
    }
    if ($null -ne $prop -and $prop.GetType().Name -eq 'WzCanvasProperty') { return $prop }
    return $null
}

function Save-Icon($node, [string]$id, [string]$outDir) {
    $canvas = Resolve-Canvas (Get-Node $node 'info/icon')
    if ($null -eq $canvas) { $canvas = Resolve-Canvas (Get-Node $node 'info/iconRaw') }
    if ($null -eq $canvas) { return $false }
    $bmp = $canvas.GetLinkedWzCanvasBitmap()   # resolves _inlink/_outlink canvases
    if ($null -eq $bmp) { return $false }
    $bmp.Save((Join-Path $outDir ("{0}.png" -f [int]$id)), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    return $true
}

$saved = 0; $missing = 0

# ---- equips: Character.wz/<Category>/<00133700>.img -> info/icon ----
$charWz = Open-Wz (Join-Path $ClientWzDir 'Character.wz')
foreach ($dir in $charWz.WzDirectory.WzDirectories) {
    foreach ($img in $dir.WzImages) {
        $id = $img.Name -replace '\.img$', ''
        if (-not $wanted.Contains($id)) { continue }
        try {
            if (-not $img.Parsed) { [void]$img.ParseImage() }
            if (Save-Icon $img $id $OutDir) { $saved++ } else { $missing++ }
            $img.UnparseImage()   # keep the 32-bit-friendly memory footprint flat
        } catch { $missing++ }
    }
    Write-Host "  Character.wz/$($dir.Name) done (saved so far: $saved)"
}
$charWz.Dispose()

# ---- items: Item.wz/<Category>/<0200>.img/<02000000> -> info/icon ----
$itemWz = Open-Wz (Join-Path $ClientWzDir 'Item.wz')
foreach ($dir in $itemWz.WzDirectory.WzDirectories) {
    if ($dir.Name -eq 'Special') { continue }   # map/portal special imgs, not inventory items
    foreach ($img in $dir.WzImages) {
        try {
            if (-not $img.Parsed) { [void]$img.ParseImage() }
            foreach ($child in @($img.WzProperties)) {
                if ($child.Name -notmatch '^\d{7,8}$') { continue }
                $id = $child.Name.PadLeft(8, '0')
                if (-not $wanted.Contains($id)) { continue }
                if (Save-Icon $child $id $OutDir) { $saved++ } else { $missing++ }
            }
            $img.UnparseImage()
        } catch { $missing++ }
    }
    Write-Host "  Item.wz/$($dir.Name) done (saved so far: $saved)"
}
$itemWz.Dispose()

Write-Host "DONE: $saved icons -> $OutDir  ($missing wanted ids had no extractable icon)"
