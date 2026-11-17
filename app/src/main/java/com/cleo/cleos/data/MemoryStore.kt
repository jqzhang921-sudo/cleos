package com.cleo.cleos.data

import androidx.room.withTransaction
import com.cleo.cleos.ai.MemoryKinds
import com.cleo.cleos.data.db.*

/** The sole normal chat/editor mutation path. Recheck snapshots inside the same Room transaction. */
class MemoryStore(private val db:AppDatabase,private val clock:()->Long=System::currentTimeMillis) {
    private val log get()=db.memoryChanges()
    private suspend fun checkBefore(owner:Long,rows:List<MemoryEntity>) {
        require(db.companions().get(owner)!=null) { "这位 TA 已不存在" }
        require(rows.map{it.id}.distinct().size==rows.size && rows.all { it.companionId==owner && db.memories().get(it.id)==it }) {
            "记忆已被修改或移除，请重新打开后再操作"
        }
    }
    private suspend fun writeRows(owner:Long,before:List<MemoryEntity>,after:List<MemoryEntity>,actor:String):List<MemoryEntity> {
        checkBefore(owner,before)
        require(after.all { it.companionId==owner && MemoryRules.valid(it) }) { "记忆内容或时间无效" }
        require(after.filter{it.id>0}.all { row -> before.any { it.id==row.id } }) { "不能覆盖未核对的记忆" }
        if(actor!="user") {
            require(before.none { it.pinned }) { "钉住的记忆不能自动修改" }
            val blocked=log.blocks(owner).map{it.factHash}.toSet()
            require(after.none { MemoryRules.hashes(it).any(blocked::contains) }) { "这项内容已被要求遗忘，不能原样自动记回；如需重新记住，请用户手动添加" }
            val unchanged=db.memories().allFor(owner).filterNot { m->before.any { it.id==m.id } }
            require(after.none { row->row.id==0L && unchanged.any { old->old.kind==row.kind && MemoryRules.normalize(old.name)==MemoryRules.normalize(row.name) } }) {
                "同名话题已由另一轮写入，请重新打开已有记忆，不要重复创建"
            }
            val final=unchanged+after
            require(final.groupBy { it.kind }.all { (kind,rs)->rs.size<=maxOf(MemoryKinds.PER_KIND,db.memories().allFor(owner).count{it.kind==kind}) }) { "这一类记满了，请先整理" }
        } else log.unblock(owner,after.flatMap(MemoryRules::hashes))
        before.filterNot { b->after.any { it.id==b.id } }.forEach { db.memories().delete(it.id) }
        return after.map { row ->
            val saved=row.copy(updatedAt=maxOf(clock(),row.updatedAt,(before.firstOrNull{it.id==row.id}?.updatedAt ?: -1L)+1))
            if(saved.id==0L) saved.copy(id=db.memories().insert(saved)) else { db.memories().update(saved);saved }
        }
    }
    private suspend fun retain(owner:Long) { val old=log.forOwner(owner).drop(100); if(old.isNotEmpty())log.remove(owner,old.map{it.id}) }
    suspend fun write(owner:Long,before:List<MemoryEntity>,after:List<MemoryEntity>,action:String,actor:String,
        reason:String="",pending:Boolean=false):MemoryChangeEntity = db.withTransaction {
        checkBefore(owner,before)
        require(before.size<=5 && after.size<=5 && after.all { it.companionId==owner && MemoryRules.valid(it) })
        if(actor!="user") require(before.none { it.pinned }) { "钉住的记忆不能自动修改" }
        val current=if(pending) after.map { row->row.copy(updatedAt=before.firstOrNull{it.id==row.id}?.updatedAt ?: row.createdAt) }
            else writeRows(owner,before,after,actor)
        val event=MemoryChangeEntity(companionId=owner,action=action,actor=actor,state=if(pending)"pending" else "applied",
            reason=reason.take(500),beforeRows=MemoryRules.encode(before),afterRows=MemoryRules.encode(current),createdAt=clock())
        // Identical pending suggestions need only one review card.
        val same=log.forOwner(owner).firstOrNull { it.state==event.state && it.beforeRows==event.beforeRows && it.afterRows==event.afterRows && it.action==event.action }
        if(pending && same!=null) same else event.copy(id=log.insert(event)).also { retain(owner) }
    }
    suspend fun review(owner:Long,rows:List<MemoryEntity>,reason:String):MemoryChangeEntity = db.withTransaction {
        checkBefore(owner,rows)
        val event=MemoryChangeEntity(companionId=owner,action="review",actor="organizer",state="review",reason=reason.take(500),beforeRows=MemoryRules.encode(rows),createdAt=clock())
        val same=log.forOwner(owner).firstOrNull { it.state=="review" && it.beforeRows==event.beforeRows && it.reason==event.reason }
        same ?: event.copy(id=log.insert(event)).also { retain(owner) }
    }
    suspend fun apply(owner:Long,id:Long) = db.withTransaction {
        val event=requireNotNull(log.get(owner,id)) { "这条建议已移除" };require(event.state=="pending") { "这条建议已处理" }
        val before=MemoryRules.decode(event.beforeRows);require(before.none{it.pinned}) { "请先手动处理钉住的记忆" }
        val after=writeRows(owner,before,MemoryRules.decode(event.afterRows).map{it.copy(updatedAt=clock())},"user")
        log.update(event.copy(state="applied",afterRows=MemoryRules.encode(after)))
    }
    suspend fun dismiss(owner:Long,id:Long) = db.withTransaction {
        val event=requireNotNull(log.get(owner,id));require(event.state in setOf("pending","review"));log.update(event.copy(state="rejected"))
    }
    suspend fun undo(owner:Long,id:Long) = db.withTransaction {
        val event=requireNotNull(log.get(owner,id));require(event.state=="applied") { "只能撤销已采用的改动" }
        val after=MemoryRules.decode(event.afterRows);val before=MemoryRules.decode(event.beforeRows)
        checkBefore(owner,after)
        require(before.filter{b->after.none{it.id==b.id}}.all { db.memories().get(it.id)==null }) { "原编号已被占用，未覆盖当前记忆" }
        // Restoring an explicit user-selected snapshot is allowed, including a manually pinned topic.
        after.filter{a->before.none{it.id==a.id}}.forEach{db.memories().delete(it.id)}
        before.forEach { b->
            val restored=b.copy(updatedAt=maxOf(clock(),b.updatedAt+1,(after.maxOfOrNull{it.updatedAt} ?: -1)+1))
            if(db.memories().get(b.id)==null) db.memories().insert(restored) else db.memories().update(restored)
        }
        log.unblock(owner,before.flatMap(MemoryRules::hashes))
        log.update(event.copy(state="undone"))
    }
    /** Remove connected snapshots too: the history/undo UI must not resurrect forgotten facts. */
    suspend fun forget(owner:Long,memory:MemoryEntity) = db.withTransaction {
        checkBefore(owner,listOf(memory))
        val events=log.forOwner(owner)
        val ids=mutableSetOf(memory.id);val forgotten=MemoryRules.hashes(memory).toMutableSet();val erase=mutableListOf<MemoryChangeEntity>()
        var changed=true
        while(changed) { changed=false;events.filterNot{it in erase}.forEach { e ->
            val related=MemoryRules.decode(e.beforeRows)+MemoryRules.decode(e.afterRows)
            if(related.any{it.id in ids || MemoryRules.hashes(it).any(forgotten::contains)}) {
                erase+=e;ids+=related.map{it.id};forgotten+=related.flatMap(MemoryRules::hashes);changed=true
            }
        } }
        val facts=(listOf(memory)+erase.flatMap{MemoryRules.decode(it.beforeRows)+MemoryRules.decode(it.afterRows)}).flatMap(MemoryRules::hashes).distinct()
        log.block(facts.map{MemoryBlockEntity(owner,it,clock())})
        log.remove(owner,erase.map{it.id})
        db.memories().delete(memory.id)
    }
}
