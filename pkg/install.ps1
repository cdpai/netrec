# netrec installer.
#
# Unzipping puts the files somewhere; it cannot put them on PATH, and it cannot tell netrec.exe
# where the jar landed. Those are the two things this script does, and they are the only two
# things netrec needs in order to work from any directory on the machine.
#
#   1. rewrites netrec.jrc so netrec.exe launches the jar sitting next to it, by absolute path
#   2. adds this folder to the current user's PATH (no administrator rights needed)
#
# Re-running it is safe: both steps are idempotent. Move the folder and run it again.

# -NoPath  : stamp the jar path into netrec.jrc but leave PATH untouched.
# -NoPause : accepted so install.cmd can pass it straight through; not used here.
param([switch]$NoPath, [switch]$NoPause)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$here = $here.TrimEnd('\')
$jar  = Join-Path $here 'netrec.jar'
$exe  = Join-Path $here 'netrec.exe'
$jrc  = Join-Path $here 'netrec.jrc'

Write-Host ''
Write-Host 'netrec installer'
Write-Host ("  folder : {0}" -f $here)
Write-Host ''

function Fail($msg) {
    Write-Host ''
    Write-Host ("  [!!] {0}" -f $msg) -ForegroundColor Red
    Write-Host ''
    exit 1
}

# ---- 1. is there a java, and is it new enough? --------------------------------------------
$javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Fail 'java.exe is not on PATH. Install a JDK (21 minimum, 25 recommended) and run this again.'
}
# cmd does the stderr redirect: PowerShell 5.1 wraps a native command's stderr in ErrorRecords.
$verLines = cmd /c "java -version 2>&1"
$verText  = ($verLines | Out-String)
$major    = 0
if ($verText -match 'version "(\d+)') { $major = [int]$Matches[1] }
if ($major -eq 0) {
    Write-Host ("  [??] java found at {0} but its version could not be read; continuing." -f $javaCmd.Source)
} elseif ($major -lt 21) {
    Fail ("java {0} is too old -- netrec needs 21 minimum, 25 recommended. Found at {1}" -f $major, $javaCmd.Source)
} else {
    Write-Host ("  [ok] java {0}  ({1})" -f $major, $javaCmd.Source)
}

# ---- 2. did the whole archive actually get unzipped? -------------------------------------
if (-not (Test-Path $jar)) { Fail 'netrec.jar is missing from this folder. Unzip the whole archive, not just the exe.' }
if (-not (Test-Path $exe)) { Fail 'netrec.exe is missing from this folder. Unzip the whole archive.' }
if (-not (Test-Path $jrc)) { Fail 'netrec.jrc is missing from this folder. Unzip the whole archive.' }
$jarMb = [math]::Round((Get-Item $jar).Length / 1MB, 1)
Write-Host ("  [ok] netrec.jar ({0} MB) and netrec.exe are here" -f $jarMb)

# ---- 3. point netrec.exe at this jar, by absolute path -----------------------------------
# jr.exe reads this .jrc from beside itself whatever the current directory is, but it passes the
# -jar path through to the JVM untouched -- so a relative path would break the moment you ran
# netrec from anywhere else. Stamping the absolute path in is what makes the folder relocatable:
# move it, re-run this, and the line is rewritten.
# Substituted line-by-line rather than with a regex replacement: a Windows path is full of
# backslashes and can contain a dollar sign, both of which have meaning in a replacement string.
$newLine  = 'java.args=-jar "' + $jar + '"'
$stamped  = $false
$outLines = foreach ($line in (Get-Content -LiteralPath $jrc)) {
    if (-not $stamped -and $line -match '^\s*java\.args\s*=') { $stamped = $true; $newLine }
    else { $line }
}
if (-not $stamped) { $outLines = @($outLines) + '' + $newLine }
Set-Content -LiteralPath $jrc -Value $outLines -Encoding ASCII
Write-Host ("  [ok] netrec.jrc now points at {0}" -f $jar)

# ---- 4. put this folder on PATH ----------------------------------------------------------
# [Environment]::SetEnvironmentVariable(...,'User') and not setx: setx truncates at 1024
# characters, and "%PATH%" in a batch file is the system and user paths already merged, so the
# usual one-liner quietly copies the whole system PATH into the user one.
if ($NoPath) {
    Write-Host '  [--] -NoPath given, so PATH was left alone. Run netrec.exe from this folder,'
    Write-Host ("       or add {0} to PATH yourself." -f $here)
} else {
    $userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
    if ($null -eq $userPath) { $userPath = '' }
    $entries  = @($userPath -split ';' | Where-Object { $_.Trim() -ne '' })
    $already  = @($entries | Where-Object { $_.Trim().TrimEnd('\') -eq $here })
    if ($already.Count -gt 0) {
        Write-Host '  [ok] this folder is already on your PATH'
    } else {
        $newPath = (@($entries) + $here) -join ';'
        [Environment]::SetEnvironmentVariable('PATH', $newPath, 'User')
        Write-Host '  [ok] this folder added to your user PATH'
    }
}
# make it true for this session too, so the check below and any immediate use work
if (($env:PATH -split ';' | Where-Object { $_.Trim().TrimEnd('\') -eq $here }).Count -eq 0) {
    $env:PATH = $env:PATH.TrimEnd(';') + ';' + $here
}

# ---- 5. warn if some other netrec is shadowing this one ----------------------------------
$onPath = @(Get-Command netrec -All -ErrorAction SilentlyContinue |
            Where-Object { $_.Source } |
            Where-Object { (Split-Path -Parent $_.Source).TrimEnd('\') -ne $here })
if ($onPath.Count -gt 0) {
    Write-Host ''
    Write-Host '  [??] another netrec is also on your PATH and may win over this one:' -ForegroundColor Yellow
    $onPath | ForEach-Object { Write-Host ("       {0}" -f $_.Source) }
}

# ---- 6. prove it runs --------------------------------------------------------------------
# Warm the cache first and throw the output away. The run that BUILDS the AOT cache prints a
# few hundred "[warning][aot] Skipping ..." lines, which are normal, harmless, and meaningless
# to anyone installing a tool -- showing them would bury the part that matters.
Write-Host ''
Write-Host '  the first run builds a startup cache next to the jar (about 30 MB) and is slow,'
Write-Host '  perhaps half a minute. It happens once; runs after it take about a second.'
Write-Host ''
cmd /c ('"' + $exe + '" config >nul 2>&1') | Out-Null

# "config" is the smoke test: it loads the settings and prints the CDP port and the exact browser
# command line, which is the next thing needed anyway, and it returns 0 without starting the
# daemon. "status" is the obvious choice and the wrong one -- it returns 1 whenever the daemon is
# down, by design, so a perfectly healthy fresh install would report itself as broken.
#
# cmd does the redirect, not PowerShell: 5.1 turns a native command's stderr into ErrorRecords,
# and with $ErrorActionPreference = 'Stop' that alone would abort the install.
$out = cmd /c ('"' + $exe + '" config 2>&1')
$rc  = $LASTEXITCODE
$out | ForEach-Object { Write-Host ("       {0}" -f $_) }
Write-Host ''
if ($rc -ne 0) {
    Write-Host ("  [!!] netrec config exited {0} -- see the output above." -f $rc) -ForegroundColor Red
    exit $rc
}

Write-Host '  netrec is installed.' -ForegroundColor Green
Write-Host ''
Write-Host '  Open a NEW terminal (this one does not know about the PATH change) and try:'
Write-Host ''
Write-Host '      netrec --help'
Write-Host '      netrec config --detect'
Write-Host ''
Write-Host '  Then read HOWTO.md in this folder -- it walks through an actual capture.'
Write-Host ''
exit 0
