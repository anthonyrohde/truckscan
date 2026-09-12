package com.anthonyrohde.truckscan.core

import com.anthonyrohde.truckscan.core.ford.ChecksumStrategy
import com.anthonyrohde.truckscan.core.ford.SeedKeyAlgorithm
import com.anthonyrohde.truckscan.core.isotp.IsoTpSegmenter
import com.anthonyrohde.truckscan.core.trace.LearnedProfile
import com.anthonyrohde.truckscan.core.trace.TraceLogAnalyzer
import com.anthonyrohde.truckscan.core.trace.securityAccessManager
import com.anthonyrohde.truckscan.core.trace.seedKeyPairs
import com.anthonyrohde.truckscan.core.util.Hex
import com.anthonyrohde.truckscan.core.util.toHex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Builds synthetic captures with the real ISO-TP segmenter, so the analyzer is
 * fed exactly the frame sequences a genuine log would contain.
 */
class TraceAnalyzerTest {

    private val bcmRequest = 0x726
    private val bcmResponse = 0x72E

    /** Renders a UDS message as the log lines a capture would hold. */
    private fun lines(canId: Int, payloadHex: String): List<String> =
        IsoTpSegmenter.segment(Hex.decode(payloadHex)).map { frame ->
            "${Hex.encode(canId, 3)}#${Hex.encode(frame.encode())}"
        }

    /** Two's complement checksum, as the simulated modules use. */
    private fun withChecksum(dataHex: String): String {
        val data = Hex.decode(dataHex)
        return dataHex + Hex.encode(ChecksumStrategy.TWOS_COMPLEMENT.compute(data), 2)
    }

    /**
     * A capture of a working tool reading and writing a BCM: two configuration
     * blocks, the VIN, a write, a routine, and a security handshake.
     */
    private fun bcmCapture(): String {
        val block0 = withChecksum("410200001C08")
        val block1 = withChecksum("00300000")
        val vin = "1FT8W2BT7NEC12345".toByteArray(Charsets.US_ASCII).toHex()

        return buildList {
            // --- read configuration block DE00
            addAll(lines(bcmRequest, "22DE00"))
            addAll(lines(bcmResponse, "62DE00$block0"))

            // --- read configuration block DE01
            addAll(lines(bcmRequest, "22DE01"))
            addAll(lines(bcmResponse, "62DE01$block1"))

            // --- read the VIN (multi-frame response, so flow control appears)
            addAll(lines(bcmRequest, "22F190"))
            add("${Hex.encode(bcmRequest, 3)}#3000000000000000")
            addAll(lines(bcmResponse, "62F190$vin"))

            // --- security handshake: seed requested, key accepted
            addAll(lines(bcmRequest, "2701"))
            addAll(lines(bcmResponse, "67014A7C"))
            addAll(lines(bcmRequest, "270291E3"))
            addAll(lines(bcmResponse, "6702"))

            // --- write configuration block DE00
            addAll(lines(bcmRequest, "2EDE00$block0"))
            addAll(lines(bcmResponse, "6EDE00"))

            // --- start a routine
            addAll(lines(bcmRequest, "31010203"))
            addAll(lines(bcmResponse, "71010203"))
        }.joinToString("\n")
    }

    // --------------------------------------------------------------- reassembly

    @Test
    fun `single and multi frame messages both reassemble`() {
        val analyzer = TraceLogAnalyzer()
        val (profile, report) = analyzer.analyseText(bcmCapture(), "test capture")

        assertFalse(report.isEmpty, report.describe())
        assertFalse(profile.isEmpty, profile.summarise())
        assertTrue(profile.messagesReconstructed >= 12, "got ${profile.messagesReconstructed}")
    }

    @Test
    fun `flow control frames are not treated as messages`() {
        val analyzer = TraceLogAnalyzer()
        val (frames, _) = com.anthonyrohde.truckscan.core.trace.TraceLineParser
            .parse("726#3000000000000000")
        val messages = analyzer.reassemble(frames)
        assertTrue(messages.isEmpty(), "a lone flow control frame carries no message")
    }

    // ------------------------------------------------------------------ learning

    @Test
    fun `configuration identifiers are learned - this is the real did map`() {
        val profile = TraceLogAnalyzer().analyseText(bcmCapture()).first
        val bcm = profile.module(bcmRequest)

        assertNotNull(bcm)
        assertEquals(
            setOf(0xDE00, 0xDE01),
            bcm!!.configurationDids.keys,
            "learned config identifiers: ${bcm.configurationDids.keys.map { Hex.encode(it, 4) }}",
        )
        assertEquals("410200001C0899", bcm.configurationDids[0xDE00]!!.toHex())
    }

    @Test
    fun `identification identifiers are kept separate from configuration`() {
        val profile = TraceLogAnalyzer().analyseText(bcmCapture()).first
        val bcm = profile.module(bcmRequest)!!

        assertEquals(setOf(0xF190), bcm.identificationDids.keys)
        assertFalse(
            bcm.configurationDids.containsKey(0xF190),
            "the VIN must not be classed as a checksummed config block",
        )
        assertEquals(
            "1FT8W2BT7NEC12345",
            String(bcm.identificationDids[0xF190]!!, Charsets.US_ASCII),
        )
    }

    @Test
    fun `checksum algorithm is measured from the captured blocks`() {
        val profile = TraceLogAnalyzer().analyseText(bcmCapture()).first
        assertEquals(
            ChecksumStrategy.TWOS_COMPLEMENT,
            profile.module(bcmRequest)!!.checksumStrategy,
        )
    }

    @Test
    fun `written identifiers are recorded`() {
        val profile = TraceLogAnalyzer().analyseText(bcmCapture()).first
        assertEquals(setOf(0xDE00), profile.module(bcmRequest)!!.writtenDids)
    }

    @Test
    fun `routine identifiers are learned by observation not by probing`() {
        val profile = TraceLogAnalyzer().analyseText(bcmCapture()).first
        assertEquals(setOf(0x0203), profile.module(bcmRequest)!!.routineIds)
    }

    @Test
    fun `security handshake is captured with its acceptance status`() {
        val profile = TraceLogAnalyzer().analyseText(bcmCapture()).first
        val observations = profile.module(bcmRequest)!!.seedKeyObservations

        assertEquals(1, observations.size)
        val observed = observations.single()
        assertEquals(0x01, observed.securityLevel)
        assertEquals("4A7C", observed.seed.toHex())
        assertEquals("91E3", observed.key.toHex())
        assertTrue(observed.accepted)
    }

    @Test
    fun `a rejected key is recorded but never replayed`() {
        val capture = buildList {
            addAll(lines(bcmRequest, "2701"))
            addAll(lines(bcmResponse, "67014A7C"))
            addAll(lines(bcmRequest, "2702DEAD"))
            // 0x7F 0x27 0x35 - invalid key
            addAll(lines(bcmResponse, "7F2735"))
        }.joinToString("\n")

        val profile = TraceLogAnalyzer().analyseText(capture).first
        val observed = profile.module(bcmRequest)!!.seedKeyObservations.single()

        assertFalse(observed.accepted)
        assertTrue(
            profile.seedKeyPairs().isEmpty(),
            "a rejected key must not be offered for replay - it would waste an attempt",
        )
    }

    @Test
    fun `response pending does not break request pairing`() {
        val block = withChecksum("410200001C08")
        val capture = buildList {
            addAll(lines(bcmRequest, "22DE00"))
            // Module stalls twice, then answers.
            addAll(lines(bcmResponse, "7F2278"))
            addAll(lines(bcmResponse, "7F2278"))
            addAll(lines(bcmResponse, "62DE00$block"))
        }.joinToString("\n")

        val profile = TraceLogAnalyzer().analyseText(capture).first
        assertEquals(
            "410200001C0899",
            profile.module(bcmRequest)!!.configurationDids[0xDE00]!!.toHex(),
        )
    }

    @Test
    fun `negative read responses do not populate the did map`() {
        val capture = buildList {
            addAll(lines(bcmRequest, "22DE7F"))
            // 0x7F 0x22 0x31 - request out of range
            addAll(lines(bcmResponse, "7F2231"))
        }.joinToString("\n")

        val profile = TraceLogAnalyzer().analyseText(capture).first
        assertTrue(
            profile.module(bcmRequest)?.configurationDids.isNullOrEmpty(),
            "an identifier the module refused does not exist",
        )
    }

    @Test
    fun `multiple modules in one capture are separated`() {
        val capture = buildList {
            addAll(lines(0x726, "22DE00"))
            addAll(lines(0x72E, "62DE00" + withChecksum("4102")))
            addAll(lines(0x7D0, "22DE00"))
            addAll(lines(0x7D8, "62DE00" + withChecksum("0605")))
        }.joinToString("\n")

        val profile = TraceLogAnalyzer().analyseText(capture).first

        assertEquals(2, profile.modules.size)
        assertNotNull(profile.module(0x726))
        assertNotNull(profile.module(0x7D0))
        assertEquals(0x7D8, profile.module(0x7D0)!!.responseId)
    }

    @Test
    fun `read order replaces a blind range sweep`() {
        val profile = TraceLogAnalyzer().analyseText(bcmCapture()).first
        val order = profile.module(bcmRequest)!!.readOrder()

        assertEquals(listOf(0xDE00, 0xDE01, 0xF190), order)
        // The blind sweep probes 448 candidates; this is three.
        assertTrue(
            order.size < 10,
            "a learned read should be a handful of identifiers, not hundreds",
        )
    }

    @Test
    fun `an unrelated capture produces an empty profile rather than noise`() {
        // Pure powertrain live data: no configuration reads at all.
        val capture = buildList {
            addAll(lines(0x7DF, "010C"))
            addAll(lines(0x7E8, "410C0C50"))
        }.joinToString("\n")

        val profile = TraceLogAnalyzer().analyseText(capture).first
        assertTrue(profile.isEmpty, profile.summarise())
        assertTrue(profile.summarise().contains("Nothing usable"))
    }

    // ------------------------------------------------------------- seed replay

    @Test
    fun `replay answers a seed it has seen and refuses one it has not`() {
        val profile = TraceLogAnalyzer().analyseText(bcmCapture()).first
        val replay = SeedKeyAlgorithm.Replay(profile.seedKeyPairs())

        assertEquals("91E3", replay.computeKey(Hex.decode("4A7C"), 0x01)!!.toHex())
        assertNull(
            replay.computeKey(Hex.decode("BEEF"), 0x01),
            "an unseen seed must not produce a fabricated key",
        )
    }

    @Test
    fun `security manager built from a profile tries replay before the legacy guess`() {
        val profile = TraceLogAnalyzer().analyseText(bcmCapture()).first
        val manager = profile.securityAccessManager()
        assertNotNull(manager)
        assertEquals(1, profile.seedKeyPairs().size)
    }

    // ----------------------------------------------------------- serialisation

    @Test
    fun `profile survives a serialise and deserialise round trip`() {
        val original = TraceLogAnalyzer().analyseText(bcmCapture(), "bcm-capture.txt").first
        val text = original.serialise()
        val restored = LearnedProfile.deserialise(text)

        assertNotNull(restored, "serialised profile should reload:\n$text")
        val before = original.module(bcmRequest)!!
        val after = restored!!.module(bcmRequest)!!

        assertEquals(before.responseId, after.responseId)
        assertEquals(before.checksumStrategy, after.checksumStrategy)
        assertEquals(before.configurationDids.keys, after.configurationDids.keys)
        assertEquals(before.identificationDids.keys, after.identificationDids.keys)
        assertEquals(before.writtenDids, after.writtenDids)
        assertEquals(before.routineIds, after.routineIds)
        assertEquals(before.seedKeyObservations, after.seedKeyObservations)
        assertEquals("bcm-capture.txt", restored.sourceDescription)
        assertEquals(
            before.configurationDids[0xDE00]!!.toHex(),
            after.configurationDids[0xDE00]!!.toHex(),
        )
    }

    @Test
    fun `serialised form is human readable`() {
        val profile = TraceLogAnalyzer().analyseText(bcmCapture(), "capture.txt").first
        val text = profile.serialise()

        assertTrue(text.contains("module 726 72E"), text)
        assertTrue(text.contains("checksum TWOS_COMPLEMENT"), text)
        assertTrue(text.contains("config DE00 410200001C0899"), text)
        assertTrue(text.contains("routine 0203"), text)
        assertTrue(text.contains("seedkey 01 4A7C 91E3 accepted"), text)
    }

    @Test
    fun `deserialising rubbish returns null`() {
        assertNull(LearnedProfile.deserialise(""))
        assertNull(LearnedProfile.deserialise("not a profile at all"))
    }
}
