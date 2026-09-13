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

## What a 2022 F-250 actually answered

The table above is Ford's documented usage. These are measurements from one
truck, with an OBDLink EX reporting `STN2231 v5.8.1`, ignition on.

**HS-CAN1 and HS-CAN2 work.** The PCM answers on 7E8. A multi-bus scan reached
the PAM on 736 and the APIM on 7D0 **on HS-CAN2 at 500 kbps** - both of which sit
on MS-CAN on older Fords. Live data runs at a 240 ms median sweep with a 100%
fill rate.

**Nothing can be overheard.** With the PCM answering a request 150 ms earlier,
`ATMA`, `STM` and `STMA` all returned `STOPPED`, with and without a receive
filter. The likeliest reason is the gateway these trucks put in front of the OBD
port: it routes diagnostic traffic on request rather than mirroring the internal
buses. So bus discovery cannot be replaced by listening - the address sweep is
the only way to find a module.

**No MS-CAN candidate has ever worked.** Every one returns `CAN ERROR`, meaning
no node acknowledged the frame: the adapter's own `STP 33` with `STPBR 125000`
(`STPBRR` read back `125000`, so the bitrate took), and ELM327 protocol B with
options bytes C0, 40, 01, 11 and 80, each against 726, 720 and 7D0. Not one
`NO DATA`.

Two readings fit, and they have not been separated yet:

1. **This truck has no MS-CAN.** PAM and APIM answering on HS-CAN2 is consistent
   with Ford having moved that traffic to a 500 kbps bus. If so, every MS-CAN
   probe the app makes is wasted time on this vehicle.
2. **The adapter was never on pins 3/11.** `ATPB` and `STPBR` set a CAN
   controller's bitrate and options. Neither says anything about which wires the
   transceiver is attached to. Six candidates may have been six ways of talking
   to the wrong wires.

The probe console's **"Which protocols this adapter has"** investigation is what
separates them: `STP` sets a protocol without opening it, so every protocol
number can be offered to the chip and `STPRS` asked what it is called, without
putting anything on a bus. If the adapter has a protocol it calls MS-CAN, that
names the command; if it does not, reading 1 is the live one.

Until that is settled, do not trust this document on MS-CAN.

## What to buy

**OBDLink EX (USB).** FORScan's own top recommendation, and the best option if
you do not mind a cable. No radio, no pairing, no dropouts, highest throughput.
Use it for anything long-running - a full As-Built read, a logging session.
Needs a USB OTG adapter to reach an Android phone.

Two of its published specifications matter to how this app is written:

- **Electronic MS-CAN/HS-CAN switching, with simultaneous access to both.**
  Unlike a "toggle switch" adapter, which physically bridges the connector pins
  and is therefore on exactly one bus at a time, the EX switches under software
  control and can hold both buses at once. It also avoids the network
  interference a physical bridge can cause.
- **2,000 kbit/s link rate**, with 4K block transfers and a claimed 20x faster
  upload than toggle-switch adapters.

`UsbSerialTransport` negotiates the line rate downward from 2 Mbit/s rather than
assuming one, so the EX is not throttled to the 115,200 that suits a cheaper
bridge.

`BusRouter` keeps the adapter on the bus each module actually sits on, since a
module is unreachable unless its bus is selected and callers address modules
across buses freely. Returning to a bus is cheap: `ElmAdapter` remembers the
initialisation sequence proven to work for it, so the candidate probing is paid
once per bus per session, and a request for the bus already selected is a no-op.

`BusRouter.simultaneousBusAccess` is the seam for the EX's ability to hold both
buses at once. It is **off by default**, and deliberately so: the ST command set
needed to put the adapter into that mode is not something this project has been
able to verify, and assuming it would silently address modules on a bus that was
never brought up. Sequential switching is correct on every adapter and merely
leaves some speed on the table. Turning the flag on is a one-line change once
the behaviour is confirmed on real hardware.

**OBDLink MX+ (Bluetooth Classic).** The best wireless option, and also
recommended by the FORScan team. Classic SPP gives a plain byte stream with far
better throughput than BLE, and throughput sets your live-data sample rate. It
supports MS-CAN natively, with no manual switch.

Worth knowing if you read older forum threads claiming the MX+ cannot do MS-CAN:
it uses the STN2255, which older FORScan builds misdetected as a plain ELM327
and consequently would not drive on MS-CAN. Fixed in FORScan for Windows
v2.3.19.

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
