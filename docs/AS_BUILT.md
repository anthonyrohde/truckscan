# As-Built configuration

> **This app reads As-Built. It does not write it.**
>
> Writing is gated behind UDS security access, whose key derivation is Ford
> proprietary; on a 2022 vehicle no locally computed key is accepted. A write
> button that always fails at the same wall is worse than no button - it implies
> a capability that is not there and invites someone to go hunting for a way
> round it. Make changes in FORScan; take a backup here first.
>
> The sections below on the write sequence and security access are kept because
> they explain *why* the limit exists, and because the reading side depends on
> the same format and checksum work.

## What it is

Every Ford module holds a block of configuration bytes describing how that
particular truck was ordered: which lights it has, whether a trailer brake
controller is fitted, what the cluster displays, which SYNC features are
licensed. Ford publishes the factory values per VIN. Changing them is how
features get enabled or suppressed after the fact.

## What is verified here and what is not

This distinction is the whole point of this document.

**Verified, and covered by tests:**

- *The text format.* `726-01-01 0F14 0004 0000 0A` is module address, block
  identifier, data words, trailing checksum byte. Parsing and formatting
  round-trip exactly.
- *The transport.* Blocks are read with UDS ReadDataByIdentifier (0x22) and
  written with WriteDataByIdentifier (0x2E). Both are ISO standard.

**Hypothesis, resolved at runtime rather than asserted:**

- *Which identifier holds which block.* `AsBuiltDidMap` starts from the
  community-held 0xDE00 convention, but `AsBuiltReader.discoverDids` sweeps the
  candidate ranges and records which identifiers the module actually answers.
  Slower than a lookup table, but a measurement rather than a guess - and it
  still works on a module nobody has documented. Better still, import a bus
  capture and the sweep is replaced by the identifiers a working tool was
  observed reading: see [TRACE_IMPORT.md](TRACE_IMPORT.md).
- *The checksum algorithm.* Not published. `ChecksumStrategy` holds the
  plausible byte-sum variants, and `detect` identifies which one your modules
  actually use by testing candidates against blocks already read from the truck.
  Detection demands unanimity across every block: a strategy that fits some and
  not others is coincidence, and acting on coincidence writes bad data.
  **If no candidate explains the data, writes are refused.** That is correct - if
  we cannot reproduce the checksum the module already accepted, we have no
  business computing a new one.
- *Whether the returned data includes the checksum byte.* Also undocumented.
  Both readings are tried and whichever produces self-consistent checksums wins.

**Not present, and not obtainable from code:**

- *What the individual bits mean.* Ford proprietary. This app shows you the
  bytes, backs them up, and writes them back. It cannot tell you that bit 3 of
  byte 2 of block 01-01 is the daytime running lamps. Use Ford's published
  As-Built for your VIN alongside it.

## Security access is where you will stop

Writes are gated behind UDS SecurityAccess (0x27): the module issues a seed, and
the tester must return a key derived from it. The derivation is manufacturer
proprietary.

For older Ford modules the community recovered it and it is simple arithmetic -
`SeedKeyAlgorithm.Legacy` implements that, and it costs nothing to try. For a
2022 Super Duty it generally does not apply: current modules use a keyed
algorithm whose secret lives on Ford's servers, and the dealer tool (FDRS)
performs the handshake online.

So expect `Security access denied` with an explanation, not a mysterious failure.
`SeedKeyAlgorithm` is a plug-in point: if you work out a derivation, drop it in
via `SeedKeyAlgorithm.Custom` and the rest of the stack is ready.

The module tells you the truth about a wrong key: NRC 0x35 (invalid key), and
after too many attempts NRC 0x36 plus a lockout until the ignition is cycled.
`SecurityAccessManager` counts attempts and stops at two rather than
brute-forcing you into that state.

## The write sequence, and why it is not here

This is what a safe write would require. It was implemented, then removed: the
sequence is sound but it can never get past step 4 on this vehicle, and shipping
a path that always dead-ends is a way of implying otherwise.

Every write would go through this, and any failure stops it:

1. **A backup must already exist and be restorable.** Not optional. A snapshot
   held only in memory is not a backup.
2. **The checksum algorithm must be known.** Refused otherwise.
3. **Supply voltage must be sane** - above 12.0 V. A brown-out part way through
   a write is the classic way to lose a module.
4. **The module must grant a programming session and security access.**
5. **Each block is written, then read back and compared.** A module can accept a
   write and store something different; only comparing proves it took.
6. **If any step fails, blocks already written are restored** in reverse order,
   and you are told exactly what state the module was left in.

The checksum is always recomputed before writing. An edited block carrying its
old checksum is exactly what a module rejects.

## Backups

Stored as Ford-format text files in the app's storage, listed on the As-Built
screen.

Plain files rather than a database, deliberately. A configuration backup is only
worth having if it survives the app: you can share it to email or cloud storage,
read it on a laptop, paste it into a forum thread, or hand it to whoever ends up
recovering the module. A row in an app-private SQLite file fails at all of that.

**Get them off the phone.** A backup that only exists on the device you are
holding is one dropped phone away from useless.

On load, the checksum strategy is re-derived from the file's contents rather than
trusted from the comment header - if the file was hand-edited the recorded
strategy may no longer hold, and a wrong strategy is how a restore writes bad
checksums.

## Before you change anything

1. Back up the module. Verify the file is readable and get a copy off the phone.
2. Get the factory As-Built for your VIN from Ford and work from it.
3. Change one thing at a time, and know what it does before you write it.
4. Put a charger on the battery.
5. Expect to be stopped at security access, and do not go looking for a way
   around it that involves guessing keys at a module that locks out.
