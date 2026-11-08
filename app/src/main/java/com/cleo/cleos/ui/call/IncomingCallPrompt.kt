package com.cleo.cleos.ui.call

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import com.cleo.cleos.ai.IncomingCallOffer
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cleo.cleos.ui.common.*
import kotlinx.coroutines.launch

/** Answering is explicit. Rendering the banner never opens the microphone or starts call audio. */
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
        c.notifier.cancelIncoming()
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
    // Keep the outgoing content for its short exit, with its actions disabled immediately.
    var lastOffer by remember { mutableStateOf<IncomingCallOffer?>(null) }
    SideEffect { if (offer != null) lastOffer = offer }
    val displayed = offer ?: lastOffer
    val motion = systemMotionEnabled()
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(offer?.invitation?.id) { visibility.targetState = offer != null }
    BackHandler(enabled = offer != null && !answering) {
        offer?.let { item -> scope.launch { c.incomingCalls.dismiss(item.invitation.id) } }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visibleState = visibility,
            modifier = Modifier.statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
            enter = if (motion) slideInVertically(spring(dampingRatio = 0.9f, stiffness = 420f)) { -it } else EnterTransition.None,
            exit = if (motion) slideOutVertically(tween(180)) { -it } else ExitTransition.None,
        ) {
            displayed?.let { item ->
                IncomingCallBanner(
                    ta = item.ta,
                    answering = answering,
                    enabled = offer?.invitation?.id == item.invitation.id,
                    onDecline = { scope.launch { c.incomingCalls.dismiss(item.invitation.id) } },
                    onAnswer = { prepare(item.invitation.id) },
                )
            }
        }
    }
    error?.let { text -> AlertDialog(onDismissRequest={c.incomingCalls.problem.value=null},
        title={Text("来电提示")},text={Text(text)},confirmButton={TextButton({c.incomingCalls.problem.value=null}){Text("知道了")}}) }
}
