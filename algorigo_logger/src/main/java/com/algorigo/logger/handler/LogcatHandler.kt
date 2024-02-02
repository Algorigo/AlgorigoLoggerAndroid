package com.algorigo.logger.handler

import android.util.Log
import com.algorigo.logger.Level
import com.algorigo.logger.formatter.TimelessLogFormatter
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.LogRecord

class LogcatHandler(
    formatter: Formatter? = null,
    level: Level = Level.DEBUG,
) : Handler() {
    init {
        setFormatter(formatter ?: TimelessLogFormatter())
        setLevel(level.level)
        flush()
    }

    override fun publish(record: LogRecord?) {
        record?.level?.intValue()?.also {
            when {
                it >= Level.ASSERTS.level.intValue() -> {
                    Log.e(record.loggerName, formatter.format(record))
                }
                it >= Level.ERROR.level.intValue() -> {
                    Log.e(record.loggerName, formatter.format(record))
                }
                it >= Level.WARNING.level.intValue() -> {
                    Log.w(record.loggerName, formatter.format(record))
                }
                it >= Level.INFO.level.intValue() -> {
                    Log.i(record.loggerName, formatter.format(record))
                }
                it >= Level.DEBUG.level.intValue() -> {
                    Log.d(record.loggerName, formatter.format(record))
                }
                else -> {
                    Log.v(record.loggerName, formatter.format(record))
                }
            }
        }
        flush()
    }

    override fun flush() {

    }

    override fun close() {

    }
}