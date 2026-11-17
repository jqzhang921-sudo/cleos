package com.cleo.cleos.qa

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.cleo.cleos.data.EnvelopeThemes
import com.cleo.cleos.data.InstantLetterData
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.ui.letters.LetterEnvelope

/** Visual fixture only: no database writes, transport, letters or permission changes. */
class EnvelopePreviewQaActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && applicationInfo.flags and 2 != 0)
        val small = intent.getBooleanExtra("small", false)
        val compare = intent.getBooleanExtra("compare", false)
        val at = System.currentTimeMillis() - 60_000
        setContent {
            MaterialTheme {
                var action by remember { mutableStateOf("轻点信封 · 长按菜单") }
                Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFBCAACF), Color(0xFFF2E8ED))))
                    .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text(action)
                    val textured=EnvelopeThemes.builtIn.last()
                    val themes=if(compare) listOf(textured,textured.copy(id="flat-preview",decoration="none"))
                        else if(small) listOf(textured.copy(envelope=0xFF282D48,envelopeInk=0xFFFFFCFF))
                        else listOf(textured,EnvelopeThemes.builtIn[0],textured.copy(id="custom-preview",envelope=0xFF282D48,envelopeInk=0xFFFFFCFF))
                    themes.forEachIndexed { i,theme ->
                        if(compare) Text(if(i==0) "雾光织纹 · 图片纹理 + 实时文字" else "相同底色 · 纯色对照")
                        val mine=!small && i==2
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=if(mine) Arrangement.End else Arrangement.Start,
                            verticalAlignment=Alignment.Top) {
                            if(!mine) { Text("TA",Modifier.padding(top=12.dp));Spacer(Modifier.width(12.dp)) }
                            LetterEnvelope(LetterEntity(id=i.toLong()+1,companionId=1,
                                author=if(mine) LetterEntity.AUTHOR_ME else LetterEntity.AUTHOR_AI,
                                content="Fixture body never appears on the envelope",createdAt=at,deliverAt=at,
                                readAt=if(i==0 && !small) at else null,sourceType=LetterEntity.INSTANT,
                                title=if(compare) "给你的一封信" else if(i==0 && !small) "" else "写给认真完成今天的你，一封很长标题的信",
                                envelope=InstantLetterData.encode(theme)),
                                from=if(compare) "TA" else if(mine) "我" else if(i==0 && !small) "TA" else "这是一位名字特别长的 TA",
                                modifier=Modifier.weight(1f,fill=false),onClick={action="已点按信封"},onLongClick={action="已长按信封"})
                        }
                    }
                    Text("后续聊天仍然在这里")
                }
            }
        }
    }
}
