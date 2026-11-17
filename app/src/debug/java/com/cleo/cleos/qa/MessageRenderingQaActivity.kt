package com.cleo.cleos.qa

import android.os.Build
import android.os.Bundle
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.ClickableSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.GlassMode
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.chat.MessageText
import com.cleo.cleos.ui.chat.MessageTypesetter
import com.cleo.cleos.ui.chat.SelectMessageTextDialog
import com.cleo.cleos.ui.theme.CleosTheme
import kotlinx.serialization.json.*
import java.io.File

/** Emulator-only, offline synthetic fixture. No chat/role/model/database access. */
class MessageRenderingQaActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && applicationInfo.flags and 2 != 0)
        val dark = intent.getBooleanExtra("dark", false)
        val sample = intent.getStringExtra("sample") ?: "math"
        val samples = mapOf(
            "math" to """两边相等：
                |${'$'}y'x-y=x+yy'${'$'}
                |
                |解得 ${'$'}y'=\frac{x+y}{x-y}${'$'}。
                |再对 ${'$'}x${'$'} 求导：
                |\[y''=\frac{(1+y')(x-y)-(x+y)(1-y')}{(x-y)^2}\]
                |所以答案是 ${'$'}\frac{2(x^2+y^2)}{(x-y)^3}${'$'}。
                |（想了一会，把步骤理清楚）
                |\(\alpha+\beta=\gamma\)""".trimMargin(),
            "rich" to """# 小标题
                |**加粗**和 *轻强调*；[查看示例](https://example.com)。
                |1. 先看条件
                |2. 再算结果
                |
                |> 这一句作为引用。
                |```kotlin
                |val price = "${'$'}5 and ${'$'}10"
                |// ${'$'}x^2${'$'} 保留代码原样
                |```
                |![图片说明](https://example.com/no-fetch.png)""".trimMargin(),
            "colors" to """**颜色示例**
                |<span style="color:red">这句强调文字</span>，其余跟随主题。
                |${'$'}\textcolor{blue}{x^2+y^2=z^2}${'$'}
                |<font color='green'>绿色文字</font>，关闭后恢复主题颜色。""".trimMargin(),
            "invalid" to """不完整：${'$'}\frac{x}{
                |不支持：\[\includegraphics{https://example.com/x}\]
                |无效：\[\unknown{x}\]
                |价格 ${'$'}5 and ${'$'}10，代码 `${'$'}x^2${'$'}`。
                |<script>alert('原样文字')</script>""".trimMargin(),
            "long" to """长公式，点开可左右查看：
                |\[\frac{a_1+a_2+a_3+a_4+a_5+a_6+a_7+a_8+a_9+a_{10}+a_{11}+a_{12}}{b_1+b_2+b_3+b_4+b_5+b_6+b_7+b_8}\]
                |正文正常换行，**不跟着公式横向滚动**。""".trimMargin(),
            "styles" to """普通文字样式
                |\Large {\color{blue}\textbf{Cleo，英语好好考}}
                |
                |正文里的 {\color{red}红色} 和 \textbf{加粗}，后面恢复正常。
                |\small{小字也保持能看清楚。}
                |\Huge{这句只会稍大一些。}
                |${'$'}\textcolor{purple}{\textbf{公式括号中的中文也能显示}}${'$'}
                |提到命令 \Huge，不会把后面的正文变大。
                |`\textbf{代码保留原文}`""".trimMargin(),
            "plain" to "今天也辛苦啦。晚饭吃了吗？我刚才还在想，要不要提醒你早点睡。"
        )
        val source = samples.getValue(sample)
        setContent {
            var colors by remember { mutableStateOf(false) }
            var enabled by remember { mutableStateOf(true) }
            var menu by remember { mutableStateOf(false) }
            var select by remember { mutableStateOf(false) }
            var action by remember { mutableStateOf("轻点公式放大 · 长按消息") }
            CleosTheme(AppSettings(glassMode = if (dark) GlassMode.Dark else GlassMode.Light,
                messageRichText = enabled, messageColors = colors), ImageStore(this)) {
                val palette = LocalGlassPalette.current
                val style = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 25.sp)
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("消息排版验收 · $sample", color = palette.content)
                    Row {
                        TextButton(onClick = { enabled = !enabled }) { Text(if (enabled) "排版：开" else "排版：关") }
                        TextButton(onClick = { colors = !colors }) { Text(if (colors) "颜色：开" else "颜色：关") }
                    }
                    Text(action, color = palette.contentSecondary)
                    GlassSurface(style = palette.bubble, shape = GlassShape.Rounded(24.dp), contentPadding = PaddingValues(14.dp)) {
                        Box(Modifier.fillMaxWidth().combinedClickable(onClick = { action = "已点按消息" }, onLongClick = { menu = true })) {
                            MessageText(source, palette.content, style, narration = true, render = !intent.getBooleanExtra("streaming", false))
                        }
                    }
                    Text("后续消息仍在这里", color = palette.content)
                }
                if (menu) AlertDialog(onDismissRequest = { menu = false }, title = { Text("消息菜单") }, text = { Text("原文复制入口保留") },
                    confirmButton = { TextButton(onClick = { menu = false; select = true }) { Text("选择原文") } })
                if (select) SelectMessageTextDialog(source, style) { select = false }
            }
            LaunchedEffect(Unit) {
                val start = System.nanoTime()
                val rendered = MessageTypesetter.render(this@MessageRenderingQaActivity, samples.getValue("math"), 40f, android.graphics.Color.BLACK, false, 700)
                val coldMs = (System.nanoTime() - start) / 1_000_000
                val t = System.nanoTime()
                repeat(10) { MessageTypesetter.render(this@MessageRenderingQaActivity, samples.getValue("math"), 40f, android.graphics.Color.BLACK, false, 700) }
                val warmMs = (System.nanoTime() - t) / 10_000_000
                val bad = MessageTypesetter.render(this@MessageRenderingQaActivity, samples.getValue("invalid"), 40f, android.graphics.Color.BLACK, false, 700)
                val off = MessageTypesetter.render(this@MessageRenderingQaActivity, samples.getValue("colors"), 40f, android.graphics.Color.BLACK, false, 700)
                val on = MessageTypesetter.render(this@MessageRenderingQaActivity, samples.getValue("colors"), 40f, android.graphics.Color.BLACK, true, 700)
                val styled = MessageTypesetter.render(this@MessageRenderingQaActivity, samples.getValue("styles"), 40f, android.graphics.Color.BLACK, true, 700)
                val styledOff = MessageTypesetter.render(this@MessageRenderingQaActivity, samples.getValue("styles"), 40f, android.graphics.Color.BLACK, false, 700)
                fun spans(r: MessageTypesetter.Result, c: Class<*>) = r.text.getSpans(0, r.text.length, c).size
                val payload = buildJsonObject {
                    put("formulas", spans(rendered, ImageSpan::class.java)); put("invalidFormulas", spans(bad, ImageSpan::class.java))
                    put("unsafeRetained", bad.text.contains("\\[\\includegraphics{https://example.com/x}\\]")); put("htmlRetained", bad.text.contains("<script>"))
                    put("colorsOff", spans(off, ForegroundColorSpan::class.java)); put("colorsOn", spans(on, ForegroundColorSpan::class.java))
                    put("colorTagsHidden", !on.text.contains("<span")); put("coldMs", coldMs); put("warmMeanMs", warmMs)
                    put("sourceUnchanged", source == samples.getValue(sample))
                    put("safeUrl", MessageTypesetter.safeUrl("https://example.com/a"))
                    put("nativeChineseStyles", styled.text.contains("Cleo，英语好好考") && !styled.text.contains("\\Large"))
                    put("styleWeightSpans", spans(styled, StyleSpan::class.java))
                    put("styledColorsOn", spans(styled, ForegroundColorSpan::class.java))
                    put("styledColorsOff", spans(styledOff, ForegroundColorSpan::class.java))
                    put("sizeBounded", styled.text.getSpans(0, styled.text.length, RelativeSizeSpan::class.java).all { it.sizeChange in .9f..1.18f })
                    put("styleCodePreserved", styled.text.contains("\\textbf{代码保留原文}"))
                    put("literalCommandPreserved", styled.text.contains("命令 \\Huge"))
                    put("unsafeUrlBlocked", !MessageTypesetter.safeUrl("javascript:alert(1)") && !MessageTypesetter.safeUrl("file:///x"))
                    val rich = MessageTypesetter.render(this@MessageRenderingQaActivity, samples.getValue("rich"), 40f, android.graphics.Color.BLACK, false, 700)
                    put("links", spans(rich, ClickableSpan::class.java))
                }
                File(filesDir, "message-rendering-qa.json").writeText(payload.toString())
            }
        }
    }
}
