package com.cleo.cleos.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.ApiEndpoint
import com.cleo.cleos.ai.ChatException
import com.cleo.cleos.data.ApiPreset
import com.cleo.cleos.data.ApiPresets
import com.cleo.cleos.data.ModelProfile
import com.cleo.cleos.data.ModelProfileRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One model's connection as it is being edited: the address, a key for it, the model, and the
 * test that lists what the address has. The TA's model is one of these; the one it has for words
 * that are heard (CompanionEntity.spokenModelOn) is another, edited the same way. The key is only
 * ever written, encrypted and filed by address, never read back into the field.
 */
@Stable
class EndpointFields(private val c: AppContainer, private val scope: CoroutineScope, private val spoken: Boolean = false) {
    var baseUrl by mutableStateOf("")
    var model by mutableStateOf("")
    var keyInput by mutableStateOf("")
    var models by mutableStateOf<List<String>?>(null)
    var checking by mutableStateOf(false)
        private set
    var checkResult by mutableStateOf<String?>(null)
        private set
    val profiles = c.modelProfiles.profiles.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val legacyProfiles = c.modelProfiles.legacy.stateIn(scope, SharingStarted.Eagerly, emptyList())
    var activeUrl by mutableStateOf("")
        private set
    var activeModel by mutableStateOf("")
        private set
    private var companionId: Long = 0
    var savingProfile by mutableStateOf(false)
    val hasDraftChanges get() = baseUrl.trim() != activeUrl || model.trim() != activeModel || keyInput.isNotBlank()
    var profileMessage by mutableStateOf<String?>(null)
        private set
    var profileBusy by mutableStateOf(false)
        private set

    fun saveProfile(name: String) {
        val url = baseUrl.trim()
        val m = model.trim()
        val key = keyInput.trim()
        val id = companionId
        profileAction {
            require(ModelProfileRules.valid(url, m)) { "先填好接口地址和模型名称" }
            require(name.isNotBlank()) { "名称不能为空" }
            require(key.isNotEmpty() || !c.secrets.key(url).isNullOrBlank()) { "先填 API Key，再保存配置" }
            require(id > 0 && id == companionId) { "TA 已切换，请重新保存" }
            if (key.isNotEmpty()) c.secrets.setKey(url, key)
            c.modelProfiles.save(url, m, name)
            activate(id, url, m)
            profileMessage = "已保存并启用「${name.trim()}」"
        }
    }

    fun useProfile(profile: ModelProfile) {
        val id = companionId
        profileAction {
            require(!c.secrets.key(profile.baseUrl).isNullOrBlank()) { "这个配置还没有 Key，填好后保存即可使用" }
            require(id > 0 && id == companionId) { "TA 已切换，请重新选择" }
            if (!profile.isUserSaved) c.modelProfiles.save(profile.baseUrl, profile.model, profile.name)
            activate(id, profile.baseUrl, profile.model)
            profileMessage = "已使用「${profile.name}」"
        }
    }
    private suspend fun activate(id: Long, url: String, m: String) {
        c.companions.update(id) { if (spoken) it.copy(spokenApiBaseUrl = url, spokenApiModel = m)
            else it.copy(apiBaseUrl = url, apiModel = m) }
        if (id == companionId) load(url, m, id)
    }
    fun discardDraft() { load(activeUrl, activeModel, companionId) }
    fun renameProfile(id: String, name: String) = profileAction { c.modelProfiles.rename(id, name); profileMessage = "名称已修改" }
    fun deleteProfile(profile: ModelProfile) = profileAction {
        c.modelProfiles.delete(profile.id)
        profileMessage = "配置已删除，当前连接和 Key 保留"
    }
    private fun profileAction(action: suspend () -> Unit) {
        if (profileBusy) return
        profileBusy = true
        c.appScope.launch(Dispatchers.Main.immediate) {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { profileMessage = e.message ?: "配置保存失败" }
            finally { profileBusy = false }
        }
    }

    /** Whether the address being edited has a key yet: keys are filed by address. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hasKey: StateFlow<Boolean> = snapshotFlow { baseUrl }
        .flatMapLatest { c.secrets.hasKey(it) }
        .stateIn(scope, SharingStarted.Eagerly, false)
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeHasKey: StateFlow<Boolean> = snapshotFlow { activeUrl }
        .flatMapLatest { c.secrets.hasKey(it) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Another TA's, or the same one's afresh: what was typed or listed for the last one goes. */
    fun load(url: String, m: String, id: Long = companionId) {
        companionId = id
        activeUrl = url.trim()
        activeModel = m.trim()
        baseUrl = url
        model = m
        keyInput = ""
        models = null
        checkResult = null
        savingProfile = false
    }

    fun applyPreset(p: ApiPreset) {
        baseUrl = p.baseUrl
        model = p.defaultModel
        keyInput = ""
        profileMessage = null
        // A service that lists no models: the ones it is known to have, to pick from at once.
        models = p.models.ifEmpty { null }
        checkResult = null
    }

    /**
     * Sends one real message and reports whether a reply came back. Filling the model
     * picker happens after that, and only as a convenience.
     *
     * The test used to be "can this address list its models", which sent at least one
     * person round in circles: her address was right and her key was missing half of
     * itself, but the list came back in an unexpected shape, so the app talked about the
     * address and never once about the key. Asking the chat endpoint directly lets a bad
     * key say "bad key".
     */
    fun check() {
        scope.launch {
            checking = true
            checkResult = null
            val key = keyInput.trim().ifEmpty { c.secrets.key(baseUrl).orEmpty() }
            if (key.isEmpty()) {
                checkResult = "先填 API Key"
                checking = false
                return@launch
            }
            val endpoint = ApiEndpoint(baseUrl, key, model)
            checkResult = try {
                if (model.isBlank()) {
                    // Nothing to send a message *as* yet. Offer the list so there is
                    // something to pick from, and say plainly that this was not the test.
                    fillModels(endpoint)
                    "先填一个模型名再测一次——没有模型名就发不出消息，也就试不出来"
                } else {
                    c.chatClient.probe(endpoint)
                    val n = fillModels(endpoint)
                    if (n == null) "连上了，说得上话。这家不给模型列表，模型名自己填就行"
                    else "连上了，说得上话。有 $n 个模型可选"
                }
            } catch (e: ChatException) {
                // Saying hello fails for things that leave the address and the key both right,
                // and a model name the service has never heard of is the usual one — a relay
                // answers those with a 404 and its own complaint. The names it does know are
                // then the answer to "so what is it called here?", so they are fetched and
                // offered; the verdict stays what the test found, not what the list says.
                val n = fillModels(endpoint)
                if (n == null) e.message else "${e.message}\n\n它认的模型名，可以从上面「从列表里选」里挑一个。"
            } catch (e: Exception) {
                "出错了：${e.message ?: e.javaClass.simpleName}"
            }
            checking = false
        }
    }

    /**
     * Fills the model picker if anything can fill it, and says with how many.
     *
     * Never throws, and never decides the verdict: listing models and chatting are two
     * different endpoints, and a service is allowed to serve only the second. Falls back
     * to the models a preset already knows of (智谱 keeps no list).
     */
    private suspend fun fillModels(endpoint: ApiEndpoint): Int? {
        val list = runCatching { c.chatClient.models(endpoint) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: ApiPresets.at(baseUrl)?.models?.takeIf { it.isNotEmpty() }
            ?: return null
        models = list
        return list.size
    }
}
