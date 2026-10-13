package com.cleo.cleos.diagnostics

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.AccessDeniedException

/** Only callers' structural metadata, never request/response bodies or exception prose. */
internal class ToolDiagnosticStore(
    private val directory: File,
    private val version: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limit: Int = 128 * 1024,
    private val onWriteFailure: (String) -> Unit = {},
) {
    private val session = UUID.randomUUID().toString().take(8)
    private val recent get() = File(directory, "recent.txt")
    private val issue get() = File(directory, "issue.txt")

    @Synchronized fun record(metadata: String) {
        runCatching {
            val safe = metadata.split(' ').mapNotNull { token ->
                val key = token.substringBefore('=')
                val value = token.substringAfter('=', "")
                if (key in FIELDS && value.length <= 2048 && value.matches(VALUE)) "$key=$value" else null
            }.joinToString(" ")
            if (safe.isBlank()) return
            directory.mkdirs()
            val line = "${Instant.ofEpochMilli(clock())} build=${label(version)} session=$session $safe\n"
            val text = tail(read(recent) + line, limit)
            replace(recent, text)
            // Pin the first failure until explicitly cleared: normal use cannot overwrite it.
            if (!issue.exists() && isIssue(safe)) {
                replace(issue, tail(text, minOf(limit, 64 * 1024)))
            } else if (issue.exists()) {
                val pinned = read(issue)
                val id = requestId(safe)
                // Include that request's retry/response, while unrelated later requests leave it intact.
                if (id != null && id == requestId(pinned)) replace(issue, tail(pinned + line, minOf(limit, 64 * 1024)))
            }
        }.onFailure { runCatching { onWriteFailure(it.javaClass.simpleName) } }
    }

    @Synchronized fun recent(): String = runCatching { read(recent) }.getOrDefault("")
    @Synchronized fun issue(): String = runCatching { read(issue) }.getOrDefault("")
    @Synchronized fun pin() { runCatching { directory.mkdirs(); recent().takeIf { it.isNotBlank() }?.let { replace(issue, tail(it, minOf(limit, 64 * 1024))) } } }
    @Synchronized fun clear() { runCatching { recent.delete(); issue.delete() } }

    private fun read(file: File): String = if (file.exists()) file.readText() else ""
    private fun replace(file: File, text: String) {
        val pending = File(directory, file.name + ".tmp")
        pending.writeText(text)
        retryDiagnosticMove {
            try {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
    private fun tail(text: String, max: Int): String {
        if (text.length <= max) return text
        // Whole lines only; all stored metadata is ASCII apart from neither header nor prose.
        val cut = text.indexOf('\n', text.length - max)
        return if (cut < 0) "" else text.substring(cut + 1)
    }

    companion object {
        private val VALUE = Regex("[A-Za-z0-9_./,\\[\\]-]*")
        private val FIELDS = setOf("requestId", "route", "model", "thinking", "toolsPresent", "tools", "send_message",
            "mcp", "names", "assistants", "reasoningAbsent", "status", "category", "type", "code", "param", "schemaRetry",
            "event", "calls", "streamed", "server", "cache", "enabled", "failed", "reason", "rejectedTool",
            "chunks", "invalidChunks", "rootKeys", "deltaKeys", "messageKeys", "toolChunks", "namedToolChunks",
            "nonFirstToolChunks", "otherToolShapes", "legacyCalls", "pendingCalls", "finish", "contentChars", "reasoningChars")
        private fun isIssue(line: String): Boolean =
            Regex("(?:^| )status=[45][0-9]{2}(?: |$)").containsMatchIn(line) ||
                line.split(' ').any { it in setOf("event=mcp_list_failed", "event=stream_failure", "event=feature_fallback", "event=mcp_call_failed", "event=photo_search_failed", "event=photo_share_failed", "event=photo_test_failed") }
        private fun requestId(line: String): String? = Regex("(?:^| )requestId=([A-Za-z0-9-]+)").findAll(line).lastOrNull()?.groupValues?.get(1)

        fun label(value: String): String = value.takeIf { it.length <= 100 && it.matches(Regex("[A-Za-z0-9_./\\[\\]-]+")) } ?: "[redacted]"
        /** Correlates routes/services without retaining URLs, query tokens, names or keys. */
        fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}


// A short sharing violation must not discard a diagnostic line. Persistent failures stay nonfatal.
internal fun retryDiagnosticMove(pause: (Long) -> Unit = { Thread.sleep(it) }, move: () -> Unit) {
    repeat(5) { attempt ->
        try { move(); return } catch (e: AccessDeniedException) {
            if (attempt == 4) throw e
            try { pause(5L * (attempt + 1)) } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
    }
}
