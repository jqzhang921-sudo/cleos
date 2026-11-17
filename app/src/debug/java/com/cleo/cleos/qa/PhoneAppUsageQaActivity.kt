package com.cleo.cleos.qa

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.room.Room
import com.cleo.cleos.ai.*
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.AppDatabase
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug emulator only. Uses only emulator usage records, a transient DB and local tool calls. */
class PhoneAppUsageQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && applicationInfo.flags and 2 != 0)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val checks = mutableListOf<String>(); val result = JSONObject()
            val db = Room.inMemoryDatabaseBuilder(applicationContext, AppDatabase::class.java).build()
            val http = OkHttpClient()
            try {
                delay(1000)
                val live = PhoneStatusReader(applicationContext)
                var groups = emptySet<ToolGroup>()
                val guard = GuardedPhoneStatus(live) { groups }
                try { guard.appUsage(); error("closed sharing leaked") } catch (_: ToolFailure) { }
                checks += "closed app sharing blocks native source"
                groups = setOf(ToolGroup.PhoneUsage)
                try { guard.appUsage(); error("total sharing leaked app details") } catch (_: ToolFailure) { }
                checks += "total duration consent does not grant app details"
                groups = setOf(ToolGroup.PhoneAppUsage)
                val box = ToolBox(db.todos(), db.diary(), OpenMeteo(http), phoneStatus = guard)
                val settings = AppSettings(tools = groups)
                check(GroupToolRules.offered(settings).isEmpty())
                check(box.specs(groups).single().name == "get_app_usage")
                checks += "app-only consent offers separate tool; group chat has none"
                val privateRead = box.run(ToolCall("fixture", "get_app_usage", "{}"), settings, 1, 1)
                if (intent.getBooleanExtra("expectAllowed", false)) {
                    check(PhoneStatusReader.usageAllowed(applicationContext))
                    check(privateRead.result.contains("今日应用前台时长估算")) { privateRead.result }
                    check(privateRead.result.contains("Cleos")) { "Expected visible launcher label" }
                    check(!privateRead.result.contains("PhoneAppUsageQaActivity"))
                    checks += "native granted read returns timestamped per-app duration and launcher name, not activity class"
                    result.put("emulatorOnlyPreview", privateRead.result)
                } else {
                    check(!PhoneStatusReader.usageAllowed(applicationContext))
                    check(privateRead.result.contains("使用情况访问权限"))
                    checks += "missing or revoked Android usage permission fails closed"
                }
                for (ids in listOf(-1L to 1L, 1L to 0L, 0L to 1L)) {
                    val denied = box.run(ToolCall("fixture", "get_app_usage", "{}"), settings, ids.second, ids.first)
                    check(denied.result.contains("未读取数据"))
                }
                checks += "invalid identity and group calls cannot query device usage"
                var allowed = true
                val revoke = GuardedPhoneStatus(object : PhoneStatusSource {
                    override suspend fun battery() = ""
                    override suspend fun usage() = ""
                    override suspend fun appUsage(): String { allowed = false; return "PRIVATE_APP_CANARY" }
                }) { if (allowed) groups else emptySet() }
                try { revoke.appUsage(); error("revoked result leaked") } catch (e: ToolFailure) { check(!e.result.contains("PRIVATE_APP_CANARY")) }
                checks += "consent revoked in flight discards app data"
                result.put("ok", true)
            } catch (e: Throwable) { result.put("ok", false).put("error", e.stackTraceToString()) }
            finally { db.close(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
            File(filesDir, "phone-app-usage-qa-result.json").writeText(result.put("checks", JSONArray(checks)).put("externalModelRequests", 0).toString(2))
            withContext(Dispatchers.Main) { finish() }
        }
    }
}
