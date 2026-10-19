package com.cleo.cleos.ui.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cleo.cleos.glass.*
import com.cleo.cleos.ui.common.*
import kotlinx.coroutines.launch

/** Answering is an explicit foreground action. Merely rendering this card never starts audio. */
@Composable
internal fun IncomingCallPrompt() {
    val c=appContainer()
    val offer by c.incomingCalls.pending.collectAsStateWithLifecycle()
    val error by c.incomingCalls.problem.collectAsStateWithLifecycle()
    val requested by c.incomingCalls.answerRequested.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val scope=rememberCoroutineScope()
    var permissionFor by remember { mutableStateOf<String?>(null) }
    var answering by remember { mutableStateOf(false) }
    fun answer(id: String) {
        if(answering || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        answering=true
        scope.launch {
            try { c.incomingCalls.accept(id) }
            catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){c.incomingCalls.problem.value="暂时无法接通，请稍后再试。"}
            finally { answering=false }
        }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id=permissionFor; permissionFor=null
        if(granted && id!=null) answer(id)
        else if(id!=null) { c.incomingCalls.problem.value="没有麦克风权限，本次没有接通。"; scope.launch { c.incomingCalls.dismiss(id,"failed") } }
    }
    fun prepare(id: String) {
        if(answering || permissionFor!=null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) answer(id)
        else { permissionFor=id; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    val stage by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(requested,offer?.invitation?.id,stage) {
        val id=requested
        if(id!=null && offer?.invitation?.id==id && stage.isAtLeast(Lifecycle.State.RESUMED)) {
            c.incomingCalls.answerRequested.value=null; prepare(id)
        }
    }
    offer?.let { item ->
        val palette=LocalGlassPalette.current
        Dialog(onDismissRequest={ if(!answering) scope.launch { c.incomingCalls.dismiss(item.invitation.id) } }) {
            GlassSurface(Modifier.fillMaxWidth().semantics { contentDescription="TA 主动来电" },
                contentPadding=PaddingValues(24.dp)) {
                Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    Avatar(item.ta.avatar,item.ta.avatarEmoji ?: avatarLetter(item.ta.name,"TA"),72.dp)
                    Text(item.ta.name.ifBlank { "TA" },color=palette.content,fontSize=22.sp)
                    Text("想和你语音通话",color=palette.contentSecondary,fontSize=14.sp)
                    Text(item.invitation.reason,color=palette.content,fontSize=15.sp)
                    Row(horizontalArrangement=Arrangement.spacedBy(48.dp)) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            GlassIconButton(Icons.Rounded.CallEnd,"拒绝 TA 来电",{
                                scope.launch { c.incomingCalls.dismiss(item.invitation.id) }
                            },LocalWallpaperBackdrop.current,tint=palette.error,enabled=!answering,size=60.dp)
                            Text("拒绝",color=palette.contentSecondary,fontSize=12.sp)
                        }
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            GlassIconButton(Icons.Rounded.Call,"接听 TA 来电",{prepare(item.invitation.id)},
                                LocalWallpaperBackdrop.current,enabled=!answering,size=60.dp)
                            Text(if(answering) "正在接通…" else "接听",color=palette.contentSecondary,fontSize=12.sp)
                        }
                    }
                    Text("接听后才打开话筒",color=palette.contentSecondary,fontSize=11.sp)
                }
            }
        }
    }
    error?.let { text -> AlertDialog(onDismissRequest={c.incomingCalls.problem.value=null},
        title={Text("来电提示")},text={Text(text)},confirmButton={TextButton({c.incomingCalls.problem.value=null}){Text("知道了")}}) }
}
