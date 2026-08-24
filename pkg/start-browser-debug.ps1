# Start the browser with netrec's CDP port open, and check that it really opened.
#
# This exists because the browser is the one part of netrec that cannot configure itself. A
# browser only listens for the DevTools Protocol if it was given --remote-debugging-port at
# startup, the port is randomised per install so nobody can remember it, and -- the part that
# actually catches people -- a browser that is ALREADY running ignores the flag completely:
# the new invocation just hands the URL to the existing process and exits, no port is opened,
# and nothing anywhere reports an error. So this script reads the port from netrec itself,
# notices when the browser is already up, and confirms the port is listening before saying so.
#
#   start-browser-debug.cmd                  Vivaldi if present, else Chrome, else Edge
#   start-browser-debug.cmd -Browser chrome
#   start-browser-debug.cmd -Exe "C:\path\to\browser.exe"
#   start-browser-debug.cmd -Force           close a running browser without asking first
#   start-browser-debug.cmd -Check           report what it would do, and change nothing
#
# Deliberately pure ASCII: PowerShell 5.1 reads a BOM-less script through the ANSI codepage,
# so a non-ASCII character here would be mangled before the parser ever saw it.

param(
    [ValidateSet('vivaldi', 'chrome', 'edge')] [string] $Browser,
    [string] $Exe,
    [switch] $Force,
    [switch] $Check
)

$ErrorActionPreference = 'Stop'
$here = (Split-Path -Parent $MyInvocation.MyCommand.Path).TrimEnd('\')

function Say($m)  { Write-Host ("  {0}" -f $m) }
function Warn($m) { Write-Host ("  [!] {0}" -f $m) -ForegroundColor Yellow }
function Die($m)  { Write-Host ''; Write-Host ("  [X] {0}" -f $m) -ForegroundColor Red; Write-Host ''; exit 1 }

Write-Host ''
Write-Host 'Starting the browser with netrec recording enabled'
Write-Host ''

# ---- 1. ask netrec which port it expects -------------------------------------------------
# Read it rather than hardcoding it: the port is chosen per install, and "netrec config
# --hub-port" or a settings edit can change it later. A hardcoded number in a desktop
# shortcut is a thing that silently stops matching.
$netrec = Join-Path $here 'netrec.exe'
if (-not (Test-Path $netrec)) {
    $g = Get-Command netrec.exe -ErrorAction SilentlyContinue
    if ($g) { $netrec = $g.Source } else { Die 'netrec.exe not found next to this script or on PATH.' }
}
# Run it with this folder as the working directory. Before install.cmd has been run the shipped
# netrec.jrc still carries a RELATIVE jar path, which the JVM resolves against the caller's cwd
# -- so netrec.exe works from inside its own folder and nowhere else. cd /d inside the cmd line
# rather than Push-Location, because a PowerShell location change does not reliably become the
# process working directory that a child process inherits.
$cfg = cmd /c ('cd /d "' + $here + '" && "' + $netrec + '" config 2>&1')
if ($cfg -isnot [array]) { $cfg = @($cfg) }
$port = $null
foreach ($line in $cfg) { if ($line -match 'cdp\s*port\s*:\s*(\d+)') { $port = [int]$Matches[1]; break } }
if (-not $port) {
    $cfg | ForEach-Object { Say $_ }
    Die 'could not read the CDP port out of "netrec config" (output above).'
}
Say ("netrec expects CDP port {0}" -f $port)

# ---- 2. is the port already open? --------------------------------------------------------
function Test-CdpPort([int] $p) {
    # A TCP connect is not enough: something else could hold the port. Ask for the CDP
    # version endpoint, which only a debuggable browser answers.
    try {
        $r = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/json/version" -f $p) -UseBasicParsing -TimeoutSec 2
        return ($r.StatusCode -eq 200)
    } catch { return $false }
}

if (Test-CdpPort $port) {
    Say 'the port is already open -- a browser is listening on it already.'
    Write-Host ''
    Write-Host '  Nothing to do. In your terminal:'
    Write-Host '      netrec tabs'
    Write-Host ''
    exit 0
}

# ---- 3. find the browser -----------------------------------------------------------------
$known = [ordered]@{
    vivaldi = @("$env:LOCALAPPDATA\Vivaldi\Application\vivaldi.exe",
                "$env:PROGRAMFILES\Vivaldi\Application\vivaldi.exe",
                "${env:PROGRAMFILES(X86)}\Vivaldi\Application\vivaldi.exe")
    chrome  = @("$env:PROGRAMFILES\Google\Chrome\Application\chrome.exe",
                "${env:PROGRAMFILES(X86)}\Google\Chrome\Application\chrome.exe",
                "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe")
    edge    = @("$env:PROGRAMFILES\Microsoft\Edge\Application\msedge.exe",
                "${env:PROGRAMFILES(X86)}\Microsoft\Edge\Application\msedge.exe")
}

if ($Exe) {
    if (-not (Test-Path $Exe)) { Die ("no such file: {0}" -f $Exe) }
    $exePath = $Exe
} else {
    $order = if ($Browser) { @($Browser) } else { @('vivaldi', 'chrome', 'edge') }
    $exePath = $null
    foreach ($b in $order) {
        foreach ($c in $known[$b]) { if ($c -and (Test-Path $c)) { $exePath = $c; break } }
        if ($exePath) { break }
    }
    if (-not $exePath) { Die 'no Vivaldi, Chrome or Edge found at the usual paths. Pass -Exe "<full path>".' }
}
$procName = [IO.Path]::GetFileNameWithoutExtension($exePath)
Say ("browser: {0}" -f $exePath)

# ---- 3b. -Check stops here, having touched nothing ---------------------------------------
# A diagnostic that cannot close someone's browser. Everything above this line is a read:
# ask netrec for the port, probe the port, locate the exe. Everything below it acts.
if ($Check) {
    $up = @(Get-Process -Name ([IO.Path]::GetFileNameWithoutExtension($exePath)) -ErrorAction SilentlyContinue)
    if ($up.Count -gt 0) { $upText = "yes, " + $up.Count + " process(es) -- it would have to be closed and restarted" }
    else                 { $upText = 'no -- it can be started cleanly' }
    Say ("browser running now : {0}" -f $upText)
    Say ("cdp port {0} open    : no (checked above)" -f $port)
    Write-Host ''
    Say '-Check given, so nothing was started or closed.'
    Write-Host ''
    exit 0
}

# ---- 4. a browser already running would ignore the flag ----------------------------------
$running = @(Get-Process -Name $procName -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    Write-Host ''
    Warn ("{0} is already running ({1} process(es))." -f $procName, $running.Count)
    Say  'A running browser IGNORES --remote-debugging-port: the new command just hands over to'
    Say  'the existing process and no port is opened. It has to be closed completely first.'
    Say  'Your tabs and your logins are kept -- it reopens with the same profile.'
    Write-Host ''
    if (-not $Force) {
        $answer = Read-Host '  Close it now and restart it with recording enabled? (y/N)'
        if ($answer -notmatch '^(y|yes)$') {
            Write-Host ''
            Say 'left alone. Close the browser yourself and run this again.'
            Write-Host ''
            exit 2
        }
    }
    Say 'asking it to close...'
    # CloseMainWindow first, so the browser saves its session the way it does normally. Only
    # a process that refuses to go gets killed, and only after it has had time to.
    foreach ($p in $running) { try { $null = $p.CloseMainWindow() } catch { } }
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 500
        if (-not (Get-Process -Name $procName -ErrorAction SilentlyContinue)) { break }
    }
    $still = @(Get-Process -Name $procName -ErrorAction SilentlyContinue)
    if ($still.Count -gt 0) {
        Warn 'it did not close on its own; stopping it.'
        foreach ($p in $still) { try { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } catch { } }
        Start-Sleep -Seconds 2
    }
    Say 'closed.'
}

# ---- 5. launch, and prove the port opened -----------------------------------------------
Write-Host ''
Say ("launching with --remote-debugging-port={0}" -f $port)
Start-Process -FilePath $exePath -ArgumentList ("--remote-debugging-port={0}" -f $port)

$ok = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 500
    if (Test-CdpPort $port) { $ok = $true; break }
}

Write-Host ''
if (-not $ok) {
    Warn ("the browser started but nothing is answering on port {0} after 20 seconds." -f $port)
    Say  'The usual cause is another copy of the browser still running in the background --'
    Say  'check Task Manager for it, end it, and run this again.'
    Write-Host ''
    exit 1
}

Say ("port {0} is open and answering." -f $port)
Write-Host ''
Write-Host '  Log in and go to the page you care about, then in your terminal:'
Write-Host ''
Write-Host '      netrec tabs                          confirm the tab is visible'
Write-Host '      netrec rec --tab <bit of the title>  attach BEFORE you click anything'
Write-Host '      netrec mark "about to press Save"    then do it in the browser'
Write-Host '      netrec ls --writes --since-mark      what the click actually sent'
Write-Host ''
Write-Host '  Full walkthrough: HOWTO.md next to this script.'
Write-Host ''
exit 0
