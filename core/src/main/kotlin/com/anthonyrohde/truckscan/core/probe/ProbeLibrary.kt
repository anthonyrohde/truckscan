package com.anthonyrohde.truckscan.core.probe

/**
 * Investigations worth running, each aimed at one thing this project currently
 * guesses at.
 *
 * Every entry here exists because some piece of the code makes an assumption it
 * cannot check. That is not a criticism of the code - protocol behaviour on an
 * adapter that is not present cannot be tested any other way - but an
 * assumption that has never met the hardware should be labelled as one, and
 * these turn them into measurements.
 *
 * Every script is read-only. [ProbeScript] enforces that independently, so an
 * error here cannot put anything harmful on the bus.
 */
object ProbeLibrary {

    data class Investigation(
        val name: String,
        /** What is currently assumed, and what running this would settle. */
        val question: String,
        val script: String,
    )

    val ALL: List<Investigation> = listOf(
        Investigation(
            name = "Which MS-CAN init works",
            question =
            "MS-CAN runs at 125 kbps on pins 3/11 and needs the ELM327 user-defined " +
                "protocol B. Its options byte is documented inconsistently across " +
                "datasheet revisions, so the app tries five candidates every time it " +
                "touches that bus and caches nothing, because none has ever been " +
                "confirmed. This tries each and watches for traffic. Run it with the " +
                "ignition ON - a sleeping bus is silent whichever byte is right, which " +
                "would make every candidate look equally wrong.",
            script = """
                # Each block: configure, then listen for traffic.
                # ATMA returns lines if the bus is alive, STOPPED if silent.

                ATZ
                ATE0
                ATH1
                ATCAF0

                # The adapter's own native command, if it has one.
                STP 33
                STPBR 125000
                STPBRR
                ATMA

                # ELM protocol B, options C0
                ATPB C0 04
                ATSPB
                ATMA

                # options 40
                ATPB 40 04
                ATSPB
                ATMA

                # options 01
                ATPB 01 04
                ATSPB
                ATMA

                # options 11
                ATPB 11 04
                ATSPB
                ATMA

                # options 80
                ATPB 80 04
                ATSPB
                ATMA
            """.trimIndent(),
        ),

        Investigation(
            name = "Can the line rate be raised",
            question =
            "The USB link runs at 115,200 because that is where the adapter powers " +
                "up and the app never asks for more. The EX advertises 2 Mbit/s, which " +
                "would make a long As-Built read several times faster. STBR is the " +
                "command that moves it. Nobody has tried, so this asks what the " +
                "adapter reports it supports before anything attempts to use it.",
            script = """
                ATZ
                ATE0

                # What the adapter says it is, and what it was built from.
                ATI
                STI
                STDI

                # Current serial rate, and the programmable parameter behind it.
                STBR
                ATPPS

                # Device description and serial number, for the record.
                @1
                STSN
            """.trimIndent(),
        ),

        Investigation(
            name = "Does a command break a burst",
            question =
            "The live-data and As-Built fixes rest on a claim I could not test: that " +
                "sending any command between flow control and the consecutive frames " +
                "makes ELM firmware service the command instead of the bus, losing the " +
                "frames. It matches the log from the truck and the fix works, but it " +
                "has never been demonstrated. This asks for a reply long enough to " +
                "need flow control, sends it the correct way, and lets you see the " +
                "whole exchange. Run with the ignition ON.",
            script = """
                ATZ
                ATE0
                ATH1
                ATCAF0
                ATCFC0
                ATSP6

                # Ask the PCM for its part number: a reply too long for one frame.
                ATSH 7E0
                ATCRA 7E8
                0322F18800000000

                # Grant the rest of the transfer. What follows this line is the
                # evidence: consecutive frames, or nothing.
                3000000000000000
                ATR0
                3000000000000000
            """.trimIndent(),
        ),

        Investigation(
            name = "Which addresses are alive",
            question =
            "A full sweep probes 256 addresses on each bus and takes minutes, because " +
                "it asks every possible address whether anything is there. Modules " +
                "that are awake transmit on their own, so listening for a few seconds " +
                "should name them in one go. If this returns a useful set of IDs, " +
                "discovery could be seconds rather than minutes and would find modules " +
                "the address list does not know about. Run with the ignition ON.",
            script = """
                ATZ
                ATE0
                ATH1
                ATSP6

                # Everything transmitting on the powertrain bus.
                ATMA
            """.trimIndent(),
        ),
    )
}
