package com.cleo.cleos.ui.todo

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.cleo.cleos.ai.CalEvent
import com.cleo.cleos.ai.PhoneCalendar
import com.cleo.cleos.data.CourseCalendarFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

internal data class TodoCalendarAccess(
    val events: List<CalEvent>,
    val canRead: Boolean,
    val notice: String?,
    val busy: Boolean,
    val connect: () -> Unit,
    val pickCourseFile: () -> Unit,
    val clearNotice: () -> Unit,
)

@Composable
internal fun todoCalendarAccess(first: LocalDate, endExclusive: LocalDate, zone: ZoneId): TodoCalendarAccess {
    val context = LocalContext.current
    val app = context.applicationContext
    val phone = remember(app) { PhoneCalendar(app) }
    val scope = rememberCoroutineScope()
    var canRead by remember { mutableStateOf(PhoneCalendar.canRead(app)) }
    var refresh by remember { mutableIntStateOf(0) }
    var events by remember(first, endExclusive, zone, canRead) { mutableStateOf(emptyList<CalEvent>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<com.cleo.cleos.data.CourseEvent>?>(null) }
    val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canRead = PhoneCalendar.canRead(app)
        refresh++
    }
    LaunchedEffect(start, end, canRead, refresh) {
        if (!canRead) return@LaunchedEffect
        try {
            // A one-day margin also covers calendars whose all-day dates are UTC.
            events = phone.agenda(start - 86_400_000, end + 86_400_000)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice = "暂时读不到手机日历，可以稍后刷新" }
    }

    fun importConfirmed() {
        val entries = preview ?: return
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = phone.importCourses(entries)
                preview = null
                refresh++
                notice = if (result.added == 0) "这些日程已经导入过"
                else "导入了 ${result.added} 条日程" +
                    if (result.skipped > 0) "，跳过 ${result.skipped} 条重复日程" else ""
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice = "手机日历未能保存课表，请检查权限和文件后重试" }
            finally { busy = false }
        }
    }
    val readPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canRead = PhoneCalendar.canRead(app)
        if (granted) { notice = null; refresh++ } else notice = "允许读取日历后，手机上的安排才会显示在这里"
    }
    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canRead = PhoneCalendar.canRead(app)
        if (PhoneCalendar.allowed(app)) importConfirmed()
        else { preview = null; notice = "需要日历读写权限才能导入课表" }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            notice = null
            try {
                preview = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { CourseCalendarFile.read(it, zone) }
                        ?: error("无法打开课表文件")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: com.cleo.cleos.data.ImportException) { notice = e.message }
            catch (e: IllegalArgumentException) { notice = e.message ?: "无法读取课表文件" }
            catch (_: Exception) { notice = "无法读取这个课表文件，请选择有效的 .ics 文件" }
            finally { busy = false }
        }
    }
    preview?.let { entries ->
        val recurring = entries.count { it.recurrence != null }
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text("导入课表") },
            text = {
                Text(if (busy) "正在导入…" else "读取到 ${entries.size} 条日程" +
                    (if (recurring > 0) "，其中 $recurring 条会重复" else "") +
                    "。将保存到手机本地的「Cleos 课表」日历。")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    if (PhoneCalendar.allowed(app)) importConfirmed() else writePermission.launch(PhoneCalendar.PERMISSIONS)
                }) { Text("导入") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("取消") } },
        )
    }
    return TodoCalendarAccess(events, canRead, notice, busy, {
        if (PhoneCalendar.canRead(app)) {
            canRead = true; notice = null; refresh++
        } else readPermission.launch(Manifest.permission.READ_CALENDAR)
    }, { picker.launch(arrayOf("*/*")) }, { notice = null })
}
