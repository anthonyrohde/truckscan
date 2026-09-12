# Learning from a bus capture

## The problem this solves

Three things in this project cannot be derived from first principles:

1. **Which data identifiers hold As-Built blocks.** The 0xDE00 base is a
   community convention, not a documented fact.
2. **Which checksum algorithm the modules use.** Not published.
3. **Which RoutineControl identifiers are real.** Not published, and not safe to
   discover by probing a live vehicle.

All three are plainly visible in a recording of a tool that already knows the
answers. Importing one capture converts all three from guesses into
measurements.

## Getting a capture

**FORScan for Windows** can write a trace log. Enable the debug or trace log in
its settings, run the operation you want to learn from - a module configuration
read is the valuable one - then copy the log off the PC and import it on the
phone.

**Any candump-style capture** works too, from a Linux host with SocketCAN or
from another logging tool.

The importer is written to be format-tolerant rather than tied to one layout,
because tools do not agree on a log format and this project cannot assert what
FORScan emits. It recognises the *shape* of a logged frame - optional timestamp,
optional direction or bus markers, a CAN identifier, a run of hex payload bytes.
All of these parse to the same frame:

    726#0322DE0000000000
    (1718088210.123456) can0 726#0322DE00
    10:23:45.123 TX 0726 03 22 DE 00 00 00 00 00
    [10:23:45.123] 726: 0322DE0000000000
    2024-06-11 10:23:45.123  ->  0726  [8]  03 22 DE 00 00 00 00 00

If a capture yields nothing, the import screen shows the first lines it could
not read, so an unsupported format can be identified rather than guessed at.

## How it works

Three passes, in `core/.../trace/`:

1. **Reassemble ISO-TP per CAN identifier**, reusing the same `IsoTpAssembler`
   the live stack uses. A capture is the same frames without the timing, so the
   tested reassembler applies unchanged.
2. **Classify each message** as a request or a reply from its service byte.
   Request identifiers occupy 0x10-0x3E and 0x85, replies 0x50-0x7E and 0xC5, so
   the two never collide. This is why direction markers are parsed but never
   relied on - plenty of logs do not record one.
3. **Pair and extract**, using Ford's convention that a response identifier is
   the request identifier plus eight.

Interim "response pending" replies (NRC 0x78) are skipped so a request still
pairs with its real answer. Identifiers the module *refused* are not recorded -
a negative response means the identifier does not exist.

## What gets learned

| Learned | From | Effect |
|---|---|---|
| Configuration identifier map | 0x22 reads that succeeded | As-Built reads go straight to real identifiers instead of sweeping ~448 candidates |
| Identification identifiers | 0x22 reads in the 0xF1xx range | Kept separate, since ASCII text is not a checksummed block |
| Checksum algorithm | The captured blocks | Confirmed against more blocks than one live read offers |
| Writable identifiers | 0x2E writes | Shows what a working tool considered safe to write |
| Routine identifiers | 0x31 requests | Real routine IDs, learned by observation rather than probing |
| Security handshakes | 0x27 seed/key pairs | Recorded, with whether the module accepted the key |

Profiles are saved as plain text, the same reasoning as As-Built backups: a
profile is useful beyond one phone, since the identifier map for a 2022 Super
Duty BCM is the same on every 2022 Super Duty BCM.

    # truckscan learned profile v1
    # source: bcm-capture.txt
    module 726 72E
      checksum TWOS_COMPLEMENT
      config DE00 410200001C0899
      config DE01 00300000D0
      ident F190 3146543857324254374E4543313233343500
      written DE00
      routine 0203
      seedkey 01 4A7C 91E3 accepted

## On the security handshake - do not get your hopes up

A captured seed/key pair proves nothing about the *algorithm*. It is one input
and one output.

`SeedKeyAlgorithm.Replay` will replay a captured key, and that unlocks the
module **only if the module issues that exact seed again**. Whether it does is a
property of the module: some use a fixed or small-set seed, in which case a
capture is genuinely enough. Most current modules randomise, in which case
replay will essentially never hit and the honest answer stays "the derivation is
not publicly known".

Only keys the module *accepted* are offered for replay. A rejected key is
evidence of nothing and would waste one of the two security attempts before
lockout.

## Notes

- Importing touches nothing in the vehicle. It is reading a file.
- A capture of your own truck contains its VIN. Profiles are plain text, so
  check before sharing one.
- The active profile is remembered across restarts and reapplied when you next
  connect, so an imported map does not need re-importing every session.
