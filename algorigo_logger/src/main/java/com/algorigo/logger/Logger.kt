package com.algorigo.logger

import android.util.Log

object Tag {
    fun getName(): String {
        return javaClass.name
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

object Logger {

    private val loggerMap = mutableMapOf<String, java.util.logging.Logger>()

    @Synchronized
    fun getLogger(tag: Tag): java.util.logging.Logger {
        return loggerMap[tag.getName()] ?: (java.util.logging.Logger.getLogger(tag.getName()).also {
            loggerMap[tag.getName()] = it
        })
    }
}

object L {
    fun log(level: Level, tag: Tag, message: String) {
        Logger.getLogger(tag).log(level.level, message)
    }

    fun verbose(tag: Tag, message: String) {
        log(Level.VERBOSE, tag, message)
    }

    fun debug(tag: Tag, message: String) {
        log(Level.DEBUG, tag, message)
    }

    fun info(tag: Tag, message: String) {
        log(Level.INFO, tag, message)
    }

    fun notice(tag: Tag, message: String) {
        log(Level.NOTICE, tag, message)
    }

    fun warning(tag: Tag, message: String) {
        log(Level.WARNING, tag, message)
    }

    fun error(tag: Tag, message: String) {
        log(Level.ERROR, tag, message)
    }

    fun asserts(tag: Tag, message: String) {
        log(Level.ASSERTS, tag, message)
    }
}
