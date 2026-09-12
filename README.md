# Truck Scan

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

**As-Built is read-only, deliberately.** The app reads a module's configuration,
shows it in Ford's own notation, and saves it as a portable text backup. It does
not write. Writing is gated behind UDS security access, whose key derivation is
Ford proprietary - for a 2022 module no locally computed key is accepted, and
the dealer tool completes that handshake against Ford's servers. The write path
was built and then removed: a button that always fails at the same wall is worse
than no button, because it implies a capability that is not there. Make changes
in FORScan, and take a backup here first. See [docs/AS_BUILT.md](docs/AS_BUILT.md).

**Ford-specific service routines are not included, on purpose.** Injector cutout
tests, forced DPF regeneration, KAM reset and so on are invoked through
identifiers Ford does not publish. The plumbing to run them is built and tested,
and you can supply an identifier you trust. What the app will not do is sweep the
identifier space to see what responds - that means commanding unknown functions
on a live vehicle. See [docs/PROTOCOL.md](docs/PROTOCOL.md).

**What the app cannot tell you is what the configuration bytes mean.** That is
Ford's data. Get the factory As-Built for your VIN and work from it.

**It can, however, learn the parts it would otherwise guess at.** Three things -
which identifiers hold As-Built blocks, which checksum algorithm your modules
use, and which RoutineControl identifiers are real - cannot be derived from
first principles, but are plainly visible in a recording of a tool that already
knows. Import a FORScan trace or any candump-style capture and all three become
measurements instead of guesses. See [docs/TRACE_IMPORT.md](docs/TRACE_IMPORT.md).

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
    docs/    Hardware guide, protocol notes, As-Built guide, trace importing.

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

## Building the Android app

The `:app` module needs an Android SDK; `:core` does not. `settings.gradle.kts`
**skips `:app` entirely when no SDK is found**, so if `./gradlew projects` does
not list it, that is why — it is not a broken checkout.

1. **Install Android Studio** (or just the command-line tools). Through the SDK
   Manager install **SDK Platform 35** and the latest **Android SDK
   Build-Tools**.

2. **Point the build at the SDK.** Either set `ANDROID_HOME`, or create
   `local.properties` in the repository root:

       sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk     # Windows
       sdk.dir=/Users/you/Library/Android/sdk                    # macOS
       sdk.dir=/home/you/Android/Sdk                             # Linux

   `local.properties` is gitignored, which is correct — it is machine-specific.

3. **Use JDK 17 or newer.** Android Gradle Plugin 8.7.3 requires it. Android
   Studio bundles a suitable JDK; from the command line check with `java -version`.

4. **Build:**

       ./gradlew :app:assembleDebug          # macOS / Linux
       gradlew.bat :app:assembleDebug        # Windows

   The APK lands in `app/build/outputs/apk/debug/`.

5. **Install it** with `adb install -r app/build/outputs/apk/debug/app-debug.apk`,
   or just press Run in Android Studio.

Verify the toolchain first with `./gradlew projects` — if `:app` appears in the
list, the SDK was found.

### A note on the build warning

Gradle warns that the Kotlin plugin is loaded once per module. That is
deliberate, and the reasoning is recorded in the root `build.gradle.kts`:
hoisting the Kotlin plugins to the root while AGP stays in `:app` puts them in
different classloaders and breaks `kotlin-android` outright, and hoisting AGP
too would force every build to resolve it - including the `:core`-only builds
that run without an Android SDK. The warning is cosmetic; both modules request
the same Kotlin version.

`:app` compiles and assembles. `:core` additionally has 139 passing tests; the
app layer has no automated tests, so its screens are verified by running them.
