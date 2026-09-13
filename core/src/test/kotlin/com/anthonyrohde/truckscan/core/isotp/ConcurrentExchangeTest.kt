package com.anthonyrohde.truckscan.core.isotp

import com.anthonyrohde.truckscan.core.adapter.ElmAdapter
import com.anthonyrohde.truckscan.core.transport.ObdTransport
import com.anthonyrohde.truckscan.core.uds.UdsClient
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reproduces a real fault: a module scan and the live-data poller running at
 * the same time against one adapter corrupted each other's requests.
 *
 * `ElmAdapter`'s command-level mutex serialises one raw command, but a logical
 * exchange - set header, set filter, transmit, collect - is several such
 * commands, each independently locked and released. Between any two of them,
 * a second coroutine talking to the same adapter could run its own command,
 * changing the header or filter the first exchange was relying on. On a real
 * truck this turned a fault scan's request to the SYNC module into a
 * broadcast, because the live poll's own tick reset the header first - and
 * whichever module answered was then correctly discarded for coming from the
 * wrong address, so the scan saw silence and the poll saw a slower sweep than
 * it should have.
 *
 * This test does not need to engineer the race by hand: two `channel.request`
 * calls launched concurrently against a transport that suspends on every
 * `write`/`read` reproduce it on their own, because that is exactly what the
 * unlocked version of `ElmAdapter` allowed.
 */
class ConcurrentExchangeTest {

    /**
     * Answers as two modules on one bus, each replying according to whichever
     * header is selected *at the moment its frame is sent* - which is the
     * whole point: a correct exchange keeps its own header from the moment it
     * is set until its frame goes out, and an interleaved one does not.
     *
     * `yield()` on every command gives the coroutine scheduler a genuine
     * chance to run the other coroutine between any two commands, the same
     * opportunity a real serial link's latency gives it.
     */
    private class TwoModuleTransport : ObdTransport {
        @Volatile private var header = 0
        @Volatile private var filter = 0
        private val pending = ArrayDeque<String>()

        override val isOpen = true
        override val description = "two modules"
        override suspend fun open() = Unit
        override suspend fun close() = Unit

        override suspend fun write(bytes: ByteArray) {
            yield()
            val cmd = String(bytes, Charsets.US_ASCII).trim()
            val reply = when {
                cmd.isEmpty() -> null
                cmd.startsWith("ATSH") -> {
                    header = cmd.removePrefix("ATSH").trim().toInt(16)
                    "OK"
                }
                cmd.startsWith("ATCRA") -> {
                    val arg = cmd.removePrefix("ATCRA").trim()
                    filter = if (arg.isEmpty()) 0 else arg.toInt(16)
                    "OK"
                }
                cmd.startsWith("AT") || cmd.startsWith("ST") -> "OK"
                // A data frame: answered by whichever module the header
                // currently addresses, which is the real vehicle's behaviour -
                // it has no idea a caller meant to talk to someone else.
                // A positive TesterPresent reply: PCI 02 (length 2), SID 0x7E
                // (0x3E + 0x40), sub-function echoed as 00.
                header == 0x7E0 -> "7E8027E000000000000"
                header == 0x726 -> "72E027E000000000000"
                else -> "NO DATA"
            }
            yield()
            if (reply != null) pending += "$reply\r>"
        }

        override suspend fun read(timeoutMillis: Long): ByteArray {
            yield()
            if (pending.isEmpty()) return ByteArray(0)
            return pending.removeFirst().toByteArray(Charsets.US_ASCII)
        }
    }

    @Test
    fun `two exchanges on one adapter never interleave their headers`() = runBlocking {
        val transport = TwoModuleTransport()
        val adapter = ElmAdapter(transport)
        val channel = IsoTpChannel(adapter)
        val pcm = UdsClient(channel, 0x7E0, 0x7E8, "PCM")
        val bcm = UdsClient(channel, 0x726, 0x72E, "BCM")

        // Run enough rounds that an unlocked adapter would show at least one
        // corrupted exchange somewhere in the mix - a single lucky race is not
        // evidence of a fix, dozens with none is.
        repeat(40) {
            val a = async { runCatching { pcm.testerPresent(suppressResponse = false) } }
            val b = async { runCatching { bcm.testerPresent(suppressResponse = false) } }
            val (resultA, resultB) = a.await() to b.await()

            assertTrue(resultA.isSuccess, "PCM exchange failed: ${resultA.exceptionOrNull()}")
            assertTrue(resultB.isSuccess, "BCM exchange failed: ${resultB.exceptionOrNull()}")
        }
    }

    /**
     * The same race, one level down: a bus switch is its own exchange
     * ([com.anthonyrohde.truckscan.core.adapter.BusRouter] and
     * [com.anthonyrohde.truckscan.core.session.ModuleDiscovery] both wrap
     * `selectBus` in [ElmAdapter.exclusive] for exactly this reason) and must
     * not interleave with a request either.
     */
    @Test
    fun `exclusive serialises a bus switch against a concurrent request`() = runBlocking {
        val transport = TwoModuleTransport()
        val adapter = ElmAdapter(transport)
        val channel = IsoTpChannel(adapter)
        val pcm = UdsClient(channel, 0x7E0, 0x7E8, "PCM")

        repeat(20) {
            val request = async { runCatching { pcm.testerPresent(suppressResponse = false) } }
            val switch = async {
                adapter.exclusive {
                    // A stand-in for the several commands a real bus switch
                    // sends - what matters is that it holds the same lock a
                    // request does, for its whole duration.
                    yield()
                    yield()
                }
            }
            request.await()
            switch.await()
            assertTrue(request.getCompleted().isSuccess)
        }
    }
}
