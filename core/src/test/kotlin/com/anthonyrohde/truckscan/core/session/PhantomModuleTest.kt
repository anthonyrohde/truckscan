package com.anthonyrohde.truckscan.core.session

import com.anthonyrohde.truckscan.core.adapter.CanBus
import com.anthonyrohde.truckscan.core.adapter.ElmAdapter
import com.anthonyrohde.truckscan.core.isotp.IsoTpChannel
import com.anthonyrohde.truckscan.core.transport.ObdTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A scan on a real truck reported 52 modules at consecutive addresses -
 * 755, 756, 757, 758 and on - none of which exist, and every one of which then
 * failed to read. The addresses being consecutive is the clue: that is not a
 * vehicle, it is a scan that has fallen one behind and is showing each request
 * the previous request's answer.
 *
 * The mechanism was in this module. `readUntilPrompt` checks the buffer for a
 * prompt BEFORE reading the port, and `writeLine` did not clear it, so a reply
 * that arrived after its own exchange had returned was handed straight back as
 * the answer to the next command - instantly, without a single byte being read.
 * One late reply from one real module put everything after it one behind, for
 * the rest of the scan.
 */
class PhantomModuleTest {

    /**
     * A vehicle where one module answers late: its reply lands in the buffer
     * only after the request that asked for it has already timed out.
     */
    private class LateReplyTruck : ObdTransport {
        val written = mutableListOf<String>()
        private val pending = ArrayDeque<String>()
        private var owed: String? = null

        override val isOpen = true
        override val description = "late replier"
        override suspend fun open() = Unit
        override suspend fun close() = Unit

        override suspend fun write(bytes: ByteArray) {
            val cmd = String(bytes, Charsets.US_ASCII).trim()
            written += cmd
            when {
                cmd.isEmpty() -> return
                cmd.startsWith("AT") || cmd.startsWith("ST") -> pending += "OK\r>"
                // TesterPresent. The first one is answered too late to be read
                // by the request that sent it; everything after gets nothing.
                cmd.startsWith("023E") -> {
                    if (owed == null) {
                        owed = "75C027E000000000000\r>"
                        pending += "NO DATA\r>"
                    } else {
                        pending += "NO DATA\r>"
                    }
                }
                else -> pending += "NO DATA\r>"
            }
        }

        override suspend fun read(timeoutMillis: Long): ByteArray {
            // The late reply turns up during the next quiet moment, which is
            // how it ends up in the buffer with nobody waiting for it.
            owed?.let { late ->
                owed = null
                pending.addFirst(late)
            }
            if (pending.isEmpty()) return ByteArray(0)
            return pending.removeFirst().toByteArray(Charsets.US_ASCII)
        }
    }

    /**
     * The heart of it: a reply that arrives late must never be handed to the
     * next request. If it is, every address after the late one reports a module
     * that is not there.
     */
    @Test
    fun `a late reply is not handed to the next request`() = runBlocking {
        val truck = LateReplyTruck()
        val adapter = ElmAdapter(truck)
        val channel = IsoTpChannel(adapter)
        val discovery = ModuleDiscovery(adapter, channel)

        val found = discovery.scanBus(
            bus = CanBus.HS_CAN1,
            addresses = listOf(0x754, 0x755, 0x756, 0x757, 0x758),
            probeTimeoutMillis = 30,
            readIdentification = false,
        )

        // 0x754 answering is allowed: the reply carries 75C, which is that
        // address's own response identifier, so attributing it there is right
        // even though it was slow. What must not happen is the addresses after
        // it reporting modules, because the only thing they could be shown is
        // somebody else's answer.
        val phantom = found.filter { it.module.requestId > 0x754 }
        assertTrue(
            phantom.isEmpty(),
            "addresses after the slow module reported modules that cannot exist: " +
                phantom.joinToString { it.module.addressLabel },
        )
    }

    /** A bus that answers nothing must produce nothing. */
    @Test
    fun `silence finds no modules`() = runBlocking {
        val silent = object : ObdTransport {
            private val pending = ArrayDeque<String>()
            override val isOpen = true
            override val description = "silent"
            override suspend fun open() = Unit
            override suspend fun close() = Unit
            override suspend fun write(bytes: ByteArray) {
                val cmd = String(bytes, Charsets.US_ASCII).trim()
                if (cmd.isEmpty()) return
                pending += if (cmd.startsWith("AT") || cmd.startsWith("ST")) "OK\r>" else "NO DATA\r>"
            }
            override suspend fun read(timeoutMillis: Long): ByteArray {
                if (pending.isEmpty()) return ByteArray(0)
                return pending.removeFirst().toByteArray(Charsets.US_ASCII)
            }
        }
        val adapter = ElmAdapter(silent)
        val found = ModuleDiscovery(adapter, IsoTpChannel(adapter)).scanBus(
            bus = CanBus.HS_CAN1,
            addresses = (0x750..0x760).toList(),
            probeTimeoutMillis = 20,
            readIdentification = false,
        )
        assertEquals(emptyList<DiscoveredModule>(), found)
    }

    /** A bus that errors on every request must produce nothing either. */
    @Test
    fun `a bus error finds no modules`() = runBlocking {
        val broken = object : ObdTransport {
            private val pending = ArrayDeque<String>()
            override val isOpen = true
            override val description = "can error"
            override suspend fun open() = Unit
            override suspend fun close() = Unit
            override suspend fun write(bytes: ByteArray) {
                val cmd = String(bytes, Charsets.US_ASCII).trim()
                if (cmd.isEmpty()) return
                pending += if (cmd.startsWith("AT") || cmd.startsWith("ST")) "OK\r>" else "CAN ERROR\r>"
            }
            override suspend fun read(timeoutMillis: Long): ByteArray {
                if (pending.isEmpty()) return ByteArray(0)
                return pending.removeFirst().toByteArray(Charsets.US_ASCII)
            }
        }
        val adapter = ElmAdapter(broken)
        val found = ModuleDiscovery(adapter, IsoTpChannel(adapter)).scanBus(
            bus = CanBus.HS_CAN1,
            addresses = (0x750..0x760).toList(),
            probeTimeoutMillis = 20,
            readIdentification = false,
        )
        assertEquals(emptyList<DiscoveredModule>(), found)
    }

    /**
     * A quick scan must ask every known address, not only the ones the profile
     * associates with the bus being scanned.
     *
     * Measured on a 2022 F-250: the parking aid module (736) and the SYNC module
     * (7D0) answer on HS-CAN1, through the gateway, while the profile documents
     * them as MS-CAN and HS-CAN2. Filtering by the profile meant a quick scan of
     * HS-CAN1 never asked, so they were unfindable on the only bus that reaches
     * them - and the scan reported two modules where the bus has five.
     */
    @Test
    fun `a quick scan asks every known address regardless of the profile's bus`() = runBlocking {
        val asked = mutableListOf<Int>()
        val transport = object : ObdTransport {
            private val pending = ArrayDeque<String>()
            override val isOpen = true
            override val description = "address recorder"
            override suspend fun open() = Unit
            override suspend fun close() = Unit
            override suspend fun write(bytes: ByteArray) {
                val cmd = String(bytes, Charsets.US_ASCII).trim()
                if (cmd.isEmpty()) return
                if (cmd.startsWith("ATSH")) {
                    cmd.removePrefix("ATSH").trim().toIntOrNull(16)?.let { asked += it }
                }
                pending += if (cmd.startsWith("AT") || cmd.startsWith("ST")) "OK\r>" else "NO DATA\r>"
            }
            override suspend fun read(timeoutMillis: Long): ByteArray {
                if (pending.isEmpty()) return ByteArray(0)
                return pending.removeFirst().toByteArray(Charsets.US_ASCII)
            }
        }
        val adapter = ElmAdapter(transport)
        ModuleDiscovery(adapter, IsoTpChannel(adapter)).quickScan(CanBus.HS_CAN1)

        for (address in listOf(0x7E0, 0x7E1, 0x726, 0x736, 0x7D0)) {
            assertTrue(
                address in asked,
                "%03X answers on this truck's powertrain bus and was never asked".format(address),
            )
        }
    }
}
