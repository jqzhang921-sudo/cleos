package com.cleo.cleos.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.ApiPresets
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.avatarLetter
import com.cleo.cleos.ui.home.AvatarCropDialog

/**
 * The TA's picture and name, the start of their persona (written on a page of its own,
 * PersonaScreen), and, when there is more than one TA, deleting this one.
 */
@Composable
internal fun ProfilePage(vm: SettingsViewModel, onOpenPersona: () -> Unit, onLeave: () -> Unit) {
    val palette = LocalGlassPalette.current
    val companions by vm.companions.collectAsStateWithLifecycle()
    val ta = companions.firstOrNull { it.id == vm.companionId }
    val name = vm.aiName.trim().ifEmpty { "这个 TA" }
    var cropping by remember { mutableStateOf<Uri?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) cropping = uri }

    Section("头像和名字") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(ta?.avatar, ta?.avatarEmoji ?: avatarLetter(vm.aiName, "TA"), 72.dp)
            Spacer(Modifier.width(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val has = ta?.avatar != null || ta?.avatarEmoji != null
                Chip(if (has) "换一张" else "换头像", selected = false) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (has) Chip("用回默认", selected = false) { vm.clearAvatar() }
            }
        }
        Field("名字", vm.aiName, { vm.aiName = it })
    }

    Section("性格") {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(interactionSource = null, indication = null, onClickLabel = "打开", onClick = onOpenPersona),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    vm.persona.take(PERSONA_PREVIEW).replace('\n', ' ').trim().ifEmpty { "想让 TA 怎样说话、记得什么，都写在这里。可以不写。" },
                    color = if (vm.persona.isBlank()) palette.contentSecondary else palette.content,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (if (vm.persona.isEmpty()) "" else "${vm.persona.length} / $PERSONA_LIMIT 字 · ") + "点开单独一页写",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.contentSecondary)
        }
    }

    if (vm.companionCount > 1) {
        Row(Modifier.padding(horizontal = 2.dp)) {
            Chip("删除$name", selected = false) { confirmDelete = true }
        }
    }

    cropping?.let { uri ->
        AvatarCropDialog(
            uri = uri,
            onDismiss = { cropping = null },
            onCropped = { picture ->
                cropping = null
                vm.setAvatar(picture)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除$name？") },
            text = { Text("和${name}的所有对话、信、记忆、收藏，以及${name}写的日记会一起删掉，删了找不回来。你自己的日记和待办不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteCompanion(onLeave)
                }) { Text("删除", color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/**
 * Which service the TA talks through, its key and its model; and, if wanted, another model for
 * the words that are heard: on the phone, and answering a voice message.
 */
@Composable
internal fun ModelPage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current

    Section("用谁家的") { ServiceChips(vm.chat) }

    Section("连接") {
        ConnectionFields(vm.chat)
        Text(
            "每个 TA 用自己的模型。Key 跟着接口地址存：同一个地址的几个 TA 共用一个 Key，填一次就够。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }

    ListCard {
        ExplainedSwitch(
            "打电话、发语音时换个模型",
            "电话里和回你语音时，用另一个模型",
            "打开后，打电话时 TA 说的话，和你发语音过去后 TA 回的那一条，由下面这个模型来写，比如想让 Claude 来说。" +
                "平时打字、主动找你、写信、前情提要还是用上面那个。TA 自己想发的语音条，话在打字时就写好了，所以也还是上面那个。",
            vm.spokenOn,
        ) { vm.spokenOn = it }
    }

    if (vm.spokenOn) {
        Section("电话和语音用的模型") {
            ServiceChips(vm.spoken)
            ConnectionFields(vm.spoken)
            if (vm.spoken.baseUrl.isBlank() || vm.spoken.model.isBlank()) {
                Text("地址和模型都填好才会换，之前还用上面那个。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

/** The services there are presets for, the one in use marked. */
@Composable
private fun ServiceChips(fields: EndpointFields) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ApiPresets.all.forEach { p ->
            Chip(p.name, selected = fields.baseUrl.trimEnd('/') == p.baseUrl) { fields.applyPreset(p) }
        }
    }
}

/** Address, key and model, and the test that lists the address's models to pick from. */
@Composable
private fun ConnectionFields(fields: EndpointFields) {
    val palette = LocalGlassPalette.current
    val hasKey by fields.hasKey.collectAsStateWithLifecycle()
    var pickingModel by remember { mutableStateOf(false) }

    Field("接口地址", fields.baseUrl, { fields.baseUrl = it }, keyboardType = KeyboardType.Uri)
    OutlinedTextField(
        value = fields.keyInput,
        onValueChange = { fields.keyInput = it },
        label = { Text("API Key") },
        placeholder = { if (hasKey) Text("已保存（不再显示）；要换就重新填") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (fields.keyInput.isNotBlank()) Chip("保存 Key", selected = true) { fields.saveKey() }
        if (hasKey && fields.keyInput.isBlank()) {
            Text("Key 已加密保存在这台手机上", color = palette.contentSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Chip("清除", selected = false) { fields.clearKey() }
        }
    }
    Field("模型", fields.model, { fields.model = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip(if (fields.checking) "正在连接…" else "测试并列出模型", selected = false) { if (!fields.checking) fields.check() }
        if (!fields.models.isNullOrEmpty()) Chip("从列表里选", selected = false) { pickingModel = true }
    }
    fields.checkResult?.let { Text(it, color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp) }

    if (pickingModel) {
        AlertDialog(
            onDismissRequest = { pickingModel = false },
            title = { Text("选一个模型") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(fields.models.orEmpty()) { id ->
                        Text(
                            id,
                            fontSize = 15.sp,
                            fontWeight = if (id == fields.model) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    fields.model = id
                                    pickingModel = false
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingModel = false }) { Text("关闭") } },
        )
    }
}

/** How the TA goes about answering: thinking first, and coming to find the person on their own. */
@Composable
internal fun BehaviorPage(vm: SettingsViewModel) {
    ListCard {
        ExplainedSwitch(
            "深度思考",
            "回答前先想一想，更连贯，但更慢更费 token",
            "回答前先想一想：前后更连贯，推理也更好；每次要多等几秒，也多花一点 token。DeepSeek、智谱这类模型认这个开关，不认的会自动照常回复。" +
                "智谱 GLM-5.3 起的模型关不掉思考，关着时让它少想一点。",
            vm.deepThinking,
        ) { vm.setThinking(it) }
        RowDivider(inset = 0.dp)
        ExplainedSwitch(
            "主动找你",
            "想起什么会自己来找你，早晚也会打个招呼",
            "聊天时，TA 会给自己记下想过一阵再说的事（比如你去做饭了，过一会儿问问做得怎么样），到时候看看这之间聊了什么，" +
                "自己决定要不要找你；早上你醒了、晚上睡前，也可能来打个招呼。不会因为你没回、好久没聊来催你。",
            vm.proactive,
        ) { vm.setReachOut(it) }
    }
    if (vm.proactive) {
        Section("主动找你的时候") { ReachOutStatus(vm) }
    }
}
