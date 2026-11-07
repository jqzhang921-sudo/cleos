package com.cleo.cleos.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.Dates
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.diary.DiaryTab
import com.cleo.cleos.ui.todo.TodoTab

private enum class RecordSection(val label: String) { Diary("日记"), Todo("待办") }

/** Both lists keep their own filters, scroll position and unsent todo input. */
@Composable
fun RecordsTab(bottomInset: Dp, onOpenEntry: (id: Long, secret: Boolean, day: Long?) -> Unit) {
    var section by rememberSaveable { mutableStateOf(RecordSection.Diary) }
    val dates = rememberSaveable(saver = RecordCalendarState.Saver) { RecordCalendarState(Dates.today()) }
    val c = appContainer()
    val calendar by remember { c.settings.todoCalendar }.collectAsStateWithLifecycle(null)
    var requestedMode by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val modeWrites = remember { Mutex() }
    val controller = RecordCalendarController(dates, requestedMode ?: calendar) { enabled ->
        requestedMode = enabled
        // Serialise persistence and read the latest intent inside the lock. Older
        // writes cannot undo a rapid reversal, even when this tab is left mid-save.
        c.appScope.launch {
            modeWrites.withLock { c.settings.setTodoCalendar(requestedMode ?: enabled) }
        }
    }
    val holder = rememberSaveableStateHolder()
    val focus = LocalFocusManager.current
    val header: @Composable () -> Unit = {
        RecordSections(section) {
            focus.clearFocus()
            section = it
        }
    }
    CompositionLocalProvider(LocalRecordCalendar provides controller) {
    holder.SaveableStateProvider(section.name) {
        when (section) {
            RecordSection.Diary -> DiaryTab(bottomInset, onOpenEntry, header)
            RecordSection.Todo -> TodoTab(bottomInset, header)
        }
    }
    }
}

/** The same two underlined halves used for 朋友圈 / 话题. Owner filters sit below. */
@Composable
private fun RecordSections(selected: RecordSection, onSelect: (RecordSection) -> Unit) {
    val palette = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        RecordSection.entries.forEach { section ->
            val on = selected == section
            Column(
                Modifier.weight(1f).selectable(
                    selected = on,
                    role = Role.Tab,
                    onClick = { onSelect(section) },
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    section.label,
                    color = if (on) palette.content else palette.contentSecondary,
                    fontSize = 17.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
                Box(
                    Modifier.fillMaxWidth().height(if (on) 1.5.dp else 0.5.dp)
                        .background(if (on) palette.content else palette.content.copy(alpha = .12f)),
                )
            }
        }
    }
}
