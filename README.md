# SuperDuty Scan

An Android diagnostic app for a 2022 Ford F-250 Super Duty, in the spirit of
FORScan: multi-bus module discovery, per-module fault codes, live data, and
As-Built configuration read/backup/write.

## What works, and what does not

Being straight about this up front, because the difference decides whether the
app is useful to you.

**Reading is solid.** Module discovery across all four CAN buses, per-module DTCs
with status flags and J2012 failure types, standard OBD-II live data, module
identification (part numbers, calibration levels, VIN), and As-Built
configuration blocks. All of it is implemented, and the protocol layers have
94 passing tests behind them.

**Writing is implemented but will probably be refused.** As-Built writes go
through a full safety sequence - mandatory backup, checksum verification,
voltage check, programming session, security access, write, read-back verify,
automatic restore on failure. The step that stops you is security access: UDS
service 0x27 requires a key derived from a module-issued seed, and for a 2022
module that derivation is Ford proprietary. The dealer tool completes the
handshake against Ford's servers. There is no local substitute, and no amount of
code here invents one. When this happens the app says so plainly rather than
failing mysteriously. See [docs/AS_BUILT.md](docs/AS_BUILT.md).

**Ford-specific service routines are not included, on purpose.** Injector cutout
tests, forced DPF regeneration, KAM reset and so on are invoked through
identifiers Ford does not publish. The plumbing to run them is built and tested,
and you can supply an identifier you trust. What the app will not do is sweep the
identifier space to see what responds - that means commanding unknown functions
on a live vehicle. See [docs/PROTOCOL.md](docs/PROTOCOL.md).

**What the app cannot tell you is what the configuration bytes mean.** That is
Ford's data. Get the factory As-Built for your VIN and work from it.

## Hardware

You need an STN-chipset adapter - an **OBDLink EX** (USB) or **MX+**
(Bluetooth). Only those can re-route the connector pins to reach MS-CAN and
HS-CAN2, where the body, cluster and SYNC modules live.

A generic ELM327 clone is wired to pins 6/14 only. It will connect, read the
engine and transmission, and make the truck look as though it has no other
modules at all. The app detects this and says so rather than showing you an
empty list. Details in [docs/HARDWARE.md](docs/HARDWARE.md).

## Layout

    core/    Pure Kotlin/JVM. Every protocol layer: adapter command set,
             ISO-TP, UDS, DTC decoding, Ford module map, As-Built. No Android
             dependencies, so it builds and tests with just a JDK.
    app/     Android front end. Compose UI plus the three transports
             (Bluetooth Classic, BLE, USB serial).
    docs/    Hardware guide, protocol notes, As-Built guide.

The split is deliberate: all the logic that is hard to get right lives in a
module you can test without a phone, an adapter, or a truck.

## Building

    ./gradlew :core:test          # protocol tests - needs only a JDK
    ./gradlew :app:assembleDebug  # the APK - needs the Android SDK

`:app` is only included in the build when an Android SDK is present, so core
protocol work is never blocked by a missing SDK.

## Trying it without the truck

The app ships with a vehicle simulator, selectable on the connect screen. It
speaks enough of the ELM327 command set and enough UDS to exercise discovery,
fault reads, live data and As-Built reads against five simulated modules. Useful
for exploring the interface, and for telling whether a problem is the app or the
adapter.

The simulator is representative, not a model of a real F-250 - its As-Built
bytes are invented. It will not tell you anything about your own truck.

## Safety

- Read operations are safe. Discovery uses UDS TesterPresent, which changes
  nothing.
- Clearing faults also discards freeze-frame data and resets readiness monitors.
  Record faults first.
- Never write to a module on a weak battery. The app refuses below 12.0 V.
- Keep your backups off the phone. They are plain text files for exactly that
  reason.
