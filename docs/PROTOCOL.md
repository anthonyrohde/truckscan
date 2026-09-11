# Protocol stack

Layers, bottom up. Each is independently testable and lives in `core/`.

    ObdTransport        raw bytes (Bluetooth SPP / BLE / USB serial / simulator)
    ElmAdapter          ELM327 + STN command set, bus selection, frame I/O
    IsoTpChannel        ISO 15765-2 segmentation, flow control, reassembly
    UdsClient           ISO 14229 services, NRC translation, response-pending
    Ford layer          module map, DTC decoding, As-Built, security access
    DiagnosticEngine    the facade the app drives

## Adapter layer

The ELM327 protocol is prompt-oriented: send a command terminated with CR, read
CR-separated lines until a `>` prompt. All access is serialised through a mutex,
because there is one pipe and interleaving two requests produces responses that
cannot be attributed to either.

Initialisation sets `ATCAF0` (CAN auto-formatting **off**) and `ATCFC0`
(adapter must not auto-send flow control). Both are deliberate: ISO-TP is done
in software rather than in the adapter. That costs a little throughput and buys
full control over flow-control timing, plus the ability to handle the
non-standard addressing some Ford modules use. It also means the entire
transport protocol is testable from a byte array.

`ATCRA` filters received frames to the module being addressed. On a busy
500 kbps bus this matters a lot: without it the adapter's buffer fills with
unrelated traffic and multi-frame reassembly starts dropping consecutive frames.

When streaming the consecutive frames of a segmented request, responses are
switched off with `ATR0`. With responses on, the adapter waits out its ATST
timeout after every transmitted frame - 200 ms per frame, for nothing.

## ISO-TP

Standard ISO 15765-2. Single frames up to 7 bytes; longer messages become a
first frame carrying 6 bytes plus consecutive frames of up to 7, sequence
numbers starting at 1 and wrapping modulo 16.

One ordering detail is worth calling out because it is easy to get wrong, and did
cost a real bug here. When a segmented reply arrives, frames the adapter has
already buffered are drained into the assembler **before** any flow control goes
out. Whether the consecutive frames have already arrived depends on the adapter's
firmware and receive window. Sending flow control first and then reading discards
frames you were already holding - which broke every reply over 7 bytes, meaning
every VIN read, every DTC list, every As-Built block. Drain first; only ask for
more if the message is still incomplete.

Frames are padded to 8 bytes by default. Many Ford modules reject short frames.

## UDS

Services implemented: 0x10 session control, 0x11 ECU reset, 0x14 clear DTCs,
0x19 read DTC info, 0x22 read data by identifier, 0x27 security access,
0x2E write data by identifier, 0x2F IO control, 0x31 routine control,
0x3E tester present, 0x85 control DTC setting.

Negative response codes are translated into plain language with actionable
advice, because "NRC 0x33" tells a user nothing. The three you will actually hit
are 0x33 (security access denied), 0x22 (conditions not correct - usually engine
running when it must be off, or the reverse) and 0x7E/0x7F (needs an extended
session first).

NRC 0x78 "response pending" is handled by waiting for the deferred reply rather
than re-sending, capped at 30 consecutive deferrals. A module doing real work
legitimately stalls the tester for several seconds.

## Discovery

Ford uses 11-bit diagnostic addressing with a consistent convention: response ID
is request ID plus 8. Discovery sweeps 0x700-0x7FF and probes each address with
a UDS TesterPresent, which every UDS module answers and which changes nothing in
the vehicle.

A negative response counts as present. Only a real module bothers to refuse.

`VehicleProfiles.SUPER_DUTY_2022` names about two dozen known addresses, but it
is used as probe candidates and naming hints, never as a declaration of vehicle
content. The truck is the authority. An address that answers and is not in the
table is reported as an unrecognised module rather than ignored.

## DTC decoding

Two-byte code plus, under UDS, a third failure-type byte and a status byte.

    byte 0  bits 7-6  system (P / C / B / U)
            bits 5-4  second digit (0-3)
            bits 3-0  third digit (hex)
    byte 1  bits 7-4  fourth digit (hex)
            bits 3-0  fifth digit (hex)
    byte 2  failure type byte (J2012-DA)
    status  ISO 14229-1 Table D.1 bit flags

The failure type byte is kept and displayed as `P0299:1C`. Ford uses it heavily,
and the same base code with a different failure type is a different fault - a
circuit short to ground points somewhere very different from a signal stuck low.
Scan tools that discard it throw away most of the diagnostic value.

`DtcCatalog` covers the generic SAE codes, with emphasis on what a 6.7L Power
Stroke actually sets, plus the network U-codes and the standardised failure type
bytes. Ford's own P1xxx range is not published and is not in here. Unknown codes
say "no description available" and point you at a Ford source. Showing a raw
code is honest; inventing a description sends someone chasing the wrong part.

## Live data

Standard OBD-II mode 01, requested against the functional address 0x7DF. The
vehicle is asked which parameters it supports (PIDs 0x00/0x20/0x40...) rather
than probed blindly - one request per 32-parameter bank instead of 32 requests.

Each parameter is a separate round trip, so sample rate is set by how many you
watch: roughly 20-25 ms each. Six parameters gives about 6-7 Hz; twenty gives
about 2 Hz. The UI says so rather than implying a rate it is not delivering.

The diesel values people most want - individual EGT sensors, DPF soot load,
turbo vane position, DEF level, injector balance rates - are mostly **not**
standard PIDs. They sit behind Ford-specific identifiers on the PCM. The
identifier discovery sweep will find which ones respond; what they mean is not
in this app.

## Service routines

UDS RoutineControl (0x31) is fully implemented, and `runCustom` will drive any
identifier you supply.

There is no routine-discovery function, and there should not be one. Sweeping
RoutineControl identifiers to see what responds means commanding unknown
functions on a live vehicle: the identifier space includes things that energise
injectors, cycle ABS valves, and command a regeneration that puts exhaust over
600 °C. A discovery sweep is safe for *reads* and is not safe here. There is a
test asserting no such function has been added.

The four operations shipped enabled - module reset, suspend/resume fault logging,
clear faults - are defined by ISO 14229 rather than by Ford, so they work on any
module that answers.
