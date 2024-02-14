package com.algorigo.logger

import android.util.Log
import androidx.annotation.IntRange

open class Tag {

    val name: String
    private val parent: Tag?
        get() = javaClass.name
            .run {
                val lastDollar = lastIndexOf('$')
                if (lastDollar >= 0) {
                    substring(0, lastDollar)
                } else {
                    null
                }
            }?.let {
                try {
                    Class.forName(it)
                } catch (e: ClassNotFoundException) {
                    null
                }
            }?.let {
                if (Tag::class.java.isAssignableFrom(it)) {
                    (it.kotlin.objectInstance ?: it.newInstance()) as Tag
                } else {
                    null
                }
            }

    init {
        name = parent?.let {
            it.name + "." + javaClass.simpleName
        } ?: javaClass.simpleName
    }

    fun getChildren() : List<Tag> {
        return javaClass.declaredClasses.mapNotNull {
            if (Tag::class.java.isAssignableFrom(it)) {
                (it.kotlin.objectInstance ?: it.newInstance()) as Tag
            } else {
                null
            }
        }
    }
}

enum class Level(val level: java.util.logging.Level) {
    VERBOSE(java.util.logging.Level.FINEST),
    DEBUG(java.util.logging.Level.FINE),
    INFO(java.util.logging.Level.CONFIG),
    NOTICE(java.util.logging.Level.INFO),
    WARNING(java.util.logging.Level.WARNING),
    ERROR(java.util.logging.Level.SEVERE),
    ASSERTS(java.util.logging.Level.OFF),
}

object LogManager {

    private val loggerMap = mutableMapOf<Tag, java.util.logging.Logger>()

    fun initTags(vararg tags: Tag) {
        tags.forEach {
            if (!loggerMap.containsKey(it)) {
                loggerMap[it] = java.util.logging.Logger.getLogger(it.name)
                initTags(*it.getChildren().toTypedArray())
            }
        }
    }

    @Synchronized
    fun getLogger(tag: Tag): java.util.logging.Logger {
        return loggerMap[tag] ?: (java.util.logging.Logger.getLogger(tag.name).also {
            loggerMap[tag] = it
        })
    }
}

object L {
    fun log(level: Level, tag: Tag, message: String, throwable: Throwable? = null) {
        LogManager.getLogger(tag).log(level.level, message, throwable)
    }

    fun verbose(tag: Tag, message: String, throwable: Throwable? = null, @IntRange(from = 0L) depth: Int = 0) {
        val frame = Exception().stackTrace[depth + 2]
        log(Level.VERBOSE, tag, "$message (${frame.fileName}:${frame.lineNumber})", throwable)
    }

    fun debug(tag: Tag, message: String, throwable: Throwable? = null, @IntRange(from = 0L) depth: Int = 0) {
        val frame = Exception().stackTrace[depth + 2]
        log(Level.DEBUG, tag, "$message (${frame.fileName}:${frame.lineNumber})", throwable)
    }

    fun info(tag: Tag, message: String, throwable: Throwable? = null, @IntRange(from = 0L) depth: Int = 0) {
        val frame = Exception().stackTrace[depth + 2]
        log(Level.INFO, tag, "$message (${frame.fileName}:${frame.lineNumber})", throwable)
    }

    fun notice(tag: Tag, message: String, throwable: Throwable? = null, @IntRange(from = 0L) depth: Int = 0) {
        val frame = Exception().stackTrace[depth + 2]
        log(Level.NOTICE, tag, "$message (${frame.fileName}:${frame.lineNumber})", throwable)
    }

    fun warning(tag: Tag, message: String, throwable: Throwable? = null, @IntRange(from = 0L) depth: Int = 0) {
        val frame = Exception().stackTrace[depth + 2]
        log(Level.WARNING, tag, "$message (${frame.fileName}:${frame.lineNumber})", throwable)
    }

    fun error(tag: Tag, message: String, throwable: Throwable? = null, @IntRange(from = 0L) depth: Int = 0) {
        val frame = Exception().stackTrace[depth + 2]
        log(Level.ERROR, tag, "$message (${frame.fileName}:${frame.lineNumber})", throwable)
    }

    fun asserts(tag: Tag, message: String, throwable: Throwable? = null, @IntRange(from = 0L) depth: Int = 0) {
        val frame = Exception().stackTrace[depth + 2]
        log(Level.ASSERTS, tag, "$message (${frame.fileName}:${frame.lineNumber})", throwable)
    }
}
