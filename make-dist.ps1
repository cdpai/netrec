# Assemble the standalone distributable: dist/netrec/ plus a zip of it.
#
# Everything that is not the jar lives in pkg/ and is version-controlled; the jar comes from
# target/ and so must already have been built (mvn clean package). The launcher shipped as
# netrec.exe is a copy of jr.exe -- refresh pkg/netrec.exe from the jr project when jr changes.
#
#   .\make-dist.ps1              build from the jar already in target/
#   .\make-dist.ps1 -Build       run mvn clean package first

param([switch]$Build)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

$version = ([xml](Get-Content -LiteralPath (Join-Path $root 'pom.xml'))).project.version
$name    = "netrec-$version-win-x64"
$jar     = Join-Path $root 'target\netrec.jar'
$stage   = Join-Path $root 'dist\netrec'
$zip     = Join-Path $root "dist\$name.zip"

if ($Build) {
    # A running daemon holds the jar open, so a clean would fail while one is up.
    & netrec stop 2>&1 | Out-Null
    & mvn -q clean package
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed ($LASTEXITCODE)" }
}
if (-not (Test-Path $jar)) { throw "target\netrec.jar is missing -- run: mvn clean package" }

if (Test-Path (Join-Path $root 'dist')) { Remove-Item -Recurse -Force (Join-Path $root 'dist') }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

# The launcher is jr.exe under another name. It is NOT kept in this repo: a committed binary
# goes stale silently, and jr is its own project. Take the freshest one available.
$jrCandidates = @(
    (Join-Path $root '..\jr\jr.exe'),
    (Join-Path $root 'pkg\netrec.exe')
) + @((Get-Command jr.exe -ErrorAction SilentlyContinue).Source)
$jrExe = $jrCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $jrExe) {
    throw "no jr.exe found -- clone https://github.com/littlejlib/jr next to this project, or put jr.exe on PATH"
}
Write-Host ("  launcher from : {0}" -f (Resolve-Path $jrExe))

Copy-Item (Join-Path $root 'pkg\*') $stage -Recurse
Copy-Item $jrExe (Join-Path $stage 'netrec.exe') -Force
Copy-Item (Join-Path $root 'README.md') $stage
Copy-Item $jar $stage

# The AOT cache is keyed to the jar size and modification time and to the JDK build that wrote
# it, so shipping one would only be thrown away on the far machine. The first run rebuilds it.
Get-ChildItem $stage -Filter '*.aot' -ErrorAction SilentlyContinue | Remove-Item -Force

Compress-Archive -Path $stage -DestinationPath $zip -CompressionLevel Optimal

$mb = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host ''
Write-Host ("  staged : {0}" -f $stage)
Get-ChildItem $stage | ForEach-Object {
    Write-Host ("           {0,-16} {1,10:N0} bytes" -f $_.Name, $_.Length)
}
Write-Host ''
Write-Host ("  zip    : {0}  ({1} MB)" -f $zip, $mb)
Write-Host ''
