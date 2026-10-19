package com.cleo.cleos.data

import com.cleo.cleos.network.EndpointTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Device-local consent, encrypted with secrets and excluded from both system and Cleos backups. */
class HttpPermissions(private val read: () -> Flow<String?>, private val write: suspend (String) -> Unit) {
    constructor(secrets: SecretStore) : this({ secrets.secret("http_permissions") }, { secrets.setSecret("http_permissions", it) })
    private val lock = Mutex()
    private fun decode(raw: String?): Set<String> = runCatching {
        Json.decodeFromString<Set<String>>(raw ?: "[]").takeIf { it.size <= 128 } ?: emptySet()
    }.getOrDefault(emptySet())
    val approvals = read().map(::decode)
    fun key(origin: String, mcpId: String? = null): String = if (mcpId == null) "model|$origin" else "mcp:$mcpId|$origin"
    suspend fun allowed(url: String, mcpId: String? = null): Boolean {
        val origin = EndpointTransport.httpOrigin(url) ?: return false
        return key(origin, mcpId) in approvals.first()
    }
    suspend fun set(origin: String, on: Boolean, mcpId: String? = null) = lock.withLock {
        require(EndpointTransport.httpOrigin(origin) == origin) { "HTTP 地址已变化，请重新确认" }
        val existing = approvals.first()
        val next = if (on) existing + key(origin, mcpId) else existing - key(origin, mcpId)
        require(next.size <= 128) { "已保存的 HTTP 授权太多，请先关闭不用的地址" }
        write(Json.encodeToString(next))
    }
    suspend fun clearMcp(id: String) = lock.withLock {
        write(Json.encodeToString(approvals.first().filterNot { it.startsWith("mcp:$id|") }.toSet()))
    }
}
