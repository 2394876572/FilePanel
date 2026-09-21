# =============================================================================
#  FilePanel - finish an MSI when jpackage's own light.exe call cannot validate.
#
#  WHY THIS EXISTS
#  `jpackage --type msi` generates the WiX sources and objects, then calls
#  light.exe to link them into an .msi. light.exe runs ICE validation by
#  default, and ICE validation talks to the Windows Installer API. In a
#  restricted environment that API is unreachable, so light.exe aborts with
#
#      error LGHT0217 : Error executing ICE action 'ICE01' ...
#          "The Windows Installer Service could not be accessed."
#      error LGHT0216 : An unexpected Win32 exception with error code 0x643
#
#  and jpackage reports "exited with 216 code" without telling you why.
#
#  ICE validation is a LINT PASS over an already-built MSI - it checks MSI
#  authoring rules, it does not change the payload. So skipping it is safe:
#  we suppress every ICE and let light.exe write the .msi it had already built.
#
#  IMPORTANT: this is a WORKAROUND for restricted environments. On a normal
#  Windows machine `scripts\package.cmd installer` succeeds on its own and this
#  script is never called. It also cannot be verified by installing the result
#  here, because installing an MSI needs to write its rollback script into
#  C:\Windows\Installer\ - the same blocked area.
#
#  Usage (normally invoked by package.cmd, not by hand):
#    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\finish-msi.ps1 `
#        -Work <dir with config\ and wixobj\> -Out <target .msi> -Wix <tools\wix314>
# =============================================================================

param(
    [Parameter(Mandatory = $true)][string]$Work,
    [Parameter(Mandatory = $true)][string]$Out,
    [Parameter(Mandatory = $true)][string]$Wix
)

$ErrorActionPreference = 'Stop'

function Fail([string]$message) {
    Write-Host "[finish-msi] ERROR: $message"
    exit 1
}

$light = Join-Path $Wix 'light.exe'
if (-not (Test-Path $light)) { Fail "light.exe not found at $light" }

$configDir = Join-Path $Work 'config'
$objDir = Join-Path $Work 'wixobj'
if (-not (Test-Path $configDir)) { Fail "no WiX sources at $configDir (did jpackage get as far as candle?)" }
if (-not (Test-Path $objDir)) { Fail "no WiX objects at $objDir" }

$objects = Get-ChildItem -Path $objDir -Filter '*.wixobj' | ForEach-Object { $_.FullName }
if ($objects.Count -eq 0) { Fail "no .wixobj files in $objDir" }

# Pick the localization file that matches its own declared culture. jpackage
# generates one per supported locale; using the wrong one makes light.exe report
# every !(loc.X) as unknown, which looks like a broken build but is just a
# language mismatch (that mistake was made once while diagnosing this).
$wxl = Get-ChildItem -Path $configDir -Filter 'MsiInstallerStrings_*.wxl' |
    Where-Object { $_.Name -match '_zh_CN\.wxl$' } | Select-Object -First 1
$culture = 'zh-CN'
if (-not $wxl) {
    $wxl = Get-ChildItem -Path $configDir -Filter 'MsiInstallerStrings_*.wxl' | Select-Object -First 1
    $culture = 'en-us'
    if (-not $wxl) { Fail "no MsiInstallerStrings_*.wxl in $configDir" }
}

New-Item -ItemType Directory -Path (Split-Path $Out -Parent) -Force | Out-Null

$arguments = @(
    '-nologo', '-spdb',
    '-ext', 'WixUtilExtension',
    '-ext', 'WixUIExtension',
    '-b', $configDir,
    '-sice:ICE27', '-sice:ICE91',          # jpackage already suppresses these two
    '-loc', $wxl.FullName,
    # NOTE: must be ONE interpolated string. Writing '-cultures:' + $culture here
    # does not work: inside an array literal the comma binds TIGHTER than '+',
    # so it becomes '-cultures:' + @($culture, '-out', ...) and light.exe then
    # receives a bare 'zh-CN' as a source file name (error LGHT0103).
    "-cultures:$culture",
    '-out', $Out
)
# Suppress every ICE. WiX names them ICE01..ICE99 and ICE100+, so both paddings
# have to be produced - "ICE1" would not match "ICE01".
foreach ($n in 1..99) { $arguments += ('-sice:ICE{0:D2}' -f $n) }
foreach ($n in 100..300) { $arguments += ('-sice:ICE' + $n) }
$arguments += $objects

Write-Host ("[finish-msi] light.exe with {0} objects, culture {1}, ICE validation suppressed" -f $objects.Count, $culture)
& $light @arguments 2>&1 | Where-Object { $_ -notmatch 'LGHT0217' } | ForEach-Object { Write-Host "  $_" }
$code = $LASTEXITCODE

if (-not (Test-Path $Out)) { Fail "light.exe produced no MSI (exit $code)" }

# Structural sanity check: a Windows Installer database is an OLE2 compound
# file, so it must start with D0 CF 11 E0 A1 B1 1A E1. Checking the magic bytes
# is weak evidence, but it does catch "we produced a 0-byte or text file".
# It does NOT prove the MSI installs - only a real install proves that, and that
# has to happen on a machine where the Windows Installer service is reachable.
$head = [byte[]](Get-Content -LiteralPath $Out -Encoding Byte -TotalCount 8)
$magic = ($head | ForEach-Object { $_.ToString('X2') }) -join ' '
if ($magic -ne 'D0 CF 11 E0 A1 B1 1A E1') {
    Fail "output is not an OLE2 compound file (magic: $magic) - not a real MSI"
}

$size = [int]((Get-Item $Out).Length / 1KB)
Write-Host "[finish-msi] OK: $Out  ($size KB, OLE2 magic verified)"
Write-Host "[finish-msi] NOTE: ICE validation was skipped; install/upgrade must be verified on a normal machine."
exit 0
