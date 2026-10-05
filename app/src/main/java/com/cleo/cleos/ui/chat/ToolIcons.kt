package com.cleo.cleos.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.graphics.vector.ImageVector

/** The line icon for a kind of tool call; what isn't one of the tools Cleos has is the sparkle. */
internal fun ToolKind.icon(): ImageVector = when (this) {
    ToolKind.Todo -> Icons.Outlined.TaskAlt
    ToolKind.Diary -> Icons.Outlined.MenuBook
    ToolKind.Secret -> Icons.Outlined.Lock
    ToolKind.Memory -> Icons.Outlined.Bookmark
    ToolKind.Letter -> Icons.Outlined.Mail
    ToolKind.Avatar -> Icons.Outlined.Face
    ToolKind.Weather -> Icons.Outlined.Cloud
    ToolKind.Location -> Icons.Outlined.LocationOn
    ToolKind.Alarm -> Icons.Outlined.Alarm
    ToolKind.Timer -> Icons.Outlined.Timer
    ToolKind.Calendar -> Icons.Outlined.CalendarMonth
    ToolKind.Music -> Icons.Outlined.MusicNote
    ToolKind.Outside -> Icons.Outlined.Extension
    ToolKind.Other -> Icons.Outlined.AutoAwesome
}
