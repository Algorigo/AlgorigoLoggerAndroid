package com.algorigo.logger.handler

import com.algorigo.logger.Level
import com.algorigo.logger.formatter.TimedLogFormatter
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.LogRecord

class AlgorigoLogHandler(
    formatter: Formatter? = null,
    level: Level = Level.DEBUG,
) : Handler() {

    private val subHandlers = mutableListOf<Handler>()

    init {
        setFormatter(formatter ?: TimedLogFormatter())
        setLevel(level.level)
    }

    fun addHandler(subHandler: Handler) {
        subHandlers.add(subHandler)
    }

    fun removeHandler(subHandler: Handler) {
        subHandlers.remove(subHandler)
        subHandler.close()
    }

    override fun publish(record: LogRecord?) {
        if (!isLoggable(record)) {
            return
        }
        flush()
        subHandlers.toList().forEach {
            if (it.level.intValue() <= (record?.level?.intValue() ?: Int.MAX_VALUE)) {
                it.publish(record)
            }
        }
    }

    override fun flush() {
        subHandlers.toList().forEach {
            it.flush()
        }
    }

    override fun close() {
        subHandlers.toList().forEach {
            it.close()
        }
        subHandlers.clear()
    }
}
