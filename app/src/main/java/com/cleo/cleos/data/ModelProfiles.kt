package com.cleo.cleos.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.UUID

/** Keys continue to live in SecretStore by address; profiles never duplicate credentials. */
@Serializable
data class ModelProfile(val id: String = UUID.randomUUID().toString(), val name: String,
                        val baseUrl: String, val model: String, val forgotten: Boolean = false)

internal object ModelProfileRules {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(profiles: List<ModelProfile>) = json.encodeToString(profiles)
    fun decode(raw: String?): List<ModelProfile> = if (raw.isNullOrBlank()) emptyList()
        else json.decodeFromString<List<ModelProfile>>(raw)
    fun valid(url: String, model: String): Boolean = runCatching {
        val uri = URI(url.trim())
        uri.scheme.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && model.isNotBlank()
    }.getOrDefault(false)
    fun matches(profile: ModelProfile, url: String, model: String) =
        addressOf(profile.baseUrl) == addressOf(url.trim()) && profile.model == model.trim()
    fun saved(profiles: List<ModelProfile>, url: String, model: String, name: String?): List<ModelProfile> {
        require(valid(url, model)) { "先填好接口地址和模型名称" }
        val existing = profiles.firstOrNull { matches(it, url, model) }
        // An explicit removal must survive automatic history collection and app restarts.
        // Saving by name deliberately restores that connection to the list.
        if (existing?.forgotten == true && name == null) return profiles
        val label = name?.trim()?.take(64)?.takeIf { it.isNotEmpty() } ?: existing?.name ?: model.trim().take(64)
        val profile = ModelProfile(existing?.id ?: UUID.randomUUID().toString(), label, url.trim(), model.trim())
        return listOf(profile) + profiles.filterNot { it.id == profile.id }
    }
}

class ModelProfiles(private val read: () -> Flow<String?>, private val write: suspend (String) -> Unit) {
    constructor(secrets: SecretStore) : this({ secrets.secret("model_profiles") }, { secrets.setSecret("model_profiles", it) })
    private val mutex = Mutex()
    val profiles: Flow<List<ModelProfile>> = read().map { ModelProfileRules.decode(it).filterNot { p -> p.forgotten } }
    suspend fun save(url: String, model: String, name: String? = null) = change {
        ModelProfileRules.saved(it, url, model, name)
    }
    suspend fun rename(id: String, name: String) {
        require(name.isNotBlank()) { "名称不能为空" }
        change { profiles -> profiles.map { if (it.id == id) it.copy(name = name.trim().take(64)) else it } }
    }
    suspend fun delete(id: String) = change { profiles -> profiles.map { if (it.id == id) it.copy(forgotten = true) else it } }
    private suspend fun change(transform: (List<ModelProfile>) -> List<ModelProfile>) = mutex.withLock {
        write(ModelProfileRules.encode(transform(ModelProfileRules.decode(read().first()))))
    }
}
