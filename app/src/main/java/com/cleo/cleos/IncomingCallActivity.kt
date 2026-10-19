package com.cleo.cleos

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.cleo.cleos.ai.IncomingAlertRules
import com.cleo.cleos.ai.IncomingCallOffer
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.*
import com.cleo.cleos.ui.theme.CleosTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A ring-only lock-screen surface. It cannot accept or open audio itself. */
class IncomingCallActivity : ComponentActivity() {
    private val c get()=(application as CleosApp).container
    private var offer by mutableStateOf<IncomingCallOffer?>(null)
    private var answering by mutableStateOf(false)
    private var unlockProblem by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge();super.onCreate(savedInstanceState)
        setShowWhenLocked(true);setTurnScreenOn(true)
        val id=intent.getStringExtra(Notifier.EXTRA_INCOMING)
        if(id.isNullOrBlank()){finish();return}
        lifecycleScope.launch {
            try {
            c.incomingCalls.restored()
            val settings=c.settings.current()
            if(!IncomingAlertRules.canShow(settings.persistentIncomingAlerts,id,c.db.incomingCalls().get(id),System.currentTimeMillis())) {finish();return@launch}
            val first=withTimeoutOrNull(3000){c.incomingCalls.pending.first{it?.invitation?.id==id}}
            if(first==null){finish();return@launch}
            combine(c.incomingCalls.pending,c.settings.settings){pending,s->pending?.takeIf{s.persistentIncomingAlerts && it.invitation.id==id}}
                .collect { current -> if(current==null)finish() else offer=current }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { finish() }
        }
        setContent {
            val settings by c.settings.settings.collectAsState(initial=null)
            settings?.let { s -> CleosTheme(s,c.images) {
                BackHandler { decline(id) }
                GlassPage {
                    val palette=LocalGlassPalette.current
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp),
                        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        offer?.let { item ->
                            Text("语音来电",color=palette.contentSecondary,fontSize=18.sp)
                            Spacer(Modifier.height(28.dp))
                            Avatar(item.ta.avatar,item.ta.avatarEmoji ?: avatarLetter(item.ta.name,"TA"),112.dp)
                            Spacer(Modifier.height(20.dp))
                            Text(item.ta.name.ifBlank{"TA"},color=palette.content,fontSize=28.sp)
                            Text("想和你语音通话",color=palette.contentSecondary,fontSize=16.sp)
                            // Do not expose a private invitation reason on the lock screen.
                            Spacer(Modifier.height(44.dp))
                            Row(horizontalArrangement=Arrangement.spacedBy(72.dp)) {
                                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                    GlassIconButton(Icons.Rounded.CallEnd,"拒绝来电",{decline(id)},LocalWallpaperBackdrop.current,
                                        tint=palette.error,enabled=!answering,size=68.dp)
                                    Text("拒绝",color=palette.contentSecondary)
                                }
                                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                    GlassIconButton(Icons.Rounded.Call,"接听来电",{answer(id)},LocalWallpaperBackdrop.current,
                                        enabled=!answering,size=68.dp)
                                    Text(if(answering) "正在接通…" else "接听",color=palette.contentSecondary)
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                            Text(unlockProblem ?: "接听后才打开话筒；手机已锁定时需要先解锁",color=palette.contentSecondary,fontSize=12.sp)
                        }
                    }
                }
            } }
        }
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);recreate()}
    private fun decline(id: String) {if(answering)return;lifecycleScope.launch{c.incomingCalls.dismiss(id);finish()}}
    private fun answer(id: String) {
        if(answering)return
        answering=true;unlockProblem=null
        fun open() {
            if(isFinishing || isDestroyed || offer?.invitation?.id!=id)return
            startActivity(Intent(this,MainActivity::class.java).putExtra(Notifier.EXTRA_INCOMING,id)
                .putExtra(Notifier.EXTRA_ANSWER,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        val keyguard=getSystemService(KeyguardManager::class.java)
        if(keyguard?.isDeviceLocked==true) keyguard.requestDismissKeyguard(this,object:KeyguardManager.KeyguardDismissCallback(){
            override fun onDismissSucceeded(){open()}
            override fun onDismissCancelled(){answering=false}
            override fun onDismissError(){answering=false;unlockProblem="请先解锁手机，再接听来电"}
        }) else open()
    }
}
