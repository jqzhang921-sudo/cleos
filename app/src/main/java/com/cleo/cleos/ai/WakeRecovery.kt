package com.cleo.cleos.ai

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** Only system reason codes and timestamps; no traces, process descriptions or chat contents. */
data class WakeProcessExit(val at: Long, val reason: Int)

internal object WakeRecovery {
    fun exits(context: Context): List<WakeProcessExit> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 32).orEmpty()
                .filter { it.processName == context.packageName }
                .map { WakeProcessExit(it.timestamp, it.reason) }
        }.getOrDefault(emptyList())
    }

    fun detail(startedAt: Long, now: Long, exits: List<WakeProcessExit>): String {
        // Old system history may have been pruned. Never attach an unrelated update hours
        // later to an old run, and describe a time correlation rather than a proven cause.
        val until = minOf(now, startedAt + 15 * 60_000L)
        val exit = exits.filter { it.at in startedAt..until }.minByOrNull { it.at }
        val reason = when (exit?.reason) {
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "系统记录：安装了应用更新"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "系统记录：内存不足，进程被回收"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "系统记录：资源使用受限，进程被停止"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "系统记录：应用被请求停止"
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "系统记录：应用崩溃"
            ApplicationExitInfo.REASON_ANR -> "系统记录：应用无响应"
            ApplicationExitInfo.REASON_OTHER -> "系统记录：进程被结束，未提供具体原因"
            else -> "没有查到可对应的系统退出原因"
        }
        return "上次执行未正常收尾。${reason.replace("系统记录：", "相近时间的系统记录：")}；未自动重做，避免重复操作"
    }
}
