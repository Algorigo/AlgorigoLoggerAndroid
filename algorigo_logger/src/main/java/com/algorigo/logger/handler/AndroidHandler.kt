package com.algorigo.logger.handler

import android.util.Log
import com.algorigo.logger.Level
import com.algorigo.logger.formatter.FastPrintWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * This is clone of com.android.internal.logging.AndroidHandler but little modified.
 */
class AndroidHandler(level: Level = Level.DEBUG) : Handler() {
    /**
     * Constructs a new instance of the Android log handler.
     */
    init {
        formatter = THE_FORMATTER
        this.level = level.level
    }

    override fun close() {
        // No need to close, but must implement abstract method.
    }

    override fun flush() {
        // No need to flush, but must implement abstract method.
    }

    override fun publish(record: LogRecord?) {
        if (record == null) {
            return
        }

        val tag = loggerNameToTag(record.loggerName)
        try {
            val message: String = formatter.format(record)
            val value: Int = record.level.intValue()
            when {
                value >= Level.ASSERTS.level.intValue() -> {
                    Log.println(Log.ASSERT, tag, message)
                }
                value >= Level.ERROR.level.intValue() -> {
                    Log.e(tag, message)
                }
                value >= Level.WARNING.level.intValue() -> {
                    Log.w(tag, message)
                }
                value >= Level.INFO.level.intValue() -> {
                    Log.i(tag, message)
                }
                value >= Level.DEBUG.level.intValue() -> {
                    Log.d(tag, message)
                }
                else -> {
                    Log.v(tag, message)
                }
            }
        } catch (e: RuntimeException) {
            Log.e("AndroidHandler", "Error logging message.", e)
        }
    }

    fun publish(source: Logger?, tag: String?, level: Level, message: String?) {
        // TODO: avoid ducking into native 2x; we aren't saving any formatter calls
        try {
            val value: Int = level.level.intValue()
            when {
                value >= Level.ASSERTS.level.intValue() -> {
                    Log.wtf(tag, message)
                }
                value >= Level.ERROR.level.intValue() -> {
                    Log.e(tag, message ?: "")
                }
                value >= Level.WARNING.level.intValue() -> {
                    Log.w(tag, message ?: "")
                }
                value >= Level.INFO.level.intValue() -> {
                    Log.i(tag, message ?: "")
                }
                value >= Level.DEBUG.level.intValue() -> {
                    Log.d(tag, message ?: "")
                }
                else -> {
                    Log.v(tag, message ?: "")
                }
            }
        } catch (e: RuntimeException) {
            Log.e("AndroidHandler", "Error logging message.", e)
        }
    }

    companion object {
        /**
         * Holds the formatter for all Android log handlers.
         */
        private val THE_FORMATTER: Formatter = object : Formatter() {
            override fun format(r: LogRecord?): String {
                return r?.let {
                    if (it.thrown != null) {
                        val sw = StringWriter()
                        val pw: PrintWriter = FastPrintWriter(sw, false, 256)
                        sw.write(it.message)
                        sw.write("\n")
                        it.thrown.printStackTrace(pw)
                        pw.flush()
                        sw.toString()
                    } else {
                        it.message
                    }
                } ?: ""
            }
        }

        /**
         * Returns the short logger tag (up to 23 chars) for the given logger name.
         * Traditionally loggers are named by fully-qualified Java classes; this
         * method attempts to return a concise identifying part of such names.
         */
        private fun loggerNameToTag(loggerName: String?): String {
            // Anonymous logger.
            if (loggerName == null) {
                return "null"
            }
            val length = loggerName.length
            if (length <= 23) {
                return loggerName
            }
            var firstPeriod = loggerName.indexOf(".")
            val lastPeriod = loggerName.lastIndexOf(".")
            return if (firstPeriod == -1) {
                loggerName.substring(loggerName.length - 23)
            } else if (firstPeriod == lastPeriod) {
                loggerName.substring(0, 23)
            } else {
                val splitted = loggerName.split(".").toMutableList()
                for (index in 0 until splitted.size) {
                    splitted[index] = splitted[index].substring(0, 1)
                    val merged = splitted.joinToString(".")
                    if (merged.length <= 23) {
                        return merged
                    }
                }
                return if (length - (lastPeriod + 1) <= 23) loggerName.substring(lastPeriod + 1) else loggerName.substring(
                    loggerName.length - 23
                )
            }
        }
    }
}