@file:JvmName("Bot")

import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.createManager
import club.minnced.jda.reactor.on
import club.minnced.jda.reactor.onMessage
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA.Status.CONNECTED
import net.dv8tion.jda.api.JDA.Status.SHUTDOWN
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.OnlineStatus.DO_NOT_DISTURB
import net.dv8tion.jda.api.OnlineStatus.ONLINE
import net.dv8tion.jda.api.Permission.MESSAGE_WRITE
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Activity.watching
import net.dv8tion.jda.api.entities.MessageType
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.ShutdownEvent
import net.dv8tion.jda.api.events.StatusChangeEvent
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.events.message.priv.PrivateMessageReceivedEvent
import net.dv8tion.jda.api.exceptions.ErrorResponseException.ignore
import net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE
import net.dv8tion.jda.api.requests.RestAction.setDefaultFailure
import net.dv8tion.jda.api.utils.ChunkingFilter.exclude
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.utils.data.DataObject
import org.reactivestreams.Publisher
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.io.File
import java.lang.Runtime.getRuntime
import java.time.Duration.ofSeconds
import java.util.EnumSet.noneOf
import java.util.HashMap
import java.util.concurrent.Executors
import java.util.concurrent.Executors.newScheduledThreadPool
import java.util.concurrent.ScheduledExecutorService
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

typealias Task = Publisher<*>

val tokens: Map<String, String> by lazy {
    val json = DataObject.fromJson(File("tokens.json").reader())
    HashMap<String, String>().also { map ->
        json.keys().forEach { map[it] = json.getString(it) }
    }
}

val token: String by lazy { tokens.getValue("bot") }

val engine: ScriptEngine by lazy {
    ScriptEngineManager().getEngineByExtension("kts")!!.apply {
        this("""
        import net.dv8tion.jda.api.*
        import net.dv8tion.jda.api.entities.*
        import net.dv8tion.jda.api.utils.*
        import reactor.core.publisher.*
        import club.minnced.jda.reactor.*
        import java.util.*
        import java.util.stream.*
        import java.io.*
        import java.time.*
        import com.vdurmont.emoji.*
        """.trimIndent())
    }
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun ScriptEngine.set(key: String, value: Any?) = put(key, value)

@Suppress("NOTHING_TO_INLINE")
inline operator fun ScriptEngine.invoke(code: String): Any? = synchronized(this) { eval(code) }

@Suppress("NOTHING_TO_INLINE")
inline operator fun Mono<*>.plus(other: Publisher<*>): Mono<Void> = and(other)

@Suppress("NOTHING_TO_INLINE")
inline fun JDABuilder.setPool(pool: ScheduledExecutorService): JDABuilder
    = this.setGatewayPool(pool).setRateLimitPool(pool).setCallbackPool(pool)

const val MIB = 1024 * 1024
const val prefix = "--"

val pool: ScheduledExecutorService = newScheduledThreadPool(8)
val schedulerPool: Scheduler = Schedulers.fromExecutor(pool)
val messageLog: Logger = LoggerFactory.getLogger("Messages")
val log: Logger = LoggerFactory.getLogger("Main")

fun main() {
    System.`in`.close()
    setDefaultFailure(ignore(UNKNOWN_MESSAGE))
    val manager = createManager {
        this.scheduler = schedulerPool
        this.overflowStrategy = FluxSink.OverflowStrategy.LATEST
    }

    val jda = JDABuilder(token)
            .setEventManager(manager)
            .setActivity(watching("\u200B"))
            .setStatus(DO_NOT_DISTURB)
            .setBulkDeleteSplittingEnabled(false)
            .setPool(pool)
            .setRawEventsEnabled(true)
            .setEnabledCacheFlags(noneOf(CacheFlag::class.java))
            .setGuildSubscriptionsEnabled(false)
            .setChunkingFilter(exclude(81384788765712384))
            .setLargeThreshold(50)
            .build()

    jda.on<ShutdownEvent>()
       .map { it.jda.httpClient }
       .subscribe {
           it.connectionPool().evictAll()
           it.dispatcher().executorService().shutdown()
       }

    jda.on<PrivateMessageReceivedEvent>()
       .doOnNext {
           messageLog.info("[private] {}: {}",
                   it.author.asTag, it.message.contentDisplay)
       }
       .flatMap { it.channel.close().asMono() }
       .subscribe()

    // Handle commands (guild/owner only)
    jda.on<GuildMessageReceivedEvent>()
       .filter { it.message.type == MessageType.DEFAULT }
       .doOnNext {
           messageLog.info("[{}#{}] {}: {}",
                           it.guild.name, it.channel.name, it.author.asTag,
                           it.message.contentDisplay.take(100).replace("\n", " "))
       }
       .filter { it.author.asTag == "Minn#6688" }
       .filter { it.guild.selfMember.hasPermission(it.channel, MESSAGE_WRITE) }
       .filter { it.message.contentRaw.startsWith(prefix) }
       .flatMap(::onMessage)
       .onErrorContinue { t, _ -> t.printStackTrace() }
       .subscribe()

    // Handle activity streaming
    Flux.interval(ofSeconds(5), ofSeconds(15))
        .doOnNext { System.gc() }
        .takeUntil { jda.status == SHUTDOWN }
        .filter { jda.status == CONNECTED }
        .map { getRuntime().run { totalMemory() - freeMemory() } / MIB }
        .map { watching("${jda.userCache.size()} users at $it MiB") }
        .filter { it != jda.presence.activity }
        .subscribe { jda.presence.setPresence(ONLINE, it) }

    jda.on<StatusChangeEvent>()
       .map { "Changed status ${it.oldStatus} -> ${it.newStatus}" }
       .subscribe(log::info)
}

// Basic command handling
fun onMessage(event: GuildMessageReceivedEvent): Task {
    val content = event.message.contentRaw.split(" ")
    val command = content[0].substring(2)
    val args = content.drop(1)

    return when (command) {
        "ping"  -> ping(event, args)
        "eval"  -> eval(event, args)
        "nonce" -> nonce(event, args)
        else    -> Mono.empty<Unit>()
    }
}

fun nonce(event: GuildMessageReceivedEvent, args: List<String>): Task {
    val channel = event.channel
    val nonce = args.joinToString(" ")

    val listener = event.channel.onMessage()
         .filter { it.message.nonce == nonce }
         .flatMap { channel.sendMessage("Received nonce!").asMono() }
         .next()

    val send = channel.sendMessage("testing nonce").nonce(nonce).asMono()
    return listener + send
}

fun ping(event: GuildMessageReceivedEvent, args: List<String>): Task {
    return event.jda.restPing.asMono()
             .flatMap { event.channel.sendMessage("Ping: $it ms").asMono() }
}

fun eval(event: GuildMessageReceivedEvent, args: List<String>): Task = Mono.defer {
    engine["event"] = event
    engine["message"] = event.message
    engine["author"] = event.author
    engine["api"] = event.jda
    engine["member"] = event.member
    engine["guild"] = event.guild
    engine["channel"] = event.channel

    try {
        val code = event.message.contentRaw.removePrefix(prefix + "eval")
        val emptySuccess = event.message.addReaction("\uD83D\uDC4D\uD83C\uDFFB")

        val output = engine(code) ?: return@defer emptySuccess.asMono()
        return@defer event.channel.sendMessage(output.toString()).asMono()
    } catch (ex: Exception) {
        log.error("eval():", ex)
        var t: Throwable = ex
        while (ex.cause != null)
            t = ex.cause!!
        return@defer event.channel.sendMessage(t.toString()).asMono()
    }
}.subscribeOn(Schedulers.elastic())
