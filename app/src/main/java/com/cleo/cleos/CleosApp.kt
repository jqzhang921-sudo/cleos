package com.cleo.cleos

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.room.Room
import com.cleo.cleos.ai.AiSelfAvatar
import com.cleo.cleos.ai.Calls
import com.cleo.cleos.ai.ChatClient
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.ai.EarVoice
import com.cleo.cleos.ai.Glance
import com.cleo.cleos.ai.Later
import com.cleo.cleos.ai.Letters
import com.cleo.cleos.ai.Listening
import com.cleo.cleos.ai.Lyrics
import com.cleo.cleos.ai.PersonaMemory
import com.cleo.cleos.ai.PhoneCalendar
import com.cleo.cleos.ai.PhoneClock
import com.cleo.cleos.ai.PhoneLocation
import com.cleo.cleos.ai.PhoneMusic
import com.cleo.cleos.ai.McpClient
import com.cleo.cleos.ai.McpHub
import com.cleo.cleos.ai.Recaps
import com.cleo.cleos.ai.OpenMeteo
import com.cleo.cleos.ai.SecretRequests
import com.cleo.cleos.ai.Speaker
import com.cleo.cleos.ai.ToolBox
import com.cleo.cleos.ai.Transcriber
import com.cleo.cleos.data.BackupService
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.ForeignImport
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.data.McpServers
import com.cleo.cleos.data.SecretStore
import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.Stickers
import com.cleo.cleos.data.db.AppDatabase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CleosApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Before anything that could fail: a crash while the rest is set up is kept too.
        CrashLog.install(this)
        container = AppContainer(this)
        container.notifier.channels()
    }
}

/** Where a tapped notification leads: MainActivity reads it from the intent, the screens follow. */
sealed interface Opening {
    data class Chat(val conversationId: Long) : Opening

    data class Diary(val id: Long) : Opening
    data class Letter(val id: Long) : Opening
}

/** Hand-made dependency wiring; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    /**
     * Outlives any screen: saves and replies started here finish even if the user leaves.
     *
     * Nothing out here may take the app down with it. Without a handler of its own, an exception
     * from any of these jobs goes to the thread's own and closes the app — which is how the rarest
     * thing in this file became the loudest: a wake that outlived its TA failed on the row it was
     * writing and killed the process. A background job that fails now is written down instead
     * (CrashLog.note, shown in 关于), and only that job is let go of.
     */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> CrashLog.note(context, error) },
    )

    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "cleos.db").build()
    val settings = SettingsRepository(context)
    val secrets = SecretStore(context)
    val modelProfiles = com.cleo.cleos.data.ModelProfiles(secrets)
    val images = ImageStore(context)
    val feed = com.cleo.cleos.data.FeedRepository(db, images)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Reasoning models can think for a long time before the first visible token.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    internal val updates = Updates(context, http, version)

    val companions = Companions(db, settings, secrets, images)
    val chatClient = ChatClient(http)
    val feedNews = com.cleo.cleos.ai.FeedNews(http.newBuilder().callTimeout(18, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build())
    val feedAi = com.cleo.cleos.ai.FeedAi(db, settings, secrets, chatClient, feedNews, feed, images)
    private val calendar = PhoneCalendar(context)

    /** What the phone is playing, and its words: the chat's 一起听 bar, a reply's line about it, music_control. */
    val music = PhoneMusic(context)
    val lyrics = Lyrics(http, "Cleos/$version (${Releases.url(Releases.HOSTS.first())})", appScope)
    // Typed: its note_for_later reaches [later], which is made further down from what uses this.
    val tools: ToolBox = ToolBox(
        db.todos(),
        db.diary(),
        OpenMeteo(http),
        requests = { id -> db.messages().requestsBy(id).mapNotNull { SecretRequests.decode(it.content) } },
        avatar = AiSelfAvatar(db, images, companions),
        letters = { id -> db.letters().allFor(id) },
        memories = db.memories(),
        lore = db.lore(),
        location = PhoneLocation(context, http),
        later = { later },
        alarms = PhoneClock(context) { visible },
        calendar = calendar,
        music = music,
        patBack = { id, suffix -> chat.patBack(id, suffix) },
        reactBack = { conversation, message, emoji, remove -> chat.reactBack(conversation, message, emoji, remove) },
        planVisit = { conversation, minutes -> freeTopics.propose(conversation, minutes) },
    )
    val recaps = Recaps(db, settings, secrets, chatClient, appScope)
    val mcp = McpHub(
        McpServers(secrets),
        McpClient(http, version),
    )
    val transcriber = Transcriber(http, secrets)
    val speaker = Speaker(images, http, secrets)

    /** The TA's voice messages beside the ear, on headphones. */
    val ear = EarVoice(context, images)
    val wakeActivities = com.cleo.cleos.ai.WakeActivities(db)
    val chat: ChatRepository = ChatRepository(
        db, settings, secrets, chatClient, tools, images, companions, recaps, mcp, transcriber, speaker, appScope,
        // A reply finished where the person isn't looking (they left, or went to another page): as a notification.
        replied = { ta, conversationId, said ->
            if (!(visible && chatOnScreen == conversationId)) notifier.messages(ta, conversationId, said)
            followUps.plan(ta, conversationId, said)
            freeTopics.replied(conversationId)
        },
        interrupted = { followUps.cancel(it); freeTopics.interrupt(it); feedVisits.interrupt(it) },
        activities = wakeActivities,
        listening = { Listening(music, lyrics).line() },
    )
    val stickers = Stickers(context, db, images)
    val favorites = com.cleo.cleos.data.Favorites(db, settings, images)
    val backup = BackupService(context, db, settings, images)
    val imports = ForeignImport(context, db, companions)
    val notifier = Notifier(context, images)

    /** Phone calls with a TA. */
    val calls = Calls(context, db, chat, companions, settings, transcriber, speaker, appScope)

    /** Whether the app is on screen (MainActivity, between onStart and onStop). */
    @Volatile
    var visible = false

    /**
     * The conversation whose chat is on screen right now (the chat tab showing, the app in front),
     * else null. Only what arrives there goes without a notification: the app being open is not
     * enough, since on another tab, or in settings, nothing new in the chat is seen.
     */
    @Volatile
    var chatOnScreen: Long? = null

    val later: Later = Later(
        context,
        db,
        chat,
        notifier,
        appScope,
        showing = { id -> visible && chatOnScreen == id },
        companions = companions,
        settings = settings,
        glance = Glance(db, calendar, settings) { PhoneCalendar.allowed(context) },
        activities = wakeActivities,
        inUse = {
            context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
                context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true
        },
    )
    val followUps = com.cleo.cleos.ai.FollowUps(context, db, chat, notifier, appScope, wakeActivities) { id -> visible && chatOnScreen == id }
    val freeTopics = com.cleo.cleos.ai.FreeTopics(context, db, chat, notifier, appScope,
        activities = wakeActivities,
        showing = { id -> visible && chatOnScreen == id },
        onEnabled = { later.wantsNotifications.value = true })
    val letters = Letters(db, settings, secrets, chatClient, appScope, written = { later.letterWritten(it) })
    val feedVisits = com.cleo.cleos.ai.FeedVisits(context, db, feedAi, chat, secrets, appScope, wakeActivities)

    /** Where a tapped notification leads, until a screen has gone there. */
    val opening = MutableStateFlow<Opening?>(null)

    /** A notification about [conversationId] was tapped: its TA and the conversation become current, then the chat shows. */
    fun openConversation(conversationId: Long) {
        appScope.launch {
            val conversation = db.conversations().get(conversationId) ?: return@launch
            settings.setCurrentCompanion(conversation.companionId)
            settings.setCurrentConversation(conversationId)
            opening.value = Opening.Chat(conversationId)
        }
    }

    fun openLetter(id: Long) {
        opening.value = Opening.Letter(id)
    }

    init {
        // The first TA is made from the old settings before anything asks who is being talked to.
        appScope.launch {
            wakeActivities.recover()
            companions.ensure()
            favorites.prune()
            // What a TA brought from another app used to sit in their persona; it moves into their memory, once.
            PersonaMemory.migrate(db, System.currentTimeMillis())
            // A call the app was stopped in the middle of ended there.
            chat.closeOpenCalls()
        }
        // Notes still waiting and letters on their way get their background work back, if it was lost.
        later.reconcile()
        followUps.restore()
        freeTopics.restore()
        feedVisits.restore()
        // A reply under way is what keeps the app running in the background (ReplyKeeper), and this
        // is where that is decided: it goes up the moment one starts — the person is in front then,
        // and the system lets a service start — rather than when they leave, which is the moment
        // these phones freeze the process and the start never lands. It takes itself down again
        // when nothing is being written (ReplyKeeper).
        appScope.launch {
            chat.working.collect { if (it.isNotEmpty()) ReplyKeeper.start(context) }
        }
    }
}
