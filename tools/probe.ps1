<#
.SYNOPSIS
  Talks to an OBDLink adapter over its serial port and records the exchange.

.DESCRIPTION
  Truck Scan is developed in an environment with no USB port and no adapter, so
  several things in it are reasoned from logs rather than measured: which MS-CAN
  initialisation byte a real adapter accepts, whether an AT command sent between
  flow control and the consecutive frames really costs those frames, whether the
  serial rate can be raised past 115,200. This turns those questions into
  answers.

  Nothing is installed to run it. System.IO.Ports ships with Windows.

  READ-ONLY. Every frame is checked against a list of UDS and OBD services that
  change nothing, and anything else is refused and never sent - no module reset,
  no clearing fault codes, no security access, no routine control, no writing
  configuration. The same rule the app enforces, for the same reason.

.EXAMPLE
  .\probe.ps1 -ListPorts
  .\probe.ps1 -Port COM3 -Investigation alive
  .\probe.ps1 -Port COM3 -ScriptFile my-script.txt
#>

[CmdletBinding()]
param(
    [switch] $ListPorts,
    [string] $Port,
    [ValidateSet('alive', 'mscan', 'burst', 'rate')]
    [string] $Investigation,
    [string] $ScriptFile,
    [int]    $Baud = 115200,
    [string] $Out,
    [int]    $MonitorSeconds = 4
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- safety

# Services that only ever read. Anything not here is refused.
$AllowedServices = @{
    0x01 = 'OBD-II current data'
    0x02 = 'OBD-II freeze frame'
    0x03 = 'OBD-II stored fault codes'
    0x07 = 'OBD-II pending fault codes'
    0x09 = 'OBD-II vehicle information'
    0x0A = 'OBD-II permanent fault codes'
    0x19 = 'UDS ReadDTCInformation'
    0x22 = 'UDS ReadDataByIdentifier'
    0x3E = 'UDS TesterPresent'
}

$RefusedServices = @{
    0x10 = 'changes the diagnostic session, which unlocks other services'
    0x11 = 'resets the module'
    0x14 = 'erases fault codes and freeze-frame data'
    0x27 = 'security access - the gateway to programming'
    0x28 = 'stops modules communicating'
    0x2E = 'writes configuration to the module'
    0x2F = 'drives the module inputs and outputs'
    0x31 = 'runs a routine inside the module'
    0x34 = 'starts a firmware download'
    0x35 = 'starts a firmware upload'
    0x36 = 'transfers firmware data'
    0x37 = 'ends a firmware transfer'
    0x85 = 'turns fault code recording on or off'
}

function Test-Line {
    <# Returns @{ Kind = 'note'|'command'|'frame'|'refused'; Text; Reason; Note } #>
    param([string] $Line)

    $text = ($Line -split '#', 2)[0].Trim()
    if ($text -eq '') { return @{ Kind = 'note'; Text = $Line } }

    $upper = ($text.ToUpper() -replace '\s', '')

    if ($upper -like '@*') {
        if ($upper -eq '@1' -or $upper -eq '@2') {
            return @{ Kind = 'command'; Text = $text; Note = 'adapter' }
        }
        return @{ Kind = 'refused'; Text = $text
                  Reason = 'writes a device identifier that persists' }
    }

    if ($upper -like 'AT*' -or $upper -like 'ST*') {
        if ($upper -like 'ATPP*' -and $upper -like '*SV*') {
            return @{ Kind = 'refused'; Text = $text
                      Reason = 'sets a programmable parameter, which persists across power cycles' }
        }
        return @{ Kind = 'command'; Text = $text; Note = 'adapter' }
    }

    if ($upper -notmatch '^[0-9A-F]+$' -or ($upper.Length % 2) -ne 0) {
        return @{ Kind = 'refused'; Text = $text; Reason = 'not an adapter command and not valid hex' }
    }
    if ($upper.Length -gt 16) {
        return @{ Kind = 'refused'; Text = $text; Reason = 'a CAN frame carries at most 8 bytes' }
    }

    $pci = [Convert]::ToInt32($upper.Substring(0, 2), 16)
    $type = $pci -band 0xF0

    # 0x30 is flow control: permission for the sender to continue, no service.
    if ($type -eq 0x30) { return @{ Kind = 'frame'; Text = $text; Note = 'flow control' } }
    # 0x20 is a consecutive frame; its service was checked on the first frame.
    if ($type -eq 0x20) { return @{ Kind = 'frame'; Text = $text; Note = 'consecutive frame' } }

    if ($upper.Length -lt 4) {
        return @{ Kind = 'refused'; Text = $text; Reason = 'too short to tell which service it asks for' }
    }
    $service = [Convert]::ToInt32($upper.Substring(2, 2), 16)

    if ($AllowedServices.ContainsKey($service)) {
        return @{ Kind = 'frame'; Text = $text; Note = $AllowedServices[$service] }
    }
    if ($RefusedServices.ContainsKey($service)) {
        return @{ Kind = 'refused'; Text = $text
                  Reason = ('service 0x{0:X2} {1}' -f $service, $RefusedServices[$service]) }
    }
    return @{ Kind = 'refused'; Text = $text
              Reason = ('service 0x{0:X2} is not on the read-only list' -f $service) }
}

# ---------------------------------------------------------------- ports

function Show-Ports {
    $names = [System.IO.Ports.SerialPort]::GetPortNames() | Sort-Object
    if (-not $names) {
        Write-Host "No serial ports found. Plug the adapter in and try again."
        Write-Host "If it is plugged in, Windows may not have a driver for it yet -"
        Write-Host "check Device Manager for an unknown device."
        return
    }
    Write-Host "Serial ports:"
    $friendly = @{}
    try {
        Get-CimInstance Win32_PnPEntity -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '\((COM\d+)\)' } |
            ForEach-Object { $friendly[$Matches[1]] = $_.Name }
    } catch { }
    foreach ($name in $names) {
        $label = if ($friendly.ContainsKey($name)) { $friendly[$name] } else { '' }
        Write-Host ("  {0,-6} {1}" -f $name, $label)
    }
    Write-Host ""
    Write-Host "The OBDLink usually names itself. Then:"
    Write-Host "  .\probe.ps1 -Port COM3 -Investigation alive"
}

# ---------------------------------------------------------------- serial

function Read-Reply {
    param($SerialPort, [int] $TimeoutMs)

    $sb = New-Object System.Text.StringBuilder
    $deadline = (Get-Date).AddMilliseconds($TimeoutMs)
    while ((Get-Date) -lt $deadline) {
        if ($SerialPort.BytesToRead -gt 0) {
            [void] $sb.Append($SerialPort.ReadExisting())
            if ($sb.ToString().Contains('>')) { break }
        }
        else {
            Start-Sleep -Milliseconds 10
        }
    }
    return $sb.ToString()
}

function Invoke-Monitor {
    <#
      ATMA streams until something interrupts it - it never returns a prompt on
      its own. Collect for a while, then send any byte to stop it. Getting this
      wrong is the difference between "the bus is silent" and "we never waited".
    #>
    param($SerialPort, [int] $Seconds)

    $sb = New-Object System.Text.StringBuilder
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ($SerialPort.BytesToRead -gt 0) { [void] $sb.Append($SerialPort.ReadExisting()) }
        else { Start-Sleep -Milliseconds 20 }
    }
    $SerialPort.Write("`r")
    Start-Sleep -Milliseconds 200
    if ($SerialPort.BytesToRead -gt 0) { [void] $sb.Append($SerialPort.ReadExisting()) }
    return $sb.ToString()
}

function Format-Reply {
    param([string] $Raw)
    if ($Raw -eq '') { return @('(no reply)') }
    $lines = $Raw -split "[`r`n]+" | Where-Object { $_.Trim() -ne '' -and $_.Trim() -ne '>' }
    if (-not $lines) { return @('(prompt only)') }
    return $lines
}

# ---------------------------------------------------------------- scripts

$Investigations = @{
    alive = @{
        Title = 'Which addresses are alive'
        Body  = @'
# Modules that are awake transmit on their own. If this lists CAN IDs,
# discovery could be seconds rather than the 1024-address sweep that
# currently takes minutes - and would find modules the address list
# does not know about. Ignition ON.
ATZ
ATE0
ATH1
ATSP6
ATMA
'@
    }
    mscan = @{
        Title = 'Which MS-CAN init works'
        Body  = @'
# MS-CAN is 125 kbps on pins 3/11 and needs ELM327 protocol B, whose
# options byte is documented inconsistently. The app tries five
# candidates every time and caches none, because none was ever
# confirmed. Ignition ON - a sleeping bus is silent whichever is right.
ATZ
ATE0
ATH1
ATCAF0

STP 33
STPBR 125000
STPBRR
ATMA

ATPB C0 04
ATSPB
ATMA

ATPB 40 04
ATSPB
ATMA

ATPB 01 04
ATSPB
ATMA

ATPB 11 04
ATSPB
ATMA

ATPB 80 04
ATSPB
ATMA
'@
    }
    burst = @{
        Title = 'Does a command break a burst'
        Body  = @'
# Asks the PCM for a part number - a reply too long for one frame - then
# grants the transfer. What follows the flow control line is the
# evidence: consecutive frames, or nothing. Ignition ON.
ATZ
ATE0
ATH1
ATCAF0
ATCFC0
ATSP6
ATSH 7E0
ATCRA 7E8
0322F18800000000
3000000000000000
'@
    }
    rate = @{
        Title = 'Can the line rate be raised'
        Body  = @'
# Adapter only - no vehicle needed. The link runs at 115200 because that
# is where the adapter powers up; the EX advertises 2 Mbit/s.
ATZ
ATE0
ATI
STI
STDI
STBR
ATPPS
@1
STSN
'@
    }
}

# ---------------------------------------------------------------- main

if ($ListPorts -or (-not $Port)) {
    Show-Ports
    if (-not $Port) { return }
}

$scriptText = $null
$title = 'Probe'
if ($ScriptFile) {
    if (-not (Test-Path $ScriptFile)) { throw "No such script file: $ScriptFile" }
    $scriptText = Get-Content $ScriptFile -Raw
    $title = [IO.Path]::GetFileNameWithoutExtension($ScriptFile)
}
elseif ($Investigation) {
    $scriptText = $Investigations[$Investigation].Body
    $title = $Investigations[$Investigation].Title
}
else {
    throw "Give -Investigation (alive, mscan, burst, rate) or -ScriptFile."
}

$parsed = @($scriptText -split "`n" | ForEach-Object { Test-Line $_ })
$refused = @($parsed | Where-Object { $_.Kind -eq 'refused' })
$sending = @($parsed | Where-Object { $_.Kind -eq 'command' -or $_.Kind -eq 'frame' })

Write-Host ""
Write-Host $title
Write-Host ('=' * $title.Length)
Write-Host "$($sending.Count) command(s) will be sent on $Port at $Baud baud."
if ($refused.Count -gt 0) {
    Write-Host ""
    Write-Host "$($refused.Count) line(s) will NOT be sent:" -ForegroundColor Yellow
    foreach ($r in $refused) { Write-Host "  $($r.Text) - $($r.Reason)" -ForegroundColor Yellow }
}
Write-Host ""

$serial = New-Object System.IO.Ports.SerialPort($Port, $Baud, 'None', 8, 'One')
$serial.ReadTimeout = 2000
$serial.WriteTimeout = 2000
$serial.DtrEnable = $true
$serial.RtsEnable = $true

$transcript = New-Object System.Text.StringBuilder
[void] $transcript.AppendLine($title)
[void] $transcript.AppendLine('=' * $title.Length)
[void] $transcript.AppendLine("Port $Port at $Baud baud")
[void] $transcript.AppendLine("Run $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
[void] $transcript.AppendLine('')

try {
    $serial.Open()
    Start-Sleep -Milliseconds 200
    if ($serial.BytesToRead -gt 0) { [void] $serial.ReadExisting() }

    foreach ($line in $parsed) {
        switch ($line.Kind) {
            'note' { continue }
            'refused' {
                Write-Host "-- $($line.Text)  NOT SENT" -ForegroundColor Yellow
                [void] $transcript.AppendLine("-- $($line.Text)")
                [void] $transcript.AppendLine("   NOT SENT: $($line.Reason)")
                [void] $transcript.AppendLine('')
                continue
            }
            default {
                $isMonitor = ($line.Text.ToUpper() -replace '\s', '') -like 'ATMA*'
                $started = Get-Date
                $serial.Write($line.Text + "`r")
                $raw = if ($isMonitor) {
                    Invoke-Monitor -SerialPort $serial -Seconds $MonitorSeconds
                } else {
                    Read-Reply -SerialPort $serial -TimeoutMs 2000
                }
                $ms = [int]((Get-Date) - $started).TotalMilliseconds
                $replies = Format-Reply $raw

                Write-Host ">> $($line.Text)  ($($line.Note), $ms ms)"
                foreach ($r in $replies) { Write-Host "   $r" }

                [void] $transcript.AppendLine(">> $($line.Text)    ($($line.Note), $ms ms)")
                foreach ($r in $replies) { [void] $transcript.AppendLine("   $r") }
                [void] $transcript.AppendLine('')
            }
        }
    }
}
finally {
    if ($serial.IsOpen) { $serial.Close() }
}

if (-not $Out) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $slug = ($title.ToLower() -replace '[^a-z0-9]+', '-').Trim('-')
    $Out = "truckscan-probe-$slug-$stamp.txt"
}
$transcript.ToString() | Set-Content -Path $Out -Encoding UTF8

Write-Host ""
Write-Host "Transcript written to $Out" -ForegroundColor Green
