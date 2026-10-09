package com.cleo.cleos.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

data class ApiMessage(
    val role: String,
    val content: String,
    /** An assistant turn that called tools. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** A tool result: the call it answers. */
    val toolCallId: String? = null,
    /** Reasoning handed back within the turn that produced it (see MessageEntity.reasoning). */
    val reasoning: String? = null,
    /**
     * Pictures the person sent: ImageStore names as Prompt builds the message, turned into
     * data: URLs just before sending.
     */
    val images: List<String> = emptyList(),
    /** An assistant message that went as a voice message: Prompt gives it back as the send_voice it was. Not sent. */
    val spoken: Boolean = false,
    /** An assistant message that quoted one of the person's: the words, for the send_message it was. Not sent. */
    val quoted: String? = null,
)

/** Where to send a conversation. [baseUrl] may or may not already end in /chat/completions. */
data class ApiEndpoint(val baseUrl: String, val apiKey: String, val model: String) {
    /** The address as written, with the one trailing thing people leave on taken off. */
    private val base: String get() = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")

    val chatUrl: String
        get() = "$base/chat/completions"
    val modelsUrl: String
        get() = "$base/models"

    /**
     * The same endpoint one level down, for an address typed without the version segment it
     * hangs off — `https://host` for what is really `https://host/v1`. Null when the last
     * segment already is one (…/v1, …/v4, …/v1beta): there is nothing to put back.
     *
     * Addresses come in every shape there is. OpenAI is /v1, DeepSeek is bare,
     * 智谱 is /api/paas/v4, and a relay's API host is not the host its front page is on —
     * so this is only ever a second guess, made after the address as typed has failed.
     */
    val withV1: ApiEndpoint?
        get() {
            // A box someone is still filling in: no host, so no path to hang anything under.
            if (!base.contains("://")) return null
            val path = base.substringAfter("://", "").substringAfter('/', "")
            if (VERSION.matches(path.trimEnd('/').substringAfterLast('/'))) return null
            return copy(baseUrl = "$base/v1")
        }

    private companion object {
        val VERSION = Regex("v\\d+[a-z]*", RegexOption.IGNORE_CASE)
    }
}

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent

    /**
     * The model thinking before it answers, shown to the person folded above the reply.
     * [sendBack]: it came as reasoning_content, which the provider wants back while the same
     * turn is still calling tools; thinking found anywhere else is only shown.
     */
    data class Reasoning(val text: String, val sendBack: Boolean = true) : ChatEvent

    /** The tool calls the reply ended with. Sent once, after everything else. */
    data class ToolCalls(val calls: List<ToolCall>) : ChatEvent
}

/**
 * A failure whose message is already worded for the person using the app, and that knows whether
 * what went wrong was the address itself — the one kind of failure worth trying again a level
 * down (see [atRightAddress]). The chat's [ChatException] and the voice's `SpeechException` are
 * both these: a hand-filled address can be missing its version segment wherever it is used, so
 * `/audio/speech` and `/audio/transcriptions` need the same second guess the chat makes.
 */
interface EndpointFailure {
    /**
     * This address is not this service at all: a web page came back, or there is nothing at
     * that path. A refused key, an empty balance, a 500 are not this — they come from an
     * endpoint that was found, and moving the path under it would hide the real problem.
     */
    val wrongEndpoint: Boolean

    /**
     * The service itself answered, in words it wrote ("no such model: …"), rather than the app
     * finding a web page where an API should be. The two kinds of wrongness are not worth the
     * same: a page says nothing about what is really wrong, so where both addresses above have
     * failed it is the one that got words out of a service that is reported.
     */
    val serviceSpoke: Boolean get() = false
}

/** A failure with a message already worded for the person using the app. */
class ChatException(
    message: String,
    val status: Int? = null,
    override val wrongEndpoint: Boolean = false,
    override val serviceSpoke: Boolean = false,
    val rejectedFeature: RequestFeature? = null,
) : Exception(message), EndpointFailure

/** Whether [code] is one of the two that say the path itself is not there. */
internal fun noSuchPath(code: Int): Boolean = code == 404 || code == 405

/**
 * Calls [attempt] with the address as it was typed, and — only when what failed is the
 * address itself — once more with `/v1` put back under it.
 *
 * People copy an address out of a browser, and what they copy is the site, not the API: a
 * relay's front page answers *every* path, including `POST /chat/completions`, with its own
 * HTML under HTTP 200, so a missing `/v1` arrives looking like a service that is up and
 * speaking nonsense. 智谱 is `/api/paas/v4` and DeepSeek is bare, so this can only ever be a
 * second guess made after the address as typed has already failed.
 *
 * Both failing is not the same as both failures being worth reading: see
 * [EndpointFailure.serviceSpoke] for which one gets reported then.
 *
 * The address that worked is remembered in [remembered] (`base as typed` → `base to use`),
 * so the price is one wasted round trip for the first message, not for every message. It is
 * deliberately not written back to the stored address: the address is also what the key is
 * filed under (see `SecretStore.addressOf`), and moving it would lose the key.
 */
internal suspend fun <T> atRightAddress(
    endpoint: ApiEndpoint,
    remembered: MutableMap<String, String> = AddressMemory.learned,
    attempt: suspend (ApiEndpoint) -> T,
): T {
    val key = baseAsTyped(endpoint)
    remembered[key]?.let { return attempt(endpoint.copy(baseUrl = it)) }
    try {
        return attempt(endpoint)
    } catch (first: Exception) {
        val under = endpoint.withV1
        if (under == null || !isWrongEndpoint(first)) throw first
        try {
            val done = attempt(under)
            remembered[key] = under.baseUrl
            return done
        } catch (second: Exception) {
            // A rejected key, an empty balance: /v1 is the endpoint after all — not an address
            // that might move — so later requests go straight there.
            if (!isWrongEndpoint(second)) remembered[key] = under.baseUrl
            // Either way it is /v1's failure that is reported, it being the one that got
            // closer: a service complaining there about the model says something the front
            // page above it never did. When both are only pages the wording is the same, and
            // when the address typed was the one that spoke, that is the one kept.
            if (spokeAsService(second) || !spokeAsService(first)) throw second
            throw first
        }
    }
}

/**
 * Whether [e] is one of the failures that says the address is what is wrong. Anything else —
 * a cancelled request, a bug — is none of this function's business and is thrown straight
 * back out.
 */
private fun isWrongEndpoint(e: Exception): Boolean = e is EndpointFailure && e.wrongEndpoint

/** Whether [e] is a service answering in its own words, and not a page answering for one. */
private fun spokeAsService(e: Exception): Boolean = e is EndpointFailure && e.serviceSpoke

private fun baseAsTyped(endpoint: ApiEndpoint): String = endpoint.baseUrl.trim().trimEnd('/')

/**
 * What this run of the app has learned about addresses: which ones turned out to live one
 * level down (see [atRightAddress]), keyed by the address as the person typed it. One map for
 * the whole app, so a chat message working it out spares the voice message after it the same
 * wasted round trip — every one of them sends to the addresses on the same settings screen.
 *
 * Held for the run only, and never written over a stored address: the address is also what a
 * key is filed under (see `SecretStore.addressOf`), and moving it would lose the key.
 */
internal object AddressMemory {
    val learned = ConcurrentHashMap<String, String>()
}

/**
 * OpenAI-compatible chat completions with streaming (DeepSeek, OpenAI, SiliconFlow,
 * Moonshot, OpenRouter… all speak it).
 *
 * The stream is read line by line from OkHttp's buffered source. A server-sent event can
 * arrive split across two network chunks; splitting each chunk on newlines by hand drops
 * both halves of such a line, which shows up as characters missing from the middle of
 * long replies. readUtf8Line() waits for the whole line.
 */
class ChatClient(
    private val http: OkHttpClient,
    private val diagnostic: (String) -> Unit = { Log.i("CleosTools", it) },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Endpoints (address and model) that turned down being told not to think, in this run of the
     * app. From then on they aren't told: thinking or not is up to them.
     */
    private val refusesOff = ConcurrentHashMap.newKeySet<String>()

    /**
     * [thinking]: ask the model to think first (see [requestBody]). Without it, a model that thinks
     * unless told otherwise ([Thinking.canSwitchOff]) is told not to: the wait and the tokens are
     * for nothing then. One that can't stop thinking ([Thinking.thinksAlways]) is told to think
     * little. One that turns either down is asked again plainly, at once.
     */
    fun stream(
        endpoint: ApiEndpoint,
        messages: List<ApiMessage>,
        tools: List<ToolSpec> = emptyList(),
        thinking: Boolean = false,
    ): Flow<ChatEvent> = callbackFlow {
        val key = endpoint.chatUrl + "|" + endpoint.model
        val always = Thinking.thinksAlways(endpoint.model)
        fun request(to: ApiEndpoint, off: Boolean): Call {
            val body = requestBody(
                to.model, messages, tools,
                thinking = if (thinking) true else if (off && !always) false else null,
                effort = if (off && always) Thinking.LITTLE else null,
            )
            diagnostic(ChatCompatibility.diagnostic(body))
            return http.newCall(
            Request.Builder()
                .url(to.chatUrl)
                .header("Authorization", "Bearer ${to.apiKey}")
                .header("Accept", "text/event-stream")
                .post(
                    body.toString().toRequestBody(JSON_TYPE),
                )
                .build(),
        )
        }
        val off = !thinking && (always || Thinking.canSwitchOff(endpoint.model)) && key !in refusesOff
        var call: Call? = null

        launch(Dispatchers.IO) {
            // One whole request and the reading of its answer. Nothing has been sent when this
            // throws, which is what lets atRightAddress run it a second time at another address.
            suspend fun sendFrom(to: ApiEndpoint, off: Boolean) {
                if (!isActive) return
                fun execute(disableThinking: Boolean): okhttp3.Response {
                    val c = request(to, disableThinking)
                    call = c
                    val answer = c.execute()
                    if (!answer.isSuccessful) {
                        answer.use {
                            val body = it.body.string()
                            val sent = requestBody(to.model, messages, tools,
                                thinking = if (thinking) true else if (disableThinking && !always) false else null,
                                effort = if (disableThinking && always) Thinking.LITTLE else null)
                            diagnostic(ChatCompatibility.diagnostic(sent, it.code, body))
                            throw httpFailure(it.code, body)
                        }
                    }
                    return answer
                }
                val answer = try {
                    execute(off)
                } catch (e: ChatException) {
                    if (!off || e.status !in REFUSED || e.rejectedFeature != RequestFeature.Thinking) throw e
                    refusesOff += key
                    execute(disableThinking = false)
                }
                answer.use { response ->
                    val source = response.body.source()
                    val parser = StreamParser()
                    // What isn't a `data:` line is kept (a little of it): if the answer turns out not to be a stream at all, it is the answer.
                    val stray = StringBuilder()
                    var streamed = false
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) {
                            if (stray.length < STRAY_MAX) stray.append(line).append('\n')
                            continue
                        }
                        streamed = true
                        val data = line.substring(5).trim()
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") break
                        for (event in parser.feed(data)) send(event)
                    }
                    if (!streamed) for (event in parser.wholeReply(stray.toString())) send(event)
                    for (event in parser.finish()) send(event)
                    parser.toolCalls().takeIf { it.isNotEmpty() }?.let { send(ChatEvent.ToolCalls(it)) }
                }
            }
            try {
                atRightAddress(endpoint) { to -> sendFrom(to, off) }
                close()
            } catch (e: ChatException) {
                close(e)
            } catch (e: IOException) {
                close(if (call?.isCanceled() == true) e else ChatException(describeNetworkError(e)))
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { call?.cancel() }
    }

    /**
     * Asks for a single token, the same way the chat does. **This is the connection test.**
     *
     * Listing models used to be the test, and that was wrong twice over: a service can chat
     * perfectly well and keep no list at all, and — worse — a wrong address can answer the
     * list politely while being unable to chat. Only the road actually travelled proves
     * anything, so this sends one real message down it.
     *
     * ## ⚠️ 2xx is not success here
     *
     * 智谱's older `/api/paas/v1` and `/v3` answer **HTTP 200** to anything, including a
     * request with no key at all, with `{"code":1001,"msg":"…"}` in the body. An endpoint
     * like that passes every check that only looks at the status line. A reply with no
     * `choices` is a failure however cheerful the status code, and whatever the service
     * said in the body is worth repeating — it is usually the real answer.
     */
    suspend fun probe(endpoint: ApiEndpoint) = withContext(Dispatchers.IO) {
        atRightAddress(endpoint) { to ->
            val request = Request.Builder()
                .url(to.chatUrl)
                .header("Authorization", "Bearer ${to.apiKey}")
                .post(probeBody(to.model).toString().toRequestBody(JSON_TYPE))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) throw httpFailure(response.code, text)
                    // Not JSON at all: a web page, which says nothing about what is wrong.
                    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                        ?: throw ChatException("地址能连上，但回的不是 JSON，多半不是聊天接口。", wrongEndpoint = true)
                    if (root["choices"] == null) {
                        // JSON, but no reply in it: this is a service, and what it said about
                        // the model or the key ("no such model") is worth more than the page
                        // that answered at the address typed (see EndpointFailure.serviceSpoke).
                        throw ChatException(notAChatReply(root), wrongEndpoint = true, serviceSpoke = saidBy(root) != null)
                    }
                }
            } catch (e: IOException) {
                throw ChatException(describeNetworkError(e))
            }
        }
    }

    /**
     * Lists model ids the endpoint serves, to fill the picker.
     *
     * **Not the connection test** — that is [probe]. Plenty of services chat fine and serve
     * no list; failing here means only that there is nothing to show.
     */
    suspend fun models(endpoint: ApiEndpoint): List<String> = withContext(Dispatchers.IO) {
        atRightAddress(endpoint) { to ->
            val request = Request.Builder()
                .url(to.modelsUrl)
                .header("Authorization", "Bearer ${to.apiKey}")
                .get()
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) throw httpFailure(response.code, text)
                    // A web page where a list should be is the address's fault like any other,
                    // and the list is behind /v1 for exactly the same relays the chat is —
                    // which matters more here than elsewhere: the names in that list are the
                    // only way to know what a service calls its models.
                    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                        ?: throw ChatException("地址能连上，但回的不是 JSON，多半不是聊天接口。", wrongEndpoint = true)
                    root["data"]?.jsonArray
                        ?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
                        ?.sorted()
                        // No "check whether it is /v1": 智谱 is /v4 and DeepSeek is /v1, so that
                        // advice was wrong half the time — and following it landed people on an
                        // address that answers 200 to everything, where the real fault (a bad
                        // key) could no longer be reported. Say what is missing, nothing more.
                        ?: throw ChatException("这个地址没有给出模型列表。有的服务就是不提供，模型名直接填也能用。")
                }
            } catch (e: IOException) {
                throw ChatException(describeNetworkError(e))
            }
        }
    }

    /**
     * A non-2xx answer, worded for the person. 404 and 405 are the two that say the path
     * itself is not there, which is what [atRightAddress] is allowed to answer with a second try.
     */
    private fun httpFailure(code: Int, body: String) = ChatException(
        describeHttpError(code, body),
        code,
        wrongEndpoint = noSuchPath(code),
        // An error code with a complaint written into it is a service that was found
        // ("no such model: …"); the same code with a page for a body is not.
        serviceSpoke = complainedIn(body),
        rejectedFeature = ChatCompatibility.rejectedFeature(body),
    )

    /** Whether [body] is JSON with the service's own complaint in it. */
    private fun complainedIn(body: String): Boolean =
        runCatching { errorText(json.parseToJsonElement(body).jsonObject["error"]!!) }.getOrNull()?.isNotBlank() == true

    private fun describeHttpError(code: Int, body: String): String {
        val detail = runCatching { errorText(json.parseToJsonElement(body).jsonObject["error"]!!) }
            .getOrNull()
            ?: body.take(160)
        val hint = when (code) {
            401, 403 -> "API Key 不对，或者已经失效了"
            402 -> "账户余额不足"
            404 -> "地址或模型名不对（404）"
            429 -> "请求太频繁，或者额度用完了"
            in 500..599 -> "服务那边出错了（$code），过一会儿再试"
            else -> "请求失败（$code）"
        }
        return if (detail.isBlank()) hint else "$hint\n$detail"
    }

    private fun describeNetworkError(e: IOException): String = when {
        e is UnknownHostException -> "找不到服务器：检查地址，或者网络是不是断了"
        e is SocketTimeoutException -> "等太久没有回应，网络可能不太好"
        // Android refuses plain http by default, so the key would never go out unencrypted.
        e.message?.contains("CLEARTEXT", ignoreCase = true) == true -> "只支持 https:// 开头的地址"
        else -> "网络出错：${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /** How endpoints turn down a field they don't take: 400, 404 (OpenRouter), 422. */
        val REFUSED = setOf(400, 404, 422)

        /** How much of an answer that isn't a stream is kept to be read as one (characters). */
        const val STRAY_MAX = 200_000
    }
}

/**
 * Models that think unless told not to, and take being told: DeepSeek V4 (DeepSeek's own
 * OpenClaw plugin sends the same switch) and GLM from 4.5 on. Asked plainly they would think
 * anyway, so a TA with thinking off would still keep the person waiting and cost the tokens.
 */
object Thinking {
    private val SWITCHABLE = listOf("deepseek-flash", "deepseek-v4", "glm-4.5", "glm-4.6", "glm-4.7", "glm-5")

    /** DeepSeek's thinking tool requests require reasoning from earlier turns too. */
    fun keepsToolReasoning(model: String): Boolean = name(model).let { it == "deepseek-flash" || it.startsWith("deepseek-v4") }

    /**
     * Models that always think: told not to, they fail the request (GLM from 5.3 on: its docs say
     * thinking.type only takes enabled). Asked plainly they think as hard as they can, which is
     * the longest wait there is; they take being asked to think little ([LITTLE]).
     */
    private val ALWAYS = listOf("glm-5.3")

    /** reasoning_effort for a model that can't stop thinking, when the TA isn't to think. */
    const val LITTLE = "low"

    /** By the model's own name, also behind a relay's prefix (deepseek/deepseek-v4-flash). */
    fun canSwitchOff(model: String): Boolean = !thinksAlways(model) && SWITCHABLE.any { name(model).startsWith(it) }

    fun thinksAlways(model: String): Boolean = ALWAYS.any { name(model).startsWith(it) }

    private fun name(model: String) = model.trim().substringAfterLast('/').lowercase()
}

/**
 * The request as the OpenAI-compatible endpoints take it. An assistant turn that called
 * tools goes back with its calls, and with null content when it said nothing (the
 * canonical shape, what the OpenAI SDK itself sends); each result follows as a "tool"
 * message naming its call.
 *
 * [thinking] is the switch DeepSeek and GLM take for thinking before answering: true turns it
 * on, false off, null leaves it out. On, DeepSeek wants every earlier reply to carry its
 * reasoning too: the turn under way sends its own back, earlier turns an empty one. Off, no
 * reasoning goes back at all (as DeepSeek's own plugin does it). [effort]: how hard a model that
 * always thinks should (reasoning_effort); its reasoning goes back as with the switch left out.
 */
/**
 * The smallest thing that still counts as using the service: one word, one token back.
 *
 * Deliberately **not** streamed and deliberately not built by [requestBody] — a test should
 * ask for as little as possible, and should not quietly inherit whatever the real chat path
 * grows later (thinking switches, tools, pictures). A model that cannot answer "hi" in one
 * token is not going to carry a conversation.
 */
internal fun probeBody(model: String): JsonObject = buildJsonObject {
    put("model", model)
    put("stream", false)
    put("max_tokens", 1)
    putJsonArray("messages") {
        addJsonObject {
            put("role", "user")
            put("content", "hi")
        }
    }
}

/**
 * What to say about a 200 that is not a reply.
 *
 * Services disagree about where they put the complaint — 智谱's old endpoints use `msg`,
 * OpenAI-shaped ones nest it under `error`, some just use `message`. Whichever it is, the
 * service's own words beat anything guessed here, so they are passed straight through.
 */
/** The service's own words in an answer, if it wrote any: 智谱's `msg`, an OpenAI `error`, a bare `message`. */
internal fun saidBy(root: JsonObject): String? =
    (root["msg"] ?: root["message"])?.jsonPrimitive?.contentOrNull
        ?: root["error"]?.let { errorText(it) }

internal fun notAChatReply(root: JsonObject): String {
    val said = saidBy(root)
    return if (said.isNullOrBlank()) {
        "地址能连上，但它没有按聊天接口回话。多半是地址填到了别的层级。"
    } else {
        "地址能连上，但它没有按聊天接口回话：$said"
    }
}

internal fun requestBody(
    model: String,
    messages: List<ApiMessage>,
    tools: List<ToolSpec>,
    thinking: Boolean? = null,
    effort: String? = null,
): JsonObject = buildJsonObject {
    val allReasoning = tools.isNotEmpty() && thinking != false && Thinking.keepsToolReasoning(model)
    put("model", model)
    put("stream", true)
    if (thinking != null) putJsonObject("thinking") { put("type", if (thinking) "enabled" else "disabled") }
    if (effort != null) put("reasoning_effort", effort)
    putJsonArray("messages") {
        for (m in messages) {
            addJsonObject {
                put("role", m.role)
                if (m.images.isNotEmpty()) {
                    // With pictures the content becomes a list of parts, the text first.
                    putJsonArray("content") {
                        if (m.content.isNotEmpty()) {
                            addJsonObject {
                                put("type", "text")
                                put("text", m.content)
                            }
                        }
                        for (url in m.images) {
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", url) }
                            }
                        }
                    }
                } else if (m.toolCalls.isEmpty()) {
                    put("content", m.content)
                } else {
                    if (m.content.isEmpty()) put("content", JsonNull) else put("content", m.content)
                    if (thinking == null && !allReasoning) m.reasoning?.let { put("reasoning_content", it) }
                    putJsonArray("tool_calls") {
                        for (c in m.toolCalls) {
                            addJsonObject {
                                put("id", c.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", c.name)
                                    put("arguments", sendableArguments(c.arguments))
                                }
                            }
                        }
                    }
                }
                if ((thinking == true || allReasoning) && m.role == "assistant") put("reasoning_content", m.reasoning ?: "")
                m.toolCallId?.let { put("tool_call_id", it) }
            }
        }
    }
    if (tools.isNotEmpty()) {
        putJsonArray("tools") {
            for (t in tools) {
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    }
                }
            }
        }
    }
}

/**
 * Arguments are echoed back as the model wrote them, unless they are not a JSON object
 * (cut off mid-stream, or "" for no parameters): endpoints that check them would reject
 * the whole request. The tool already told the model its arguments were broken.
 */
private fun sendableArguments(raw: String): String =
    if (raw.isNotBlank() && ToolArgs.parse(raw) != null) raw else "{}"
