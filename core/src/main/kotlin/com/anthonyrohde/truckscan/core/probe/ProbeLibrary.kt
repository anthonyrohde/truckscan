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
            name = "Every protocol primitive, one at a time",
            question =
            "The app sends a dozen adapter commands on every connection and not one has ever been isolated. This exercises each in turn with a readable outcome, starting with the battery voltage so a sleeping truck announces itself instead of quietly voiding the run. It carries two experiments. The first: the burst probe used ATSP6 and the PCM answered, while a minute earlier STP 33 with nothing after it got NO DATA from the same PCM - so STP may set a protocol without opening it, and every MS-CAN attempt may have been made on a bus that was never brought up. The second: the live-data and As-Built fixes rest on a claim about interrupted multi-frame bursts that has never been demonstrated, and this sends the same request the right way and the wrong way so the difference is visible or absent.",
            script = """
                # Every adapter command this app relies on, one at a time, each with an
                # outcome you can read. The app sends all of it on every connection and the
                # only evidence has ever been "the app worked".
                #
                # READ SECTION A FIRST. If the truck is asleep the whole run is void, which is
                # exactly what happened to the last MS-CAN run.
                #
                # Engine running if the work allows it. With the engine off the battery is only being drawn down, and this truck reached 10.0 V while its modules were still answering. Engine running is better still - ATRV will say which.

                # ============================================================ A. is it awake
                # ATRV is the adapter's own voltmeter and needs no bus at all.
                #   ~12.2-12.7  key off, battery only
                #   ~13.8-14.6  engine running, alternator charging
                ATZ
                ATE0
                ATRV

                # What the adapter is. ATI is the ELM327 compatibility string and says
                # ELM327 v1.4b even on an STN chip; STI and STDI are the real ones. @1 is
                # here to record that this adapter answers ? to it - the app used to ask @1
                # for its name and got nothing.
                ATI
                STI
                STDI
                @1
                STSN
                STBR
                ATPPS

                # ================================== B. which command actually opens a bus
                # THE FIRST EXPERIMENT, and it may invalidate the MS-CAN result.
                #
                # The burst probe used ATSP6 and the PCM answered. A minute earlier the
                # MS-CAN probe used STP 33 with nothing after it and the same PCM returned
                # NO DATA. The app has always sent STP followed by STPBR, and the app works.
                # So STP on its own may set a protocol without opening it, and every MS-CAN
                # attempt may have been made on a protocol that was never brought up.
                #
                # Four independent attempts at the same request, each from a clean reset.
                # The one that answers 7E8 ... 41 00 ... is the one that opens a bus.

                # --- B1: ATSP6, the plain ELM327 way
                ATZ
                ATE0
                ATH1
                ATCAF0
                ATCFC0
                ATSP6
                ATSH 7E0
                ATCRA 7E8
                0201000000000000

                # --- B2: STP 33 alone
                ATZ
                ATE0
                ATH1
                ATCAF0
                ATCFC0
                STP 33
                ATSH 7E0
                ATCRA 7E8
                0201000000000000

                # --- B3: STP 33 then STPO, which is documented as "open current protocol"
                ATZ
                ATE0
                ATH1
                ATCAF0
                ATCFC0
                STP 33
                STPO
                ATSH 7E0
                ATCRA 7E8
                0201000000000000

                # --- B4: STP 33 then STPBR, which is what the app sends and what works
                ATZ
                ATE0
                ATH1
                ATCAF0
                ATCFC0
                STP 33
                STPBR 500000
                STPBRR
                ATSH 7E0
                ATCRA 7E8
                0201000000000000

                # ============================== C. MS-CAN again, opened the same two ways
                # If B3 or B4 answered and B2 did not, then the MS-CAN run proved nothing,
                # because it was STP 53 with nothing after it. This repeats it properly.
                # CAN ERROR means nothing acknowledged; NO DATA means the right pins and
                # nobody at that address. One NO DATA here is the headline.

                ATZ
                ATE0
                ATH1
                ATCAF0
                ATCFC0
                STP 53
                STPO
                STPRS
                ATSH 726
                023E000000000000
                ATSH 720
                023E000000000000
                ATSH 7D0
                023E000000000000

                ATZ
                ATE0
                ATH1
                ATCAF0
                ATCFC0
                STP 53
                STPBR 125000
                STPBRR
                STPRS
                ATSH 726
                023E000000000000
                ATSH 720
                023E000000000000
                ATSH 733
                023E000000000000
                ATSH 7D0
                023E000000000000

                # ================================================== D. the working baseline
                # Exactly what the app sets up, then the request that must answer. Everything
                # below this line depends on this line replying.
                ATZ
                ATE0
                ATL0
                ATS0
                ATH1
                ATAL
                ATCAF0
                ATCFC0
                ATAT1
                ATSP6
                ATDPN
                ATSH 7E0
                ATCRA 7E8
                0201000000000000

                # ============================================ E. does ATCAF1 change meaning
                # The app runs with auto-formatting OFF and writes its own PCI byte. With it
                # ON the adapter writes the PCI and the caller sends the service directly, so
                # the same hex means two different things. That ambiguity nearly let an ECU
                # reset through the read-only checker.
                #
                # 0103 is the demonstration because it is a read under BOTH readings: mode 01
                # PID 03 with formatting on, stored fault codes with it off. A line that is
                # only safe under one reading is exactly what must never be sent.
                ATCAF1
                0103
                ATCAF0
                0103000000000000

                # ================================================= F. does the filter matter
                # ATCRA sets which CAN IDs are let through. Cleared, the reply should still
                # arrive, possibly with other traffic alongside. Set, only 7E8.
                ATCRA
                0201000000000000
                ATCRA 7E8
                0201000000000000

                # ================================================== G. does ATST do anything
                # ATST sets the wait before giving up, in 4 ms units. The bracketed timing on
                # each line is the measurement. PID 4E is not a real PID, so both are misses
                # on purpose: a tiny timeout should fail fast, a large one slowly.
                ATST 05
                02014E0000000000
                ATST FF
                02014E0000000000
                ATST 32

                # =============================================== H. what the PCM actually has
                # The supported-PID bitmaps: 4 bytes of flags for the next 32 PIDs each, the
                # last bit saying whether the following bitmap exists. This is the definitive
                # list of what this truck can put on a gauge, replacing a catalogue of 45
                # that was never checked against the vehicle.
                0201000000000000
                0201200000000000
                0201400000000000
                0201600000000000
                0201800000000000
                0201A00000000000
                0201C00000000000
                0201E00000000000

                # ======================================== I. multi-frame, the correct way
                # Mode 09 PID 02 is the VIN: 17 bytes, too long for one frame. Expect a first
                # frame (7E8 10 ...), silence until flow control, then 7E8 21 ..., 7E8 22 ...
                ATCRA 7E8
                0209020000000000
                3000000000000000

                # ================================ J. multi-frame, with a command in between
                # THE SECOND EXPERIMENT. The live-data and As-Built fixes rest on a claim
                # that has never been demonstrated: that any command sent between the first
                # frame and the flow control makes the firmware service the command instead
                # of the bus, losing the consecutive frames. Same request as I, with one
                # harmless command inserted.
                #
                # I produced the VIN and this does not -> the claim is demonstrated.
                # Both work -> the fix is harmless but my stated reason for it is wrong,
                # and I would rather know that than keep repeating it.
                0209020000000000
                ATCRA 7E8
                3000000000000000

                # ============================================ K. the long read, the app's way
                # ReadDataByIdentifier F188 is a part number over several frames - the path
                # the As-Built reader uses. ATR0 stops the adapter waiting for a reply to the
                # flow control it has just been told not to expect.
                ATSH 7E0
                ATCRA 7E8
                0322F18800000000
                ATR0
                3000000000000000
                ATR1

                # ========================================================== L. leave it clean
                ATZ
            """.trimIndent(),
        ),

        Investigation(
            name = "The paths the primitives run did not cover",
            question =
            "The primitives run settled the request and response path. It did not touch four things the app depends on: which addresses actually answer on the powertrain bus now that MS-CAN is known to be empty, ReadDTCInformation on its own, the As-Built read path - which has never been run against this truck at all - and the entire 29-bit code path. This covers those. It cannot cover the multi-frame request path: a first frame begins 0x10, which is also DiagnosticSessionControl, so the read-only rule refuses to put one on a bus. That refusal is correct and stays, and that path is marked in the source as reasoned rather than measured.",
            script = """
                # The paths the app uses that the primitives run did not cover. Everything
                # here is a read. Ignition ON, engine running if you can - section A is the
                # liveness check and the rest is void without it.

                # ============================================================ A. is it awake
                ATZ
                ATE0
                ATL0
                ATS0
                ATH1
                ATAL
                ATCAF0
                ATCFC0
                ATAT1
                STP 33
                ATSH 7E0
                ATCRA 7E8
                0201000000000000

                # ============================================ B. who is actually on this bus
                # MS-CAN is empty on this truck, so every module it has must answer here,
                # through the gateway. This is module discovery, by hand, with the receive
                # filter cleared so any address can reply - watch the CAN ID on each reply,
                # not just the fact of one. A module that refuses with 7F is still a module.
                #
                # This also matters because a full sweep in the app once found nothing and
                # that has never been explained.
                ATCRA
                ATSH 7E0
                023E000000000000    # PCM
                ATSH 7E1
                023E000000000000    # TCM
                ATSH 7E2
                023E000000000000    # engine 3
                ATSH 760
                023E000000000000    # ABS
                ATSH 706
                023E000000000000    # RCM
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
                ATSH 730
                023E000000000000    # GWM
                ATSH 764
                023E000000000000    # PSCM
                ATSH 765
                023E000000000000    # IPMA
                ATSH 775
                023E000000000000    # TRM
                ATSH 783
                023E000000000000    # RTM

                # ==================================================== C. fault codes, isolated
                # UDS ReadDTCInformation, subfunction 02, mask FF: every stored DTC. The app
                # does this and it worked, but it has never been run on its own.
                ATSH 7E0
                ATCRA 7E8
                031902FF00000000
                3000000000000000

                # ================================================= D. the As-Built read path
                # Ford keeps configuration in DIDs from DE00 up, read with service 22. The
                # As-Built reader has never been run against this truck at all, so this asks
                # for the first few blocks and nothing more. Reading is all it does.
                0322DE0000000000
                3000000000000000
                0322DE0100000000
                3000000000000000
                0322DE0200000000
                3000000000000000

                # A longer one, to exercise a reply spanning several flow controls.
                0322F19000000000
                3000000000000000

                # ======================================== E. functional addressing, 11-bit
                # 7DF is the broadcast address the live-data poller uses. Several modules may
                # answer one request; with the filter cleared you should see more than 7E8 if
                # anything else speaks OBD-II.
                ATCRA
                ATSH 7DF
                0201000000000000
                02010C0000000000
                0201050000000000

                # ============================================== F. 29-bit addressing, untried
                # Nothing on this truck has needed it, and the app has a whole code path for
                # it that has never touched hardware. 18DB33F1 is the standard 29-bit
                # functional request; a reply would come from 18DAF1xx.
                ATZ
                ATE0
                ATH1
                ATCAF0
                ATCFC0
                STP 34
                STPRS
                ATCRA
                ATSH 18DB33F1
                0201000000000000

                # ========================================================== G. leave it clean
                ATZ
            """.trimIndent(),
        ),

        Investigation(
            name = "Confirm the cluster decoders against the truck",
            question =
            "Two decoders the cluster depends on have never been checked against a vehicle, and a wrong divisor produces a plausible number rather than an error. Both are settled by one run. The odometer, PID A6, has a known answer: this truck's cluster reads 35707.4 km, so the four bytes should be 0x000572D2. Manifold pressure, PID 87, is absolute, so at idle it is atmospheric and must agree with the barometer on PID 33. The run also reads every gauge the cluster draws, and records raw bytes for fuel rate, DPF differential pressure and the two turbocharger pressures, so adding those later is a measurement rather than another guess. Engine running - idle is what makes the manifold check work.",
            script = """
                # Confirms the decoders the cluster relies on that have never been checked
                # against a vehicle, and reads every gauge the cluster draws.
                #
                # Two of these are guesses from the standard rather than from this truck, and
                # a wrong divisor produces a plausible number rather than an error - which is
                # the worst kind of wrong for a gauge. Both are checkable in one reading:
                #
                #   PID A6, odometer. The cluster in this truck read 35707.4 km. Four bytes,
                #   tenths of a kilometre, so a correct decode is 0x000572D2 = 357074. If the
                #   bytes say something else, the scaling is wrong and the gauge is wrong.
                #
                #   PID 87, manifold absolute pressure. This is ABSOLUTE, so at idle it reads
                #   atmospheric - it should agree with barometric pressure (PID 33) to within
                #   a couple of kPa. Five bytes: a status byte then two 16-bit readings, said
                #   to be thirty-seconds of a kPa. If B,C over 32 does not land near the
                #   barometer, the divisor is wrong.
                #
                # Engine running. Idle is what makes the manifold check work.

                ATZ
                ATE0
                ATL0
                ATS0
                ATH1
                ATAL
                ATCAF0
                ATCFC0
                ATAT1
                STP 33
                ATSH 7E0
                ATCRA 7E8

                # --- the liveness check, and the battery. ATRV is the one that matters now.
                ATRV
                0201000000000000

                # ================================================ the two unconfirmed ones
                # Barometric first, so the manifold reading has something to be compared with
                # in the same run and the same weather.
                0201330000000000
                0201870000000000
                0201A60000000000

                # ======================================================= every cluster gauge
                02010C0000000000    # engine speed
                02010D0000000000    # vehicle speed
                0201050000000000    # coolant temperature
                02015C0000000000    # engine oil temperature
                02012F0000000000    # fuel tank level
                0201420000000000    # control module voltage
                0201460000000000    # ambient air temperature
                0201780000000000    # exhaust gas temperature
                0201770000000000    # charge air cooler temperature
                0201040000000000    # calculated engine load

                # ================================ worth having, and not decoded by this app
                # 9D is engine fuel rate and 7A is DPF differential pressure. Neither is in
                # the catalogue, because neither has a reading to check the scaling against.
                # Recording the raw bytes here is what makes adding them later a measurement
                # rather than another guess.
                02019D0000000000
                02017A0000000000
                02016F0000000000    # turbocharger compressor inlet pressure
                0201700000000000    # boost pressure control

                # ============================ supported, and this app fails to decode them
                # The vehicle reports both as supported and neither produces a reading, so
                # the suspect is the layout this app expects rather than the truck. Both are
                # multi-sensor structures with a leading byte saying which sensors are
                # present. The raw bytes are the whole point of asking - with them the fix is
                # arithmetic, without them it is another guess.
                0201780000000000    # exhaust gas temperature, bank 1
                02016B0000000000    # EGR temperature

                ATZ
            """.trimIndent(),
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
                "mistaken for a dead bus. Engine running if the work allows it. With the engine off the battery is only being drawn down, and this truck reached 10.0 V while its modules were still answering.",
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
                # adapter is on pins 3/11 for the first time. Engine running if the work allows it. With the engine off the battery is only being drawn down, and this truck reached 10.0 V while its modules were still answering.

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
