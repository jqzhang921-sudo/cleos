package com.cleo.cleos.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns the `data:` payloads of one streamed chat completion into events, and collects
 * the tool calls it makes along the way. Kept apart from the network for testing.
 *
 * Tool calls arrive in fragments: the first piece of each call carries its id and name,
 * the rest carry slices of the arguments' JSON, and `index` says which call a fragment
 * belongs to. Providers bend this. Some send a whole call in one piece, some send the
 * arguments as an object instead of a string, and some leave `index` out, in which case
 * a new id means a new call and a piece without an id continues the last one.
 */
internal class StreamParser {
    private val json = Json { ignoreUnknownKeys = true }

    private class Pending(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())

    private val calls = sortedMapOf<Int, Pending>()
    private val think = ThinkSplitter()
    private val structure = StreamStructure()

    fun diagnostic(): String = structure.diagnostic(calls.size)

    fun feed(data: String): List<ChatEvent> {
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
            ?: return emptyList<ChatEvent>().also { structure.invalidChunk() }
        structure.record(obj)
        obj["error"]?.let { throw ChatException("服务端报错：" + errorText(it)) }
        // A relay that ignores stream:true sends the whole reply as one JSON, under "message".
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val delta = (choice?.get("delta") ?: choice?.get("message")) as? JsonObject
            ?: return emptyList()
        val events = ArrayList<ChatEvent>(2)
        fun text(key: String) = (delta[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        // DeepSeek and most others say reasoning_content; OpenRouter says reasoning. Some
        // servers send both with the same words, so reasoning only counts on its own.
        val reasoning = text("reasoning_content")
        if (reasoning != null) {
            events += ChatEvent.Reasoning(reasoning)
        } else {
            text("reasoning")?.let { events += ChatEvent.Reasoning(it, sendBack = false) }
        }
        text("content")?.let { events += think.split(it) }
        (delta["tool_calls"] as? JsonArray)?.forEach { piece -> (piece as? JsonObject)?.let(::addPiece) }
        return events
    }

    /**
     * What came back when it wasn't a stream at all: no `data:` line in the whole answer. Some
     * relays then send the reply as one JSON (shown as it is), or an error as one (thrown, as it is
     * in a stream); others send nothing, or a web page. Silently dropping those left an empty chat
     * with no word on why, so what can't be read as a reply says what it was instead.
     *
     * Both failures are marked [ChatException.wrongEndpoint]: nothing that answers like this is
     * the chat endpoint, so it is worth one more try a level down (see [atRightAddress]).
     */
    fun wholeReply(body: String): List<ChatEvent> {
        val text = body.trim()
        if (text.isEmpty()) {
            throw ChatException(
                "连上了，但服务什么内容都没回。多半是中转站不支持流式、这个模型名没开通，或者额度有问题。",
                wrongEndpoint = true,
            )
        }
        val events = feed(text) + finish()
        if (events.isEmpty() && toolCalls().isEmpty()) {
            throw ChatException("服务回的不是聊天内容：" + text.take(120), wrongEndpoint = true)
        }
        return events
    }

    /** What was still held back when the stream ended. */
    fun finish(): List<ChatEvent> = think.finish()

    /** The calls, in order, once the stream is over. Pieces that never got a name are dropped. */
    fun toolCalls(): List<ToolCall> = calls.values
        .filter { it.name.isNotEmpty() }
        .mapIndexed { i, p -> ToolCall(p.id.ifEmpty { "call_$i" }, p.name, p.arguments.toString()) }

    private fun addPiece(piece: JsonObject) {
        val id = (piece["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val index = (piece["index"] as? JsonPrimitive)?.intOrNull
        val pending = when {
            index != null -> calls.getOrPut(index) { Pending() }
            id.isNotEmpty() -> calls.values.firstOrNull { it.id == id }
                ?: Pending().also { calls[(calls.keys.maxOrNull() ?: -1) + 1] = it }
            else -> calls.values.lastOrNull() ?: Pending().also { calls[0] = it }
        }
        if (pending.id.isEmpty() && id.isNotEmpty()) pending.id = id
        val function = piece["function"] as? JsonObject ?: return
        val name = (function["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (pending.name.isEmpty() && name.isNotEmpty()) pending.name = name
        when (val args = function["arguments"]) {
            is JsonObject -> pending.arguments.append(args.toString())
            is JsonPrimitive -> args.contentOrNull?.let { pending.arguments.append(it) }
            else -> Unit
        }
    }
}

/**
 * Some models, and relays in front of them, put the thinking into the reply itself: a
 * <think>…</think> (or <thinking>) block before the answer. Split off, it is thinking like any
 * other, shown folded instead of as the start of the bubble. Only a block the reply opens
 * with counts, and either tag can arrive cut across two pieces, so a little is held back
 * until it is clear what it is.
 */
internal class ThinkSplitter {
    private enum class Mode { Start, Inside, After, Plain }

    private var mode = Mode.Start
    private val held = StringBuilder()
    private var closer = ""

    fun split(text: String): List<ChatEvent> = when (mode) {
        Mode.Start -> start(text)
        Mode.Inside -> inside(text)
        Mode.After -> after(text)
        Mode.Plain -> listOf(ChatEvent.Delta(text))
    }

    /** At the end of the stream: a block never closed was all thinking; anything else held back is text. */
    fun finish(): List<ChatEvent> {
        val rest = held.toString()
        held.clear()
        val out = when {
            rest.isEmpty() -> emptyList()
            mode == Mode.Inside -> listOf(ChatEvent.Reasoning(rest, sendBack = false))
            mode == Mode.Start -> listOf(ChatEvent.Delta(rest))
            else -> emptyList()
        }
        mode = Mode.Plain
        return out
    }

    private fun start(text: String): List<ChatEvent> {
        held.append(text)
        val all = held.toString()
        val lead = all.trimStart()
        val tag = OPENERS.firstOrNull { lead.startsWith(it) }
        if (tag != null) {
            held.clear()
            mode = Mode.Inside
            closer = "</" + tag.substring(1)
            return inside(lead.substring(tag.length))
        }
        // Could still become a tag ("<thi"): wait for the next piece.
        if (OPENERS.any { it.startsWith(lead) }) return emptyList()
        held.clear()
        mode = Mode.Plain
        return listOf(ChatEvent.Delta(all))
    }

    private fun inside(text: String): List<ChatEvent> {
        held.append(text)
        val all = held.toString()
        held.clear()
        val end = all.indexOf(closer)
        if (end >= 0) {
            mode = Mode.After
            val thought = all.substring(0, end)
            return listOfNotNull(thought.takeIf { it.isNotEmpty() }?.let { ChatEvent.Reasoning(it, sendBack = false) }) +
                after(all.substring(end + closer.length))
        }
        // The end may be the closing tag's first letters: keep those for the next piece.
        val keep = (minOf(closer.length - 1, all.length) downTo 1).firstOrNull { all.endsWith(closer.substring(0, it)) } ?: 0
        held.append(all, all.length - keep, all.length)
        val thought = all.substring(0, all.length - keep)
        return listOfNotNull(thought.takeIf { it.isNotEmpty() }?.let { ChatEvent.Reasoning(it, sendBack = false) })
    }

    /** Right after the block: the blank lines before the answer go. */
    private fun after(text: String): List<ChatEvent> {
        val t = text.trimStart()
        if (t.isEmpty()) return emptyList()
        mode = Mode.Plain
        return listOf(ChatEvent.Delta(t))
    }

    private companion object {
        val OPENERS = listOf("<think>", "<thinking>")
    }
}

internal fun errorText(e: JsonElement): String =
    (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString()
