package com.cleo.cleos.qa

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cleo.cleos.ui.settings.SecretKeyField

/** Isolated UI acceptance: uses only synthetic drafts, never reads/writes stored secrets or calls APIs. */
class SecretKeyQaActivity:ComponentActivity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        check(Build.HARDWARE in setOf("ranchu","goldfish") && applicationInfo.flags and 2 != 0)
        setContent { MaterialTheme {
            var draft by remember {mutableStateOf("")}
            var saved by remember {mutableStateOf(false)}
            var endpoint by remember {mutableIntStateOf(0)}
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(18.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("密钥输入检查 · 全部为合成示例")
                SecretKeyField(draft,{draft=it},saved,endpoint)
                Button(onClick={draft="DEMO_ONLY_NOT_A_REAL_KEY_123456"}) {Text("填入合成示例")}
                Button(onClick={draft="DEMO…PARTIAL"}) {Text("填入截断示例")}
                Button(onClick={draft="DEMO\u200BPARTIAL"}) {Text("填入不可见字符示例")}
                Button(onClick={draft="";saved=true}) {Text("模拟保存并清空")}
                Button(onClick={endpoint++}) {Text("切换接口")}
                Text(if(saved) "示例已保存；不回填原文" else "示例未保存")
            }
        } }
    }
}
