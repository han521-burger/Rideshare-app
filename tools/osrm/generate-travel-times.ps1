<#
.SYNOPSIS
  Regenerates backend/src/main/resources/travel-times.json from OpenStreetMap road data with OSRM.

.DESCRIPTION
  The production travel-time matrix (870 directed durations between the 30 approved points) comes from
  OSRM routing over OpenStreetMap data. This script does the whole procedure on Windows, no Docker needed:

    1. downloads the pinned OSRM release (prebuilt Windows tools) and its car profile
    2. downloads the OpenStreetMap extract for Victoria (Geofabrik) - or uses -OsmFile
    3. prepares the road graph (osrm-extract, osrm-partition, osrm-customize; MLD)
    4. starts osrm-routed on 127.0.0.1 only
    5. runs  ./gradlew :backend:generateTravelTimes  - ONE /table request for the whole 30 x 30 matrix,
       through the strict importer, which refuses anything incomplete
    6. stops osrm-routed

  Everything it downloads goes in tools/osrm/work (git-ignored). It records exactly what was used (OSRM version,
  profile hash, OSM extract file/size/hash/data timestamp) in the "provenance" block of travel-times.json.

  Nothing is sent to any public routing service: OSRM runs locally. The only downloads are the OSRM release, its
  source archive (for the unmodified car.lua profile) and the OSM extract.

  See docs/TRAVEL_TIMES.md.

.PARAMETER WorkDir     Where downloads and the prepared graph live. Default: tools/osrm/work
.PARAMETER OsrmVersion OSRM release tag. Default v26.10.0 (the one the current matrix was made with).
.PARAMETER OsmUrl      OpenStreetMap extract to download. Default: Geofabrik, Victoria (latest).
.PARAMETER OsmFile     Use this local .osm.pbf instead of downloading one (it is still hashed and recorded).
.PARAMETER Port        Local port for osrm-routed. Default 5000.
.PARAMETER Force       Re-prepare the road graph even if one already exists.
#>
param(
    [string]$WorkDir = (Join-Path $PSScriptRoot "work"),
    [string]$OsrmVersion = "v26.10.0",
    [string]$NapiBuild = "8",
    [string]$OsmUrl = "https://download.geofabrik.de/australia-oceania/australia/victoria-latest.osm.pbf",
    [string]$OsmFile = "",
    [int]$Port = 5000,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
New-Item -ItemType Directory -Force $WorkDir | Out-Null
$WorkDir = (Resolve-Path $WorkDir).Path

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }
# Run a native program and judge it by its EXIT CODE only. (Windows PowerShell 5.1 would otherwise treat any text a
# program writes to stderr - OSRM prints harmless warnings there - as a terminating error.)
function Invoke-Native([scriptblock]$Command, [string]$Failure) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $Command
        if ($LASTEXITCODE -ne 0) { throw $Failure }
    } finally { $ErrorActionPreference = $previous }
}
function Download($url, $to) {
    if (Test-Path $to) { Write-Host "    have $(Split-Path $to -Leaf)"; return }
    Write-Host "    downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $to -UseBasicParsing -TimeoutSec 1800
}

# --- 1. OSRM tools and the car profile -------------------------------------------------------------------
Step "OSRM $OsrmVersion"
$toolsDir = Join-Path $WorkDir "osrm-$OsrmVersion"
$bin = Join-Path $toolsDir "binding_napi_v$NapiBuild"
if (-not (Test-Path (Join-Path $bin "osrm-routed.exe"))) {
    $archive = Join-Path $WorkDir "node_osrm-$OsrmVersion-win32-x64.tar.gz"
    Download "https://github.com/Project-OSRM/osrm-backend/releases/download/$OsrmVersion/node_osrm-$OsrmVersion-$NapiBuild-win32-x64-Release.tar.gz" $archive
    New-Item -ItemType Directory -Force $toolsDir | Out-Null
    Invoke-Native { tar -xzf $archive -C $toolsDir } "could not unpack the OSRM tools"
}
$srcArchive = Join-Path $WorkDir "osrm-backend-$OsrmVersion-src.tar.gz"
$profilesDir = Join-Path $WorkDir "osrm-backend-$OsrmVersion-profiles"
if (-not (Test-Path (Join-Path $profilesDir "osrm-backend-*\profiles\car.lua"))) {
    Download "https://github.com/Project-OSRM/osrm-backend/archive/refs/tags/$OsrmVersion.tar.gz" $srcArchive
    New-Item -ItemType Directory -Force $profilesDir | Out-Null
    Invoke-Native { tar -xzf $srcArchive -C $profilesDir --include "*/profiles/*" } "could not unpack the OSRM profiles"
}
$profile = (Get-ChildItem $profilesDir -Recurse -Filter car.lua | Select-Object -First 1).FullName
$profileSha = (Get-FileHash $profile -Algorithm SHA256).Hash.ToLower()

# --- 2. OpenStreetMap extract ----------------------------------------------------------------------------
Step "OpenStreetMap extract"
$extractLastModified = $null
if ($OsmFile) {
    $pbf = (Resolve-Path $OsmFile).Path
    $OsmUrl = "(local file)"
} else {
    $pbf = Join-Path $WorkDir (Split-Path $OsmUrl -Leaf)
    if (-not (Test-Path $pbf)) {
        $head = Invoke-WebRequest -Uri $OsmUrl -Method Head -UseBasicParsing -TimeoutSec 60
        $extractLastModified = [string]$head.Headers["Last-Modified"]
        Set-Content -Path "$pbf.last-modified" -Value $extractLastModified
    } elseif (Test-Path "$pbf.last-modified") {
        # [string]: a string read with Get-Content carries PowerShell metadata that ConvertTo-Json would serialise too.
        $extractLastModified = [string](Get-Content "$pbf.last-modified" -TotalCount 1)
    }
    Download $OsmUrl $pbf
}
$pbfInfo = Get-Item $pbf
$pbfSha = (Get-FileHash $pbf -Algorithm SHA256).Hash.ToLower()
Write-Host "    $($pbfInfo.Name): $([math]::Round($pbfInfo.Length/1MB,1)) MB, sha256 $pbfSha"

# --- 3. prepare the road graph ---------------------------------------------------------------------------
# OSRM's command-line parser cannot read a path that contains a space (C:\Users\Some Name\...), so the OSRM
# tools are run from inside the work directory with RELATIVE paths. (Keep the repository's tools/osrm/work free of
# spaces below it; the folders this script creates there have none.)
Step "Preparing the road graph (car profile, MLD)"
function Relative($path) { $path.Substring($WorkDir.Length).TrimStart("\", "/") }
$base = Join-Path $WorkDir ($pbfInfo.Name -replace "\.osm\.pbf$", "")
$extractLog = "$base.extract.log"
$relBase = Relative $base
$relProfile = Relative $profile
$relPbf = Relative $pbf
if ($relProfile -match " " -or $relPbf -match " ") { throw "OSRM cannot read paths with spaces: $relProfile / $relPbf" }
Push-Location $WorkDir
try {
    if ($Force -or -not (Test-Path "$base.osrm.mldgr")) {
        Invoke-Native { & (Join-Path $bin "osrm-extract.exe") -p $relProfile $relPbf *> $extractLog } "osrm-extract failed - see $extractLog"
        Invoke-Native { & (Join-Path $bin "osrm-partition.exe") "$relBase.osrm" *> "$base.partition.log" } "osrm-partition failed - see $base.partition.log"
        Invoke-Native { & (Join-Path $bin "osrm-customize.exe") "$relBase.osrm" *> "$base.customize.log" } "osrm-customize failed - see $base.customize.log"
    } else {
        Write-Host "    reusing the graph prepared earlier (use -Force to rebuild)"
    }
} finally { Pop-Location }
$osmTimestamp = $null
if (Test-Path $extractLog) {
    $m = Select-String -Path $extractLog -Pattern "timestamp: (\S+)" | Select-Object -First 1
    if ($m) { $osmTimestamp = $m.Matches[0].Groups[1].Value }
}

# --- 4. what was used, recorded in the output ------------------------------------------------------------
$provenance = [ordered]@{
    routingEngine        = "OSRM (Open Source Routing Machine)"
    engineVersion        = $OsrmVersion
    engineSource         = "https://github.com/Project-OSRM/osrm-backend"
    engineLicence        = "BSD 2-Clause (LICENSE.TXT in the osrm-backend release)"
    algorithm            = "MLD"
    profile              = "car.lua (osrm-backend $OsrmVersion profiles/car.lua, unmodified)"
    profileSha256        = $profileSha
    roadData             = "OpenStreetMap"
    roadDataAttribution  = "Contains information from OpenStreetMap: (c) OpenStreetMap contributors"
    roadDataLicence      = "Open Data Commons Open Database License (ODbL) 1.0"
    roadDataLicenceUrl   = "https://opendatacommons.org/licenses/odbl/1-0/"
    roadDataCopyrightUrl = "https://www.openstreetmap.org/copyright"
    extractProvider      = "Geofabrik GmbH (download.geofabrik.de)"
    extractUrl           = $OsmUrl
    extractFile          = $pbfInfo.Name
    extractBytes         = $pbfInfo.Length
    extractSha256        = $pbfSha
    extractLastModified  = [string]$extractLastModified
    osmDataTimestamp     = $osmTimestamp
    note                 = "Free-flow durations from the road network only; no live traffic. Not tuned or adjusted to match any other source."
}
$provenanceFile = Join-Path $WorkDir "provenance.json"
# UTF-8 without a byte-order mark (Windows PowerShell 5.1's Set-Content -Encoding UTF8 would add one).
[System.IO.File]::WriteAllText($provenanceFile, ($provenance | ConvertTo-Json -Depth 4), (New-Object System.Text.UTF8Encoding($false)))

# --- 5. serve locally, ask once, stop --------------------------------------------------------------------
Step "Starting osrm-routed on 127.0.0.1:$Port"
$routed = Start-Process -FilePath (Join-Path $bin "osrm-routed.exe") -PassThru -WindowStyle Hidden -WorkingDirectory $WorkDir `
    -ArgumentList "--algorithm", "mld", "--port", "$Port", "--ip", "127.0.0.1", "$relBase.osrm" `
    -RedirectStandardOutput "$base.routed.log" -RedirectStandardError "$base.routed.err.log"
try {
    $ready = $false
    for ($i = 0; $i -lt 90 -and -not $ready; $i++) {
        Start-Sleep -Seconds 1
        try { Invoke-RestMethod -Uri "http://127.0.0.1:$Port/route/v1/driving/145.1029,-37.9112;145.1362,-37.9105?overview=false" -TimeoutSec 5 | Out-Null; $ready = $true } catch { }
    }
    if (-not $ready) { throw "osrm-routed did not start - see $base.routed.err.log" }

    Step "Generating backend/src/main/resources/travel-times.json (one /table request)"
    Push-Location $repo
    try {
        Invoke-Native { & .\gradlew.bat :backend:generateTravelTimes "--args=--osrm http://127.0.0.1:$Port" --console=plain } "generateTravelTimes failed"
    } finally { Pop-Location }
} finally {
    if ($routed -and -not $routed.HasExited) { Stop-Process -Id $routed.Id -Force }
}
Step "Done. Review the diff of backend/src/main/resources/travel-times.json, run ./gradlew :backend:test, then commit."
