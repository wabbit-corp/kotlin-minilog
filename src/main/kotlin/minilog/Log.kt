package minilog

import one.wabbit.formatting.escapeJavaString
import java.util.logging.Logger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayDeque

abstract class Log {
    data class Tag(val name: String, val c: Char, val level: java.util.logging.Level)

    data class Stacktrace(
        val stack: List<StackTraceElement>,
        val fileName: String?,
        val lineNumber: Int?)

    class Context {
        var description: String? = null
        val variables = mutableListOf<Pair<String, Any?>>()
        val exceptions = mutableListOf<Throwable>()
        fun message(msg: String) { description = msg }
        fun data(name: String, value: Boolean?) { variables.add(name to value) }
        fun data(name: String, value: Int?) { variables.add(name to value) }
        fun data(name: String, value: Long?) { variables.add(name to value) }
        fun data(name: String, value: Double?) { variables.add(name to value) }
        fun data(name: String, value: UUID?) { variables.add(name to value) }
        fun data(name: String, value: String?) { variables.add(name to value) }
        fun exception(throwable: Throwable) { exceptions.add(throwable) }
    }

    abstract fun log(t1: Tag, f: (Context) -> Unit)
    abstract fun log(t1: Tag, t2: Tag, f: (Context) -> Unit)
    abstract fun log(t1: Tag, t2: Tag, t3: Tag, f: (Context) -> Unit)

    abstract fun log(ulid: String, t1: Tag, f: (Context) -> Unit)
    abstract fun log(ulid: String, t1: Tag, t2: Tag, f: (Context) -> Unit)
    abstract fun log(ulid: String, t1: Tag, t2: Tag, t3: Tag, f: (Context) -> Unit)

    class WithContext(val base: Log, val parentCtx: Context) {
        fun log(t1: Tag, f: (Context) -> Unit) {
            base.log(t1) {
                it.description = parentCtx.description
                it.variables.addAll(parentCtx.variables)
                it.exceptions.addAll(parentCtx.exceptions)
                f(it)
            }
        }
        fun log(t1: Tag, t2: Tag, f: (Context) -> Unit) {
            base.log(t1, t2) {
                it.description = parentCtx.description
                it.variables.addAll(parentCtx.variables)
                it.exceptions.addAll(parentCtx.exceptions)
                f(it)
            }
        }
        fun log(t1: Tag, t2: Tag, t3: Tag, f: (Context) -> Unit) {
            base.log(t1, t2, t3) {
                it.description = parentCtx.description
                it.variables.addAll(parentCtx.variables)
                it.exceptions.addAll(parentCtx.exceptions)
                f(it)
            }
        }

        fun log(ulid: String, t1: Tag, f: (Context) -> Unit) {
            base.log(ulid, t1) {
                it.description = parentCtx.description
                it.variables.addAll(parentCtx.variables)
                it.exceptions.addAll(parentCtx.exceptions)
                f(it)
            }
        }
        fun log(ulid: String, t1: Tag, t2: Tag, f: (Context) -> Unit) {
            base.log(ulid, t1, t2) {
                it.description = parentCtx.description
                it.variables.addAll(parentCtx.variables)
                it.exceptions.addAll(parentCtx.exceptions)
                f(it)
            }
        }
        fun log(ulid: String, t1: Tag, t2: Tag, t3: Tag, f: (Context) -> Unit) {
            base.log(ulid, t1, t2, t3) {
                it.description = parentCtx.description
                it.variables.addAll(parentCtx.variables)
                it.exceptions.addAll(parentCtx.exceptions)
                f(it)
            }
        }
    }

    fun withContext(f: (Context) -> Unit): Log {
        val ctx = Context()
        f(ctx)
        return this
    }

    companion object {
        val dummy: Log = object : Log() {
            override fun log(t1: Tag, f: (Context) -> Unit) { }
            override fun log(t1: Tag, t2: Tag, f: (Context) -> Unit) { }
            override fun log(t1: Tag, t2: Tag, t3: Tag, f: (Context) -> Unit) { }
            override fun log(ulid: String, t1: Tag, f: (Context) -> Unit) { }
            override fun log(ulid: String, t1: Tag, t2: Tag, f: (Context) -> Unit) { }
            override fun log(ulid: String, t1: Tag, t2: Tag, t3: Tag, f: (Context) -> Unit) { }
        }
    }
}

sealed class Set1<out T> {
    object All : Set1<Nothing>()
    data class Prim<out T>(val on: Set<T>) : Set1<Nothing>()

    operator fun contains(v1: @UnsafeVariance T): Boolean = when(this) {
        is All -> true
        is Prim<*> -> on.contains(v1)
    }
}

interface Reporter {
    fun report(
        ulid: String?,
        tags: List<Log.Tag>,
        description: String?,
        ctx: Log.Context,
        stack: Log.Stacktrace
    )
}

private val BAD_PREFIXES = arrayOf(
    "java.lang.Thread",
    "net.minecraft.server",
    "org.bukkit.craftbukkit",
    "io.papermc.paper"
)

private fun cleanupStackTrace(stack: Array<StackTraceElement>): List<StackTraceElement> {
    val queue = ArrayDeque<StackTraceElement>()

    for (s in stack) queue.add(s)

    while (queue.isNotEmpty()) {
        val head = queue.first()
        if (head.className == "minilog.LogManager\$LogImpl" ||
            head.className == "minilog.Log") {
            queue.removeFirst()
        } else break
    }

    while (queue.isNotEmpty()) {
        val tail = queue.last()
        if (BAD_PREFIXES.any { tail.className.startsWith(it) }) {
            queue.removeLast()
        } else break
    }

    return queue.toList()
}

private val NL = System.lineSeparator()

class LogManager(var reporter: Reporter? = null) {
    private fun stackTraceInfo(stack: Array<StackTraceElement>): Log.Stacktrace {
        val adjustedStack = stack.dropWhile {
            it.className == "minilog.LogManager\$LogImpl" ||
                    it.className == "minilog.Log"
        }

        val el = if (adjustedStack.isNotEmpty()) adjustedStack[0] else null
        val fn = el?.fileName
        val ln = el?.lineNumber

        return Log.Stacktrace(adjustedStack, fn, ln)
    }

    private inner class LogImpl(
        val name: String,
        val logger: Logger,
        @Volatile var enabled: Set1<Tag>) : Log() {

        private fun log(ulid: String?, tags: List<Tag>, f: Context.() -> Unit) {
            val dummyException = Throwable()
            val stack = stackTraceInfo(dummyException.stackTrace)

            val tagString = tags.map { it.c }.sorted().joinToString(separator="")
            val effectiveLevel = tags.map { it.level }.maxByOrNull { it.intValue() }!!

            val ctx = Context()
            f(ctx)

            ////////////////////////////////////////////////////////////////////
            // Building a string.
            ////////////////////////////////////////////////////////////////////
            val sb = StringBuilder()
            sb.append('[').append(tagString).append("] ")
                .append(stack.fileName).append(':').append(stack.lineNumber).append(' ')

            val identifier = ulid
            if (identifier != null)
                sb.append(identifier).append(" ")

            val description = ctx.description
            if (description != null)
                sb.append("(").append(description).append(")").append(" ")

            for ((n, v) in ctx.variables) {
                sb.append(n).append('=')
                if (v is CharSequence) {
                    sb.append('"').append(escapeJavaString(v, doubleQuoted = true, limit = 256)).append('"')
                } else {
                    sb.append(v)
                }
                sb.append(' ')
            }

            fun go(namePrefix: String, depth: Int, throwable: Throwable, parent: Throwable? = null) {
                sb.append("  ".repeat(depth))
                    .append(namePrefix).append(throwable.javaClass.name).append(": ")
                    .append(throwable.message).append(NL)

                val childStack = cleanupStackTrace(throwable.stackTrace)
                val parentStack = parent?.let { cleanupStackTrace(it.stackTrace) } ?: emptyList()

                if (parent == null) {
                    for (s in childStack) {
                        sb.append("  ".repeat(depth + 1))
                            .append(s.toString()).append(NL)
                    }
                } else {
                    val common = parentStack.asReversed().zip(childStack.asReversed())
                        .takeWhile { (a, b) -> a == b }.size
                    for (s in childStack.dropLast(common)) {
                        sb.append("  ".repeat(depth + 1))
                            .append(s.toString()).append(NL)
                    }
                    sb.append("  ".repeat(depth + 1))
                        .append("... $common common frames omitted ...\n")
                }

                if (throwable.cause != null)
                    go("Caused by ", depth + 1, throwable.cause!!, throwable)

                if (throwable.suppressed.isNotEmpty()) {
                    sb.append("Suppressed: ")
                    for (s in throwable.suppressed) go("With suppressed ", depth + 1, s)
                }
            }
            for (e in ctx.exceptions) {
                sb.append(NL)
                go("", 0, e)
            }

            val message = sb.toString()

            ////////////////////////////////////////////////////////////////////
            // Reporting.
            ////////////////////////////////////////////////////////////////////
            reporter?.report(identifier, tags, description, ctx, stack)

            ////////////////////////////////////////////////////////////////////
            // Logging.
            ////////////////////////////////////////////////////////////////////
            logger.log(effectiveLevel, message)
        }

        override fun log(t1: Tag, f: (Context) -> Unit) {
            if (t1 in enabled) log(null, listOf(t1), f)
        }
        override fun log(t1: Tag, t2: Tag, f: (Context) -> Unit) {
            if (t1 in enabled || t2 in enabled) log(null, listOf(t1, t2), f)
        }
        override fun log(t1: Tag, t2: Tag, t3: Tag, f: (Context) -> Unit) {
            if (t1 in enabled || t2 in enabled || t3 in enabled) log(null, listOf(t1, t2, t3), f)
        }

        override fun log(ulid: String, t1: Tag, f: (Context) -> Unit) {
            if (t1 in enabled) log(ulid, listOf(t1), f)
        }
        override fun log(ulid: String, t1: Tag, t2: Tag, f: (Context) -> Unit) {
            if (t1 in enabled || t2 in enabled) log(ulid, listOf(t1, t2), f)
        }
        override fun log(ulid: String, t1: Tag, t2: Tag, t3: Tag, f: (Context) -> Unit) {
            if (t1 in enabled || t2 in enabled || t3 in enabled) log(ulid, listOf(t1, t2, t3), f)
        }
    }

    private val loggers = ConcurrentHashMap<String, LogImpl>()

    fun getLogger(name: String): Log =
        loggers.computeIfAbsent(name) { LogImpl(name, Logger.getLogger(name), Set1.All) }
}
