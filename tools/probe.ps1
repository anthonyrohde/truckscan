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
  .\probe.ps1 -Port COM3 -Investigation protocols
  .\probe.ps1 -Port COM3 -ScriptFile my-script.txt
#>

[CmdletBinding()]
param(
    [switch] $ListPorts,
    [string] $Port,
    [ValidateSet('alive', 'monitor', 'protocols', 'mscan', 'burst', 'rate')]
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

    # The same bytes mean two things depending on ATCAF. With ATCAF0 the frame
    # carries its own ISO-TP header and the service is the second byte; with
    # ATCAF1 - the state after ATZ - the adapter adds that header and the
    # service comes first. So '1101' is an innocuous-looking first frame under
    # one reading and ECU RESET under the other. Check both; either one naming
    # a refused service refuses the line.
    if ($RefusedServices.ContainsKey($pci)) {
        return @{ Kind = 'refused'; Text = $text
                  Reason = ('read without an ISO-TP header this is service 0x{0:X2}, which {1}' -f $pci, $RefusedServices[$pci]) }
    }

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
        Title = 'Can anything be overheard (answered - no)'
        Body  = @'
# Answered on a 2022 F-250: nothing can be overheard. With the ignition
# on and the PCM answering 150 ms earlier, ATMA, STM and STMA all
# reported silence, with and without a receive filter - the gateway in
# front of the OBD port routes diagnostic traffic on request and does
# not mirror the internal buses. Kept because another vehicle may
# answer differently. Ignition ON.
ATZ
ATE0
ATH1
ATSP6
ATMA
'@
    }
    protocols = @{
        Title = 'Which protocols this adapter has'
        Body  = @'
# Asks the adapter which protocols it has, and what it calls them.
#
# This exists because of a measured dead end. Every MS-CAN candidate this
# project could think of came back CAN ERROR on a 2022 F-250: STP 33 with
# STPBR 125000 (STPBRR confirmed the 125000 took), and all five ELM327
# protocol B options bytes. But ATPB and STPBR configure a CAN controller's
# bitrate and options - neither says which pins the transceiver is wired
# to. Six candidates may have been six ways of talking to the wrong wires.
#
# The chip knows. STP sets a protocol without opening it: an unsupported
# number answers ? and changes nothing, a supported one is set but not
# connected. So nothing here goes on any bus, and the ignition can be off.
# STPRS then reports the protocol's name. Whatever this adapter calls
# MS-CAN, it will say so below.
#
# Read the output for a name containing MS-CAN, MEDIUM or 125.
ATZ
ATE0

# Where it starts, so the sweep can be read against it.
STPR
STPRS
STP 00
STPRS
STP 01
STPRS
STP 02
STPRS
STP 03
STPRS
STP 04
STPRS
STP 05
STPRS
STP 06
STPRS
STP 07
STPRS
STP 08
STPRS
STP 09
STPRS
STP 0A
STPRS
STP 0B
STPRS
STP 0C
STPRS
STP 20
STPRS
STP 21
STPRS
STP 22
STPRS
STP 23
STPRS
STP 24
STPRS
STP 25
STPRS
STP 26
STPRS
STP 27
STPRS
STP 28
STPRS
STP 29
STPRS
STP 2A
STPRS
STP 2B
STPRS
STP 2C
STPRS
STP 2D
STPRS
STP 2E
STPRS
STP 2F
STPRS
STP 30
STPRS
STP 31
STPRS
STP 32
STPRS
STP 33
STPRS
STP 34
STPRS
STP 35
STPRS
STP 36
STPRS
STP 37
STPRS
STP 38
STPRS
STP 39
STPRS
STP 3A
STPRS
STP 3B
STPRS
STP 3C
STPRS
STP 3D
STPRS
STP 3E
STPRS
STP 3F
STPRS
STP 40
STPRS
STP 41
STPRS
STP 42
STPRS
STP 43
STPRS
STP 44
STPRS
STP 45
STPRS
STP 46
STPRS
STP 47
STPRS
STP 48
STPRS
STP 49
STPRS
STP 4A
STPRS
STP 4B
STPRS
STP 4C
STPRS
STP 4D
STPRS
STP 4E
STPRS
STP 4F
STPRS
STP 50
STPRS
STP 51
STPRS
STP 52
STPRS
STP 53
STPRS
STP 54
STPRS
STP 55
STPRS
STP 56
STPRS
STP 57
STPRS
STP 58
STPRS
STP 59
STPRS
STP 5A
STPRS
STP 5B
STPRS
STP 5C
STPRS
STP 5D
STPRS
STP 5E
STPRS
STP 5F
STPRS
STP 60
STPRS
STP 61
STPRS
STP 62
STPRS
STP 63
STPRS
STP 64
STPRS
STP 65
STPRS
STP 66
STPRS
STP 67
STPRS
STP 68
STPRS
STP 69
STPRS
STP 6A
STPRS
STP 6B
STPRS
STP 6C
STPRS
STP 6D
STPRS
STP 6E
STPRS
STP 6F
STPRS
STP 70
STPRS
STP 71
STPRS
STP 72
STPRS
STP 73
STPRS
STP 74
STPRS
STP 75
STPRS
STP 76
STPRS
STP 77
STPRS
STP 78
STPRS
STP 79
STPRS
STP 7A
STPRS
STP 7B
STPRS
STP 7C
STPRS
STP 7D
STPRS
STP 7E
STPRS
STP 7F
STPRS

# Back to the standard powertrain bus.
STP 06
'@
    }
    mscan = @{
        Title = 'Does MS-CAN answer on protocol 53'
        Body  = @'
# ANSWERED. The adapter's protocol table, read back with STP xx / STPRS,
# names protocol 53 "MS CAN (ISO 15765, 125K/11B)" - pins 3/11, the right
# rate, in one command.
#
# What this project used to send was STP 33 with STPBR 125000. Protocol 33
# is "HS CAN (ISO 15765, 500K/11B)". So it selected the HIGH SPEED
# transceiver on pins 6/14 and set it to 125 kbps: a 125 kbps node on a
# 500 kbps bus, which can never acknowledge a frame. Hence CAN ERROR every
# time, and never once NO DATA. The five ELM327 protocol B candidates were
# the same mistake - ATPB sets a controller's rate, not its pins.
#
# This is the corrected test. Read the replies:
#
#   a reply (7xx ... 7E ...) -> MS-CAN is real and reachable
#   NO DATA                  -> on the right pins, nobody at that address
#   CAN ERROR                -> still not reaching the bus
#
# One NO DATA anywhere below is already the headline: it would mean the
# adapter is on pins 3/11 for the first time. Ignition ON.

ATZ
ATE0
ATH1
ATCAF0
ATCFC0
ATCRA

# --- MS-CAN, 11-bit, ISO 15765. The rate is part of the protocol; no STPBR.
STP 53
STPRS

ATSH 726
023E000000000000    # BCM
ATSH 720
023E000000000000    # IPC
ATSH 724
023E000000000000    # SCCM
ATSH 712
023E000000000000    # DSM
ATSH 733
023E000000000000    # HVAC
ATSH 736
023E000000000000    # PAM
ATSH 7D0
023E000000000000    # APIM
ATSH 727
023E000000000000    # ACM
ATSH 740
023E000000000000    # FCIM
ATSH 754
023E000000000000    # TCU
# --- same bus, 29-bit addressing, in case the body modules are extended.
STP 54
STPRS
ATSH 726
023E000000000000
ATSH 7D0
023E000000000000

# --- back to the powertrain bus, and prove the adapter still works. The PCM
#     must answer here. If it does not, the MS-CAN result above means nothing
#     because the adapter was broken, not the bus.
STP 33
STPRS
ATSH 7E0
ATCRA 7E8
0201000000000000
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
    monitor = @{
        Title = 'Can this adapter monitor at all'
        Body  = @'
# ATMA returns STOPPED on this adapter even while modules are answering,
# which makes the app report a live bus as silent and stops it ever
# caching a working bus setup. Two candidate reasons: monitoring needs
# the protocol brought up by a real request first, and STN chipsets have
# their own monitor command, STM, which the app never tries.
#
# This proves the bus is alive, then tries both. Ignition ON.
ATZ
ATE0
ATH1
ATSP6

# Prove the bus is alive and bring the protocol up. ATCAF0 first, so the
# frame below carries its own ISO-TP header - without it the adapter is
# still auto-formatting after ATZ and rejects a header-prefixed frame
# with '?', which is exactly what happened the first time this was tried.
ATCAF0
ATSH 7DF
ATCRA 7E8
0201000000000000

# ELM monitor, now that the protocol has carried a real request.
ATMA

# STN monitor.
STM
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
    throw "Give -Investigation (alive, monitor, protocols, mscan, burst, rate) or -ScriptFile."
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
                # ATMA and the STN equivalent STM both stream until something
                # interrupts them. Treating STM as an ordinary command would
                # time out and leave the adapter still streaming into whatever
                # came next.
                $cmdUpper = ($line.Text.ToUpper() -replace '\s', '')
                $isMonitor = ($cmdUpper -like 'ATMA*') -or ($cmdUpper -like 'STM*')
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
