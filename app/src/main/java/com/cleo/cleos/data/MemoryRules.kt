package com.cleo.cleos.data

import com.cleo.cleos.ai.MemoryKinds
import com.cleo.cleos.data.db.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

object MemoryRules {
    private val json=Json { encodeDefaults=true; ignoreUnknownKeys=true }
    private val serializer=ListSerializer(MemoryEntity.serializer())
    fun normalize(s:String)=Normalizer.normalize(s,Normalizer.Form.NFKC).trim().replace(Regex("\\s+")," ").lowercase(Locale.ROOT)
    fun unique(details:List<String>):List<String> = details.map(String::trim).filter(String::isNotEmpty).distinctBy(::normalize)
    fun facts(m:MemoryEntity)= (listOf(m.summary)+MemoryDetails.decode(m.details)).map(::normalize).filter(String::isNotBlank)
    fun hashes(m:MemoryEntity)=facts(m).map(MemoryContext::hash).distinct()
    fun revision(m:MemoryEntity)=MemoryContext.hash(json.encodeToString(MemoryEntity.serializer(),m))
    fun encode(rows:List<MemoryEntity>)=json.encodeToString(serializer,rows)
    fun decode(raw:String):List<MemoryEntity> { require(raw.length<=1_000_000);return json.decodeFromString(serializer,raw) }
    fun destructive(before:MemoryEntity,next:MemoryEntity)=before.summary!=next.summary ||
        !MemoryDetails.decode(next.details).map(::normalize).containsAll(MemoryDetails.decode(before.details).map(::normalize)) ||
        before.kind!=next.kind || before.name!=next.name || before.temporalState!=next.temporalState ||
        before.validFromDay!=next.validFromDay || before.validUntilDay!=next.validUntilDay
    fun valid(m:MemoryEntity)=m.companionId>0 && MemoryKinds.of(m.kind)!=null &&
        m.name.isNotBlank() && m.name.length<=MemoryKinds.NAME_MAX && m.summary.isNotBlank() && m.summary.length<=MemoryKinds.SUMMARY_MAX &&
        MemoryDetails.decode(m.details).let { it.size<=MemoryKinds.DETAILS && it.all { d->d.isNotBlank() && d.length<=MemoryKinds.DETAIL_MAX } } && MemoryContext.valid(m)
    fun merge(rows:List<MemoryEntity>,at:Long):MemoryEntity {
        require(rows.size in 2..5 && rows.map { it.id }.distinct().size==rows.size && rows.all { it.id>0 && !it.pinned }) { "需选择 2–5 条不同且未钉住的记忆" }
        val target=rows.first()
        require(rows.all { it.companionId==target.companionId && it.kind==target.kind &&
            it.temporalState==target.temporalState && it.validFromDay==target.validFromDay && it.validUntilDay==target.validUntilDay }) {
            "归属、分类或适用时间不同，需分别保留"
        }
        val details=unique(rows.flatMap { MemoryDetails.decode(it.details) + if(it.id!=target.id && normalize(it.summary)!=normalize(target.summary)) listOf(it.summary) else emptyList() })
        require(details.size<=MemoryKinds.DETAILS) { "合并会超过 30 条细节，请先手动整理，未截掉任何内容" }
        val next=target.copy(details=MemoryDetails.encode(details),updatedAt=at)
        val traces=rows.flatMap { row -> MemoryContext.traces(row).flatMap { trace ->
            if(row.id!=target.id && trace.target=="summary:"+MemoryContext.hash(row.summary) && normalize(row.summary)!=normalize(target.summary))
                listOf(trace,trace.copy(target="detail:"+MemoryContext.hash(row.summary))) else listOf(trace)
        } }.groupBy { it.target }.mapValues { (_,r)->r.maxWith(compareBy<MemoryTrace>{it.quoted}.thenBy { it.at }) }
        val mapped=MemoryContext.targets(next).distinct().mapNotNull { traces[it] }
        // Metadata stays in the existing validated format; unrelated histories are not copied into a fact.
        val traceJson=json.encodeToString(ListSerializer(MemoryTrace.serializer()),mapped)
        return next.copy(provenance=traceJson)
    }
    fun validateBackup(changes:List<MemoryChangeEntity>,blocks:List<MemoryBlockEntity>,owners:Set<Long>) {
        require(changes.map{it.id}.distinct().size==changes.size && changes.all { c ->
            c.id>0 && c.companionId in owners && c.actor in setOf("ai","user","organizer") &&
                c.state in setOf("pending","applied","undone","rejected","review") &&
                c.reason.length<=500 && c.createdAt>=0 && c.action in setOf("remember","update","manual","merge","review") &&
                runCatching { (decode(c.beforeRows)+decode(c.afterRows)).all { it.id>0 && it.companionId==c.companionId && valid(it) } &&
                    decode(c.beforeRows).size<=5 && decode(c.afterRows).size<=5 }.getOrDefault(false)
        } && changes.groupBy { it.companionId }.values.all { it.size<=100 }) { "记忆变更记录无效" }
        require(blocks.map { it.companionId to it.factHash }.distinct().size==blocks.size && blocks.all {
            it.companionId in owners && it.createdAt>=0 && it.factHash.matches(Regex("[0-9a-f]{64}"))
        }) { "记忆遗忘规则无效" }
    }
}
