package com.cleo.cleos.data

import com.cleo.cleos.data.db.CompanionEntity

/** One shared default for groups/feed, with an optional private-chat override per actual TA. */
object UserAvatars {
    private val leaf=Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
    fun filename(raw: String?): String? = raw?.takeIf { leaf.matches(it) && ".." !in it }
    fun effective(settings: AppSettings, ta: CompanionEntity?) = filename(ta?.userAvatar) ?: settings.userAvatar
    fun restored(raw: String?, exists: (String) -> Boolean) = filename(raw)?.takeIf(exists)
    data class Target(val id: Long, val createdAt: Long, val previous: String?) {
        fun matches(ta: CompanionEntity?) = ta!=null && ta.id==id && ta.createdAt==createdAt && ta.userAvatar==previous
    }
    fun target(ta: CompanionEntity)=Target(ta.id,ta.createdAt,ta.userAvatar)
    fun settingsFiles(s: AppSettings) = (s.bubblePresets.flatMap { it.decoration.imageFiles() } +
        s.bubbleDecoration.imageFiles() + listOfNotNull(s.wallpaper,s.feedCover,s.userAvatar)).toSet()
}
