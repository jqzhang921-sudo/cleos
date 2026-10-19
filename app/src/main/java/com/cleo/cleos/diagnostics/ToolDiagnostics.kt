package com.cleo.cleos.diagnostics

import android.content.Context
import android.os.Build
import java.io.File

/** Local bounded records, outside Android backup. Sharing happens only via the About page. */
class ToolDiagnostics(context: Context) {
    private val app = context.applicationContext
    private val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
    private val store = ToolDiagnosticStore(File(app.noBackupFilesDir, "tools-diagnostics"), version)

    internal fun record(metadata: String) = store.record(metadata)
    fun clear() = store.clear()
    fun pin() = store.pin()
    fun report(pinned: Boolean = false): String? {
        val text = if (pinned) store.issue() else store.recent()
        if (text.isBlank()) return null
        return "Cleos $version 工具诊断\n${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n" +
            "${if (pinned) "已保留的异常现场" else "最近记录"}；时间为 UTC，逐行 build 为当时版本。\n" +
            "只记录请求结构、结果类别与阶段耗时，不含 Key、聊天正文、工具参数或原始响应。未自动上传。\n\n$text"
    }
}
