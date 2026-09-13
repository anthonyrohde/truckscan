package com.anthonyrohde.truckscan.core.probe

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Checks the investigations embedded in tools/probe.ps1 against the allowlist
 * this module defines.
 *
 * The PowerShell harness necessarily carries its own copy of the read-only rule,
 * because it runs on a laptop with none of this code available. Two copies of a
 * safety rule is how one of them quietly stops matching the other, and the copy
 * that drifts is the one nobody is testing. This does not test that copy - it
 * tests the scripts it ships, against the implementation that is tested, so a
 * command that should never reach a bus cannot be introduced there unnoticed.
 */
class ProbeShellScriptTest {

    private fun harness(): File? =
        listOf(File("../tools/probe.ps1"), File("tools/probe.ps1")).firstOrNull { it.isFile }

    /** Pulls the bodies out of the `Body = @' ... '@` here-strings. */
    private fun embeddedScripts(text: String): List<String> =
        Regex("""Body\s*=\s*@'\r?\n(.*?)\r?\n'@""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `every investigation in the powershell harness is read-only`() {
        val file = harness()
        assumeTrue(file != null, "tools/probe.ps1 not reachable from the test working directory")

        val scripts = embeddedScripts(file!!.readText())
        assertTrue(scripts.isNotEmpty(), "found no embedded scripts in ${file.path}")

        for ((index, script) in scripts.withIndex()) {
            val parsed = ProbeScript.parse(script)
            assertTrue(
                parsed.isSafe,
                "script #${index + 1} in probe.ps1 would send something refused: " +
                    parsed.refusals.joinToString { "${it.raw.trim()} (${it.reason})" },
            )
            assertTrue(parsed.willSend > 0, "script #${index + 1} sends nothing")
        }
    }

    /**
     * The harness must refuse the same services this module does. Comparing the
     * two lists by name catches the likeliest drift - a service added to one
     * side and forgotten on the other.
     */
    @Test
    fun `the harness refuses the services this module refuses`() {
        val file = harness()
        assumeTrue(file != null, "tools/probe.ps1 not reachable")

        val text = file!!.readText()
        for (service in listOf(0x10, 0x11, 0x14, 0x27, 0x28, 0x2E, 0x2F, 0x31, 0x34, 0x35, 0x36, 0x37, 0x85)) {
            val literal = "0x%02X".format(service)
            assertTrue(
                text.contains(literal),
                "probe.ps1 does not mention $literal, which this module refuses",
            )
        }
    }
}
