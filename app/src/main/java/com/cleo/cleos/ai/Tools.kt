package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.DiaryBlock
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.Pats
import com.cleo.cleos.data.db.DiaryDao
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.LoreDao
import com.cleo.cleos.data.db.MemoryDao
import com.cleo.cleos.data.db.TodoDao
import com.cleo.cleos.data.db.TodoEntity
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import java.util.Locale

/**
 * What the model may do. Each group is switched on or off in settings. [Diary] is reading
 * the person's diary; [AiDiary] is the model's own entries, writing and reading back.
 * [Later] is not among the switches in settings: a TA gets it while its own "reach out"
 * switch is on (CompanionEntity.proactive), and it is never stored with the others. [Alarm] is
 * the phone's clock app; [Calendar] the phone's calendar. [Stickers] has no tool at all: the TA
 * writes a sticker's name into what it says, so it is told about apart from the tools, and a model
 * that takes no tools sends them too.
 */
enum class ToolGroup { Todos, Diary, AiDiary, Secrets, Avatar, Weather, Messages, Letters, Memory, Lore, Location, Speak, Later, Alarm, Calendar, Music, Stickers, Pat, FreeVisit, Feed, FeedActions }

/**
 * A function offered to the model, when any of its [groups] is on. [parameters] is a
 * JSON Schema object.
 */
data class ToolSpec(
    val name: String,
    val groups: Set<ToolGroup>,
    /** What it does, as a verb phrase for the chat: 记待办. */
    val action: String,
    val description: String,
    val parameters: JsonObject,
) {
    val activity: String get() = "在$action"
}

/** A call as the model wrote it. [arguments] is raw JSON text and may not parse. */
@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * [result] goes back to the model; [note] is the one line the chat shows (none when
 * blank). A [request] is put to the person as a card in the chat.
 */
data class ToolOutcome(val result: String, val note: String, val request: SecretRequest? = null, val sharedDiaryId: Long? = null, val sharedExcerpt: String? = null)

/**
 * A tool that could not do its job. [result] tells the model what to do instead;
 * [note] is the reason, for the chat line "记待办没成：…".
 */
class ToolFailure(val result: String, val note: String) : Exception(result)

/**
 * The tools and what they tell the model about themselves. Descriptions say when to use
 * a tool and where its inputs come from (an id "from list_todos"); the rules about
 * whether to use tools at all live in the system prompt.
 */
object ToolSpecs {
    val readFeed = ToolSpec(
        name = "read_feed", groups = setOf(ToolGroup.Feed), action = "看朋友圈",
        description = "查看 Cleos App 内共享的朋友圈和资讯话题，不是微信朋友圈。对方问能否看朋友圈、最近发了什么或让你去看时，先用它查实际内容。默认看对方最近的帖子；能读正文、作者、时间、来源与最近评论，图片只返回数量。只读，不会点赞、评论或发帖。",
        parameters = schema(
            "post_id" to prop("integer", "可不填。查看某条具体帖子，编号来自此前 read_feed 的结果；填了后忽略其他筛选"),
            "author" to prop("string", "user 对方（默认），self 你自己，all 所有人"),
            "kind" to prop("string", "all 全部（默认），moments 日常朋友圈，topic 资讯话题"),
            "query" to prop("string", "可不填。正文或来源标题里的关键词"),
            "limit" to prop("integer", "最多几条，默认 3，最多 5"),
            "offset" to prop("integer", "跳过几条匹配结果，默认 0；需要继续看更早的时使用"),
        ),
    )
    val publishFeed = ToolSpec(
        name = "publish_feed", groups = setOf(ToolGroup.FeedActions), action = "发朋友圈",
        description = "对方让你发朋友圈时，以你自己的 TA 身份在 Cleos App 发表一条文字日常动态。不是微信，不代替对方或其他 TA 发帖。直接写正文，不需要对方复制粘贴；成功返回后才说已发布。不会附图，不编造新闻或现实经历。",
        parameters = schema(required = listOf("content"),
            "content" to prop("string", "你想发表的正文，1–1200 字，用自己的语气")),
    )
    val likeFeed = ToolSpec(
        name = "like_feed", groups = setOf(ToolGroup.FeedActions), action = "赞朋友圈",
        description = "以你自己的 TA 身份点赞或取消赞 Cleos App 内的一条朋友圈或话题。先用 read_feed 确认帖子编号和作者；不能赞自己，不改变对方或其他 TA 的赞。只有成功返回才算操作完成。",
        parameters = schema(required = listOf("post_id"),
            "post_id" to prop("integer", "已有帖子的编号，来自 read_feed 或转发卡片"),
            "liked" to prop("boolean", "true 点赞（默认），false 取消你的赞；重复调用不会反复切换")),
    )
    val commentFeed = ToolSpec(
        name = "comment_feed", groups = setOf(ToolGroup.FeedActions), action = "回朋友圈",
        description = "以你自己的 TA 身份评论 Cleos App 内的一条动态或回复朋友评论。先用 read_feed 确认正文、作者及评论编号。不能回复自己的评论，也不把自己的正文当成别人说的话；自己的帖子只能回复朋友评论。成功返回后才算已留言。",
        parameters = schema(required = listOf("post_id", "content"),
            "post_id" to prop("integer", "已有帖子的编号，来自 read_feed 或转发卡片"),
            "content" to prop("string", "简短评论，1–200 字"),
            "reply_to" to prop("string", "可不填；要回复具体朋友时，填 read_feed 返回的那条评论 id")),
    )
    val addTodo = ToolSpec(
        name = "add_todo",
        groups = setOf(ToolGroup.Todos),
        action = "记待办",
        description = "在对方的待办清单里加一条。",
        parameters = schema(
            required = listOf("title"),
            "title" to prop("string", "要做的事，一句话"),
            "due" to prop("string", "哪天要做完，写成 YYYY-MM-DD；没说日期就不填"),
            "note" to prop("string", "补充说明，没有就不填"),
        ),
    )
    val listTodos = ToolSpec(
        name = "list_todos",
        groups = setOf(ToolGroup.Todos),
        action = "看待办",
        description = "看对方的待办清单：没做完的全部列出，每条带编号。改一条待办之前，先用它找到编号。",
        parameters = schema("include_done" to prop("boolean", "要不要顺带列出最近做完的")),
    )
    val updateTodo = ToolSpec(
        name = "update_todo",
        groups = setOf(ToolGroup.Todos),
        action = "改待办",
        description = "改一条待办：打勾、改回没做完、改内容、改日期。只填要改的项。",
        parameters = schema(
            required = listOf("id"),
            "id" to prop("integer", "待办编号，来自 list_todos 或 add_todo 的结果"),
            "done" to prop("boolean", "true 是做完了，false 是改回没做完"),
            "title" to prop("string", "新的内容"),
            "due" to prop("string", "新的日期 YYYY-MM-DD；要去掉日期就填 none"),
            "note" to prop("string", "新的补充说明；要去掉就填 none"),
        ),
    )
    /** Its description depends on whose entries it can reach; see [offered]. */
    val readDiary = ToolSpec(
        name = "read_diary",
        groups = setOf(ToolGroup.Diary, ToolGroup.AiDiary),
        action = "翻日记",
        description = readDiaryDescription(setOf(ToolGroup.Diary, ToolGroup.AiDiary)),
        parameters = schema(
            "date" to prop("string", "哪一天，YYYY-MM-DD"),
            "query" to prop("string", "关键词"),
            "limit" to prop("integer", "最多几篇，默认 3，最多 10"),
        ),
    )
    val writeDiary = ToolSpec(
        name = "write_diary",
        groups = setOf(ToolGroup.AiDiary),
        action = "写日记",
        description = "写一篇你自己的日记，记在今天。和对方的日记在同一个本子里，标着是你写的；普通日记对方能看但改不了；想留给自己的小秘密设 secret=true，标题和正文会隐藏，public_hint 是对方可见的一句话，不要泄露秘密。",
        parameters = schema(
            required = listOf("text"),
            "title" to prop("string", "标题，可以不写"),
            "text" to prop("string", "正文：你自己的所见所想，用第一人称"),
            "secret" to prop("boolean", "是否作为上锁的小秘密，默认 false"),
            "public_hint" to prop("string", "上锁时给对方看的公开提示，不要泄露标题和正文"),
        ),
    )
    val shareMySecret = ToolSpec(
        name = "share_my_secret",
        groups = setOf(ToolGroup.AiDiary),
        action = "分享小秘密",
        description = "分享你自己的小秘密：可以回应对方的请求，也可以在你愿意时主动分享。mode=full 解锁全文；mode=partial 只分享 excerpt 这段原文，全文继续上锁。只口头同意不会解锁，不愿意就正常回复。编号来自请求或 read_diary。",
        parameters = schema(required = listOf("id"),
            "id" to prop("integer", "你写的那篇小秘密的编号"),
            "mode" to prop("string", "full 或 partial，默认 full"),
            "excerpt" to prop("string", "partial 时必填：选出一段连续原文，最多 2000 字，不要添加解释或改写")),
    )
    val listSecrets = ToolSpec(
        name = "list_secrets",
        groups = setOf(ToolGroup.Secrets),
        action = "数小秘密",
        description = "看对方有哪些小秘密：只有编号和日期，标题和内容都看不到。也会说哪些你问过、对方怎么答的。",
        parameters = schema(),
    )
    val requestSecret = ToolSpec(
        name = "request_secret",
        groups = setOf(ToolGroup.Secrets),
        action = "请求看小秘密",
        description = "请对方给你看一个小秘密。对方会在聊天里决定；同意了，内容会跟着对方的下一条消息给你。",
        parameters = schema(
            required = listOf("id"),
            "id" to prop("integer", "小秘密的编号，来自 list_secrets"),
            "reason" to prop("string", "想看的理由，一句话，对方会看到"),
        ),
    )
    /**
     * Speaking in several messages, the way people do in a chat: one call, one bubble.
     * Optional on purpose. Plain text is shown too, as one bubble, so a model that doesn't
     * call it still gets heard: a chat that only shows what goes through a tool falls
     * silent whenever the model answers in plain text.
     */
    val sendMessage = ToolSpec(
        name = "send_message",
        groups = setOf(ToolGroup.Messages),
        action = "发消息",
        description = "发一条消息给对方。想分成几条说的时候用：一条只说一件事，要发几条就在这一次回复里调用几次，按顺序。只说一句的话直接回复就行。",
        parameters = schema(
            required = listOf("text"),
            "text" to prop("string", "这一条消息的内容"),
            "quote" to prop(
                "string",
                "可不填。这条在回对方的哪句话：照抄那句话里的几个字。对方连着发了几条、你一条条回的时候，或者回到前面某句话时才用；一问一答不要填",
            ),
        ),
    )

    val sendVoice = ToolSpec(
        name = "send_voice",
        groups = setOf(ToolGroup.Speak),
        action = "发语音",
        description = "用声音说：发一条语音条给对方，对方听到的是你的声音，也看得到文字。想用声音说的时候用，比如道晚安、撒娇、情绪浓的时候；平常还是打字。一条一两句话。",
        parameters = schema(
            required = listOf("text"),
            "text" to prop("string", "要说的话，写成说出口的样子，不要括号里的动作和神情"),
        ),
    )

    /** The calls that are the TA speaking: each becomes a bubble of its own. */
    val speaking = setOf(sendMessage.name, sendVoice.name)

    /** What a sent message answers the model with. */
    const val SENT = "已发出。"

    /**
     * One tool with four actions rather than four tools: they belong together, and the
     * model knows the four words (remember, open, update, forget) either way.
     */
    val memory = ToolSpec(
        name = "memory",
        groups = setOf(ToolGroup.Memory),
        action = "记东西",
        description = "长期记忆：关于对方是谁的事，以及关于你自己的事。一条记忆是一个话题：名字、一行摘要、若干条细节。" +
            "摘要一直在你上下文里，细节要 open 才看得到。\n" +
            "action 四选一：\n" +
            "· open：取出一条的细节。要说到具体内容就先 open，别照着摘要猜。\n" +
            "· remember：开一条新的。先看已有的，能归进某个已有话题就用 update 加细节，别另开一条。\n" +
            "· update：改一条：加细节、改摘要，或整体重写细节。事情变了、当初记错了就改它，别留两条互相矛盾的；「最近」那类过期了尤其要改。\n" +
            "· forget：整条删掉。只是变了就用 update；对方明确说别记了才删。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("open", "remember", "update", "forget").forEach { add(it) } }
                    put("description", "要做哪件事")
                }
                putJsonObject("id") {
                    put("type", "integer")
                    put("description", "open / update / forget 要：那条前面方括号里的编号，比如 12")
                }
                putJsonObject("category") {
                    put("type", "string")
                    putJsonArray("enum") { MemoryKinds.all.forEach { add(it.key) } }
                    put("description", "remember 要；update 只在换分类时给。\n" + MemoryKinds.all.joinToString("\n") { "${it.key}：${it.help}" })
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "话题名，短，像个标题：「怎么称呼」「读书口味」。remember 要；update 不改名就别给。")
                }
                putJsonObject("summary") {
                    put("type", "string")
                    put(
                        "description",
                        "一行，说清这条讲什么，不是内容本身。它会一直在你上下文里，也是你以后判断要不要打开这条的唯一依据。remember 要；update 不改就别给。",
                    )
                }
                putJsonObject("details") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "remember 用：具体内容，一条一件事。写具体的，不写性格判断；尽量带上从哪儿知道的（「对方自己说的」）。")
                }
                putJsonObject("add_detail") {
                    put("type", "string")
                    put("description", "update 用：追加一条细节。最常用，不动已有的。")
                }
                putJsonObject("set_details") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "update 用：整体替换所有细节。要删掉或改写某条细节时才用；先 open 读出来，把要留的一起写回去。")
                }
            }
            putJsonArray("required") { add("action") }
        },
    )

    /**
     * The 设定 a TA was given, one world book at a time. Chats are about what the person is
     * saying now; this is what everyone in that world already knows, and it is looked up
     * rather than carried: a book of hundreds of entries would otherwise be in every message.
     */
    val lore = ToolSpec(
        name = "lore",
        groups = setOf(ToolGroup.Lore),
        action = "查设定",
        description = "查你的设定（世界书）：你和对方所在的世界里，人、地方、称呼、规矩都写在里面。" +
            "聊到里面会有的词（人名、地名、称呼、别的设定里的说法）时，先查了再开口，别自己编。\n" +
            "action 两种：\n" +
            "· search：给一个词，看设定里和它有关的条目原文。\n" +
            "· open：给编号，看一整条。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("search", "open").forEach { add(it) } }
                    put("description", "search 查，open 看一整条")
                }
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "search 要：要查的那个词。短一点——人名、地名、称呼；别拿一整句话去查。")
                }
                putJsonObject("id") {
                    put("type", "integer")
                    put("description", "open 要：条目编号，来自 search 结果里的 #号，比如 7")
                }
            }
            putJsonArray("required") { add("action") }
        },
    )

    val readLetters = ToolSpec(
        name = "read_letters",
        groups = setOf(ToolGroup.Letters),
        action = "翻信",
        description = "看你和对方之间写过的信，最新的在前。对方提到信的时候用。",
        parameters = schema("limit" to prop("integer", "最多几封，默认 3，最多 10")),
    )

    val setMyAvatar = ToolSpec(
        name = "set_my_avatar",
        groups = setOf(ToolGroup.Avatar),
        action = "换头像",
        description = "换你自己的头像：用对方在这段聊天里发来的一张图（取正中间的方块），或者一个表情。两者给一个。",
        parameters = schema(
            "image" to prop("string", "图的编号，比如 #45-1；写 latest 就是对方最近发来的那张"),
            "emoji" to prop("string", "一个表情，比如 🌙"),
        ),
    )
    val getWeather = ToolSpec(
        name = "get_weather",
        groups = setOf(ToolGroup.Weather),
        action = "查天气",
        description = "查天气：现在的天气和接下来几天的预报。",
        parameters = schema(
            "city" to prop(
                "string",
                "城市。中国的用中文名，不带“市”字，如“杭州”；国外的用英文名，如“Tokyo”。对方没说在哪就不填，会查对方在设置里填的城市",
            ),
            "days" to prop("integer", "预报几天，默认 3，最多 7"),
        ),
    )

    val getLocation = ToolSpec(
        name = "get_location",
        groups = setOf(ToolGroup.Location),
        action = "查位置",
        description = "查对方手机现在在哪：省、市、区（查得到的话再细些），和坐标。对方问附近有什么、怎么走、天气，或者你需要知道对方在哪时用。",
        parameters = schema(),
    )

    /**
     * Noting something to come back to (ai/Later.kt). It leaves no line in the chat, not even when
     * it fails: what the TA means to bring up later is for later.
     */
    val noteForLater = ToolSpec(
        name = "note_for_later",
        groups = setOf(ToolGroup.Later),
        action = "记一笔",
        description = "给以后的自己记一笔：一件过一阵想跟对方说或问的事，和大概多久以后再想起来。到时候你会看到这一笔和这之间聊的，再决定说不说。",
        parameters = schema(
            required = listOf("what", "minutes"),
            "what" to prop("string", "想说或想问的事，写具体，到时候的你要看得懂：比如「问问糖醋排骨做成没有」「对方今晚考完试，问问考得怎样」"),
            "minutes" to prop("integer", "多少分钟以后再想起来。对方说去做饭了，大概 40；明早的事，就算到明早。最少 1，最多 10080（七天）"),
            "why" to prop("string", "可不填。当时的情形，给到时候的自己看"),
        ),
    )

    // Declared above `quiet`, which names it: an object's properties are initialized in the
    // order they are written, so one that is read by an earlier one has to come first.
    val patUser = ToolSpec(
        name = "pat_user",
        groups = setOf(ToolGroup.Pat),
        action = "拍一拍",
        description = "拍一拍对方，像聊天软件里双击头像：对方的手机会轻轻震一下，聊天里多一行“你拍了拍我”。" +
            "对方拍了你、想撒娇、打招呼、逗一逗的时候用，用来代替一句话也行。要用就在回复之间用，一次回复最多一下，别回回都拍。",
        parameters = schema(
            "suffix" to prop("string", "拍在哪儿，接在“我”后面，比如 的头、的脸蛋，最多 12 个字；不填就是直接拍了拍我"),
        ),
    )

    /** Tools that leave no trace in the chat, neither a line nor "在…" while they run. */
    val reactMessage = ToolSpec(
        name = "react_message", groups = setOf(ToolGroup.Messages), action = "贴表情",
        description = "给对方消息贴一个表情。message_id 来自对方消息前的编号，只能回应当前聊天的用户消息。自然地偶尔使用，别每条都贴。贴完可继续说话；只用表情回应设 finish=true。重复贴不会取消，取消用 remove=true。",
        parameters = schema(required = listOf("message_id", "emoji"),
            "message_id" to prop("integer", "对方消息编号"), "emoji" to prop("string", "一个 emoji 表情"),
            "remove" to prop("boolean", "取消，默认 false"), "finish" to prop("boolean", "只用表情回应，默认 false")),
    )
    val planNextVisit = ToolSpec(
        name = "plan_next_visit", groups = setOf(ToolGroup.FreeVisit), action = "安排下次看看",
        description = "决定本次聊天结束后多少分钟再醒来看看。醒来后可以保持安静；这是自由聊天的下一次机会，不是具体事项提醒。时间会受对方的频率、免打扰和每日次数限制。一次只保留最后的安排。",
        parameters = schema(required = listOf("minutes"),
            "minutes" to prop("integer", "多少分钟后再看看，正整数；按当前提示中的频率范围选择")),
    )
    val quiet = setOf(noteForLater.name, patUser.name, reactMessage.name, planNextVisit.name)

    val setAlarm = ToolSpec(
        name = "set_alarm",
        groups = setOf(ToolGroup.Alarm),
        action = "定闹钟",
        description = "在对方手机自带的时钟里定一个闹钟，到点手机响铃，和对方自己定的一样（能贪睡、能关）。" +
            "只能定钟点：在这个时间最近的一次响，今天过了就是明天；也可以按星期重复。" +
            "更远的某一天、或者要写清楚是什么事的，用日历加日程带提醒。",
        parameters = schema(
            required = listOf("time"),
            "time" to prop("string", "几点，24 小时制 HH:MM，比如 07:30、19:00"),
            "label" to prop("string", "可不填。闹钟上显示的字，比如「起床」「吃药」"),
            "repeat" to prop("string", "可不填。按星期重复：每天、工作日、周末，或者「一三五」；不填就只响一次"),
        ),
    )
    val setTimer = ToolSpec(
        name = "set_timer",
        groups = setOf(ToolGroup.Alarm),
        action = "开计时器",
        description = "在对方手机的时钟里开一个倒计时，到点手机响。「十分钟后叫我」「面煮三分钟」这种用它。",
        parameters = schema(
            required = listOf("minutes"),
            "minutes" to prop("number", "多少分钟，可以有小数，0.5 就是 30 秒；最多 1440"),
            "label" to prop("string", "可不填。计时器上显示的字"),
        ),
    )
    val readCalendar = ToolSpec(
        name = "read_calendar",
        groups = setOf(ToolGroup.Calendar),
        action = "看日历",
        description = "看对方手机日历上的安排：对方自己的，和你加的，按时间列出，每条带编号。",
        parameters = schema(
            "from" to prop("string", "从哪天看起，YYYY-MM-DD；不填是今天"),
            "days" to prop("integer", "看几天，默认 7，最多 31"),
            "query" to prop("string", "可不填。只看标题、地点或备注里有这个词的"),
        ),
    )
    val addEvent = ToolSpec(
        name = "add_event",
        groups = setOf(ToolGroup.Calendar),
        action = "加日程",
        description = "在对方手机的日历里加一条日程，加在「Cleos」这个日历里。对方让你记个安排、到时候提醒的时候用；要提醒就带 remind。",
        parameters = schema(
            required = listOf("title", "start"),
            "title" to prop("string", "什么事，一句话"),
            "start" to prop("string", "开始：YYYY-MM-DD HH:MM；全天的只写日期 YYYY-MM-DD"),
            "end" to prop("string", "可不填。结束，格式同上；不填就是一小时（全天的就是那一天）"),
            "location" to prop("string", "可不填。地点"),
            "notes" to prop("string", "可不填。备注"),
            "remind" to prop("integer", "可不填。提前几分钟提醒：到点提醒填 0，提前半小时填 30；不填就不提醒"),
        ),
    )
    val updateEvent = ToolSpec(
        name = "update_event",
        groups = setOf(ToolGroup.Calendar),
        action = "改日程",
        description = "改一条你加的日程（在「Cleos」日历里的）：只填要改的项。对方自己的日程改不了。",
        parameters = schema(
            required = listOf("id"),
            "id" to prop("integer", "日程编号，来自 read_calendar 或 add_event 的结果"),
            "title" to prop("string", "新的事由"),
            "start" to prop("string", "新的开始：YYYY-MM-DD HH:MM，或全天的 YYYY-MM-DD"),
            "end" to prop("string", "新的结束，格式同上"),
            "location" to prop("string", "新的地点；要去掉就填 none"),
            "notes" to prop("string", "新的备注；要去掉就填 none"),
            "remind" to prop("string", "提前几分钟提醒；要去掉提醒就填 none"),
        ),
    )
    val deleteEvent = ToolSpec(
        name = "delete_event",
        groups = setOf(ToolGroup.Calendar),
        action = "删日程",
        description = "删一条你加的日程（在「Cleos」日历里的）。对方自己的日程删不了。",
        parameters = schema(
            required = listOf("id"),
            "id" to prop("integer", "日程编号，来自 read_calendar 或 add_event 的结果"),
        ),
    )

    val musicControl = ToolSpec(
        name = "music_control",
        groups = setOf(ToolGroup.Music),
        action = "切歌",
        description = "控制对方手机上正在放的音乐（网易云、QQ 音乐这些都行）：暂停、接着放、下一首、上一首。" +
            "对方让你停一下、接着放、换一首的时候用；结果里有现在放的是哪首。",
        parameters = schema(
            required = listOf("action"),
            "action" to prop("string", "pause 暂停，play 接着放，next 下一首，previous 上一首"),
        ),
    )

    val all = listOf(
        sendMessage,
        sendVoice,
        addTodo,
        listTodos,
        updateTodo,
        readDiary,
        writeDiary,
        shareMySecret,
        listSecrets,
        requestSecret,
        readLetters,
        memory,
        lore,
        setMyAvatar,
        getWeather,
        getLocation,
        noteForLater,
        planNextVisit,
        setAlarm,
        setTimer,
        readCalendar,
        addEvent,
        updateEvent,
        deleteEvent,
        musicControl,
        patUser,
        reactMessage,
        readFeed,
        publishFeed,
        likeFeed,
        commentFeed,
    )
    val byName = all.associateBy { it.name }

    /** What to offer for the groups that are on, each worded for what it can reach. */
    fun offered(groups: Set<ToolGroup>): List<ToolSpec> = all
        .filter { spec -> spec.groups.any { it in groups } }
        .map { if (it.name == readDiary.name) it.copy(description = readDiaryDescription(groups)) else it }

    private fun readDiaryDescription(groups: Set<ToolGroup>): String {
        val whose = when {
            ToolGroup.Diary in groups && ToolGroup.AiDiary in groups -> "读日记本：对方写的和你自己写的都在里面，小秘密除外。"
            ToolGroup.Diary in groups -> "读对方写的日记，小秘密除外。"
            else -> "读你自己以前写的日记。"
        }
        return whose + "给 date 读那一天的，给 query 按关键词找，都不给就读最近几篇。"
    }

    private fun prop(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun schema(vararg props: Pair<String, JsonObject>) = schema(emptyList(), *props)

    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (name, p) -> put(name, p) } }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    }
}

interface WeatherSource {
    /** Throws [ToolFailure] when the place is unknown or the service can't be reached. */
    suspend fun report(city: String, days: Int): WeatherReport
}

/** [place] as the chat names it ("杭州"); [text] is what the model reads. */
data class WeatherReport(val place: String, val text: String)

/** Where the model's own avatar is kept; the app side of set_my_avatar. */
interface SelfAvatar {
    /** The file of the picture [ref] names in this conversation ("latest", "#45-1"), if there is one. */
    suspend fun picture(conversationId: Long, ref: String): String?

    /** Crops [file] and makes it TA [companionId]'s avatar; false when the picture can't be read. */
    suspend fun usePicture(companionId: Long, file: String): Boolean

    suspend fun useEmoji(companionId: Long, emoji: String)
}

/**
 * Runs what the model asked for. Whether a group is allowed is checked here again, at the
 * moment of the call, not only when the tools are offered: the history may hold calls
 * from before a switch was turned off, and a model can call a tool it was not offered.
 */
class ToolBox(
    private val todos: TodoDao,
    private val diary: DiaryDao,
    private val weather: WeatherSource,
    /** One TA's requests to see a secret so far, oldest first, from any of their conversations. */
    private val requests: suspend (companionId: Long) -> List<SecretRequest> = { emptyList() },
    private val avatar: SelfAvatar? = null,
    /** The letters between one TA and the person, any order. */
    private val letters: suspend (companionId: Long) -> List<LetterEntity> = { emptyList() },
    memories: MemoryDao? = null,
    /** A TA's 设定: a world book brought over from elsewhere, looked up rather than always carried. */
    lore: LoreDao? = null,
    /** Where the phone is, for get_location. */
    private val location: LocationSource? = null,
    /** Where note_for_later keeps its notes; asked for at the call, since it is made after this. */
    private val later: () -> LaterBook? = { null },
    /** The phone's clock app, for set_alarm and set_timer. */
    private val alarms: AlarmSource? = null,
    /** The phone's calendar. */
    private val calendar: CalendarSource? = null,
    /** Whatever the phone is playing, for music_control. */
    private val music: MusicSource? = null,
    /** Leaves a pat from the TA in a conversation (pat_user); the chat shows it, so the tool has no line of its own. */
    private val patBack: suspend (conversationId: Long, suffix: String) -> Unit = { _, _ -> },
    private val reactBack: suspend (Long, Long, String, Boolean) -> Unit = { _, _, _, _ -> error("表情回应不可用") },
    private val planVisit: suspend (Long, Int) -> String = { _, _ -> throw ToolFailure("想找你聊不可用", "") },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val feed: FeedBook? = null,
    private val feedActions: FeedActions? = null,
) {
    private val book = memories?.let { MemoryBook(it, clock) }
    private val loreBook = lore?.let { LoreBook(it) }

    fun specs(groups: Set<ToolGroup>): List<ToolSpec> = ToolSpecs.offered(groups)

    /** "在…" while [name] runs; null for a quiet one, which shows only the dots. */
    fun activity(name: String): String? = if (name in ToolSpecs.quiet) null else ToolSpecs.byName[name]?.activity ?: "在用工具"

    fun action(name: String): String = ToolSpecs.byName[name]?.action ?: "用工具"

    /** [companionId]: the TA making the call; what it can read and change is theirs. */
    suspend fun run(
        call: ToolCall,
        settings: AppSettings,
        conversationId: Long = 0,
        companionId: Long = Companions.FIRST,
    ): ToolOutcome {
        val spec = ToolSpecs.byName[call.name]
            ?: return ToolOutcome("没有叫 ${call.name} 的工具。", "想用的工具不存在：${call.name}")
        // A quiet tool's failures leave no line either: "记一笔没成" would say there was something to note.
        fun failed(result: String, why: String) = ToolOutcome(result, if (spec.name in ToolSpecs.quiet) "" else "${spec.action}没成：$why")
        if (spec.groups.none { it in settings.tools }) return failed("对方在设置里关掉了这项功能，现在用不了。", "设置里关着")
        val args = ToolArgs.parse(call.arguments) ?: return failed("参数不是合法的 JSON 对象，按参数说明重新调用。", "参数写错了")
        // From the same clock as every "now" in here, not the wall clock beside it.
        val today = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()
        return try {
            when (spec.name) {
                ToolSpecs.readFeed.name -> (feed ?: throw ToolFailure("当前无法读取 App 内朋友圈，请让对方转发帖子到聊天。", "暂时读不到")).read(args, companionId, settings.userName)
                ToolSpecs.publishFeed.name, ToolSpecs.likeFeed.name, ToolSpecs.commentFeed.name ->
                    (feedActions ?: throw ToolFailure("朋友圈操作暂时不可用，没有发布或修改任何内容。", "暂时用不了")).run(spec.name, args, companionId)
                ToolSpecs.addTodo.name -> addTodo(args, today)
                ToolSpecs.listTodos.name -> listTodos(args, today)
                ToolSpecs.updateTodo.name -> updateTodo(args, today)
                ToolSpecs.readDiary.name -> readDiary(args, today, settings.tools, companionId)
                ToolSpecs.writeDiary.name -> writeDiary(args, today, companionId)
                ToolSpecs.shareMySecret.name -> shareMySecret(args, companionId)
                ToolSpecs.listSecrets.name -> listSecrets(today, companionId)
                ToolSpecs.requestSecret.name -> requestSecret(args, companionId)
                ToolSpecs.setMyAvatar.name -> setMyAvatar(args, conversationId, companionId)
                // Sent messages become bubbles in ChatRepository; this is only reached by mistake.
                ToolSpecs.sendMessage.name, ToolSpecs.sendVoice.name -> ToolOutcome(ToolSpecs.SENT, "")
                ToolSpecs.readLetters.name -> readLetters(args, today, companionId)
                ToolSpecs.memory.name -> (book ?: throw ToolFailure("现在记不了。", "这里记不了")).act(args, companionId)
                ToolSpecs.lore.name -> (loreBook ?: throw ToolFailure("现在查不了设定。", "这里查不了")).act(args, companionId)
                ToolSpecs.getLocation.name -> getLocation()
                ToolSpecs.noteForLater.name -> noteForLater(args, conversationId, companionId)
                ToolSpecs.planNextVisit.name -> {
                    val minutes = ToolArgs.int(args["minutes"])?.takeIf { it > 0 }
                        ?: throw ToolFailure("minutes 必须是正整数", "")
                    ToolOutcome(planVisit(conversationId, minutes), "")
                }
                ToolSpecs.setAlarm.name -> setAlarm(args)
                ToolSpecs.setTimer.name -> setTimer(args)
                ToolSpecs.readCalendar.name -> readCalendar(args, today)
                ToolSpecs.addEvent.name -> addEvent(args, today)
                ToolSpecs.updateEvent.name -> updateEvent(args, today)
                ToolSpecs.deleteEvent.name -> deleteEvent(args, today)
                ToolSpecs.musicControl.name -> musicControl(args)
                ToolSpecs.patUser.name -> patUser(args, conversationId)
                ToolSpecs.reactMessage.name -> {
                    val id = (args["message_id"] as? JsonPrimitive)?.longOrNull ?: throw ToolFailure("需要合法消息编号。", "")
                    val emoji = ToolArgs.text(args, "emoji").orEmpty().trim()
                    if (emoji !in com.cleo.cleos.data.MessageReactions.ALL)
                        throw ToolFailure("支持的表情：" + com.cleo.cleos.data.MessageReactions.ALL.joinToString(" "), "")
                    reactBack(conversationId, id, emoji, ToolArgs.bool(args, "remove") == true)
                    ToolOutcome("表情回应已完成。", "")
                }
                else -> getWeather(args, settings)
            }
        } catch (f: ToolFailure) {
            failed(f.result, f.note)
        }
    }

    private suspend fun musicControl(a: JsonObject): ToolOutcome {
        val phone = music ?: throw ToolFailure("这里控制不了音乐。", "这里控制不了")
        if (!phone.allowed()) {
            throw ToolFailure("对方还没给 Cleos 开「通知使用权」，看不到也控制不了在放的音乐；要的话，请对方在设置「一起听歌」里打开。", "没开通知使用权")
        }
        val action = MusicText.action(ToolArgs.text(a, "action"))
            ?: throw ToolFailure("action 只能是 pause、play、next、previous 里的一个。", "没说要怎么切")
        val before = phone.now() ?: throw ToolFailure("对方手机上现在没有在放的音乐。", "没在放歌")
        phone.control(action)
        val after = settled(phone, before, action)
        return ToolOutcome(MusicText.done(action, before, after), MusicText.note(action, before, after))
    }

    /** What plays once the player has done it: another song takes a moment to show. */
    private suspend fun settled(phone: MusicSource, before: NowPlaying, action: MusicAction): NowPlaying? {
        val changesSong = action == MusicAction.Next || action == MusicAction.Previous
        repeat(if (changesSong) SONG_CHANGE_LOOKS else 1) {
            delay(SONG_CHANGE_STEP_MS)
            val now = phone.now()
            if (!changesSong || (now != null && now.song != before.song)) return now
        }
        return phone.now()
    }

    private fun setAlarm(a: JsonObject): ToolOutcome {
        val phone = alarms ?: throw ToolFailure("这里定不了闹钟。", "这里定不了")
        val time = AlarmText.time(ToolArgs.text(a, "time") ?: throw ToolFailure("缺少 time：几点，写成 HH:MM。", "没说几点"))
        val days = AlarmText.days(ToolArgs.text(a, "repeat"))
        val label = ToolArgs.text(a, "label").orEmpty().trim().take(ALARM_LABEL_MAX)
        phone.setAlarm(time.hour, time.minute, label, days)
        val now = Instant.ofEpochMilli(clock()).atZone(zone())
        val at = if (days.isEmpty()) LaterRules.at(AlarmText.rings(time, now), now) else "${AlarmText.repeatName(days)} $time"
        val what = label.takeIf { it.isNotEmpty() }?.let { "「$it」" }.orEmpty()
        return ToolOutcome("闹钟定好了：$at$what。在对方手机自带的时钟里，响铃、贪睡、关掉都在那里。", "定了闹钟：$at$what")
    }

    private fun setTimer(a: JsonObject): ToolOutcome {
        val phone = alarms ?: throw ToolFailure("这里开不了计时器。", "这里开不了")
        val minutes = (a["minutes"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.toDoubleOrNull()
            ?: throw ToolFailure("缺少 minutes：多少分钟，写个数。", "没说多久")
        val seconds = (minutes * 60).roundToInt()
        if (seconds !in 1..TIMER_MAX) throw ToolFailure("计时器要在 1 秒到 24 小时之间。", "时长不对")
        val label = ToolArgs.text(a, "label").orEmpty().trim().take(ALARM_LABEL_MAX)
        phone.setTimer(seconds, label)
        val what = label.takeIf { it.isNotEmpty() }?.let { "「$it」" }.orEmpty()
        return ToolOutcome("计时器开始了：${AlarmText.span(seconds)}$what，到点手机的时钟会响。", "开了计时器：${AlarmText.span(seconds)}$what")
    }

    private suspend fun readCalendar(a: JsonObject, today: LocalDate): ToolOutcome {
        val cal = calendar ?: throw ToolFailure("这里看不了日历。", "这里看不了")
        val from = ToolArgs.optionalDay(a, "from", today) ?: today
        val days = (ToolArgs.int(a["days"]) ?: 7).coerceIn(1, CALENDAR_DAYS_MAX)
        val last = from.plusDays(days - 1L)
        val z = zone()
        val all = cal.events(from.atStartOfDay(z).toInstant().toEpochMilli(), last.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli())
        val query = ToolArgs.text(a, "query")?.trim().orEmpty()
        val found = if (query.isEmpty()) all else all.filter { query in it.title || query in it.location || query in it.notes }
        val range = if (days == 1) CalendarText.dayName(from, today) else "${CalendarText.dayName(from, today)}到${CalendarText.dayName(last, today)}"
        return ToolOutcome(CalendarText.list(found.take(CALENDAR_LIST_MAX), from, last, z, today), "看了日历：$range")
    }

    private suspend fun addEvent(a: JsonObject, today: LocalDate): ToolOutcome {
        val cal = calendar ?: throw ToolFailure("这里加不了日程。", "这里加不了")
        val title = ToolArgs.text(a, "title").orEmpty().trim().take(TITLE_MAX)
        if (title.isEmpty()) throw ToolFailure("缺少 title：什么事。", "没有内容")
        val startText = ToolArgs.text(a, "start")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ToolFailure("缺少 start：什么时候，写成 YYYY-MM-DD HH:MM。", "没说什么时候")
        val start = CalendarText.parse(startText, today)
        val end = optional(a, "end")?.let { CalendarText.parse(it, today) }
        val (begin, finish) = CalendarText.span(start, end, zone())
        val remind = ToolArgs.int(a["remind"])?.coerceIn(0, REMIND_MAX)
        val draft = EventDraft(title, begin, finish, start.allDay, optional(a, "location"), optional(a, "notes"), remind)
        val id = cal.add(draft)
        val event = CalEvent(id, title, begin, finish, start.allDay, draft.location.orEmpty(), draft.notes.orEmpty(), "Cleos", ours = true)
        val at = CalendarText.whenText(event, zone(), today)
        val reminder = remindText(remind)
        return ToolOutcome(
            "加好了：${CalendarText.line(event, zone(), today)}$reminder。在对方手机日历里「Cleos」这个日历中。",
            "加了日程「$title」· $at$reminder",
        )
    }

    private suspend fun updateEvent(a: JsonObject, today: LocalDate): ToolOutcome {
        val cal = calendar ?: throw ToolFailure("这里改不了日程。", "这里改不了")
        val id = ToolArgs.id(a["id"]) ?: throw ToolFailure("缺少 id：日程编号，来自 read_calendar。", "没说是哪条")
        val old = cal.event(id) ?: throw ToolFailure("日历里没有 #$id 这条，先用 read_calendar 看看编号。", "没有这条日程")
        notTheirs(old)
        val z = zone()
        val startText = optional(a, "start")
        val endText = optional(a, "end")
        // Moving the start keeps the length it had, unless an end comes with it.
        val oldStart = if (old.allDay) {
            CalendarText.When(Instant.ofEpochMilli(old.begin).atZone(java.time.ZoneOffset.UTC).toLocalDate(), null)
        } else {
            Instant.ofEpochMilli(old.begin).atZone(z).let { CalendarText.When(it.toLocalDate(), it.toLocalTime()) }
        }
        val start = startText?.let { CalendarText.parse(it, today) } ?: oldStart
        val span = when {
            endText != null -> CalendarText.span(start, CalendarText.parse(endText, today), z)
            startText != null && start.allDay == old.allDay -> CalendarText.span(start, null, z).let { (b, _) -> b to b + (old.end - old.begin) }
            startText != null -> CalendarText.span(start, null, z)
            else -> null
        }
        val remindArg = ToolArgs.text(a, "remind")?.trim()?.takeIf { it.isNotEmpty() }
        val remind = when {
            remindArg == null -> null
            ToolArgs.isNone(remindArg) -> EventDraft.NO_REMINDER
            else -> remindArg.toIntOrNull()?.coerceIn(0, REMIND_MAX) ?: throw ToolFailure("remind 写成分钟数，要去掉就填 none。", "提醒没写对")
        }
        val draft = EventDraft(
            title = ToolArgs.text(a, "title")?.trim()?.takeIf { it.isNotEmpty() }?.take(TITLE_MAX),
            begin = span?.first,
            end = span?.second,
            allDay = if (span != null) start.allDay else null,
            location = ToolArgs.text(a, "location")?.trim()?.let { if (ToolArgs.isNone(it)) "" else it },
            notes = ToolArgs.text(a, "notes")?.trim()?.let { if (ToolArgs.isNone(it)) "" else it },
            remind = remind,
        )
        cal.update(id, draft)
        val now = cal.event(id) ?: old
        return ToolOutcome("改好了：${CalendarText.line(now, z, today)}${remindText(remind)}。", "改了日程「${now.title}」· ${CalendarText.whenText(now, z, today)}")
    }

    private suspend fun deleteEvent(a: JsonObject, today: LocalDate): ToolOutcome {
        val cal = calendar ?: throw ToolFailure("这里删不了日程。", "这里删不了")
        val id = ToolArgs.id(a["id"]) ?: throw ToolFailure("缺少 id：日程编号，来自 read_calendar。", "没说是哪条")
        val old = cal.event(id) ?: throw ToolFailure("日历里没有 #$id 这条，先用 read_calendar 看看编号。", "没有这条日程")
        notTheirs(old)
        cal.delete(id)
        return ToolOutcome("删掉了：${CalendarText.line(old, zone(), today)}。", "删了日程「${old.title}」")
    }

    /** The person's own events are only read (see CalendarSource); the calendar says so again below this. */
    private fun notTheirs(e: CalEvent) {
        if (!e.ours) {
            throw ToolFailure(
                "#${e.id} 是对方自己的日程（在「${e.calendar.ifBlank { "日历" }}」里），你只能看，不能改也不能删。需要的话请对方自己改。",
                "那条是对方自己的",
            )
        }
    }

    /** A text argument that says something: absent, blank and "none" are all nothing. */
    private fun optional(a: JsonObject, key: String): String? =
        ToolArgs.text(a, key)?.trim()?.takeIf { it.isNotEmpty() && !ToolArgs.isNone(it) }

    private fun remindText(minutes: Int?): String = when (minutes) {
        null -> ""
        EventDraft.NO_REMINDER -> "，不再提醒"
        0 -> "，到点提醒"
        else -> "，提前 ${LaterRules.span(minutes.toLong())}提醒"
    }

    private suspend fun patUser(a: JsonObject, conversationId: Long): ToolOutcome {
        patBack(conversationId, Pats.cleanSuffix(ToolArgs.text(a, "suffix").orEmpty()))
        return ToolOutcome("拍了拍对方，对方的手机会震一下，聊天里已经有这一行了，不用再说明。", "")
    }

    private suspend fun noteForLater(a: JsonObject, conversationId: Long, companionId: Long): ToolOutcome {
        val notes = later() ?: throw ToolFailure("现在记不了。", "这里记不了")
        val what = ToolArgs.text(a, "what").orEmpty().trim().take(LATER_WHAT_MAX)
        if (what.isEmpty()) throw ToolFailure("缺少 what：写上想说或想问的事。", "没有内容")
        val minutes = ToolArgs.int(a["minutes"]) ?: throw ToolFailure("缺少 minutes：多少分钟以后，写个整数。", "没说多久")
        val why = ToolArgs.text(a, "why").orEmpty().trim().take(LATER_WHY_MAX)
        return ToolOutcome(notes.note(companionId, conversationId, what, why, minutes), "")
    }

    private suspend fun addTodo(a: JsonObject, today: LocalDate): ToolOutcome {
        val title = ToolArgs.text(a, "title").orEmpty().trim().take(TITLE_MAX)
        if (title.isEmpty()) throw ToolFailure("缺少 title。", "没有内容")
        val due = ToolArgs.optionalDay(a, "due", today)
        val note = ToolArgs.text(a, "note").orEmpty().trim().take(NOTE_MAX)
        val todo = TodoEntity(title = title, note = note, dueDay = due?.toEpochDay(), createdAt = clock())
        val id = todos.insert(todo)
        return ToolOutcome(
            "已添加：" + describe(todo.copy(id = id), today),
            "记下了待办「$title」" + (due?.let { " · " + Describe.monthDay(it) } ?: ""),
        )
    }

    private suspend fun listTodos(a: JsonObject, today: LocalDate): ToolOutcome {
        val all = todos.all()
        // The same order as the todo screen: dated first by date, then in the order added.
        val pending = all.filter { !it.done }
            .sortedWith(compareBy<TodoEntity> { it.dueDay ?: Long.MAX_VALUE }.thenBy { it.createdAt })
        val withDone = ToolArgs.bool(a, "include_done") == true
        val done = all.filter { it.done }.sortedByDescending { it.doneAt ?: 0L }.take(DONE_LIST_MAX)
        val text = buildString {
            if (pending.isEmpty()) {
                append(if (all.isEmpty()) "待办清单是空的。" else "没有没做完的待办。")
            } else {
                append("没做完的 ${pending.size} 条：")
                pending.take(LIST_MAX).forEach { append('\n').append(describe(it, today)) }
                if (pending.size > LIST_MAX) append("\n……还有 ${pending.size - LIST_MAX} 条没列出")
            }
            if (withDone && done.isNotEmpty()) {
                append("\n最近做完的 ${done.size} 条：")
                done.forEach { append('\n').append(describe(it, today)) }
            }
        }
        val note = when {
            all.isEmpty() -> "看了一眼待办：清单是空的"
            pending.isEmpty() -> "看了一眼待办：都做完了"
            else -> "看了一眼待办（${pending.size} 条没做完）"
        }
        return ToolOutcome(text, note)
    }

    private suspend fun updateTodo(a: JsonObject, today: LocalDate): ToolOutcome {
        val id = ToolArgs.id(a["id"]) ?: throw ToolFailure("缺少 id。先用 list_todos 找到编号。", "不知道是哪一条")
        val old = todos.get(id) ?: throw ToolFailure("没有 #$id 这条待办。先用 list_todos 看看现在有哪些。", "没找到这一条")
        var t = old
        // Empty strings change nothing. Some models fill every optional field with ""
        // and would otherwise wipe dates and notes the call never meant to touch.
        ToolArgs.text(a, "title")?.trim()?.takeIf { it.isNotEmpty() }?.let { t = t.copy(title = it.take(TITLE_MAX)) }
        ToolArgs.text(a, "note")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            t = t.copy(note = if (ToolArgs.isNone(it)) "" else it.take(NOTE_MAX))
        }
        ToolArgs.text(a, "due")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            t = t.copy(dueDay = if (ToolArgs.isNone(it)) null else ToolArgs.day(it, today).toEpochDay())
        }
        ToolArgs.bool(a, "done")?.let { done ->
            if (done != t.done) t = t.copy(done = done, doneAt = if (done) clock() else null)
        }
        if (t == old) return ToolOutcome("没有变化：" + describe(old, today), "看了看待办「${old.title}」")
        todos.upsert(t)
        val note = when {
            t.done && !old.done -> "把「${t.title}」打了勾"
            !t.done && old.done -> "把「${t.title}」改回没做完"
            else -> "改了待办「${t.title}」"
        }
        return ToolOutcome("已更新：" + describe(t, today), note)
    }

    private suspend fun readDiary(a: JsonObject, today: LocalDate, groups: Set<ToolGroup>, companionId: Long): ToolOutcome {
        // The person's entries only with their permission; this TA's own always, and no
        // other TA's. Secrets are kept out by the queries themselves, not by a filter here
        // that could be missed.
        val mine = ToolGroup.Diary in groups
        val own = if (ToolGroup.AiDiary in groups) companionId else -1L
        val date = ToolArgs.optionalDay(a, "date", today)
        val query = ToolArgs.text(a, "query").orEmpty().trim()
        val limit = (ToolArgs.int(a["limit"]) ?: 3).coerceIn(1, 10)
        val entries = when {
            date != null -> diary.onDay(date.toEpochDay(), mine, own).take(limit)
            query.isNotEmpty() -> diary.search(ToolArgs.likePattern(query), mine, own, SEARCH_CANDIDATES)
                .filter { Describe.diaryContains(it, query) }
                .take(limit)
            else -> diary.recent(mine, own, limit)
        }
        // A secret written that day is mentioned, never shown: the model can't ask about
        // what it doesn't know is there.
        val locked = if (date != null && ToolGroup.Secrets in groups) diary.secretsOnDay(date.toEpochDay()) else 0
        val lockedLine = if (locked > 0) "这天对方还写了 $locked 个小秘密，锁着，你看不到。" else ""
        if (entries.isEmpty()) {
            val nobody = when {
                mine && own < 0 -> "对方"
                !mine -> "你"
                else -> ""
            }
            return when {
                date != null -> ToolOutcome(
                    "${Describe.date(date, today)}${nobody}没有写日记。$lockedLine",
                    "找了${Describe.monthDay(date)}的日记：" + if (locked > 0) "只有小秘密" else "那天没写",
                )
                query.isNotEmpty() -> ToolOutcome("日记里没有找到「$query」。", "在日记里找了「$query」：没找到")
                else -> ToolOutcome("${nobody.ifEmpty { "日记本里" }}还没有写过日记。", "翻了翻日记：还没有")
            }
        }
        val note = when {
            date != null -> "读了${Describe.monthDay(date)}的日记"
            query.isNotEmpty() -> "在日记里找了「$query」（${entries.size} 篇）"
            else -> "翻了最近的 ${entries.size} 篇日记"
        }
        val text = Describe.diary(entries, today) + if (lockedLine.isEmpty()) "" else "\n\n$lockedLine"
        return ToolOutcome(text, note)
    }

    private suspend fun writeDiary(a: JsonObject, today: LocalDate, companionId: Long): ToolOutcome {
        val text = ToolArgs.text(a, "text").orEmpty().trim().take(DIARY_MAX)
        if (text.isEmpty()) throw ToolFailure("缺少 text。", "没有内容")
        val title = ToolArgs.text(a, "title").orEmpty().trim().take(TITLE_MAX)
        val now = clock()
        val id = diary.insert(
            DiaryEntryEntity(
                day = today.toEpochDay(),
                title = title,
                blocks = DiaryBlocks.encode(listOf(DiaryBlock.Text(text))),
                createdAt = now,
                updatedAt = now,
                author = DiaryEntryEntity.AUTHOR_AI,
                companionId = companionId,
                secret = ToolArgs.bool(a, "secret") == true,
                publicHint = ToolArgs.text(a, "public_hint").orEmpty().trim().take(120),
            ),
        )
        return ToolOutcome(
            "写好了，记在 ${Describe.date(today, today)}：#$id " + if (ToolArgs.bool(a, "secret") == true) "（已上锁的小秘密）" else title.ifEmpty { "（没有标题）" },
            if (ToolArgs.bool(a, "secret") == true) "写下了一个小秘密" else if (title.isEmpty()) "写了一篇日记" else "写了一篇日记「$title」",
        )
    }

    private suspend fun shareMySecret(a: JsonObject, companionId: Long): ToolOutcome {
        val id = ToolArgs.id(a["id"]) ?: throw ToolFailure("缺少日记编号。", "不知道是哪篇")
        val entry = diary.get(id)?.takeIf { it.author == DiaryEntryEntity.AUTHOR_AI && it.companionId == companionId && it.secret }
            ?: throw ToolFailure("只能分享你自己的小秘密。", "没找到你的这篇秘密")
        val mode = ToolArgs.text(a, "mode") ?: "full"
        if (mode !in setOf("full", "partial")) throw ToolFailure("mode 只能是 full 或 partial。", "分享方式写错了")
        if (mode == "partial") {
            val excerpt = ToolArgs.text(a, "excerpt").orEmpty().trim()
            val body = DiaryBlocks.plainText(DiaryBlocks.decode(entry.blocks))
            if (excerpt.isEmpty() || excerpt.length > 2000 || !body.contains(excerpt))
                throw ToolFailure("excerpt 必须是这篇日记的一段连续原文，1 到 2000 字。先 read_diary 看原文再选。", "摘录不是有效原文")
            diary.update(entry.copy(sharedExcerpt = excerpt, updatedAt = clock()))
            return ToolOutcome("已分享 #$id 的这段摘录，未解锁的全文继续上锁。", "愿意告诉你一点", sharedDiaryId = id, sharedExcerpt = excerpt)
        }
        if (!entry.secretShared) diary.update(entry.copy(secretShared = true, updatedAt = clock()))
        return ToolOutcome("已解锁 #$id，对方现在可以在日记里查看。", "愿意把这篇小秘密给你看了", sharedDiaryId = id)
    }

    private suspend fun listSecrets(today: LocalDate, companionId: Long): ToolOutcome {
        val secrets = diary.secrets()
        if (secrets.isEmpty()) return ToolOutcome("对方现在没有小秘密。", "数了数你的小秘密：还没有")
        // The latest request about each one: later ones replace earlier ones.
        val asked = requests(companionId).associateBy { it.diaryId }
        val text = buildString {
            append("对方有 ${secrets.size} 个小秘密，标题和内容你都看不到；想看就用 request_secret 问。")
            for (e in secrets) {
                append("\n#").append(e.id).append(' ').append(Describe.date(LocalDate.ofEpochDay(e.day), today))
                when (asked[e.id]?.status) {
                    SecretRequest.PENDING -> append(" · 你问过了，还在等对方决定")
                    SecretRequest.GRANTED -> append(" · 对方给你看过一次")
                    SecretRequest.DECLINED -> append(" · 你问过，对方没给看")
                }
            }
        }
        return ToolOutcome(text, "数了数你的小秘密：${secrets.size} 个")
    }

    private suspend fun requestSecret(a: JsonObject, companionId: Long): ToolOutcome {
        val id = ToolArgs.id(a["id"]) ?: throw ToolFailure("缺少 id。先用 list_secrets 看看有哪些。", "不知道是哪一个")
        val entry = diary.get(id)?.takeIf { it.secret && it.author == DiaryEntryEntity.AUTHOR_ME }
            ?: throw ToolFailure("#$id 不是小秘密。先用 list_secrets 看看有哪些。", "没找到这个小秘密")
        // One card per secret at a time: asking again while the first card waits is nagging.
        if (requests(companionId).any { it.diaryId == id && it.status == SecretRequest.PENDING }) {
            return ToolOutcome("这个你已经问过了，对方还没决定，先别再问。", "")
        }
        val reason = ToolArgs.text(a, "reason").orEmpty().trim().take(REASON_MAX)
        return ToolOutcome(
            "请求已经发给对方了，对方会在聊天里决定给不给你看。同意了，内容会跟着对方的下一条消息给你。现在别追问，简单说一句就好。",
            "",
            SecretRequest(diaryId = id, day = entry.day, title = entry.title, reason = reason),
        )
    }

    private suspend fun setMyAvatar(a: JsonObject, conversationId: Long, companionId: Long): ToolOutcome {
        val port = avatar ?: throw ToolFailure("现在换不了头像。", "这里换不了")
        val image = ToolArgs.text(a, "image")?.trim().orEmpty()
        val emoji = ToolArgs.text(a, "emoji")?.trim().orEmpty()
        return when {
            image.isNotEmpty() -> {
                val file = port.picture(conversationId, image)
                    ?: throw ToolFailure("找不到「$image」这张图。用对方发来的图的编号（像 #45-1），或者写 latest。", "找不到那张图")
                if (!port.usePicture(companionId, file)) throw ToolFailure("这张图读不出来，换一张。", "那张图读不出来")
                ToolOutcome("换好了：现在的头像是对方发来的那张图。", "换了新头像")
            }
            emoji.isNotEmpty() -> {
                // An emoji is a few code points at most (a family, a flag); a sentence is not an avatar.
                if (emoji.codePointCount(0, emoji.length) > EMOJI_MAX) throw ToolFailure("emoji 只放一个表情。", "表情太长了")
                port.useEmoji(companionId, emoji)
                ToolOutcome("换好了：现在的头像是 $emoji。", "换了新头像：$emoji")
            }
            else -> throw ToolFailure("image 和 emoji 给一个。", "没说换成什么")
        }
    }

    /**
     * What has arrived: the person's sent letters and the TA's delivered ones. Drafts are
     * not letters yet, and a letter still on its way is not in the person's hands.
     */
    private suspend fun readLetters(a: JsonObject, today: LocalDate, companionId: Long): ToolOutcome {
        val now = clock()
        val limit = (ToolArgs.int(a["limit"]) ?: 3).coerceIn(1, 10)
        val shown = letters(companionId)
            .filter { !it.draft && (it.author == LetterEntity.AUTHOR_ME || (it.deliverAt ?: Long.MAX_VALUE) <= now) }
            .sortedByDescending { it.deliverAt ?: it.createdAt }
            .take(limit)
        if (shown.isEmpty()) return ToolOutcome("你们还没有写过信。", "翻了翻信：还没有")
        val text = shown.joinToString("\n\n") { l ->
            val mine = l.author == LetterEntity.AUTHOR_AI
            val day = Instant.ofEpochMilli(l.deliverAt ?: l.createdAt).atZone(zone()).toLocalDate()
            val unread = if (mine && l.readAt == null) "（对方还没拆开）" else ""
            val body = l.content.trim().let { if (it.length > LETTER_MAX) it.take(LETTER_MAX) + "……（后面还有 ${it.length - LETTER_MAX} 字）" else it }
            "【${Describe.date(day, today)} · ${if (mine) "你写的" else "对方写的"}】$unread\n$body"
        }
        return ToolOutcome(text, "翻了翻你们的信（${shown.size} 封）")
    }

    /** The chat line names the area, so the person sees each time the TA looked. */
    private suspend fun getLocation(): ToolOutcome {
        val place = (location ?: throw ToolFailure("这里查不了位置。", "这里查不了")).here()
        return ToolOutcome(Locations.describe(place), "查了你的位置" + (place.area?.let { "：$it" } ?: ""))
    }

    private suspend fun getWeather(a: JsonObject, settings: AppSettings): ToolOutcome {
        val city = ToolArgs.text(a, "city")?.trim().orEmpty().ifEmpty { settings.weatherCity.trim() }
        if (city.isEmpty()) {
            throw ToolFailure("不知道对方在哪个城市。先问一下，再带上 city 查。", "不知道在哪个城市")
        }
        val days = (ToolArgs.int(a["days"]) ?: 3).coerceIn(1, 7)
        val report = weather.report(city, days)
        return ToolOutcome(report.text, "查了${report.place}的天气")
    }

    private fun describe(t: TodoEntity, today: LocalDate) = Describe.todo(t, today, zone())

    private companion object {
        const val TITLE_MAX = 200
        const val NOTE_MAX = 1000
        const val LIST_MAX = 50
        const val DONE_LIST_MAX = 10
        const val SEARCH_CANDIDATES = 60
        const val DIARY_MAX = 5000
        const val REASON_MAX = 120
        const val EMOJI_MAX = 8
        const val LETTER_MAX = 2000
        const val LATER_WHAT_MAX = 200
        const val LATER_WHY_MAX = 200
        const val ALARM_LABEL_MAX = 60
        const val SONG_CHANGE_LOOKS = 12
        const val SONG_CHANGE_STEP_MS = 150L
        const val TIMER_MAX = 24 * 3600
        const val CALENDAR_DAYS_MAX = 31
        const val CALENDAR_LIST_MAX = 60
        const val REMIND_MAX = 7 * 24 * 60
    }
}

/** Reading what the model wrote. Lenient where models are commonly sloppy. */
internal object ToolArgs {
    private val json = Json { ignoreUnknownKeys = true }

    /** Blank means no arguments: some models send "" for a call without parameters. */
    fun parse(raw: String): JsonObject? {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    fun text(a: JsonObject, key: String): String? = (a[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    fun bool(a: JsonObject, key: String): Boolean? =
        (a[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }

    fun int(e: JsonElement?): Int? =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.content.trim().toIntOrNull() }

    /** 12, "12" and "#12" all mean #12: the ids are shown to the model with a #. */
    fun id(e: JsonElement?): Long? =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.content.trim().removePrefix("#").toLongOrNull() }

    fun isNone(s: String) = s.equals("none", ignoreCase = true) || s == "无"

    /** Absent, blank or "none" is no date. */
    fun optionalDay(a: JsonObject, key: String, today: LocalDate): LocalDate? {
        val s = text(a, key)?.trim().orEmpty()
        return if (s.isEmpty() || isNone(s)) null else day(s, today)
    }

    /**
     * YYYY-MM-DD, also with one-digit month or day, or / as the separator. M-D alone means
     * this year. Anything else is sent back with an example rather than guessed at.
     */
    fun day(s: String, today: LocalDate): LocalDate {
        val nums = s.trim().split('-', '/', '.').map { it.trim().toIntOrNull() }
        val date = runCatching {
            when {
                nums.size == 3 && nums.all { it != null } -> LocalDate.of(nums[0]!!, nums[1]!!, nums[2]!!)
                nums.size == 2 && nums.all { it != null } -> LocalDate.of(today.year, nums[0]!!, nums[1]!!)
                else -> null
            }
        }.getOrNull()
        return date ?: throw ToolFailure("日期「$s」看不懂，写成 YYYY-MM-DD，比如 ${today.plusDays(1)}。", "日期没写对")
    }

    /** A LIKE pattern for [q] anywhere, `!` escaping the wildcards (the query's ESCAPE char). */
    fun likePattern(q: String): String =
        "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
}

/** How todos, dates and diary entries are written out for the model. */
internal object Describe {
    private val monthDay = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA)

    /** Per entry and in total, so a long diary can't crowd the conversation out. */
    private const val ENTRY_MAX = 1500
    private const val TOTAL_MAX = 6000

    fun monthDay(d: LocalDate): String = d.format(monthDay)

    fun weekday(d: LocalDate): String = d.format(weekday).replace("星期", "周")

    /** 2026-09-24（周四，明天）: the ISO date to copy into calls, and a check on the arithmetic. */
    fun date(d: LocalDate, today: LocalDate): String {
        val relative = when (ChronoUnit.DAYS.between(today, d)) {
            0L -> "今天"
            1L -> "明天"
            2L -> "后天"
            -1L -> "昨天"
            -2L -> "前天"
            else -> null
        }
        return "$d（${weekday(d)}${relative?.let { "，$it" }.orEmpty()}）"
    }

    fun todo(t: TodoEntity, today: LocalDate, zone: ZoneId): String = buildString {
        append('#').append(t.id).append(' ').append(t.title)
        t.dueDay?.let { day ->
            val due = LocalDate.ofEpochDay(day)
            append(" · 截止 ").append(date(due, today))
            if (!t.done && due.isBefore(today)) append(" · 已过期")
        }
        if (t.note.isNotBlank()) append(" · 备注：").append(t.note)
        if (t.done) {
            append(" · 已做完")
            t.doneAt?.let { append("（").append(monthDay(Instant.ofEpochMilli(it).atZone(zone).toLocalDate())).append("）") }
        }
    }

    fun diaryContains(e: DiaryEntryEntity, q: String): Boolean =
        e.title.contains(q, ignoreCase = true) ||
            DiaryBlocks.plainText(DiaryBlocks.decode(e.blocks)).contains(q, ignoreCase = true)

    fun diary(entries: List<DiaryEntryEntity>, today: LocalDate): String {
        var used = 0
        return entries.joinToString("\n\n") { e ->
            val blocks = DiaryBlocks.decode(e.blocks)
            val body = DiaryBlocks.plainText(blocks)
            val images = DiaryBlocks.images(blocks).size
            val room = (TOTAL_MAX - used).coerceIn(0, ENTRY_MAX)
            val shown = when {
                body.length <= room -> body
                room == 0 -> "（正文太长没放下，要读就按日期单独读这一天）"
                else -> body.take(room) + "……（后面还有 ${body.length - room} 字）"
            }
            used += minOf(body.length, room)
            buildString {
                if (e.lockedForUser) append("【私密日记 #${e.id}，未分享，请勿透露正文】\n")
                else if (e.author == DiaryEntryEntity.AUTHOR_AI) append("日记 #${e.id}\n")
                append('【').append(date(LocalDate.ofEpochDay(e.day), today))
                append(if (e.author == DiaryEntryEntity.AUTHOR_AI) " · 你写的" else " · 对方写的").append('】')
                append(e.title.ifBlank { "（没有标题）" })
                if (shown.isNotEmpty()) append('\n').append(shown)
                if (images > 0) append("\n（配了 $images 张图）")
            }
        }
    }
}

internal object ToolCallCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(calls: List<ToolCall>): String = json.encodeToString(calls)

    fun decode(raw: String?): List<ToolCall> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString<List<ToolCall>>(raw) }.getOrDefault(emptyList())
}
