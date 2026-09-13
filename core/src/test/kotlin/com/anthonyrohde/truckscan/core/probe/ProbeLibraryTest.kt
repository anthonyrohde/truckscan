package com.anthonyrohde.truckscan.core.probe

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProbeLibraryTest {

    /**
     * The safety promise is only worth anything if the scripts shipped with the
     * app keep it. Checking every built-in against the same allowlist the user's
     * own scripts go through means a careless edit here fails the build rather
     * than reaching a bus.
     */
    @Test
    fun `every built-in investigation is read-only`() {
        for (investigation in ProbeLibrary.ALL) {
            val parsed = ProbeScript.parse(investigation.script)
            assertTrue(
                parsed.isSafe,
                "'${investigation.name}' would send something refused: " +
                    parsed.refusals.joinToString { "${it.raw.trim()} (${it.reason})" },
            )
            assertTrue(parsed.willSend > 0, "'${investigation.name}' sends nothing")
        }
    }

    @Test
    fun `each investigation says what it would settle`() {
        for (investigation in ProbeLibrary.ALL) {
            assertTrue(investigation.question.length > 80, investigation.name)
            assertTrue(investigation.name.isNotBlank())
        }
    }
}
