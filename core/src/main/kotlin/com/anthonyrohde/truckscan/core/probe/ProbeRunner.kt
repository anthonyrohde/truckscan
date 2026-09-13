package com.anthonyrohde.truckscan.core.probe

import com.anthonyrohde.truckscan.core.adapter.ElmAdapter

/** One command and what came back. */
data class ProbeStep(
    val command: String,
    val note: String,
    val replies: List<String>,
    val elapsedMillis: Long,
    val sent: Boolean,
    val refusalReason: String? = null,
)

/**
 * Runs a checked script against the adapter and records the exchange.
 *
 * Deliberately thin. It sends what [ProbeScript] permitted, verbatim, and
 * records exactly what came back - no retries, no interpretation, no tidying.
 * The point of running a probe is to find out what the hardware really does,
 * and a runner that smooths over an odd reply destroys the only evidence worth
 * having.
 */
class ProbeRunner(
    private val adapter: ElmAdapter,
    private val timeoutMillis: Long = 2_000,
) {
    suspend fun run(
        parsed: ProbeScript.Parsed,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<ProbeStep> {
        val steps = mutableListOf<ProbeStep>()
        val total = parsed.lines.count { it !is ProbeScript.Line.Note }
        var done = 0

        for (line in parsed.lines) {
            when (line) {
                is ProbeScript.Line.Note -> Unit

                is ProbeScript.Line.Refused -> {
                    steps += ProbeStep(
                        command = line.raw.trim(),
                        note = "refused",
                        replies = emptyList(),
                        elapsedMillis = 0,
                        sent = false,
                        refusalReason = line.reason,
                    )
                    onProgress?.invoke(++done, total)
                }

                is ProbeScript.Line.Adapter -> {
                    steps += send(line.command, "adapter command")
                    onProgress?.invoke(++done, total)
                }

                is ProbeScript.Line.Frame -> {
                    steps += send(line.raw.substringBefore('#').trim(), line.description)
                    onProgress?.invoke(++done, total)
                }
            }
        }
        return steps
    }

    private suspend fun send(command: String, note: String): ProbeStep {
        val startedAt = System.currentTimeMillis()
        val response = runCatching { adapter.command(command, timeoutMillis) }
        val elapsed = System.currentTimeMillis() - startedAt

        return response.fold(
            onSuccess = { reply ->
                ProbeStep(command, note, reply.lines, elapsed, sent = true)
            },
            onFailure = { error ->
                // A failure is a result too, and is recorded as one rather than
                // stopping the script: the command after it is often the one
                // that explains the command before.
                ProbeStep(
                    command = command,
                    note = note,
                    replies = listOf("ERROR: ${error.message}"),
                    elapsedMillis = elapsed,
                    sent = true,
                )
            },
        )
    }

    companion object {
        /** Plain-text transcript, for writing out and reading later. */
        fun transcript(title: String, steps: List<ProbeStep>): String = buildString {
            appendLine(title)
            appendLine("=".repeat(title.length))
            appendLine()
            for (step in steps) {
                if (!step.sent) {
                    appendLine("-- ${step.command}")
                    appendLine("   NOT SENT: ${step.refusalReason}")
                    appendLine()
                    continue
                }
                appendLine(">> ${step.command}    (${step.note}, ${step.elapsedMillis} ms)")
                if (step.replies.isEmpty()) {
                    appendLine("   (no reply)")
                } else {
                    step.replies.forEach { appendLine("   $it") }
                }
                appendLine()
            }
        }
    }
}
