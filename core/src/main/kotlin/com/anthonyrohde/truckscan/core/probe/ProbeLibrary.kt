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

    /**
     * Protocol numbers to walk. The ELM327 range is a single hex digit; the STN
     * chips add two-digit numbers above it, and this project has never had a
     * copy of the table that names them. Rather than guess which one is MS-CAN,
     * [PROTOCOL_SWEEP] asks the chip for every one it will accept and prints
     * the name it gives back.
     */
    private val PROTOCOL_SWEEP: List<Int> = (0x00..0x0C) + (0x20..0x7F)

    /**
     * `STP` sets the protocol without opening it, so this puts nothing on any
     * bus: an unsupported number answers `?` and leaves the previous protocol
     * alone. That is what makes a sweep like this safe to run with the truck
     * connected.
     */
    private fun protocolSweep(): String = buildString {
        appendLine(
            """
            # Asks the adapter which protocols it has, and what it calls them.
            #
            # This exists because of a measured dead end. Every MS-CAN candidate
            # this project could think of came back CAN ERROR on a 2022 F-250:
            # the adapter's own STP 33 with STPBR 125000 (STPBRR confirmed the
            # 125000 took), and all five ELM327 protocol B options bytes. Looking
            # at what those commands actually do, that is not surprising - ATPB
            # and STPBR configure a CAN controller's bitrate and options. Nothing
            # in them tells the adapter to route its transceiver to pins 3/11.
            # Six candidates may have been six ways of talking to the wrong wires.
            #
            # The chip knows. STP sets a protocol without opening it - an
            # unsupported number answers ? and changes nothing, and a supported
            # one is set but not connected, so this sweep puts nothing on any
            # bus. STPRS then reports the protocol's name. Whatever the adapter
            # calls MS-CAN, it will say so here.
            #
            # Ignition can be off. Read the output for a name containing MS-CAN,
            # MEDIUM, or 125.

            ATZ
            ATE0

            # Where it starts, so the sweep can be read against it.
            STPR
            STPRS
            """.trimIndent(),
        )
        appendLine()
        for (protocol in PROTOCOL_SWEEP) {
            appendLine("STP %02X".format(protocol))
            appendLine("STPRS")
        }
        appendLine()
        appendLine("# Back to the standard powertrain bus.")
        append("STP 06")
    }

    val ALL: List<Investigation> = listOf(
        Investigation(
            name = "Which protocols this adapter has",
            question =
            "Every MS-CAN candidate this project could think of returned CAN ERROR " +
                "on the truck: the adapter's own STP 33 with STPBR 125000, and all " +
                "five ELM327 protocol B options bytes. Those commands set a CAN " +
                "controller's bitrate and options; none of them says which pins the " +
                "transceiver is wired to, so all six may have been the wrong wires. " +
                "Instead of guessing a seventh, this asks the chip: STP sets a " +
                "protocol without opening it, so every number can be offered and " +
                "STPRS asked what it is called. Whatever this adapter calls MS-CAN, " +
                "it says so here. Nothing is put on any bus, so the ignition can be " +
                "off.",
            script = protocolSweep(),
        ),

        Investigation(
            name = "Does MS-CAN answer on protocol 53",
            question =
            "The adapter's own protocol table, read back with STP xx and STPRS, "  +
                "names protocol 53 \"MS CAN (ISO 15765, 125K/11B)\" - pins 3/11 at "  +
                "the right rate, in one command. Every earlier attempt sent STP 33 "  +
                "with STPBR 125000, and protocol 33 is \"HS CAN (ISO 15765, "  +
                "500K/11B)\": the high speed transceiver on pins 6/14, told to run "  +
                "at 125 kbps. That cannot acknowledge anything, which is why every "  +
                "attempt returned CAN ERROR and never NO DATA. This is the corrected "  +
                "test, ending on the powertrain bus so a dead adapter cannot be "  +
                "mistaken for a dead bus. Ignition ON.",
            script = """
                # ANSWERED. The adapter's protocol table, read back with STP xx / STPRS,
                # names protocol 53 "MS CAN (ISO 15765, 125K/11B)" - pins 3/11, the right
                # rate, in one command.
                #
                # What this project used to send was STP 33 with STPBR 125000. Protocol 33
                # is "HS CAN (ISO 15765, 500K/11B)". So it selected the HIGH SPEED
                # transceiver on pins 6/14 and set it to 125 kbps: a 125 kbps node on a
                # 500 kbps bus, which can never acknowledge a frame. Hence CAN ERROR every
                # time, and never once NO DATA. The five ELM327 protocol B candidates were
                # the same mistake - ATPB sets a controller's rate, not its pins.
                #
                # This is the corrected test. Read the replies:
                #
                #   a reply (7xx ... 7E ...) -> MS-CAN is real and reachable
                #   NO DATA                  -> on the right pins, nobody at that address
                #   CAN ERROR                -> still not reaching the bus
                #
                # One NO DATA anywhere below is already the headline: it would mean the
                # adapter is on pins 3/11 for the first time. Ignition ON.

                ATZ
                ATE0
                ATH1
                ATCAF0
                ATCFC0
                ATCRA

                # --- MS-CAN, 11-bit, ISO 15765. The rate is part of the protocol; no STPBR.
                STP 53
                STPRS

                ATSH 726
                023E000000000000    # BCM
                ATSH 720
                023E000000000000    # IPC
                ATSH 724
                023E000000000000    # SCCM
                ATSH 712
                023E000000000000    # DSM
                ATSH 733
                023E000000000000    # HVAC
                ATSH 736
                023E000000000000    # PAM
                ATSH 7D0
                023E000000000000    # APIM
                ATSH 727
                023E000000000000    # ACM
                ATSH 740
                023E000000000000    # FCIM
                ATSH 754
                023E000000000000    # TCU
                # --- same bus, 29-bit addressing, in case the body modules are extended.
                STP 54
                STPRS
                ATSH 726
                023E000000000000
                ATSH 7D0
                023E000000000000

                # --- back to the powertrain bus, and prove the adapter still works. The PCM
                #     must answer here. If it does not, the MS-CAN result above means nothing
                #     because the adapter was broken, not the bus.
                STP 33
                STPRS
                ATSH 7E0
                ATCRA 7E8
                0201000000000000
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
