# Adapter and wiring notes

## Why the adapter choice matters more than anything else

A 2022 F-250 has several independent CAN buses behind one OBD-II connector. Which
physical pins your adapter is wired to decides which modules you can talk to at
all.

| Bus | Pins | Bitrate | Typically carries |
|-----|------|---------|-------------------|
| HS-CAN1 | 6 / 14 | 500 kbps | PCM, TCM, ABS, PSCM, RCM - and standard OBD-II |
| MS-CAN | 3 / 11 | 125 kbps | BCM, IPC, APIM (SYNC), ACM, DSM, HVAC |
| HS-CAN2 | 12 / 13 | 500 kbps | Gateway, driver assistance, cameras, radar |
| HS-CAN3 | 1 / 9 | 500 kbps | Present on some 2020+ builds |

Every OBD adapter reaches HS-CAN1, because that is what the OBD-II standard
mandates. Reaching the others needs an adapter that can re-route its pins.

**These pin assignments are Ford's documented usage. Which buses are actually
populated on your truck varies by trim and options, so the app treats the table
above as probe candidates and lets discovery decide.** On 2020+ Fords the Gateway
Module also bridges diagnostics, so a module that physically sits on MS-CAN may
still answer on HS-CAN1. The app reports where each module actually answered
rather than where it was expected.

## What to buy

**OBDLink EX (USB).** The best option if you do not mind a cable. No radio, no
pairing, no dropouts, highest throughput. Use this for anything long-running -
a full As-Built read, a logging session. Needs a USB OTG adapter for your phone.

**OBDLink MX+ (Bluetooth Classic).** The best wireless option. Classic SPP gives
a plain byte stream with far better throughput than BLE, and throughput sets your
live-data sample rate.

**Do not buy a generic ELM327 clone** if you care about anything beyond the
engine and transmission. It is wired to pins 6/14 only. The app will connect,
work correctly for powertrain diagnostics, and tell you plainly that it cannot
reach the other buses - but it cannot make the hardware do something it is not
wired for.

BLE-only adapters (OBDLink CX and similar) work through the app's BLE transport,
but every exchange is a GATT write plus a notification, so sample rates are
noticeably lower than Classic.

## The one uncertain constant in this codebase

Selecting 500 kbps HS-CAN is completely standard: `ATSP6`, works everywhere.

Selecting 125 kbps MS-CAN is not. It needs the ELM327 user-defined "protocol B",
whose options byte has been documented inconsistently across ELM327 datasheet
revisions and is outright wrong on many clones.

Rather than hard-code one magic number and hope, `core/.../adapter/BusInit.kt`
holds the plausible sequences in preference order and `ElmAdapter.selectBus`
probes them, keeping the first that yields real bus traffic. On STN hardware the
native `STP`/`STPBR` path is tried first and is expected to succeed outright -
which is a large part of why that hardware is recommended.

**If you bench-verify the correct options byte for your specific adapter, pin it
by moving that sequence to the head of the list in `BusInit.kt`.** That is the
one place in this project where a verified value should replace a probe.

## Practical notes

- **Turn the ignition on.** With the key off, body buses are genuinely silent and
  most modules will not answer. The app distinguishes "bus configured but quiet"
  from "bus not working" and tells you which.
- **One connection at a time.** An adapter already connected to another app or
  another phone will refuse a second connection.
- **Battery voltage.** The app reads control module voltage and refuses writes
  below 12.0 V. A brown-out part way through a module write is the classic way to
  lose a module. Put a charger on it.
