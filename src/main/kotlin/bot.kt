@file:JvmName("Bot")

import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.createManager
import club.minnced.jda.reactor.on
import club.minnced.jda.reactor.onRaw
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.utils.data.DataObject
import org.reactivestreams.Publisher
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.io.File
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

typealias Task = Publisher<*>

val token: String by lazy {
    DataObject.fromJson(File("tokens.json").reader()).getString("bot")
}

val engine: ScriptEngine by lazy {
    ScriptEngineManager().getEngineByExtension("kts")!!.apply {
        eval("import net.dv8tion.jda.api.*")
        eval("import net.dv8tion.jda.api.entities.*")
        eval("import net.dv8tion.jda.api.utils.*")
        eval("import reactor.core.publisher.*")
        eval("import club.minnced.jda.reactor.*")
        eval("import java.util.*")
        eval("import java.io.*")
        eval("import java.time.*")
    }
}

operator fun ScriptEngine.set(key: String, value: Any?) = put(key, value)

const val prefix = "--"
val messageLog: Logger = LoggerFactory.getLogger("Messages")
val pool: ScheduledExecutorService = Executors.newScheduledThreadPool(8)

fun main() {
    val manager = createManager {
        scheduler = Schedulers.fromExecutor(pool)
    }

    val jda = JDABuilder(token)
            .setEventManager(manager)
            .setActivity(Activity.watching("\u200B"))
            .setStatus(OnlineStatus.DO_NOT_DISTURB)
            .setBulkDeleteSplittingEnabled(false)
            .setGatewayPool(pool)
            .setRateLimitPool(pool)
            .setCallbackPool(pool)
            .setGuildSubscriptionsEnabled(false)
            .setRawEventsEnabled(true)
            .setEnabledCacheFlags(EnumSet.of(CacheFlag.VOICE_STATE))
            .setChunkingFilter(ChunkingFilter.NONE)
            .build()

    jda.onRaw("MESSAGE_REACTION_ADD")
       .map { "reaction add: ${it.payload}" }
       .subscribe(::println)
    jda.onRaw("MESSAGE_REACTION_REMOVE")
       .map { "reaction rem: ${it.payload}" }
       .subscribe(::println)

    // Handle commands (guild/owner only)
    jda.on<GuildMessageReceivedEvent>()
       .doOnNext {
           messageLog.info("[{}#{}] {}: {}",
                           it.guild.name, it.channel.name, it.author.asTag,
                           it.message.contentDisplay.take(100).replace("\n", " "))
       }
       .filter { it.author.asTag == "Minn#6688" }
       .filter { it.guild.selfMember.hasPermission(it.channel, Permission.MESSAGE_WRITE) }
       .flatMap(::onMessage)
       .onErrorContinue { t, _ ->
           t.printStackTrace()
       }
       .subscribe()

    // Handle activity streaming
    jda.on<ReadyEvent>()
       .next()
       .doOnSuccess { jda.presence.setStatus(OnlineStatus.ONLINE) }
       .flatMapMany { Flux.interval(Duration.ZERO, Duration.ofSeconds(15)) }
       .takeUntil { jda.status == JDA.Status.SHUTDOWN }
       .filter { jda.status == JDA.Status.CONNECTED }
       .subscribe {
           System.gc()
           val users = jda.userCache.size()
           val memory = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / (1024 * 1024)
           jda.presence.activity = Activity.watching("$users users at $memory MiB")
       }
}

// Basic command handling
fun onMessage(event: GuildMessageReceivedEvent): Task {
    if (!event.message.contentRaw.startsWith(prefix)) {
        return Mono.empty<Unit>()
    }
    val content = event.message.contentRaw.split(" ")
    val command = content[0].substring(2)
    val args = content.drop(1)

    return when (command) {
        "ping" -> ping(event, args)
        "eval" -> eval(event, args)
        else -> Mono.empty<Unit>()
    }
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
        val output = engine.eval(event.message.contentRaw.substring(prefix.length + "eval".length))
                ?: return@defer event.message.addReaction("\uD83D\uDC4D\uD83C\uDFFB").asMono()

        return@defer event.channel.sendMessage(output.toString()).asMono()
    } catch (ex: Exception) {
        ex.printStackTrace()
        var t: Throwable = ex
        while (ex.cause != null)
            t = ex.cause!!
        return@defer event.channel.sendMessage(t.toString()).asMono()
    }

}.subscribeOn(Schedulers.elastic())
