package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Only metadata; never stores, logs or returns any fragment of the credential. */
internal data class KeyDraftInfo(val characters:Int,val hasEllipsis:Boolean,val hasSpacing:Boolean) {
    val warning:String? get()=when {
        hasEllipsis -> "含省略号，可能没有复制完整，请回服务商页面核对"
        hasSpacing -> "含空白或不可见字符，请核对复制内容"
        else -> null
    }
}
internal fun inspectKeyDraft(value:String)=KeyDraftInfo(
    characters=value.codePointCount(0,value.length),
    hasEllipsis='…' in value || "..." in value,
    hasSpacing=value.any { it.isWhitespace() || Character.isSpaceChar(it) || Character.isISOControl(it) || Character.getType(it)==Character.FORMAT.toInt() },
)

/** The caller supplies only the unsaved draft. Saved credentials are never read by this field. */
@Composable
internal fun SecretKeyField(value:String,onValueChange:(String)->Unit,hasSavedKey:Boolean,contextKey:Any,
                            label:String="API Key",modifier:Modifier=Modifier) {
    // No rememberSaveable: a new screen/process must always begin masked.
    var revealed by remember(contextKey) { mutableStateOf(false) }
    LaunchedEffect(value.isEmpty()) { if(value.isEmpty()) revealed=false }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { revealed=false }
    val info=remember(value) { inspectKeyDraft(value) }
    OutlinedTextField(value=value,onValueChange=onValueChange,label={Text(label)},
        placeholder={if(hasSavedKey) Text("已保存；重新填写可替换")},
        visualTransformation=if(revealed && value.isNotEmpty()) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon={ IconButton(enabled=value.isNotEmpty(),onClick={revealed=!revealed}) {
            Icon(if(revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription=if(revealed) "隐藏正在填写的 Key" else "显示正在填写的 Key")
        } },
        supportingText={if(value.isNotEmpty()) Text(buildString {
            append("已输入 ${info.characters} 个字符")
            info.warning?.let {append(" · ").append(it)}
        })},
        singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
        modifier=modifier.fillMaxWidth())
}
