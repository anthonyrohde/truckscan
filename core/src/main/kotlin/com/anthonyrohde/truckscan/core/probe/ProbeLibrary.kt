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
                "protocol B, whose options byte is documented inconsistently. The app " +
                "tries five candidates every time and caches none, because none was " +
                "ever confirmed. Each candidate is judged by whether a module replies, " +
                "not by listening to the bus - monitoring reports silence on this " +
                "vehicle even while a module is answering, so it cannot tell a working " +
                "setup from a broken one. CAN ERROR means the physical layer did not " +
                "come up; NO DATA means it did and nobody was home. Ignition ON.",
            script = """
                # MS-CAN is 125 kbps on pins 3/11 and needs ELM327 protocol B, whose
                # options byte is documented inconsistently. The app tries five candidates
                # every time and caches none, because none was ever confirmed.
                #
                # This does NOT use ATMA to judge them. Monitoring returns silence on this
                # vehicle even while a module is answering, so it cannot tell a working
                # setup from a broken one. Instead each candidate is followed by a
                # TesterPresent to addresses that live on MS-CAN, and the reply is the
                # verdict:
                #
                #   a reply (7xx ... 7E ...) -> this candidate works
                #   NO DATA                  -> configured correctly, nobody home
                #   CAN ERROR                -> the physical layer did not come up; wrong
                #
                # The difference between NO DATA and CAN ERROR is the whole point.
                # Ignition ON.

                ATZ
                ATE0
                ATH1
                ATCAF0
                ATCRA

                # --- the adapter's own command, if it has one
                STP 33
                STPBR 125000
                STPBRR
                ATSH 726
                023E000000000000
                ATSH 720
                023E000000000000
                ATSH 7D0
                023E000000000000

                # --- ELM protocol B, options C0
                ATPB C0 04
                ATSPB
                ATSH 726
                023E000000000000
                ATSH 720
                023E000000000000

                # --- options 40
                ATPB 40 04
                ATSPB
                ATSH 726
                023E000000000000
                ATSH 720
                023E000000000000

                # --- options 01
                ATPB 01 04
                ATSPB
                ATSH 726
                023E000000000000
                ATSH 720
                023E000000000000

                # --- options 11
                ATPB 11 04
                ATSPB
                ATSH 726
                023E000000000000
                ATSH 720
                023E000000000000

                # --- options 80
                ATPB 80 04
                ATSPB
                ATSH 726
                023E000000000000
                ATSH 720
                023E000000000000
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
            name = "Can anything be overheard (answered: no)",
            question =
            "Asked whether listening to the bus could replace the 1024-address sweep. " +
                "Answered on a 2022 F-250: no. With the ignition on and the PCM " +
                "answering a request 150 ms earlier, ATMA, STM and STMA all reported " +
                "silence, with and without a receive filter. The likeliest reason is " +
                "the gateway these trucks put in front of the OBD port: it routes " +
                "diagnostic traffic on request and does not mirror the internal buses, " +
                "so there is nothing to overhear. Kept because the answer may differ on " +
                "another vehicle, and because a negative result is worth being able to " +
                "reproduce.",
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
