package com.cleo.cleos.data

import com.cleo.cleos.data.db.WorldArticleEntity
import com.cleo.cleos.data.db.WorldPictureEntity

object WorldPictureData {
    fun validateLooks(pictures: List<WorldPictureEntity>, looks: List<com.cleo.cleos.data.db.WorldPictureLookEntity>, textNotes: List<com.cleo.cleos.data.db.WorldNoteEntity>) {
        val byArticle = pictures.associateBy { it.articleId }
        require(looks.map { it.id }.distinct().size == looks.size && (looks.map { it.runId } + textNotes.map { it.runId }).distinct().size == looks.size + textNotes.size) { "看图记录含重复编号或结果" }
        require((looks.map { it.companionId } + textNotes.map { it.companionId }).groupingBy { it }.eachCount().values.all { it <= WorldReading.MAX_NOTES }) { "随手本记录过多" }
        looks.forEach { look ->
            val p = byArticle[look.articleId]
            require(look.id > 0 && p != null && p.companionId == look.companionId && p.sha256 == look.pictureSha256 && look.createdAt > 0 && look.runId.isNotBlank() && look.runId.length <= 100) { "看图记录归属或像素版本无效" }
            require(look.content.length <= 4000 && look.question.length <= 600) { "看图感想过长" }
        }
    }
    fun digest(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private val filename = Regex("world-[0-9a-fA-F-]{36}\\.(jpg|png)")
    fun validFile(file: String) = filename.matches(file)
    fun validate(articles: List<WorldArticleEntity>, items: List<WorldPictureEntity>) {
        val byId = articles.associateBy { it.id }
        require(items.map { it.articleId }.distinct().size == items.size && items.map { it.file }.distinct().size == items.size) { "配图重复" }
        items.forEach { p ->
            val a = byId[p.articleId]
            require(a != null && a.companionId == p.companionId && a.space == "outside" && a.fingerprint == p.fingerprint && WorldTopicSearch.validUrl(a.originUrl)) { "配图来源或归属无效" }
            require(validFile(p.file) && p.width in 1..960 && p.height in 1..960 && p.fetchedAt > 0 && Regex("[0-9a-f]{64}").matches(p.sha256)) { "配图文件无效" }
            require(p.title.isNotBlank() && p.title.length <= 240 && p.author.isNotBlank() && p.author.length <= 1000 && p.credit.length <= 1000 && p.description.length <= 2000 &&
                listOf(p.title, p.author, p.credit, p.description).all { text -> text.none { it.isISOControl() } && text.indices.all { i -> when {
                    text[i].isHighSurrogate() -> i + 1 < text.length && text[i + 1].isLowSurrogate()
                    text[i].isLowSurrogate() -> i > 0 && text[i - 1].isHighSurrogate()
                    else -> true
                } } }) { "配图署名无效" }
            require(WorldPictureSource.validLicense(p.license, p.licenseUrl) && WorldPictureSource.validSource(p.sourceUrl) && WorldPictureSource.validImage(p.imageUrl)) { "配图许可或来源无效" }
        }
    }
}
