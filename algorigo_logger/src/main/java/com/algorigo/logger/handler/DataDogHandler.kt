package com.algorigo.logger.handler

import android.util.Log
import com.algorigo.logger.DataDogLogDelegate
import com.algorigo.logger.Level
import com.algorigo.logger.LogManager
import com.algorigo.logger.formatter.FastPrintWriter
import com.algorigo.logger.loggingLevelToIntValue
import com.datadog.android.Datadog
import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.LogRecord

class DataDogHandler(level: Level = Level.DEBUG) : Handler() {

    private val dataDogLogDelegate: DataDogLogDelegate

    init {
        formatter = THE_FORMATTER
        this.level = level.level
        if (!Datadog.isInitialized()) {
            throw IllegalStateException("Datadog is not initialized")
        }

        dataDogLogDelegate = LogManager.getDelegate(DataDogLogDelegate::class.java)
            ?: throw IllegalStateException("DataDogLogDelegate is not initialized. Add DataDogLogDelegate to LogManager")
    }

    override fun publish(p0: LogRecord?) {
        p0?.also { logRecord ->
            if (!isLoggable(logRecord)) {
                return
            }
            dataDogLogDelegate.getLogger(logRecord.loggerName)
                ?.log(logRecord.level?.loggingLevelToIntValue() ?: Log.DEBUG, logRecord.message)
        }
    }

    override fun flush() {

    }

    override fun close() {

    }

    companion object {
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
    }
}
