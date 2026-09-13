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

The table above is Ford's documented usage. Below is measurement, from one truck
with an OBDLink EX (`STI` = `STN2231 v5.8.1`, `STDI` = `OBDLink EX r2.2.1`,
serial 223110082390), VIN beginning `1FT8W2BT8N`, engine running.

### The adapter's protocol table

Read off the chip itself by offering every number to `STP` and asking `STPRS`
what it is. `STP` sets a protocol without opening it, so the sweep touches no
bus.

| `STP` | Reported name | Pins |
|-------|---------------|------|
| 21-25 | ISO 9141 / ISO 14230 variants | 7/15 |
| 31, 32 | HS CAN (ISO 11898, 500K, 11B/29B) | 6/14 |
| 33, 34 | HS CAN (ISO 15765, 500K, 11B/29B) | 6/14 |
| 35, 36 | HS CAN (ISO 15765, 250K, 11B/29B) | 6/14 |
| 41, 42 | SAE J1939 (250K, 11B/29B) | 6/14 |
| 51, 52 | MS CAN (ISO 11898, 125K, 11B/29B) | 3/11 |
| 53, 54 | MS CAN (ISO 15765, 125K, 11B/29B) | 3/11 |
| 61-64 | SW CAN (33K) | 1 |

There is no entry for pins 12/13 or 9. **HS-CAN2 and HS-CAN3 cannot be reached
through the OBD connector at all**, by this adapter or any other that does not
rewire it. Anything this app previously reported as found on HS-CAN2 was found
on pins 6/14 — the bus selection for those two ran the identical `STP 33` as
HS-CAN1 and scanned the powertrain bus again under another name.

### Opening a bus

Four ways, each from a clean reset, each asked the PCM for PID 00. **All four
answered**: `ATSP6`; `STP 33` alone; `STP 33` + `STPO`; `STP 33` + `STPBR
500000`. `STP` alone opens a protocol, so the bus init sends one command.

### MS-CAN is reachable, and empty

With the engine running and the PCM answering seconds earlier in the same run,
`STP 53` opened (`STPRS` confirming `MS CAN (ISO 15765, 125K/11B)`) and a
TesterPresent to 726, 720, 733 and 7D0 returned `CAN ERROR` every time — no node
acknowledged. Repeated with the protocol opened by `STPO` and again by `STPBR
125000`. **This truck has nothing on pins 3/11.**

An earlier run that looked like the same result was void: the truck had gone to
sleep, and the control request at the end returned `NO DATA` rather than a
reading. That is why every bus test now ends by asking the PCM something.

### Which modules answer, and where

Twenty Ford addresses probed with TesterPresent, receive filter cleared, engine
running. Five answered, all on **HS-CAN1**:

| Request | Reply | Module |
|---------|-------|--------|
| 7E0 | 7E8 | PCM |
| 7E1 | 7E9 | TCM |
| 726 | 72E | BCM |
| 736 | 73E | PAM |
| 7D0 | 7D8 | APIM |

No reply from 7E2, 760, 706, 720, 724, 712, 733, 727, 740, 754, 730, 764, 765,
775, 783. The response ID is the request plus 8 in every case.

The BCM is the point: it answers on the powertrain bus, not on MS-CAN where it
is documented to live. The gateway presents it there. A module answers in
56-64 ms; an address with nothing on it costs 120-137 ms to rule out.

The OBD-II broadcast address 7DF brings back both 7E8 and 7E9 to one request in
60 ms, which is what bus liveness is now tested with.

### 29-bit addressing is not used here

`STP 34` opened `HS CAN (ISO 15765, 500K/29B)` and the standard 29-bit
functional request to 18DB33F1 returned `NO DATA`. Everything on this truck is
11-bit.

### Nothing can be overheard

With the PCM answering a request 150 ms earlier, `ATMA`, `STM` and `STMA` all
returned `STOPPED`, with and without a receive filter. The gateway in front of
the OBD port routes diagnostic traffic on request and does not mirror the
internal buses, so discovery cannot be replaced by listening — the address sweep
is the only way to find a module.

### Multi-frame transfers, and a claim that turned out to be wrong

Mode 09 PID 02 (VIN) and `22 F188` (part number) both came back correctly: a
first frame, silence, then the consecutive frames released by our flow control.

Two things were tested that this project had asserted without evidence:

- **An AT command between the first frame and the flow control costs nothing.**
  `ATCRA 7E8` was deliberately inserted mid-burst and the consecutive frames
  still arrived. The claim that ELM firmware abandons reception to service a
  command is not supported.
- **`ATR0` around the flow control loses the burst.** Sent plainly, a flow
  control returned both consecutive frames of the VIN in 58 ms. Behind `ATR0`,
  the same flow control returned a bare prompt in 29 ms and no frames at all.
  The suppression was not protecting the burst, it was hiding it. It has been
  removed from the receive path.

### Individual commands

- `ATRV` works but is **not** a liveness test: it read 10.0 V on a truck whose
  PCM was answering, and 11.7 V on one that was asleep. Only asking a module
  something tells you the vehicle is awake.
- `ATST` is confirmed: `ATST 05` gave up on a miss in 49 ms, `ATST FF` in
  1069 ms — 255 × 4 ms, as documented.
- `ATCAF` ambiguity is confirmed on hardware. `0103` with auto-formatting **on**
  returned `7E8 03 7F 01 31` (mode 01 PID 03, request out of range); the same
  four characters with it **off** returned `7E8 02 43 00` (mode 03, read stored
  fault codes, none stored). One string, two services. This is why the probe
  console refuses any frame that is dangerous under either reading.
- `@1` returns `?` on this adapter; `STDI` is the command that names it. `STBR`
  is a setter, not a query, and also returns `?`. `ATPPS` shows `0C:23`, which is
  4,000,000/35 ≈ 114,286 — the link is at 115,200 and is configurable.
- `ATCRA` made no observable difference here: with the filter cleared, only 7E8
  replied anyway, consistent with the gateway not mirroring traffic.

### What the PCM offers

67 PIDs claimed across six bitmaps, 62 of them carrying a reading. Recorded
verbatim in `MeasuredSupport`, including the raw bytes. Notably present: engine
oil temperature (5C), fuel tank level (2F), boost pressure control (70),
turbocharger inlet pressure (6F), exhaust gas temperature (78), DPF differential
pressure (7A), control module voltage (42), ambient air temperature (46) and
odometer (A6). Notably absent: manifold pressure (0B) and throttle position (11),
and OBD-II carries no engine oil *pressure* at all, so a dash replica cannot show
that gauge honestly.

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
