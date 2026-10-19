package com.cleo.cleos.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.job
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
import java.nio.file.Path

sealed interface TextEvent {
    data class Text(val value: String) : TextEvent
    data class Thought(val value: String) : TextEvent
}

class TextFailure(message: String) : Exception(message)

fun chatUrl(endpoint: Endpoint): String {
    val base = keyAddress(endpoint.baseUrl)
    val url = base.toHttpUrlOrNull() ?: throw TextFailure("请填写完整的 HTTPS 接口地址。")
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null)
        throw TextFailure("接口地址不能包含用户名、密码、查询参数或片段。")
    if (!url.isHttps && !endpoint.allowHttp) throw TextFailure("HTTP 会明文传输聊天和 Key，请在模型设置中明确允许后使用。")
    return "$base/chat/completions"
}

fun failureText(status: Int, raw: String): String {
    val code = runCatching { Json.parseToJsonElement(raw).jsonObject.let { root ->
        val error = root["error"] as? JsonObject
        (error?.get("code") ?: root["code"])?.jsonPrimitive?.contentOrNull.orEmpty()
    } }.getOrDefault("")
    return when {
        code.uppercase() in setOf("INSUFFICIENT_BALANCE", "INSUFFICIENT_QUOTA") || status == 402 -> "账户余额或额度不足，请到接口服务商处查看。"
        status == 401 -> "接口拒绝了 API Key，请检查 Key 是否有效。"
        status == 403 -> "接口拒绝了访问，请检查账户权限。"
        status == 404 -> "没有找到这个接口或模型，请检查接口地址与模型名。"
        status == 429 -> "请求太频繁或额度受限，请稍后重试。"
        status >= 500 -> "接口服务暂时出错（$status），请稍后重试。"
        else -> "接口返回错误（$status），请检查模型配置。"
    }
}

class TextClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
    // Never forward a bearer token to a redirected host or downgraded HTTP endpoint.
    .followRedirects(false).followSslRedirects(false).build()) {
    fun stream(endpoint: Endpoint, key: String, messages: List<WireMessage>, mediaRoot:Path?=null): Flow<TextEvent> = frames(endpoint, key, messages,mediaRoot).map { it.getOrThrow() }

    // Transport failures cross the dispatcher as an ordered value. Throwing directly
    // inside flowOn can discard a final buffered delta before the UI receives it.
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private fun frames(endpoint: Endpoint, key: String, messages: List<WireMessage>,mediaRoot:Path?): Flow<Result<TextEvent>> = flow {
        suspend fun event(value: TextEvent) { emit(Result.success(value)) }
        if (endpoint.model.isBlank()) throw TextFailure("请先填写模型名。")
        if (key.isBlank()) throw TextFailure("请先在模型设置中填写 API Key。")
        val url = chatUrl(endpoint)
        val context=currentCoroutineContext()
        val prepared=prepareImages(messages,mediaRoot?:Path.of("__cleos_no_media__"),endpoint.supportsImages && mediaRoot!=null) {context.ensureActive()}
        val body = buildJsonObject {
            put("model", endpoint.model.trim()); put("stream", true)
            putJsonArray("messages") { prepared.forEach { m -> addJsonObject {
                put("role", m.role)
                if(m.images.isEmpty())put("content", m.content)
                else putJsonArray("content") {
                    addJsonObject {put("type","text");put("text",m.content.ifBlank {"请看这张图片。"})}
                    m.images.forEach {url ->addJsonObject {put("type","image_url");putJsonObject("image_url") {put("url",url)}}}
                }
            } } }
        }
        val call = client.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${key.trim()}")
            .header("Accept", "text/event-stream, application/json").post(body.toString().toRequestBody("application/json".toMediaType())).build())
        val cancellation = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw TextFailure(failureText(response.code, response.peekBody(8192).string()))
                var completed = false
                var sawContent = false
                val thinking = ThinkingText()
                suspend fun frame(raw: String) {
                    currentCoroutineContext().ensureActive()
                    if (raw.trim() == "[DONE]") { completed = true; return }
                    val root = try { Json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { throw TextFailure("接口返回的数据格式无法识别，已保留收到的内容。") }
                    if (root["error"] != null) throw TextFailure("接口在回复过程中返回错误，已保留收到的内容。")
                    val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
                    val delta = (choice["delta"] ?: choice["message"]) as? JsonObject ?: JsonObject(emptyMap())
                    val thought = (delta["reasoning_content"] ?: delta["reasoning"]) as? JsonPrimitive
                    thought?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { event(TextEvent.Thought(it)); sawContent = true }
                    (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { text -> thinking.split(text).forEach { event(it) }; sawContent = true }
                    val finish = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
                    if (finish == "length") throw TextFailure("回复达到接口长度上限，已保留收到的内容。")
                    if (finish == "tool_calls") throw TextFailure("电脑版原型暂不支持工具调用，请使用文字聊天模型。")
                    if (finish != null && finish != "null") completed = true
                }
                if (response.header("Content-Type").orEmpty().contains("text/event-stream", true)) {
                    val source = response.body.source()
                    val pending = StringBuilder()
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) {
                            if (pending.isNotEmpty()) { frame(pending.toString()); pending.setLength(0) }
                            if (completed) break
                        } else if (line.startsWith("data:")) {
                            if (pending.length + line.length > 4_000_000) throw TextFailure("接口返回内容过大。")
                            if (pending.isNotEmpty()) pending.append('\n'); pending.append(line.drop(5).trimStart())
                        }
                    }
                    if (pending.isNotEmpty() && !completed) frame(pending.toString())
                } else {
                    val raw = response.peekBody(4_000_001).string()
                    if (raw.length > 4_000_000) throw TextFailure("接口返回内容过大。")
                    frame(raw)
                }
                thinking.finish().forEach { event(it) }
                if (!completed) throw TextFailure("连接提前结束，已保留收到的内容。")
                if (!sawContent) throw TextFailure("模型没有返回文字，请检查模型配置后重试。")
            }
        } catch (e: Exception) {
            // OkHttp may report SocketException when cancellation closes a blocked read.
            // Preserve cancellation so Stop is never displayed as a network failure.
            currentCoroutineContext().ensureActive(); throw e
        } finally { cancellation.dispose(); call.cancel() }
    }.catch { emit(Result.failure(it)) }.flowOn(Dispatchers.IO)
}
