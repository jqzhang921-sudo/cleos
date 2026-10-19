package com.cleo.cleos.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.cleo.cleos.data.db.StickerEntity
import com.cleo.cleos.ui.common.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The same collection/editor as private chat. Picking a picture only imports it, never sends it. */
@Composable
internal fun GroupStickerDrawer(stickers: List<StickerEntity>, enabled: Boolean, onSend: (StickerEntity) -> Unit, onProblem: (String) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val draftState = remember { mutableStateOf<StickerDraft?>(null) }
    var draft by draftState
    var problem by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<StickerEntity?>(null) }
    val savingState = remember { mutableStateOf(false) }
    var saving by savingState
    var importing by remember { mutableStateOf(false) }
    val mounted = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) { onDispose {
        mounted[0] = false
        // Read the State objects directly: disposal can happen before the next recomposition.
        if (!savingState.value) (draftState.value as? StickerDraft.New)?.let { c.stickers.discard(it.picked) }
    } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try { draft = StickerDraft.New(c.stickers.import(uri)); problem = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onProblem(e.message ?: "这张图用不了") }
            finally { importing = false }
        }
    }
    StickerDrawer(stickers, onSend,
        onAdd = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRename = { draft = StickerDraft.Edit(it); problem = null }, onDelete = { deleting = it }, enabled = enabled && !importing && !saving)
    draft?.let { current -> StickerDialog(current, problem, onSave = { name, about ->
        if (!saving) {
            saving = true
            // An explicitly confirmed save finishes even if the drawer closes while Room writes.
            c.appScope.launch {
                try {
                    problem = when (current) {
                        is StickerDraft.New -> c.stickers.add(current.picked, name, about)
                        is StickerDraft.Edit -> c.stickers.edit(current.sticker.id, name, about)
                    }
                    if (problem == null && draft == current) draft = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { problem = e.message ?: "表情包没保存，请再试一次" }
                finally {
                    saving = false
                    if (!mounted[0]) (current as? StickerDraft.New)?.let { c.stickers.discard(it.picked) }
                }
            }
        }
    }, onDismiss = {
        if (!saving) {
            (draft as? StickerDraft.New)?.let { c.stickers.discard(it.picked) }
            draft = null; problem = null
        }
    }) }
    deleting?.let { sticker -> DeleteStickerDialog(sticker, onConfirm = {
        deleting = null
        c.appScope.launch {
            try { c.stickers.delete(sticker.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onProblem(e.message ?: "表情包没删除，请再试一次") }
        }
    }, onDismiss = { deleting = null }) }
}
