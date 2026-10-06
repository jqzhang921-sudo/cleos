package com.cleo.cleos

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal data class UpdateInfo(val version: String, val notes: String)
internal sealed interface UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult
    data object Current : UpdateResult
    data object Failed : UpdateResult
}

internal object UpdateVersions {
    fun parts(raw: String): List<Int>? {
        val value = raw.trim().removePrefix("v").removePrefix("V")
        if (!value.matches(Regex("[0-9]+(\\.[0-9]+)*"))) return null
        val parts = value.split('.').map { it.toIntOrNull() ?: return null }
        return parts.takeIf { it.size <= 4 }
    }
    fun newer(latest: String, current: String): Boolean {
        val l = parts(latest) ?: return false
        val c = parts(current) ?: return false
        for (i in 0 until maxOf(l.size, c.size)) {
            val delta = l.getOrElse(i) { 0 }.compareTo(c.getOrElse(i) { 0 })
            if (delta != 0) return delta > 0
        }
        return false
    }
    fun parse(raw: String, current: String): UpdateResult = runCatching {
        val release = Json.parseToJsonElement(raw).jsonObject
        require(release["draft"]?.jsonPrimitive?.booleanOrNull == false)
        require(release["prerelease"]?.jsonPrimitive?.booleanOrNull == false)
        val tag = release.getValue("tag_name").jsonPrimitive.content
        require(parts(tag) != null && parts(current) != null)
        if (newer(tag, current)) UpdateResult.Available(UpdateInfo(tag.trim().removePrefix("v").removePrefix("V"),
            release["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(12000))) else UpdateResult.Current
    }.getOrDefault(UpdateResult.Failed)
}

internal object UpdateRules {
    fun shouldCheck(manual: Boolean, now: Long, last: Long): Boolean =
        manual || last <= 0 || now < last || now - last >= 24 * 60 * 60 * 1000L
    fun shouldPrompt(manual: Boolean, version: String, skipped: String?, reminded: String?): Boolean =
        manual || (version != skipped && version != reminded)
}

/** Release metadata only; downloads keep using Releases' existing 蓝奏云 path. */
internal class Updates(context: Context, http: OkHttpClient, private val current: String) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val client = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available = _available.asStateFlow()
    private val _checking = MutableStateFlow(false)
    val checking = _checking.asStateFlow()
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback = _feedback.asStateFlow()

    suspend fun check(manual: Boolean = false) {
        if (!mutex.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            val last = prefs.getLong("last_check", 0)
            if (!UpdateRules.shouldCheck(manual, now, last)) return
            _checking.value = true
            _feedback.value = null
            prefs.edit().putLong("last_check", now).apply()
            when (val result = fetch()) {
                is UpdateResult.Available -> {
                    val skip = prefs.getString("skip", null)
                    val reminded = prefs.getString("reminded", null)
                    if (UpdateRules.shouldPrompt(manual, result.info.version, skip, reminded)) {
                        _available.value = result.info
                        prefs.edit().putString("reminded", result.info.version).apply()
                    }
                    if (manual) _feedback.value = "发现新版本 ${result.info.version}"
                }
                UpdateResult.Current -> if (manual) _feedback.value = "当前已是最新正式版本"
                UpdateResult.Failed -> if (manual) _feedback.value = "暂时无法检查更新，请稍后重试，也可以直接去蓝奏云查看。"
            }
        } finally { _checking.value = false; mutex.unlock() }
    }

    fun dismiss(skip: Boolean = false) {
        if (skip) _available.value?.let { prefs.edit().putString("skip", it.version).apply() }
        _available.value = null
    }

    private suspend fun fetch(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://api.github.com/repos/jqzhang921-sudo/cleos/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Cleos/$current").build()
            client.newCall(request).execute().use { response ->
                currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) return@withContext UpdateResult.Failed
                val body = response.body ?: return@withContext UpdateResult.Failed
                if (body.contentLength() > 128000) return@withContext UpdateResult.Failed
                val raw = body.charStream().use { reader ->
                    val out = StringBuilder()
                    val buffer = CharArray(4096)
                    while (out.length <= 128000) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        out.append(buffer, 0, count)
                    }
                    out.toString()
                }
                if (raw.length > 128000) UpdateResult.Failed else UpdateVersions.parse(raw, current)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { currentCoroutineContext().ensureActive(); UpdateResult.Failed }
    }
}
