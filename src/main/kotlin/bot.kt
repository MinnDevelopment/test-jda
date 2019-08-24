@file:JvmName("Bot")
import club.minnced.jda.reactor.asMono
import club.minnced.jda.reactor.createManager
import club.minnced.jda.reactor.on
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.utils.data.DataObject
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.io.File
import java.util.*
import java.util.concurrent.Executors
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager

typealias Task = Publisher<*>

val token: String by lazy {
    DataObject.fromJson(File("tokens.json").readText()).getString("bot")
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

val pool = Executors.newScheduledThreadPool(8)

val prefix = "--"

fun main() {
    val manager = createManager {
        setScheduler(Schedulers.fromExecutor(pool))
    }

    val jda = JDABuilder(token)
            .setEventManager(manager)
            .setActivity(Activity.watching("\u200B"))
            .setStatus(OnlineStatus.DO_NOT_DISTURB)
            .setBulkDeleteSplittingEnabled(false)
            .setGatewayPool(pool)
            .setRateLimitPool(pool)
            .setCallbackPool(pool)
            .setDisabledCacheFlags(EnumSet.allOf(CacheFlag::class.java))
            .setChunkingFilter(ChunkingFilter.NONE)
            .build()
    jda.on<GuildMessageReceivedEvent>()
       .filter { it.author.asTag == "Minn#6688" }
       .filter { it.guild.selfMember.hasPermission(it.channel, Permission.MESSAGE_WRITE) }
       .flatMap(::onMessage)
       .subscribe()
}

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

fun eval(event: GuildMessageReceivedEvent, args: List<String>): Task {
    engine["event"] = event
    engine["message"] = event.message
    engine["author"] = event.author
    engine["api"] = event.jda
    engine["member"] = event.member
    engine["guild"] = event.guild
    engine["channel"] = event.channel

    try {
        val output = engine.eval(event.message.contentRaw.substring(prefix.length + "eval".length))
                ?: return event.message.addReaction("\uD83D\uDC4D\uD83C\uDFFB").asMono()

        return event.channel.sendMessage(output.toString()).asMono()
    }
    catch (ex: Exception) {
        ex.printStackTrace()
        var t: Throwable = ex
        while (ex.cause != null)
            t = ex.cause!!
        return event.channel.sendMessage(t.toString()).asMono()
    }
}



